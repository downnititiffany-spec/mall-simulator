package com.graduation.analytics

import com.graduation.analytics.job.{AdsQualityJob, LocalSchemaInitJob}
import com.graduation.analytics.sql.{AdsSql, DwsSql}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Small behavior test for the two N31-02 leg-C sales dimensions. */
class CategoryRegionAdsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val dt = "20260901"
  private val snapshot = "S_N3102_C"
  private val badSnapshot = "S_N3102_C_BAD"
  private val ns = WarehouseNamespace.of("dw_n3102_c")
  private var spark: SparkSession = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    spark = P2TestSupport.spark("n3102-category-region-ads")
    LocalSchemaInitJob.statements(ns).foreach { case (_, statement) => spark.sql(statement) }
    insertOrders()
    insertProducts()
  }

  override protected def afterAll(): Unit = {
    P2TestSupport.stop(spark)
    super.afterAll()
  }

  private def insertOrders(): Unit = {
    val table = s"${ns.dwd}.dwd_order_detail"
    spark.sql(
      s"""INSERT INTO TABLE $table PARTITION (dt = '$dt') VALUES
         |(101, 11, 1001, 10, 2, CAST(10.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(20.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-01 09:00:00' AS TIMESTAMP), '2026-09-01', 'L1', CAST('2026-09-01 09:05:00' AS TIMESTAMP), CAST(20.00 AS DECIMAL(18,2)), CAST(20.00 AS DECIMAL(18,2)), CAST(2.00 AS DECIMAL(18,2)), CAST(18.00 AS DECIMAL(18,2)), 1, 0, 11, 1001, 10),
         |(102, 12, 2001, 20, 1, CAST(30.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(30.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-01 10:00:00' AS TIMESTAMP), '2026-09-01', 'L1', CAST('2026-09-01 10:05:00' AS TIMESTAMP), CAST(30.00 AS DECIMAL(18,2)), CAST(30.00 AS DECIMAL(18,2)), CAST(NULL AS DECIMAL(18,2)), CAST(30.00 AS DECIMAL(18,2)), 1, 0, 12, 2001, 20),
         |(103, 13, 3001, CAST(NULL AS BIGINT), 1, CAST(50.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(50.00 AS DECIMAL(18,2)), 'paid', CAST('2026-09-01 11:00:00' AS TIMESTAMP), '2026-09-01', CAST(NULL AS STRING), CAST('2026-09-01 11:05:00' AS TIMESTAMP), CAST(50.00 AS DECIMAL(18,2)), CAST(50.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(50.00 AS DECIMAL(18,2)), 1, 0, 13, 3001, CAST(NULL AS BIGINT)),
         |(104, 14, 1001, 10, 10, CAST(99.90 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(999.00 AS DECIMAL(18,2)), 'unpaid', CAST('2026-09-01 12:00:00' AS TIMESTAMP), '2026-09-01', 'L2', CAST(NULL AS TIMESTAMP), CAST(999.00 AS DECIMAL(18,2)), CAST(0.00 AS DECIMAL(18,2)), CAST(500.00 AS DECIMAL(18,2)), CAST(-500.00 AS DECIMAL(18,2)), 0, 0, 14, 1001, 10)""".stripMargin)
  }

  private def insertProducts(): Unit = {
    val table = s"${ns.dim}.dim_product"
    spark.sql(
      s"""INSERT INTO TABLE $table PARTITION (dt = '$dt') VALUES
         |(1001, 'phone', 10, '数码', 1, '电子产品', 7, CAST(10.00 AS DECIMAL(18,2)), CAST(5.00 AS DECIMAL(18,2)), 'active', 1, 1001, 10, 1, 7),
         |(2001, 'book', 20, '图书', 2, '阅读', 8, CAST(30.00 AS DECIMAL(18,2)), CAST(12.00 AS DECIMAL(18,2)), 'active', 1, 2001, 20, 2, 8)""".stripMargin)
  }

  private def amount(row: org.apache.spark.sql.Row, column: String): java.math.BigDecimal =
    new java.math.BigDecimal(row.getAs[Any](column).toString)

  "Category and city-level sales ADS" should "retain unknowns, exclude unpaid rows, and reconcile additive money to DWS" in {
    spark.sql(DwsSql.tradeDay(ns, dt))
    spark.sql(DwsSql.regionSaleDay(ns, dt))
    spark.sql(AdsSql.categorySale(ns, dt, Some(snapshot)))
    spark.sql(AdsSql.regionSale(ns, dt, Some(snapshot)))

    val categoryTable = AdsSql.staging(ns, "ads_category_sale")
    val categories = spark.table(categoryTable).where(s"snapshot_id = '$snapshot' AND dt = '$dt'")
      .collect().map(row => row.getAs[Long]("category_id") -> row).toMap
    categories.keySet should be(Set(-1L, 10L, 20L))
    categories(-1L).getAs[String]("category_name") should be("未分类")
    categories(10L).getAs[String]("category_name") should be("数码")
    categories(10L).getAs[Long]("parent_category_id") should be(1L)
    categories(10L).getAs[Long]("sale_count") should be(2L)
    amount(categories(10L), "sale_amount") should be(new java.math.BigDecimal("20.00"))
    amount(categories(10L), "net_sale_amount") should be(new java.math.BigDecimal("18.00"))
    amount(categories(-1L), "sale_amount") should be(new java.math.BigDecimal("50.00"))

    val regionTable = AdsSql.staging(ns, "ads_region_sale")
    val regions = spark.table(regionTable).where(s"snapshot_id = '$snapshot' AND dt = '$dt'")
      .collect().map(row => row.getAs[String]("region") -> row).toMap
    regions.keySet should be(Set("L1", "unknown"))
    amount(regions("L1"), "sale_amount") should be(new java.math.BigDecimal("50.00"))
    amount(regions("L1"), "net_sale_amount") should be(new java.math.BigDecimal("48.00"))
    amount(regions("unknown"), "sale_amount") should be(new java.math.BigDecimal("50.00"))

    val trade = spark.table(s"${ns.dws}.dws_trade_day").where(s"dt = '$dt'").head()
    amount(trade, "sale_amount") should be(new java.math.BigDecimal("100.00"))
    amount(trade, "net_sale_amount") should be(new java.math.BigDecimal("98.00"))
    AdsQualityJob.categorySaleReconcileCheck(spark, ns, snapshot, dt).passed should be(true)
    AdsQualityJob.regionSaleReconcileCheck(spark, ns, snapshot, dt).passed should be(true)

    val badCategoryStaging = AdsSql.staging(ns, "ads_category_sale")
    spark.sql(
      s"""INSERT INTO TABLE $badCategoryStaging PARTITION (snapshot_id = '$badSnapshot', dt = '$dt')
         |SELECT -1, '未分类', -1, '未分类', 1,
         |       CAST(1.00 AS DECIMAL(18,2)), CAST(1.00 AS DECIMAL(18,2))""".stripMargin)
    AdsQualityJob.categorySaleReconcileCheck(spark, ns, badSnapshot, dt).passed should be(false)
  }
}
