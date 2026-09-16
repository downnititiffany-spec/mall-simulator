package com.graduation.analytics

import com.graduation.analytics.job.{AdsQualityJob, LocalSchemaInitJob}
import com.graduation.analytics.metric.MetricAdsSpec
import com.graduation.analytics.sql.{AdsSql, DwsSql}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S3-03 **复购率落地**（阶段 3「指标计算」第 3 项：口径固定 + 逐层对账）。
 *
 * 设计标尺（逐字）：
 *  - 设计 §11.2 L433 `repeat_rate` =「有效购买 ≥2 次用户 ÷ 支付用户；**完全退款不算有效复购订单**；
 *    必须声明观察期和变体」；
 *  - 设计 §11.4 L449「复购区分支付复购/有效复购，取消不计、**全退是否剔除按当前有效口径**」；
 *  - 字典 `docs/contracts/metric-dictionary.md:30` 源表 = `dws_user_trade_period`，grain =「观察期默认 30 天」；
 *  - 指导书 §7 阶段 3 ①「固定粒度/分子分母/时间窗口/空值规则/版本」②「逐层对账」。
 *
 * 本 spec **自行声明**本层实现的两个口径维度（设计只要求声明、不指定取值）：
 *  1. **观察期**：取作业/单据层的唯一既有参数 `[periodStart, periodEnd]`（`DwsSql.userTradePeriod` 的入参，
 *     即 `dws_user_trade_period.period_start/period_end` 的写入者）。缺省 = 统计日当日（沿用既有缺省，
 *     不在此隐式改成字典页面默认的 30 天 —— 否则两层各有一个默认窗口）；ADS 行把该窗口**落库声明**为
 *     `repeat_period_start/repeat_period_end`（ISO），使发布出的 `metric_value` 能声明自己是窗口指标
 *     而不是 `day:` 单日指标；
 *  2. **变体**：实现字典/设计的默认变体「**有效复购率**」（分子 = 有效购买订单 ≥2 次的用户），
 *     完全退款订单**不计入**有效购买次数。另一变体「支付复购率」本节不实现（见 F-36 登记的 backlog）。
 *
 * 改动前的可证事实：`repeat_rate` 无任何落地面（审计 `v2-completeness-audit.md:172`「缺 ADS 列 →
 * 页面/发布通路都不存在」）；`dws_user_trade_period` 只有 `order_count`，而 `order_count` 含全额退款订单
 * ⇒ **仅凭现有列无法复现黄金值**（本夹具同构于黄金夹具：naive 支付复购率 = `0.6667`，有效复购率 = `0.3333`）。
 *
 * 判定方式（不读 SQL 文本下结论）：
 *  ① 真跑 `DWD → dws_user_trade_period → ads_operation_overview`，断言有效订单数/复购率落到 ADS；
 *  ② **口径判别力**：夹具含「部分退款（仍算有效）」「全额退款（不算有效）」「未支付（两层都不进）」三类行，
 *     并同时算出 naive 支付复购率 `0.6667` —— 实现若漏掉「全退剔除」，断言值会变成 `0.6667`；
 *  ③ **跨层对账**：用 DWD 的**金额规则**（`Σrefund >= paid` ⇒ 全退）独立重算一遍，与走 `final_refunded_flag`
 *     标记的实现逐值相等（黄金夹具用的就是这条金额规则）；
 *  ④ 观察期声明真的随参数走：窗口取 `[Ps, Pe]` = 两个分区，ADS 声明 ISO 化后的窗口；
 *  ⑤ 分母 0（无买家日）⇒ `repeat_rate` 为 NULL 且**不伪造窗口**；
 *  ⑥ 空值规则：`repeat_rate=NULL` 是合法业务态，发布前关键列规则**不得**因此阻断；
 *  ⑦ 正式/暂存 DDL 列与列序 = `MetricAdsSpec.columns`（插入按位置写，列序漂移即写错列）。
 *
 * 只读夹具与隔离仓库由 `P2TestSupport.spark` 提供，绝不触碰在产 `spark-warehouse`。
 */
class AdsRepeatRateSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  /** 统计日（分区 dt，yyyyMMdd） */
  private val Dt = "20260901"

  /** 观察期 [Ps, Pe]（跨两个 DWD 分区，用来证明"窗口真的随参数走"而不是恒等于 dt） */
  private val Ps = "20260831"
  private val Pe = "20260901"

  /** 无买家统计日（分母 0 → NULL 规则专用） */
  private val DtEmpty = "20260902"

  private val Snap = "S_REPEAT"
  private val SnapEmpty = "S_REPEAT_EMPTY"

  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-repeat-rate")
    ns = WarehouseNamespace.of("dw_repeat")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }

    writeBehavior(Dt, Seq(1L -> "view", 2L -> "view", 3L -> "view"))
    writeBehavior(DtEmpty, Seq.empty)
    // o101/o104 落在观察期起点分区；其余落在统计日分区
    writeOrders(Ps, Seq((101L, 1L, "110.00", 1, "0.00", 0), (104L, 2L, "80.00", 1, "0.00", 0)))
    writeOrders(Dt, Seq((102L, 1L, "60.00", 1, "20.00", 0), (103L, 2L, "40.00", 1, "40.00", 1),
      (105L, 3L, "30.00", 1, "0.00", 0), (106L, 4L, "999.00", 0, "0.00", 0)))

    // 上游：用户×观察期交易汇总（窗口 = 作业参数），日交易汇总（大盘其它列的上游）
    spark.sql(DwsSql.userTradePeriod(ns, Dt, Ps, Pe))
    spark.sql(DwsSql.userTradePeriod(ns, DtEmpty, DtEmpty, DtEmpty))
    spark.sql(DwsSql.tradeDay(ns, Dt))
    spark.sql(DwsSql.tradeDay(ns, DtEmpty))
    spark.sql(AdsSql.operationOverview(ns, Dt, Some(Snap)))
    spark.sql(AdsSql.operationOverview(ns, DtEmpty, Some(SnapEmpty)))
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  /**
   * 订单明细（`dwd_order_detail`，一单一行）：
   *
   * | 订单 | 分区 | 用户 | amount | final_paid_flag | refund_amount | final_refunded_flag | 有效购买 | 说明 |
   * |------|------|------|--------|-----------------|---------------|---------------------|----------|------|
   * | 101  | Ps   | 1    | 110.00 | 1               | 0.00          | 0                   | 是       | |
   * | 102  | Dt   | 1    | 60.00  | 1               | 20.00         | 0                   | 是       | 部分退款仍算有效 |
   * | 104  | Ps   | 2    | 80.00  | 1               | 0.00          | 0                   | 是       | |
   * | 103  | Dt   | 2    | 40.00  | 1               | 40.00         | 1（全额退款）        | **否**   | 判别探针 |
   * | 105  | Dt   | 3    | 30.00  | 1               | 0.00          | 0                   | 是       | |
   * | 106  | Dt   | 4    | 999.00 | 0               | 0.00          | 0                   | **否**   | 未支付，两层都不进 |
   *
   * ⇒ 支付用户 3（u1/u2/u3）；有效购买次数 u1=2、u2=1、u3=1
   * ⇒ 有效复购用户 1（仅 u1）⇒ `repeat_rate = 1/3 = 0.3333`
   * ⇒ 若按「支付订单数 ≥2」这一 naive 口径，u2 也会进分子 ⇒ `0.6667`（错误实现可被断言抓住）。
   */
  private def writeOrders(dt: String, rows: Seq[(Long, Long, String, Int, String, Int)]): Unit = {
    if (rows.isEmpty) return
    val row = (r: (Long, Long, String, Int, String, Int)) =>
      s"""SELECT ${r._1}L AS order_id, ${r._2}L AS user_id, 1L AS product_id, 1L AS category_id,
         |       1 AS quantity, CAST(${r._3} AS DECIMAL(18,2)) AS unit_price,
         |       CAST(0.00 AS DECIMAL(18,2)) AS discount, CAST(${r._3} AS DECIMAL(18,2)) AS amount,
         |       'PAID' AS order_status, CAST(NULL AS TIMESTAMP) AS order_time, '$dt' AS order_date,
         |       CAST(NULL AS STRING) AS city_level, CAST(NULL AS TIMESTAMP) AS paid_at,
         |       CAST(${r._3} AS DECIMAL(18,2)) AS order_amount, CAST(${r._3} AS DECIMAL(18,2)) AS paid_amount,
         |       CAST(${r._5} AS DECIMAL(18,2)) AS refund_amount,
         |       CAST(${r._3} AS DECIMAL(18,2)) AS net_paid_amount,
         |       ${r._4} AS final_paid_flag, ${r._6} AS final_refunded_flag,
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

  private def writeBehavior(dt: String, rows: Seq[(Long, String)]): Unit = {
    if (rows.isEmpty) return
    val row = (r: (Long, String)) =>
      s"""SELECT ${r._1}L AS behavior_id, ${r._1}L AS user_id, CAST(NULL AS BIGINT) AS product_id,
         |       CAST(NULL AS BIGINT) AS category_id, '${r._2}' AS behavior_type,
         |       CAST(NULL AS TIMESTAMP) AS event_time, '$dt' AS event_date,
         |       CAST(NULL AS INT) AS event_hour, CAST(NULL AS STRING) AS city_level,
         |       CAST(NULL AS STRING) AS channel, CAST(NULL AS STRING) AS session_id,
         |       CAST(NULL AS BIGINT) AS source_batch_id, CAST(NULL AS BIGINT) AS user_key,
         |       CAST(NULL AS BIGINT) AS product_key, CAST(NULL AS BIGINT) AS category_key""".stripMargin
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '$dt')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
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

  private def dwsRows = spark.sql(
    s"""SELECT user_id, order_count, sale_amount, valid_order_count, period_start, period_end
       |FROM ${ns.dws}.dws_user_trade_period WHERE dt = '$Dt' ORDER BY user_id""".stripMargin)
    .collect().map(r => r.getLong(0) -> r).toMap

  // ---------------------------------------------------------------- 用例

  "dws_user_trade_period" should
    "落有效购买订单数（剔除全额退款），且不改动含全退的 order_count 口径" in {
    val rows = dwsRows
    withClue(s"用户集合=${rows.keySet}（未支付用户不得进 dws_user_trade_period）；") {
      rows.keySet should be(Set(1L, 2L, 3L)) // u4 未支付，两层都不进
    }
    val expect = Map(1L -> (2L, 2L), 2L -> (2L, 1L), 3L -> (1L, 1L)) // user -> (order_count, valid_order_count)
    expect.foreach { case (uid, (orders, valid)) =>
      val r = rows(uid)
      withClue(s"用户 $uid：order_count=${r.getAs[Any]("order_count")}（含全退口径）valid_order_count=" +
        s"${r.getAs[Any]("valid_order_count")}；") {
        r.getLong(1) should be(orders)
        r.getLong(3) should be(valid)
      }
    }
    // 观察期声明写进 DWS 行（窗口的唯一所有者），后续 ADS 声明以它为源
    Seq(1L, 2L, 3L).foreach { uid =>
      withClue(s"用户 $uid 的观察期声明：${rows(uid).getAs[Any]("period_start")} ~ ${rows(uid).getAs[Any]("period_end")}；") {
        rows(uid).getAs[Any]("period_start").toString should be(Ps)
        rows(uid).getAs[Any]("period_end").toString should be(Pe)
      }
    }
  }

  "ads_operation_overview.repeat_rate" should
    "是有效复购率（全退订单不算有效复购），并声明观察期窗口" in {
    val row = overviewRow(Snap, Dt)
    val rate = row.getAs[Any]("repeat_rate")
    withClue(s"实际 repeat_rate=$rate；有效复购用户/支付用户 = 1/3 ⇒ 0.3333；" +
      "naive 支付复购率（order_count>=2）应为 0.6667；") {
      (rate == null) should be(false)
      rate.toString should be("0.3333")
      rate.toString should not be "0.6667"
    }
    withClue(s"观察期声明=${row.getAs[Any]("repeat_period_start")} ~ " +
      s"${row.getAs[Any]("repeat_period_end")}（应为 $Ps/$Pe 的 ISO 形态）；") {
      row.getAs[Any]("repeat_period_start").toString should be("2026-08-31")
      row.getAs[Any]("repeat_period_end").toString should be("2026-09-01")
    }
  }

  "复购率" should
    "与 DWD 金额口径（Σrefund >= paid ⇒ 全退）独立重算的结果一致（逐层对账）" in {
    val oracle = spark.sql(
      s"""SELECT COUNT(DISTINCT user_id) AS pay_users,
         |       COUNT(DISTINCT CASE WHEN valid_cnt >= 2 THEN user_id END) AS repeat_users,
         |       COUNT(DISTINCT CASE WHEN order_cnt >= 2 THEN user_id END) AS naive_repeat_users
         |FROM (
         |  SELECT user_id,
         |         COUNT(DISTINCT order_id) AS order_cnt,
         |         COUNT(DISTINCT CASE WHEN refund_sum < paid_sum THEN order_id END) AS valid_cnt
         |  FROM (
         |    SELECT user_id, order_id,
         |           MAX(paid_amount) AS paid_sum, SUM(refund_amount) AS refund_sum
         |    FROM ${ns.dwd}.dwd_order_detail
         |    WHERE dt >= '$Ps' AND dt <= '$Pe' AND final_paid_flag = 1
         |    GROUP BY user_id, order_id
         |  ) o
         |  GROUP BY user_id
         |) g""".stripMargin).head()
    val payUsers = oracle.getLong(0)
    val repeatUsers = oracle.getLong(1)
    val naiveUsers = oracle.getLong(2)
    withClue(s"金额口径重算：支付用户=$payUsers 有效复购用户=$repeatUsers 支付复购用户=$naiveUsers；") {
      payUsers should be(3L)
      repeatUsers should be(1L)
      naiveUsers should be(2L) // 判别力：两种口径在这份夹具上必然不同
    }

    val row = overviewRow(Snap, Dt)
    val ads = new java.math.BigDecimal(String.valueOf(row.getAs[Any]("repeat_rate")))
    withClue(s"ADS=$ads；金额口径 ${repeatUsers}/${payUsers}；") {
      ads.compareTo(new java.math.BigDecimal(repeatUsers).divide(
        new java.math.BigDecimal(payUsers), 4, java.math.RoundingMode.HALF_UP)) should be(0)
    }
  }

  "复购率在无买家统计日" should
    "为 NULL 且不伪造观察期声明（分母 0 不满除零）" in {
    val row = overviewRow(SnapEmpty, DtEmpty)
    withClue(s"无买家日：repeat_rate=${row.getAs[Any]("repeat_rate")}、" +
      s"窗口=${row.getAs[Any]("repeat_period_start")} ~ ${row.getAs[Any]("repeat_period_end")}；") {
      row.isNullAt(row.fieldIndex("repeat_rate")) should be(true)
      row.isNullAt(row.fieldIndex("repeat_period_start")) should be(true)
      row.isNullAt(row.fieldIndex("repeat_period_end")) should be(true)
    }
  }

  "发布前关键列规则（AdsQualityJob 规则 3）" should
    "不把『无买家 ⇒ repeat_rate 为 NULL』这一合法业务态判为缺陷" in {
    val predicates = AdsQualityJob.keyPredicates(ns)
    val pred = predicates(staging)
    val bad = spark.sql(
      s"""SELECT COUNT(*) FROM $staging
         |WHERE snapshot_id = '$SnapEmpty' AND dt = '$DtEmpty' AND ($pred)""".stripMargin).head().getLong(0)
    withClue(s"规则 `$pred` 对『pv/uv/dau=0、repeat_rate=NULL』的合法行判定行数=$bad；") { bad should be(0L) }
  }

  "ADS 运营大盘表结构" should
    "正式/暂存 DDL 的列与列序都与 MetricAdsSpec 一致（插入按位置写，列序漂移即写错列）" in {
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
}
