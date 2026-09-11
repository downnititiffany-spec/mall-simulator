package com.graduation.analytics.job

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.graduation.analytics.warehouse.WarehouseNamespace

/**
 * 作业统一入口（§24.5）：
 *   spark-submit --class com.graduation.analytics.job.JobRunner spark-jobs-0.1.0.jar \
 *     --runtimeProfileId=1 --jobCode=bdw --businessDate=20260901 --attemptNo=1 [--key=value...]
 * 输出一行 JobResult JSON（stdout 机器契约），日志走 stderr。
 */
object JobRunner {

  private val mapper = new ObjectMapper()

  def main(args: Array[String]): Unit = {
    val started = System.currentTimeMillis()
    JobArgs.parse(args) match {
      case Left(err) =>
        System.err.println(s"[spark-jobs] 参数错误: $err")
        System.exit(64)
      case Right(jobArgs) =>
        // P1-04：数仓库名空间在创建 SparkSession **之前**校验——非法前缀绝不进入 Spark
        WarehouseNamespace.parse(
          jobArgs.extra.getOrElse(WarehouseNamespace.ArgKey, WarehouseNamespace.DefaultPrefix)) match {
          case Left(code) =>
            System.err.println(s"[spark-jobs] 库名空间前缀非法（--${WarehouseNamespace.ArgKey}=" +
              s"${jobArgs.extra.getOrElse(WarehouseNamespace.ArgKey, "")}）：$code")
            System.exit(64)
          case Right(namespace) =>
            System.err.println(s"[spark-jobs] 数仓库名空间: ${namespace.layers.values.mkString(", ")}")
            dispatch(jobArgs, started)
        }
    }
  }

  /** 参数与库名空间校验通过后的作业分发（原 main 内层逻辑，行为不变） */
  private def dispatch(jobArgs: JobArgs, started: Long): Unit = {
    JobRegistry.lookup(jobArgs.jobCode) match {
      case None =>
        System.err.println(s"[spark-jobs] 未知作业: ${jobArgs.jobCode}，可用: ${JobRegistry.allCodes.mkString(",")}")
        System.exit(2)
      case Some(job) =>
        job.validate(jobArgs) match {
          case Left(err) =>
            System.err.println(s"[spark-jobs] ${job.code} 校验失败: $err")
            System.exit(2)
          case Right(_) =>
            val spark = SparkSessionFactory.create(s"${job.code}-${jobArgs.businessDate}", jobArgs.extra)
            try {
              val result = job.run(spark, jobArgs)
              println(toJson(result))
              if (result.status == "FAILED") System.exit(1)
            } catch {
              case e: Exception =>
                val failed = JobResult.failed(job.code, jobArgs.attemptNo,
                  Option(e.getMessage).getOrElse(e.getClass.getSimpleName),
                  System.currentTimeMillis() - started)
                println(toJson(failed))
                System.err.println(s"[spark-jobs] ${job.code} 执行异常: ${e.getMessage}")
                System.exit(1)
            } finally {
              spark.stop()
            }
        }
    }
  }

  def toJson(r: JobResult): String = {
    val node: ObjectNode = mapper.createObjectNode()
    node.put("jobCode", r.jobCode)
    node.put("inputRecords", r.inputRecords)
    node.put("outputRecords", r.outputRecords)
    node.put("rejectedRecords", r.rejectedRecords)
    r.snapshotId.foreach(node.put("snapshotId", _))
    node.put("attemptNo", r.attemptNo)
    node.put("status", r.status)
    node.put("message", r.message)
    node.put("elapsedMs", r.elapsedMs)
    // R6-12：输出分区证据（表/dt/snapshotId/rowCount/path），Java 侧落 spark_job_run.output_partitions_json
    val partitions = node.putArray("outputPartitions")
    r.outputPartitions.foreach { p =>
      val o = partitions.addObject()
      o.put("table", p.table)
      o.put("dt", p.dt)
      p.snapshotId.foreach(o.put("snapshotId", _))
      o.put("rowCount", p.rowCount)
      p.path.foreach(o.put("path", _))
    }
    // R6-13：质量检查结果（层次/目标表/检查数/错误数/阈值/严重度/是否通过）
    val checks = node.putArray("checks")
    r.checks.foreach { c =>
      val o = checks.addObject()
      o.put("ruleCode", c.ruleCode)
      o.put("layer", c.layer)
      o.put("targetTable", c.targetTable)
      o.put("checkCount", c.checkCount)
      o.put("errorCount", c.errorCount)
      o.put("threshold", c.threshold)
      o.put("severity", c.severity)
      o.put("passed", c.passed)
      o.put("detail", c.detail)
    }
    node.toString
  }
}