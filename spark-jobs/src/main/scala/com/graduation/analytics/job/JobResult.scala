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
 * 质量检查结果（R6-13，V2.0 §16.1/§16.5）：层次 + 目标表 + 检查数/错误数 + 阈值 + 严重度。
 * severity=BLOCKING 的规则不通过时作业必须 FAILED（不得 catch 后继续，§16 末尾）；
 * severity=ERROR 只记录不阻断（如 gold 夹具中的重复 event_id 观察项）。
 */
case class QualityCheck(
    ruleCode: String,
    layer: String,
    targetTable: String,
    checkCount: Long,
    errorCount: Long,
    threshold: String,
    severity: String,
    passed: Boolean,
    detail: String
)

/**
 * 作业执行结果（§5.3.1：输入/输出/异常记录数 + 快照与尝试号 + 输出分区证据 + 质量检查结果）。
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
    outputPartitions: Seq[OutputPartition] = Seq.empty,
    checks: Seq[QualityCheck] = Seq.empty
)

object JobResult {
  def success(jobCode: String, input: Long, output: Long, rejected: Long,
              snapshotId: Option[String], attemptNo: Int, elapsedMs: Long,
              outputPartitions: Seq[OutputPartition] = Seq.empty,
              checks: Seq[QualityCheck] = Seq.empty): JobResult =
    JobResult(jobCode, input, output, rejected, snapshotId, attemptNo, "SUCCESS", "ok", elapsedMs,
      outputPartitions, checks)

  def failed(jobCode: String, attemptNo: Int, message: String, elapsedMs: Long,
             outputPartitions: Seq[OutputPartition] = Seq.empty,
             checks: Seq[QualityCheck] = Seq.empty): JobResult =
    JobResult(jobCode, 0, 0, 0, None, attemptNo, "FAILED", message, elapsedMs,
      outputPartitions, checks)
}