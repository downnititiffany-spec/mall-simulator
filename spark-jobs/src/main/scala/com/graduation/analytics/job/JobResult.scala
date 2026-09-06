package com.graduation.analytics.job

/**
 * 作业执行结果（§5.3.1：输入/输出/异常记录数 + 快照与尝试号）。
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
    elapsedMs: Long
)

object JobResult {
  def success(jobCode: String, input: Long, output: Long, rejected: Long,
              snapshotId: Option[String], attemptNo: Int, elapsedMs: Long): JobResult =
    JobResult(jobCode, input, output, rejected, snapshotId, attemptNo, "SUCCESS", "ok", elapsedMs)

  def failed(jobCode: String, attemptNo: Int, message: String, elapsedMs: Long): JobResult =
    JobResult(jobCode, 0, 0, 0, None, attemptNo, "FAILED", message, elapsedMs)
}