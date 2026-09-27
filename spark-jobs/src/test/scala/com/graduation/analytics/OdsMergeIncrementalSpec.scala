package com.graduation.analytics

import com.graduation.analytics.job.{EventOdsLoadJob, JobArgs, LocalSchemaInitJob}
import com.graduation.analytics.sql.{OdsLoadSql, OdsV2Columns}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/**
 * G31-08 / F-G4-1·D-040：同日增量写入回归（分区作用域 读-合并-去重-覆写）。
 *
 * 判据（计划 BATCH-G31-08-FG41-SAMEDAY-INCREMENTAL-PLAN.md §2）：
 * G1 原有数据保留 / G2 新增计入（分区间全可加）/ G3 同批重放不重复 /
 * G4 同分区旧记录并存（硬约束判别：克隆行保持 event_time ⇒ 与原行同 (dt,hour)）/
 * G5a 单主题批次不清他表 / G5b 全拒绝批次零写入 / new-wins（ingest_batch_id 取新批）/
 * G6 partitionOverwriteMode 还原。
 *
 * **测试域声明（与 P2TestSupport 的两个关键差异，必须如实进入批次报告）**：
 *  1. 本套件**自带** SparkSession 且**不预置** `spark.sql.sources.partitionOverwriteMode`
 *     （P2TestSupport 预置了 dynamic，会掩盖「由被测作业自己设/还原」这一事实）；
 *     beforeAll 断言该 conf 为 None。生产默认 STATIC——正是 F-G4-1 整表覆写的来源。
 *  2. in-memory catalog + `USING parquet`（spark-hive 为 provided）：
 *     测试域 ≠ 在产 Hive metastore；「在产成立」归 D-044⑤ 合并重跑验证，本套件不宣称。
 *
 * 夹具纪律：golden 只读；批次行克隆到 `D:/Develop/tmp/g31-08-landing/<runId>/`，
 * 只新建不删除。
 */
class OdsMergeIncrementalSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val OverwriteModeKey = "spark.sql.sources.partitionOverwriteMode"
  private val ns = WarehouseNamespace.of("dw_g8") // 独占前缀，与既有套件防串库
  private val sourceSystem = "mock-mall"
  private val BatchAId = 101L
  private val BatchBId = 102L
  private val BatchCId = 103L
  private val BatchDId = 104L
  private val BatchEId = 105L

  private val runId: String =
    sys.props.get("g31_08.test.runId").filter(_.nonEmpty).getOrElse {
      val ts = java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
      s"$ts-${java.lang.management.ManagementFactory.getRuntimeMXBean.getName.takeWhile(_ != '@')}"
    }

  private var spark: SparkSession = _
  // 内置 SQLConf 键 getOption 恒为 Some（出厂默认 STATIC）——「未预置」的判据是 == Some("STATIC")，
  // G6 断言作业跑完后还原为该捕获值（若作业泄漏 dynamic 则拿到 Some("dynamic") 失败）
  private var initialOverwriteMode: Option[String] = _

  // ── 夹具构造（全部从 golden 克隆派生；oracle 的表归属/分区由行内字段推导）────────

  private def hasField(line: String, key: String): Boolean = line.contains("\"" + key + "\":")

  /** 可装载行 = 版本/主键/时间三键齐全（golden 有 3 行 rejected：缺键或版本不符） */
  private lazy val goldenValid: Seq[String] = P2TestSupport.goldenLines.filter(l =>
    l.contains("\"schema_version\":\"1.0\"") && hasField(l, "event_id") && hasField(l, "event_time"))

  private def linesOfType(t: String): Seq[String] =
    goldenValid.filter(topLevelString(_, "event_type") == t)

  /** 克隆行：替换首个（也是唯一一个）event_id——golden 每行恰一个该键，信封键居首 */
  private def withEventId(line: String, newId: String): String =
    "\"event_id\":\"[^\"]*\"".r.replaceFirstIn(line, s""""event_id":"$newId"""")

  /** 克隆行小时改为 23（只替换首个 event_time——信封列；保证落进 A 未命中的分区） */
  private def withHour23(line: String): String =
    "\"event_time\":\"2026-09-01T\\d{2}".r.replaceFirstIn(line, "\"event_time\":\"2026-09-01T23")

  private val behaviorPool: Seq[String] = linesOfType("behavior")
  private val aBehavior: Seq[String] = behaviorPool.take(4)
  private val aTrade: Seq[String] = linesOfType("order_created").take(2)
  private val batchA: Seq[String] = aBehavior ++ aTrade

  private val batchB: Seq[String] =
    aBehavior.zipWithIndex.map { case (l, i) => withEventId(l, s"g8-b-beh-$i") } ++
      aTrade.zipWithIndex.map { case (l, i) => withEventId(l, s"g8-b-trade-$i") } :+
      withEventId(withHour23(aTrade.head), "g8-b-trade-23")

  private val cNew: Seq[String] =
    behaviorPool.slice(4, 6).zipWithIndex.map { case (l, i) => withEventId(l, s"g8-c-beh-$i") }
  private val batchC: Seq[String] = batchB ++ cNew // B 全部行原 id 重投（new-wins 场景）+ 新增

  private val batchD: Seq[String] = Seq(linesOfType("user_registered").head) // 单主题（原始 golden 行）
  private val batchE: Seq[String] = goldenValid.take(2)
    .map(_.replaceFirst("\"schema_version\":\"1\\.0\"", "\"schema_version\":\"2.0\"")) // 全拒绝

  // ── 快照 ─────────────────────────────────────────────────────────────────

  private case class TableSnapshot(ids: Set[String],
                                   partitionCounts: Map[(String, String), Long],
                                   total: Long)

  private var snapsA: Map[String, TableSnapshot] = Map.empty
  private var snapsB: Map[String, TableSnapshot] = Map.empty
  private var snapsBReplay: Map[String, TableSnapshot] = Map.empty
  private var snapsC: Map[String, TableSnapshot] = Map.empty
  private var snapsD: Map[String, TableSnapshot] = Map.empty
  private var snapsE: Map[String, TableSnapshot] = Map.empty

  override def beforeAll(): Unit = {
    P2TestSupport.requireNonEmpty(P2TestSupport.goldenPath)
    // 夹具健全性：判别力不足直接失败（不许退化成假绿）
    aBehavior.length should be(4)
    aTrade.length should be(2)
    cNew.length should be(2)
    batchE.length should be(2)

    val warehouse = s"${P2TestSupport.TempRoot}/g31-08-warehouse/$runId"
    Files.createDirectories(Paths.get(warehouse))
    val session = SparkSession.builder()
      .appName("g31-08-merge-incremental")
      .master("local[1]")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.sql.warehouse.dir", warehouse)
      .config("spark.sql.catalogImplementation", "in-memory")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.sql.session.timeZone", "Asia/Shanghai")
      .getOrCreate()
    session.sparkContext.setLogLevel("ERROR")
    spark = session

    // G6 前置证据：本套件不预置动态覆盖模式（出厂默认 STATIC，设/还由被测作业负责）
    initialOverwriteMode = spark.conf.getOption(OverwriteModeKey)
    withClue("测试会话不得预置 spark.sql.sources.partitionOverwriteMode（否则 G4/G6 失去判别力）: ") {
      initialOverwriteMode should be(Some("STATIC"))
    }

    LocalSchemaInitJob.statements(ns).foreach { case (_, ddl) => spark.sql(ddl) }

    val landingRoot = Paths.get(P2TestSupport.TempRoot, "g31-08-landing", runId)
    Files.createDirectories(landingRoot)
    val dirA = writeBatch(landingRoot, "batch-a", batchA)
    val dirB = writeBatch(landingRoot, "batch-b", batchB)
    val dirC = writeBatch(landingRoot, "batch-c", batchC)
    val dirD = writeBatch(landingRoot, "batch-d", batchD)
    val dirE = writeBatch(landingRoot, "batch-e", batchE)

    runBatch(dirA, BatchAId); snapsA = snapAll()
    runBatch(dirB, BatchBId); snapsB = snapAll()
    runBatch(dirB, BatchBId); snapsBReplay = snapAll() // 同参重放 = 真实重跑形态
    runBatch(dirC, BatchCId); snapsC = snapAll()
    runBatch(dirD, BatchDId); snapsD = snapAll()
    runBatch(dirE, BatchEId); snapsE = snapAll()
  }

  override def afterAll(): Unit = if (spark != null) spark.stop()

  // ── 判据 ─────────────────────────────────────────────────────────────────

  "同日增量批次 B（相对基线 A）" should "G1/G2 原有数据保留、新增计入：表级 id 集与分区行数全可加" in {
    // 正对照：基线非空，否则本组断言空转假绿
    snapsA.values.map(_.total).sum should be > 0L
    OdsV2Columns.OdsTables.foreach { t =>
      val a = snapsA(t); val b = snapsB(t)
      val bContrib = contribution(batchB, t)
      withClue(s"$t id 集: ") { b.ids should be(a.ids ++ idsOf(batchB, t)) }
      withClue(s"$t 分区行数: ") { b.partitionCounts should be(addMaps(a.partitionCounts, bContrib)) }
      withClue(s"$t 总行数: ") { b.total should be(a.total + bContrib.values.sum) }
    }
  }

  it should "G4 同分区旧记录并存（硬约束判别）：克隆行与 A 行同 (dt,hour)，B 后行数 = A + B" in {
    // 判别力守卫：B 只投行为/交易两主题，克隆保持 event_time ⇒ 这两表必有 A/B 共享分区；
    // 若共享键集为空说明夹具已退化（只剩跨分区场景，测不出「同分区覆写旧行」缺陷）
    val covered = OdsV2Columns.OdsTables.filter(t => contribution(batchB, t).nonEmpty)
    covered should contain("ods_behavior_event")
    covered should contain("ods_trade_event")
    covered.foreach { t =>
      val aPart = snapsA(t).partitionCounts
      val bContrib = contribution(batchB, t)
      val shared = aPart.keySet.intersect(bContrib.keySet)
      withClue(s"$t 共享分区: ") { shared should not be empty }
      shared.foreach { p =>
        withClue(s"$t 分区 $p: ") {
          snapsB(t).partitionCounts(p) should be(aPart(p) + bContrib(p))
        }
      }
    }
  }

  "同批重放（同 landing、同 batchId 再跑一遍）" should "G3 重跑不重复：id 集与分区行数逐位不变" in {
    OdsV2Columns.OdsTables.foreach { t =>
      withClue(s"$t: ") { snapsBReplay(t) should be(snapsB(t)) }
    }
  }

  "重投递批次 C（B 全部行原 id 重投 + 2 条新增）" should
    "new-wins：旧副本被新批替换、ingest_batch_id 取新批、event_id 零重复" in {
    OdsV2Columns.OdsTables.foreach { t =>
      val b = snapsB(t); val c = snapsC(t)
      val newContrib = contribution(cNew, t)
      withClue(s"$t id 集: ") { c.ids should be(b.ids ++ idsOf(cNew, t)) }
      withClue(s"$t 分区行数: ") { c.partitionCounts should be(addMaps(b.partitionCounts, newContrib)) }
      withClue(s"$t event_id 重复组数: ") {
        spark.sql(s"SELECT event_id FROM ${ns.ods}.$t GROUP BY event_id HAVING COUNT(*) > 1")
          .count() should be(0L)
      }
    }
    // 重投递行（B 的 id）必须以 C 批号存在——证明旧副本被 anti-join 移除、新副本落表
    val reDelivered = (aBehavior.indices.map(i => s"g8-b-beh-$i") ++
      aTrade.indices.map(i => s"g8-b-trade-$i") :+ "g8-b-trade-23").toSet
    Seq("ods_behavior_event", "ods_trade_event").foreach { t =>
      val batchIds = spark.sql(s"SELECT event_id, ingest_batch_id FROM ${ns.ods}.$t")
        .collect().map(r => r.getString(0) -> r.getLong(1)).toMap
      reDelivered.intersect(batchIds.keySet).foreach { id =>
        withClue(s"$t/$id: ") { batchIds(id) should be(BatchCId) }
      }
    }
  }

  "单主题批次 D（仅 user_registered）" should "G5a 其他三表快照逐位不变，user 表恰好 +1" in {
    Seq("ods_product_event", "ods_behavior_event", "ods_trade_event").foreach { t =>
      withClue(s"$t: ") { snapsD(t) should be(snapsC(t)) }
    }
    val u0 = snapsC("ods_user_event"); val u1 = snapsD("ods_user_event")
    u1.ids should be(u0.ids + topLevelString(batchD.head, "event_id"))
    u1.total should be(u0.total + 1L)
  }

  "全拒绝批次 E（schema_version='2.0'）" should "G5b 零写入：四表快照逐位不变" in {
    OdsV2Columns.OdsTables.foreach { t =>
      withClue(s"$t: ") { snapsE(t) should be(snapsD(t)) }
    }
  }

  "会话配置" should "G6 partitionOverwriteMode 已由作业还原为进入前的出厂值（防跨作业泄漏）" in {
    spark.conf.getOption(OverwriteModeKey) should be(initialOverwriteMode)
  }

  // ── 驱动与推导辅助 ───────────────────────────────────────────────────────

  private def writeBatch(root: Path, name: String, lines: Seq[String]): Path = {
    val dir = root.resolve(name)
    Files.createDirectories(dir)
    Files.write(dir.resolve(s"$name.jsonl"),
      (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))
    dir
  }

  private def runBatch(dir: Path, batchId: Long): Unit = {
    val args = JobArgs.parse(Array(
      "--runtimeProfileId=1", "--jobCode=odl", "--businessDate=20260901", "--attemptNo=1",
      s"--hiveDatabasePrefix=${ns.prefix}",
      s"--sourceSystem=$sourceSystem",
      s"--landingDir=${dir.toUri.toString}",
      s"--batchId=$batchId")).right.get
    val result = EventOdsLoadJob.instance.run(spark, args)
    withClue(s"batch $batchId: ") { result.status should be("SUCCESS") }
  }

  private def snapshot(table: String): TableSnapshot = {
    val rows = spark.sql(s"SELECT event_id, dt, hour FROM ${ns.ods}.$table").collect()
    TableSnapshot(
      rows.map(_.getString(0)).toSet,
      rows.map(r => (r.getString(1), r.getString(2)) -> 1L)
        .groupBy(_._1).map { case (k, v) => k -> v.size.toLong },
      rows.length.toLong)
  }

  private def snapAll(): Map[String, TableSnapshot] =
    OdsV2Columns.OdsTables.map(t => t -> snapshot(t)).toMap

  /** 行 → (dt, hour)：与模板分区派生同口径（dt=日期去横线，hour=第 12-13 字符） */
  private def partitionOf(line: String): (String, String) = {
    val t = topLevelString(line, "event_time")
    (t.take(10).replace("-", ""), t.substring(11, 13))
  }

  private def tableOf(line: String): String =
    OdsLoadSql.eventTypeToTable(topLevelString(line, "event_type"))

  private def idsOf(lines: Seq[String], table: String): Set[String] =
    lines.filter(tableOf(_) == table).map(topLevelString(_, "event_id")).toSet

  /** 一组行对某表的分区贡献图（夹具侧 oracle；G4 判别力来自克隆行保持 event_time） */
  private def contribution(lines: Seq[String], table: String): Map[(String, String), Long] =
    lines.filter(tableOf(_) == table)
      .map(l => partitionOf(l) -> 1L)
      .groupBy(_._1).map { case (k, v) => k -> v.size.toLong }

  private def addMaps(a: Map[(String, String), Long],
                      b: Map[(String, String), Long]): Map[(String, String), Long] =
    (a.keySet ++ b.keySet).map(k => k -> (a.getOrElse(k, 0L) + b.getOrElse(k, 0L))).toMap

  /** 从源行里取一个顶层字符串字段（与 OdsV2ByteFidelitySpec 同款测试自有实现） */
  private def topLevelString(line: String, key: String): String = {
    val idx = line.indexOf("\"" + key + "\"")
    val from = line.indexOf('"', idx + key.length + 2) + 1
    val to = line.indexOf('"', from)
    line.substring(from, to)
  }
}
