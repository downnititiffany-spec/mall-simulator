package com.graduation.analytics.job

import com.graduation.analytics.sql.OdsLoadSql
import org.apache.spark.sql.SparkSession

/**
 * Job01 行为事件 ODS 装载（§24.6 EventOdsLoadJob）：
 * Landing JSON 目录 → dw_ods.ods_behavior_event（schema_version 过滤，dt/hour 由 event_time 派生）。
 * 读目录而非单文件（滚动 jsonl 多文件）；输出先写临时分区由发布流程切换（集群版）。
 */
class EventOdsLoadJob extends WarehouseJob {
  override val code: String = "odl"
  override val description: String = "Landing JSON → ODS 行为事件表"

  override def validate(args: JobArgs): Either[String, Unit] =
    Either.cond(args.extra.contains("landingDir"), (), "缺少参数 --landingDir")

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val landingDir = args.extra("landingDir")

    spark.sparkContext.setJobDescription(s"$code input (landing)")
    val input = spark.read.json(s"$landingDir")
    val inputCount = input.count()
    // 仅统计行为事件（与 ODS 装载口径一致）
    val behaviorCount = input.filter("event_type = 'behavior' AND schema_version = '1.0'").count()

    spark.sparkContext.setJobDescription(s"$code ods load")
    spark.sql(OdsLoadSql.behaviorFromLanding(landingDir))

    val outputCount = spark.sql(
      "SELECT COUNT(*) c FROM dw_ods.ods_behavior_event").collect()(0).getLong(0)

    JobResult.success(code, behaviorCount, outputCount, 0L, args.outputSnapshotId, args.attemptNo,
      System.currentTimeMillis() - start)
  }
}

object EventOdsLoadJob {
  val instance: EventOdsLoadJob = new EventOdsLoadJob()
}