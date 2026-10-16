package com.graduation.analytics

import com.graduation.analytics.job.{AdsQualityJob, LocalSchemaInitJob}
import com.graduation.analytics.sql.{AdsSql, DwsSql}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.{Row, SparkSession}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * WP-F7d 回归测试：退款金额行粒度扇出修复（WP-F7c 发现 2）。
 *
 * 缺陷背景：
 * dwd_order_detail 为订单行（商品项）粒度，每行携带整单级字段 refund_amount/order_amount 等。
 * 原 DwsSql.tradeDay / regionSaleDay 对行粒度表直接执行 SUM(refund_amount)：
 * 当订单存在多行商品（≥2 行）且发生退款时，该整单退款金额被按照订单行数倍增（Σ(退款 × 行数)），
 * 造成 dws_trade_day.refund_amount 虚高、net_sale_amount 严重失真。
 *
 * 本测试断言：
 *  1. 多行订单（3 行）+ 部分退款：refund_amount 仅计算 1 次，不随行数倍增；
 *  2. 多行订单（2 行）+ 全额退款：refund_amount 仅计算 1 次；
 *  3. 单行订单无退款 + 未支付多行订单（带退款字段）：未支付单不计入；
 *  4. dws_trade_day 的 sale_amount, refund_amount, net_sale_amount, avg_order_value 精确闭环；
 *  5. 正对照判别力：证明旧口径直算会产生 420.00（放大 2.33 倍），而修复后严格为 180.00；
 *  6. 协同验证 dws_region_sale_day 同样消除多行订单扇出，且经 AdsQualityJob 对账与 dws_trade_day 逐分一致。
 */
class DwsTradeDayMultiLineRefundSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val dt = "20260918"
  private val snapshot = "S_WPF7D_REGRESSION"
  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("dws-trade-day-multiline-refund")
    ns = WarehouseNamespace.of("dw_wpf7d_reg")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }
    insertOrders()
  }

  override def afterAll(): Unit = {
    P2TestSupport.stop(spark)
  }

  /**
   * 构造夹具数据：
   *
   * 订单 201（已支付，3 行，部分退款 60.00）：
   *   行 1: prod 1001, cat 10, qty 1, amt 100.00, refund_amount 60.00, city_level 'tier1'
   *   行 2: prod 1002, cat 10, qty 2, amt 200.00, refund_amount 60.00, city_level 'tier1'
   *   行 3: prod 1003, cat 20, qty 1, amt 300.00, refund_amount 60.00, city_level 'tier1'
   *   小计: order_amount = 600.00, refund = 60.00
   *
   * 订单 202（已支付，2 行，全额退款 120.00）：
   *   行 1: prod 2001, cat 20, qty 1, amt 50.00, refund_amount 120.00, city_level 'tier2'
   *   行 2: prod 2002, cat 20, qty 1, amt 70.00, refund_amount 120.00, city_level 'tier2'
   *   小计: order_amount = 120.00, refund = 120.00, final_refunded_flag = 1
   *
   * 订单 203（已支付，1 行，无退款）：
   *   行 1: prod 1001, cat 10, qty 1, amt 80.00, refund_amount 0.00, city_level 'tier1'
   *   小计: order_amount = 80.00, refund = 0.00
   *
   * 订单 204（未支付/已取消，2 行，带退款残值 200.00）：
   *   行 1: prod 3001, cat 30, qty 1, amt 500.00, refund_amount 200.00, city_level 'tier3', final_paid_flag = 0
   *   行 2: prod 3002, cat 30, qty 1, amt 500.00, refund_amount 200.00, city_level 'tier3', final_paid_flag = 0
   *   小计: 未支付，不计入
   *
   * 期望汇总：
   *   order_count = 3 (201, 202, 203)
   *   buyer_count = 2 (user 1, user 2)
   *   sale_amount = 600.00 + 120.00 + 80.00 = 800.00
   *   refund_amount = 60.00 + 120.00 + 0.00 = 180.00 (旧缺陷口径：60*3 + 120*2 = 420.00)
   *   net_sale_amount = 800.00 - 180.00 = 620.00 (旧缺陷口径：800.00 - 420.00 = 380.00)
   *   avg_order_value = 800.00 / 3 = 266.67
   */
  private def insertOrders(): Unit = {
    val table = s"${ns.dwd}.dwd_order_detail"
    spark.sql(
      s"""INSERT INTO TABLE $table PARTITION (dt = '$dt') VALUES
         |-- Order 201: User 1, 3 lines, partial refund 60.00, tier1
         |(201, 1, 1001, 10, 1, CAST(100.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(100.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 09:00:00' AS TIMESTAMP), '2026-09-18', 'tier1', CAST('2026-09-18 09:05:00' AS TIMESTAMP), CAST(600.00 AS DECIMAL(18,2)), CAST(600.00 AS DECIMAL(18,2)), CAST(60.00 AS DECIMAL(18,2)), CAST(540.00 AS DECIMAL(18,2)), 1, 0, 1, 1001, 10),
         |(201, 1, 1002, 10, 2, CAST(100.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(200.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 09:00:00' AS TIMESTAMP), '2026-09-18', 'tier1', CAST('2026-09-18 09:05:00' AS TIMESTAMP), CAST(600.00 AS DECIMAL(18,2)), CAST(600.00 AS DECIMAL(18,2)), CAST(60.00 AS DECIMAL(18,2)), CAST(540.00 AS DECIMAL(18,2)), 1, 0, 1, 1002, 10),
         |(201, 1, 1003, 20, 1, CAST(300.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(300.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 09:00:00' AS TIMESTAMP), '2026-09-18', 'tier1', CAST('2026-09-18 09:05:00' AS TIMESTAMP), CAST(600.00 AS DECIMAL(18,2)), CAST(600.00 AS DECIMAL(18,2)), CAST(60.00 AS DECIMAL(18,2)), CAST(540.00 AS DECIMAL(18,2)), 1, 0, 1, 1003, 20),
         |-- Order 202: User 2, 2 lines, full refund 120.00, tier2
         |(202, 2, 2001, 20, 1, CAST(50.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(50.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 10:00:00' AS TIMESTAMP), '2026-09-18', 'tier2', CAST('2026-09-18 10:05:00' AS TIMESTAMP), CAST(120.00 AS DECIMAL(18,2)), CAST(120.00 AS DECIMAL(18,2)), CAST(120.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), 1, 1, 2, 2001, 20),
         |(202, 2, 2002, 20, 1, CAST(70.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(70.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 10:00:00' AS TIMESTAMP), '2026-09-18', 'tier2', CAST('2026-09-18 10:05:00' AS TIMESTAMP), CAST(120.00 AS DECIMAL(18,2)), CAST(120.00 AS DECIMAL(18,2)), CAST(120.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), 1, 1, 2, 2002, 20),
         |-- Order 203: User 1, 1 line, no refund, tier1
         |(203, 1, 1001, 10, 1, CAST(80.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(80.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 11:00:00' AS TIMESTAMP), '2026-09-18', 'tier1', CAST('2026-09-18 11:05:00' AS TIMESTAMP), CAST(80.00 AS DECIMAL(18,2)), CAST(80.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(80.00 AS DECIMAL(18,2)), 1, 0, 1, 1001, 10),
         |-- Order 204: User 3, 2 lines, unpaid (final_paid_flag = 0), refund 200.00, tier3
         |(204, 3, 3001, 30, 1, CAST(500.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(500.00 AS DECIMAL(18,2)), 'cancelled', CAST('2026-09-18 12:00:00' AS TIMESTAMP), '2026-09-18', 'tier3', CAST(NULL AS TIMESTAMP), CAST(1000.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(200.00 AS DECIMAL(18,2)), CAST(-200.00 AS DECIMAL(18,2)), 0, 0, 3, 3001, 30),
         |(204, 3, 3002, 30, 1, CAST(500.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(500.00 AS DECIMAL(18,2)), 'cancelled', CAST('2026-09-18 12:00:00' AS TIMESTAMP), '2026-09-18', 'tier3', CAST(NULL AS TIMESTAMP), CAST(1000.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(200.00 AS DECIMAL(18,2)), CAST(-200.00 AS DECIMAL(18,2)), 0, 0, 3, 3002, 30)""".stripMargin)
  }

  private def dec(r: Row, col: String): BigDecimal =
    BigDecimal(r.getAs[Any](col).toString)

  "DwsSql.tradeDay" should "多行订单 + 部分退款场景下退款金额只计一次，净销售额正确（消除行粒度扇出）" in {
    // 1. 验证正对照探针：证明旧口径直算必定发生多倍扇出
    val oldNaiveRefund = spark.sql(
      s"""SELECT SUM(CASE WHEN final_paid_flag = 1 THEN refund_amount ELSE 0 END)
         |FROM ${ns.dwd}.dwd_order_detail WHERE dt = '$dt'""".stripMargin).collect()(0).getAs[Any](0)
    BigDecimal(oldNaiveRefund.toString) should be(BigDecimal("420.00")) // 60*3 + 120*2 = 420.00 (扇出倍增)

    // 2. 执行修复后的 DWS 作业
    spark.sql(DwsSql.tradeDay(ns, dt))

    val trade = spark.table(s"${ns.dws}.dws_trade_day").where(s"dt = '$dt'").head()

    // 3. 核心断言：退款金额只计一次，净销售额正确
    trade.getAs[Long]("order_count") should be(3L)
    trade.getAs[Long]("buyer_count") should be(2L)
    dec(trade, "sale_amount") should be(BigDecimal("800.00"))
    dec(trade, "refund_amount") should be(BigDecimal("180.00"))      // 60.00 + 120.00，绝非 420.00
    dec(trade, "net_sale_amount") should be(BigDecimal("620.00"))   // 800.00 - 180.00，绝非 380.00
    dec(trade, "avg_order_value") should be(BigDecimal("266.67"))   // 800.00 / 3

    // 4. 下游透传验证：ads_sale_trend 与 dws_trade_day 严格一致
    spark.sql(AdsSql.saleTrend(ns, dt, Some(snapshot)))
    val saleTrend = spark.table(AdsSql.staging(ns, "ads_sale_trend"))
      .where(s"snapshot_id = '$snapshot' AND dt = '$dt'").head()
    dec(saleTrend, "sale_amount") should be(BigDecimal("800.00"))
    dec(saleTrend, "net_sale_amount") should be(BigDecimal("620.00"))
  }

  "DwsSql.regionSaleDay" should "多行订单场景下城市等级销售净额同样消除退款扇出，并与 dws_trade_day 对账闭环" in {
    spark.sql(DwsSql.regionSaleDay(ns, dt))
    spark.sql(AdsSql.regionSale(ns, dt, Some(snapshot)))

    val regions = spark.table(s"${ns.dws}.dws_region_sale_day").where(s"dt = '$dt'")
      .collect().map(r => r.getAs[String]("region") -> r).toMap

    regions.keySet should be(Set("tier1", "tier2"))

    // tier1: 订单 201 (600 - 60 = 540) + 订单 203 (80 - 0 = 80) -> sale 680, net 620
    dec(regions("tier1"), "sale_amount") should be(BigDecimal("680.00"))
    dec(regions("tier1"), "net_sale_amount") should be(BigDecimal("620.00")) // 旧口径：680 - 180 = 500
    regions("tier1").getAs[Long]("order_count") should be(2L)
    regions("tier1").getAs[Long]("buyer_count") should be(1L)

    // tier2: 订单 202 (120 - 120 = 0) -> sale 120, net 0
    dec(regions("tier2"), "sale_amount") should be(BigDecimal("120.00"))
    dec(regions("tier2"), "net_sale_amount") should be(BigDecimal("0.00"))   // 旧口径：120 - 240 = -120

    // 跨层对账：地区销售汇总与交易日汇总对账严格通过
    AdsQualityJob.regionSaleReconcileCheck(spark, ns, snapshot, dt).passed should be(true)
  }

  "DwsSql.tradeDay" should "在全天无有效支付订单时保持空值语义自洽" in {
    val emptyDt = "20260999"
    spark.sql(DwsSql.tradeDay(ns, emptyDt))
    val emptyRow = spark.table(s"${ns.dws}.dws_trade_day").where(s"dt = '$emptyDt'").head()

    emptyRow.getAs[Long]("order_count") should be(0L)
    emptyRow.getAs[Long]("buyer_count") should be(0L)
    emptyRow.isNullAt(emptyRow.fieldIndex("sale_amount")) should be(true)
    dec(emptyRow, "refund_amount") should be(BigDecimal("0.00"))
    emptyRow.isNullAt(emptyRow.fieldIndex("net_sale_amount")) should be(true)
    emptyRow.isNullAt(emptyRow.fieldIndex("avg_order_value")) should be(true)
  }
}
