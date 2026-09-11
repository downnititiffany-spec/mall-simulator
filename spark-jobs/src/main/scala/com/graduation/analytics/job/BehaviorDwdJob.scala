package com.graduation.analytics.job

import com.graduation.analytics.sql.DwdSql
import com.graduation.analytics.warehouse.WarehouseNamespace
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
    val ns = WarehouseNamespace.fromArgs(args)

    spark.sparkContext.setJobDescription(s"$code input")
    val input = spark.sql(s"SELECT COUNT(*) c FROM ${ns.ods}.ods_behavior_event WHERE dt = '$dt'")
    val inputCount = input.collect()(0).getLong(0)

    spark.sparkContext.setJobDescription(s"$code clean")
    spark.sql(DwdSql.behaviorClean(ns, dt))
    spark.sql(DwdSql.duplicateReject(ns, dt))

    val outputCount = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.dwd}.dwd_user_behavior_detail WHERE dt = '$dt'").collect()(0).getLong(0)
    val rejected = spark.sql(
      s"SELECT COUNT(*) c FROM ${ns.dwd}.dwd_reject_record WHERE dt = '$dt'").collect()(0).getLong(0)

    JobResult.success(code, inputCount, outputCount, rejected, args.outputSnapshotId, args.attemptNo,
      System.currentTimeMillis() - start,
      PartitionEvidence.collect(spark, BehaviorDwdJob.outputTables(ns), args.outputSnapshotId, Some(dt)))
  }
}

object BehaviorDwdJob {
  val instance: BehaviorDwdJob = new BehaviorDwdJob()

  /** 本作业写出的目标表（R6-12 分区证据采集范围）；库名由唯一所有者派生 */
  def outputTables(ns: WarehouseNamespace): Seq[String] =
    Seq(ns.table("dwd", "dwd_user_behavior_detail"), ns.table("dwd", "dwd_reject_record"))
}