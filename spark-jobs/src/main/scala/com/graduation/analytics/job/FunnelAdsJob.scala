package com.graduation.analytics.job

import com.graduation.analytics.sql.AdsSql
import org.apache.spark.sql.SparkSession

/**
 * Job04 漏斗 ADS（§24.6 FunnelAdsJob，§12.4 扩到 8 张核心 ADS）：
 * dws_behavior_funnel_day → ads_behavior_funnel；
 * 同批生成大盘/活跃趋势/热门/商品转化/销售趋势/用户画像/数据质量。
 */
class FunnelAdsJob extends WarehouseJob {
  override val code: String = "fna"
  override val description: String = "8 张 ADS：漏斗/大盘/活跃/热门/转化/销售趋势/用户画像/数据质量"

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val dt = args.businessDate
    val topN = args.extra.get("topN").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(50)
    val periodStart = args.extra.getOrElse("periodStart", dt)
    val periodEnd = args.extra.getOrElse("periodEnd", dt)

    spark.sparkContext.setJobDescription(s"$code input")
    val inputCount = spark.sql(
      s"SELECT COUNT(*) c FROM dw_dws.dws_behavior_funnel_day WHERE dt = '$dt'").collect()(0).getLong(0)

    spark.sparkContext.setJobDescription(s"$code ads")
    spark.sql(AdsSql.operationOverview(dt))
    spark.sql(AdsSql.activeTrend(dt))
    spark.sql(AdsSql.funnel(dt))
    spark.sql(AdsSql.hotProduct(dt, topN))
    spark.sql(AdsSql.productConversion(dt))
    spark.sql(AdsSql.saleTrend(dt))
    spark.sql(AdsSql.userProfile(dt, periodStart, periodEnd))
    spark.sql(AdsSql.dataQuality(dt))

    val outputCount = spark.sql(
      s"SELECT COUNT(*) c FROM dw_ads.ads_behavior_funnel WHERE dt = '$dt'").collect()(0).getLong(0)
    JobResult.success(code, inputCount, outputCount, 0L, args.outputSnapshotId, args.attemptNo,
      System.currentTimeMillis() - start,
      PartitionEvidence.collect(spark, FunnelAdsJob.OUTPUT_TABLES, args.outputSnapshotId, Some(dt)))
  }
}

object FunnelAdsJob {
  val instance: FunnelAdsJob = new FunnelAdsJob()

  /** 本作业写出的 8 张 ADS（R6-12 分区证据采集范围） */
  val OUTPUT_TABLES: Seq[String] = Seq(
    "dw_ads.ads_operation_overview", "dw_ads.ads_active_trend", "dw_ads.ads_behavior_funnel",
    "dw_ads.ads_hot_product", "dw_ads.ads_product_conversion", "dw_ads.ads_sale_trend",
    "dw_ads.ads_user_profile", "dw_ads.ads_data_quality")
}