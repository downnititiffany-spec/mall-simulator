package com.graduation.analytics

import com.graduation.analytics.job.LocalSchemaInitJob
import com.graduation.analytics.sql.AdsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * WP-F9a / F9-GAP-03 用户留存率行为测试：
 *  - 队列定义：对 dwd_user_behavior_detail 中出现的每个 dt，cohort(dt) = 当日有任一有效行为的去重用户集；
 *  - 留存定义：retained_n(dt) = cohort(dt) 中在 dt+n 日仍有任一有效行为的去重用户数，n ∈ {1, 7, 30}；
 *    retention_n = retained_n / |cohort(dt)|；
 *  - 可观测性：当 dt+n 超出数据内最大 dt 时，该 n 的 retained/retention 必须为 NULL（未可观测），不得记 0；
 *    |cohort(dt)| = 0 的 dt 不出行；
 *  - 判别力覆盖：
 *    ① 跨日回访（有留存用户，计数与比例精确断言）；
 *    ② 仅首日用户（次日未回访，留存为 0 而非 NULL，分母保留）；
 *    ③ Horizon 外 NULL（dt+n > max(dt) 时为 NULL 而非 0）；
 *    ④ |cohort(dt)| = 0 时不产出行。
 */
class AdsUserRetentionSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val Snap = "SNAP_RETENTION_TEST"
  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-user-retention")
    ns = WarehouseNamespace.of("dw_retention")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }
    seedData()
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  /**
   * 构造多日行为明细数据：
   *  - 20260831 (Day 0): User 999 唯一活跃（次日未回访 ⇒ D+1 留存 0，在 horizon 内，断言 0L 非 NULL）
   *  - 20260901 (Day 1): User 101（次日回访）与 User 102（仅首日活跃）⇒ cohort=2, retained_d1=1, retention_d1=0.5000
   *  - 20260902 (Day 2): User 101（回访）与 User 103（新活跃，次日回访）
   *  - 20260903 (Day 3, max_dt): User 103（回访）与 User 104（新活跃）⇒ D+1 超出 max_dt，断言 NULL
   */
  private def seedData(): Unit = {
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '20260831')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  SELECT 'b_999_0' AS behavior_id, 999L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260831' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's0' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |) v""".stripMargin)

    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '20260901')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  SELECT 'b_101_1' AS behavior_id, 101L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260901' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |  UNION ALL
         |  SELECT 'b_102_1' AS behavior_id, 102L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260901' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |) v""".stripMargin)

    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '20260902')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  SELECT 'b_101_2' AS behavior_id, 101L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260902' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |  UNION ALL
         |  SELECT 'b_103_2' AS behavior_id, 103L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260902' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |) v""".stripMargin)

    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '20260903')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  SELECT 'b_103_3' AS behavior_id, 103L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260903' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |  UNION ALL
         |  SELECT 'b_104_3' AS behavior_id, 104L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260903' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |) v""".stripMargin)
  }

  "AdsSql.userRetention" should "正确计算跨日回访队列留存，horizon 外严格为 NULL" in {
    spark.sql(AdsSql.userRetention(ns, "20260901", Some(Snap)))

    val rows = spark.sql(
      s"""SELECT cohort_user_count, retained_d1, retention_d1, retained_d7, retention_d7, retained_d30, retention_d30
         |FROM ${ns.ads}.ads_user_retention__staging
         |WHERE snapshot_id = '$Snap' AND dt = '20260901'""".stripMargin).collect()

    rows.length should be(1)
    val r = rows.head
    // cohort(20260901) = {101, 102} -> 2 人
    r.getAs[Long]("cohort_user_count") should be(2L)
    // D+1 = 20260902 <= max_dt(20260903)，可观测：101 回访，102 未回访 -> retained_d1 = 1, retention_d1 = 0.5000
    r.getAs[Long]("retained_d1") should be(1L)
    r.getAs[java.math.BigDecimal]("retention_d1") should be(new java.math.BigDecimal("0.5000"))
    // D+7 = 20260908 > max_dt(20260903)，不可观测 -> NULL（不得记 0）
    r.isNullAt(r.fieldIndex("retained_d7")) should be(true)
    r.isNullAt(r.fieldIndex("retention_d7")) should be(true)
    // D+30 = 20261001 > max_dt(20260903)，不可观测 -> NULL（不得记 0）
    r.isNullAt(r.fieldIndex("retained_d30")) should be(true)
    r.isNullAt(r.fieldIndex("retention_d30")) should be(true)
  }

  it should "在 horizon 内若无用户回访，留存记 0 而非 NULL" in {
    spark.sql(AdsSql.userRetention(ns, "20260831", Some(Snap)))

    val rows = spark.sql(
      s"""SELECT cohort_user_count, retained_d1, retention_d1, retained_d7, retention_d7
         |FROM ${ns.ads}.ads_user_retention__staging
         |WHERE snapshot_id = '$Snap' AND dt = '20260831'""".stripMargin).collect()

    rows.length should be(1)
    val r = rows.head
    // cohort(20260831) = {999} -> 1 人
    r.getAs[Long]("cohort_user_count") should be(1L)
    // D+1 = 20260901 <= max_dt(20260903)，在可观测范围内：999 未回访 -> retained_d1 = 0L, retention_d1 = 0.0000
    r.isNullAt(r.fieldIndex("retained_d1")) should be(false)
    r.getAs[Long]("retained_d1") should be(0L)
    r.getAs[java.math.BigDecimal]("retention_d1") should be(new java.math.BigDecimal("0.0000"))
    // D+7 = 20260907 > max_dt(20260903) -> NULL
    r.isNullAt(r.fieldIndex("retained_d7")) should be(true)
    r.isNullAt(r.fieldIndex("retention_d7")) should be(true)
  }

  it should "dt 位于最新业务日时，D+1/D+7/D+30 均超出 horizon，全部置为 NULL" in {
    spark.sql(AdsSql.userRetention(ns, "20260903", Some(Snap)))

    val rows = spark.sql(
      s"""SELECT cohort_user_count, retained_d1, retention_d1, retained_d7, retention_d7, retained_d30, retention_d30
         |FROM ${ns.ads}.ads_user_retention__staging
         |WHERE snapshot_id = '$Snap' AND dt = '20260903'""".stripMargin).collect()

    rows.length should be(1)
    val r = rows.head
    r.getAs[Long]("cohort_user_count") should be(2L)
    // D+1 = 20260904 > max_dt(20260903) -> NULL
    r.isNullAt(r.fieldIndex("retained_d1")) should be(true)
    r.isNullAt(r.fieldIndex("retention_d1")) should be(true)
    // D+7 -> NULL
    r.isNullAt(r.fieldIndex("retained_d7")) should be(true)
    r.isNullAt(r.fieldIndex("retention_d7")) should be(true)
    // D+30 -> NULL
    r.isNullAt(r.fieldIndex("retained_d30")) should be(true)
    r.isNullAt(r.fieldIndex("retention_d30")) should be(true)
  }

  it should "|cohort(dt)| = 0 的日期不产出行" in {
    // 20260904 无任何用户行为记录
    spark.sql(AdsSql.userRetention(ns, "20260904", Some(Snap)))

    val rows = spark.sql(
      s"""SELECT * FROM ${ns.ads}.ads_user_retention__staging
         |WHERE snapshot_id = '$Snap' AND dt = '20260904'""".stripMargin).collect()

    rows should be(empty)
  }

  it should "正式表写入与暂存表同结构（不带 snapshot_id 分区）" in {
    spark.sql(AdsSql.userRetention(ns, "20260901", None))

    val rows = spark.sql(
      s"""SELECT cohort_user_count, retained_d1, retention_d1
         |FROM ${ns.ads}.ads_user_retention
         |WHERE dt = '20260901'""".stripMargin).collect()

    rows.length should be(1)
    rows.head.getAs[Long]("cohort_user_count") should be(2L)
    rows.head.getAs[Long]("retained_d1") should be(1L)
  }
}
