package com.graduation.analytics.job

import com.graduation.analytics.sql.DwsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession

/**
 * Job03 用户/商品主题聚合（§24.6 UserProductDwsJob，§12.1 扩到 7 张）：
 * DWD → dws_user_behavior_day / dws_behavior_funnel_day / dws_product_behavior_day /
 *       dws_trade_day / dws_product_sale_day / dws_user_trade_period / dws_region_sale_day。
 * 观察期 [periodStart, periodEnd] 缺省为当日窗口，可由 extra 参数覆盖（RFM 复购分析用）。
 */
class UserProductDwsJob extends WarehouseJob {
  override val code: String = "usw"
  override val description: String = "DWD → 7 张 DWS 聚合（用户/漏斗/商品/交易/商品销售/用户周期/地区）"

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val dt = args.businessDate
    val periodStart = args.extra.getOrElse("periodStart", dt)
    val periodEnd = args.extra.getOrElse("periodEnd", dt)
    val ns = WarehouseNamespace.fromArgs(args)

    spark.sparkContext.setJobDescription(s"$code input")
    val inputCount = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.dwd}.dwd_user_behavior_detail WHERE dt = '$dt'").collect()(0).getLong(0)

    spark.sparkContext.setJobDescription(s"$code user day")
    spark.sql(DwsSql.userBehaviorDay(ns, dt))
    spark.sparkContext.setJobDescription(s"$code funnel day")
    spark.sql(DwsSql.funnelDay(ns, dt))
    spark.sparkContext.setJobDescription(s"$code product behavior day")
    spark.sql(DwsSql.productBehaviorDay(ns, dt))
    spark.sparkContext.setJobDescription(s"$code trade day")
    spark.sql(DwsSql.tradeDay(ns, dt))
    spark.sparkContext.setJobDescription(s"$code product sale day")
    spark.sql(DwsSql.productSaleDay(ns, dt))
    spark.sparkContext.setJobDescription(s"$code user trade period")
    spark.sql(DwsSql.userTradePeriod(ns, dt, periodStart, periodEnd))
    spark.sparkContext.setJobDescription(s"$code region sale day")
    spark.sql(DwsSql.regionSaleDay(ns, dt))

    val outputCount = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.dws}.dws_user_behavior_day WHERE dt = '$dt'").collect()(0).getLong(0)
    JobResult.success(code, inputCount, outputCount, 0L, args.outputSnapshotId, args.attemptNo,
      System.currentTimeMillis() - start,
      PartitionEvidence.collect(spark, UserProductDwsJob.outputTables(ns), args.outputSnapshotId, Some(dt)))
  }
}

object UserProductDwsJob {
  val instance: UserProductDwsJob = new UserProductDwsJob()

  /** 本作业写出的 7 张 DWS（R6-12 分区证据采集范围）；库名由唯一所有者派生 */
  def outputTables(ns: WarehouseNamespace): Seq[String] = Seq(
    ns.table("dws", "dws_user_behavior_day"), ns.table("dws", "dws_behavior_funnel_day"),
    ns.table("dws", "dws_product_behavior_day"), ns.table("dws", "dws_trade_day"),
    ns.table("dws", "dws_product_sale_day"), ns.table("dws", "dws_user_trade_period"),
    ns.table("dws", "dws_region_sale_day"))
}