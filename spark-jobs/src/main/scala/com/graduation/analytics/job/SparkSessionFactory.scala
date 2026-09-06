package com.graduation.analytics.job

import org.apache.spark.sql.SparkSession

/**
 * SparkSession 工厂：Hive 支持、Asia/Shanghai 时区、
 * shuffle 分区数与广播阈值来自启动参数（性能实验可复现，§7.9）。
 */
object SparkSessionFactory {

  def create(appName: String, extra: Map[String, String] = Map.empty): SparkSession = {
    val builder = SparkSession.builder()
      .appName(appName)
      .config("spark.sql.session.timeZone", "Asia/Shanghai")
      .config("spark.sql.shuffle.partitions", extra.getOrElse("shufflePartitions", "200"))
      .config("spark.sql.adaptive.enabled", "true")
      .enableHiveSupport()

    // 本机 LOCAL 验证模式：spark.local 通过 --master=local[*] 注入（不写死）
    extra.get("master").foreach(builder.master)
    extra.get("hiveMetastoreUris").foreach(uri => builder.config("hive.metastore.uris", uri))

    val spark = builder.getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    spark
  }
}