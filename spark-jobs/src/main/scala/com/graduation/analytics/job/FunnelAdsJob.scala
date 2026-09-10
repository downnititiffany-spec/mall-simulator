package com.graduation.analytics.job

import com.graduation.analytics.sql.AdsSql
import org.apache.spark.sql.SparkSession

/**
 * Job04 漏斗 ADS（§24.6 FunnelAdsJob，§12.4 扩到 8 张核心 ADS）：
 * dws_behavior_funnel_day → ads_behavior_funnel；
 * 同批生成大盘/活跃趋势/热门/商品转化/销售趋势/用户画像/数据质量。
 *
 * R6-13（V2.0 §14.4 分区幂等协议）：本作业**只写暂存分区**
 * `dw_ads.{table}__staging/snapshot_id={snapshotId}/dt={dt}`，不碰正式分区；
 * 正式分区只在 dqc 质量门通过后由 pub 作业用 Hive 元数据指针发布。
 * 缺少 --outputSnapshotId 时直接校验失败：绝不允许"无快照号直写正式分区"绕过质量门。
 */
class FunnelAdsJob extends WarehouseJob {
  override val code: String = "fna"
  override val description: String = "8 张 ADS：漏斗/大盘/活跃/热门/转化/销售趋势/用户画像/数据质量（写暂存分区）"

  override def validate(args: JobArgs): Either[String, Unit] =
    args.outputSnapshotId.filter(_.nonEmpty)
      .toRight("fna 需要 --outputSnapshotId（R6-13：ADS 只写暂存分区，正式分区由 pub 发布）")
      .map(_ => ())

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val dt = args.businessDate
    val snapshotId = args.outputSnapshotId.get
    val topN = args.extra.get("topN").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(50)
    val periodStart = args.extra.getOrElse("periodStart", dt)
    val periodEnd = args.extra.getOrElse("periodEnd", dt)

    spark.sparkContext.setJobDescription(s"$code input")
    val inputCount = spark.sql(
      s"SELECT COUNT(*) c FROM dw_dws.dws_behavior_funnel_day WHERE dt = '$dt'").collect()(0).getLong(0)

    val sid = Some(snapshotId)
    spark.sparkContext.setJobDescription(s"$code ads staging")
    spark.sql(AdsSql.operationOverview(dt, sid))
    spark.sql(AdsSql.activeTrend(dt, sid))
    spark.sql(AdsSql.funnel(dt, sid))
    spark.sql(AdsSql.hotProduct(dt, topN, sid))
    spark.sql(AdsSql.productConversion(dt, sid))
    spark.sql(AdsSql.saleTrend(dt, sid))
    spark.sql(AdsSql.userProfile(dt, periodStart, periodEnd, sid))
    spark.sql(AdsSql.dataQuality(dt, sid))

    // 输出计数 = 8 张暂存表本次快照分区行数之和（真实 COUNT(*)，非输入数冒充）
    val stagingTables = FunnelAdsJob.STAGING_TABLES
    val outputCount = stagingTables.map(t =>
      spark.sql(s"SELECT COUNT(*) c FROM $t WHERE snapshot_id = '$snapshotId' AND dt = '$dt'")
        .collect()(0).getLong(0)).sum

    JobResult.success(code, inputCount, outputCount, 0L, sid, args.attemptNo,
      System.currentTimeMillis() - start,
      PartitionEvidence.collect(spark, stagingTables, sid, Some(dt)))
  }
}

object FunnelAdsJob {
  val instance: FunnelAdsJob = new FunnelAdsJob()

  /** 本作业写出的 8 张 ADS 暂存表（R6-13；证据采集范围 = 真实写入目标） */
  val STAGING_TABLES: Seq[String] = AdsSql.TABLES.map(AdsSql.staging)

  /** 正式表（pub 作业发布目标） */
  val FORMAL_TABLES: Seq[String] = AdsSql.TABLES.map(AdsSql.formal)
}
