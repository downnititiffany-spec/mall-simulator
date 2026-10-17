package com.graduation.analytics

import com.graduation.analytics.job.LocalSchemaInitJob
import com.graduation.analytics.sql.AdsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * WP-F9b / F9-GAP-06 渠道流量汇总表行为测试：
 *  - 渠道流量汇总（行为侧）：pv, view_users, intent_users, cart_users, intent_rate, cart_rate；
 *  - 空/NULL 渠道归一化为 'UNKNOWN'；
 *  - 同一用户跨渠道分别独立计数（各渠道内部独立去重）；
 *  - 分母 view_users = 0 时，转化率必须为 NULL（不得记 0）；
 *  - 交易侧渠道归因严格不出（无 order_uv / pay_uv 字段）；
 *  - 暂存（__staging）与正式写入双重验证。
 */
class AdsChannelSummarySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val Snap = "SNAP_CHANNEL_TEST"
  private val Dt = "20260901"
  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-channel-summary")
    ns = WarehouseNamespace.of("dw_channel")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }
    seedData()
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  /**
   * 构造各渠道行为明细数据：
   *  - APP:
   *      User 1: view (2次), cart_add (1次)
   *      User 2: view (1次)
   *      => APP: pv=3 (view事件总数), view_users=2, intent_users=1, cart_users=1, intent_rate=0.5000, cart_rate=0.5000
   *  - H5:
   *      User 1 (跨渠道独立计数): view (1次)
   *      User 3: cart_add (1次)
   *      => H5: pv=1, view_users=1, intent_users=1, cart_users=1, intent_rate=1.0000, cart_rate=1.0000
   *  - PC:
   *      User 4: cart_add 仅加购无浏览 (1次)
   *      => PC: pv=0, view_users=0, intent_users=1, cart_users=1, intent_rate=NULL, cart_rate=NULL
   *  - NULL / blank 渠道:
   *      User 5: channel=NULL, view (1次)
   *      User 6: channel='   ', view (1次)
   *      => UNKNOWN: pv=2, view_users=2, intent_users=0, cart_users=0, intent_rate=0.0000, cart_rate=0.0000
   */
  private def seedData(): Unit = {
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '$Dt')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  -- APP
         |  SELECT 'b_app_1_v1' AS behavior_id, 1L AS user_id, 10L AS product_id, 100L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '$Dt' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 10L AS product_key, 100L AS category_key
         |  UNION ALL
         |  SELECT 'b_app_1_v2' AS behavior_id, 1L AS user_id, 11L AS product_id, 100L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '$Dt' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 11L AS product_key, 100L AS category_key
         |  UNION ALL
         |  SELECT 'b_app_1_c' AS behavior_id, 1L AS user_id, 10L AS product_id, 100L AS category_id,
         |         'cart_add' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '$Dt' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 10L AS product_key, 100L AS category_key
         |  UNION ALL
         |  SELECT 'b_app_2_v' AS behavior_id, 2L AS user_id, 20L AS product_id, 100L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '$Dt' AS event_date,
         |         11 AS event_hour, 'tier_2' AS city_level, 'APP' AS channel, 's2' AS session_id,
         |         1L AS source_batch_id, 2L AS user_key, 20L AS product_key, 100L AS category_key
         |  -- H5
         |  UNION ALL
         |  SELECT 'b_h5_1_v' AS behavior_id, 1L AS user_id, 30L AS product_id, 200L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '$Dt' AS event_date,
         |         12 AS event_hour, 'tier_1' AS city_level, 'H5' AS channel, 's3' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 30L AS product_key, 200L AS category_key
         |  UNION ALL
         |  SELECT 'b_h5_3_c' AS behavior_id, 3L AS user_id, 30L AS product_id, 200L AS category_id,
         |         'cart_add' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '$Dt' AS event_date,
         |         12 AS event_hour, 'tier_3' AS city_level, 'H5' AS channel, 's4' AS session_id,
         |         1L AS source_batch_id, 3L AS user_key, 30L AS product_key, 200L AS category_key
         |  -- PC
         |  UNION ALL
         |  SELECT 'b_pc_4_c' AS behavior_id, 4L AS user_id, 40L AS product_id, 300L AS category_id,
         |         'cart_add' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '$Dt' AS event_date,
         |         13 AS event_hour, 'tier_1' AS city_level, 'PC' AS channel, 's5' AS session_id,
         |         1L AS source_batch_id, 4L AS user_key, 40L AS product_key, 300L AS category_key
         |  -- NULL / BLANK channel
         |  UNION ALL
         |  SELECT 'b_null_5_v' AS behavior_id, 5L AS user_id, 50L AS product_id, 400L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '$Dt' AS event_date,
         |         14 AS event_hour, 'tier_2' AS city_level, CAST(NULL AS STRING) AS channel, 's6' AS session_id,
         |         1L AS source_batch_id, 5L AS user_key, 50L AS product_key, 400L AS category_key
         |  UNION ALL
         |  SELECT 'b_blank_6_v' AS behavior_id, 6L AS user_id, 60L AS product_id, 400L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '$Dt' AS event_date,
         |         15 AS event_hour, 'tier_2' AS city_level, '   ' AS channel, 's7' AS session_id,
         |         1L AS source_batch_id, 6L AS user_key, 60L AS product_key, 400L AS category_key
         |) v""".stripMargin)
  }

  "AdsSql.channelSummary" should "正确计算渠道PV、去重用户及转化率，并将空/NULL渠道归一化为UNKNOWN" in {
    spark.sql(AdsSql.channelSummary(ns, Dt, Some(Snap)))

    val rows = spark.sql(
      s"""SELECT channel, pv, view_users, intent_users, cart_users, intent_rate, cart_rate
         |FROM ${ns.ads}.ads_channel_summary__staging
         |WHERE snapshot_id = '$Snap' AND dt = '$Dt'
         |ORDER BY channel""".stripMargin).collect()

    rows.length should be(4)
    val map = rows.map(r => r.getAs[String]("channel") -> r).toMap

    // APP (User 1 has 2 views, User 2 has 1 view => pv = 3, view_users = 2)
    val app = map("APP")
    app.getAs[Long]("pv") should be(3L)
    app.getAs[Long]("view_users") should be(2L)
    app.getAs[Long]("intent_users") should be(1L)
    app.getAs[Long]("cart_users") should be(1L)
    app.getAs[java.math.BigDecimal]("intent_rate") should be(new java.math.BigDecimal("0.5000"))
    app.getAs[java.math.BigDecimal]("cart_rate") should be(new java.math.BigDecimal("0.5000"))

    // H5 (User 1 also appears here, proving independent counting across channels)
    val h5 = map("H5")
    h5.getAs[Long]("pv") should be(1L)
    h5.getAs[Long]("view_users") should be(1L)
    h5.getAs[Long]("intent_users") should be(1L)
    h5.getAs[Long]("cart_users") should be(1L)
    h5.getAs[java.math.BigDecimal]("intent_rate") should be(new java.math.BigDecimal("1.0000"))
    h5.getAs[java.math.BigDecimal]("cart_rate") should be(new java.math.BigDecimal("1.0000"))

    // PC (view_users = 0 => conversion rates must be NULL, not 0)
    val pc = map("PC")
    pc.getAs[Long]("pv") should be(0L)
    pc.getAs[Long]("view_users") should be(0L)
    pc.getAs[Long]("intent_users") should be(1L)
    pc.getAs[Long]("cart_users") should be(1L)
    pc.isNullAt(pc.fieldIndex("intent_rate")) should be(true)
    pc.isNullAt(pc.fieldIndex("cart_rate")) should be(true)

    // UNKNOWN (NULL and '   ' normalized together)
    val unk = map("UNKNOWN")
    unk.getAs[Long]("pv") should be(2L)
    unk.getAs[Long]("view_users") should be(2L)
    unk.getAs[Long]("intent_users") should be(0L)
    unk.getAs[Long]("cart_users") should be(0L)
    unk.getAs[java.math.BigDecimal]("intent_rate") should be(new java.math.BigDecimal("0.0000"))
    unk.getAs[java.math.BigDecimal]("cart_rate") should be(new java.math.BigDecimal("0.0000"))
  }

  it should "正式表写入与暂存表同结构（不带 snapshot_id 分区）" in {
    spark.sql(AdsSql.channelSummary(ns, Dt, None))

    val rows = spark.sql(
      s"""SELECT channel, pv, view_users, intent_users, cart_users, intent_rate, cart_rate
         |FROM ${ns.ads}.ads_channel_summary
         |WHERE dt = '$Dt'
         |ORDER BY channel""".stripMargin).collect()

    rows.length should be(4)
    val app = rows.find(_.getAs[String]("channel") == "APP").get
    app.getAs[Long]("pv") should be(3L)
    app.getAs[Long]("view_users") should be(2L)
    app.getAs[Long]("intent_users") should be(1L)
    app.getAs[Long]("cart_users") should be(1L)
  }

  it should "无数据的日期不产出行" in {
    spark.sql(AdsSql.channelSummary(ns, "20260999", Some(Snap)))

    val rows = spark.sql(
      s"""SELECT * FROM ${ns.ads}.ads_channel_summary__staging
         |WHERE snapshot_id = '$Snap' AND dt = '20260999'""".stripMargin).collect()

    rows should be(empty)
  }
}
