package com.graduation.analytics.job

import com.graduation.analytics.sql.DwsSql
import org.apache.spark.sql.SparkSession

/**
 * Job03 用户/商品主题聚合（§24.6 UserProductDwsJob）：
 * DWD 行为 → dws_user_behavior_day / dws_behavior_funnel_day / dws_product_behavior_day。
 */
class UserProductDwsJob extends WarehouseJob {
  override val code: String = "usw"
  override val description: String = "DWD 行为 → DWS 用户日/漏斗日/商品日"

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val dt = args.businessDate

    spark.sparkContext.setJobDescription(s"$code input")
    val inputCount = spark.sql(
      s"SELECT COUNT(*) c FROM dw_dwd.dwd_user_behavior_detail WHERE dt = '$dt'").collect()(0).getLong(0)

    spark.sparkContext.setJobDescription(s"$code user day")
    spark.sql(DwsSql.userBehaviorDay(dt))
    spark.sparkContext.setJobDescription(s"$code funnel day")
    spark.sql(DwsSql.funnelDay(dt))
    spark.sparkContext.setJobDescription(s"$code product day")
    spark.sql(DwsSql.productBehaviorDay(dt))

    val outputCount = spark.sql(
      s"SELECT COUNT(*) c FROM dw_dws.dws_user_behavior_day WHERE dt = '$dt'").collect()(0).getLong(0)
    JobResult.success(code, inputCount, outputCount, 0L, args.outputSnapshotId, args.attemptNo,
      System.currentTimeMillis() - start)
  }
}

object UserProductDwsJob {
  val instance: UserProductDwsJob = new UserProductDwsJob()
}