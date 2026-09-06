package com.graduation.analytics.job

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

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
    node.toString
  }
}