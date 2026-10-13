package com.graduation.analytics

import com.graduation.analytics.job.{DimensionBuildJob, JobArgs, LocalSchemaInitJob}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.{Row, SparkSession}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** D-061: dim_date 连续闭区间、四类 ODS 边界、保留既有历史、ISO-8601 与重跑幂等。 */
class DateDimensionRangeSpec extends AnyFlatSpec with Matchers {
  "日期维度范围构建" should "合并四类 ODS 与历史边界、按 ISO-8601 幂等重跑并在坏日期时先失败" in {
    val spark: SparkSession = P2TestSupport.spark("date-dimension-range")
    try {
      val ns = WarehouseNamespace.defaultNamespace
      LocalSchemaInitJob.statements(ns).foreach { case (_, sql) => spark.sql(sql) }
      val regionTable = ns.table("dim", "dim_region")
      def regionRows: Seq[Row] = spark.sql(
        s"SELECT region_code, region_name, city_level FROM $regionTable ORDER BY region_code")
        .collect().toSeq
      val expectedRegions = Seq("other", "tier1", "tier2", "tier3", "unknown")
        .map(member => Row(member, member, member))

      val emptyOdsBuild = DimensionBuildJob.instance.run(
        spark, JobArgs(1L, "dim", "20210104", None, None, 1, Map.empty))
      emptyOdsBuild.status shouldBe "SUCCESS"
      emptyOdsBuild.message should include("dim_region=5")
      regionRows shouldBe expectedRegions

      def addOdsDate(table: String, date: String): Unit = {
        val fullName = ns.table("ods", table)
        val schema = spark.table(fullName).schema
        val values: Map[String, Any] = Map("dt" -> date, "hour" -> "00")
        val row = Row.fromSeq(schema.fieldNames.map(name => values.getOrElse(name, null)))
        spark.createDataFrame(spark.sparkContext.parallelize(Seq(row)), schema)
          .write.mode("append").insertInto(fullName)
      }

      addOdsDate("ods_user_event", "20201231")
      addOdsDate("ods_product_event", "20210101")
      addOdsDate("ods_behavior_event", "20210103")
      addOdsDate("ods_trade_event", "20210104")

      val dateTable = ns.table("dim", "dim_date")
      val dateSchema = spark.table(dateTable).schema
      val oldDate = Row("2020-12-30", 2020, 12, 30, 53, 4, 3, 0)
      spark.createDataFrame(spark.sparkContext.parallelize(Seq(oldDate)), dateSchema)
        .write.mode("append").insertInto(dateTable)

      val args = JobArgs(1L, "dim", "20210104", None, None, 1, Map.empty)
      val first = DimensionBuildJob.instance.run(spark, args)
      first.status shouldBe "SUCCESS"
      first.message should include("dim_date=6")
      first.message should include("dim_region=5")

      regionRows shouldBe expectedRegions

      def dateRows: Seq[Row] = spark.sql(
        s"SELECT date_key, week_of_year, day_of_week, is_weekend FROM $dateTable ORDER BY date_key")
        .collect().toSeq
      val rowsAfterFirst = dateRows
      rowsAfterFirst.map(_.getString(0)) shouldBe Seq(
        "2020-12-30", "2020-12-31", "2021-01-01", "2021-01-02", "2021-01-03", "2021-01-04")
      val byDate = rowsAfterFirst.map(row => row.getString(0) -> row).toMap
      byDate("2021-01-01").getInt(1) shouldBe 53
      byDate("2021-01-01").getInt(2) shouldBe 5
      byDate("2021-01-03").getInt(2) shouldBe 7
      byDate("2021-01-03").getInt(3) shouldBe 1
      byDate("2021-01-04").getInt(2) shouldBe 1
      byDate("2021-01-04").getInt(3) shouldBe 0

      // The static dictionary is rebuilt independently of business events and removes drift rows.
      spark.sql(s"INSERT INTO $regionTable VALUES ('obsolete', 'obsolete', 'obsolete')")

      val second = DimensionBuildJob.instance.run(spark, args.copy(attemptNo = 2))
      second.status shouldBe "SUCCESS"
      dateRows shouldBe rowsAfterFirst
      regionRows shouldBe expectedRegions

      def appendByName(table: String, values: Map[String, Any]): Unit = {
        val fullName = ns.table(table.split("\\.")(0), table.split("\\.")(1))
        val schema = spark.table(fullName).schema
        val row = Row.fromSeq(schema.fieldNames.map(name => values.getOrElse(name, null)))
        spark.createDataFrame(spark.sparkContext.parallelize(Seq(row)), schema)
          .write.mode("append").insertInto(fullName)
      }
      // Seven digits can be accepted by some date parsers; the declared ODS partition contract is exact yyyyMMdd.
      appendByName("ods.ods_user_event", Map("dt" -> "2021011", "hour" -> "00"))
      // A correctly shaped but impossible date must also fail the calendar validity check.
      appendByName("ods.ods_trade_event", Map("dt" -> "20210229", "hour" -> "00"))
      appendByName("dim.dim_user", Map(
        "user_id" -> 99L, "age_group" -> "sentinel", "city_level" -> "tier1",
        "member_level" -> "silver", "register_date" -> "2021-01-04", "user_key" -> 99L,
        "dt" -> "20210104"))
      appendByName("dim.dim_product", Map(
        "product_id" -> 99L, "product_name" -> "sentinel", "status" -> "active",
        "product_key" -> 99L, "dt" -> "20210104"))

      intercept[Exception] {
        DimensionBuildJob.instance.run(spark, args.copy(attemptNo = 3))
      }
      spark.sql(s"SELECT user_id FROM ${ns.dim}.dim_user WHERE dt = '20210104'")
        .collect().map(_.getLong(0)).toSeq shouldBe Seq(99L)
      spark.sql(s"SELECT product_id FROM ${ns.dim}.dim_product WHERE dt = '20210104'")
        .collect().map(_.getLong(0)).toSeq shouldBe Seq(99L)
      dateRows shouldBe rowsAfterFirst
    } finally P2TestSupport.stop(spark)
  }
}
