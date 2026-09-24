package com.graduation.analytics

import com.graduation.analytics.job.{DimensionBuildJob, EventOdsLoadJob, JobArgs, LocalSchemaInitJob}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.{Row, SparkSession}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** QA-02 回归：无当日商品事件时仍生成 as-of 全量维表，且不得读取未来分区。 */
class ProductDimensionAsOfSpec extends AnyFlatSpec with Matchers {

  "商品维度 as-of 构建" should "跨无变更日延续商品并按业务日隔离未来更新" in {
    val spark: SparkSession = P2TestSupport.spark("product-dimension-as-of")
    try {
      val ns = WarehouseNamespace.defaultNamespace
      LocalSchemaInitJob.statements(ns).foreach { case (_, sql) => spark.sql(sql) }
      EventOdsLoadJob.registerUdfs(spark)

      val odsTable = ns.table("ods", "ods_product_event")
      val schema = spark.table(odsTable).schema
      def addProductEvent(id: String, eventType: String, time: String, name: String,
                          dt: String, batchId: Long): Unit = {
        val values: Map[String, Any] = Map(
          "event_id" -> id, "event_type" -> eventType, "event_time" -> time,
          "ingest_time" -> time, "source_system" -> "mall-a", "schema_version" -> "1.0",
          "trace_id" -> s"trace-$id", "raw_event_type" -> eventType,
          "raw_source_system" -> "mall-a", "landing_file" -> "fixture.jsonl",
          "payload_json" -> "{}", "payload_hash" -> s"hash-$id", "ingest_batch_id" -> batchId,
          "payload_product_id" -> "101", "payload_product_name" -> name,
          "payload_category_id" -> "10", "payload_category_name" -> "家电",
          "payload_parent_category_id" -> "1", "payload_parent_category_name" -> "数码",
          "payload_brand_id" -> "7", "payload_price" -> new java.math.BigDecimal("99.90"),
          "payload_cost" -> new java.math.BigDecimal("60.00"), "payload_status" -> "active",
          "source_file" -> "fixture.jsonl", "dt" -> dt, "hour" -> time.substring(11, 13))
        val row = Row.fromSeq(schema.fieldNames.map(name => values.getOrElse(name, null)))
        spark.createDataFrame(spark.sparkContext.parallelize(Seq(row)), schema)
          .write.mode("append").insertInto(odsTable)
      }
      def runDim(dt: String): Unit = {
        val args = JobArgs(1L, "dim", dt, None, None, 1, Map.empty)
        DimensionBuildJob.instance.run(spark, args).status shouldBe "SUCCESS"
      }
      def nameAt(dt: String): String = spark.sql(
        s"SELECT product_name FROM ${ns.dim}.dim_product WHERE dt = '$dt' AND product_id = 101")
        .head().getString(0)

      addProductEvent("p-created", "product_created", "2026-09-01 10:00:00", "旧商品名", "20260901", 1L)
      runDim("20260901")
      nameAt("20260901") shouldBe "旧商品名"

      // 9 月 2 日没有商品事件，完整 as-of 分区仍应包含前一日有效商品。
      runDim("20260902")
      nameAt("20260902") shouldBe "旧商品名"

      addProductEvent("p-updated", "product_updated", "2026-09-03 09:00:00", "新商品名", "20260903", 2L)
      runDim("20260903")
      nameAt("20260903") shouldBe "新商品名"

      // 即使未来更新已在 ODS 中，重跑 9 月 2 日也只能看到当时状态。
      runDim("20260902")
      nameAt("20260902") shouldBe "旧商品名"
    } finally P2TestSupport.stop(spark)
  }

  it should "同日重跑且不存在合格 ODS 商品时清除过期快照分区" in {
    val spark: SparkSession = P2TestSupport.spark("product-dimension-as-of-empty-rerun")
    try {
      val ns = WarehouseNamespace.defaultNamespace
      LocalSchemaInitJob.statements(ns).foreach { case (_, sql) => spark.sql(sql) }

      val dimTable = ns.table("dim", "dim_product")
      val schema = spark.table(dimTable).schema
      val staleValues: Map[String, Any] = Map(
        "product_id" -> 101L, "product_name" -> "过期商品名",
        "category_id" -> 10L, "category_name" -> "家电",
        "parent_category_id" -> 1L, "parent_category_name" -> "数码",
        "brand_id" -> 7L, "price" -> new java.math.BigDecimal("99.90"),
        "cost" -> new java.math.BigDecimal("60.00"), "status" -> "active",
        "source_batch_id" -> 1L, "product_key" -> 101L,
        "category_key" -> 10L, "parent_category_key" -> 1L,
        "brand_key" -> 7L, "dt" -> "20260901")
      val staleRow = Row.fromSeq(schema.fieldNames.map(name => staleValues.getOrElse(name, null)))
      spark.createDataFrame(spark.sparkContext.parallelize(Seq(staleRow)), schema)
        .write.mode("append").insertInto(dimTable)

      spark.sql(s"SELECT COUNT(*) FROM $dimTable WHERE dt = '20260901'").head().getLong(0) shouldBe 1L

      val args = JobArgs(1L, "dim", "20260901", None, None, 1, Map.empty)
      DimensionBuildJob.instance.run(spark, args).status shouldBe "SUCCESS"

      // ODS 没有任何合格商品事件时，仍须覆盖该静态分区，不能保留上一次运行的旧快照。
      spark.sql(s"SELECT COUNT(*) FROM $dimTable WHERE dt = '20260901'").head().getLong(0) shouldBe 0L
    } finally P2TestSupport.stop(spark)
  }
}
