package com.graduation.analytics

import com.graduation.analytics.job.{AdsQualityJob, FunnelAdsJob, LocalSchemaInitJob}
import com.graduation.analytics.metric.MetricAdsSpec
import com.graduation.analytics.sql.AdsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S3-02 `ads_sale_trend` 补 **净销售额**（阶段 3「指标计算」第 2 项）：
 *  - 设计 §9.3 L333「`ads_sale_trend` / `ads_sale_trend_m`：历史已发布，**net_sale 等字段需补**」；
 *  - 设计 §11.2 L428「净销售 = 同口径支付金额 − 成功退款金额；**真实收入方向，退款归属期需冻结**」；
 *  - 指导书 §7 阶段 3 ①「固定粒度/分子分母/**金额退款口径**/空值规则/版本」、② 「逐层对账」。
 *
 * 改动前 `ads_sale_trend` 只有 `order_count/buyer_count/sale_amount/avg_order_value`：
 * 页面能看「卖了多少钱」，看不到「扣掉退款后真正剩多少」，与大盘表 `ads_operation_overview`
 * （已有 `net_sale_amount`）**口径不对称** —— 同一 dt 两张 ADS 表给出不同收入视图。
 *
 * 本 spec 的判定方式（对齐 `AdsRfmRawValueSpec` 的方法论，不读 SQL 文本下结论）：
 *  ① 真跑 `DWD → dws_trade_day → ads_sale_trend`，断言净额落到 ADS；
 *  ② **退款口径判别力**：夹具含「部分退款」「全额退款」「未支付却带退款金额」三种行，
 *     若实现把未支付行的退款也累计，净额会从 `150.00` 变成 `-350.00`（判别力来自夹具，不来自断言措辞）；
 *  ③ **跨表对账**：同一 dt 的 `ads_sale_trend` 与 `ads_operation_overview` 同源同口径 ⇒ 逐列相等；
 *     大盘表净额已有黄金值（`1493.00`，见 `docs/contracts/metric-lineage.md` §11-metric-value-active.tsv）；
 *  ④ **血缘**：ADS 净额 = 上游 `dws_trade_day.net_sale_amount`，不是 ADS 里另起一套算法；
 *  ⑤ 正式/暂存 DDL 列与列序 = `MetricAdsSpec.columns`（插入按位置写，列序漂移即写错列）；
 *  ⑥ **空值规则**：新增列为 `NOT NULL` 镜像列，`AdsQualityJob` 的发布前关键列规则必须真的覆盖它
 *     （用带 NULL 的**构造行**验证规则命中，而不是只钉住字符串）。
 *
 * 只读夹具与隔离仓库由 `P2TestSupport.spark` 提供，绝不触碰在产 `spark-warehouse`。
 */
class AdsSaleTrendNetSaleSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  /** 业务日（分区 dt，yyyyMMdd） */
  private val Dt = "20260901"

  /** 本 spec 主快照 / 空值规则专用快照（分离，避免构造行污染主断言） */
  private val Snap = "S_NETSALE"
  private val SnapNullProbe = "S_NETSALE_NULL"

  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-sale-trend-net-sale")
    ns = WarehouseNamespace.of("dw_netsale")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  /**
   * 订单明细（`dwd_order_detail`，一单一行）：
   *
   * | 订单 | 用户 | amount | final_paid_flag | refund_amount | final_refunded_flag | 是否计入 |
   * |------|------|--------|-----------------|---------------|---------------------|----------|
   * | 101  | 1    | 110.00 | 1               | 0.00          | 0                   | 是       |
   * | 102  | 1    | 60.00  | 1               | 20.00         | 0（部分退款）        | 是       |
   * | 103  | 2    | 40.00  | 1               | 40.00         | 1（全额退款）        | 是       |
   * | 104  | 2    | 999.00 | 0               | 500.00        | 0                   | **否**   |
   *
   * 期望（`dws_trade_day` / `ads_sale_trend`）：订单 3、买家 2、销售额 `210.00`、
   * 退款 `60.00`、净额 `150.00`、客单价 `70.00`。
   * 订单 104 是**退款口径判别探针**：它未支付却带 `refund_amount`，只有
   * `CASE WHEN final_paid_flag = 1 THEN refund_amount ELSE 0 END` 这样的口径才会把它排除；
   * 若实现直接 `SUM(refund_amount)`，退款变 `560.00`、净额变 `-350.00`。
   */
  private def writeOrderDetail(): Unit = {
    val row = (oid: Long, uid: Long, amt: String, paid: Int, refund: String, refunded: Int) =>
      s"""SELECT ${oid}L AS order_id, ${uid}L AS user_id, 1L AS product_id, 1L AS category_id,
         |       1 AS quantity, CAST($amt AS DECIMAL(18,2)) AS unit_price,
         |       CAST(0.00 AS DECIMAL(18,2)) AS discount, CAST($amt AS DECIMAL(18,2)) AS amount,
         |       'PAID' AS order_status, CAST(NULL AS TIMESTAMP) AS order_time, '$Dt' AS order_date,
         |       CAST(NULL AS STRING) AS city_level, CAST(NULL AS TIMESTAMP) AS paid_at,
         |       CAST($amt AS DECIMAL(18,2)) AS order_amount, CAST($amt AS DECIMAL(18,2)) AS paid_amount,
         |       CAST($refund AS DECIMAL(18,2)) AS refund_amount,
         |       CAST($amt AS DECIMAL(18,2)) AS net_paid_amount,
         |       $paid AS final_paid_flag, $refunded AS final_refunded_flag,
         |       CAST(NULL AS BIGINT) AS user_key, CAST(NULL AS BIGINT) AS product_key,
         |       CAST(NULL AS BIGINT) AS category_key""".stripMargin
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_order_detail PARTITION(dt = '$Dt')
         |SELECT order_id, user_id, product_id, category_id, quantity, unit_price, discount, amount,
         |       order_status, order_time, order_date, city_level, paid_at, order_amount, paid_amount,
         |       refund_amount, net_paid_amount, final_paid_flag, final_refunded_flag,
         |       user_key, product_key, category_key FROM (
         |  ${row(101L, 1L, "110.00", 1, "0.00", 0)}
         |  UNION ALL ${row(102L, 1L, "60.00", 1, "20.00", 0)}
         |  UNION ALL ${row(103L, 2L, "40.00", 1, "40.00", 1)}
         |  UNION ALL ${row(104L, 2L, "999.00", 0, "500.00", 0)}
         |) v""".stripMargin)
  }

  /** 行为明细（大盘表 pv/uv/dau 的输入）：u1 view、u2 view、u2 pay ⇒ pv2/uv2/dau2 */
  private def writeUserBehavior(): Unit = {
    val row = (bid: Long, uid: Long, bt: String) =>
      s"""SELECT ${bid}L AS behavior_id, ${uid}L AS user_id, CAST(NULL AS BIGINT) AS product_id,
         |       CAST(NULL AS BIGINT) AS category_id, '$bt' AS behavior_type,
         |       CAST(NULL AS TIMESTAMP) AS event_time, '$Dt' AS event_date,
         |       CAST(NULL AS INT) AS event_hour, CAST(NULL AS STRING) AS city_level,
         |       CAST(NULL AS STRING) AS channel, CAST(NULL AS STRING) AS session_id,
         |       CAST(NULL AS BIGINT) AS source_batch_id, CAST(NULL AS BIGINT) AS user_key,
         |       CAST(NULL AS BIGINT) AS product_key, CAST(NULL AS BIGINT) AS category_key""".stripMargin
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '$Dt')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  ${row(1L, 1L, "view")}
         |  UNION ALL ${row(2L, 2L, "view")}
         |  UNION ALL ${row(3L, 2L, "pay")}
         |) v""".stripMargin)
  }

  private def staging = AdsSql.staging(ns, "ads_sale_trend")

  private def spec = MetricAdsSpec.TABLES
    .find(_.mysqlTable == "ads_sale_trend_m")
    .getOrElse(fail("MetricAdsSpec 未登记 ads_sale_trend_m"))

  /** 按列名取值（**不按序号**）：`<NULL>` 显式可见，避免空值在比较里静默通过 */
  private def watched = Seq("order_count", "buyer_count", "sale_amount", "avg_order_value", "net_sale_amount")

  private def trendRow(snapshot: String): Map[String, String] =
    spark.sql(
      s"""SELECT ${watched.mkString(", ")} FROM $staging
         |WHERE snapshot_id = '$snapshot' AND dt = '$Dt'""".stripMargin)
      .collect()
      .map { r => watched.map(c => c -> Option(r.getAs[Any](c)).map(_.toString).getOrElse("<NULL>")).toMap }
      .headOption
      .getOrElse(fail(s"staging 无行（snapshot=$snapshot dt=$Dt）"))

  private def dwsRow: Map[String, String] =
    spark.sql(
      s"""SELECT order_count, buyer_count, sale_amount, refund_amount, net_sale_amount, avg_order_value
         |FROM ${ns.dws}.dws_trade_day WHERE dt = '$Dt'""".stripMargin)
      .collect()
      .map { r =>
        Seq("order_count", "buyer_count", "sale_amount", "refund_amount", "net_sale_amount", "avg_order_value")
          .map(c => c -> Option(r.getAs[Any](c)).map(_.toString).getOrElse("<NULL>")).toMap
      }
      .head

  /** 真跑 DWD → DWS → ADS（ads_sale_trend + ads_operation_overview，用于跨表对账） */
  private def run(): Unit = {
    writeOrderDetail()
    writeUserBehavior()
    spark.sql(com.graduation.analytics.sql.DwsSql.tradeDay(ns, Dt))
    spark.sql(AdsSql.saleTrend(ns, Dt, Some(Snap)))
    spark.sql(AdsSql.operationOverview(ns, Dt, Some(Snap)))
  }

  // ---------------------------------------------------------------- 用例

  "ads_sale_trend 的净销售额（§9.3 L333 / §11.2 L428）" should
    "落到 ADS 并与上游 DWS 同值（退款只计已支付订单：部分/全额退款都扣，未支付行的退款不扣）" in {
    run()

    val ads = trendRow(Snap)
    val dws = dwsRow
    withClue(s"ADS=$ads；DWS=$dws；") {
      // 订单 3（101/102/103）、买家 2、销售额 110+60+40=210、退款 20+40=60、净额 150、客单价 210/3=70
      dws("order_count") should be("3")
      dws("buyer_count") should be("2")
      dws("sale_amount") should be("210.00")
      dws("refund_amount") should be("60.00")
      dws("net_sale_amount") should be("150.00")
      dws("avg_order_value") should be("70.00")
      ads should be(Map(
        "order_count" -> "3", "buyer_count" -> "2", "sale_amount" -> "210.00",
        "avg_order_value" -> "70.00", "net_sale_amount" -> "150.00"))
    }
  }

  "净销售额口径" should
    "等于销售额 − 已支付订单退款额，且 ADS 与上游 dws_trade_day 逐列一致（血缘，不是另算一套）" in {
    run()

    val diff = spark.sql(
      s"""SELECT
         |  SUM(CASE WHEN s.net_sale_amount IS NULL OR d.net_sale_amount IS NULL
         |            OR s.net_sale_amount <> d.net_sale_amount
         |            OR s.sale_amount <> d.sale_amount
         |            OR s.order_count <> d.order_count
         |            OR s.buyer_count <> d.buyer_count THEN 1 ELSE 0 END),
         |  SUM(CASE WHEN d.net_sale_amount <> d.sale_amount - d.refund_amount THEN 1 ELSE 0 END)
         |FROM $staging s JOIN ${ns.dws}.dws_trade_day d ON d.dt = s.dt
         |WHERE s.snapshot_id = '$Snap' AND s.dt = '$Dt'""".stripMargin).head()
    withClue(s"与上游/口径不一致的行数=${diff.getLong(0)}；DWS 内部 sale-refund≠net 的行数=${diff.getLong(1)}；") {
      diff.getLong(0) should be(0L)
      diff.getLong(1) should be(0L)
    }
  }

  "销售趋势表与运营大盘表" should
    "在同一 dt 上给出相同的销售额/订单数/客单价/净销售额（两张 ADS 不得出现两种收入口径）" in {
    run()

    val overview = spark.sql(
      s"""SELECT order_count, sale_amount, avg_order_value, net_sale_amount
         |FROM ${AdsSql.staging(ns, "ads_operation_overview")}
         |WHERE snapshot_id = '$Snap' AND dt = '$Dt'""".stripMargin)
      .collect().head
    val trend = trendRow(Snap)
    withClue(s"大盘=$overview；趋势=$trend；") {
      trend("order_count") should be(overview.getAs[Any]("order_count").toString)
      trend("sale_amount") should be(overview.getAs[Any]("sale_amount").toString)
      trend("avg_order_value") should be(overview.getAs[Any]("avg_order_value").toString)
      trend("net_sale_amount") should be(overview.getAs[Any]("net_sale_amount").toString)
      overview.getAs[Any]("net_sale_amount").toString should be("150.00") // 大盘净额本身也被本夹具钉住
    }
  }

  "ADS 销售趋势表结构" should
    "正式/暂存 DDL 的列与列序都与 MetricAdsSpec 一致（插入按位置写，列序漂移即写错列）" in {
    val formalCols = spark.table(AdsSql.formal(ns, "ads_sale_trend")).columns.toSeq
    withClue(s"正式表列=$formalCols；spec 列=${spec.columns}；") {
      formalCols should be(spec.columns :+ "dt")
    }

    val stagingCols = spark.table(staging).columns.toSeq
    withClue(s"暂存表列=$stagingCols；spec 列=${spec.columns}；") {
      stagingCols.take(spec.columns.size) should be(spec.columns)
      stagingCols.drop(spec.columns.size).toSet should be(Set("snapshot_id", "dt"))
    }
  }

  "发布前关键列规则（AdsQualityJob 规则 3）" should
    "覆盖全部 8 张暂存表，且真的能把净销售额为 NULL 的暂存行判为不合格" in {
    run()

    val predicates = AdsQualityJob.keyPredicates(ns)
    withClue(s"规则覆盖的暂存表=${predicates.keySet}；暂存表清单=${FunnelAdsJob.stagingTables(ns)}；") {
      predicates.keySet should be(FunnelAdsJob.stagingTables(ns).toSet)
    }

    // 构造行：净销售额显式为 NULL（其余关键列合法）⇒ 规则必须命中 1 行。
    // 用独立快照号，避免污染主快照断言。
    spark.sql(
      s"""INSERT INTO $staging PARTITION(snapshot_id = '$SnapNullProbe', dt = '$Dt')
         |SELECT 1L, 1L, CAST(1.00 AS DECIMAL(18,2)), CAST(1.00 AS DECIMAL(18,2)),
         |       CAST(NULL AS DECIMAL(18,2))""".stripMargin)
    val pred = predicates(staging)
    val bad = spark.sql(
      s"""SELECT COUNT(*) FROM $staging
         |WHERE snapshot_id = '$SnapNullProbe' AND dt = '$Dt' AND ($pred)""".stripMargin).head().getLong(0)
    withClue(s"关键列规则 `$pred` 对 1 行 NULL 净额的判定行数=$bad；") { bad should be(1L) }

    // 同一规则对合法行必须判 0（避免规则写法恒真造成的假阳性）
    val clean = spark.sql(
      s"""SELECT COUNT(*) FROM $staging
         |WHERE snapshot_id = '$Snap' AND dt = '$Dt' AND ($pred)""".stripMargin).head().getLong(0)
    withClue(s"关键列规则对合法暂存行的误判行数=$clean；") { clean should be(0L) }
  }

  // ── 结构钉住：行为用例的判别力来自 SQL 真的选了净额列；若未来编辑移除，
  //    行为用例可能因列缺失直接解析失败（好）或改由别处兜底（坏）⇒ 同时钉住 SQL 文本。
  "AdsSql.saleTrend" should
    "在生成 SQL 文本中显式选择净销售额，且不再只投影四列" in {
    val sql = AdsSql.saleTrend(ns, Dt, Some("S_PIN")).toLowerCase
    withClue(s"生成 SQL 缺少净额投影：$sql") { sql should include("net_sale_amount") }
    withClue(s"生成 SQL 仍只投影四列（净额未补）：$sql") {
      sql should not include "select order_count, buyer_count, sale_amount, avg_order_value\n"
    }
  }
}
