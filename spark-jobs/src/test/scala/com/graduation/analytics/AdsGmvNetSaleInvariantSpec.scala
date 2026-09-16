package com.graduation.analytics

import com.graduation.analytics.job.{AdsQualityJob, LocalSchemaInitJob}
import com.graduation.analytics.sql.{AdsSql, DwsSql}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S3-22：ADS 大盘**同归属口径不变量**「GMV ≥ 净销售 ≥ 0」（在产质量门规则 8）。
 *
 * 设计标尺（逐字）：设计 §12.3 L506「8. 同归属口径 ADS GMV≥净销售≥0。」；
 * L512「每条规则记录作用域、阈值、版本、阶段、实际值、passed、原始/生效严重度。付款 vs 订单、
 * 订单项公式、DWD/DWS 对账三者独立，不能用一个 AMOUNT_RECONCILE 覆盖」——
 * 故本规则**独立成码**，不与第 9 项「UV ≤ PV」（同为大盘行不变量）也不与任何对账码合并；
 * 指导书 §7 阶段 3 L148「对每个指标固定粒度、分子分母、时间窗口、**金额/退款口径**、空值规则和版本」。
 *
 * 本层实现的口径声明（设计只给不变量与归属要求，落点与空值规则由本节声明并登记）：
 *  1. **作用域**：`ads_operation_overview__staging` 的**本次快照 + 本次 dt** 分区；
 *     该表按生产 SQL 恒为**单行/分区**（两列同一次聚合产出 ⇒ 「同归属口径」）。
 *  2. **判据**：`NOT (sale_amount >= net_sale_amount AND net_sale_amount >= 0)`；
 *     `check_count` = 被检查行数，`error_count` = 违反该式的行数。
 *  3. **空值规则**：任一金额列为 NULL ⇒ **不通过**（不可证明的不变量不得放行）。
 *     与既有 ADS 阻断口径一致：`AdsQualityJob.keyPredicates` 对 `ads_sale_trend` 已要求
 *     `sale_amount`/`net_sale_amount` 非空；本规则把同一空值口径补到大盘表（该表的关键列
 *     断言当前只覆盖 `pv/uv/dau`，金额两列**无任何守卫** —— 这正是本轮缺口）。
 *  4. **只判不改**：违规行不得被静默修正/回填/置 0，规则只产出结论（读侧不加启发式纠正）。
 *     「净销售 < 0」属口径破坏而非展示问题，故档位 BLOCKING（与漏斗跨层对账同族）。
 *
 * 判定方式（不读 SQL 文本下结论）：
 *  ① 真跑生产链路 `dwd_order_detail → DwsSql.tradeDay → AdsSql.operationOverview`（含一笔成功退款），
 *     断言规则**通过**且金额列确有值（非空跑绿）——证明生产 SQL 自身不会写出违规行；
 *  ② 四种破坏形态各自命中且互相独立：净销售 > GMV、净销售 < 0、GMV < 0、金额列为 NULL；
 *  ③ 边界：`GMV == 净销售`（无退款）与 `0 == 0`（当日无有效支付）必须**通过**（「≥」不是「>」）；
 *  ④ 快照隔离：同 dt 下**别的快照**的违规行不得影响本快照判定；
 *  ⑤ 违规行的实际值在判定后**原样保留**（只判不改）。
 *
 * 夹具与隔离仓库由 `P2TestSupport.spark` 提供，绝不触碰在产 `spark-warehouse`。
 */
class AdsGmvNetSaleInvariantSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val RuleCode = "ADS_GMV_NET_SALE_INVARIANT"

  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-gmv-net-sale-invariant")
    ns = WarehouseNamespace.of("dw_gmvnet")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  private def staging = AdsSql.staging(ns, "ads_operation_overview")

  /** 订单明细夹具：`(orderId, userId, amount, refundAmount, finalPaidFlag)`。 */
  private def writeOrders(dt: String, rows: Seq[(Long, Long, String, String, Int)]): Unit = {
    if (rows.isEmpty) return
    val row = (r: (Long, Long, String, String, Int)) =>
      s"""SELECT ${r._1}L AS order_id, ${r._2}L AS user_id, 1L AS product_id, 1L AS category_id,
         |       1 AS quantity, CAST(10.00 AS DECIMAL(18,2)) AS unit_price,
         |       CAST(0.00 AS DECIMAL(18,2)) AS discount, CAST(${r._3} AS DECIMAL(18,2)) AS amount,
         |       'PAID' AS order_status, CAST(NULL AS TIMESTAMP) AS order_time, '$dt' AS order_date,
         |       CAST(NULL AS STRING) AS city_level, CAST(NULL AS TIMESTAMP) AS paid_at,
         |       CAST(${r._3} AS DECIMAL(18,2)) AS order_amount,
         |       CAST(${r._3} AS DECIMAL(18,2)) AS paid_amount,
         |       CAST(${r._4} AS DECIMAL(18,2)) AS refund_amount,
         |       CAST(${r._3} AS DECIMAL(18,2)) AS net_paid_amount,
         |       ${r._5} AS final_paid_flag, 0 AS final_refunded_flag,
         |       CAST(NULL AS BIGINT) AS user_key, CAST(NULL AS BIGINT) AS product_key,
         |       CAST(NULL AS BIGINT) AS category_key""".stripMargin
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_order_detail PARTITION(dt = '$dt')
         |SELECT order_id, user_id, product_id, category_id, quantity, unit_price, discount, amount,
         |       order_status, order_time, order_date, city_level, paid_at, order_amount, paid_amount,
         |       refund_amount, net_paid_amount, final_paid_flag, final_refunded_flag,
         |       user_key, product_key, category_key FROM (
         |  ${rows.map(row).mkString(" UNION ALL ")}
         |) v""".stripMargin)
  }

  /** 行为明细：保证大盘的 pv/uv/dau 非空（本规则不判它们，但不许用空表冒充通过）。 */
  private def writeBehavior(dt: String, users: Seq[Long]): Unit = {
    if (users.isEmpty) return
    var id = 0L
    val row = (u: Long) => {
      id += 1
      s"""SELECT ${id}L AS behavior_id, ${u}L AS user_id, CAST(NULL AS BIGINT) AS product_id,
         |       CAST(NULL AS BIGINT) AS category_id, 'view' AS behavior_type,
         |       CAST(NULL AS TIMESTAMP) AS event_time, '$dt' AS event_date,
         |       CAST(NULL AS INT) AS event_hour, CAST(NULL AS STRING) AS city_level,
         |       CAST(NULL AS STRING) AS channel, CAST(NULL AS STRING) AS session_id,
         |       CAST(NULL AS BIGINT) AS source_batch_id, CAST(NULL AS BIGINT) AS user_key,
         |       CAST(NULL AS BIGINT) AS product_key, CAST(NULL AS BIGINT) AS category_key""".stripMargin
    }
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '$dt')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  ${users.map(row).mkString(" UNION ALL ")}
         |) v""".stripMargin)
  }

  /**
   * 构造一条**真实生产链路**的大盘行：`dwd_order_detail → dws_trade_day → ads_operation_overview`。
   * 两笔支付（10.00 + 20.00）其中一笔退款 2.00 ⇒ 期望 GMV = 30.00、净销售 = 28.00。
   */
  private def buildChainPartition(dt: String, sid: String): Unit = {
    writeBehavior(dt, Seq(1L, 2L))
    writeOrders(dt, Seq(
      (101L, 1L, "10.00", "0.00", 1),
      (102L, 2L, "20.00", "2.00", 1),
      (103L, 1L, "5.00", "0.00", 0))) // 未支付：不进 GMV/净销售
    spark.sql(DwsSql.tradeDay(ns, dt))
    spark.sql(DwsSql.userTradePeriod(ns, dt, dt, dt))
    spark.sql(AdsSql.operationOverview(ns, dt, Some(sid)))
  }

  /**
   * 写一条**金额可指定**的大盘行到独立快照分区（用于制造违反形态）。
   *
   * 为什么不用「复制基线行再改两列」：spark 禁止 `INSERT OVERWRITE TABLE t … SELECT … FROM t`
   * （实测 `cannotOverwriteTableThatIsBeingReadFromError`）；而动态拼投影列还会让
   * `FixtureWriteShapeSpec` 的静态写入形状守卫失去判别力（它要求**逐列命名**、与唯一所有者同序）。
   * 故此处按 `LocalSchemaInitJob` 的 `ads_operation_overview__staging` **唯一所有者列序**逐列命名写死：
   * 14 个非分区列（`snapshot_id`/`dt` 为静态分区），列名/列序由守卫（A3/A4）静态核对。
   * 金额两列由参数给出（`None` ⇒ NULL）；其余 12 列取固定的**非空合法**值，
   * 以便「空跑绿」不可能发生（夹具判别力：判据只读金额两列，但行本身不是空壳）。
   */
  private def writeOverview(dst: String, dt: String,
                            gross: Option[String], net: Option[String]): Unit = {
    def dec(v: Option[String]): String =
      v.map(x => s"CAST('$x' AS DECIMAL(18,2))").getOrElse("CAST(NULL AS DECIMAL(18,2))")
    spark.sql(
      s"""INSERT OVERWRITE TABLE $staging PARTITION(snapshot_id = '$dst', dt = '$dt')
         |SELECT 10L AS pv, 5L AS uv, 4L AS dau, 2L AS order_count,
         |       ${dec(gross)} AS sale_amount,
         |       ${dec(net)} AS net_sale_amount,
         |       CAST('15.00' AS DECIMAL(18,2)) AS avg_order_value,
         |       CAST('0.0667' AS DECIMAL(8,4)) AS refund_rate,
         |       CAST('0.0000' AS DECIMAL(8,4)) AS full_refund_rate,
         |       CAST('0.0000' AS DECIMAL(8,4)) AS repeat_rate,
         |       '20260901' AS repeat_period_start,
         |       '20260930' AS repeat_period_end,
         |       3L AS fav_cnt, 4L AS cart_add_cnt""".stripMargin)
  }

  private def check(sid: String, dt: String) = AdsQualityJob.gmvNetSaleInvariantCheck(spark, ns, sid, dt)

  private def amounts(sid: String, dt: String): (String, String) = {
    val r = spark.sql(
      s"SELECT sale_amount, net_sale_amount FROM $staging " +
        s"WHERE snapshot_id = '$sid' AND dt = '$dt'").collect().head
    (String.valueOf(r.get(0)), String.valueOf(r.get(1)))
  }

  private def rowsOf(sid: String, dt: String): Long =
    spark.sql(s"SELECT COUNT(*) FROM $staging WHERE snapshot_id = '$sid' AND dt = '$dt'")
      .collect().head.getLong(0)

  // ---------------------------------------------------------------- 用例

  "生产链路（dwd_order_detail → dws_trade_day → ads_operation_overview，含一笔成功退款）" should
    "通过规则，且金额列确有值（非空跑绿）" in {
    val dt = "20260901"
    buildChainPartition(dt, "S_GMV_CHAIN")

    val (gross, net) = amounts("S_GMV_CHAIN", dt)
    withClue(s"夹具判别力：GMV=$gross 净销售=$net 必须非空且不相等，否则本用例会因'两边都是 NULL'而假绿；") {
      gross should be("30.00")
      net should be("28.00")
    }

    val c = check("S_GMV_CHAIN", dt)
    c.ruleCode should be(RuleCode)
    c.severity should be("BLOCKING")
    c.layer should be("ADS_STAGING")
    c.targetTable should endWith("ads_operation_overview__staging")
    c.passed should be(true)
    c.checkCount should be(1L)
    c.errorCount should be(0L)
    c.detail should include("30.00")
    c.detail should include("28.00")
  }

  "净销售 > GMV（退款超过实付）" should "命中，并同时给出两列实际值" in {
    val dt = "20260902"
    buildChainPartition(dt, "S_GMV_BASE2")
    writeOverview("S_GMV_GT", dt, Some("10.00"), Some("20.00"))

    val c = check("S_GMV_GT", dt)
    c.passed should be(false)
    c.severity should be("BLOCKING")
    c.errorCount should be(1L)
    c.detail should include("10.00")
    c.detail should include("20.00")
    c.detail should include("net_sale_amount")
  }

  "净销售为负（成功退款 > 实付）" should "命中" in {
    val dt = "20260903"
    buildChainPartition(dt, "S_GMV_BASE3")
    writeOverview("S_GMV_NEGNET", dt, Some("100.00"), Some("-5.00"))

    val c = check("S_GMV_NEGNET", dt)
    c.passed should be(false)
    c.errorCount should be(1L)
    c.detail should include("-5.00")
  }

  "GMV 为负（两个金额列都越界）" should "命中" in {
    val dt = "20260904"
    buildChainPartition(dt, "S_GMV_BASE4")
    writeOverview("S_GMV_NEGGROSS", dt, Some("-10.00"), Some("-20.00"))

    val c = check("S_GMV_NEGGROSS", dt)
    c.passed should be(false)
    c.errorCount should be(1L)
    c.detail should include("-10.00")
  }

  "金额列为 NULL（未计算）" should "不通过：不可证明的不变量不得放行" in {
    val dt = "20260905"
    buildChainPartition(dt, "S_GMV_BASE5")
    writeOverview("S_GMV_NULLNET", dt, Some("30.00"), None)
    writeOverview("S_GMV_NULLBOTH", dt, None, None)

    val cNet = check("S_GMV_NULLNET", dt)
    cNet.passed should be(false)
    cNet.errorCount should be(1L)
    cNet.detail should include("NULL")

    val cBoth = check("S_GMV_NULLBOTH", dt)
    cBoth.passed should be(false)
    cBoth.errorCount should be(1L)
  }

  "GMV == 净销售（无成功退款）" should "通过（判据是「≥」不是「>」）" in {
    val dt = "20260906"
    buildChainPartition(dt, "S_GMV_BASE6")
    writeOverview("S_GMV_EQUAL", dt, Some("30.00"), Some("30.00"))

    val c = check("S_GMV_EQUAL", dt)
    c.passed should be(true)
    c.errorCount should be(0L)
  }

  "两列均为 0（当日无有效支付）" should "通过（0 ≥ 0）" in {
    val dt = "20260907"
    buildChainPartition(dt, "S_GMV_BASE7")
    writeOverview("S_GMV_ZERO", dt, Some("0.00"), Some("0.00"))

    val c = check("S_GMV_ZERO", dt)
    c.passed should be(true)
    c.errorCount should be(0L)
  }

  "同 dt 下别的快照存在违规行" should "不影响本快照判定（只读本次快照分区）" in {
    val dt = "20260908"
    buildChainPartition(dt, "S_GMV_BASE8")
    writeOverview("S_GMV_OTHERBAD", dt, Some("1.00"), Some("999.00"))

    val good = check("S_GMV_BASE8", dt)
    good.passed should be(true)
    good.errorCount should be(0L)

    val bad = check("S_GMV_OTHERBAD", dt)
    bad.passed should be(false)
    bad.errorCount should be(1L)
  }

  "违反不变量的行" should "被判定后原样保留（规则只判不改）" in {
    val dt = "20260909"
    buildChainPartition(dt, "S_GMV_BASE9")
    writeOverview("S_GMV_KEEP", dt, Some("1.00"), Some("2.00"))

    val c = check("S_GMV_KEEP", dt)
    c.passed should be(false)
    rowsOf("S_GMV_KEEP", dt) should be(1L)
    val (gross, net) = amounts("S_GMV_KEEP", dt)
    gross should be("1.00")
    net should be("2.00")
  }
}
