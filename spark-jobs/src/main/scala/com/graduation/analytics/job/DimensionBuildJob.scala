package com.graduation.analytics.job

import com.graduation.analytics.sql.DimSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession

/**
 * Job05 用户/商品/日期/地区维度构建（§11.2 DimensionBuildJob）：
 * ODS 用户事件 → dim_user；ODS 商品/库存事件 → dim_product；四类 ODS 日期 → dim_date；
 * canonical-event.v1 固定城市等级 → dim_region。
 * 用户与商品维度均按 dt 截止日回看 ODS 历史并生成完整快照，
 * 生效日期=dt 分区，来源批次=ingest_batch_id（source_batch_id）；
 * 维度数据来自事件流（ODS），禁止 Spark 直连商城数据库。
 */
class DimensionBuildJob extends WarehouseJob {
  override val code: String = "dim"
  override val description: String = "ODS 用户事件与截至业务日的商品历史 → dim_user / 完整 dim_product 快照"

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val dt = args.businessDate
    val ns = WarehouseNamespace.fromArgs(args)

    spark.sparkContext.setJobDescription(s"$code input count")
    val userInput = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.ods}.ods_user_event WHERE dt = '$dt'").collect()(0).getLong(0)
    // 商品快照是截至 dt 的完整视图：即使当日无商品事件，也要把历史商品延续到当天分区。
    // 查询条件与 DimSql.productSnapshot 的 as-of 过滤保持一致，避免晚到的未来分区泄漏。
    val productInput = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.ods}.ods_product_event WHERE dt = '$dt'").collect()(0).getLong(0)
    val productAsOfInput = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.ods}.ods_product_event WHERE dt <= '$dt' " +
        "AND schema_version = '1.0' AND payload_product_id IS NOT NULL " +
        "AND event_type IN ('product_created', 'product_updated')").collect()(0).getLong(0)

    // Validate the calendar inputs before any dimension write. A malformed ODS dt must not
    // leave user/product snapshots partially advanced when this stage fails.
    spark.sparkContext.setJobDescription(s"$code dim_date range bounds")
    val dateBounds = spark.sql(DimSql.dateRangeBounds(ns)).head()
    val minDate = dateBounds.getAs[java.sql.Date]("min_date")
    val maxDate = dateBounds.getAs[java.sql.Date]("max_date")
    val invalidDates = Option(dateBounds.getAs[java.lang.Long]("invalid_count")).fold(0L)(_.longValue())
    require(invalidDates == 0L,
      s"dim_date encountered $invalidDates non-null source date values that do not match their declared format")

    spark.sparkContext.setJobDescription(s"$code dim_user snapshot")
    // 用户维度同样是截至业务日的完整快照。即使当日没有注册事件，也必须延续历史用户，
    // 否则 DWD 当日关联会把所有既有用户的 city_level 回退为 unknown。
    spark.sql(DimSql.userSnapshot(ns, dt))
    spark.sparkContext.setJobDescription(s"$code dim_product as-of snapshot")
    // 即使当前没有合格 ODS 商品，也必须覆盖该静态分区：否则同日重跑会遗留旧快照。
    spark.sql(DimSql.productSnapshot(ns, dt))

    spark.sparkContext.setJobDescription(s"$code dim_region static dictionary")
    spark.sql(DimSql.regionDictionary(ns))

    // 日期边界已在写入前物化，再 OVERWRITE 可避免同一 SQL 语句同时读写目标表。
    if (minDate != null && maxDate != null) {
      spark.sparkContext.setJobDescription(s"$code dim_date idempotent range rebuild")
      spark.sql(DimSql.dateRangeRebuild(ns, minDate.toString, maxDate.toString))
    }

    val userOutput = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.dim}.dim_user WHERE dt = '$dt'").collect()(0).getLong(0)
    val productOutput = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.dim}.dim_product WHERE dt = '$dt'").collect()(0).getLong(0)
    val dateOutput = spark.sql(s"SELECT COUNT(*) c FROM ${ns.dim}.dim_date").collect()(0).getLong(0)
    val regionOutput = spark.sql(s"SELECT COUNT(*) c FROM ${ns.dim}.dim_region").collect()(0).getLong(0)
    require(regionOutput == 5L, s"dim_region static dictionary expected 5 members, got $regionOutput")

    JobResult.success(code, userInput + productInput, userOutput + productOutput + dateOutput + regionOutput, 0L,
      args.outputSnapshotId, args.attemptNo, System.currentTimeMillis() - start,
      PartitionEvidence.collect(spark, DimensionBuildJob.outputTables(ns), args.outputSnapshotId, Some(dt)))
      .copy(message = s"user=$userInput->$userOutput product=$productInput->$productOutput " +
        s"productAsOfEligible=$productAsOfInput dim_date=$dateOutput dim_region=$regionOutput")
  }
}

object DimensionBuildJob {
  val instance: DimensionBuildJob = new DimensionBuildJob()

  /** 本作业写出的目标表（R6-12 分区证据采集范围）；库名由唯一所有者派生 */
  def outputTables(ns: WarehouseNamespace): Seq[String] = Seq(
    ns.table("dim", "dim_user"), ns.table("dim", "dim_product"))
}
