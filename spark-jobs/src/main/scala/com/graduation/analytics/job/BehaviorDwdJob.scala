package com.graduation.analytics.job

import com.graduation.analytics.sql.DwdSql
import org.apache.spark.sql.SparkSession

/**
 * Job02 行为明细清洗（§24.6 BehaviorDwdJob）：
 * ods_behavior_event → dwd_user_behavior_detail（event_id 去重/枚举过滤/维度补充），
 * 重复事件 → dwd_reject_record。
 */
class BehaviorDwdJob extends WarehouseJob {
  override val code: String = "bdw"
  override val description: String = "ODS 行为 → DWD 明细（去重+清洗）"

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val dt = args.businessDate

    spark.sparkContext.setJobDescription(s"$code input")
    val input = spark.sql(s"SELECT COUNT(*) c FROM dw_ods.ods_behavior_event WHERE dt = '$dt'")
    val inputCount = input.collect()(0).getLong(0)

    spark.sparkContext.setJobDescription(s"$code clean")
    spark.sql(DwdSql.behaviorClean(dt))
    spark.sql(DwdSql.duplicateReject(dt))

    val outputCount = spark.sql(
      s"SELECT COUNT(*) c FROM dw_dwd.dwd_user_behavior_detail WHERE dt = '$dt'").collect()(0).getLong(0)
    val rejected = spark.sql(
      s"SELECT COUNT(*) c FROM dw_dwd.dwd_reject_record WHERE dt = '$dt'").collect()(0).getLong(0)

    JobResult.success(code, inputCount, outputCount, rejected, args.outputSnapshotId, args.attemptNo,
      System.currentTimeMillis() - start,
      PartitionEvidence.collect(spark, BehaviorDwdJob.OUTPUT_TABLES, args.outputSnapshotId, Some(dt)))
  }
}

object BehaviorDwdJob {
  val instance: BehaviorDwdJob = new BehaviorDwdJob()

  /** 本作业写出的目标表（R6-12 分区证据采集范围） */
  val OUTPUT_TABLES: Seq[String] = Seq("dw_dwd.dwd_user_behavior_detail", "dw_dwd.dwd_reject_record")
}