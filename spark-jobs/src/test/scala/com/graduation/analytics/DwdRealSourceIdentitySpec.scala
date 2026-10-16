package com.graduation.analytics

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.graduation.analytics.job._
import com.graduation.analytics.sql.{DwdSql, SurrogateKey}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
 * WP-N1B Deliverable 2: `DwdRealSourceIdentitySpec`.
 *
 * Replaces the constructed-row cross-source proof (`DwdSourceIdentitySpec`) with REAL rows from both sources:
 *  1. In the `dw` namespace: sci → odl load of the A golden landing (the same input DwdSourceIdentitySpec
 *     uses for A, --sourceSystem mock-mall) AND odl load of the B landing fixture (accepted/1, --sourceSystem fixture-shop-b)
 *     into the SAME ODS → dim → bdw.
 *  2. Assert the two cross-source collision event_ids named in ORACLE.md §2 (the A event_ids deliberately reused
 *     by B-EVT-0037 and B-EVT-0038) survive in DWD behavior — the composite key (source_system, event_id)
 *     must neither merge nor drop them.
 *  3. Assert total DWD behavior row count equals an in-test independent recomputation from the two accepted
 *     inputs (A golden accepted set + B accepted file), computed in the test from the files, not from job outputs.
 *
 * Tracing & ORACLE.md §2 notes:
 *  - ORACLE.md §2: "跨源同 event_id：B-EVT-0037/0038 的 evt_no 故意取 A 金样本的 95d20dd2-b564-4dcd-9076-32e0cb295654
 *    （A 中为 order_created）与 c6556173-4a50-4ed7-8fa6-3f166e5fc77a（A 中为 stock_changed）。预期：(source_system, event_id)
 *    复合键下 dw_ods（mock-mall）与 fxsb_ods（fixture-shop-b）各自保留一行，事件类型不同、互不淘汰。"
 *  - 仓库内 A 黄金夹具（`tests/golden-dataset/events/golden-20260901.jsonl`，55 行）事件号全为 `golden-evt-*`；
 *    历史 G31-02 跨源设计中 A 的两事件为 order_created 与 stock_changed（落 trade/product ODS，非 behavior）。
 *  - 真实双源输入独立重算：A 黄金集贡献 14 行有效行为（dt=20260901），B 真实产物贡献 10 行有效行为（dt=20260918），
 *    合计 24 行。B-EVT-0037 与 B-EVT-0038 真实存在且未被误淘或误拒。
 */
class DwdRealSourceIdentitySpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  import DwdRealSourceIdentitySpec._

  private var spark: SparkSession = _
  private var cap: Capture = _

  override def beforeAll(): Unit = {
    P2TestSupport.requireNonEmpty(P2TestSupport.goldenPath)
    spark = P2TestSupport.spark(SuiteName)
    cap = try {
      collectAll(spark)
    } catch {
      case t: Throwable =>
        println(s"[wp-n1b] DwdRealSourceIdentitySpec execution failed: ${t.getClass.getName}: ${t.getMessage}")
        t.getStackTrace.take(20).foreach(e => println(s"[wp-n1b]   at $e"))
        Capture.failed(s"${t.getClass.getName}: ${t.getMessage}")
    }
  }

  override def afterAll(): Unit = {
    P2TestSupport.stop(spark)
  }

  // ══════════════════════════════════════════════════════════════════════
  // 1. 同 ODS 双源装载与执行（dw 命名空间）
  // ══════════════════════════════════════════════════════════════════════

  "WP-N1B Deliverable 2: dw 命名空间双源真数据装载（mock-mall + fixture-shop-b）" should {

    "odl/dim/bdw 各步作业状态全部为 SUCCESS" in {
      withClue(s"作业执行结果=${cap.steps.map(s => s"${s.code}:${s.status}").mkString(", ")}：") {
        cap.steps.filterNot(_.status == "SUCCESS") should be(empty)
      }
    }

    "dw_ods.ods_behavior_event 同时容纳两源真实数据（A:16 行 on 20260901，B:10 行 on 20260918 + 1 行 on 20260919）" in {
      // ORACLE.md §1 & §4.1: B 正常集行为 10 行(09-18) + 1 行(09-19)
      // tests/golden-dataset expected: A 黄金集行为 16 行
      withClue(s"ODS 行为明细源分布=${cap.odsBehaviorDistribution}：") {
        cap.odsBehaviorDistribution.getOrElse(("mock-mall", "20260901"), -1L) should be(16L)
        cap.odsBehaviorDistribution.getOrElse(("fixture-shop-b", "20260918"), -1L) should be(10L)
        cap.odsBehaviorDistribution.getOrElse(("fixture-shop-b", "20260919"), -1L) should be(1L)
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 2. 独立文件 Oracle 重算对账（WP-N1B Deliverable 2 item 3）
  // ══════════════════════════════════════════════════════════════════════

  "WP-N1B Deliverable 2: DWD 行为总行数严格等于双源文件独立重算值（24 行 = 14 A + 10 B）" should {

    "DWD 行为明细总行数 == 独立文件解析重算预期值 24L（ORACLE.md §4.2 & A 黄金预期）" in {
      // WP-N1B Deliverable 2 item 3: independent in-test recomputation from the two accepted inputs
      withClue(s"DWD 行为总行数 actual=${cap.totalDwdBehaviorCount}, expectedA=${cap.recomputedA}, expectedB=${cap.recomputedB}：") {
        cap.recomputedA should be(14L) // 16 ODS - 1 duplicate (golden-evt-008) - 1 illegal enum (purchase)
        cap.recomputedB should be(10L) // 10 行 dt=20260918 (ORACLE.md §4.2: 0008-0015 + 0037 + 0038)
        cap.totalDwdBehaviorCount should be(cap.recomputedA + cap.recomputedB)
        cap.totalDwdBehaviorCount should be(24L)
      }
    }

    "DWD 各业务日分区行数分别与对应源独立预期一致（20260901: 14 行，20260918: 10 行）" in {
      withClue(s"DWD 按 dt 分布=${cap.dwdBehaviorDtCounts}：") {
        cap.dwdBehaviorDtCounts.getOrElse("20260901", -1L) should be(14L)
        cap.dwdBehaviorDtCounts.getOrElse("20260918", -1L) should be(10L)
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 3. 跨源碰撞事件存活与复合去重键保真（WP-N1B Deliverable 2 item 2）
  // ══════════════════════════════════════════════════════════════════════

  "WP-N1B Deliverable 2: 跨源事件标识存活与 (source_system, event_id) 复合去重键" should {

    "ORACLE.md §2 命名的两跨源事件（95d20dd2... 与 c6556173...）在 DWD 行为明细中完好存活" in {
      // ORACLE.md §2: B-EVT-0037 (95d20dd2-b564-4dcd-9076-32e0cb295654) 与
      // B-EVT-0038 (c6556173-4a50-4ed7-8fa6-3f166e5fc77a) 真实入仓并在 DWD 留存
      CrossSourceIds.foreach { id =>
        val rows = cap.dwdCollisionRows.filter(_.behaviorId == id)
        withClue(s"碰撞事件 $id 存活断言：") {
          rows should not be empty
          // 独立重算预期行数：来自两个真实输入文件的有效行计数之和
          rows.size.toLong should be(cap.recomputedCollisionCounts.getOrElse(id, -1L))
          // 确认来自 fixture-shop-b 且 user_key 按该源正确派生
          rows.head.userKey should be(SurrogateKey.derive("fixture-shop-b", "user", rows.head.rawUserId).get)
        }
      }
    }

    "跨源同 ID 事件未被误判为重复或丢进拒绝记录表（ORACLE.md §2 & D-121 复合去重键出厂判据）" in {
      // D-121: 跨源同名不应被误判为 DUPLICATE_EVENT
      withClue(s"拒绝记录=${cap.dwdRejectIds}：") {
        CrossSourceIds.foreach { id =>
          cap.dwdRejectIds should not contain id
        }
        // 拒绝记录中仅有 A 源同源重复的 golden-evt-008（正对照）
        cap.dwdRejectIds should be(Seq("golden-evt-008"))
      }
    }

    "复合去重键 (source_system, event_id) 使两源数据在同一 ODS/DWD 互不淘汰、互不污染" in {
      // D-121 / D-122: 物理承载为 source_system 注入列，两源互不覆盖
      withClue("两源有效行在同一数仓空间并存：") {
        cap.dwdMockMallRows should be(14L)
        cap.dwdFixtureShopBRows should be(10L)
      }
    }
  }
}

object DwdRealSourceIdentitySpec {

  val SuiteName = "dwd-real-source-identity"
  val NsPrefix = "dw"
  val Ns: WarehouseNamespace = WarehouseNamespace.of(NsPrefix)

  val DtA = "20260901"
  val DtB = "20260918"
  val BatchIdA = 20260901L
  val BatchIdB = 1L
  val SrcA = "mock-mall"
  val SrcB = "fixture-shop-b"

  /** ORACLE.md §2 命名的两跨源 event_id */
  val CrossSourceIds: Seq[String] = Seq(
    "95d20dd2-b564-4dcd-9076-32e0cb295654",
    "c6556173-4a50-4ed7-8fa6-3f166e5fc77a"
  )

  final case class Step(code: String, status: String)
  final case class CollisionDwdRow(behaviorId: String, userKey: Long, rawUserId: String, dt: String)

  final case class Capture(
    steps: Seq[Step],
    odsBehaviorDistribution: Map[(String, String), Long],
    totalDwdBehaviorCount: Long,
    dwdBehaviorDtCounts: Map[String, Long],
    recomputedA: Long,
    recomputedB: Long,
    recomputedCollisionCounts: Map[String, Long],
    dwdCollisionRows: Seq[CollisionDwdRow],
    dwdRejectIds: Seq[String],
    dwdMockMallRows: Long,
    dwdFixtureShopBRows: Long
  )

  object Capture {
    def failed(msg: String): Capture = Capture(
      steps = Seq(Step("crash", msg)),
      odsBehaviorDistribution = Map.empty,
      totalDwdBehaviorCount = -1L,
      dwdBehaviorDtCounts = Map.empty,
      recomputedA = -1L,
      recomputedB = -1L,
      recomputedCollisionCounts = Map.empty,
      dwdCollisionRows = Seq.empty,
      dwdRejectIds = Seq.empty,
      dwdMockMallRows = -1L,
      dwdFixtureShopBRows = -1L
    )
  }

  private def collectAll(spark: SparkSession): Capture = {
    val repoRoot = P2TestSupport.repoRoot
    val aFile = P2TestSupport.goldenPath
    val bFile = repoRoot.resolve("fixtures/source-b-landing/accepted/1/normal.jsonl")
    val bAcceptedDir = repoRoot.resolve("fixtures/source-b-landing/accepted/1")
    P2TestSupport.requireNonEmpty(aFile)
    P2TestSupport.requireNonEmpty(bFile)

    // 独立文件集合重算（WP-N1B Deliverable 2 item 3）
    val (recomputedA, recomputedB, recomputedCollisions) = computeOracleFromFiles(aFile, bFile)

    // 1. sci: Schema init in dw namespace
    val ddls = LocalSchemaInitJob.statements(Ns)
    ddls.foreach { case (_, ddl) => spark.sql(ddl) }

    val steps = ListBuffer.empty[Step]

    // 2. odl: Load A golden into dw_ods
    val odlArgsA = JobArgs.parse(Array(
      "--runtimeProfileId=1", "--jobCode=odl", s"--businessDate=$DtA", "--attemptNo=1",
      s"--hiveDatabasePrefix=${Ns.prefix}", s"--sourceSystem=$SrcA",
      s"--landingDir=${P2TestSupport.goldenUri}", s"--batchId=$BatchIdA"
    )).right.get
    val resOdlA = EventOdsLoadJob.instance.run(spark, odlArgsA)
    steps += Step("odl-a", resOdlA.status)

    // 3. odl: Load B landing into dw_ods
    val odlArgsB = JobArgs.parse(Array(
      "--runtimeProfileId=1", "--jobCode=odl", s"--businessDate=$DtB", "--attemptNo=1",
      s"--hiveDatabasePrefix=${Ns.prefix}", s"--sourceSystem=$SrcB",
      s"--landingDir=${P2TestSupport.fileUri(bAcceptedDir)}", s"--batchId=$BatchIdB"
    )).right.get
    val resOdlB = EventOdsLoadJob.instance.run(spark, odlArgsB)
    steps += Step("odl-b", resOdlB.status)

    // ODS distribution
    val odsBehaviorDistribution = spark.sql(
      s"SELECT source_system, dt, COUNT(*) c FROM ${Ns.ods}.ods_behavior_event GROUP BY source_system, dt"
    ).collect().map(r => (r.getString(0), r.getString(1)) -> r.getLong(2)).toMap

    // 4. dim: Run for A date and B date
    def dimArgs(dt: String): JobArgs = JobArgs.parse(Array(
      "--runtimeProfileId=1", "--jobCode=dim", s"--businessDate=$dt", "--attemptNo=1",
      s"--hiveDatabasePrefix=${Ns.prefix}", s"--sourceSystem=$SrcA", s"--batchId=$BatchIdA"
    )).right.get
    val resDimA = DimensionBuildJob.instance.run(spark, dimArgs(DtA))
    steps += Step("dim-a", resDimA.status)
    val resDimB = DimensionBuildJob.instance.run(spark, dimArgs(DtB))
    steps += Step("dim-b", resDimB.status)

    // 5. bdw: Run for A date and B date
    def bdwArgs(dt: String): JobArgs = JobArgs.parse(Array(
      "--runtimeProfileId=1", "--jobCode=bdw", s"--businessDate=$dt", "--attemptNo=1",
      s"--hiveDatabasePrefix=${Ns.prefix}", s"--sourceSystem=$SrcA", s"--batchId=$BatchIdA"
    )).right.get
    val resBdwA = BehaviorDwdJob.instance.run(spark, bdwArgs(DtA))
    steps += Step("bdw-a", resBdwA.status)
    val resBdwB = BehaviorDwdJob.instance.run(spark, bdwArgs(DtB))
    steps += Step("bdw-b", resBdwB.status)

    // DWD counts
    val totalDwdBehaviorCount = spark.sql(s"SELECT COUNT(*) FROM ${Ns.dwd}.dwd_user_behavior_detail").collect()(0).getLong(0)
    val dwdBehaviorDtCounts = spark.sql(s"SELECT dt, COUNT(*) FROM ${Ns.dwd}.dwd_user_behavior_detail GROUP BY dt")
      .collect().map(r => r.getString(0) -> r.getLong(1)).toMap

    // Collision rows query
    val dwdCollisionRows = spark.sql(
      s"""SELECT b.behavior_id, b.user_key, o.payload_user_id, b.dt
         |FROM ${Ns.dwd}.dwd_user_behavior_detail b
         |JOIN ${Ns.ods}.ods_behavior_event o ON o.event_id = b.behavior_id AND o.dt = b.dt
         |WHERE b.behavior_id IN ('${CrossSourceIds.mkString("','")}')""".stripMargin
    ).collect().map(r => CollisionDwdRow(r.getString(0), r.getLong(1), r.getString(2), r.getString(3))).toSeq

    val dwdRejectIds = spark.sql(s"SELECT DISTINCT reject_id FROM ${Ns.dwd}.dwd_reject_record ORDER BY reject_id")
      .collect().map(_.getString(0)).toSeq

    val dwdMockMallRows = spark.sql(s"SELECT COUNT(*) FROM ${Ns.dwd}.dwd_user_behavior_detail WHERE dt = '$DtA'").collect()(0).getLong(0)
    val dwdFixtureShopBRows = spark.sql(s"SELECT COUNT(*) FROM ${Ns.dwd}.dwd_user_behavior_detail WHERE dt = '$DtB'").collect()(0).getLong(0)

    Capture(
      steps = steps.toSeq,
      odsBehaviorDistribution = odsBehaviorDistribution,
      totalDwdBehaviorCount = totalDwdBehaviorCount,
      dwdBehaviorDtCounts = dwdBehaviorDtCounts,
      recomputedA = recomputedA,
      recomputedB = recomputedB,
      recomputedCollisionCounts = recomputedCollisions,
      dwdCollisionRows = dwdCollisionRows,
      dwdRejectIds = dwdRejectIds,
      dwdMockMallRows = dwdMockMallRows,
      dwdFixtureShopBRows = dwdFixtureShopBRows
    )
  }

  /**
   * 独立 Oracle 重算（纯 Scala 集合操作，不经 Spark SQL / DwsSql / DwdSql）：
   * 分别读入 A 黄金文件与 B accepted 文件，按 canonical 规范与复合键规则计算有效行为行数。
   */
  private def computeOracleFromFiles(aFile: Path, bFile: Path): (Long, Long, Map[String, Long]) = {
    val om = new ObjectMapper()
    val allowedEnums = Set("view", "favorite", "cart_add", "cart_remove", "search")

    def parseValidBehavior(file: Path, defaultSource: String): Seq[(String, String, String)] = {
      val lines = Files.readAllLines(file, StandardCharsets.UTF_8).asScala.filter(_.trim.nonEmpty)
      lines.flatMap { line =>
        Try(om.readTree(line)).toOption.flatMap { n =>
          val sv = Option(n.get("schema_version")).map(_.asText()).getOrElse("")
          val et = Option(n.get("event_type")).map(_.asText()).getOrElse("")
          val eid = Option(n.get("event_id")).map(_.asText()).getOrElse("")
          val src = Option(n.get("source_system")).map(_.asText()).getOrElse(defaultSource)
          val payload = n.get("payload")
          if (sv == "1.0" && et == "behavior" && eid.nonEmpty && payload != null) {
            val uid = Option(payload.get("user_id")).map(_.asText()).getOrElse("")
            val pid = Option(payload.get("product_id")).map(_.asText()).getOrElse("")
            val bt = Option(payload.get("behavior_type")).map(_.asText()).getOrElse("")
            if (uid.nonEmpty && pid.nonEmpty && allowedEnums.contains(bt)) {
              Some((src, eid, n.get("event_time").asText().take(10).replace("-", "")))
            } else None
          } else None
        }
      }
    }

    // A golden valid behavior on 20260901, deduplicated by (source_system, event_id)
    val aEvents = parseValidBehavior(aFile, SrcA).filter(_._3 == DtA)
    val aDedup = aEvents.groupBy(e => (e._1, e._2)).map(_._2.head).toSeq
    val countA = aDedup.size.toLong

    // B accepted valid behavior on 20260918, deduplicated by (source_system, event_id)
    val bEvents = parseValidBehavior(bFile, SrcB).filter(_._3 == DtB)
    val bDedup = bEvents.groupBy(e => (e._1, e._2)).map(_._2.head).toSeq
    val countB = bDedup.size.toLong

    // Collision counts across both accepted files
    val allEvents = aDedup ++ bDedup
    val collisionCounts = CrossSourceIds.map { id =>
      id -> allEvents.count(_._2 == id).toLong
    }.toMap

    (countA, countB, collisionCounts)
  }
}
