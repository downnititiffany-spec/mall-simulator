package com.graduation.analytics

import com.graduation.analytics.job.{
  BehaviorDwdJob, DimensionBuildJob, EventOdsLoadJob, JobArgs, JobResult, LocalSchemaInitJob,
  TradeDwdJob
}
import com.graduation.analytics.sql.{OdsV2Columns, SurrogateKey}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S2-05：**DIM/DWD 主链真跑**（`odl` → `bdw` / `dim` → `tdw`）在真实 Spark 上的端到端执行证据。
 *
 * 为什么要有这个套件：本轮之前 `bdw`/`dim`/`tdw` 只有 SQL 模板文本断言
 * （`SqlTemplateSpec`、`IdCodecSpec:78`、`WarehouseNamespaceSpec:252`），
 * `DwdSql.behaviorClean` / `DwdSql.duplicateReject` / `DimSql.userSnapshot` /
 * `DimSql.productSnapshot` / `TradeDwdJob.orderDetailInsertSql` 这几条
 * `INSERT OVERWRITE … SELECT` **从未真正被执行过**——「SELECT 列数/列序是否与 DDL 对得上」
 * 「JOIN 键类型是否可用」「SQL 是否直接抛异常」此前无任何执行证据。
 *
 * 本套件复用仓库既有夹具与**真实入口**，不自造 SparkSession、不抄 DDL 副本：
 *  - `P2TestSupport.spark(app)`（隔离 warehouse；in-memory catalog——该文件自陈的证明边界：
 *    「测试通过」≠「在产 Hive metastore 通过」，本套件的结论同样只在此边界内成立）；
 *  - `LocalSchemaInitJob.statements(ns)` 建表；
 *  - `EventOdsLoadJob` / `DimensionBuildJob` / `BehaviorDwdJob` / `TradeDwdJob` 的 `run` 真跑。
 *
 * 断言对着**行级真实结果**与设计口径（§9.2 L310-312、§9.4 L343-347），不是「非空即绿」。
 *
 * ── 编排顺序对照实验（本套件最有价值的证据）──────────────────────────────
 * 在产编排 `SparkStageExecutor.java:46` 的 BUILD_DWD 阶段为 `List.of("bdw","dim","tdw")`，
 * 同文件 `:134` **按列表顺序串行提交**（不是依赖拓扑排序）；而 `DwdSql.behaviorClean`
 * （`DwdSql.scala:51-52`）在 `bdw` 内 `LEFT JOIN ${ns.dim}.dim_user / dim_product`。
 * 故同一份黄金 ODS 输入、同一套真实入口，在**两个互不共享的隔离域**里按两种顺序各跑一次：
 *  - `A` = 生产顺序 `sci → odl → bdw → dim → tdw`；
 *  - `B` = 依赖直觉顺序 `sci → odl → dim → bdw → tdw`。
 * 对照结果在下方「A/B 对照（缺陷现状，未修）」一组用例里逐列钉住。
 */
class DimDwdChainExecSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // ── 夹具常量（黄金夹具 `tests/golden-dataset/events/golden-20260901.jsonl` 只读，55 行）──
  private val BusinessDate = "20260901"
  private val BatchId = 20260901L
  private val SourceSystem = "mock-mall"

  /** 设计 §9.2 L310 行为枚举 = `DwdSql.behaviorClean` 的白名单 */
  private val BehaviorEnum = Seq("view", "favorite", "cart_add", "cart_remove", "search")

  // ── 两个互不共享的隔离域（不同 app 名 ⇒ 不同 warehouse 根目录）──────────
  private val NsA = WarehouseNamespace.of("dw_ab_exe_a") // 生产顺序 bdw→dim
  private val AppA = "dim-dwd-chain-order-a"
  private val NsB = WarehouseNamespace.of("dw_ab_exe_b") // 依赖直觉顺序 dim→bdw
  private val AppB = "dim-dwd-chain-order-b"

  // ── 独立 oracle：从黄金夹具原文自己数出来的期望值（不依赖被测代码）──────
  /** `schema_version='1.0'` 且 `event_id` 非空的行数 = 53 */
  private val GoldenLines = 55L
  private val GoldenAccepted = 53L
  /** 被 odl 拒绝的行数 = 3（1 行 `schema_version=2.0` + 1 行缺 `event_id` + 1 行缺 `event_type`）*/
  private val GoldenRejected = 3L
  /** 53 行有效行去重后的 `event_id` 数（`golden-evt-008` 出现两次）= 52 */
  private val GoldenUniqueValidEvents = 52L
  /** 重复 `event_id` 的组数（`GROUP BY event_id HAVING COUNT(*)>1`）= 1 */
  private val GoldenDuplicateIdGroups = 1L

  // ── 期望的行级结果（设计口径 + 夹具原文可复算的事实；`r2` 真跑读数已核对）──
  /** ODS 四主题行数 = 52：trade 18（order×6 + paid×5 + cancelled×1 + refund×6）、
   *  behavior 16（17 行 behavior − 1 行缺 event_id）、product 14（created 4 + updated 1 + stock 8）、
   *  user 4（5 行 user_registered − 1 行 schema_version=2.0） */
  private val ExpOds = Map(
    "ods_user_event" -> 4L, "ods_product_event" -> 14L,
    "ods_behavior_event" -> 16L, "ods_trade_event" -> 18L)
  /** dim_user：user_id=1/2/3 三个业务键各取最新建档事件（user_id=9 是 v2.0，被拒） */
  private val ExpDimUser = 3L
  /** dim_product：product_id=1..4（product_id=1 有 created+updated 两条，取 updated） */
  private val ExpDimProduct = 4L
  /** 行为 DWD：16 行 ODS − 1 行 event_id 重复（golden-evt-008）− 1 行非法枚举（purchase）= 14 */
  private val ExpBehavior = 14L
  /** 订单明细行数 = 7（**不是订单数**：1 个订单有 2 个商品项 ⇒ 6 订单 → 7 行）*/
  private val ExpOrderDetail = 7L
  private val ExpOrderCount = 6L

  /** 一个场景的全量实测结果（套件初始化时一次性真跑出来；用例只做断言） */
  /** 把一次场景的实测读数打成一行（进 ScalaTest 报告，作为原始证据） */
  private def dump(c: Capture): String =
    s"""[CAPTURE ${c.tag}] {"warehouse":"${c.warehouse}",
       |"namespace":"${c.namespace}","spark_app":"${c.sparkApp}",
       |"isolation":{"dim_rows_at_bdw":{"dim_user":${c.dimRowsAtBdw._1},"dim_product":${c.dimRowsAtBdw._2}},
       |  "dim_input_rows_at_bdw":${c.dimInputAtBdw}},
       |"ods":${c.odsCounts.toSeq.sortBy(_._1).map { case (k, v) => s""""$k":$v""" }.mkString("{", ",", "}")},
       |"dim_user":${c.dimUser},"dim_product":${c.dimProduct},
       |"dim_user_attrs":${c.dimUserAttrs.mkString("[", ",", "]")},
       |"behavior":${c.behavior},"behavior_distinct_ids":${c.behaviorDistinctIds},
       |"behavior_bad_enum":${c.behaviorBadEnum},
       |"behavior_null_user_key":${c.behaviorNullUserKey},"behavior_null_product_key":${c.behaviorNullProductKey},
       |"behavior_null_category_key":${c.behaviorNullCategoryKey},
       |"behavior_key_mismatch":${c.behaviorKeyMismatch},"behavior_key_mismatch_sample":${c.behaviorKeyMismatchSample.mkString("[", ",", "]")},
       |"ods_trade_partitions":${c.odsTradePartitions.mkString("[", ",", "]")},
       |"behavior_city_level":${c.behaviorCityLevel.mkString("[", ",", "]")},
       |"behavior_category_id":${c.behaviorCategoryId.mkString("[", ",", "]")},
       |"reject":${c.rejectRows},"reject_groups_in_ods":${c.rejectIdGroupsInOds},
       |"reject_sample":${c.rejectSample.mkString("[", ",", "]")},
       |"order":${c.orderDetail},"order_distinct":${c.orderDistinctOrders},
       |"order_sentinels":${c.orderSentinels},"order_null_user_key":${c.orderNullUserKeys},
       |"order_null_product_key":${c.orderNullProductKeys},"order_null_category_key":${c.orderNullCategoryKeys},
       |"order_gap_max":${c.orderAmountGapMax},"order_line_mismatch":${c.orderLineSumMismatch},
       |"order_city_level":${c.orderCityLevel.mkString("[", ",", "]")},"order_key_type":"${c.orderKeyType}",
       |"order_multi_line_orders":${c.orderMultiLineOrders},
       |"order_partitions":${c.orderPartitions.mkString("[", ",", "]")},
       |"job_odl":${job(c.odl)},"job_dim":${job(c.dim)},"job_bdw":${job(c.bdw)},"job_tdw":${job(c.tdw)}}""".stripMargin

  private def job(r: JobResult): String =
    s"""{"in":${r.inputRecords},"out":${r.outputRecords},"rej":${r.rejectedRecords},"status":"${r.status}","msg":"${r.message}"}"""

  private case class Capture(
      tag: String,
      warehouse: String,
      namespace: String,
      sparkApp: String,
      dimRowsAtBdw: (Long, Long),
      dimInputAtBdw: Long,
      odsCounts: Map[String, Long],
      dimUser: Long,
      dimProduct: Long,
      dimUserAttrs: Seq[(String, String, String)],
      behavior: Long,
      behaviorDistinctIds: Long,
      behaviorBadEnum: Long,
      behaviorNullUserKey: Long,
      behaviorNullProductKey: Long,
      behaviorNullCategoryKey: Long,
      behaviorKeyMismatch: Long,
      behaviorKeyMismatchSample: Seq[String],
      odsTradePartitions: Seq[(String, Long)],
      behaviorCityLevel: Seq[(String, Long)],
      behaviorCategoryId: Seq[(Long, Long)],
      rejectRows: Long,
      rejectIdGroupsInOds: Long,
      rejectSample: Seq[String],
      orderDetail: Long,
      orderDistinctOrders: Long,
      orderSentinels: Long,
      orderNullUserKeys: Long,
      orderNullProductKeys: Long,
      orderNullCategoryKeys: Long,
      orderAmountGapMax: BigDecimal,
      orderLineSumMismatch: Long,
      orderCityLevel: Seq[(String, Long)],
      orderKeyType: String,
      orderMultiLineOrders: Long,
      orderPartitions: Seq[(String, Long)],
      odl: JobResult, dim: JobResult, bdw: JobResult, tdw: JobResult)

  private def scenario(tag: String, ns: WarehouseNamespace, appName: String,
                       steps: Seq[String]): Capture = {
    val spark = P2TestSupport.spark(appName)
    try {
      // sci：与既有 spec 逐字相同的建表路径（不自造 DDL 副本）
      LocalSchemaInitJob.statements(ns).foreach { case (_, ddl) => spark.sql(ddl) }

      def baseArgs(code: String): JobArgs = JobArgs.parse(Array(
        "--runtimeProfileId=1", s"--jobCode=$code", s"--businessDate=$BusinessDate", "--attemptNo=1",
        s"--hiveDatabasePrefix=${ns.prefix}",
        s"--sourceSystem=$SourceSystem",
        s"--landingDir=${P2TestSupport.goldenUri}",
        s"--batchId=$BatchId")).right.get

      var odl: JobResult = null
      var dim: JobResult = null
      var bdw: JobResult = null
      var tdw: JobResult = null

      /** bdw 开跑**之前**，本场景自己 namespace 下 dim 表的行数。
       *  非 0 ⇒ 该场景的 bdw 看到了「别人留下的/自己提前建好的」维度数据，A/B 对照被污染，用例必须红。
       *  这正是总控在 `r2` 后提出的污染风险，本探针把它变成可观测事实而不是假设。 */
      var dimRowsAtBdw: (Long, Long) = (-1L, -1L)
      var dimInputAtBdw: Long = -1L

      steps.foreach {
        case "odl" => odl = EventOdsLoadJob.instance.run(spark, baseArgs("odl"))
        case "dim" => dim = DimensionBuildJob.instance.run(spark, baseArgs("dim"))
        case "bdw" =>
          dimRowsAtBdw = spark.sql(s"SELECT COUNT(*) FROM ${ns.dim}.dim_user WHERE dt = '$BusinessDate'")
            .collect()(0).getLong(0) ->
            spark.sql(s"SELECT COUNT(*) FROM ${ns.dim}.dim_product WHERE dt = '$BusinessDate'")
              .collect()(0).getLong(0)
          dimInputAtBdw = spark.sql(s"SELECT COUNT(*) FROM ${ns.ods}.ods_user_event WHERE dt = '$BusinessDate'")
            .collect()(0).getLong(0) +
            spark.sql(s"SELECT COUNT(*) FROM ${ns.ods}.ods_product_event WHERE dt = '$BusinessDate'")
              .collect()(0).getLong(0)
          bdw = BehaviorDwdJob.instance.run(spark, baseArgs("bdw"))
        case "tdw" => tdw = TradeDwdJob.instance.run(spark, baseArgs("tdw"))
        case other => throw new IllegalArgumentException(s"未登记的场景步骤：$other")
      }
      Vector(odl, dim, bdw, tdw).foreach(r =>
        withClue(s"[$tag] 作业未执行或未成功：") {
          r should not be null
          r.status should be("SUCCESS")
        })

      def count(sql: String): Long = spark.sql(sql).collect()(0).getLong(0)
      def dist(sql: String): Seq[(String, Long)] = spark.sql(sql).collect()
        .map(r => r.getString(0) -> r.getLong(1)).toSeq

      val odsCounts = OdsV2Columns.OdsTables.map { t =>
        t -> count(s"SELECT COUNT(*) FROM ${ns.ods}.$t")
      }.toMap

      val dimUser = count(s"SELECT COUNT(*) FROM ${ns.dim}.dim_user WHERE dt = '$BusinessDate'")
      val dimProduct = count(s"SELECT COUNT(*) FROM ${ns.dim}.dim_product WHERE dt = '$BusinessDate'")
      val dimUserAttrs = spark.sql(
        s"SELECT CAST(user_id AS STRING) u, member_level m, city_level c FROM ${ns.dim}.dim_user " +
          s"WHERE dt = '$BusinessDate' ORDER BY user_id")
        .collect().map(r => (r.getString(0), r.getString(1), r.getString(2))).toSeq

      val dwdBehavior = s"${ns.dwd}.dwd_user_behavior_detail"
      val dwdOrder = s"${ns.dwd}.dwd_order_detail"
      val dwdReject = s"${ns.dwd}.dwd_reject_record"
      val odsBehavior = s"${ns.ods}.ods_behavior_event"

      val behavior = count(s"SELECT COUNT(*) FROM $dwdBehavior")
      val behaviorDistinctIds = count(s"SELECT COUNT(DISTINCT behavior_id) FROM $dwdBehavior")
      val behaviorBadEnum = count(s"SELECT COUNT(*) FROM $dwdBehavior WHERE behavior_type NOT IN " +
        BehaviorEnum.map(v => s"'$v'").mkString("(", ",", ")"))
      val behaviorNullUserKey = count(s"SELECT COUNT(*) FROM $dwdBehavior WHERE user_key IS NULL")
      val behaviorNullProductKey = count(s"SELECT COUNT(*) FROM $dwdBehavior WHERE product_key IS NULL")
      val behaviorNullCategoryKey = count(s"SELECT COUNT(*) FROM $dwdBehavior WHERE category_key IS NULL")
      val behaviorCityLevel = dist(
        s"SELECT COALESCE(city_level,'<NULL>') v, COUNT(*) c FROM $dwdBehavior GROUP BY 1 ORDER BY 1")
      val behaviorCategoryId = spark.sql(
        s"SELECT category_id, COUNT(*) c FROM $dwdBehavior GROUP BY 1 ORDER BY 1")
        .collect().map(r => r.getLong(0) -> r.getLong(1)).toSeq

      // 代理键对账（契约 `SurrogateKey` 是唯一所有者）：逐行与独立重算值比对。
      // 必须在**本场景的会话内**做——另开会话读不到本会话 in-memory catalog 里的表。
      val behaviorKeyPairs = spark.sql(
        s"""SELECT CAST(b.user_key AS STRING), CAST(b.product_key AS STRING),
           |       o.payload_user_id, o.payload_product_id
           |FROM $dwdBehavior b
           |JOIN $odsBehavior o ON o.event_id = b.behavior_id""".stripMargin)
        .collect().map(r => (r.getString(0), r.getString(1), r.getString(2), r.getString(3))).toSeq
      val behaviorKeyMismatchSample = behaviorKeyPairs.filter { case (uk, pk, uid, pid) =>
        val expUser = SurrogateKey.derive(SourceSystem, "user", uid).map(_.toString).getOrElse("<NULL键>")
        val expProduct = SurrogateKey.derive(SourceSystem, "product", pid).map(_.toString).getOrElse("<NULL键>")
        uk != expUser || pk != expProduct
      }.map { case (uk, pk, uid, pid) => s"$uid/$pid -> ($uk,$pk)" }
      val behaviorKeyMismatch = behaviorKeyMismatchSample.size.toLong

      // ODS 交易事件按 `event_time` 落 dt（迟到退款在 ODS 里属 20260902）
      val odsTradePartitions = dist(
        s"SELECT dt, COUNT(*) FROM ${ns.ods}.ods_trade_event GROUP BY dt ORDER BY dt")

      val rejectRows = count(s"SELECT COUNT(*) FROM $dwdReject")
      val rejectIdGroupsInOds = count(
        s"SELECT COUNT(*) FROM (SELECT event_id FROM $odsBehavior WHERE dt = '$BusinessDate' " +
          "GROUP BY event_id HAVING COUNT(*) > 1) g")
      val rejectSample = spark.sql(
        s"SELECT reject_reason, source_table FROM $dwdReject ORDER BY reject_id LIMIT 3")
        .collect().map(r => s"${r.getString(0)}|${r.getString(1)}").toSeq

      val orderDetail = count(s"SELECT COUNT(*) FROM $dwdOrder")
      val orderDistinctOrders = count(s"SELECT COUNT(DISTINCT order_id) FROM $dwdOrder")
      // 契约 rule：代理键恒在 [1, 2^63-1]，**永不等于** −1 哨兵（D-085/D-087）
      val orderSentinels = count(
        s"SELECT COUNT(*) FROM $dwdOrder WHERE user_key = -1 OR product_key = -1")
      val orderNullUserKeys = count(s"SELECT COUNT(*) FROM $dwdOrder WHERE user_key IS NULL")
      val orderNullProductKeys = count(s"SELECT COUNT(*) FROM $dwdOrder WHERE product_key IS NULL")
      val orderNullCategoryKeys = count(s"SELECT COUNT(*) FROM $dwdOrder WHERE category_key IS NULL")
      // §9.4 L345：SUM(quantity×unit_price − line_discount) 与 order_amount 绝对误差 < 0.01
      val orderAmountGapMax = BigDecimal(spark.sql(
        s"""SELECT COALESCE(MAX(ABS(line_sum - order_amount)), 0) FROM (
           |  SELECT order_id, order_amount,
           |         SUM(CAST(quantity AS DECIMAL(18,2)) * unit_price - discount) AS line_sum
           |  FROM $dwdOrder GROUP BY order_id, order_amount) t""".stripMargin)
        .collect()(0).getDecimal(0).toPlainString)
      val orderLineSumMismatch = count(
        s"""SELECT COUNT(*) FROM (
           |  SELECT order_id, order_amount,
           |         ABS(SUM(CAST(quantity AS DECIMAL(18,2)) * unit_price - discount)
           |             - order_amount) AS gap
           |  FROM $dwdOrder GROUP BY order_id, order_amount) t WHERE gap >= 0.01""".stripMargin)

      val orderCityLevel = dist(
        s"SELECT COALESCE(city_level,'<NULL>') v, COUNT(*) c FROM $dwdOrder GROUP BY 1 ORDER BY 1")
      val orderKeyType = spark.table(dwdOrder).schema.fields
        .filter(_.name == "user_key").map(_.dataType.catalogString).headOption.getOrElse("<缺列>")
      // 一单多行的订单数（用来解释「行数 7 ≠ 订单数 6」）
      val orderMultiLineOrders = count(
        s"SELECT COUNT(*) FROM (SELECT order_id FROM $dwdOrder GROUP BY order_id HAVING COUNT(*) > 1) g")
      // 数据驱动分区（§11.4 迟到退款按订单归属业务日重算历史分区）
      val orderPartitions = dist(s"SELECT dt, COUNT(*) FROM $dwdOrder GROUP BY dt ORDER BY dt")

      Capture(tag, s"${P2TestSupport.TempRoot}/p2-01-warehouse/$appName",
        ns.prefix, spark.sparkContext.appName, dimRowsAtBdw, dimInputAtBdw,
        odsCounts, dimUser, dimProduct, dimUserAttrs, behavior, behaviorDistinctIds,
        behaviorBadEnum, behaviorNullUserKey, behaviorNullProductKey, behaviorNullCategoryKey,
        behaviorKeyMismatch, behaviorKeyMismatchSample, odsTradePartitions,
        behaviorCityLevel, behaviorCategoryId, rejectRows, rejectIdGroupsInOds, rejectSample,
        orderDetail, orderDistinctOrders, orderSentinels, orderNullUserKeys, orderNullProductKeys,
        orderNullCategoryKeys, orderAmountGapMax, orderLineSumMismatch, orderCityLevel, orderKeyType,
        orderMultiLineOrders, orderPartitions, odl, dim, bdw, tdw)
    } finally {
      P2TestSupport.stop(spark)
    }
  }

  /** A：**在产编排顺序**（`SparkStageExecutor` BUILD_DWD = `bdw` → `dim` → `tdw`，按列表串行） */
  private lazy val A: Capture = scenario("A", NsA, AppA, Seq("odl", "bdw", "dim", "tdw"))

  /** B：**依赖直觉顺序**（`dim` 先于 `bdw`；其余不变） */
  private lazy val B: Capture = scenario("B", NsB, AppB, Seq("odl", "dim", "bdw", "tdw"))

  override def beforeAll(): Unit = {
    P2TestSupport.requireNonEmpty(P2TestSupport.goldenPath)
    A
    B
    ()
  }

  // ── 原始读数（进测试报告，作为场景 A/B 的实测取证）────────────────────
  // 缺陷现状：本组是「真跑结果的如实打印」，供报告引用；不作为业务期望。
  "实测读数（A/B 场景全量状态）" should "打印两个隔离域的真跑结果" in {
    info(dump(A))
    info(dump(B))
    info(s"[WAREHOUSE A] ${A.warehouse}")
    info(s"[WAREHOUSE B] ${B.warehouse}")
  }

  /**
   * 断言非空转的取证标记。
   *
   * 纪律：`info` 只被调用在**同一条用例的全部断言之后**——ScalaTest 的断言抛
   * `TestFailedException` 会终止该用例剩余语句，因此「标记出现」⟺「该用例的断言全部真的判过分」。
   * 本轮的 RED 运行（`r1`/`r2`）已实测这条机制：故意写错期望值时用例失败、
   * 且标记**不出现**（见 `.verify/v3-stage2/s2-05/s205-dim-dwd-chain/raw/r2-RED-observe-actual.log`）。
   */
  private def assertNonVacuous(tag: String): Unit = info(s"[ASSERTIONS-OK] $tag")

  // ══════════════════════════════════════════════════════════════════════
  // 场景 A：在产编排顺序 odl → bdw → dim → tdw
  // ══════════════════════════════════════════════════════════════════════

  "A 生产顺序" should "odl 真跑：四张 ODS 表逐表行数 + 合计 52（golden 55 行 − 3 行被拒）" in {
    A.odsCounts should be(ExpOds)
    A.odsCounts.values.sum should be(GoldenAccepted - GoldenDuplicateIdGroups)
    A.odsCounts.values.sum should be(A.odl.outputRecords)
    assertNonVacuous("odl-ods-rows")
  }

  it should "dim 真跑：dim_user=3 / dim_product=4（每业务键取最新建档事件，unknown 兜底不额外造行）" in {
    A.dimUser should be(ExpDimUser)
    A.dimProduct should be(ExpDimProduct)
  }

  it should "dim_user 取「最新」而非「首见」：user_id=1 是 12:00 的 platinum/tier1（不是 09:00 的 gold）" in {
    A.dimUserAttrs should contain(("1", "platinum", "tier1"))
    A.dimUserAttrs.map(_._1) should be(Seq("1", "2", "3"))
    A.dimUserAttrs should not contain (("1", "gold", "tier1"))
  }

  it should "bdw 真跑：dwd_user_behavior_detail 行数 = 14，behavior_id 去重后唯一（COUNT(*)=COUNT(DISTINCT)）" in {
    A.behavior should be(ExpBehavior)
    A.behaviorDistinctIds should be(A.behavior)
  }

  it should "bdw 真跑：行为枚举只含设计 §9.2 L310 的 5 个合法值（golden 的 purchase 被拒在表外）" in {
    A.behaviorBadEnum should be(0L)
  }

  it should "dwd_reject_record：真跑出行，DUPLICATE_EVENT 条数 == ODS 里重复 event_id 的组数（1）" in {
    A.rejectRows should be(GoldenDuplicateIdGroups)
    A.rejectIdGroupsInOds should be(GoldenDuplicateIdGroups)
    A.rejectSample should be(Seq("DUPLICATE_EVENT|ods_behavior_event"))
  }

  it should "tdw 真跑：dwd_order_detail 行数 = 7（6 订单，其中 1 单 2 个商品项）" in {
    A.orderDetail should be(ExpOrderDetail)
    A.orderDistinctOrders should be(ExpOrderCount)
    A.orderMultiLineOrders should be(1L)
    A.orderNullUserKeys should be(0L)
    A.orderNullProductKeys should be(0L)
    A.orderSentinels should be(0L)
    A.orderKeyType should be("bigint")
  }

  it should "tdw 分区口径：dwd_order_detail 按**订单归属业务日**落分区，7 行全在 20260901" in {
    // 晚到事件（golden-evt-045/046 的 09-02 退款）在 ODS 里属 20260902 分区，
    // 但 DWD 的行归属由 `orderDate = order_created.event_time`（OrderTradeCompiler:137）决定 ⇒ 仍是 20260901。
    A.orderPartitions should be(Seq("20260901" -> ExpOrderDetail))
    A.odsTradePartitions should be(Seq("20260901" -> 16L, "20260902" -> 2L))
    A.orderDetail should not be A.odsTradePartitions.map(_._2).sum
    A.tdw.outputRecords should be(A.orderPartitions.map(_._2).sum)
  }

  it should "tdw 对账：SUM(quantity×unit_price − line_discount) == order_amount，绝对误差 < 0.01（§9.4 L345）" in {
    A.orderAmountGapMax should be(BigDecimal(0))
    A.orderLineSumMismatch should be(0L)
  }

  // ══════════════════════════════════════════════════════════════════════
  // 场景 B：依赖直觉顺序 odl → dim → bdw → tdw
  // ══════════════════════════════════════════════════════════════════════

  "B 依赖直觉顺序" should "dim 自身结果与 A 逐行同值（顺序不影响 dim 的计算）" in {
    B.dimUser should be(A.dimUser)
    B.dimProduct should be(A.dimProduct)
    B.dimUserAttrs should be(A.dimUserAttrs)
  }

  it should "bdw 在 dim 之后真跑：city_level 全部取到真实维度值（tier1/tier2/tier3，无 unknown）" in {
    B.behaviorCityLevel should be(Seq("tier1" -> 5L, "tier2" -> 6L, "tier3" -> 3L))
    B.behaviorCityLevel.map(_._1) should not contain "unknown"
  }

  it should "bdw 在 dim 之后真跑：category_id 全部命中商品维（无 −1 哨兵）、category_key 全部非空" in {
    B.behaviorCategoryId should be(Seq(11L -> 7L, 12L -> 4L, 21L -> 3L))
    B.behaviorNullCategoryKey should be(0L)
  }

  it should "两场景的代理键列同值：user_key/product_key 不依赖 dim 是否已建" in {
    B.behaviorNullUserKey should be(0L)
    B.behaviorNullProductKey should be(0L)
    B.behavior should be(A.behavior)
    B.orderDetail should be(A.orderDetail)
    B.orderSentinels should be(0L)
  }

  it should "dwd_user_behavior_detail.user_key/product_key == SurrogateKey.derive 的期望值（契约算法为唯一所有者）" in {
    A.behaviorKeyMismatchSample should be(Seq.empty)
    A.behaviorKeyMismatch should be(0L)
    // 覆盖性：上式确实逐行比过（14 行行为明细，去重+非法枚举后）
    A.behavior should be(ExpBehavior)
    assertNonVacuous("surrogate-key-recompute")
  }

  // ══════════════════════════════════════════════════════════════════════
  // A/B 对照：编排顺序缺陷的实测后果（**缺陷现状、未修**；不作为期望行为）
  // ══════════════════════════════════════════════════════════════════════

  "A/B 隔离性（防止对照被污染的硬前置）" should
    "A 场景的 bdw 在「本 namespace 的 dim 一行都没有」的状态下运行，且 A/B 的 namespace 与 warehouse 互不相同" in {
    // 若这两行非 0，说明 A 的 bdw 看到了别处留下的维度数据 ⇒ 下面的 A/B 差值一律作废（用例即红）
    A.dimRowsAtBdw should be((0L, 0L))
    // 对照组：B 场景的 bdw 在 dim 建好之后跑，此时维度必须有行（否则 B 也是空跑）
    B.dimRowsAtBdw should be((ExpDimUser, ExpDimProduct))
    // 两场景的维度输入都是同一份 ODS（18 行），行数差异只来自执行顺序
    A.dimInputAtBdw should be(B.dimInputAtBdw)
    A.dimInputAtBdw should be(18L)
    A.namespace should not be B.namespace
    A.warehouse should not be B.warehouse
    A.sparkApp should not be B.sparkApp
    assertNonVacuous("isolation-guard")
  }

  "A/B 对照（缺陷现状，未修）" should "A 顺序下 bdw 的 dim 依赖列全部丢失：city_level=NULL、category_id=−1、category_key=NULL" in {
    A.behaviorCityLevel should be(Seq("<NULL>" -> ExpBehavior))
    A.behaviorCategoryId should be(Seq(-1L -> ExpBehavior))
    A.behaviorNullCategoryKey should be(ExpBehavior)
    A.behavior should be(B.behavior) // 行数不受影响：丢失的是**列值**，不是行
    // 同一份输入、同一套 SQL，只有编排顺序不同 ⇒ B 下三列全部有值
    B.behaviorCityLevel.map(_._1) should be(Seq("tier1", "tier2", "tier3"))
    B.behaviorCategoryId should be(Seq(11L -> 7L, 12L -> 4L, 21L -> 3L))
    B.behaviorNullCategoryKey should be(0L)
    assertNonVacuous("A-order-vs-B-order-behavior")
  }

  it should "tdw 的维度补充在 A/B 两顺序下都命中（tdw 总排在 dim 之后，city_level 无 'unknown' 兜底）" in {
    // 与 bdw 不同：两种顺序里 tdw 都在 dim 之后执行 ⇒ 维度 JOIN 都命中，观测不到差异。
    A.orderCityLevel should be(Seq("tier1" -> 3L, "tier2" -> 2L, "tier3" -> 2L))
    A.orderCityLevel.map(_._1) should not contain "unknown"
    B.orderCityLevel should be(A.orderCityLevel)
    // category_key 两场景都非空：JOIN 未命中时它是 NULL，而本夹具 7 行的商品 id 都能命中 dim_product
    A.orderNullCategoryKeys should be(0L)
    B.orderNullCategoryKeys should be(0L)
  }

  it should "A/B 唯一的差异面就是 dim 依赖列（行数、代理键、金额对账在两顺序下逐项相同）" in {
    A.behavior should be(B.behavior)
    A.behaviorDistinctIds should be(B.behaviorDistinctIds)
    A.behaviorBadEnum should be(B.behaviorBadEnum)
    A.behaviorNullUserKey should be(B.behaviorNullUserKey)
    A.behaviorNullProductKey should be(B.behaviorNullProductKey)
    A.rejectRows should be(B.rejectRows)
    A.orderDetail should be(B.orderDetail)
    A.orderAmountGapMax should be(B.orderAmountGapMax)
    A.dimUserAttrs should be(B.dimUserAttrs)
    assertNonVacuous("A/B-shared-metrics")
  }

  // ══════════════════════════════════════════════════════════════════════
  // JobResult 计数与表内实际行数一致（§5.3.1 计数口径）
  // ══════════════════════════════════════════════════════════════════════

  "JobResult 计数" should "odl：input=55 golden 行 / output=四表合计 52 / rejected=3" in {
    A.odl.inputRecords should be(GoldenLines)
    A.odl.outputRecords should be(A.odsCounts.values.sum)
    A.odl.rejectedRecords should be(GoldenRejected)
    A.odl.message should include("accepted=52")
    A.odl.message should include("rejectedVersionKeys=3")
  }

  it should "dim：input=18（用户 4 + 商品 14） / output=7（dim_user 3 + dim_product 4）" in {
    A.dim.inputRecords should be(
      A.odsCounts("ods_user_event") + A.odsCounts("ods_product_event"))
    A.dim.inputRecords should be(18L)
    A.dim.outputRecords should be(A.dimUser + A.dimProduct)
    A.dim.outputRecords should be(7L)
    A.dim.message should be("user=4->3 product=14->4")
  }

  it should "bdw：input=ODS 行为 16 / output=14 / rejected=1（与 reject 表行数一致）" in {
    A.bdw.inputRecords should be(A.odsCounts("ods_behavior_event"))
    A.bdw.inputRecords should be(16L)
    A.bdw.outputRecords should be(A.behavior)
    A.bdw.rejectedRecords should be(A.rejectRows)
  }

  it should "tdw：input=ODS 交易 18 / output=dwd_order_detail 7（两个归属日分区之和）" in {
    A.tdw.inputRecords should be(A.odsCounts("ods_trade_event"))
    A.tdw.inputRecords should be(18L)
    A.tdw.outputRecords should be(A.orderDetail)
    A.tdw.outputRecords should be(A.orderPartitions.map(_._2).sum)
  }
}
