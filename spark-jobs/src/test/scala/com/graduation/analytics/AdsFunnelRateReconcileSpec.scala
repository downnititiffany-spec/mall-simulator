package com.graduation.analytics

import com.graduation.analytics.job.{AdsQualityJob, LocalSchemaInitJob}
import com.graduation.analytics.sql.AdsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S3-10：ADS 漏斗**率列**的跨层对账（在产质量门规则 7）。
 *
 * 缺口来源：S3-04 引入 `ads_behavior_funnel.overall_cart_rate`（加性迁移 V7）后，
 * `AdsQualityJob` 的在产阻断规则 `ADS_DWS_FUNNEL_RECONCILE` **只**对账 4 个 stage 的
 * `user_count` 汇总，**不校验** `overall_buy_rate`/`overall_cart_rate` 两个整体率列；
 * 等价断言只存在于 S3-04 的 spec 里（该缺口已登记为 S3-04 R-1，本轮关闭）。
 *
 * 判定方式（不读 SQL 文本下结论）：
 *  1. 通过**生产 SQL** `AdsSql.funnel` 把 DWS 全站行透传进 ADS 暂存分区，再跑生产函数
 *     `AdsQualityJob.funnelRateCheck`，断言通过 —— 证明「ADS 率列 = DWS 率列」在真实链路上成立；
 *  2. 三个率列族（`conversion_rate` / `overall_buy_rate` / `overall_cart_rate`）**各自**
 *     构造一处篡改，断言规则**独立命中**该列且不误报其它列（设计 §7.3.1 line 526 的
 *     「不同校验不能互相替代」精神：合并成一个"率对账"断言会让漏测被掩盖）；
 *  3. 空值规则：DWS 分母为 0 时率列必须是 NULL，ADS 同为 NULL **判等**（不得因 NULL 误报）；
 *     反过来 DWS 为 NULL 而 ADS 有值（含"拿 0 冒充无复购"）**必须命中**；
 *  4. 口径钉位：DWS 漏斗表带 `category_id`/`channel` 维度列，规则只允许对齐全站行
 *     （`category_id = -1 AND channel = 'all'`，S3-04 口径⑤）——
 *     存在非全站维度行时不得把维度行当全站口径参与比较。
 *
 * 夹具与隔离仓库由 `P2TestSupport.spark` 提供，绝不触碰在产 `spark-warehouse`。
 */
class AdsFunnelRateReconcileSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val RuleCode = "ADS_DWS_FUNNEL_RATE_RECONCILE"

  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-funnel-rate-reconcile")
    ns = WarehouseNamespace.of("dw_funrate")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  /** DWS 漏斗行（率列用 `Option`：分母为 0 时必须是 NULL，而不是 0）。 */
  private case class DwsFunnelRow(
      categoryId: Long,
      channel: String,
      view: Long,
      intent: Long,
      order: Long,
      pay: Long,
      intentRate: Option[String],
      orderRate: Option[String],
      payRate: Option[String],
      buyRate: Option[String],
      cartUsers: Long,
      cartRate: Option[String])

  /** ADS 漏斗暂存行（`conversion_rate` 是 stage 级率，另两列是整体率）。 */
  private case class AdsFunnelRow(
      stage: String,
      userCount: Long,
      conversion: Option[String],
      buy: Option[String],
      cart: Option[String])

  /** 全站行黄金夹具：100 浏览 / 40 意向 / 20 下单 / 10 支付 / 25 加购。 */
  private val StdDws = DwsFunnelRow(-1L, "all", 100L, 40L, 20L, 10L,
    Some("0.4000"), Some("0.5000"), Some("0.5000"), Some("0.1000"), 25L, Some("0.2500"))

  private def dec8(v: Option[String]): String =
    v.map(x => s"CAST($x AS DECIMAL(8,4))").getOrElse("CAST(NULL AS DECIMAL(8,4))")

  private def writeDwsFunnel(dt: String, rows: Seq[DwsFunnelRow]): Unit = {
    val selects = rows.map { r =>
      s"""SELECT ${r.categoryId}L AS category_id, '${r.channel}' AS channel,
         |       ${r.view}L AS view_users, ${r.intent}L AS intent_users,
         |       ${r.order}L AS order_users, ${r.pay}L AS pay_users,
         |       ${dec8(r.intentRate)} AS intent_rate, ${dec8(r.orderRate)} AS order_rate,
         |       ${dec8(r.payRate)} AS pay_rate, ${dec8(r.buyRate)} AS overall_buy_rate,
         |       ${r.cartUsers}L AS cart_users, ${dec8(r.cartRate)} AS cart_rate""".stripMargin
    }
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dws}.dws_behavior_funnel_day PARTITION(dt = '$dt')
         |SELECT category_id, channel, view_users, intent_users, order_users, pay_users,
         |       intent_rate, order_rate, pay_rate, overall_buy_rate, cart_users, cart_rate
         |FROM (${selects.mkString(" UNION ALL ")}) v""".stripMargin)
  }

  private def writeAdsStaging(sid: String, dt: String, rows: Seq[AdsFunnelRow]): Unit = {
    val selects = rows.map { r =>
      s"""SELECT '${r.stage}' AS stage, ${r.userCount}L AS user_count,
         |       ${dec8(r.conversion)} AS conversion_rate, ${dec8(r.buy)} AS overall_buy_rate,
         |       ${dec8(r.cart)} AS overall_cart_rate""".stripMargin
    }
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${AdsSql.staging(ns, "ads_behavior_funnel")}
         |PARTITION(snapshot_id = '$sid', dt = '$dt')
         |SELECT stage, user_count, conversion_rate, overall_buy_rate, overall_cart_rate
         |FROM (${selects.mkString(" UNION ALL ")}) v""".stripMargin)
  }

  /** 生产形状的 4 行 ADS 漏斗行（stage 级率 view 为 NULL，整体率 4 行重复携带）。 */
  private def funnelRows(buy: Option[String], cart: Option[String],
                         intent: Option[String], order: Option[String],
                         pay: Option[String]): Seq[AdsFunnelRow] =
    Seq(AdsFunnelRow("view", 100L, None, buy, cart),
      AdsFunnelRow("intent", 40L, intent, buy, cart),
      AdsFunnelRow("order", 20L, order, buy, cart),
      AdsFunnelRow("pay", 10L, pay, buy, cart))

  private def stdRows: Seq[AdsFunnelRow] =
    funnelRows(Some("0.1000"), Some("0.2500"), Some("0.4000"), Some("0.5000"), Some("0.5000"))

  private def check(sid: String, dt: String) = AdsQualityJob.funnelRateCheck(spark, ns, sid, dt)

  private def queryLong(sql: String): Seq[Long] = spark.sql(sql).collect().map(_.getLong(0))

  // ---------------------------------------------------------------- 用例

  "跨层率列一致（AdsSql.funnel 真实透传）" should "通过规则，且夹具率列确有值（非空跑绿）" in {
    val dt = "20260901"
    writeDwsFunnel(dt, Seq(StdDws))
    // 生产 SQL：ADS 只透传 DWS，不重算（S3-04 口径④）
    spark.sql(AdsSql.funnel(ns, dt, Some("S_FUNRATE_OK")))

    val nonNull = queryLong(
      s"""SELECT COUNT(*) FROM ${AdsSql.staging(ns, "ads_behavior_funnel")}
         |WHERE snapshot_id = 'S_FUNRATE_OK' AND dt = '$dt'
         |  AND overall_buy_rate IS NOT NULL AND overall_cart_rate IS NOT NULL""".stripMargin)
    withClue(s"夹具判别力：4 行整体率必须都非空，否则本用例会因'两边都是 NULL'而假绿：$nonNull") {
      nonNull should be(Seq(4L))
    }

    val c = check("S_FUNRATE_OK", dt)
    c.ruleCode should be(RuleCode)
    c.severity should be("BLOCKING")
    c.layer should be("ADS_STAGING")
    c.targetTable should endWith("ads_behavior_funnel__staging")
    c.passed should be(true)
    c.checkCount should be(12L)
    c.errorCount should be(0L)
    c.detail should include("12")
  }

  "ADS 端 overall_buy_rate 被改" should "只判该列不等，不误报另两列" in {
    val dt = "20260902"
    writeDwsFunnel(dt, Seq(StdDws))
    writeAdsStaging("S_FUNRATE_BUY", dt,
      funnelRows(Some("0.2000"), Some("0.2500"), Some("0.4000"), Some("0.5000"), Some("0.5000")))

    val c = check("S_FUNRATE_BUY", dt)
    c.passed should be(false)
    c.severity should be("BLOCKING")
    c.errorCount should be(4L)
    c.detail should include("overall_buy_rate")
    (c.detail should not include ("overall_cart_rate"))
    (c.detail should not include ("conversion_rate"))
  }

  "ADS 端 overall_cart_rate 被改" should "只判该列不等（S3-04 新增列的在产守卫）" in {
    val dt = "20260903"
    writeDwsFunnel(dt, Seq(StdDws))
    writeAdsStaging("S_FUNRATE_CART", dt,
      funnelRows(Some("0.1000"), Some("0.3000"), Some("0.4000"), Some("0.5000"), Some("0.5000")))

    val c = check("S_FUNRATE_CART", dt)
    c.passed should be(false)
    c.errorCount should be(4L)
    c.detail should include("overall_cart_rate")
    (c.detail should not include ("overall_buy_rate"))
  }

  "ADS 端单个 stage 的 conversion_rate 被改" should "只命中该 stage（逐 stage 定位，不整表报错）" in {
    val dt = "20260904"
    writeDwsFunnel(dt, Seq(StdDws))
    writeAdsStaging("S_FUNRATE_CONV", dt,
      funnelRows(Some("0.1000"), Some("0.2500"), Some("0.4000"), Some("0.9000"), Some("0.5000")))

    val c = check("S_FUNRATE_CONV", dt)
    c.passed should be(false)
    c.errorCount should be(1L)
    c.detail should include("order/conversion_rate")
    (c.detail should not include ("intent/conversion_rate"))
    (c.detail should not include ("overall_buy_rate"))
  }

  "DWS 分母为 0（率列 NULL）且 ADS 同为 NULL" should "判等通过，不得因 NULL 误报" in {
    val dt = "20260905"
    writeDwsFunnel(dt, Seq(DwsFunnelRow(-1L, "all", 0L, 0L, 0L, 0L,
      None, None, None, None, 0L, None)))
    writeAdsStaging("S_FUNRATE_NULL", dt,
      funnelRows(None, None, None, None, None))

    val c = check("S_FUNRATE_NULL", dt)
    c.passed should be(true)
    c.errorCount should be(0L)
    c.checkCount should be(12L)
  }

  "DWS 率列为 NULL 而 ADS 有值" should "命中（NULL 不得当 0、也不得当无复购）" in {
    val dt = "20260906"
    writeDwsFunnel(dt, Seq(DwsFunnelRow(-1L, "all", 0L, 0L, 0L, 0L,
      None, None, None, None, 0L, None)))
    // ADS 侧拿具体数值冒充「无复购」
    writeAdsStaging("S_FUNRATE_NULLVAL", dt,
      funnelRows(None, Some("0.2500"), None, None, None))

    val c = check("S_FUNRATE_NULLVAL", dt)
    c.passed should be(false)
    c.errorCount should be(4L)
    c.detail should include("overall_cart_rate")
    c.detail should include("DWS=NULL")
  }

  "DWS 存在非全站维度行" should "只对齐全站行（category_id=-1/channel='all'），不被维度行带偏" in {
    val dt = "20260907"
    // 维度行刻意给出与全站完全不同的率：若实现按 SUM/MAX/任意行取 DWS 侧，必然红
    writeDwsFunnel(dt, Seq(
      StdDws,
      DwsFunnelRow(5L, "web", 50L, 20L, 10L, 5L,
        Some("0.1111"), Some("0.2222"), Some("0.3333"), Some("0.4444"), 7L, Some("0.5555"))))
    writeAdsStaging("S_FUNRATE_DIM", dt, stdRows)

    val c = check("S_FUNRATE_DIM", dt)
    c.passed should be(true)
    c.errorCount should be(0L)
    c.checkCount should be(12L)
  }
}
