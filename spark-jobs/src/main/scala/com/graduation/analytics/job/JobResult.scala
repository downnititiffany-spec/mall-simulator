package com.graduation.analytics.job

/**
 * 输出分区证据（R6-12，V2.0 §15.3）：本次作业真实写出的表/dt/快照/行数/物理路径。
 * 五个字段均为实测值：rowCount 来自 `SELECT COUNT(*)`，path 来自 Hive 元数据 Location。
 */
case class OutputPartition(
    table: String,
    dt: String,
    snapshotId: Option[String],
    rowCount: Long,
    path: Option[String]
)

/**
 * 作业执行结果（§5.3.1：输入/输出/异常记录数 + 快照与尝试号 + 输出分区证据）。
 */
case class JobResult(
    jobCode: String,
    inputRecords: Long,
    outputRecords: Long,
    rejectedRecords: Long,
    snapshotId: Option[String],
    attemptNo: Int,
    status: String,       // SUCCESS / FAILED
    message: String,
    elapsedMs: Long,
    outputPartitions: Seq[OutputPartition] = Seq.empty
)

object JobResult {
  def success(jobCode: String, input: Long, output: Long, rejected: Long,
              snapshotId: Option[String], attemptNo: Int, elapsedMs: Long,
              outputPartitions: Seq[OutputPartition] = Seq.empty): JobResult =
    JobResult(jobCode, input, output, rejected, snapshotId, attemptNo, "SUCCESS", "ok", elapsedMs,
      outputPartitions)

  def failed(jobCode: String, attemptNo: Int, message: String, elapsedMs: Long): JobResult =
    JobResult(jobCode, 0, 0, 0, None, attemptNo, "FAILED", message, elapsedMs)
}