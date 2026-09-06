package com.graduation.analytics.job

import org.apache.spark.sql.SparkSession

/**
 * Spark 作业统一接口（§24.5）：code/validate/run。
 * 同一入口参数；禁止写死节点地址；业务算法在可测试的纯对象中。
 */
trait WarehouseJob {
  def code: String
  def description: String
  def validate(args: JobArgs): Either[String, Unit] = Right(())
  def run(spark: SparkSession, args: JobArgs): JobResult
}