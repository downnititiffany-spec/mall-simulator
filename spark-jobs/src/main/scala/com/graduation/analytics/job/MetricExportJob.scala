package com.graduation.analytics.job

import com.graduation.analytics.metric.MetricAdsSpec
import com.graduation.analytics.warehouse.WarehouseNamespace
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
 *
 * S3-06 起清单每表另带 `checksum`（该 JSONL 全部原始字节的 CRC32，`Long.toHexString` 形式）：
 * 行数相等挡不住"内容被截断/错位搬运"，发布侧 `MetricPublishValidator` 的 `MP_EXPORT_CHECKSUM`
 * 会逐表重算比对（§12.5 L528/L529、指导书 §7 阶段 3 L151）。
 * 注意本作业**只产出摘要、不自行判定**自己是否可信 —— 判定权在发布侧唯一所有者。
 */
class MetricExportJob extends WarehouseJob {
  override val code: String = "mxp"
  override val description: String = "Hive 正式 ADS → 发布导出文件（JSONL + 清单），供指标库发布读取"

  override def validate(args: JobArgs): Either[String, Unit] = {
    if (args.outputSnapshotId.forall(_.trim.isEmpty)) return Left("mxp 需要 --outputSnapshotId（只导出本次快照）")
    if (!args.extra.get("exportDir").exists(_.nonEmpty)) return Left("mxp 需要 --exportDir（导出目录）")
    Right(())
  }

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val dt = args.businessDate
    val ns = WarehouseNamespace.fromArgs(args)
    val sid = args.outputSnapshotId.get
    val exportDir = args.extra("exportDir").stripSuffix("/")
    val checks = ListBuffer.empty[QualityCheck]

    // null 字段必须出现在 JSON 里（默认 true 会省略 null → 各行键集不一致 → 发布侧拒绝批量写入）
    spark.conf.set("spark.sql.jsonGenerator.ignoreNullFields", "false")

    val formalTables = MetricAdsSpec.TABLES.map(_.hiveTable(ns))
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
      val loc = pathOf(spec.hiveTable(ns)).get
      val hiveRows = countOf.getOrElse(spec.hiveTable(ns), -1L)
      val df =
        if (hiveRows == 0L) {
          // 合法空态（T-R1 实证缺口）：上面的 collect 已用 metastore 的 COUNT(*) 证实该分区存在且
          // 真实 0 行 —— 当天无行为事实时 fna 写出的暂存目录只有 _SUCCESS、无 parquet 数据文件，
          // `spark.read.parquet(loc)` 的文件级 schema 推断必然抛 UNABLE_TO_INFER_SCHEMA
          // （T-R1 PUBLISH_METRIC 即因此 FAILED，见 BATCH-T-R1-…-RESULT.md §4）。
          // 此时从 catalog 侧表 schema 构造 0 行 DataFrame（limit(0) 不做文件读取），列序仍与
          // 契约投影一致，导出走既有空文件路径：真实空 JSONL + rowCount=0 + checksum="0"。
          // 分区缺失（-1）/非 0 行一律走下面的物理读取，缺路径、schema 损坏照旧 fail-closed，
          // 不允许把任何读取失败扩大成"按空表处理"。
          spark.table(spec.hiveTable(ns)).select(spec.columns.map(col): _*).limit(0)
        } else {
          spark.read.parquet(loc).select(spec.columns.map(col): _*)
        }
      val exportFile = s"$exportDir/${spec.mysqlTable}.jsonl"
      MetricExportJob.writeJsonl(spark, df, exportFile)
      val written = MetricExportJob.countLines(spark, exportFile)
      // 内容摘要：随清单一起交付，发布侧（MetricPublishValidator 的 MP_EXPORT_CHECKSUM）重算比对。
      // 只核行数时，行数相同但内容被截断/错位搬运的制品在发布侧没有判据（§12.5 L528/L529）。
      val checksum = MetricExportJob.crc32(spark, exportFile)
      checks += QualityCheck("MXP_EXPORT_ROWS", "PUBLISH", spec.mysqlTable, hiveRows, if (written == hiveRows) 0L else 1L,
        "导出文件行数 = Hive 分区行数", "BLOCKING", written == hiveRows,
        s"${spec.mysqlTable}: hive=$hiveRows export=$written")
      totalRows += written
      manifestRows +=
        s"""    {"hiveTable":"${spec.hiveTable(ns)}","mysqlTable":"${spec.mysqlTable}","rowCount":$written,""" +
          s""""columns":[${spec.columns.map(c => s""""$c"""").mkString(",")}],""" +
          s""""hivePath":"$loc","exportFile":"${MetricExportJob.posix(exportFile)}","checksum":"$checksum"}"""
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

  /**
   * 导出制品的**内容摘要**：CRC32 作用于该文件全部原始字节，输出 `Long.toHexString` 形式
   * （小写十六进制、无前导零、空文件为 `"0"`）。
   *
   * <p>依据：设计 §12.5 L528 的发布顺序要求 `mxp` 导出随清单给出 checksum，L529 的 MySQL 暂存
   * 验证含「内容验证」；指导书 §7 阶段 3 L151 要求「导出 ADS 制品核 schema/行数/checksum」。
   * 只有行数（`MXP_EXPORT_ROWS` / `MP_ADS_ROWS_MATCH`）时，行数相同但内容被截断或改写的制品
   * 在发布侧没有任何判据（`docs/audit/v2-completeness-audit.md:213` 已登记该缺口）。</p>
   *
   * <p>口径唯一性：与 landing 侧 `ingestion-manifest.v1` 的 checksum **同一写法**
   * （`IngestionService` 用 `Long.toHexString(CRC32.getValue())`），因此同一制品在
   * landing 清单与 `mxp` 清单里的摘要形式不会分裂成两套。</p>
   *
   * <p>为什么是 CRC32 而不是 SHA-256：本摘要的用途是**同一文件在 Spark 写出与 Java 读入之间的
   * 一致性核对**（防截断/防错位搬运），不是抗篡改的密码学承诺；与既有 ingestion 清单保持同口径
   * 比另立一套更强的哈希更重要（后者会让同一制品在两条链路上有两个"校验和"概念）。</p>
   *
   * <p>实现要点：必须**流式读满整个文件**（复用 `writeJsonl` 的字节缓冲方式），
   * 不能只读首个缓冲区；文件不存在即抛错（缺失制品不该得到一个"合法摘要"）。</p>
   */
  def crc32(spark: SparkSession, file: String): String = {
    val path = new Path(file)
    val fs = path.getFileSystem(spark.sparkContext.hadoopConfiguration)
    if (!fs.exists(path)) {
      throw new java.io.FileNotFoundException(s"导出制品不存在，无法计算摘要：$file")
    }
    val crc = new java.util.zip.CRC32()
    val in = fs.open(path)
    try {
      val buf = new Array[Byte](64 * 1024)
      var n = in.read(buf)
      while (n > 0) {
        crc.update(buf, 0, n)
        n = in.read(buf)
      }
    } finally in.close()
    java.lang.Long.toHexString(crc.getValue)
  }

  def writeText(spark: SparkSession, file: String, content: String): Unit = {
    val path = new Path(file)
    val fs = path.getFileSystem(spark.sparkContext.hadoopConfiguration)
    val out = fs.create(path, true)
    try out.write(content.getBytes(StandardCharsets.UTF_8)) finally out.close()
  }
}
