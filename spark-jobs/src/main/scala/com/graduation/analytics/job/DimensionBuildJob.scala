package com.graduation.analytics.job

import com.graduation.analytics.sql.DimSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession

/**
 * Job05 用户/商品维度构建（§11.2 DimensionBuildJob）：
 * ODS 用户事件 → dim_user；ODS 商品/库存事件 → dim_product。
 * 每日全量快照，生效日期=dt 分区，来源批次=ingest_batch_id（source_batch_id）；
 * 维度数据来自事件流（ODS），禁止 Spark 直连商城数据库。
 */
class DimensionBuildJob extends WarehouseJob {
  override val code: String = "dim"
  override val description: String = "ODS 用户/商品事件 → dim_user / dim_product 每日快照"

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val dt = args.businessDate
    val ns = WarehouseNamespace.fromArgs(args)

    spark.sparkContext.setJobDescription(s"$code input count")
    val userInput = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.ods}.ods_user_event WHERE dt = '$dt'").collect()(0).getLong(0)
    val productInput = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.ods}.ods_product_event WHERE dt = '$dt'").collect()(0).getLong(0)

    spark.sparkContext.setJobDescription(s"$code dim_user snapshot")
    if (userInput > 0) spark.sql(DimSql.userSnapshot(ns, dt))
    spark.sparkContext.setJobDescription(s"$code dim_product snapshot")
    if (productInput > 0) spark.sql(DimSql.productSnapshot(ns, dt))

    val userOutput = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.dim}.dim_user WHERE dt = '$dt'").collect()(0).getLong(0)
    val productOutput = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.dim}.dim_product WHERE dt = '$dt'").collect()(0).getLong(0)

    JobResult.success(code, userInput + productInput, userOutput + productOutput, 0L,
      args.outputSnapshotId, args.attemptNo, System.currentTimeMillis() - start,
      PartitionEvidence.collect(spark, DimensionBuildJob.outputTables(ns), args.outputSnapshotId, Some(dt)))
      .copy(message = s"user=$userInput->$userOutput product=$productInput->$productOutput")
  }
}

object DimensionBuildJob {
  val instance: DimensionBuildJob = new DimensionBuildJob()

  /** 本作业写出的目标表（R6-12 分区证据采集范围）；库名由唯一所有者派生 */
  def outputTables(ns: WarehouseNamespace): Seq[String] = Seq(
    ns.table("dim", "dim_user"), ns.table("dim", "dim_product"))
}