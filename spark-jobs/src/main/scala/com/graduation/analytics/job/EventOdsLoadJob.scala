package com.graduation.analytics.job

import com.graduation.analytics.sql.OdsLoadSql
import org.apache.spark.sql.SparkSession

/**
 * Job01 行为事件 ODS 装载（§24.6 EventOdsLoadJob）：
 * Landing JSON → dw_ods.ods_behavior_event（版本过滤，未知版本计入 rejected）。
 */
class EventOdsLoadJob extends WarehouseJob {
  override val code: String = "odl"
  override val description: String = "Landing JSON → ODS 行为事件表"

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val landingDir = args.extra.getOrElse("landingDir", "/landing/events")
    val dt = args.businessDate

    spark.sparkContext.setJobDescription(s"$code input (landing)")
    val input = spark.read.json(s"$landingDir/runtime.json")
    val inputCount = input.count()

    spark.sparkContext.setJobDescription(s"$code ods load")
    spark.sql(OdsLoadSql.behaviorFromLanding(landingDir, dt, s"${dt}%"))
    // 注意：正式实现按 dt/hour 目录扫描；这里以行为主题演示主链路
    val outputCount = spark.sql(
      s"SELECT COUNT(*) c FROM dw_ods.ods_behavior_event WHERE dt = '$dt'").collect()(0).getLong(0)

    JobResult.success(code, inputCount, outputCount, 0L, args.outputSnapshotId, args.attemptNo,
      System.currentTimeMillis() - start)
  }

  override def validate(args: JobArgs): Either[String, Unit] =
    Either.cond(args.extra.contains("landingDir"), (), "缺少参数 --landingDir")
}

object EventOdsLoadJob {
  val instance: EventOdsLoadJob = new EventOdsLoadJob()
}