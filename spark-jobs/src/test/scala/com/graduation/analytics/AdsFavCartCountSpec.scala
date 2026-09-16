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
 * S3-08 **收藏/加购次数落地**（阶段 3「指标计算」：口径固定 + 逐层对账 + 制品可查）。
 *
 * 设计标尺（逐字）：
 *  - 设计 §11.2 L425「收藏/加购 | **对应行为事件数**，用户转化时另算去重用户数 | 行为」——
 *    本行要求的是**次数**，并明确「去重用户数」是**另一件事**（不是同义词）；
 *  - 字典 `docs/contracts/metric-dictionary.md:21`「`fav_cnt` 收藏次数 = `count(favorite 事件)` | 日 |
 *    event_time | **`dwd_user_behavior_detail`**」、`:22`「`cart_add_cnt` 加购次数 = `count(cart_add 事件)` |
 *    日 | event_time | `dwd_user_behavior_detail`」；
 *  - 设计 §9.3 L333 起 ADS 口径表把「行为」类日粒度计数归在大盘表一族（与 `pv` 同表同粒度）；
 *  - 指导书 §7 阶段 3 ①「固定粒度/分子分母/时间窗口/空值规则/版本」②「逐层对账」。
 *
 * 本层实现的口径声明（设计/字典只给公式与源表，落点由本节声明并登记）：
 *  1. **粒度**：日粒度、全站维度（`dt` = 统计分区），与同表 `pv` 同粒度；
 *  2. **定义**：`fav_cnt` = 当日 `behavior_type = 'favorite'` 的事件**条数**；
 *     `cart_add_cnt` = 当日 `behavior_type = 'cart_add'` 的事件**条数**（`cart_remove` 不是加购）；
 *  3. **源表**：字典登记的 `dwd_user_behavior_detail`（本层按字典源表直取，不绕道 DWS 的 per-user 汇总，
 *     避免同一口径出现两个聚合属主；跨层一致性由本用例的逐层对账断言钉住）；
 *  4. **落点**：`ads_operation_overview` 末尾追加两列 —— 与 `pv`（同为行为事件次数、同为日粒度单行）
 *     同表；**不进** `ads_behavior_funnel`（§11.3 L441 漏斗恒为 view/intent/order/pay 四阶段，
 *     且 stage 行的语义是「去重用户数」），**不做** `ads_hot_product` 的 per-product 列
 *     （该表已有 per-product `fav`/`cart`，是全站口径之外的另一种粒度）；
 *  5. **空值规则**：当日无对应事件 ⇒ **0**（与 `pv` 的 `COUNT`→0 同型）；次数不是比率，
 *     字典「分母为 0 ⇒ null」规则只作用于率；
 *  6. **版本**：字典两行均无窗口/变体声明，定义版本 `v1`（随 `metric_definition` 种子行登记）。
 *
 * 为什么必须在 ADS 落列而不是让页面临时算：指导书 §8 L199「稳定指标公式…**不靠前端/AI 临时算出指标**」；
 * 现网事实是这两个码在字典里已登记、但**没有任何 ADS 载体**（`docs/contracts/metric-lineage.md` §2/L53
 * 把 `cart_add_cnt` 列为「仍未落地」，S3-04 落的是**率** `cart_rate`，两者不是同一个指标码）。
 *
 * 判定方式（不读 SQL 文本下结论）：
 *  ① 真跑 `DWD → ads_operation_overview`，断言两列落到暂存分区；
 *  ② **口径判别力**：夹具刻意让「次数」与「去重人数」取不同值（收藏 3 次 / 2 人、加购 5 次 / 3 人）
 *     ⇒ 拿 `COUNT(DISTINCT user_id)` 冒充次数的实现会给出 2/3，被断言抓住；
 *  ③ **逐层对账**：ADS 值 = DWD 按字典公式独立重算 = `dws_user_behavior_day` 的 `SUM(fav)/SUM(cart)`；
 *  ④ 既有列（`pv`/`uv`/`dau`）不被本轮改动带偏；
 *  ⑤ 无收藏/加购事件日 ⇒ 两列为 **0**（不是 NULL、不是丢行）；
 *  ⑥ 正式/暂存 DDL 列与列序 = `MetricAdsSpec.columns`，且存量表补列清单末尾列与之一致
 *     （插入按位置写，列序漂移即写错列）。
 *
 * 只读夹具与隔离仓库由 `P2TestSupport.spark` 提供，绝不触碰在产 `spark-warehouse`。
 */
class AdsFavCartCountSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  /** 统计日（分区 dt，yyyyMMdd） */
  private val Dt = "20260901"

  /** 无收藏/加购日：只有 view/search（两列必须为 0 而不是 NULL） */
  private val DtNoCnt = "20260902"

  private val Snap = "S_FAVCART"
  private val SnapNoCnt = "S_FAVCART_NOCNT"

  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-fav-cart-count")
    ns = WarehouseNamespace.of("dw_favcart")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }

    writeBehavior(Dt, Seq(
      1L -> "view", 1L -> "favorite", 1L -> "favorite", 1L -> "cart_add",
      2L -> "view", 2L -> "cart_add", 2L -> "cart_add", 2L -> "cart_add",
      3L -> "view", 3L -> "favorite",
      4L -> "cart_add", // 只加购未浏览
      5L -> "cart_remove", // 不是加购
      6L -> "search"
    ))
    writeBehavior(DtNoCnt, Seq(7L -> "view", 8L -> "search"))

    // 上游：订单明细 + 日交易汇总 + 用户×观察期交易汇总（大盘其它列的上游，本层不改其口径）
    writeOrders(Dt, Seq((201L, 1L, 1)))
    writeOrders(DtNoCnt, Seq((202L, 7L, 1)))
    spark.sql(DwsSql.userBehaviorDay(ns, Dt))
    spark.sql(DwsSql.userBehaviorDay(ns, DtNoCnt))
    spark.sql(DwsSql.tradeDay(ns, Dt))
    spark.sql(DwsSql.tradeDay(ns, DtNoCnt))
    spark.sql(DwsSql.userTradePeriod(ns, Dt, Dt, Dt))
    spark.sql(DwsSql.userTradePeriod(ns, DtNoCnt, DtNoCnt, DtNoCnt))

    spark.sql(AdsSql.operationOverview(ns, Dt, Some(Snap)))
    spark.sql(AdsSql.operationOverview(ns, DtNoCnt, Some(SnapNoCnt)))
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  /**
   * 行为明细（`dwd_user_behavior_detail`）与派生口径：
   *
   * | 用户 | 行为 | 收藏**次数** | 加购**次数** | 收藏人数 | 加购人数 |
   * |------|------|--------------|--------------|----------|----------|
   * | 1    | view + favorite×2 + cart_add | 2 | 1 | ✓ | ✓ |
   * | 2    | view + cart_add×3 | 0 | 3 | ✗ | ✓ |
   * | 3    | view + favorite | 1 | 0 | ✓ | ✗ |
   * | 4    | cart_add（无 view） | 0 | 1 | ✗ | ✓ |
   * | 5    | cart_remove（不是加购） | 0 | 0 | ✗ | ✗ |
   * | 6    | search | 0 | 0 | ✗ | ✗ |
   *
   * ⇒ `fav_cnt = 3`、`cart_add_cnt = 5`；对照的**去重人数**是 `2` 与 `3`（判别力：两组值互不相同）
   * ⇒ 同时 `pv = 3`、`uv = 3`、`dau = 6`（既有列口径）。
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

  /** 订单明细（`dwd_order_detail`）：每个统计日一笔有效支付，保证大盘行存在且其它列非空 */
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

  private def staging = AdsSql.staging(ns, "ads_operation_overview")

  private def spec = MetricAdsSpec.TABLES
    .find(_.mysqlTable == "ads_operation_overview_m")
    .getOrElse(fail("MetricAdsSpec 未登记 ads_operation_overview_m"))

  private def overviewRow(snapshot: String, dt: String) =
    spark.sql(s"SELECT * FROM $staging WHERE snapshot_id = '$snapshot' AND dt = '$dt'").collect().head

  // ---------------------------------------------------------------- 用例

  "ads_operation_overview" should
    "末尾落 fav_cnt / cart_add_cnt，列序与 MetricAdsSpec 一致（正式/暂存同序）" in {
    withClue(s"spec 列=${spec.columns}；") {
      spec.columns.takeRight(2) should be(Seq("fav_cnt", "cart_add_cnt"))
    }

    val formalCols = spark.table(AdsSql.formal(ns, "ads_operation_overview")).columns.toSeq
    withClue(s"正式表列=$formalCols；spec 列=${spec.columns}；") {
      formalCols should be(spec.columns :+ "dt")
    }

    val stagingCols = spark.table(staging).columns.toSeq
    withClue(s"暂存表列=$stagingCols；spec 列=${spec.columns}；") {
      stagingCols.take(spec.columns.size) should be(spec.columns)
      stagingCols.drop(spec.columns.size).toSet should be(Set("snapshot_id", "dt"))
    }
  }

  "收藏/加购次数" should
    "等于当日对应行为的事件条数（设计 §11.2 L425「对应行为事件数」）" in {
    val r = overviewRow(Snap, Dt)
    withClue(s"fav_cnt=${r.getAs[Any]("fav_cnt")} cart_add_cnt=${r.getAs[Any]("cart_add_cnt")}；") {
      r.getLong(r.fieldIndex("fav_cnt")) should be(3L) // u1×2 + u3×1
      r.getLong(r.fieldIndex("cart_add_cnt")) should be(5L) // u1×1 + u2×3 + u4×1
    }
  }

  "收藏/加购次数" should
    "不等于去重用户数（拿 COUNT(DISTINCT user_id) 冒充次数的实现必须被抓住）" in {
    val oracle = spark.sql(
      s"""SELECT COUNT(CASE WHEN behavior_type = 'favorite' THEN 1 END) AS fav_cnt,
         |       COUNT(CASE WHEN behavior_type = 'cart_add' THEN 1 END) AS cart_add_cnt,
         |       COUNT(DISTINCT CASE WHEN behavior_type = 'favorite' THEN user_id END) AS fav_users,
         |       COUNT(DISTINCT CASE WHEN behavior_type = 'cart_add' THEN user_id END) AS cart_users
         |FROM ${ns.dwd}.dwd_user_behavior_detail
         |WHERE dt = '$Dt'""".stripMargin).head()

    val favUsers = oracle.getLong(2)
    val cartUsers = oracle.getLong(3)
    withClue(s"DWD 重算：收藏 $favUsers 人 / 加购 $cartUsers 人；") {
      favUsers should be(2L)
      cartUsers should be(3L)
    }

    val r = overviewRow(Snap, Dt)
    val favCnt = r.getLong(r.fieldIndex("fav_cnt"))
    val cartCnt = r.getLong(r.fieldIndex("cart_add_cnt"))
    withClue(s"次数（$favCnt/$cartCnt）与人数（$favUsers/$cartUsers）必须被区分开；") {
      favCnt should not be favUsers
      cartCnt should not be cartUsers
    }
  }

  "收藏/加购次数" should
    "与 DWD 按字典公式独立重算、以及 DWS 的 SUM(fav)/SUM(cart) 逐层对账相等" in {
    val r = overviewRow(Snap, Dt)

    val dwd = spark.sql(
      s"""SELECT COUNT(CASE WHEN behavior_type = 'favorite' THEN 1 END) AS fav_cnt,
         |       COUNT(CASE WHEN behavior_type = 'cart_add' THEN 1 END) AS cart_add_cnt
         |FROM ${ns.dwd}.dwd_user_behavior_detail WHERE dt = '$Dt'""".stripMargin).head()

    val dws = spark.sql(
      s"""SELECT COALESCE(SUM(fav), 0) AS fav_cnt, COALESCE(SUM(cart), 0) AS cart_add_cnt
         |FROM ${ns.dws}.dws_user_behavior_day WHERE dt = '$Dt'""".stripMargin).head()

    withClue(s"ADS=${r.getAs[Any]("fav_cnt")}/${r.getAs[Any]("cart_add_cnt")} " +
      s"DWD 重算=${dwd.getLong(0)}/${dwd.getLong(1)} DWS 汇总=${dws.getLong(0)}/${dws.getLong(1)}；") {
      r.getLong(r.fieldIndex("fav_cnt")) should be(dwd.getLong(0))
      r.getLong(r.fieldIndex("cart_add_cnt")) should be(dwd.getLong(1))
      r.getLong(r.fieldIndex("fav_cnt")) should be(dws.getLong(0))
      r.getLong(r.fieldIndex("cart_add_cnt")) should be(dws.getLong(1))
    }
  }

  "当日无收藏/加购事件" should
    "两列为 0 而不是 NULL（次数口径的空值规则与 pv 同型）" in {
    val r = overviewRow(SnapNoCnt, DtNoCnt)
    withClue(s"无事件日：fav_cnt=${r.getAs[Any]("fav_cnt")} cart_add_cnt=${r.getAs[Any]("cart_add_cnt")}；") {
      r.isNullAt(r.fieldIndex("fav_cnt")) should be(false)
      r.isNullAt(r.fieldIndex("cart_add_cnt")) should be(false)
      r.getLong(r.fieldIndex("fav_cnt")) should be(0L)
      r.getLong(r.fieldIndex("cart_add_cnt")) should be(0L)
      r.getLong(r.fieldIndex("pv")) should be(1L) // 既有列不受影响（该日只有 1 次 view）
    }
  }

  "存量 ads_operation_overview 补列清单" should
    "末尾两列与 MetricAdsSpec 的末尾两列同名同序（ALTER 追加列，列序漂移即写错列）" in {
    val want = Seq("fav_cnt", "cart_add_cnt")
    Seq("ads_operation_overview", "ads_operation_overview__staging").foreach { table =>
      val cols = LocalSchemaInitJob.R7_ADDED_COLUMNS.getOrElse(("ads", table),
        fail(s"R7_ADDED_COLUMNS 未登记 $table"))
      withClue(s"$table 补列清单=$cols；") {
        cols.takeRight(2).map(_.split(" ").head) should be(want)
      }
    }
    // 补列清单的末尾列必须正是 spec 的末尾列（两者顺序不同 ⇒ 写入按位置错位）
    LocalSchemaInitJob.R7_ADDED_COLUMNS.get(("ads", "ads_operation_overview")).get
      .map(_.split(" ").head).takeRight(2) should be(spec.columns.takeRight(2))
  }
}
