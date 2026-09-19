package com.graduation.analytics

import com.fasterxml.jackson.databind.ObjectMapper
import com.graduation.analytics.job.{JobArgs, JobRegistry, JobResult, LocalSchemaInitJob, PartitionEvidence, WarehouseJob}
import com.graduation.analytics.metric.MetricAdsSpec
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.zip.CRC32
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

/**
 * S7-T-R2 前置（developer test）：mxp 对「已发布但真实 0 行」的 ADS 正式分区的导出行为。
 *
 * 背景（T-R1 生产 FAIL 的根因，见 `docs/verification/results/BATCH-T-R1-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md` §4）：
 * v2 质量规则（D-019）让「分区存在且 Location 可读但 0 行」的合法空态第一次走到发布链路，
 * `MetricExportJob` 随后在 `spark.read.parquet(loc)` 上抛 UNABLE_TO_INFER_SCHEMA ——
 * fna 对 0 行静态分区只写 `_SUCCESS`、不写 parquet 数据文件，文件级 schema 推断无从谈起。
 *
 * 修复口径（总控裁决 D-020）：仅当 `PartitionEvidence` 已用 metastore `COUNT(*)` 证实
 * 「分区存在 + 真实 0 行」（`hiveRows==0`）时，从 catalog 侧表 schema 构造 0 行 DataFrame
 * （`spark.table(...).select(...).limit(0)`，不做文件读取）；非 0 行与分区缺失（-1）保持
 * `spark.read.parquet(loc)` 物理读取，缺路径/schema 损坏照旧 fail-closed。
 * 本修复没有新增任何 catch —— 不把任何读取失败扩大成「按空表处理」。
 *
 * 本套件在 P2TestSupport 的隔离 warehouse + in-memory catalog 里铺 8 张暂存分区
 * （6 张各 1 行 + ads_hot_product/ads_product_conversion 2 张 0 行，与 T-R1 的
 * MALL_API 纯交易源同型），走**真实** `pub`（元数据指针切换）与真实 `mxp`，断言：
 *   ① 0 行表导出为真实存在的 0 字节 JSONL，manifest rowCount=0、checksum="0"（合法空文件摘要）；
 *   ② 非 0 行 6 张表照常导出（行数/列契约/checksum 独立重算逐一核对）——不回归判据；
 *   ③ manifest 的 columns 与 `MetricAdsSpec` 契约逐列一致，8 表齐全、totalRows 为逐表之和；
 *   ④ 负向对照：0 行分区的物理目录确实无法做文件级 schema 推断 —— 证明空态分支是被测路径
 *      （若有人回退修复，mxp 将重新在此炸掉，本套件会红）。
 *
 * 边界（沿用 P2TestSupport 自陈）：in-memory catalog，「测试通过 ≠ 在产 Hive metastore 通过」；
 * 在产形态由 Batch T-R2 真实验证门复核。0 行静态 INSERT 在内存目录的落盘形态以实测为准，
 * 若与在产 Hive 的 T-R1 形态（分区已注册 + `_SUCCESS`-only 目录）有差异，setup 显式补齐并记录。
 */
class MetricExportZeroRowSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  import MetricExportZeroRowSpec._

  private var spark: SparkSession = _
  private var cap: Capture = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark(SuiteName)
    cap = try collectAll(spark) catch {
      case t: Throwable =>
        println(s"[mxp0] 采集整体失败：${t.getClass.getName}: ${t.getMessage}")
        t.getStackTrace.take(15).foreach(e => println(s"[mxp0]   at $e"))
        Capture.crashed(s"${t.getClass.getName}: ${t.getMessage}")
    }
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ══════════════════════════════════════════════════════════════════════
  // pub：0 行暂存分区是合法就绪态
  // ══════════════════════════════════════════════════════════════════════

  "pub 接受 0 行暂存分区（v2 就绪语义）并完成元数据指针切换" should "PUB_STAGING_READY 通过且点名 0 行专题" in {
    withClue(s"采集状态：${cap.crash.getOrElse("ok")}；pub=${cap.pub.map(_.status).getOrElse("<未执行>")}：") {
      cap.crash should be(None)
      val pub = cap.pub.getOrElse(fail("pub 未执行"))
      pub.status should be("SUCCESS")
      val ready = pub.checks.find(_.ruleCode == "PUB_STAGING_READY").getOrElse(fail("pub 无 PUB_STAGING_READY 检查"))
      ready.passed should be(true)
      withClue(s"PUB_STAGING_READY detail=${ready.detail}：") {
        ready.detail should include("0 行专题")
        ZeroRowTables.foreach(t => ready.detail should include(t))
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // mxp：整体成功 + 0 行走 catalog-backed 空态分支
  // ══════════════════════════════════════════════════════════════════════

  "mxp 对含 0 行专题的 8 表导出整体 SUCCESS（修复前此处抛 UNABLE_TO_INFER_SCHEMA）" should
    "SNAPSHOT_PINNED / EXPORT_ROWS / EXPORT_COMPLETE 全部通过" in {
      val mxp = cap.mxp.getOrElse(fail("mxp 未执行"))
      withClue(s"mxp=${mxp.status}: ${mxp.message}；checks=${mxp.checks.map(c => s"${c.ruleCode}|${c.targetTable}|${c.passed}").mkString(" ;; ")}：") {
        mxp.status should be("SUCCESS")
        mxp.checks.find(_.ruleCode == "MXP_SNAPSHOT_PINNED").get.passed should be(true)
        val rows = mxp.checks.filter(_.ruleCode == "MXP_EXPORT_ROWS")
        rows should have size 8
        rows.foreach(c => withClue(s"${c.targetTable} detail=${c.detail}：") { c.passed should be(true) })
        mxp.checks.find(_.ruleCode == "MXP_EXPORT_COMPLETE").get.passed should be(true)
        // outputRecords 是真实回读行数之和（不是输入数冒充）
        mxp.outputRecords should be(RealRowTables.size.toLong)
      }
    }

  it should "0 行表的 MXP_EXPORT_ROWS 为 hive=0 export=0（合法空态对账，不是跳过）" in {
    val mxp = cap.mxp.getOrElse(fail("mxp 未执行"))
    val rows = mxp.checks.filter(c => c.ruleCode == "MXP_EXPORT_ROWS" && ZeroRowMysql.contains(c.targetTable))
    rows should have size ZeroRowTables.size
    rows.foreach { c =>
      withClue(s"${c.targetTable} detail=${c.detail}：") {
        c.detail should include("hive=0")
        c.detail should include("export=0")
      }
    }
  }

  "负向对照：0 行分区物理目录无法做文件级 schema 推断（证明空态分支是被测路径）" should
    "spark.read.parquet(0 行分区 Location) 抛 UNABLE_TO_INFER_SCHEMA" in {
      ZeroRowTables.foreach { t =>
        val loc = cap.emptyLocations.getOrElse(t, fail(s"$t 的 0 行分区 Location 未采集到"))
        Try(spark.read.parquet(loc)) match {
          case Failure(e) =>
            withClue(s"$t @ $loc：${e.getClass.getName}: ${e.getMessage}：") {
              e.getMessage should include("Unable to infer schema")
            }
          case Success(_) =>
            fail(s"$t 的物理目录 $loc 竟可被文件级 schema 推断 —— 夹具未复现 T-R1 的 _SUCCESS-only 形态")
        }
      }
    }

  // ══════════════════════════════════════════════════════════════════════
  // 导出制品：0 行 = 真实空文件；非 0 行不回归
  // ══════════════════════════════════════════════════════════════════════

  "0 行表导出制品" should "是真实存在的 0 字节 JSONL，manifest rowCount=0、checksum=\"0\"" in {
    val manifest = cap.manifest
    // manifest 键是 mysqlTable（ads_hot_product_m），不是 Hive 侧表名
    MetricAdsSpec.TABLES.filter(s => ZeroRowTables.contains(s.table)).foreach { spec =>
      val node = manifest.table(spec.mysqlTable)
      node.get("rowCount").asLong() should be(0L)
      node.get("checksum").asText() should be("0") // 空文件的合法摘要（与 MetricExportChecksumSpec 同一实测口径）
      val file = nioPath(node.get("exportFile").asText())
      withClue(s"${spec.table}(${spec.mysqlTable}) 导出文件 $file：") {
        Files.isRegularFile(file) should be(true)
        Files.size(file) should be(0L)
      }
    }
  }

  "非 0 行 6 张表照常导出（不回归判据）" should "文件行数、列契约、checksum（独立重算）逐一一致" in {
    val manifest = cap.manifest
    val nonEmpty = MetricAdsSpec.TABLES.filterNot(s => ZeroRowTables.contains(s.table))
    nonEmpty should have size 6
    nonEmpty.foreach { spec =>
      val node = manifest.table(spec.mysqlTable)
      val file = nioPath(node.get("exportFile").asText())
      withClue(s"${spec.mysqlTable}.jsonl @ $file：") {
        Files.isRegularFile(file) should be(true)
        fileLines(file) should be(1L)
        node.get("rowCount").asLong() should be(1L)
        // 列契约：manifest columns 与 MetricAdsSpec 逐列逐序一致（发布侧建表/插入列的唯一事实来源）
        node.get("columns").elements().asScala.map(_.asText()).toList should be(spec.columns.toList)
        // checksum 独立重算（JDK 读文件全部字节，不复用导出作业的 Hadoop 流式实现）
        val crc = new CRC32()
        crc.update(Files.readAllBytes(file))
        node.get("checksum").asText() should be(java.lang.Long.toHexString(crc.getValue))
        node.get("checksum").asText() should not be "0"
      }
    }
  }

  it should "导出行内容与铺入的暂存行一致（JSONL 含 null 字段键集，发布侧可批量写入）" in {
    val manifest = cap.manifest
    val overview = manifest.table("ads_operation_overview_m")
    val file = nioPath(overview.get("exportFile").asText())
    val line = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim
    withClue(s"ads_operation_overview_m 首行=$line：") {
      line should include("\"pv\":1")
      line should include("\"repeat_period_start\":\"20260911\"")
    }
  }

  "manifest 总账（§12.5 L528）" should "8 表齐全、snapshotId/dt 正确、totalRows 为逐表之和、hivePath 指向本次快照" in {
    val manifest = cap.manifest
    manifest.snapshotId should be(Sid)
    manifest.dt should be(Dt)
    manifest.totalRows should be(RealRowTables.size.toLong)
    manifest.allTableNames should be(MetricAdsSpec.TABLES.map(_.mysqlTable).toSet)
    manifest.allTableNames.foreach { t =>
      manifest.table(t).get("hivePath").asText() should include(s"snapshot_id=$Sid")
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 夹具与采集
  // ══════════════════════════════════════════════════════════════════════

  /** manifest 的结构化视图（Jackson 读树，不重造解析器） */
  final case class ManifestView(raw: String) {
    private val root = new ObjectMapper().readTree(raw)

    def snapshotId: String = root.get("snapshotId").asText()
    def dt: String = root.get("dt").asText()
    def totalRows: Long = root.get("totalRows").asLong()

    def allTableNames: Set[String] =
      root.get("tables").elements().asScala.map(_.get("mysqlTable").asText()).toSet

    def table(mysqlTable: String): com.fasterxml.jackson.databind.JsonNode =
      root.get("tables").elements().asScala.find(_.get("mysqlTable").asText() == mysqlTable)
        .getOrElse(throw new IllegalStateException(s"manifest 缺 $mysqlTable 表项"))
  }

  final case class Capture(
    crash: Option[String],
    notes: Seq[String],
    pub: Option[JobResult],
    mxp: Option[JobResult],
    emptyLocations: Map[String, String],
    manifestRaw: String) {

    def manifest: ManifestView =
      if (manifestRaw.isEmpty) throw new IllegalStateException("_export.json 未采集到")
      else ManifestView(manifestRaw)
  }

  object Capture {
    def crashed(msg: String): Capture = Capture(Some(msg), Seq.empty, None, None, Map.empty, "")
  }

  private def collectAll(spark: SparkSession): Capture = {
    val notes = ListBuffer.empty[String]
    def note(s: String): Unit = { notes += s; println(s"[mxp0][note] $s") }

    val warehouseDir = spark.conf.get("spark.sql.warehouse.dir")
    // conf 读回的是 URI（`file:/D:/…`）；作业侧统一用 Hadoop Path（认 URI），这里只传字符串
    val exportDir = new Path(new Path(warehouseDir), "mxp-zero-export").toString
    note(s"namespace=${Ns.prefix} sid=$Sid dt=$Dt exportDir=$exportDir")

    // ── sci：与既有 spec 相同的建表路径（不自造 DDL 副本）──
    LocalSchemaInitJob.statements(Ns).foreach { case (_, ddl) => spark.sql(ddl) }

    // ── 铺 8 张暂存分区：6 张各 1 行 + 2 张 0 行（T-R1 的 MALL_API 纯交易源同型：
    //    无 behavior 事实 → ads_hot_product / ads_product_conversion 当天 0 行）──
    val rows: Seq[(String, String)] = Seq(
      "ads_operation_overview" ->
        "SELECT 1, 1, 1, 1, 1.00, 0.50, 1.00, 0.1000, 0.0500, 0.2000, '20260911', '20260917', 2, 3",
      "ads_sale_trend" -> "SELECT 1, 1, 1.00, 1.00, 0.50",
      "ads_behavior_funnel" -> "SELECT 'view', 1, 1.0000, 0.5000, 0.5000",
      "ads_active_trend" -> "SELECT 1, 5",
      "ads_user_profile" ->
        "SELECT 1, 3, 2, 100, 'high', 'active', 1, '20260917', '20260917', 'active', 1, '20260918', 3, 2, 100.00, '20260911', '20260917'",
      "ads_data_quality" -> "SELECT 'DQC_ODS_ENUM', 10, 0, 0.000000, 1, '0.01', 1",
      // 0 行来源用 FROM (SELECT 1) dual 而不是裸 SELECT..WHERE（后者对 Spark 版本敏感；
      // 语义等价：1 行来源被 1=0 过滤成 0 行，静态分区 INSERT 照常执行）
      "ads_hot_product" ->
        "SELECT 1, 'x', 1.0000, 1, 1, 1, 1, 1, 'v1' FROM (SELECT 1) dual WHERE 1 = 0",
      "ads_product_conversion" ->
        "SELECT 1, 1, 1, 1.0000 FROM (SELECT 1) dual WHERE 1 = 0")
    rows.foreach { case (t, sel) =>
      spark.sql(s"INSERT INTO ${Ns.ads}.${t}__staging PARTITION (snapshot_id = '$Sid', dt = '$Dt') $sel")
    }

    // ── 0 行分区的内存目录形态以实测为准：静态 INSERT 未注册/未落目录时（内存目录与在产
    //    Hive metastore 的边界差异），显式补齐到 T-R1 实测形态（分区已注册 + `_SUCCESS`-only
    //    目录），保证被测物理形态与生产一致；补齐路径记录在 notes。 ──
    val emptyLocations = ZeroRowTables.map { t =>
      val stg = s"${Ns.ads}.${t}__staging"
      var parts = PartitionEvidence.collect(spark, Seq(stg), Some(Sid), Some(Dt))
      if (parts.isEmpty) {
        note(s"$stg：0 行静态 INSERT 未在内存目录注册分区，显式补建（T-R1 物理形态）")
        val partDir = new Path(new Path(new Path(warehouseDir), s"${Ns.ads}.db/${t}__staging"),
          s"snapshot_id=$Sid/dt=$Dt")
        val fs = partDir.getFileSystem(spark.sparkContext.hadoopConfiguration)
        fs.mkdirs(partDir)
        touchSuccess(spark, partDir)
        spark.sql(s"ALTER TABLE $stg ADD IF NOT EXISTS PARTITION (snapshot_id = '$Sid', dt = '$Dt')")
        parts = PartitionEvidence.collect(spark, Seq(stg), Some(Sid), Some(Dt))
      }
      withClue(s"$stg 暂存分区证据=${parts.map(p => s"${p.rowCount}行@${p.path}").mkString}：") {
        parts should have size 1
      }
      val ev = parts.head
      ev.rowCount should be(0L)
      val loc = ev.path.getOrElse(throw new IllegalStateException(s"$stg 分区无 Location"))
      // 夹具镜像 T-R1：分区目录只有 _SUCCESS（无 parquet 数据文件）；若写入残留 part-* 则清掉
      val p = new Path(loc)
      val fs = p.getFileSystem(spark.sparkContext.hadoopConfiguration)
      fs.listStatus(p).filter(st => st.isFile && st.getPath.getName.startsWith("part-"))
        .foreach { st =>
          note(s"$stg：夹具清理写入残留 ${st.getPath.getName}（T-R1 实测 0 行分区无数据文件）")
          fs.delete(st.getPath, false)
        }
      touchSuccess(spark, p)
      t -> loc
    }.toMap

    // ── 真实 pub：元数据指针切换（正式分区 Location → 暂存快照路径）──
    val pub = runJob(spark, "pub", Some(Sid))(note)

    // ── 真实 mxp：被测对象 ──
    val mxp = runJob(spark, "mxp", Some(Sid), "exportDir" -> exportDir)(note)

    // ── 采集 manifest 原文 ──
    val manifestNio = nioPath(s"$exportDir/${MetricAdsSpec.EXPORT_MANIFEST}")
    val manifestRaw = if (Files.isRegularFile(manifestNio)) new String(Files.readAllBytes(manifestNio), StandardCharsets.UTF_8) else ""

    Capture(None, notes.toList, pub, mxp, emptyLocations, manifestRaw)
  }

  private def runJob(spark: SparkSession, code: String, snapshot: Option[String],
                     extra: (String, String)*)(note: String => Unit): Option[JobResult] = {
    val base = Seq("--runtimeProfileId=1", s"--jobCode=$code", s"--businessDate=$Dt",
      "--attemptNo=1", s"--hiveDatabasePrefix=${Ns.prefix}", "--sourceSystem=mock-mall",
      s"--landingDir=${P2TestSupport.goldenUri}", "--batchId=20260918")
    val all = base ++ snapshot.map(s => s"--outputSnapshotId=$s") ++ extra.map { case (k, v) => s"--$k=$v" }
    val args: JobArgs = JobArgs.parse(all.toArray) match {
      case Right(a) => a
      case Left(e) => throw new IllegalArgumentException(s"$code 参数构造失败: $e（${all.mkString(" ")}）")
    }
    val job: WarehouseJob = JobRegistry.lookup(code)
      .getOrElse(throw new IllegalStateException(s"JobRegistry 未注册 $code"))
    val r = try Some(job.run(spark, args)) catch {
      case t: Throwable =>
        note(s"$code THREW: ${t.getClass.getName}: ${t.getMessage}")
        throw t
    }
    r.foreach { res =>
      println(s"[mxp0] $code ${res.status}: ${res.message}")
      res.checks.foreach(c => println(
        s"[mxp0][check:$code] ${c.ruleCode}|${c.severity}|passed=${c.passed}|${c.detail}"))
    }
    r
  }

  /** 确保分区目录存在且带 `_SUCCESS`（T-R1 实测 0 行分区的物理形态） */
  private def touchSuccess(spark: SparkSession, dir: Path): Unit = {
    val fs = dir.getFileSystem(spark.sparkContext.hadoopConfiguration)
    if (!fs.exists(dir)) fs.mkdirs(dir)
    val success = new Path(dir, "_SUCCESS")
    if (!fs.exists(success)) {
      val out = fs.create(success, true)
      try out.write(Array.emptyByteArray) finally out.close()
    }
  }

  private def nioPath(s: String): java.nio.file.Path =
    if (s.startsWith("file:")) Paths.get(java.net.URI.create(s)) else Paths.get(s)

  /** 与发布侧逐行读一致的行数口径（非空行） */
  private def fileLines(p: java.nio.file.Path): Long =
    Files.readAllLines(p, StandardCharsets.UTF_8).asScala.count(_.trim.nonEmpty)
}

object MetricExportZeroRowSpec {
  private val SuiteName = "mxp-zero-row"
  private val Sid = "s-mxp-zero-01"
  private val Dt = "20260918"
  // 前缀不得以层后缀结尾（WarehouseNamespace.validate → WAREHOUSE_PREFIX_LAYER_SUFFIX）
  private val Ns = WarehouseNamespace.of("dw_mxp_zero")

  /** T-R1 实测的 0 行专题：来源为纯交易事件（REFERENCE_MALL_HTTP 无通用 behavior endpoint，B-04） */
  private val ZeroRowTables = Seq("ads_hot_product", "ads_product_conversion")

  private val RealRowTables = Seq(
    "ads_operation_overview", "ads_sale_trend", "ads_behavior_funnel",
    "ads_active_trend", "ads_user_profile", "ads_data_quality")

  private val ZeroRowMysql: Set[String] = Set("ads_hot_product_m", "ads_product_conversion_m")
}
