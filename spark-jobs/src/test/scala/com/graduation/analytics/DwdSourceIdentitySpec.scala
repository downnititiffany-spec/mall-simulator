package com.graduation.analytics

import com.graduation.analytics.job.{BehaviorDwdJob, DimensionBuildJob, EventOdsLoadJob, JobArgs, LocalSchemaInitJob, WarehouseJob}
import com.graduation.analytics.sql.{DwdSql, SurrogateKey}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, lit}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * P2-04-a（裁决 D-121 / D-122 / D-124 / D-126）：**DWD 去重键复合化**的真跑取证。
 *
 * 缺陷口径（不是新需求）：冻结契约（权威①）逐字写 `(source_instance_id, event_id)`，
 * 而实现此前是**单键** `ROW_NUMBER() OVER (PARTITION BY event_id …)`（`DwdSql.behaviorClean`）
 * ⇒ 同一 `event_id` 分属两个源实例时会互相去重、丢掉一方；且 `duplicateReject` 按 `event_id`
 * 单键分组 ⇒ 跨源同名会被**误判为重复并丢进拒绝记录**。
 *
 * 裁决口径（逐字，防止越界实现）：
 *  - D-121 L25：落点＝**DWD 去重窗口**（`DwdSql.scala`）＋「使得 ODS/DWD 侧确实存在可用的源身份列」；
 *  - D-122 L32/L70：物理承载＝**既有列 `source_system`**（注入值），**不新增列**；L34 要求只加一行
 *    「契约 `source_instance_id` ⇔ 物理 `source_system`」**别名说明**（不改语义/列名）；
 *    L35：**不得**把行内原值 `raw_source_system` 当去重键输入；
 *  - D-124 L47：**只扩键**，不得改 `event_id`/`behavior_id` 的生成、归一或编码规则；
 *  - D-126 L61-63：跨源正确性**只认构造夹具 + 本地链**；上限 `DONE_LIMITED`；
 *  - 裁决 L81：**本裁决不授权任何 DDL** ⇒ 源身份在 ODS 侧已存在，去重窗口直接引用它即可；
 *    本套件同时把「DWD 表不加 `source_system` 列」钉住（若要落表须另行授权 DDL）。
 *
 * 构造夹具（D-126 口径，如实标注）：真库是**单源**，跨源性质在真库上不可证伪 ⇒ 用黄金夹具
 * （只读）真跑出源 A 的 ODS，再把其中若干行为行**复制**成源 B（同 `event_id`、不同
 * `source_system`）——复制件即构造夹具。
 *
 * 证明边界：in-JVM `local[1]` + `catalogImplementation=in-memory`，**不是** `spark-submit`、
 * **不是** Hive metastore、**不是在产 3306/Hive 数据**。
 */
class DwdSourceIdentitySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val sparkAppName = "dwd-source-identity"
  private val ns = WarehouseNamespace.of("dw_srcid")
  private val dt = "20260901"
  private val batchId = 20260901L
  private val SrcA = "mock-mall"
  private val SrcB = "mock-mall-b"

  /** 构造夹具规模：3 个跨源同 id + 1 个同源重复（全部复制自黄金夹具的真实行） */
  private val CrossSourceCount = 3
  private val SameSourceDupCount = 1

  private var spark: SparkSession = _
  private var crossIds: Seq[String] = Seq.empty
  private var sameDupId: String = ""
  /** 构造夹具**之前**测得的源 A 侧同源重复组数（正对照；黄金夹具自带 1 组） */
  private var goldenDupGroups: Long = -1L

  override def beforeAll(): Unit = {
    P2TestSupport.requireNonEmpty(P2TestSupport.goldenPath)
    spark = P2TestSupport.spark(sparkAppName)
    LocalSchemaInitJob.statements(ns).foreach { case (_, ddl) => spark.sql(ddl) }

    runOdl(SrcA) // 真跑 odl：golden 55 行 → 四主题 ODS（注入 source_system=SrcA）
    goldenDupGroups = dupGroups(SrcA) // 构造前测：黄金夹具自身的同源重复组数
    val ids = pickSingletonBehaviorIds(CrossSourceCount + SameSourceDupCount)
    require(ids.size == CrossSourceCount + SameSourceDupCount,
      s"夹具不足：需要 ${CrossSourceCount + SameSourceDupCount} 个单行且枚举合法的行为 event_id，实得 ${ids.size}")
    crossIds = ids.take(CrossSourceCount)
    sameDupId = ids(CrossSourceCount)
    appendBehaviorCopy(SrcB, crossIds) // 构造夹具：跨源同 id
    appendBehaviorCopy(SrcA, Seq(sameDupId)) // 构造夹具：同源重复

    // 依赖顺序：dim 必须先于 bdw（bdw 的 SQL LEFT JOIN dim_user/dim_product 取
    // city_level/category_id/category_key，谓词带 u.dt='<业务日>'）
    runJob(DimensionBuildJob.instance, "dim")
    runJob(BehaviorDwdJob.instance, "bdw")
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ── 正对照 / 前置条件 ────────────────────────────────────────────────────

  "夹具" should "黄金夹具自带 1 组同源重复 (source_system,event_id)（后续拒绝计数以此为准）" in {
    // 构造前实测：黄金 55 行 → 行为 ODS 若干行，其中 1 组同源重复
    withClue(s"goldenDupGroups=$goldenDupGroups：") { goldenDupGroups should be(1L) }
  }

  // ── D-121 出口判据：跨源同名不再互相去重 ─────────────────────────────────

  "DwdSql.behaviorClean" should "D-121 同一 event_id 分属两个源实例时不再互相去重（各留一行）" in {
    crossIds.foreach { id =>
      val row = spark.sql(
        s"""SELECT COUNT(*) AS c, COUNT(DISTINCT user_key) AS k
           |FROM ${ns.dwd}.dwd_user_behavior_detail
           |WHERE dt = '$dt' AND behavior_id = '$id'""".stripMargin).collect()(0)
      withClue(s"event_id=$id：") {
        row.getLong(0) should be(2L) // 两行都在（单键时会被压成 1 行）
        // 两行的 user_key 不同 ⇔ 二者来自不同 source_system：
        // user_key = HASH64(source_system, payload_user_id)，而这两行的 payload_user_id 相同
        row.getLong(1) should be(2L)
      }
    }
  }

  it should "D-122 去重键用的是**注入值** source_system，不是行内原值 raw_source_system" in {
    // 前置事实：构造夹具的源 B 行，注入值与行内原值确实不同（否则本断言无区分度）
    val injectedDiffersFromRaw = spark.sql(
      s"""SELECT COUNT(*) AS c FROM ${ns.ods}.ods_behavior_event
         |WHERE dt = '$dt' AND source_system = '$SrcB' AND raw_source_system <> '$SrcB'""".stripMargin
    ).collect()(0).getLong(0)
    injectedDiffersFromRaw should be(CrossSourceCount.toLong)

    // DWD 落表的 user_key 必须等于「用注入值算出的键」，且**不等于**用行内原值算出的键
    val id = crossIds.head
    val r = spark.sql(
      s"""SELECT
         |  ${SurrogateKey.toBIGINT("o.source_system", "user", "o.payload_user_id")} AS k_injected,
         |  ${SurrogateKey.toBIGINT("o.raw_source_system", "user", "o.payload_user_id")} AS k_raw
         |FROM ${ns.ods}.ods_behavior_event o
         |WHERE o.dt = '$dt' AND o.event_id = '$id' AND o.source_system = '$SrcB'""".stripMargin
    ).collect()(0)
    val kInjected = r.getLong(0)
    val kRaw = r.getLong(1)
    withClue(s"kInjected=$kInjected kRaw=$kRaw：") { kRaw should not be kInjected }

    val dwdKeys = spark.sql(
      s"""SELECT DISTINCT user_key FROM ${ns.dwd}.dwd_user_behavior_detail
         |WHERE dt = '$dt' AND behavior_id = '$id'""".stripMargin)
      .collect().map(_.getLong(0)).toSet
    withClue(s"dwdKeys=$dwdKeys：") {
      dwdKeys should contain(kInjected) // 注入值 → 键；若按 raw 取键，这一项根本不会出现
      dwdKeys.size should be(2) // 若按 raw_source_system 取键，两行会同键 ⇒ 只会剩 1 个
    }
    // 说明：本构造夹具里源 B 复制件保留了 A 的行内原值（raw = 'mock-mall'），故用 raw 算出的键
    // 恰好等于源 A 行的键。这恰好让上面两条断言具备区分度：kInjected 只可能来自注入列
    // source_system；而「键集合恰有 2 个」排除了「两行按 raw 折叠成同一个键」。
  }

  it should "D-121 单源部分退化为与旧单键语义等价（DWD 行数 = ODS 的 (source_system,event_id) 对数）" in {
    // 独立 oracle：不复用 DwdSql 文本，直接按契约口径在 ODS 上数
    val expected = spark.sql(
      s"""SELECT COUNT(*) AS c FROM (
         |  SELECT DISTINCT source_system, event_id FROM ${ns.ods}.ods_behavior_event
         |  WHERE dt = '$dt' AND schema_version = '1.0'
         |    AND payload_user_id IS NOT NULL AND payload_product_id IS NOT NULL
         |    AND payload_behavior_type IN ('view','favorite','cart_add','cart_remove','search')
         |) t""".stripMargin).collect()(0).getLong(0)
    val actual = spark.sql(
      s"SELECT COUNT(*) AS c FROM ${ns.dwd}.dwd_user_behavior_detail WHERE dt = '$dt'")
      .collect()(0).getLong(0)
    withClue(s"oracle=$expected actual=$actual：") {
      actual should be(expected)
      actual should be > 0L // 正对照：不是空表假绿
    }
  }

  // ── 拒绝记录：只记**同源**重复，跨源同名不得进拒绝 ────────────────────────

  it should "D-121 跨源同 id 不进拒绝记录；拒绝记录 = 黄金固有 1 组 + 构造的同源重复 1 组" in {
    val rejected = spark.sql(
      s"SELECT COUNT(*) AS c FROM ${ns.dwd}.dwd_reject_record WHERE dt = '$dt'")
      .collect()(0).getLong(0)
    withClue(s"拒绝记录=$rejected（跨源同名被误判为重复时会再 +$CrossSourceCount）：") {
      rejected should be(goldenDupGroups + 1L)
    }

    val rejectedIds = spark.sql(
      s"SELECT DISTINCT reject_id FROM ${ns.dwd}.dwd_reject_record WHERE dt = '$dt'")
      .collect().map(_.getString(0)).toSet
    withClue(s"rejectedIds=$rejectedIds：") { rejectedIds should contain(sameDupId) }
    crossIds.foreach(id => withClue(s"$id：") { rejectedIds should not contain id })
    // 拒绝原因字面量被下游消费，不得改名
    spark.sql(s"SELECT DISTINCT reject_reason FROM ${ns.dwd}.dwd_reject_record WHERE dt = '$dt'")
      .collect().map(_.getString(0)).toSeq should be(Seq("DUPLICATE_EVENT"))
  }

  // ── 固化「本轮不动 DDL」这条裁决边界 + 双所有者 DDL 对账 ─────────────────

  "dwd_user_behavior_detail" should "静态 DDL 与 LocalSchemaInitJob 逐列一致（本行不改 DDL）" in {
    val table = "dwd_user_behavior_detail"
    val static = StaticOdsDdl.parse(readStaticDwdDdl())
    val derived = LocalSchemaInitJob.statements(ns).map(_._2)
      .find(_.contains(s".$table ("))
      .getOrElse(fail(s"INIT_SCHEMA 未包含 $table 建表语句"))
    val parsed = StaticOdsDdl.parse(derived)

    withClue("静态/派生不一致：") {
      parsed.columnSeq(table) should be(static.columnSeq(table))
      parsed.partitions(table) should be(static.partitions(table))
    }
    // D-122「不新增列」+ 裁决 L81「不授权任何 DDL」：源身份留在 ODS 侧，不落 DWD。
    // 将来若总控另行授权把源身份落表，需连同本断言一起改（届时属 DDL 授权变更）。
    static.columnSeq(table).map(_._1) should not contain "source_system"
    parsed.columnSeq(table).map(_._1) should not contain "source_system"
  }

  "DwdSql.behaviorClean" should "去重窗口按 (source_system,event_id) 复合键，且不改 SELECT 列表形状（无新列）" in {
    val flat = DwdSql.behaviorClean(ns, dt).split("\n")
      .map(_.replaceAll("--.*$", "")).mkString(" ").replaceAll("\\s+", " ").trim
    val lower = flat.toLowerCase
    lower should include("row_number() over (partition by source_system, event_id order by ingest_time)")
    lower should include("rn.rn = 1")
    // INSERT 未写列名 ⇒ 列体靠位置对齐；本行只扩键、不加列：SELECT 列表末位仍是 category_key
    val aliasIdx = flat.indexOf("AS category_key")
    withClue(s"未找到 `AS category_key`；实际 SQL=$flat；") { aliasIdx should be >= 0 }
    flat.substring(aliasIdx + "AS category_key".length, flat.indexOf("FROM (")).trim should be("")
  }

  it should "拒绝模板按 (source_system,event_id) 分组（与去重窗口同一把钥匙）" in {
    val flat = DwdSql.duplicateReject(ns, dt).replaceAll("\\s+", " ").toLowerCase
    flat should include("group by source_system, event_id having count(*) > 1")
    flat should include("'duplicate_event'")
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────

  /** 真跑 odl（与 ODS 侧既有 spec 同口径：同一 JobArgs 通道） */
  private def runOdl(sourceSystem: String): Unit = {
    val args = JobArgs.parse(Array(
      "--runtimeProfileId=1", "--jobCode=odl", "--businessDate=" + dt, "--attemptNo=1",
      s"--hiveDatabasePrefix=${ns.prefix}",
      s"--sourceSystem=$sourceSystem",
      s"--landingDir=${P2TestSupport.goldenUri}",
      s"--batchId=$batchId")).right.get
    EventOdsLoadJob.instance.run(spark, args)
  }

  /** 真跑 dim / bdw（参数走与生产同一条 `--hiveDatabasePrefix` 通道） */
  private def runJob(job: WarehouseJob, code: String): Unit = {
    val args = JobArgs.parse(Array(
      "--runtimeProfileId=1", s"--jobCode=$code", "--businessDate=" + dt, "--attemptNo=1",
      s"--hiveDatabasePrefix=${ns.prefix}",
      s"--sourceSystem=$SrcA",
      s"--batchId=$batchId")).right.get
    job.run(spark, args)
  }

  /**
   * 选 `n` 个**单行且 DWD 可用**的行为 `event_id`（源 A 侧）：
   * 过滤条件与 `behaviorClean` 的 WHERE 同口径，且 `HAVING COUNT(*) = 1` 保证不与黄金固有重复组相撞
   * —— 否则「同源重复 +1 组」的断言会因并组而失真。
   */
  private def pickSingletonBehaviorIds(n: Int): Seq[String] =
    spark.sql(
      s"""SELECT event_id FROM ${ns.ods}.ods_behavior_event
         |WHERE dt = '$dt' AND source_system = '$SrcA' AND schema_version = '1.0'
         |  AND payload_user_id IS NOT NULL AND payload_product_id IS NOT NULL
         |  AND payload_behavior_type IN ('view','favorite','cart_add','cart_remove','search')
         |GROUP BY source_system, event_id HAVING COUNT(*) = 1
         |ORDER BY event_id LIMIT $n""".stripMargin)
      .collect().map(_.getString(0)).toSeq

  /**
   * 构造夹具：把源 A 的行为行整行复制成另一个 `source_system`（只覆盖**注入列**）。
   * D-126 口径下真库给不出该形态，只能构造。
   */
  private def appendBehaviorCopy(sourceSystem: String, ids: Seq[String]): Unit = {
    if (ids.isEmpty) return
    val copy = spark.table(s"${ns.ods}.ods_behavior_event")
      .where(col("event_id").isin(ids: _*))
      .withColumn("source_system", lit(sourceSystem))
    copy.write.mode("append").insertInto(s"${ns.ods}.ods_behavior_event")
  }

  /** 指定源侧按 (source_system,event_id) 的重复组数（正对照用；与拒绝模板同population：不加业务过滤） */
  private def dupGroups(sourceSystem: String): Long =
    spark.sql(
      s"""SELECT COUNT(*) AS c FROM (
         |  SELECT source_system, event_id FROM ${ns.ods}.ods_behavior_event
         |  WHERE dt = '$dt' AND source_system = '$sourceSystem'
         |  GROUP BY source_system, event_id HAVING COUNT(*) > 1
         |) t""".stripMargin).collect()(0).getLong(0)

  /** 读静态 DWD DDL（存在且非空先断言） */
  private def readStaticDwdDdl(): String = {
    val path: Path = P2TestSupport.repoRoot.resolve("warehouse/ddl/01-dwd.sql")
    P2TestSupport.requireNonEmpty(path)
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
