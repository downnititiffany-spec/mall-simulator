package com.graduation.analytics

import com.graduation.analytics.job.LocalSchemaInitJob
import com.graduation.analytics.sql.DwsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S3-12 `dws_region_sale_day` 补 **净销售额** `net_sale_amount`：
 *  - 设计 §12.1 **L319**：`dws_region_sale_day` ｜ source+region+dt，sale/net/order/buyer（四列）｜
 *    「未匹配地区保留 `unknown`」；
 *  - 设计 §12.1 **L455**：「分类/地区金额求和必须**包含** unknown」（否则地区合计小于大盘且无人报错）；
 *  - 指导书 §7 阶段3 ①「每指标固定粒度/分子分母/时间窗口/金额退款口径/空值规则/版本」、② 「逐层对账」；
 *  - 判类依据（同类先例）：S3-02 给 `ads_sale_trend` **末尾追加** `net_sale_amount`，经总控判 **A 类**。
 *
 * 改动前该表只有 `region/buyer_count/order_count/sale_amount`：地区维度只能看毛销售额，
 * 与同层 `dws_trade_day`（已有 `net_sale_amount`）**口径不对称** ⇒ 地区专题无法回答"扣掉退款后还剩多少"。
 *
 * 本 spec 的判定方式（不读 SQL 文本下结论）：
 *  ① 真跑 `DWD → dws_region_sale_day`（同批跑 `dws_trade_day` 作对账基准），逐地区断言净额；
 *  ② **退款口径判别力**：夹具含「部分退款」「全额退款」「**未支付却带退款金额**」「`refund_amount` 为 NULL」四种行 ——
 *     若实现直接用 `SUM(refund_amount)` 而不限 `final_paid_flag = 1`，净额合计会从 `190.00` 变成 `-310.00`；
 *  ③ **跨层对账**：`Σ各地区 net = dws_trade_day.net`（同 dt、同输入）⇒ 地区净额不是"另算一套"；
 *     并断言**排除 unknown 就不等**（设计 L455 的要求非空转）；
 *  ④ **运行期表形**：写完后 `spark.table(...).schema.fieldNames` 必须含 `net_sale_amount`（静态三方守卫在
 *     `DwsSchemaOwnerSpec`，此处钉的是**运行期**真实表形）；
 *  ⑤ **空值规则**：`refund_amount` 全为 NULL 的地区净额 = 销售额（`COALESCE(SUM(...), 0)`），不得为 NULL。
 *
 * 只读夹具与隔离仓库由 `P2TestSupport.spark` 提供，绝不触碰在产 `spark-warehouse`。
 */
class DwsRegionNetSaleSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  /** 业务日（分区 dt，yyyyMMdd） */
  private val Dt = "20260901"

  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("dws-region-net-sale")
    ns = WarehouseNamespace.of("dw_regionnet")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  /**
   * 订单明细（`dwd_order_detail`，一单一行）：
   *
   * | 订单 | 用户 | city_level | amount | paid | refund | 计入 | 期望地区净额 |
   * |------|------|-----------|--------|------|--------|------|--------------|
   * | 201  | 1    | 一线      | 100.00 | 1    | 10.00  | 是（部分退款） | 一线 |
   * | 202  | 2    | 一线      | 50.00  | 1    | 0.00   | 是 | 一线 |
   * | 203  | 3    | 二线      | 80.00  | 1    | 80.00  | 是（全额退款） | 二线 |
   * | 204  | 4    | NULL      | 30.00  | 1    | 0.00   | 是 ⇒ `unknown` | unknown |
   * | 205  | 5    | 三线      | 999.00 | **0** | 500.00 | **否**（未支付，整行不计；`三线` 不得出现） |
   * | 206  | 6    | 四线      | 20.00  | 1    | NULL   | 是（退款 NULL ⇒ 视作 0） | 四线 |
   *
   * 期望：一线 `sale=150.00 net=140.00`、二线 `80.00 / 0.00`、四线 `20.00 / 20.00`、
   * unknown `30.00 / 30.00`；合计 `sale=280.00 net=190.00`（与 `dws_trade_day` 同 dt 相等）。
   * 判别探针：若不限 `final_paid_flag = 1` 就累计退款，退款从 `90.00` 变 `590.00`、净额变 `-310.00`。
   */
  private def writeOrderDetail(): Unit = {
    val row = (oid: Long, uid: Long, city: String, amt: String, paid: Int, refund: String) =>
      s"""SELECT ${oid}L AS order_id, ${uid}L AS user_id, 1L AS product_id, 1L AS category_id,
         |       1 AS quantity, CAST($amt AS DECIMAL(18,2)) AS unit_price,
         |       CAST(0.00 AS DECIMAL(18,2)) AS discount, CAST($amt AS DECIMAL(18,2)) AS amount,
         |       'PAID' AS order_status, CAST(NULL AS TIMESTAMP) AS order_time, '$Dt' AS order_date,
         |       CAST($city AS STRING) AS city_level, CAST(NULL AS TIMESTAMP) AS paid_at,
         |       CAST($amt AS DECIMAL(18,2)) AS order_amount, CAST($amt AS DECIMAL(18,2)) AS paid_amount,
         |       CAST($refund AS DECIMAL(18,2)) AS refund_amount,
         |       CAST($amt AS DECIMAL(18,2)) AS net_paid_amount,
         |       $paid AS final_paid_flag, 0 AS final_refunded_flag,
         |       CAST(NULL AS BIGINT) AS user_key, CAST(NULL AS BIGINT) AS product_key,
         |       CAST(NULL AS BIGINT) AS category_key""".stripMargin
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_order_detail PARTITION(dt = '$Dt')
         |SELECT order_id, user_id, product_id, category_id, quantity, unit_price, discount, amount,
         |       order_status, order_time, order_date, city_level, paid_at, order_amount, paid_amount,
         |       refund_amount, net_paid_amount, final_paid_flag, final_refunded_flag,
         |       user_key, product_key, category_key FROM (
         |  ${row(201L, 1L, "'一线'", "100.00", 1, "10.00")}
         |  UNION ALL ${row(202L, 2L, "'一线'", "50.00", 1, "0.00")}
         |  UNION ALL ${row(203L, 3L, "'二线'", "80.00", 1, "80.00")}
         |  UNION ALL ${row(204L, 4L, "NULL", "30.00", 1, "0.00")}
         |  UNION ALL ${row(205L, 5L, "'三线'", "999.00", 0, "500.00")}
         |  UNION ALL ${row(206L, 6L, "'四线'", "20.00", 1, "NULL")}
         |) v""".stripMargin)
  }

  private val watched = Seq("region", "buyer_count", "order_count", "sale_amount", "net_sale_amount")

  /** 地区行按 `region` 建索引（**不按排序**：中文排序不可依赖） */
  private def regionRows: Map[String, Map[String, String]] =
    spark.sql(s"SELECT ${watched.mkString(", ")} FROM ${ns.dws}.dws_region_sale_day WHERE dt = '$Dt'")
      .collect()
      .map { r =>
        val cells = watched.map(c => c -> Option(r.getAs[Any](c)).map(_.toString).getOrElse("<NULL>")).toMap
        cells("region") -> cells
      }
      .toMap

  private def tradeRow: Map[String, String] = {
    val cols = Seq("order_count", "buyer_count", "sale_amount", "refund_amount", "net_sale_amount")
    spark.sql(s"SELECT ${cols.mkString(", ")} FROM ${ns.dws}.dws_trade_day WHERE dt = '$Dt'")
      .collect()
      .map(r => cols.map(c => c -> Option(r.getAs[Any](c)).map(_.toString).getOrElse("<NULL>")).toMap)
      .headOption
      .getOrElse(fail(s"dws_trade_day 无行（dt=$Dt）"))
  }

  /** 真跑 DWD 夹具 → dws_region_sale_day（同批 `dws_trade_day` 作对账基准） */
  private def run(): Unit = {
    writeOrderDetail()
    spark.sql(DwsSql.regionSaleDay(ns, Dt))
    spark.sql(DwsSql.tradeDay(ns, Dt))
  }

  // ---------------------------------------------------------------- 用例

  "dws_region_sale_day 的净销售额（§12.1 L319「sale/net/order/buyer」）" should
    "逐地区落到 DWS：net = 销售 − 已支付订单退款（部分/全额都扣，未支付行整行不计）" in {
    run()

    val rows = regionRows
    withClue(s"实测地区行=$rows；") {
      rows.keySet should be(Set("一线", "二线", "四线", "unknown"))
      rows("一线") should be(Map(
        "region" -> "一线", "buyer_count" -> "2", "order_count" -> "2",
        "sale_amount" -> "150.00", "net_sale_amount" -> "140.00"))
      rows("二线") should be(Map(
        "region" -> "二线", "buyer_count" -> "1", "order_count" -> "1",
        "sale_amount" -> "80.00", "net_sale_amount" -> "0.00"))
      rows("四线") should be(Map(
        "region" -> "四线", "buyer_count" -> "1", "order_count" -> "1",
        "sale_amount" -> "20.00", "net_sale_amount" -> "20.00"))
      rows("unknown") should be(Map(
        "region" -> "unknown", "buyer_count" -> "1", "order_count" -> "1",
        "sale_amount" -> "30.00", "net_sale_amount" -> "30.00"))
      // 未支付订单（205，三线）整行不计 ⇒ 该地区连行都不该有（不是"净额为 0 的行"）
      rows.contains("三线") should be(false)
    }
  }

  it should "跨层对账：各地区销售/净额求和（**含 unknown**）= dws_trade_day 同 dt 值（设计 L455）" in {
    run()

    val sums = spark.sql(
      s"""SELECT COALESCE(SUM(sale_amount), 0), COALESCE(SUM(net_sale_amount), 0),
         |       COALESCE(SUM(CASE WHEN region <> 'unknown' THEN sale_amount ELSE 0 END), 0),
         |       COALESCE(SUM(CASE WHEN region <> 'unknown' THEN net_sale_amount ELSE 0 END), 0)
         |FROM ${ns.dws}.dws_region_sale_day WHERE dt = '$Dt'""".stripMargin).head()
    val trade = tradeRow
    val (allSale, allNet, noUnknownSale) =
      (sums.getDecimal(0).toPlainString, sums.getDecimal(1).toPlainString, sums.getDecimal(2).toPlainString)
    withClue(s"地区合计 sale=$allSale net=$allNet（排除 unknown sale=$noUnknownSale）；trade=$trade；") {
      allSale should be(trade("sale_amount"))
      allNet should be(trade("net_sale_amount"))
      // 「必须包含 unknown」非空转：本夹具 unknown 的销售额是 30.00 ⇒ 排除它就与大盘不等
      noUnknownSale should be("250.00")
      noUnknownSale should not be trade("sale_amount")
    }
  }

  it should "空值规则：退款额全为 NULL 的地区净额 = 销售额（COALESCE 0），不得为 NULL 或负数" in {
    run()

    val probe = spark.sql(
      s"""SELECT COUNT(*) AS total,
         |       SUM(CASE WHEN net_sale_amount IS NULL THEN 1 ELSE 0 END) AS nullNet,
         |       SUM(CASE WHEN net_sale_amount < 0 THEN 1 ELSE 0 END) AS negativeNet,
         |       MAX(CASE WHEN region = '四线' THEN net_sale_amount END) AS sishi
         |FROM ${ns.dws}.dws_region_sale_day WHERE dt = '$Dt'""".stripMargin).head()
    withClue(s"总行=${probe.getLong(0)} 净额为 NULL 行=${probe.getLong(1)} 净额为负行=${probe.getLong(2)} " +
      s"四线净额=${probe.getDecimal(3)}；") {
      probe.getLong(0) should be(4L)
      probe.getLong(1) should be(0L)
      probe.getLong(2) should be(0L)
      probe.getDecimal(3).toPlainString should be("20.00") // 206 的 refund_amount = NULL ⇒ 视作 0
    }
  }

  it should "运行期表形：写完后该表的列名/列序含 net_sale_amount（静态三方守卫见 DwsSchemaOwnerSpec）" in {
    run()

    // 分区列 `dt` 由 Spark 追加在数据列之后（`fieldNames` 含分区列），故期望值里显式带上
    val fields = spark.table(s"${ns.dws}.dws_region_sale_day").schema.fieldNames.toSeq
    withClue(s"运行期列=$fields；") {
      fields should be(Seq("region", "buyer_count", "order_count", "sale_amount", "net_sale_amount", "dt"))
    }
  }
}
