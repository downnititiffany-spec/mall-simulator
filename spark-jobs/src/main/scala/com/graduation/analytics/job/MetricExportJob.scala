package com.graduation.analytics.job

import com.graduation.analytics.metric.MetricAdsSpec
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.col

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

/**
 * MetricExportJob（code=mxp）—— R7-3（V2.0 §17.4/§17.5）指标发布链路的**读取侧**：
 * 把本次已发布的 8 张 Hive 正式 ADS 分区导出为"发布导出文件"（每表一个 JSONL + 一份 `_export.json` 清单），
 * 交给 Java 侧 `MetricPublisher` 批量写入 analytics_metric 并做对账/激活。
 *
 * 为什么"导出文件"而不是 Spark 直连 MySQL：发布事务、快照 ACTIVE 切换、失败保留旧快照必须
 * 落在**平台侧的指标库发布事务**里（§17.5），因此 Spark 只负责读 Hive 并给出真实行数/清单，
 * **不接触任何数据库凭据**（整改书 §8.1：凭据不入作业参数）。
 *
 * 硬约束（缺一即 FAILED，不允许"看起来成功"）：
 *   ① 快照钉住：正式分区的 Hive Location 必须指向 `snapshot_id=<本次快照>` 的路径，
 *      否则说明发布指针还停在旧快照 → 拒绝导出（防把上一版数据当本次发布）；
 *   ② 导出文件真实回读行数必须等于 Hive 分区真实 `COUNT(*)`（逐表校验）；
 *   ③ JSONL 必须保留 null 字段：发布侧批量写入要求同一批列集一致（`ignoreNullFields=false`）。
 */
class MetricExportJob extends WarehouseJob {
  override val code: String = "mxp"
  override val description: String = "Hive 正式 ADS → 发布导出文件（JSONL + 清单），供指标库发布读取"

  override def validate(args: JobArgs): Either[String, Unit] = {
    if (args.outputSnapshotId.forall(_.isBlank)) return Left("mxp 需要 --outputSnapshotId（只导出本次快照）")
    if (!args.extra.get("exportDir").exists(_.nonEmpty)) return Left("mxp 需要 --exportDir（导出目录）")
    Right(())
  }

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val dt = args.businessDate
    val sid = args.outputSnapshotId.get
    val exportDir = args.extra("exportDir").stripSuffix("/")
    val checks = ListBuffer.empty[QualityCheck]

    // null 字段必须出现在 JSON 里（默认 true 会省略 null → 各行键集不一致 → 发布侧拒绝批量写入）
    spark.conf.set("spark.sql.jsonGenerator.ignoreNullFields", "false")

    val formalTables = MetricAdsSpec.TABLES.map(_.hiveTable)
    val evidence = PartitionEvidence.collect(spark, formalTables, None, Some(dt))
    val pathOf = evidence.map(p => p.table -> p.path).toMap
    val countOf = evidence.map(p => p.table -> p.rowCount).toMap

    // ── 规则 1：发布指针必须指向本次快照（否则导出的可能是上一版数据） ──
    val unpinned = formalTables.filter(t => pathOf.get(t).flatten.forall(p => !p.contains(s"snapshot_id=$sid")))
    checks += QualityCheck("MXP_SNAPSHOT_PINNED", "PUBLISH", formalTables.mkString(","),
      formalTables.size, unpinned.size, s"正式分区 Location 指向 snapshot_id=$sid", "BLOCKING",
      unpinned.isEmpty,
      if (unpinned.isEmpty) s"8 张正式分区均指向 snapshot_id=$sid"
      else s"未指向本次快照: ${unpinned.map(t => s"$t=${pathOf.get(t).flatten.getOrElse("<无路径>")}").mkString(",")}")
    if (unpinned.nonEmpty) {
      return JobResult.failed(code, args.attemptNo,
        s"快照未发布/未指向本次快照，拒绝导出: ${unpinned.mkString(",")}",
        System.currentTimeMillis() - start, evidence, checks.toList)
    }

    // ── 逐表导出 + 回读校验 ──
    val manifestRows = ListBuffer.empty[String]
    var totalRows = 0L
    MetricAdsSpec.TABLES.foreach { spec =>
      val loc = pathOf(spec.hiveTable).get
      val hiveRows = countOf.getOrElse(spec.hiveTable, -1L)
      val df = spark.read.parquet(loc).select(spec.columns.map(col): _*)
      val exportFile = s"$exportDir/${spec.mysqlTable}.jsonl"
      MetricExportJob.writeJsonl(spark, df, exportFile)
      val written = MetricExportJob.countLines(spark, exportFile)
      checks += QualityCheck("MXP_EXPORT_ROWS", "PUBLISH", spec.mysqlTable, hiveRows, if (written == hiveRows) 0L else 1L,
        "导出文件行数 = Hive 分区行数", "BLOCKING", written == hiveRows,
        s"${spec.mysqlTable}: hive=$hiveRows export=$written")
      totalRows += written
      manifestRows +=
        s"""    {"hiveTable":"${spec.hiveTable}","mysqlTable":"${spec.mysqlTable}","rowCount":$written,""" +
          s""""columns":[${spec.columns.map(c => s""""$c"""").mkString(",")}],""" +
          s""""hivePath":"$loc","exportFile":"${MetricExportJob.posix(exportFile)}"}"""
    }

    val exportFailed = checks.exists(c => c.severity == "BLOCKING" && !c.passed)
    checks += QualityCheck("MXP_EXPORT_COMPLETE", "PUBLISH", MetricAdsSpec.TABLES.map(_.mysqlTable).mkString(","),
      MetricAdsSpec.TABLES.size, checks.count(c => c.severity == "BLOCKING" && !c.passed),
      "8 张表导出完成且行数一致", "BLOCKING", !exportFailed,
      s"合计导出 $totalRows 行 / ${MetricAdsSpec.TABLES.size} 张表")

    val manifest =
      s"""{
         |  "snapshotId": "$sid",
         |  "businessDate": "$dt",
         |  "dt": "$dt",
         |  "source": "spark-ads",
         |  "generatedAt": "${java.time.LocalDateTime.now()}",
         |  "totalRows": $totalRows,
         |  "tables": [
         |${manifestRows.mkString(",\n")}
         |  ]
         |}
         |""".stripMargin
    MetricExportJob.writeText(spark, s"$exportDir/${MetricAdsSpec.EXPORT_MANIFEST}", manifest)

    val elapsed = System.currentTimeMillis() - start
    if (exportFailed) {
      JobResult.failed(code, args.attemptNo,
        s"ADS 导出校验失败: ${checks.filter(c => c.severity == "BLOCKING" && !c.passed).map(_.ruleCode).mkString(",")}",
        elapsed, evidence, checks.toList)
    } else {
      JobResult.success(code, totalRows, totalRows, 0L, Some(sid), args.attemptNo, elapsed, evidence, checks.toList)
    }
  }
}

object MetricExportJob {
  val instance: MetricExportJob = new MetricExportJob()

  /**
   * 清单里的路径统一写成正斜杠形式（`D:/x/y.jsonl`）。
   *
   * <p>Windows 反斜杠在 JSON 里是转义前缀（`"D:\Develop"` 直接非法，发布侧 Jackson 报
   * `Unrecognized character escape 'D'`）；同时正斜杠形式在 Linux 与 Java `Path.of` 下同样有效，
   * 因此清单只认这一种写法（唯一口径，不做双写兼容）。</p>
   */
  def posix(path: String): String = path.replace('\\', '/')

  /**
   * 把 DataFrame 写成**单个** JSONL 文件（UTF-8，含 null 字段）。
   *
   * <p>`df.write.text(path)` 会把 path 当**目录**（产出 `part-00000`），而发布侧契约是
   * "清单里 `exportFile` 指向一个真实文件"（`Files.isRegularFile` + 逐行读），所以这里
   * 先落到临时目录，再把所有 part 文件按名顺序拼成目标文件，最后删掉临时目录。
   * 不做 `coalesce(1)`：拼接不改变并行度，也不需要数据全落一个 executor。</p>
   */
  def writeJsonl(spark: SparkSession, df: org.apache.spark.sql.DataFrame, targetFile: String): Unit = {
    val tmpDir = new Path(targetFile + ".parts")
    val target = new Path(targetFile)
    val fs = target.getFileSystem(spark.sparkContext.hadoopConfiguration)
    // 幂等清理：导出作业拥有 `targetFile` 与 `targetFile.parts` 两个路径，重试必须能覆盖上一次
    // 失败留下的**任何形态**。真机事故（run 21/22 attempt 3）：旧实现用 `text(targetFile)` 直接写，
    // 目标路径是**非空目录**；这里的 `fs.delete(target, false)` 非递归删除对非空目录抛
    // `Directory ...jsonl is not empty` → 导出作业一旦重试就必然失败（PUBLISH_METRIC FAILED）。
    // 故统一递归清理后再写临时目录、拼成单文件。
    if (fs.exists(tmpDir)) fs.delete(tmpDir, true)
    if (fs.exists(target)) fs.delete(target, true)
    df.toJSON.write.mode("overwrite").text(tmpDir.toString)
    val out = fs.create(target, true)
    try {
      val parts = fs.listStatus(tmpDir)
        .filter(st => st.isFile && st.getPath.getName.startsWith("part-"))
        .sortBy(_.getPath.getName)
      parts.foreach { st =>
        val in = fs.open(st.getPath)
        try {
          val buf = new Array[Byte](64 * 1024)
          var n = in.read(buf)
          while (n > 0) {
            out.write(buf, 0, n)
            n = in.read(buf)
          }
        } finally in.close()
      }
    } finally out.close()
    fs.delete(tmpDir, true)
  }

  /** 真实回读导出目录的行数（不是"应该写了多少"的估算） */
  def countLines(spark: SparkSession, dir: String): Long = {    val path = new Path(dir)
    val fs = path.getFileSystem(spark.sparkContext.hadoopConfiguration)
    if (!fs.exists(path)) return 0L
    var total = 0L
    val files = fs.listStatus(path).filter(s => s.isFile && !s.getPath.getName.startsWith("_"))
    files.foreach { st =>
      val in = new BufferedReader(new InputStreamReader(fs.open(st.getPath), StandardCharsets.UTF_8))
      try {
        var line = in.readLine()
        while (line != null) {
          if (line.trim.nonEmpty) total += 1
          line = in.readLine()
        }
      } finally in.close()
    }
    total
  }

  def writeText(spark: SparkSession, file: String, content: String): Unit = {
    val path = new Path(file)
    val fs = path.getFileSystem(spark.sparkContext.hadoopConfiguration)
    val out = fs.create(path, true)
    try out.write(content.getBytes(StandardCharsets.UTF_8)) finally out.close()
  }
}
