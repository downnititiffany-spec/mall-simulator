package com.graduation.analytics

import com.graduation.analytics.job.LocalSchemaInitJob
import com.graduation.analytics.metric.MetricAdsSpec
import com.graduation.analytics.sql.{AdsSql, DwsSql}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S3-04 **加购率落地**（阶段 3「指标计算」：口径固定 + 逐层对账 + 制品可查）。
 *
 * 设计标尺（逐字）：
 *  - 设计 §11.2 L432「`buy_rate`/`cart_rate` | 支付/加购去重用户数 ÷ 浏览去重用户数 |
 *    标注独立集合或严格 cohort」——加购率与购买率**同组同型**（整体率，分母都是浏览去重用户）；
 *  - 字典 `docs/contracts/metric-dictionary.md:23`「`cart_rate` 加购率 = 加购用户数 ÷ 浏览用户数（用户去重口径）|
 *    日 | event_time | 源表 `dws_behavior_funnel_day`」；`:22`「`cart_add_cnt` = cart_add 事件」；
 *  - 设计 §11.3 L441 漏斗**只有 view/intent/order/pay 四个阶段**（「禁止 min 截断」）——
 *    本节**不新增第五个阶段行**，只补一个整体率列（与既有 `overall_buy_rate` 同型）；
 *  - 指导书 §7 阶段 3 ①「固定粒度/分子分母/时间窗口/空值规则/版本」②「逐层对账」。
 *
 * 本层实现的口径声明（设计只要求声明、不指定取值）：
 *  1. **粒度**：日粒度、全站维度（`category_id = -1, channel = 'all'`，与既有漏斗行一致；分类/渠道下钻是 G-04 未决项，
 *     本节不伪造）；
 *  2. **分子**：当日 `behavior_type = 'cart_add'` 的**去重用户数**（`cart_users`）。**不等于** `intent_users`
 *     （后者含 `favorite`）——这是与字典 L22/L23 一致的判别点；
 *  3. **分母**：当日 `behavior_type = 'view'` 的去重用户数（`view_users`），日窗口 = 统计分区 `dt`；
 *  4. **空值规则**：分母为 0 ⇒ `cart_rate` 为 **NULL**（绝不写 0、绝不除零、绝不打成第五阶段）；
 *  5. **版本**：`rule_version` 语义不在本节（字典 `cart_rate` 定义版本 v1，无窗口/变体声明）。
 *
 * 为什么必须在 ADS 落列而不是让页面临时算：指导书 §8 L199「稳定指标公式…**不靠前端/AI 临时算出指标**」；
 * 现网事实是 `cart_rate` 在字典里已登记、但**没有任何 ADS 载体**（`docs/contracts/metric-lineage.md` §2
 * 把 `cart_rate` 列为「未落地」，原因原文「需在 DWS 漏斗增加 `cart_users` 并扩 `ads_behavior_funnel`」）。
 *
 * 判定方式（不读 SQL 文本下结论）：
 *  ① 真跑 `DWD → dws_behavior_funnel_day → ads_behavior_funnel`，断言加购去重人数/加购率落到 DWS 与 ADS；
 *  ② **口径判别力**：夹具含「仅收藏（进 intent 不进 cart）」「只加购未浏览（进分子不进分母）」
 *     「cart_remove（不是加购）」三类探针 ⇒ 复用 `intent_users` 会算成 `0.8000`、只算浏览∩加购会算成 `0.4000`，
 *     正确实现必须给出 `0.6000`；
 *  ③ **跨层对账**：用 DWD 行为表按字典列名独立重算一遍，与走 DWS 列的实现逐值相等；
 *  ④ ADS 四行 `overall_cart_rate` 必须**同值**且等于 DWS 行（透传，不在 ADS 重算）；
 *  ⑤ 分母 0 日（只有 cart_add 没有 view）⇒ `cart_rate` 为 NULL，且 `cart_users` 仍如实为 1（不是丢行）；
 *  ⑥ 阶段集合仍是 view/intent/order/pay 四个（不得因加购率多出第五阶）；
 *  ⑦ 正式/暂存 DDL 列与列序 = `MetricAdsSpec.columns`（插入按位置写，列序漂移即写错列）。
 *
 * 只读夹具与隔离仓库由 `P2TestSupport.spark` 提供，绝不触碰在产 `spark-warehouse`。
 */
class AdsCartRateSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  /** 统计日（分区 dt，yyyyMMdd） */
  private val Dt = "20260901"

  /** 无浏览日：只有 cart_add 用户（分母 0 ⇒ NULL 规则专用） */
  private val DtNoView = "20260902"

  private val Snap = "S_CART"
  private val SnapNoView = "S_CART_NOVIEW"

  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-cart-rate")
    ns = WarehouseNamespace.of("dw_cart")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }

    writeBehavior(Dt, Seq(
      1L -> "view", 1L -> "cart_add",
      2L -> "view", 2L -> "cart_add",
      3L -> "view", 3L -> "favorite", // 仅收藏：进 intent_users，不进 cart_users
      4L -> "cart_add", // 只加购未浏览：进分子，不进分母
      5L -> "view",
      6L -> "view", 6L -> "cart_remove", // cart_remove 不是加购
      7L -> "search" // 与四阶段都无关
    ))
    writeBehavior(DtNoView, Seq(8L -> "cart_add", 9L -> "search"))
    writeOrders(Dt, Seq((201L, 1L, 1), (202L, 3L, 0)))

    spark.sql(DwsSql.funnelDay(ns, Dt))
    spark.sql(DwsSql.funnelDay(ns, DtNoView))
    spark.sql(AdsSql.funnel(ns, Dt, Some(Snap)))
    spark.sql(AdsSql.funnel(ns, DtNoView, Some(SnapNoView)))
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  /**
   * 行为明细（`dwd_user_behavior_detail`）与派生口径：
   *
   * | 用户 | 行为 | view_users | intent_users(favorite\|cart_add) | cart_users(cart_add) |
   * |------|------|-----------|----------------------------------|----------------------|
   * | 1    | view + cart_add | ✓ | ✓ | ✓ |
   * | 2    | view + cart_add | ✓ | ✓ | ✓ |
   * | 3    | view + favorite | ✓ | ✓ | **✗**（仅收藏，判别探针） |
   * | 4    | cart_add（无 view） | **✗** | ✓ | ✓（判别探针：分子可含未浏览用户） |
   * | 5    | view | ✓ | ✗ | ✗ |
   * | 6    | view + cart_remove | ✓ | ✗ | **✗**（cart_remove 不是加购） |
   * | 7    | search | ✗ | ✗ | ✗ |
   *
   * ⇒ `view_users = 5`、`intent_users = 4`（intent_rate `0.8000`）、`cart_users = 3`
   * ⇒ **`cart_rate = 3/5 = 0.6000`**；错误实现：复用 intent ⇒ `0.8000`；只算 view∩cart ⇒ `0.4000`。
   */
  private def writeBehavior(dt: String, rows: Seq[(Long, String)]): Unit = {
    if (rows.isEmpty) return
    var behaviorId = 0L
    val row = (r: (Long, String)) => {
      behaviorId += 1
      s"""SELECT ${behaviorId}L AS behavior_id, ${r._1}L AS user_id, CAST(NULL AS BIGINT) AS product_id,
         |       CAST(NULL AS BIGINT) AS category_id, '${r._2}' AS behavior_type,
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
         |  ${rows.map(row).mkString(" UNION ALL ")}
         |) v""".stripMargin)
  }

  /** 订单明细（`dwd_order_detail`）：user 1 有效支付、user 3 未支付 ⇒ order_users=2、pay_users=1 */
  private def writeOrders(dt: String, rows: Seq[(Long, Long, Int)]): Unit = {
    if (rows.isEmpty) return
    val row = (r: (Long, Long, Int)) =>
      s"""SELECT ${r._1}L AS order_id, ${r._2}L AS user_id, 1L AS product_id, 1L AS category_id,
         |       1 AS quantity, CAST(10.00 AS DECIMAL(18,2)) AS unit_price,
         |       CAST(0.00 AS DECIMAL(18,2)) AS discount, CAST(10.00 AS DECIMAL(18,2)) AS amount,
         |       'PAID' AS order_status, CAST(NULL AS TIMESTAMP) AS order_time, '$dt' AS order_date,
         |       CAST(NULL AS STRING) AS city_level, CAST(NULL AS TIMESTAMP) AS paid_at,
         |       CAST(10.00 AS DECIMAL(18,2)) AS order_amount, CAST(10.00 AS DECIMAL(18,2)) AS paid_amount,
         |       CAST(0.00 AS DECIMAL(18,2)) AS refund_amount,
         |       CAST(10.00 AS DECIMAL(18,2)) AS net_paid_amount,
         |       ${r._3} AS final_paid_flag, 0 AS final_refunded_flag,
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

  private def dwsRow(dt: String) = spark.sql(
    s"""SELECT view_users, intent_users, cart_users, intent_rate, order_rate, pay_rate,
       |       overall_buy_rate, cart_rate
       |FROM ${ns.dws}.dws_behavior_funnel_day WHERE dt = '$dt'""".stripMargin).collect().head

  private def adsRows(snapshot: String, dt: String) = spark.sql(
    s"""SELECT stage, user_count, conversion_rate, overall_buy_rate, overall_cart_rate
       |FROM ${AdsSql.staging(ns, "ads_behavior_funnel")}
       |WHERE snapshot_id = '$snapshot' AND dt = '$dt'""".stripMargin).collect()

  private def spec = MetricAdsSpec.TABLES
    .find(_.mysqlTable == "ads_behavior_funnel_m")
    .getOrElse(fail("MetricAdsSpec 未登记 ads_behavior_funnel_m"))

  // ---------------------------------------------------------------- 用例

  "dws_behavior_funnel_day" should
    "落加购去重人数与加购率（cart_add 去重 ÷ view 去重，且不等于 intent 口径）" in {
    val r = dwsRow(Dt)
    withClue(s"view_users=${r.getAs[Any]("view_users")} intent_users=${r.getAs[Any]("intent_users")} " +
      s"cart_users=${r.getAs[Any]("cart_users")} cart_rate=${r.getAs[Any]("cart_rate")}；") {
      r.getLong(r.fieldIndex("view_users")) should be(5L)
      r.getLong(r.fieldIndex("intent_users")) should be(4L) // 含 favorite
      r.getLong(r.fieldIndex("cart_users")) should be(3L) // 仅 cart_add（u4 无浏览也计入分子）
      r.getAs[Any]("intent_rate").toString should be("0.8000")
      r.getAs[Any]("cart_rate").toString should be("0.6000")
    }
    withClue("加购率必须与「intent 口径」区分开：3/5=0.6000 ≠ 4/5=0.8000；") {
      r.getAs[Any]("cart_rate").toString should not be "0.8000"
      r.getAs[Any]("cart_rate").toString should not be "0.4000" // 只算 view∩cart 的错误实现
    }
  }

  "ads_behavior_funnel.overall_cart_rate" should
    "四行同值且等于 DWS 的 cart_rate（透传，不在 ADS 重算）" in {
    val rows = adsRows(Snap, Dt)
    rows.map(_.getAs[Any]("stage").toString).toSet should be(Set("view", "intent", "order", "pay"))
    val dwsRate = dwsRow(Dt).getAs[Any]("cart_rate")
    rows.foreach { row =>
      withClue(s"stage=${row.getAs[Any]("stage")} overall_cart_rate=${row.getAs[Any]("overall_cart_rate")} " +
        s"DWS cart_rate=$dwsRate；") {
        row.getAs[Any]("overall_cart_rate").toString should be("0.6000")
        row.getAs[Any]("overall_cart_rate").toString should be(dwsRate.toString)
      }
    }
  }

  "加购率" should
    "与 DWD 行为明细按字典公式独立重算的结果一致（逐层对账）" in {
    val oracle = spark.sql(
      s"""SELECT COUNT(DISTINCT CASE WHEN behavior_type = 'view' THEN user_id END) AS view_users,
         |       COUNT(DISTINCT CASE WHEN behavior_type = 'cart_add' THEN user_id END) AS cart_users,
         |       COUNT(DISTINCT CASE WHEN behavior_type IN ('favorite','cart_add') THEN user_id END) AS intent_users
         |FROM ${ns.dwd}.dwd_user_behavior_detail
         |WHERE dt = '$Dt'""".stripMargin).head()
    val viewUsers = oracle.getLong(0)
    val cartUsers = oracle.getLong(1)
    val intentUsers = oracle.getLong(2)
    withClue(s"DWD 重算：view=$viewUsers cart=$cartUsers intent=$intentUsers；") {
      viewUsers should be(5L)
      cartUsers should be(3L)
      intentUsers should be(4L) // 判别力：三种口径在这份夹具上互不相同
    }

    val dwsRate = new java.math.BigDecimal(String.valueOf(dwsRow(Dt).getAs[Any]("cart_rate")))
    val expect = new java.math.BigDecimal(cartUsers).divide(
      new java.math.BigDecimal(viewUsers), 4, java.math.RoundingMode.HALF_UP)
    withClue(s"DWS cart_rate=$dwsRate；DWD 金额外重算 $cartUsers/$viewUsers=$expect；") {
      dwsRate.compareTo(expect) should be(0)
    }
    adsRows(Snap, Dt).foreach { row =>
      new java.math.BigDecimal(String.valueOf(row.getAs[Any]("overall_cart_rate"))).compareTo(expect) should be(0)
    }
  }

  "加购率在无浏览统计日" should
    "为 NULL（分母 0 不除零、不写 0），而分子仍如实落库" in {
    val r = dwsRow(DtNoView)
    withClue(s"无浏览日：view_users=${r.getAs[Any]("view_users")} cart_users=${r.getAs[Any]("cart_users")} " +
      s"cart_rate=${r.getAs[Any]("cart_rate")}；") {
      r.getLong(r.fieldIndex("view_users")) should be(0L)
      r.getLong(r.fieldIndex("cart_users")) should be(1L) // 分母为 0 不代表没有加购用户
      r.isNullAt(r.fieldIndex("cart_rate")) should be(true)
    }
    adsRows(SnapNoView, DtNoView).foreach { row =>
      withClue(s"stage=${row.getAs[Any]("stage")} overall_cart_rate=${row.getAs[Any]("overall_cart_rate")}；") {
        row.isNullAt(row.fieldIndex("overall_cart_rate")) should be(true)
      }
    }
  }

  "漏斗阶段集合" should
    "仍只有 view/intent/order/pay 四个（加购率是整体率列，不得新增第五阶段）" in {
    adsRows(Snap, Dt).length should be(4)
    adsRows(SnapNoView, DtNoView).length should be(4)
    MetricAdsSpec.TABLES.size should be(8) // 设计 §9.3 L322/§24.4：首期 ADS 恒为 8 张，本节不新增表
  }

  "ADS 漏斗表结构" should
    "正式/暂存 DDL 的列与列序都与 MetricAdsSpec 一致（插入按位置写，列序漂移即写错列）" in {
    val formalCols = spark.table(AdsSql.formal(ns, "ads_behavior_funnel")).columns.toSeq
    withClue(s"正式表列=$formalCols；spec 列=${spec.columns}；") {
      formalCols should be(spec.columns :+ "dt")
    }

    val stagingCols = spark.table(AdsSql.staging(ns, "ads_behavior_funnel")).columns.toSeq
    withClue(s"暂存表列=$stagingCols；spec 列=${spec.columns}；") {
      stagingCols.take(spec.columns.size) should be(spec.columns)
      stagingCols.drop(spec.columns.size).toSet should be(Set("snapshot_id", "dt"))
    }
  }
}
