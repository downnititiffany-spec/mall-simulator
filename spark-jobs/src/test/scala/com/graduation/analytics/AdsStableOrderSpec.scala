package com.graduation.analytics

import com.graduation.analytics.job.LocalSchemaInitJob
import com.graduation.analytics.sql.AdsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S2-06.2 ADS 稳定次序键（S2-06 第 2 项）：
 *  - §11.4 L447「NTILE 的同值加**稳定源/用户 ID 排序**保证可复现，但同值用户可能被拆分，
 *    必须展示评分规则」；
 *  - §11.5 L455「商品排行按热度/销量/金额并有**稳定次序键**」；
 *  - §10.1 L375「合法 DAG 的拓扑排序遇同优先级节点使用固定次序，避免重跑顺序漂移」；
 *  - §9.4 L343「以 event_time、ingest_time、event_id 确定**稳定顺序**」同一原则在 ADS 名次上的落点。
 *
 * 判定方式**不是读 SQL 文本**，而是把同一份逻辑输入用两种物理顺序各写一次，断言：
 *  ① 两轮名次/评分完全相同（可复现）；
 *  ② 名次由稳定键（`product_id` / `user_id` 升序）决定。
 * 无稳定键时 `ROW_NUMBER`/`NTILE` 对同值行的分配取决于扫描顺序（本地 `local[1]` 实测会跟随
 * `INSERT` 的落文件顺序）⇒ 两轮结果不同（RED）。名次漂移不是学术问题：TopN 的**入选集合**
 * 会随重跑改变，用户看到的热门商品列表与上游同一份数据却对不上。
 *
 * 只读夹具与隔离仓库由 `P2TestSupport.spark` 提供（`D:/Develop/tmp/p2-01-warehouse/...`），
 * 绝不触碰在产 `spark-warehouse`。
 */
class AdsStableOrderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val Dt = "20260901"
  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-stable-order")
    ns = WarehouseNamespace.of("dw_stable")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  /** 3 个商品**热度完全相同**（pv/fav/cart/buy 全同 ⇒ heat_score 并列）。
    * `ascending=false` 时按 `product_id` 降序落文件：逻辑内容相同、物理顺序相反。 */
  private def writeProductBehavior(ascending: Boolean): Unit = {
    val order = if (ascending) "ASC" else "DESC"
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dws}.dws_product_behavior_day PARTITION(dt = '$Dt')
         |SELECT product_id, category_id, pv, uv, fav, cart, buy FROM (
         |  SELECT 101L AS product_id, 9L AS category_id, 10L AS pv, 5L AS uv,
         |         0L AS fav, 0L AS cart, 0L AS buy
         |  UNION ALL SELECT 102L, 9L, 10L, 5L, 0L, 0L, 0L
         |  UNION ALL SELECT 103L, 9L, 10L, 5L, 0L, 0L, 0L
         |) v ORDER BY product_id $order""".stripMargin)
  }

  /** 5 个用户 R/F/M **原值全同**（同 `last_buy_date`/`order_count`/`sale_amount` ⇒ 三个 NTILE 全并列） */
  private def writeUserTradePeriod(ascending: Boolean): Unit = {
    val order = if (ascending) "ASC" else "DESC"
    val row = (u: Int) =>
      s"""SELECT ${u}L AS user_id, '2026-09-01' AS last_buy_date, 1L AS order_count,
         |       CAST(10.00 AS DECIMAL(18,2)) AS sale_amount,
         |       '$Dt' AS period_start, '$Dt' AS period_end""".stripMargin
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dws}.dws_user_trade_period PARTITION(dt = '$Dt')
         |SELECT user_id, last_buy_date, order_count, sale_amount, period_start, period_end FROM (
         |  ${row(1)}
         |  UNION ALL ${row(2)}
         |  UNION ALL ${row(3)}
         |  UNION ALL ${row(4)}
         |  UNION ALL ${row(5)}
         |) v ORDER BY user_id $order""".stripMargin)
  }

  private def hotRanks(snapshot: String): Map[Long, Int] =
    spark.sql(
      s"""SELECT product_id, rank_no FROM ${AdsSql.staging(ns, "ads_hot_product")}
         |WHERE snapshot_id = '$snapshot' AND dt = '$Dt'""".stripMargin)
      .collect().map(r => r.getLong(0) -> r.getInt(1)).toMap

  private def profileScores(snapshot: String): Map[Long, (Int, Int, Int)] =
    spark.sql(
      s"""SELECT user_id, r, f, m FROM ${AdsSql.staging(ns, "ads_user_profile")}
         |WHERE snapshot_id = '$snapshot' AND dt = '$Dt'""".stripMargin)
      .collect().map(r => r.getLong(0) -> ((r.getInt(1), r.getInt(2), r.getInt(3)))).toMap

  // ---------------------------------------------------------------- 用例

  "ads_hot_product.rank_no（§11.5 稳定次序键）" should
    "同热度时按 product_id 升序，且名次与输入物理顺序无关" in {
    writeProductBehavior(ascending = true)
    spark.sql(AdsSql.hotProduct(ns, Dt, 10, Some("S_ASC")))
    val ascRanks = hotRanks("S_ASC")

    writeProductBehavior(ascending = false)
    spark.sql(AdsSql.hotProduct(ns, Dt, 10, Some("S_DESC")))
    val descRanks = hotRanks("S_DESC")

    withClue(s"同热度名次必须由稳定键决定：升序落盘=$ascRanks 降序落盘=$descRanks；") {
      ascRanks should be(Map(101L -> 1, 102L -> 2, 103L -> 3))
      descRanks should be(ascRanks)
    }
  }

  "ads_hot_product 的 TopN 截断（§11.5 排行稳定）" should
    "同热度时仍取 product_id 最小的 N 个，不随扫描顺序漂移" in {
    writeProductBehavior(ascending = true)
    spark.sql(AdsSql.hotProduct(ns, Dt, 2, Some("S_ASC2")))
    val ascTop = hotRanks("S_ASC2").keySet

    writeProductBehavior(ascending = false)
    spark.sql(AdsSql.hotProduct(ns, Dt, 2, Some("S_DESC2")))
    val descTop = hotRanks("S_DESC2").keySet

    withClue(s"TopN 入选集合必须稳定：升序落盘=$ascTop 降序落盘=$descTop；") {
      ascTop should be(Set(101L, 102L))
      descTop should be(ascTop)
    }
  }

  "ads_user_profile 的 R/F/M 五分位（§11.4 NTILE 稳定排序）" should
    "同值用户按 user_id 升序入桶，且评分与输入物理顺序无关" in {
    writeUserTradePeriod(ascending = true)
    spark.sql(AdsSql.userProfile(ns, Dt, Dt, Dt, Some("S_ASC")))
    val ascScores = profileScores("S_ASC")

    writeUserTradePeriod(ascending = false)
    spark.sql(AdsSql.userProfile(ns, Dt, Dt, Dt, Some("S_DESC")))
    val descScores = profileScores("S_DESC")

    withClue(s"五分位必须可复现：升序落盘=$ascScores 降序落盘=$descScores；") {
      // 原值全同时，NTILE(5) 按稳定键 user_id 升序切 5 桶；R 反向（r = 6 - r_ntile）
      ascScores should be(Map(1L -> ((5, 1, 1)), 2L -> ((4, 2, 2)), 3L -> ((3, 3, 3)),
        4L -> ((2, 4, 4)), 5L -> ((1, 5, 5))))
      descScores should be(ascScores)
    }
  }

  // ── 结构钉住：行为用例的判别力依赖"引擎按物理顺序取并列值"这一实现细节，
  //    若未来引擎/版本改变该行为，行为用例可能静默失去判别力 ⇒ 同时钉住 SQL 文本本身。
  "AdsSql 的稳定次序键" should "在生成 SQL 文本中显式出现（防未来编辑移除平局裁决）" in {
    val hot = AdsSql.hotProduct(ns, Dt, 50, Some("S_PIN")).toLowerCase
    val prof = AdsSql.userProfile(ns, Dt, Dt, Dt, Some("S_PIN")).toLowerCase

    withClue("ads_hot_product 的 rank_no 缺 product_id 平局键：") {
      hot should include("product_id asc) as rank_no")
    }
    withClue("ads_user_profile 的 r_ntile 缺 user_id 平局键：") {
      prof should include("last_buy_date) asc, user_id asc) as r_ntile")
    }
    withClue("ads_user_profile 的 f_ntile 缺 user_id 平局键：") {
      prof should include("order_count asc, user_id asc) as f_ntile")
    }
    withClue("ads_user_profile 的 m_ntile 缺 user_id 平局键：") {
      prof should include("sale_amount asc, user_id asc) as m_ntile")
    }
  }
}
