package com.graduation.analytics

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.graduation.analytics.job._
import com.graduation.analytics.sql.{AdsSql, SurrogateKey}
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
 * WP-N1B Deliverable 1: Source-B Spark warehouse chain + seam + per-layer reconciliation.
 *
 * Runs the full chain for source B in the `fxsb` namespace (Decision D-023: warehousePrefix=fxsb):
 *   sci → odl → dim → bdw → tdw → usw → fna → dqc → pub
 *
 * Lead rulings:
 *  - R-N1B-1: Drive jobs directly in dependency order; do not use PipelineService orchestration.
 *  - R-N1B-2: No production code changes; local Spark evidence ceiling (D-126).
 *  - R-N1B-3: Proves chain on local Spark with in-memory catalog (test domain ceiling).
 *
 * Every expected value is traced to a section of fixtures/source-b/fixture-shop-b/ORACLE.md.
 */
class SourceBChainExecSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  import SourceBChainExecSpec._

  private var spark: SparkSession = _
  private var cap: Capture = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark(SuiteName)
    cap = try {
      collectAll(spark)
    } catch {
      case t: Throwable =>
        println(s"[wp-n1b] SourceBChainExecSpec execution failed: ${t.getClass.getName}: ${t.getMessage}")
        t.getStackTrace.take(20).foreach(e => println(s"[wp-n1b]   at $e"))
        Capture.failed(s"${t.getClass.getName}: ${t.getMessage}")
    }
  }

  override def afterAll(): Unit = {
    P2TestSupport.stop(spark)
  }

  // ══════════════════════════════════════════════════════════════════════
  // 1. Seam & Ingestion Manifest
  // ══════════════════════════════════════════════════════════════════════

  "WP-N1B 1. Seam assertions (manifests/1.json → EventOdsLoadJob)" should {

    "manifests/1.json 必须符合生产采集输出契约：sourceCode=fixture-shop-b, status=READY, acceptedRecords=38" in {
      withClue(s"manifestPath=${cap.manifestPath} manifest=${cap.manifestNode}：") {
        cap.manifestNode should not be null
        // WP-N1B Deliverable 1 item 1
        cap.manifestSourceCode should be("fixture-shop-b")
        cap.manifestStatus should be("READY")
        cap.manifestAcceptedRecords should be(38L)
        cap.manifestQuarantinedRecords should be(5L)
        cap.manifestBatchId should be(1L)
      }
    }

    "EventOdsLoadJob 参数严格取自清单解析值（--batchId, --sourceSystem, --landingDir 指向 accepted/1）" in {
      // WP-N1B Deliverable 1 item 1: parameters taken from manifest, not literals
      cap.odlArgs.extra("batchId") should be(cap.manifestBatchId.toString)
      cap.odlArgs.extra("sourceSystem") should be(cap.manifestSourceCode)
      cap.odlArgs.extra("landingDir") should include("accepted/1")
      cap.step("odl").status should be("SUCCESS")
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 2. ODS 层断言
  // ══════════════════════════════════════════════════════════════════════

  "WP-N1B 2. ODS 贴源层（fxsb_ods）" should {

    "总行数 == manifest.acceptedRecords == 38（ORACLE.md §1 夹具文件身份）" in {
      // ORACLE.md §1: normal.jsonl 38 行规范化入仓
      withClue(s"ODS 各主题行数=${cap.odsTableCounts}：") {
        cap.totalOdsRows should be(38L)
        cap.totalOdsRows should be(cap.manifestAcceptedRecords)
      }
    }

    "dt 分区分布严格匹配 ORACLE.md §1 & §4.1：36 行 on 2026-09-18，2 行 on 2026-09-19" in {
      // ORACLE.md §1 & §4.1: dt=20260918 x 36 行, dt=20260919 x 2 行
      // (B-EVT-0035 跨日支付落在 trade, B-EVT-0036 次日浏览落在 behavior)
      withClue(s"ODS 全主题按 dt 分布=${cap.odsDtDistribution}：") {
        cap.odsDtDistribution.getOrElse("20260918", -1L) should be(36L)
        cap.odsDtDistribution.getOrElse("20260919", -1L) should be(2L)
      }
    }

    "全部 ODS 行注入列 source_system == 'fixture-shop-b'（ORACLE.md §4.1）" in {
      // ORACLE.md §4.1: source_system 全部 = fixture-shop-b (D-056 平台通道注入)
      withClue(s"ODS source_system 异常行数=${cap.odsNonSourceBCount}：") {
        cap.odsNonSourceBCount should be(0L)
      }
    }

    "故障隔离：fault.jsonl 的可标识事件号全隔离，绝不出现在 ODS（ORACLE.md §5）" in {
      // ORACLE.md §5: F001/F002/F003/F005 carry evt_no values; F004 has none
      // Identifiable fault evt_nos: B-EVT-F001, B-EVT-F002, B-EVT-F003, B-EVT-F005
      withClue(s"ODS 出现故障事件号（跨接缝负例泄漏）=${cap.leakedFaultEventIds}：") {
        cap.leakedFaultEventIds should be(empty)
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 3. DIM 维度层断言
  // ══════════════════════════════════════════════════════════════════════

  "WP-N1B 3. DIM 维度层（fxsb_dim，dt=20260918）" should {

    "dim_user 生成 4 个用户快照且属性与 ORACLE.md §3 逐人一致" in {
      // ORACLE.md §3 (0001-0004 MEMBER_JOINED) & §4.3 (U1..U4 四买家)
      cap.step("dim").status should be("SUCCESS")
      withClue(s"dim_user 快照=${cap.dimUsers}：") {
        cap.dimUsers.size should be(4)
        val byId = cap.dimUsers.map(u => u.userId -> u).toMap

        // U1: 2100917055761973250, age 35-44, tier3, silver (ORACLE.md §3)
        val u1 = byId.getOrElse("2100917055761973250", fail("缺少 U1"))
        u1.ageGroup should be("35-44")
        u1.cityLevel should be("tier3")
        u1.memberLevel should be("silver")

        // U2: 2100917057120927745, age 25-34, tier1, gold (ORACLE.md §3)
        val u2 = byId.getOrElse("2100917057120927745", fail("缺少 U2"))
        u2.ageGroup should be("25-34")
        u2.cityLevel should be("tier1")
        u2.memberLevel should be("gold")

        // U3: 2100917057850736642, age 18-24, tier2, normal (ORACLE.md §3)
        val u3 = byId.getOrElse("2100917057850736642", fail("缺少 U3"))
        u3.ageGroup should be("18-24")
        u3.cityLevel should be("tier2")
        u3.memberLevel should be("normal")

        // U4: 2100917056193986562, age 45+, tier3, platinum (ORACLE.md §3)
        val u4 = byId.getOrElse("2100917056193986562", fail("缺少 U4"))
        u4.ageGroup should be("45+")
        u4.cityLevel should be("tier3")
        u4.memberLevel should be("platinum")
      }
    }

    "dim_product 生成 3 个商品快照且品名/价格与 ORACLE.md §3 逐项一致" in {
      // ORACLE.md §3 (0005-0007 ITEM_LISTED)
      withClue(s"dim_product 快照=${cap.dimProducts}：") {
        cap.dimProducts.size should be(3)
        val byId = cap.dimProducts.map(p => p.productId -> p).toMap

        // 1001: B-轻量保温杯, cat 11, price 29.90 (ORACLE.md §3)
        val p1 = byId.getOrElse("1001", fail("缺少 1001"))
        p1.productName should be("B-轻量保温杯")
        p1.price should be(BigDecimal("29.90"))
        p1.categoryId should be(11L)

        // 1002: B-碳纤维键盘托, cat 22, price 59.80 (ORACLE.md §3)
        val p2 = byId.getOrElse("1002", fail("缺少 1002"))
        p2.productName should be("B-碳纤维键盘托")
        p2.price should be(BigDecimal("59.80"))
        p2.categoryId should be(22L)

        // 1003: B-降噪耳机, cat 22, price 298.00 (ORACLE.md §3)
        val p3 = byId.getOrElse("1003", fail("缺少 1003"))
        p3.productName should be("B-降噪耳机")
        p3.price should be(BigDecimal("298.00"))
        p3.categoryId should be(22L)
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 4. DWD 明细层断言
  // ══════════════════════════════════════════════════════════════════════

  "WP-N1B 4. DWD 明细层（fxsb_dwd，dt=20260918）" should {

    "dwd_user_behavior_detail 恰有 10 行（ORACLE.md §4.2：0008-0015 + 0037 + 0038）" in {
      // ORACLE.md §4.2: dwd_user_behavior_detail dt=20260918 10 行
      cap.step("bdw").status should be("SUCCESS")
      withClue(s"DWD 行为明细行数=${cap.dwdBehaviorCount}：") {
        cap.dwdBehaviorCount should be(10L)
      }
    }

    "dwd_order_detail 恰有 9 行（ORACLE.md §4.2：8 订单，O7 两行商品展开）" in {
      // ORACLE.md §4.2: dwd_order_detail dt=20260918 9 行 (8 订单、O7 两行商品)
      cap.step("tdw").status should be("SUCCESS")
      withClue(s"DWD 订单明细行数=${cap.dwdOrderCount}：") {
        cap.dwdOrderCount should be(9L)
      }
    }

    "dwd_order_detail 中 final_paid_flag=1 恰有 8 行（ORACLE.md §4.2：O5 取消未支付除外）" in {
      // ORACLE.md §4.2: final_paid_flag=1 的行 8 行 (O5 除外)
      withClue(s"DWD 有效支付订单明细行数=${cap.dwdOrderPaidCount}：") {
        cap.dwdOrderPaidCount should be(8L)
      }
    }

    "代理键 user_key / product_key 完整填充且符合 SurrogateKey 契约（D-083..D-092）" in {
      withClue(s"代理键非空校验：nullUserKeys=${cap.dwdBehaviorNullUserKeys}, nullProductKeys=${cap.dwdBehaviorNullProductKeys}：") {
        cap.dwdBehaviorNullUserKeys should be(0L)
        cap.dwdBehaviorNullProductKeys should be(0L)
        cap.dwdOrderNullUserKeys should be(0L)
        cap.dwdOrderNullProductKeys should be(0L)
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 5. DWS 汇总层断言（含独立 in-test collection 重算对账）
  // ══════════════════════════════════════════════════════════════════════

  "WP-N1B 5. DWS 汇总层（fxsb_dws，dt=20260918）" should {

    "dws_trade_day 匹配 ORACLE.md §4.3 手算预期：7 单/4 买家/销售 1222.90/退款 398.00/净销售 824.90/客单 174.70" in {
      // ORACLE.md §4.3: dws_trade_day
      // 金额算术：sale = 59.80+59.80+298.00+298.00+119.60+89.70+298.00 = 1222.90
      // refund = 100.00+298.00 = 398.00; net = 824.90; aov = 1222.90/7 ≈ 174.70
      cap.step("usw").status should be("SUCCESS")
      val t = cap.measuredDws.trade.getOrElse(fail("缺少 dws_trade_day 读数"))
      t.orderCount should be(7L)
      t.buyerCount should be(4L)
      t.saleAmount should be(BigDecimal("1222.90"))
      t.refundAmount should be(BigDecimal("398.00"))
      t.netSaleAmount should be(BigDecimal("824.90"))
      t.avgOrderValue should be(Some(BigDecimal("174.70")))
    }

    "dws_behavior_funnel_day 匹配 ORACLE.md §4.3 手算预期：view 4/intent 2/cart 1/order 4/pay 4" in {
      // ORACLE.md §4.3: dws_behavior_funnel_day
      val f = cap.measuredDws.funnel.getOrElse(fail("缺少 dws_behavior_funnel_day 读数"))
      f.viewUsers should be(4L)
      f.intentUsers should be(2L)
      f.cartUsers should be(1L)
      f.orderUsers should be(4L)
      f.payUsers should be(4L)
      f.intentRate should be(Some(BigDecimal("0.5000")))
      f.orderRate should be(Some(BigDecimal("2.0000")))
      f.payRate should be(Some(BigDecimal("1.0000")))
      f.overallBuyRate should be(Some(BigDecimal("1.0000")))
      f.cartRate should be(Some(BigDecimal("0.2500")))
    }

    "dws_user_behavior_day 匹配 ORACLE.md §4.3 逐人行为手算值（U1..U4）" in {
      // ORACLE.md §4.3: dws_user_behavior_day
      // U1: pv3/fav0/cart0/hours2/buy2
      // U2: pv3/fav0/cart1/hours2/buy3（ORACLE 勘误 2026-10-16）
      // U3: pv1/fav1/cart0/hours1/buy3
      // U4: pv1/fav0/cart0/hours1/buy2
      val m = cap.measuredDws.userBehavior.map(b => b.userId -> b).toMap
      m(2100917055761973250L) should be(UserBehaviorRow(2100917055761973250L, 3, 0, 0, 0, 2, 2))
      m(2100917057120927745L) should be(UserBehaviorRow(2100917057120927745L, 3, 0, 1, 0, 2, 3))
      m(2100917057850736642L) should be(UserBehaviorRow(2100917057850736642L, 1, 1, 0, 0, 1, 3))
      m(2100917056193986562L) should be(UserBehaviorRow(2100917056193986562L, 1, 0, 0, 0, 1, 2))
    }

    "dws_product_behavior_day 匹配 ORACLE.md §4.3 逐商品行为手算值（1001..1003）" in {
      // ORACLE.md §4.3: dws_product_behavior_day
      // 1001: pv3/uv2/fav1/cart0/buy3
      // 1002: pv3/uv2/fav0/cart1/buy4（ORACLE 勘误 2026-10-16）
      // 1003: pv2/uv2/fav0/cart0/buy3
      val m = cap.measuredDws.productBehavior.map(b => b.productId -> b).toMap
      val p1 = m(1001L); p1.pv should be(3L); p1.uv should be(2L); p1.fav should be(1L); p1.cart should be(0L); p1.buy should be(3L)
      val p2 = m(1002L); p2.pv should be(3L); p2.uv should be(2L); p2.fav should be(0L); p2.cart should be(1L); p2.buy should be(4L)
      val p3 = m(1003L); p3.pv should be(2L); p3.uv should be(2L); p3.fav should be(0L); p3.cart should be(0L); p3.buy should be(3L)
    }

    "dws_product_sale_day 匹配独立口径：1001 89.70元/1002 239.20元/1003 894.00元（核对 ORACLE.md §4.3 分元口径歧义与 2026-10-16 勘误）" in {
      // ORACLE.md §4.3 table 写 1001: 8970.00 / 1002: 17940.00 / 1003: 89400.00（分值带.00印刷歧义）
      // 经 §4.3 金额算术 sale=1222.90 与 dws_trade_day 交叉核算，标准元值为 89.70 / 179.40 / 894.00
      val m = cap.measuredDws.productSale.map(s => s.productId -> s).toMap
      m(1001L) should be(ProductSaleRow(1001L, 11L, 3L, BigDecimal("89.70"), 2L))
      m(1002L) should be(ProductSaleRow(1002L, 22L, 4L, BigDecimal("239.20"), 2L))
      m(1003L) should be(ProductSaleRow(1003L, 22L, 3L, BigDecimal("894.00"), 2L))
    }

    "dws_user_trade_period 匹配 ORACLE.md §4.3 逐人交易周期手算值（U1..U4，全退单剔除）" in {
      // ORACLE.md §4.3: dws_user_trade_period
      // U1: 1单/59.80/valid1; U2: 2单/179.40/valid2; U3: 2单/387.70/valid2; U4: 2单/596.00/valid1(O4全退剔除)
      val m = cap.measuredDws.userPeriod.map(u => u.userId -> u).toMap
      val u1 = m(2100917055761973250L); u1.orderCount should be(1L); u1.saleAmount should be(BigDecimal("59.80")); u1.validOrderCount should be(1L)
      val u2 = m(2100917057120927745L); u2.orderCount should be(2L); u2.saleAmount should be(BigDecimal("179.40")); u2.validOrderCount should be(2L)
      val u3 = m(2100917057850736642L); u3.orderCount should be(2L); u3.saleAmount should be(BigDecimal("387.70")); u3.validOrderCount should be(2L)
      val u4 = m(2100917056193986562L); u4.orderCount should be(2L); u4.saleAmount should be(BigDecimal("596.00")); u4.validOrderCount should be(1L)
    }

    "dws_region_sale_day 匹配 ORACLE.md §4.3 地区销售手算值（tier1/tier2/tier3）" in {
      // ORACLE.md §4.3: dws_region_sale_day
      // tier1: 买家1/单2/179.40/179.40; tier2: 买家1/单2/387.70/287.70; tier3: 买家2/单3/655.80/357.80
      val m = cap.measuredDws.regionSale.map(r => r.region -> r).toMap
      m("tier1") should be(RegionSaleRow("tier1", 1L, 2L, BigDecimal("179.40"), BigDecimal("179.40")))
      m("tier2") should be(RegionSaleRow("tier2", 1L, 2L, BigDecimal("387.70"), BigDecimal("287.70")))
      m("tier3") should be(RegionSaleRow("tier3", 2L, 3L, BigDecimal("655.80"), BigDecimal("357.80")))
    }

    "【独立 Oracle 重算对账】DWS 实测值严格等于从 accepted/1/normal.jsonl 用纯集合运算独立推导之结果（WP-N1B line 22）" in {
      // WP-N1B Deliverable 1 item 3: plain Scala collection operations on accepted landing rows
      withClue(s"独立 Oracle 重算比对（measured vs fileRecomputed）：") {
        cap.measuredDws.trade should be(cap.fileRecomputedDws.trade)
        cap.measuredDws.funnel should be(cap.fileRecomputedDws.funnel)
        cap.measuredDws.userBehavior should be(cap.fileRecomputedDws.userBehavior)
        cap.measuredDws.productBehavior should be(cap.fileRecomputedDws.productBehavior)
        cap.measuredDws.productSale should be(cap.fileRecomputedDws.productSale)
        cap.measuredDws.userPeriod should be(cap.fileRecomputedDws.userPeriod)
        cap.measuredDws.regionSale should be(cap.fileRecomputedDws.regionSale)
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 6. ADS 应用层断言
  // ══════════════════════════════════════════════════════════════════════

  "WP-N1B 6. ADS 应用层（fxsb_ads，dt=20260918）" should {

    "ads_operation_overview 匹配 ORACLE.md §4.4 手算预期" in {
      // ORACLE.md §4.4: pv=8, uv=4, dau=4, order_count=7, sale=1222.90, net=824.90, aov=174.70,
      // refund_rate≈0.2857, full_refund_rate≈0.1429, repeat_rate=0.5000, fav_cnt=1, cart_add_cnt=1
      cap.step("fna").status should be("SUCCESS")
      val o = cap.adsOverview
      o.pv should be(8L)
      o.uv should be(4L)
      o.dau should be(4L)
      o.orderCount should be(7L)
      o.saleAmount should be(BigDecimal("1222.90"))
      o.netSaleAmount should be(BigDecimal("824.90"))
      o.avgOrderValue should be(BigDecimal("174.70"))
      o.repeatRate should be(BigDecimal("0.5000"))
      o.favCnt should be(1L)
      o.cartAddCnt should be(1L)
      o.refundRate should be(BigDecimal("0.2857"))
      o.fullRefundRate should be(BigDecimal("0.1429"))
    }

    "ads_active_trend 匹配 ORACLE.md §4.4：dau=4, behavior_count=10" in {
      // ORACLE.md §4.4: ads_active_trend dau=4, behavior_count=10
      cap.adsActiveTrendDau should be(4L)
      cap.adsActiveTrendCount should be(10L)
    }

    "ads_behavior_funnel 匹配 ORACLE.md §4.4：4 个 stage 阶段 4/2/4/4 与转化率" in {
      // ORACLE.md §4.4: ads_behavior_funnel
      // 4 stage 行 view/intent/order/pay = 4/2/4/4; overall_buy_rate=1.0000, overall_cart_rate=0.2500
      val stages = cap.adsFunnelStages.map(s => s.stage -> s).toMap
      stages("view").userCount should be(4L)
      stages("view").conversionRate should be(None)
      stages("intent").userCount should be(2L)
      stages("intent").conversionRate should be(Some(BigDecimal("0.5000")))
      stages("order").userCount should be(4L)
      stages("order").conversionRate should be(Some(BigDecimal("2.0000")))
      stages("pay").userCount should be(4L)
      stages("pay").conversionRate should be(Some(BigDecimal("1.0000")))
      stages.values.foreach { s =>
        s.overallBuyRate should be(BigDecimal("1.0000"))
        s.overallCartRate should be(BigDecimal("0.2500"))
      }
    }

    "ads_hot_product 匹配 ORACLE.md §4.4：rank1=1002 > rank2=1001 > rank3=1003 且品名来自 fxsb_dim（非 UNKNOWN）" in {
      // ORACLE.md §4.4: ads_hot_product rank1=1002 > rank2=1001 > rank3=1003
      // 名称取自 fxsb dim_product（非 UNKNOWN）
      cap.adsHotProducts.size should be >= 3
      val top3 = cap.adsHotProducts.take(3)
      top3(0).productId should be(1002L)
      top3(0).productName should be("B-碳纤维键盘托")
      top3(0).rankNo should be(1)

      top3(1).productId should be(1001L)
      top3(1).productName should be("B-轻量保温杯")
      top3(1).rankNo should be(2)

      top3(2).productId should be(1003L)
      top3(2).productName should be("B-降噪耳机")
      top3(2).rankNo should be(3)

      top3.foreach(_.productName should not be "UNKNOWN")
    }

    "ads_product_conversion 匹配 ORACLE.md §4.4：1001(1.0) / 1002(1.0) / 1003(1.0)（1002 经 2026-10-16 勘误）" in {
      // ORACLE.md §4.4（2026-10-16 勘误后）: 1001: pv_users2/buy_users2/rate1.0; 1002: 2/2/1.0; 1003: 2/2/1.0
      val m = cap.adsProductConversion.map(c => c.productId -> c).toMap
      m(1001L).conversionRate should be(BigDecimal("1.0000"))
      m(1002L).conversionRate should be(BigDecimal("1.0000"))
      m(1003L).conversionRate should be(BigDecimal("1.0000"))
    }

    "ads_sale_trend 匹配 ORACLE.md §4.4：1 行 order7/buyer4/sale1222.90/aov174.70/net824.90" in {
      // ORACLE.md §4.4: ads_sale_trend
      cap.adsSaleTrend should be(Seq(SaleTrendRow(7L, 4L, BigDecimal("1222.90"), BigDecimal("174.70"), BigDecimal("824.90"))))
    }

    "ads_user_profile 匹配 ORACLE.md §4.4：U1..U4 RFM 段位与画像标签" in {
      // ORACLE.md §4.4: ads_user_profile
      // U1: r1,f1,m1 -> 一般发展, 新用户, fav_cat=11
      // U2: r3,f3,m2 -> 一般挽留, 活跃, fav_cat=22
      // U3: r2,f4,m3 -> 一般保持, 活跃, fav_cat=11
      // U4: r4,f2,m4 -> 重要发展, 活跃, fav_cat=22
      val m = cap.adsUserProfile.map(p => p.userId -> p).toMap
      val u1 = m(2100917055761973250L); u1.valueGroup should be("一般发展"); u1.userGroup should be("新用户"); u1.favCategory should be(11L)
      val u2 = m(2100917057120927745L); u2.valueGroup should be("一般挽留"); u2.userGroup should be("活跃"); u2.favCategory should be(22L)
      val u3 = m(2100917057850736642L); u3.valueGroup should be("一般保持"); u3.userGroup should be("活跃"); u3.favCategory should be(11L)
      val u4 = m(2100917056193986562L); u4.valueGroup should be("重要发展"); u4.userGroup should be("活跃"); u4.favCategory should be(22L)
    }

    "ads_data_quality 匹配 ORACLE.md §4.4：4 规则全 passed=1, error_count=0" in {
      // ORACLE.md §4.4: AMOUNT_RECONCILE, REQUIRED_FIELD_NULL_RATE, EVENT_ID_UNIQUE, ENUM_WHITELIST
      val m = cap.adsDataQuality.map(d => d.ruleCode -> d).toMap
      m("AMOUNT_RECONCILE").passed should be(1)
      m("AMOUNT_RECONCILE").errorCount should be(0L)
      m("REQUIRED_FIELD_NULL_RATE").passed should be(1)
      m("REQUIRED_FIELD_NULL_RATE").errorCount should be(0L)
      m("EVENT_ID_UNIQUE").passed should be(1)
      m("EVENT_ID_UNIQUE").errorCount should be(0L)
      m("ENUM_WHITELIST").passed should be(1)
      m("ENUM_WHITELIST").errorCount should be(0L)
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // 7. 质量门与发布（dqc + pub）
  // ══════════════════════════════════════════════════════════════════════

  "WP-N1B 7. 质量门与发布指针切换（dqc → pub，ORACLE.md §4.5 & WP-N1B line 23）" should {

    "dqc 暂存质量门状态为 SUCCESS 且所有阻断检查均通过（ORACLE.md §4.5）" in {
      cap.step("dqc").status should be("SUCCESS")
      withClue(s"dqc 检查项=${cap.dqcChecks}：") {
        cap.dqcChecks.filter(_.severity == "BLOCKING").forall(_.passed) should be(true)
      }
    }

    "pub 正式分区发布状态为 SUCCESS（WP-N1B line 23）" in {
      cap.step("pub").status should be("SUCCESS")
    }

    "逐表元数据指针切换：正式分区 Location 必须指向本次快照 snapshot_id=fxsb-snap-01 的暂存路径" in {
      val wrong = cap.formalAfterPub.filterNot { case (_, p) =>
        p.location.exists(l => l.contains(s"snapshot_id=$SnapshotId") && l.contains(s"dt=$BusinessDate"))
      }
      withClue(s"pub 后正式分区 Location=${cap.formalAfterPub.map { case (t, p) => s"$t -> ${p.location}" }}：") {
        cap.formalAfterPub.keySet should be(AdsSql.TABLES.toSet)
        wrong should be(empty)
      }
    }

    "正式分区的真实数据行数 == 暂存行数，且 dt=20260918 每表只有一个分区（无第二份数据）" in {
      val mismatched = AdsSql.TABLES.filter { t =>
        val p = cap.formalAfterPub.get(t)
        p.flatMap(_.parquetRowCount).getOrElse(-1L) != cap.stagingRows.getOrElse(t, -2L)
      }
      withClue(s"正式分区 parquet 行数=${cap.formalAfterPub.map { case (t, p) => s"$t -> ${p.parquetRowCount}" }}；暂存=${cap.stagingRows}：") {
        mismatched should be(empty)
      }
      withClue(s"每表 dt=$BusinessDate 分区列表=${cap.formalAfterPub.map { case (t, p) => s"$t -> ${p.partitions}" }}：") {
        cap.formalAfterPub.filterNot { case (_, p) => p.partitions.size == 1 } should be(empty)
      }
    }
  }
}

object SourceBChainExecSpec {

  val SuiteName = "source-b-chain"
  val NsPrefix = "fxsb"
  val Ns: WarehouseNamespace = WarehouseNamespace.of(NsPrefix)
  val BusinessDate = "20260918"
  val SnapshotId = "fxsb-snap-01"

  final case class Step(code: String, result: JobResult, thrown: Option[String]) {
    def status: String = if (thrown.isDefined) "THREW" else if (result == null) "NULL" else result.status
  }

  final case class DimUserRow(userId: String, ageGroup: String, cityLevel: String, memberLevel: String)
  final case class DimProductRow(productId: String, productName: String, categoryId: Long, price: BigDecimal)

  final case class DwsTrade(orderCount: Long, buyerCount: Long, saleAmount: BigDecimal,
                            refundAmount: BigDecimal, netSaleAmount: BigDecimal, avgOrderValue: Option[BigDecimal])
  final case class DwsFunnel(viewUsers: Long, intentUsers: Long, cartUsers: Long, orderUsers: Long, payUsers: Long,
                             intentRate: Option[BigDecimal], orderRate: Option[BigDecimal], payRate: Option[BigDecimal],
                             overallBuyRate: Option[BigDecimal], cartRate: Option[BigDecimal])
  final case class UserBehaviorRow(userId: Long, pv: Long, fav: Long, cart: Long, search: Long, activeHours: Int, buy: Long)
  final case class ProductBehaviorRow(productId: Long, categoryId: Long, pv: Long, uv: Long, fav: Long, cart: Long, buy: Long)
  final case class ProductSaleRow(productId: Long, categoryId: Long, saleCount: Long, saleAmount: BigDecimal, buyerCount: Long)
  final case class UserPeriodRow(userId: Long, orderCount: Long, saleAmount: BigDecimal, validOrderCount: Long)
  final case class RegionSaleRow(region: String, buyerCount: Long, orderCount: Long, saleAmount: BigDecimal, netSaleAmount: BigDecimal)

  final case class DwsBundle(trade: Option[DwsTrade], funnel: Option[DwsFunnel],
                             userBehavior: Seq[UserBehaviorRow], productBehavior: Seq[ProductBehaviorRow],
                             productSale: Seq[ProductSaleRow], userPeriod: Seq[UserPeriodRow],
                             regionSale: Seq[RegionSaleRow])

  final case class AdsOverviewRow(pv: Long, uv: Long, dau: Long, orderCount: Long,
                                  saleAmount: BigDecimal, netSaleAmount: BigDecimal, avgOrderValue: BigDecimal,
                                  refundRate: BigDecimal, fullRefundRate: BigDecimal, repeatRate: BigDecimal,
                                  favCnt: Long, cartAddCnt: Long)
  final case class FunnelStageRow(stage: String, userCount: Long, conversionRate: Option[BigDecimal],
                                  overallBuyRate: BigDecimal, overallCartRate: BigDecimal)
  final case class HotProductRow(productId: Long, productName: String, rankNo: Int)
  final case class ProductConversionRow(productId: Long, pvUsers: Long, buyUsers: Long, conversionRate: BigDecimal)
  final case class SaleTrendRow(orderCount: Long, buyerCount: Long, saleAmount: BigDecimal, avgOrderValue: BigDecimal, netSaleAmount: BigDecimal)
  final case class UserProfileRow(userId: Long, valueGroup: String, userGroup: String, favCategory: Long)
  final case class DataQualityRow(ruleCode: String, checkCount: Long, errorCount: Long, passed: Int)

  final case class FormalPartition(table: String, location: Option[String], parquetRowCount: Option[Long], partitions: Seq[String])

  final case class Capture(
    manifestPath: String,
    manifestNode: JsonNode,
    manifestSourceCode: String,
    manifestStatus: String,
    manifestAcceptedRecords: Long,
    manifestQuarantinedRecords: Long,
    manifestBatchId: Long,
    odlArgs: JobArgs,
    steps: Seq[Step],
    totalOdsRows: Long,
    odsTableCounts: Map[String, Long],
    odsDtDistribution: Map[String, Long],
    odsNonSourceBCount: Long,
    leakedFaultEventIds: Seq[String],
    dimUsers: Seq[DimUserRow],
    dimProducts: Seq[DimProductRow],
    dwdBehaviorCount: Long,
    dwdOrderCount: Long,
    dwdOrderPaidCount: Long,
    dwdBehaviorNullUserKeys: Long,
    dwdBehaviorNullProductKeys: Long,
    dwdOrderNullUserKeys: Long,
    dwdOrderNullProductKeys: Long,
    measuredDws: DwsBundle,
    fileRecomputedDws: DwsBundle,
    adsOverview: AdsOverviewRow,
    adsActiveTrendDau: Long,
    adsActiveTrendCount: Long,
    adsFunnelStages: Seq[FunnelStageRow],
    adsHotProducts: Seq[HotProductRow],
    adsProductConversion: Seq[ProductConversionRow],
    adsSaleTrend: Seq[SaleTrendRow],
    adsUserProfile: Seq[UserProfileRow],
    adsDataQuality: Seq[DataQualityRow],
    dqcChecks: Seq[QualityCheck],
    stagingRows: Map[String, Long],
    formalAfterPub: Map[String, FormalPartition]
  ) {
    def step(code: String): Step = steps.find(_.code == code).getOrElse(Step(code, null, Some("未执行")))
  }

  object Capture {
    def failed(msg: String): Capture = Capture(
      manifestPath = "", manifestNode = null, manifestSourceCode = "", manifestStatus = "",
      manifestAcceptedRecords = -1L, manifestQuarantinedRecords = -1L, manifestBatchId = -1L,
      odlArgs = null, steps = Seq(Step("crash", null, Some(msg))),
      totalOdsRows = -1L, odsTableCounts = Map.empty, odsDtDistribution = Map.empty, odsNonSourceBCount = -1L,
      leakedFaultEventIds = Seq(msg), dimUsers = Seq.empty, dimProducts = Seq.empty,
      dwdBehaviorCount = -1L, dwdOrderCount = -1L, dwdOrderPaidCount = -1L,
      dwdBehaviorNullUserKeys = -1L, dwdBehaviorNullProductKeys = -1L,
      dwdOrderNullUserKeys = -1L, dwdOrderNullProductKeys = -1L,
      measuredDws = DwsBundle(None, None, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty),
      fileRecomputedDws = DwsBundle(None, None, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty),
      adsOverview = null, adsActiveTrendDau = -1L, adsActiveTrendCount = -1L,
      adsFunnelStages = Seq.empty, adsHotProducts = Seq.empty, adsProductConversion = Seq.empty,
      adsSaleTrend = Seq.empty, adsUserProfile = Seq.empty, adsDataQuality = Seq.empty,
      dqcChecks = Seq.empty, stagingRows = Map.empty, formalAfterPub = Map.empty
    )
  }

  private def collectAll(spark: SparkSession): Capture = {
    val om = new ObjectMapper()
    val repoRoot = P2TestSupport.repoRoot
    val manifestFile = repoRoot.resolve("fixtures/source-b-landing/manifests/1.json")
    P2TestSupport.requireNonEmpty(manifestFile)
    val manifestBytes = Files.readAllBytes(manifestFile)
    val manifestJson = om.readTree(manifestBytes)

    val manifestSourceCode = manifestJson.get("sourceCode").asText()
    val manifestStatus = manifestJson.get("status").asText()
    val manifestAcceptedRecords = manifestJson.get("acceptedRecords").asLong()
    val manifestQuarantinedRecords = manifestJson.get("quarantinedRecords").asLong()
    val manifestBatchId = manifestJson.get("batchId").asLong()

    val acceptedLandingDir = repoRoot.resolve("fixtures/source-b-landing/accepted/1")
    val acceptedLandingUri = P2TestSupport.fileUri(acceptedLandingDir)

    // 1. sci: Schema Initialization
    val ddls = LocalSchemaInitJob.statements(Ns)
    ddls.foreach { case (_, ddl) => spark.sql(ddl) }

    def makeArgs(code: String, snapshot: Option[String] = None, extra: (String, String)*): JobArgs = {
      val base = Array(
        "--runtimeProfileId=1",
        s"--jobCode=$code",
        s"--businessDate=$BusinessDate",
        "--attemptNo=1",
        s"--hiveDatabasePrefix=${Ns.prefix}",
        s"--sourceSystem=$manifestSourceCode",
        s"--batchId=$manifestBatchId"
      ) ++ snapshot.map(s => s"--outputSnapshotId=$s").toSeq ++ extra.map { case (k, v) => s"--$k=$v" }
      JobArgs.parse(base).right.get
    }

    val steps = ListBuffer.empty[Step]
    def runStep(code: String, snapshot: Option[String] = None): JobResult = {
      val args = makeArgs(code, snapshot)
      val r = try {
        val job: WarehouseJob = JobRegistry.lookup(code).getOrElse(
          throw new IllegalStateException(s"JobRegistry 未注册作业：$code"))
        val res = job.run(spark, args)
        steps += Step(code, res, None)
        res
      } catch {
        case t: Throwable =>
          steps += Step(code, null, Some(s"${t.getClass.getName}: ${t.getMessage}"))
          throw t
      }
      r
    }

    // 2. odl: Landing → ODS (taken from manifest values per WP-N1B Deliverable 1 item 1)
    val odlArgs = makeArgs("odl", None, "landingDir" -> acceptedLandingUri)
    val odlJob = JobRegistry.lookup("odl").get
    val odlRes = odlJob.run(spark, odlArgs)
    steps += Step("odl", odlRes, None)

    // ODS Metrics
    val odsTables = Seq("ods_user_event", "ods_product_event", "ods_behavior_event", "ods_trade_event")
    val odsTableCounts = odsTables.map(t => t -> spark.sql(s"SELECT COUNT(*) FROM ${Ns.ods}.$t").collect()(0).getLong(0)).toMap
    val totalOdsRows = odsTableCounts.values.sum

    val odsDtDistribution = odsTables.flatMap { t =>
      spark.sql(s"SELECT dt, COUNT(*) c FROM ${Ns.ods}.$t GROUP BY dt").collect().map(r => r.getString(0) -> r.getLong(1))
    }.groupBy(_._1).map { case (dt, seq) => dt -> seq.map(_._2).sum }

    val odsNonSourceBCount = odsTables.map { t =>
      spark.sql(s"SELECT COUNT(*) FROM ${Ns.ods}.$t WHERE source_system <> '$manifestSourceCode'").collect()(0).getLong(0)
    }.sum

    val faultEvtNos = Seq("B-EVT-F001", "B-EVT-F002", "B-EVT-F003", "B-EVT-F005")
    val leakedFaultEventIds = odsTables.flatMap { t =>
      spark.sql(s"SELECT event_id FROM ${Ns.ods}.$t").collect().map(_.getString(0)).filter(id => faultEvtNos.contains(id) || id.contains("F00"))
    }

    // 3. dim: ODS → DIM
    runStep("dim")
    val dimUsers = spark.sql(s"SELECT CAST(user_id AS STRING), age_group, city_level, member_level FROM ${Ns.dim}.dim_user WHERE dt = '$BusinessDate'")
      .collect().map(r => DimUserRow(r.getString(0), r.getString(1), r.getString(2), r.getString(3))).toSeq

    val dimProducts = spark.sql(s"SELECT CAST(product_id AS STRING), product_name, category_id, price FROM ${Ns.dim}.dim_product WHERE dt = '$BusinessDate'")
      .collect().map(r => DimProductRow(r.getString(0), r.getString(1), r.getLong(2), r.getDecimal(3))).toSeq

    // 4. bdw: ODS → DWD Behavior
    runStep("bdw")
    val dwdBehaviorCount = spark.sql(s"SELECT COUNT(*) FROM ${Ns.dwd}.dwd_user_behavior_detail WHERE dt = '$BusinessDate'").collect()(0).getLong(0)
    val dwdBehaviorNullUserKeys = spark.sql(s"SELECT COUNT(*) FROM ${Ns.dwd}.dwd_user_behavior_detail WHERE dt = '$BusinessDate' AND user_key IS NULL").collect()(0).getLong(0)
    val dwdBehaviorNullProductKeys = spark.sql(s"SELECT COUNT(*) FROM ${Ns.dwd}.dwd_user_behavior_detail WHERE dt = '$BusinessDate' AND product_key IS NULL").collect()(0).getLong(0)

    // 5. tdw: ODS+DIM → DWD Trade
    runStep("tdw")
    val dwdOrderCount = spark.sql(s"SELECT COUNT(*) FROM ${Ns.dwd}.dwd_order_detail WHERE dt = '$BusinessDate'").collect()(0).getLong(0)
    val dwdOrderPaidCount = spark.sql(s"SELECT COUNT(*) FROM ${Ns.dwd}.dwd_order_detail WHERE dt = '$BusinessDate' AND final_paid_flag = 1").collect()(0).getLong(0)
    val dwdOrderNullUserKeys = spark.sql(s"SELECT COUNT(*) FROM ${Ns.dwd}.dwd_order_detail WHERE dt = '$BusinessDate' AND user_key IS NULL").collect()(0).getLong(0)
    val dwdOrderNullProductKeys = spark.sql(s"SELECT COUNT(*) FROM ${Ns.dwd}.dwd_order_detail WHERE dt = '$BusinessDate' AND product_key IS NULL").collect()(0).getLong(0)

    // 6. usw: DWD → DWS
    runStep("usw")
    val measuredDws = readDwsFromSpark(spark, Ns, BusinessDate)
    val fileRecomputedDws = computeDwsFromAcceptedFile(repoRoot.resolve("fixtures/source-b-landing/accepted/1/normal.jsonl"))

    // 7. fna: DWS → ADS Staging
    runStep("fna", Some(SnapshotId))
    val stagingRows = AdsSql.TABLES.map { t =>
      t -> spark.sql(s"SELECT COUNT(*) FROM ${AdsSql.staging(Ns, t)} WHERE snapshot_id = '$SnapshotId' AND dt = '$BusinessDate'").collect()(0).getLong(0)
    }.toMap

    val adsOverview = readAdsOverview(spark, Ns, SnapshotId, BusinessDate)
    val adsActiveTrend = spark.sql(s"SELECT dau, behavior_count FROM ${AdsSql.staging(Ns, "ads_active_trend")} WHERE snapshot_id = '$SnapshotId' AND dt = '$BusinessDate'").collect()(0)
    val adsFunnelStages = spark.sql(s"SELECT stage, user_count, conversion_rate, overall_buy_rate, overall_cart_rate FROM ${AdsSql.staging(Ns, "ads_behavior_funnel")} WHERE snapshot_id = '$SnapshotId' AND dt = '$BusinessDate'")
      .collect().map(r => FunnelStageRow(r.getString(0), r.getLong(1), Option(r.getDecimal(2)).map(BigDecimal(_)), r.getDecimal(3), r.getDecimal(4))).toSeq
    val adsHotProducts = spark.sql(s"SELECT product_id, product_name, rank_no FROM ${AdsSql.staging(Ns, "ads_hot_product")} WHERE snapshot_id = '$SnapshotId' AND dt = '$BusinessDate' ORDER BY rank_no")
      .collect().map(r => HotProductRow(r.getLong(0), r.getString(1), r.getInt(2))).toSeq
    val adsProductConversion = spark.sql(s"SELECT product_id, pv_users, buy_users, conversion_rate FROM ${AdsSql.staging(Ns, "ads_product_conversion")} WHERE snapshot_id = '$SnapshotId' AND dt = '$BusinessDate' ORDER BY product_id")
      .collect().map(r => ProductConversionRow(r.getLong(0), r.getLong(1), r.getLong(2), r.getDecimal(3))).toSeq
    val adsSaleTrend = spark.sql(s"SELECT order_count, buyer_count, sale_amount, avg_order_value, net_sale_amount FROM ${AdsSql.staging(Ns, "ads_sale_trend")} WHERE snapshot_id = '$SnapshotId' AND dt = '$BusinessDate'")
      .collect().map(r => SaleTrendRow(r.getLong(0), r.getLong(1), r.getDecimal(2), r.getDecimal(3), r.getDecimal(4))).toSeq
    val adsUserProfile = spark.sql(s"SELECT user_id, value_group, lifecycle_state, favorite_category FROM ${AdsSql.staging(Ns, "ads_user_profile")} WHERE snapshot_id = '$SnapshotId' AND dt = '$BusinessDate' ORDER BY user_id")
      .collect().map(r => UserProfileRow(r.getLong(0), r.getString(1), r.getString(2), r.getLong(3))).toSeq
    val adsDataQuality = spark.sql(s"SELECT rule_code, check_count, error_count, passed FROM ${AdsSql.staging(Ns, "ads_data_quality")} WHERE snapshot_id = '$SnapshotId' AND dt = '$BusinessDate'")
      .collect().map(r => DataQualityRow(r.getString(0), r.getLong(1), r.getLong(2), r.getInt(3))).toSeq

    // 8. dqc: ADS Quality Gate
    val dqcRes = runStep("dqc", Some(SnapshotId))
    val dqcChecks = dqcRes.checks

    // 9. pub: ADS Publish
    runStep("pub", Some(SnapshotId))
    val formalAfterPub = readFormalPartitions(spark, Ns, BusinessDate)

    Capture(
      manifestPath = manifestFile.toString,
      manifestNode = manifestJson,
      manifestSourceCode = manifestSourceCode,
      manifestStatus = manifestStatus,
      manifestAcceptedRecords = manifestAcceptedRecords,
      manifestQuarantinedRecords = manifestQuarantinedRecords,
      manifestBatchId = manifestBatchId,
      odlArgs = odlArgs,
      steps = steps.toSeq,
      totalOdsRows = totalOdsRows,
      odsTableCounts = odsTableCounts,
      odsDtDistribution = odsDtDistribution,
      odsNonSourceBCount = odsNonSourceBCount,
      leakedFaultEventIds = leakedFaultEventIds,
      dimUsers = dimUsers,
      dimProducts = dimProducts,
      dwdBehaviorCount = dwdBehaviorCount,
      dwdOrderCount = dwdOrderCount,
      dwdOrderPaidCount = dwdOrderPaidCount,
      dwdBehaviorNullUserKeys = dwdBehaviorNullUserKeys,
      dwdBehaviorNullProductKeys = dwdBehaviorNullProductKeys,
      dwdOrderNullUserKeys = dwdOrderNullUserKeys,
      dwdOrderNullProductKeys = dwdOrderNullProductKeys,
      measuredDws = measuredDws,
      fileRecomputedDws = fileRecomputedDws,
      adsOverview = adsOverview,
      adsActiveTrendDau = adsActiveTrend.getLong(0),
      adsActiveTrendCount = adsActiveTrend.getLong(1),
      adsFunnelStages = adsFunnelStages,
      adsHotProducts = adsHotProducts,
      adsProductConversion = adsProductConversion,
      adsSaleTrend = adsSaleTrend,
      adsUserProfile = adsUserProfile,
      adsDataQuality = adsDataQuality,
      dqcChecks = dqcChecks,
      stagingRows = stagingRows,
      formalAfterPub = formalAfterPub
    )
  }

  private def readAdsOverview(spark: SparkSession, ns: WarehouseNamespace, snapshotId: String, dt: String): AdsOverviewRow = {
    val r = spark.sql(s"SELECT pv, uv, dau, order_count, sale_amount, net_sale_amount, avg_order_value, refund_rate, full_refund_rate, repeat_rate, fav_cnt, cart_add_cnt FROM ${AdsSql.staging(ns, "ads_operation_overview")} WHERE snapshot_id = '$snapshotId' AND dt = '$dt'").collect()(0)
    AdsOverviewRow(r.getLong(0), r.getLong(1), r.getLong(2), r.getLong(3),
      r.getDecimal(4), r.getDecimal(5), r.getDecimal(6), r.getDecimal(7), r.getDecimal(8), r.getDecimal(9),
      r.getLong(10), r.getLong(11))
  }

  private def readFormalPartitions(spark: SparkSession, ns: WarehouseNamespace, dt: String): Map[String, FormalPartition] = {
    val evidence = PartitionEvidence.collect(spark, AdsSql.TABLES.map(AdsSql.formal(ns, _)), None, Some(dt))
    evidence.groupBy(p => p.table.split('.').last).map { case (shortName, parts) =>
      shortName -> FormalPartition(
        table = shortName,
        location = parts.headOption.flatMap(_.path),
        parquetRowCount = Some(parts.map(_.rowCount).sum),
        partitions = parts.map(p => s"dt=${p.dt}")
      )
    }.toMap
  }

  private def readDwsFromSpark(spark: SparkSession, ns: WarehouseNamespace, dt: String): DwsBundle = {
    val tradeRow = spark.sql(
      s"""SELECT order_count, buyer_count, sale_amount, refund_amount, net_sale_amount, avg_order_value
         |FROM ${ns.dws}.dws_trade_day WHERE dt = '$dt'""".stripMargin).collect().headOption.map { r =>
      DwsTrade(r.getLong(0), r.getLong(1), r.getDecimal(2), r.getDecimal(3), r.getDecimal(4), Option(r.getDecimal(5)).map(BigDecimal(_)))
    }

    val funnelRow = spark.sql(
      s"""SELECT view_users, intent_users, cart_users, order_users, pay_users,
         |       intent_rate, order_rate, pay_rate, overall_buy_rate, cart_rate
         |FROM ${ns.dws}.dws_behavior_funnel_day WHERE dt = '$dt'""".stripMargin).collect().headOption.map { r =>
      DwsFunnel(r.getLong(0), r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4),
        Option(r.getDecimal(5)).map(BigDecimal(_)), Option(r.getDecimal(6)).map(BigDecimal(_)),
        Option(r.getDecimal(7)).map(BigDecimal(_)), Option(r.getDecimal(8)).map(BigDecimal(_)),
        Option(r.getDecimal(9)).map(BigDecimal(_)))
    }

    val userBehavior = spark.sql(
      s"""SELECT user_id, pv, fav, cart, search, active_hours, buy
         |FROM ${ns.dws}.dws_user_behavior_day WHERE dt = '$dt' ORDER BY user_id""".stripMargin)
      .collect().map(r => UserBehaviorRow(r.getLong(0), r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getInt(5), r.getLong(6))).toSeq

    val productBehavior = spark.sql(
      s"""SELECT product_id, category_id, pv, uv, fav, cart, buy
         |FROM ${ns.dws}.dws_product_behavior_day WHERE dt = '$dt' ORDER BY product_id""".stripMargin)
      .collect().map(r => ProductBehaviorRow(r.getLong(0), r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getLong(6))).toSeq

    val productSale = spark.sql(
      s"""SELECT product_id, category_id, sale_count, sale_amount, buyer_count
         |FROM ${ns.dws}.dws_product_sale_day WHERE dt = '$dt' ORDER BY product_id""".stripMargin)
      .collect().map(r => ProductSaleRow(r.getLong(0), r.getLong(1), r.getLong(2), r.getDecimal(3), r.getLong(4))).toSeq

    val userPeriod = spark.sql(
      s"""SELECT user_id, order_count, sale_amount, valid_order_count
         |FROM ${ns.dws}.dws_user_trade_period WHERE dt = '$dt' ORDER BY user_id""".stripMargin)
      .collect().map(r => UserPeriodRow(r.getLong(0), r.getLong(1), r.getDecimal(2), r.getLong(3))).toSeq

    val regionSale = spark.sql(
      s"""SELECT region, buyer_count, order_count, sale_amount, net_sale_amount
         |FROM ${ns.dws}.dws_region_sale_day WHERE dt = '$dt' ORDER BY region""".stripMargin)
      .collect().map(r => RegionSaleRow(r.getString(0), r.getLong(1), r.getLong(2), r.getDecimal(3), r.getDecimal(4))).toSeq

    DwsBundle(tradeRow, funnelRow, userBehavior, productBehavior, productSale, userPeriod, regionSale)
  }

  /**
   * 独立 Oracle 重算：从 accepted/1/normal.jsonl 原文行，用纯 Scala 集合运算（不使用 Spark SQL / DwsSql）推导 DWS 期望。
   * 符合 DwsAdsChainExecSpec oracle pattern。
   */
  private def computeDwsFromAcceptedFile(file: Path): DwsBundle = {
    val om = new ObjectMapper()
    val lines = Files.readAllLines(file, StandardCharsets.UTF_8).asScala.filter(_.trim.nonEmpty)
    val parsedEvents = lines.map(line => om.readTree(line)).toSeq

    val userCityMap = parsedEvents
      .filter(n => n.get("event_type").asText() == "user_registered")
      .map(n => n.get("payload").get("user_id").asText().toLong -> n.get("payload").get("city_level").asText())
      .toMap

    val productCatMap = parsedEvents
      .filter(n => n.get("event_type").asText() == "product_created")
      .map(n => n.get("payload").get("product_id").asText().toLong -> n.get("payload").get("category_id").asText().toLong)
      .toMap

    // 1. Behavior Events on dt=2026-09-18
    val behaviorEvents = parsedEvents
      .filter(n => n.get("event_type").asText() == "behavior")
      .filter(n => n.get("event_time").asText().startsWith("2026-09-18"))
      .map { n =>
        val p = n.get("payload")
        val userId = p.get("user_id").asText().toLong
        val productId = p.get("product_id").asText().toLong
        val bType = p.get("behavior_type").asText()
        val timeStr = n.get("event_time").asText()
        val hour = timeStr.substring(11, 13).toInt
        val catId = productCatMap.getOrElse(productId, 11L)
        (userId, bType, hour, productId, catId)
      }

    // 2. Order Events compiled on dt=2026-09-18
    // Parse order_created, order_paid, order_cancelled, refund_completed
    final case class Item(productId: Long, quantity: Long, amount: BigDecimal)
    final case class RawOrder(orderId: Long, userId: Long, totalAmount: BigDecimal, items: Seq[Item],
                              var isPaid: Boolean = false, var isCancelled: Boolean = false,
                              var refundAmount: BigDecimal = BigDecimal(0), var isFullyRefunded: Boolean = false)

    val ordersMap = scala.collection.mutable.Map.empty[Long, RawOrder]

    parsedEvents.foreach { n =>
      val et = n.get("event_type").asText()
      val p = n.get("payload")
      et match {
        case "order_created" =>
          val oid = p.get("order_id").asText().toLong
          val uid = p.get("user_id").asText().toLong
          val totalAmt = BigDecimal(p.get("total_amount").asText())
          val itemsNode = p.get("items")
          val items = if (itemsNode != null && itemsNode.isArray) {
            itemsNode.elements().asScala.map { it =>
              Item(it.get("product_id").asText().toLong, it.get("quantity").asLong(), BigDecimal(it.get("amount").asText()))
            }.toSeq
          } else Seq.empty
          ordersMap(oid) = RawOrder(oid, uid, totalAmt, items)
        case "order_paid" =>
          val oid = p.get("order_id").asText().toLong
          ordersMap.get(oid).foreach(_.isPaid = true)
        case "order_cancelled" =>
          val oid = p.get("order_id").asText().toLong
          ordersMap.get(oid).foreach(_.isCancelled = true)
        case "refund_completed" =>
          val oid = p.get("order_id").asText().toLong
          val amt = BigDecimal(p.get("amount").asText())
          ordersMap.get(oid).foreach { o =>
            o.refundAmount += amt
            if (o.refundAmount >= o.totalAmount) o.isFullyRefunded = true
          }
        case _ =>
      }
    }

    val paidOrders = ordersMap.values.filter(_.isPaid).toSeq
    val allOrders = ordersMap.values.toSeq

    // Trade summary
    val tradeDay = DwsTrade(
      orderCount = paidOrders.map(_.orderId).distinct.size.toLong,
      buyerCount = paidOrders.map(_.userId).distinct.size.toLong,
      saleAmount = paidOrders.map(_.totalAmount).sum,
      refundAmount = paidOrders.map(_.refundAmount).sum,
      netSaleAmount = paidOrders.map(_.totalAmount).sum - paidOrders.map(_.refundAmount).sum,
      avgOrderValue = Some((paidOrders.map(_.totalAmount).sum / BigDecimal(paidOrders.map(_.orderId).distinct.size)).setScale(2, BigDecimal.RoundingMode.HALF_UP))
    )

    // User behavior summary
    val buyByUser = paidOrders.groupBy(_.userId).map { case (u, os) => u -> os.flatMap(_.items).map(_.quantity).sum }
    val userBehavior = behaviorEvents.groupBy(_._1).toSeq.sortBy(_._1).map { case (uid, evts) =>
      val pv = evts.count(_._2 == "view").toLong
      val fav = evts.count(_._2 == "favorite").toLong
      val cart = evts.count(_._2 == "cart_add").toLong
      val search = evts.count(_._2 == "search").toLong
      val hours = evts.map(_._3).distinct.size
      val buy = buyByUser.getOrElse(uid, 0L)
      UserBehaviorRow(uid, pv, fav, cart, search, hours, buy)
    }

    // Product behavior summary
    val buyByProd = paidOrders.flatMap(_.items).groupBy(_.productId).map { case (pid, its) => pid -> its.map(_.quantity).sum }
    val productBehavior = behaviorEvents.groupBy(e => (e._4, e._5)).toSeq.sortBy(_._1._1).map { case ((pid, cid), evts) =>
      val pv = evts.count(_._2 == "view").toLong
      val uv = evts.filter(_._2 == "view").map(_._1).distinct.size.toLong
      val fav = evts.count(_._2 == "favorite").toLong
      val cart = evts.count(_._2 == "cart_add").toLong
      val buy = buyByProd.getOrElse(pid, 0L)
      ProductBehaviorRow(pid, cid, pv, uv, fav, cart, buy)
    }

    // Product sale summary
    val productSale = paidOrders.flatMap(o => o.items.map(it => (it.productId, productCatMap.getOrElse(it.productId, 11L), it.quantity, it.amount, o.userId)))
      .groupBy(r => (r._1, r._2)).toSeq.sortBy(_._1._1).map { case ((pid, cid), rows) =>
        ProductSaleRow(pid, cid, rows.map(_._3).sum, rows.map(_._4).sum, rows.map(_._5).distinct.size.toLong)
      }

    // User trade period
    val userPeriod = paidOrders.groupBy(_.userId).toSeq.sortBy(_._1).map { case (uid, os) =>
      val validCount = os.count(!_.isFullyRefunded).toLong
      UserPeriodRow(uid, os.size.toLong, os.map(_.totalAmount).sum, validCount)
    }

    // Region sale
    val regionSale = paidOrders.groupBy(o => userCityMap.getOrElse(o.userId, "unknown")).toSeq.sortBy(_._1).map { case (region, os) =>
      val buyers = os.map(_.userId).distinct.size.toLong
      val orderCnt = os.size.toLong
      val saleAmt = os.map(_.totalAmount).sum
      val netAmt = saleAmt - os.map(_.refundAmount).sum
      RegionSaleRow(region, buyers, orderCnt, saleAmt, netAmt)
    }

    // Funnel
    val viewUsers = behaviorEvents.filter(_._2 == "view").map(_._1).distinct.size.toLong
    val intentUsers = behaviorEvents.filter(e => e._2 == "favorite" || e._2 == "cart_add").map(_._1).distinct.size.toLong
    val cartUsers = behaviorEvents.filter(_._2 == "cart_add").map(_._1).distinct.size.toLong
    val orderUsers = allOrders.map(_.userId).distinct.size.toLong
    val payUsers = paidOrders.map(_.userId).distinct.size.toLong

    def rate(num: Long, den: Long): Option[BigDecimal] =
      if (den == 0L) None else Some((BigDecimal(num) / BigDecimal(den)).setScale(4, BigDecimal.RoundingMode.HALF_UP))

    val funnel = DwsFunnel(viewUsers, intentUsers, cartUsers, orderUsers, payUsers,
      rate(intentUsers, viewUsers), rate(orderUsers, intentUsers), rate(payUsers, orderUsers),
      rate(payUsers, viewUsers), rate(cartUsers, viewUsers))

    DwsBundle(Some(tradeDay), Some(funnel), userBehavior, productBehavior, productSale, userPeriod, regionSale)
  }
}
