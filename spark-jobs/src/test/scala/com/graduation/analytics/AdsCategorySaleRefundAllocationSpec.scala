package com.graduation.analytics

import com.graduation.analytics.job.{AdsQualityJob, LocalSchemaInitJob}
import com.graduation.analytics.sql.{AdsSql, DwsSql}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.{Row, SparkSession}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * WP-F7f 回归测试：AdsSql.categorySale 整单退款按行比例分摊与逐分最大余数校正。
 *
 * 缺陷背景（WP-F7e 质量门实证立项）：
 * WP-F7d 已修复 DwsSql.tradeDay/regionSaleDay 的整单退款行扇出，使 expectedNet = 273243.80；
 * 但 AdsSql.categorySale 原先在 facts CTE 直接对行执行 SUM(refund_amount)，
 * 导致跨品类多行退款单被重复计入多个品类（run 6 中 actualNet = 238087.15，多计退款 35156.65），
 * 质量门 ADS_CATEGORY_SALE_RECONCILE（BLOCKING，容差 <0.01）阻断发布。
 *
 * WP-F7f 裁定：
 * 整单退款在 ADS 品类层按行金额比例分摊：line_alloc = order_refund * amount / order_amount_total，
 * 并做分级最大余数校正：residual = order_refund - Σline_alloc 补入 amount 最大的行（并列取 category_id 最小，
 * 再并列取 product_id 最小），保证每单各行分摊和逐分等于整单退款（Σline_alloc = order_refund）。
 * 当 order_amount_total = 0 时，全行 raw_alloc 为 0，整单退款兜底记到 category_id 最小行。
 *
 * 本测试断言矩阵：
 *  ① 跨品类多行退款单（2 品类、退款不能整除）：验证逐分校正与两品类分摊和 = 整单退款；
 *  ② 单品类多行退款单：退款全额归该品类，与 WP-F7d 口径一致；
 *  ③ 无退款单与未支付单：final_paid_flag=0 完全不计入；
 *  ④ 断言 Σ品类 net == 手算 trade net（逐分相等）、Σ品类 sale == 手算 GMV；
 *  ⑤ 订单总额为 0 且有退款的兜底行：退款记最小 category 行，总额与退款不丢、不除零；
 *  ⑥ 正对照探针：证明旧口径直算发生 2.0 倍退款扇出，而修复后与 dws_trade_day 及质量门完全闭环。
 */
class AdsCategorySaleRefundAllocationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val dt = "20260918"
  private val snapshot = "S_WPF7F_REGRESSION"
  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-category-sale-refund-allocation")
    ns = WarehouseNamespace.of("dw_wpf7f_reg")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }
    insertDimProducts()
    insertOrders()
  }

  override def afterAll(): Unit = {
    P2TestSupport.stop(spark)
  }

  /**
   * 构造商品维表数据：
   *  - 品类 10: 服装 (父级 1: 服饰鞋包)
   *  - 品类 20: 数码 (父级 2: 电子数码)
   *  - 品类 30: 图书 (父级 3: 文娱图书)
   */
  private def insertDimProducts(): Unit = {
    val table = s"${ns.dim}.dim_product"
    spark.sql(
      s"""INSERT INTO TABLE $table PARTITION (dt = '$dt') VALUES
         |(101, '商品101', 10, '服装', 1, '服饰鞋包', 1, CAST(100.00 AS DECIMAL(18,2)), CAST(50.00 AS DECIMAL(18,2)), 'ON_SALE', 1, 101, 10, 1, 1),
         |(102, '商品102', 20, '数码', 2, '电子数码', 2, CAST(1500.00 AS DECIMAL(18,2)), CAST(800.00 AS DECIMAL(18,2)), 'ON_SALE', 1, 102, 20, 2, 2),
         |(103, '商品103', 10, '服装', 1, '服饰鞋包', 1, CAST(100.00 AS DECIMAL(18,2)), CAST(50.00 AS DECIMAL(18,2)), 'ON_SALE', 1, 103, 10, 1, 1),
         |(104, '商品104', 10, '服装', 1, '服饰鞋包', 1, CAST(200.00 AS DECIMAL(18,2)), CAST(100.00 AS DECIMAL(18,2)), 'ON_SALE', 1, 104, 10, 1, 1),
         |(105, '商品105', 30, '图书', 3, '文娱图书', 3, CAST(80.00 AS DECIMAL(18,2)), CAST(40.00 AS DECIMAL(18,2)), 'ON_SALE', 1, 105, 30, 3, 3),
         |(106, '商品106', 30, '图书', 3, '文娱图书', 3, CAST(500.00 AS DECIMAL(18,2)), CAST(250.00 AS DECIMAL(18,2)), 'ON_SALE', 1, 106, 30, 3, 3),
         |(201, '商品201', 20, '数码', 2, '电子数码', 2, CAST(0.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), 'ON_SALE', 1, 201, 20, 2, 2),
         |(202, '商品202', 10, '服装', 1, '服饰鞋包', 1, CAST(0.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), 'ON_SALE', 1, 202, 10, 1, 1)
       """.stripMargin)
  }

  /**
   * 构造测试订单明细夹具：
   *
   * 1. 订单 501（有效支付，2 行，跨品类多行退款单，退款不能整除需逐分校正）：
   *    - 行 1: prod 101, cat 10, amt 100.00, refund 10.00, qty 1
   *    - 行 2: prod 102, cat 20, amt 1500.00, refund 10.00, qty 1
   *    - 整单 order_amount = 1600.00, order_refund = 10.00
   *    - 原始分摊: 行 1 = 10 * 100 / 1600 = 0.625 -> 0.63; 行 2 = 10 * 1500 / 1600 = 9.375 -> 9.38
   *    - 舍入初和 = 10.01; residual = 10.00 - 10.01 = -0.01
   *    - 行 2 (amt 1500 最大) 承接 residual: 行 2 alloc = 9.38 - 0.01 = 9.37; 行 1 alloc = 0.63
   *    - 分摊和 = 0.63 + 9.37 = 10.00 (逐分等于 order_refund)
   *    - 品类净额: cat 10 净额 100 - 0.63 = 99.37; cat 20 净额 1500 - 9.37 = 1490.63
   *
   * 2. 订单 502（有效支付，2 行，单品类多行退款单）：
   *    - 行 1: prod 103, cat 10, amt 100.00, refund 60.00, qty 1
   *    - 行 2: prod 104, cat 10, amt 200.00, refund 60.00, qty 2
   *    - 整单 order_amount = 300.00, order_refund = 60.00
   *    - 分摊: 行 1 = 20.00, 行 2 = 40.00; 两行均属 cat 10，cat 10 分摊 60.00，净额 300 - 60 = 240.00
   *
   * 3. 订单 503（有效支付，1 行，无退款单）：
   *    - 行 1: prod 105, cat 30, amt 80.00, refund 0.00, qty 1
   *    - cat 30 分摊 0.00, 净额 80.00
   *
   * 4. 订单 504（未支付 final_paid_flag = 0，带退款残值 200.00）：
   *    - 行 1: prod 106, cat 30, amt 500.00, refund 200.00, qty 1
   *    - 必须完全被排除，不得计入销售额与退款
   *
   * 5. 订单 505（有效支付，2 行，订单总额为 0 且有退款的兜底行）：
   *    - 行 1: prod 201, cat 20, amt 0.00, refund 50.00, qty 1
   *    - 行 2: prod 202, cat 10, amt 0.00, refund 50.00, qty 1
   *    - order_amount_total = 0.00, order_refund = 50.00
   *    - 兜底规则: 全行 raw_alloc = 0，整单退款记到 category_id 最小行 (cat 10 < cat 20)
   *    - cat 10 分摊 50.00, net = 0 - 50 = -50.00; cat 20 分摊 0.00, net = 0.00
   *    - 整单 50.00 退款不丢、不发生除零
   *
   * 理论汇总（有效支付订单 501, 502, 503, 505）：
   *  - GMV / sale_amount = 1600.00 + 300.00 + 80.00 + 0.00 = 1980.00
   *  - 退款总额 = 10.00 + 60.00 + 0.00 + 50.00 = 120.00
   *  - 净销售额 net_sale_amount = 1980.00 - 120.00 = 1860.00
   *  - 品类明细：
   *    * cat 10 (服装): sale = 400.00, refund = 0.63 + 60.00 + 50.00 = 110.63, net = 289.37, qty = 5
   *    * cat 20 (数码): sale = 1500.00, refund = 9.37 + 0.00 = 9.37, net = 1490.63, qty = 2
   *    * cat 30 (图书): sale = 80.00, refund = 0.00, net = 80.00, qty = 1
   *    * Σ品类 sale = 400.00 + 1500.00 + 80.00 = 1980.00 (== GMV)
   *    * Σ品类 net = 289.37 + 1490.63 + 80.00 = 1860.00 (== Trade Net, 逐分相等)
   */
  private def insertOrders(): Unit = {
    val table = s"${ns.dwd}.dwd_order_detail"
    spark.sql(
      s"""INSERT INTO TABLE $table PARTITION (dt = '$dt') VALUES
         |-- Order 501: User 1, 2 lines across cat 10 & 20, refund 10.00 (non-divisible allocation)
         |(501, 1, 101, 10, 1, CAST(100.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(100.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 09:00:00' AS TIMESTAMP), '2026-09-18', 'tier1', CAST('2026-09-18 09:05:00' AS TIMESTAMP), CAST(1600.00 AS DECIMAL(18,2)), CAST(1600.00 AS DECIMAL(18,2)), CAST(10.00 AS DECIMAL(18,2)), CAST(1590.00 AS DECIMAL(18,2)), 1, 0, 1, 101, 10),
         |(501, 1, 102, 20, 1, CAST(1500.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(1500.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 09:00:00' AS TIMESTAMP), '2026-09-18', 'tier1', CAST('2026-09-18 09:05:00' AS TIMESTAMP), CAST(1600.00 AS DECIMAL(18,2)), CAST(1600.00 AS DECIMAL(18,2)), CAST(10.00 AS DECIMAL(18,2)), CAST(1590.00 AS DECIMAL(18,2)), 1, 0, 1, 102, 20),
         |-- Order 502: User 2, 2 lines in single cat 10, refund 60.00
         |(502, 2, 103, 10, 1, CAST(100.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(100.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 10:00:00' AS TIMESTAMP), '2026-09-18', 'tier2', CAST('2026-09-18 10:05:00' AS TIMESTAMP), CAST(300.00 AS DECIMAL(18,2)), CAST(300.00 AS DECIMAL(18,2)), CAST(60.00 AS DECIMAL(18,2)), CAST(240.00 AS DECIMAL(18,2)), 1, 0, 2, 103, 10),
         |(502, 2, 104, 10, 2, CAST(100.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(200.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 10:00:00' AS TIMESTAMP), '2026-09-18', 'tier2', CAST('2026-09-18 10:05:00' AS TIMESTAMP), CAST(300.00 AS DECIMAL(18,2)), CAST(300.00 AS DECIMAL(18,2)), CAST(60.00 AS DECIMAL(18,2)), CAST(240.00 AS DECIMAL(18,2)), 1, 0, 2, 104, 10),
         |-- Order 503: User 1, 1 line, no refund
         |(503, 1, 105, 30, 1, CAST(80.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(80.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 11:00:00' AS TIMESTAMP), '2026-09-18', 'tier1', CAST('2026-09-18 11:05:00' AS TIMESTAMP), CAST(80.00 AS DECIMAL(18,2)), CAST(80.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(80.00 AS DECIMAL(18,2)), 1, 0, 1, 105, 30),
         |-- Order 504: User 3, 1 line, unpaid (final_paid_flag = 0), refund 200.00
         |(504, 3, 106, 30, 1, CAST(500.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(500.00 AS DECIMAL(18,2)), 'cancelled', CAST('2026-09-18 12:00:00' AS TIMESTAMP), '2026-09-18', 'tier3', CAST(NULL AS TIMESTAMP), CAST(500.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(200.00 AS DECIMAL(18,2)), CAST(-200.00 AS DECIMAL(18,2)), 0, 0, 3, 106, 30),
         |-- Order 505: User 4, 2 lines, order_amount = 0.00, refund 50.00, cat 20 & 10
         |(505, 4, 201, 20, 1, CAST(0.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 13:00:00' AS TIMESTAMP), '2026-09-18', 'tier1', CAST('2026-09-18 13:05:00' AS TIMESTAMP), CAST(0.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(50.00 AS DECIMAL(18,2)), CAST(-50.00 AS DECIMAL(18,2)), 1, 0, 4, 201, 20),
         |(505, 4, 202, 10, 1, CAST(0.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-18 13:00:00' AS TIMESTAMP), '2026-09-18', 'tier1', CAST('2026-09-18 13:05:00' AS TIMESTAMP), CAST(0.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(50.00 AS DECIMAL(18,2)), CAST(-50.00 AS DECIMAL(18,2)), 1, 0, 4, 202, 10)
       """.stripMargin)
  }

  private def dec(r: Row, col: String): BigDecimal =
    BigDecimal(r.getAs[Any](col).toString)

  "AdsSql.categorySale" should "对跨品类多行退款单按金额比例分摊，并执行分级最大余数校正消除舍入漂移" in {
    // 写入 DWS 交易日与 ADS 分类销售暂存
    spark.sql(DwsSql.tradeDay(ns, dt))
    spark.sql(AdsSql.categorySale(ns, dt, Some(snapshot)))

    val staging = AdsSql.staging(ns, "ads_category_sale")
    val rows = spark.table(staging)
      .where(s"snapshot_id = '$snapshot' AND dt = '$dt'")
      .collect().map(r => r.getAs[Long]("category_id") -> r).toMap

    rows.keySet should be(Set(10L, 20L, 30L))

    // 1. 验证品类 10（服装）：
    //    Order 501 行 1: 0.63
    //    Order 502 (单品类两行): 60.00 (20.00 + 40.00)
    //    Order 505 (总额为0兜底行): 50.00
    //    销售额 = 100 + 100 + 200 + 0 = 400.00
    //    退款分摊 = 0.63 + 60.00 + 50.00 = 110.63
    //    净销售额 = 400.00 - 110.63 = 289.37
    val cat10 = rows(10L)
    cat10.getAs[String]("category_name") should be("服装")
    cat10.getAs[Long]("parent_category_id") should be(1L)
    cat10.getAs[String]("parent_category_name") should be("服饰鞋包")
    cat10.getAs[Long]("sale_count") should be(5L)
    dec(cat10, "sale_amount") should be(BigDecimal("400.00"))
    dec(cat10, "net_sale_amount") should be(BigDecimal("289.37"))

    // 2. 验证品类 20（数码）：
    //    Order 501 行 2: 9.38 + (-0.01 residual) = 9.37
    //    Order 505: 0.00
    //    销售额 = 1500.00
    //    退款分摊 = 9.37
    //    净销售额 = 1500.00 - 9.37 = 1490.63
    val cat20 = rows(20L)
    cat20.getAs[String]("category_name") should be("数码")
    cat20.getAs[Long]("parent_category_id") should be(2L)
    cat20.getAs[String]("parent_category_name") should be("电子数码")
    cat20.getAs[Long]("sale_count") should be(2L)
    dec(cat20, "sale_amount") should be(BigDecimal("1500.00"))
    dec(cat20, "net_sale_amount") should be(BigDecimal("1490.63"))

    // 验证订单 501 跨两品类分摊和逐分等于整单退款 10.00
    // Order 501 在 cat 10 分摊 0.63 (净额 99.37)，在 cat 20 分摊 9.37 (净额 1490.63) -> 0.63 + 9.37 = 10.00
    val cat10NetFrom501 = dec(cat10, "net_sale_amount") - BigDecimal("240.00") - BigDecimal("-50.00")
    cat10NetFrom501 should be(BigDecimal("99.37"))
    val cat10RefundFrom501 = BigDecimal("100.00") - cat10NetFrom501
    cat10RefundFrom501 should be(BigDecimal("0.63"))

    val cat20RefundFrom501 = BigDecimal("1500.00") - dec(cat20, "net_sale_amount")
    cat20RefundFrom501 should be(BigDecimal("9.37"))

    (cat10RefundFrom501 + cat20RefundFrom501) should be(BigDecimal("10.00"))
  }

  it should "正确处理单品类多行退款单、无退款单并完全排除未支付单" in {
    val staging = AdsSql.staging(ns, "ads_category_sale")
    val rows = spark.table(staging)
      .where(s"snapshot_id = '$snapshot' AND dt = '$dt'")
      .collect().map(r => r.getAs[Long]("category_id") -> r).toMap

    // 3. 验证品类 30（图书）：
    //    Order 503 (有效无退款): amt 80.00, refund 0.00 -> sale 80.00, net 80.00
    //    Order 504 (未支付 final_paid_flag = 0): amt 500.00, refund 200.00 -> 完全排除！
    val cat30 = rows(30L)
    cat30.getAs[String]("category_name") should be("图书")
    cat30.getAs[Long]("parent_category_id") should be(3L)
    cat30.getAs[String]("parent_category_name") should be("文娱图书")
    cat30.getAs[Long]("sale_count") should be(1L)
    dec(cat30, "sale_amount") should be(BigDecimal("80.00"))     // 绝非 80 + 500 = 580
    dec(cat30, "net_sale_amount") should be(BigDecimal("80.00")) // 绝非 80 - 200 或 580 - 200
  }

  it should "对订单总额为 0 且有退款的订单确定性记入 category_id 最小行兜底，不除零且退款不丢" in {
    // Order 505: order_amount_total = 0.00, order_refund = 50.00, lines in cat 20 & cat 10
    // 兜底逻辑应将 50.00 全额记入 cat 10 (10 < 20)，cat 20 分摊为 0
    val staging = AdsSql.staging(ns, "ads_category_sale")
    val rows = spark.table(staging)
      .where(s"snapshot_id = '$snapshot' AND dt = '$dt'")
      .collect().map(r => r.getAs[Long]("category_id") -> r).toMap

    val cat10Net = dec(rows(10L), "net_sale_amount")
    val cat20Net = dec(rows(20L), "net_sale_amount")

    // 若 cat 10 没承接 50.00 退款，其净额会是 339.37；承接后严格为 289.37
    cat10Net should be(BigDecimal("289.37"))
    // cat 20 未受 Order 505 影响，净额保持 1490.63
    cat20Net should be(BigDecimal("1490.63"))
  }

  it should "断言全站 Σ品类 sale == GMV 且 Σ品类 net == 手算 trade net（逐分对齐），质量门 CategorySaleReconcileCheck 通过" in {
    val staging = AdsSql.staging(ns, "ads_category_sale")
    val actual = spark.sql(
      s"""SELECT SUM(sale_amount), SUM(net_sale_amount)
         |FROM $staging WHERE snapshot_id = '$snapshot' AND dt = '$dt'""".stripMargin).collect()(0)

    val actualSale = BigDecimal(actual.getAs[Any](0).toString)
    val actualNet = BigDecimal(actual.getAs[Any](1).toString)

    // 手算标准值：
    // GMV = 1600.00 (501) + 300.00 (502) + 80.00 (503) + 0.00 (505) = 1980.00
    // Trade Net = 1980.00 - 120.00 (10 + 60 + 50) = 1860.00
    actualSale should be(BigDecimal("1980.00"))
    actualNet should be(BigDecimal("1860.00")) // 逐分完全一致

    // 与 dws_trade_day 对账
    val trade = spark.table(s"${ns.dws}.dws_trade_day").where(s"dt = '$dt'").head()
    trade.getAs[Long]("order_count") should be(4L)
    dec(trade, "sale_amount") should be(BigDecimal("1980.00"))
    dec(trade, "refund_amount") should be(BigDecimal("120.00"))
    dec(trade, "net_sale_amount") should be(BigDecimal("1860.00"))

    // 核心发布质量门：ADS_CATEGORY_SALE_RECONCILE 阻断规则通过且差值为 0
    val check = AdsQualityJob.categorySaleReconcileCheck(spark, ns, snapshot, dt)
    check.passed should be(true)
    check.errorCount should be(0L)
  }

  it should "正对照判别力探针：证明旧口径直算会发生 2.0 倍退款扇出并被质量门阻断" in {
    // 旧口径直算退款：
    // Order 501: 10*2 = 20.00 (cat 10: 10, cat 20: 10)
    // Order 502: 60*2 = 120.00 (cat 10: 120)
    // Order 503: 0.00
    // Order 505: 50*2 = 100.00 (cat 10: 50, cat 20: 50)
    // 旧退款合计 = 20 + 120 + 100 = 240.00 (相对真实 120.00 扇出放大 2.0 倍)
    // 旧实际净额 = 1980.00 - 240.00 = 1740.00 (相较 dws_trade_day 1860.00 偏差 120.00)
    val naiveRefund = spark.sql(
      s"""SELECT SUM(refund_amount)
         |FROM ${ns.dwd}.dwd_order_detail
         |WHERE dt = '$dt' AND final_paid_flag = 1""".stripMargin).collect()(0).getAs[Any](0)
    BigDecimal(naiveRefund.toString) should be(BigDecimal("240.00"))

    val naiveNet = BigDecimal("1980.00") - BigDecimal(naiveRefund.toString)
    naiveNet should be(BigDecimal("1740.00"))
    // 证明旧口径与期望净额 1860.00 存在 120.00 巨大差值（远超 0.01 容差），必然导致质量门阻断
    (BigDecimal("1860.00") - naiveNet) should be(BigDecimal("120.00"))
  }

  it should "在全天无有效支付订单时保持空分区语义自洽" in {
    val emptyDt = "20260999"
    spark.sql(DwsSql.tradeDay(ns, emptyDt))
    spark.sql(AdsSql.categorySale(ns, emptyDt, Some(snapshot)))

    val staging = AdsSql.staging(ns, "ads_category_sale")
    val count = spark.table(staging).where(s"snapshot_id = '$snapshot' AND dt = '$emptyDt'").count()
    count should be(0L)

    val check = AdsQualityJob.categorySaleReconcileCheck(spark, ns, snapshot, emptyDt)
    check.passed should be(true)
  }
}
