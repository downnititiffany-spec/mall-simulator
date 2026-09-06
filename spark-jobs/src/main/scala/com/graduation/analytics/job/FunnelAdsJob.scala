package com.graduation.analytics.job

import com.graduation.analytics.sql.AdsSql
import org.apache.spark.sql.SparkSession

/**
 * Job04 漏斗 ADS（§24.6 FunnelAdsJob）：
 * dws_behavior_funnel_day → ads_behavior_funnel；
 * 同批生成大盘/活跃趋势/热门/商品转化（页面核心 ADS 首披）。
 */
class FunnelAdsJob extends WarehouseJob {
  override val code: String = "fna"
  override val description: String = "漏斗/大盘/活跃/热门/转化 ADS 指标"

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val dt = args.businessDate
    val topN = args.extra.get("topN").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(50)

    spark.sparkContext.setJobDescription(s"$code input")
    val inputCount = spark.sql(
      s"SELECT COUNT(*) c FROM dw_dws.dws_behavior_funnel_day WHERE dt = '$dt'").collect()(0).getLong(0)

    spark.sparkContext.setJobDescription(s"$code ads")
    spark.sql(AdsSql.operationOverview(dt))
    spark.sql(AdsSql.activeTrend(dt))
    spark.sql(AdsSql.hotProduct(dt, topN))
    spark.sql(AdsSql.productConversion(dt))
    spark.sql(AdsSql.saleTrend(dt))
    // ads_behavior_funnel：由漏斗 DWS 展开为 stage 行
    spark.sql(
      s"""
         |INSERT OVERWRITE TABLE dw_ads.ads_behavior_funnel PARTITION(dt = '$dt')
         |SELECT '$dt' AS dt, 'view' AS stage, view_users AS user_count, NULL AS conversion_rate,
         |       overall_buy_rate
         |FROM dw_dws.dws_behavior_funnel_day WHERE dt = '$dt'
         |UNION ALL
         |SELECT '$dt', 'intent', intent_users, intent_rate, overall_buy_rate
         |FROM dw_dws.dws_behavior_funnel_day WHERE dt = '$dt'
         |UNION ALL
         |SELECT '$dt', 'order', order_users, order_rate, overall_buy_rate
         |FROM dw_dws.dws_behavior_funnel_day WHERE dt = '$dt'
         |UNION ALL
         |SELECT '$dt', 'pay', pay_users, pay_rate, overall_buy_rate
         |FROM dw_dws.dws_behavior_funnel_day WHERE dt = '$dt'
         |""".stripMargin)

    val outputCount = spark.sql(
      s"SELECT COUNT(*) c FROM dw_ads.ads_behavior_funnel WHERE dt = '$dt'").collect()(0).getLong(0)
    JobResult.success(code, inputCount, outputCount, 0L, args.outputSnapshotId, args.attemptNo,
      System.currentTimeMillis() - start)
  }
}

object FunnelAdsJob {
  val instance: FunnelAdsJob = new FunnelAdsJob()
}