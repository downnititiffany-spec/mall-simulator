package com.graduation.analytics.job

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, lit, when}

/**
 * LocalJsonParquetJob（code=ljp）—— LOCAL/SINGLE_NODE 环境的无 Hive 验证作业：
 * 读取 Landing JSON 事件目录 → 过滤 schema_version='1.0' → 投影行为/交易关键字段 →
 * 写出 Parquet（PARTITIONED 按 event_date）。用于在无 Hadoop/Hive 环境下验证：
 * ① Scala 作业的编译产物可在真实 Spark 上运行；② JSON→DataFrame→Parquet 的转换链路正确。
 * 输入：--landingDir；输出：--outputDir。依赖 spark.read.json 的原生 JSON 支持。
 */
class LocalJsonParquetJob extends WarehouseJob {
  override val code: String = "ljp"
  override val description: String = "Landing JSON → Parquet（无 Hive 的本地验证作业）"

  override def validate(args: JobArgs): Either[String, Unit] = {
    if (!args.extra.contains("landingDir")) return Left("缺少参数 --landingDir")
    if (!args.extra.contains("outputDir")) return Left("缺少参数 --outputDir")
    Right(())
  }

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val landingDir = args.extra("landingDir")
    val outputDir = args.extra("outputDir")

    spark.sparkContext.setJobDescription(s"$code read-json")
    // Spark 原生 JSON 支持：模式自动推断（嵌套 payload.* 自动展开为列）
    val raw = spark.read.json(s"$landingDir")

    val inputRecords = raw.count()

    spark.sparkContext.setJobDescription(s"$code clean-project")
    val clean = raw
      .filter(col("schema_version") === "1.0")
      .select(
        col("event_id"),
        col("event_type"),
        col("event_time"),
        col("ingest_time"),
        col("source_system"),
        col("trace_id"),
        col("payload.user_id").cast("long").as("user_id"),
        col("payload.product_id").cast("long").as("product_id"),
        col("payload.behavior_type").as("behavior_type"),
        col("payload.order_id").as("order_id"),
        col("payload.total_amount").cast("decimal(18,2)").as("total_amount"),
        col("payload.amount").cast("decimal(18,2)").as("amount"),
        // 事件归属日期（yyyy-MM-dd，口径与指标字典一致）
        when(col("event_time").isNotNull, col("event_time").substr(1, 10))
          .as("event_date")
      )

    spark.sparkContext.setJobDescription(s"$code write-parquet")
    clean.write.mode("overwrite").parquet(outputDir)

    val outputRecords = spark.read.parquet(outputDir).count()
    JobResult.success(code, inputRecords, outputRecords, 0L,
      args.outputSnapshotId, args.attemptNo, System.currentTimeMillis() - start)
  }
}

object LocalJsonParquetJob {
  val instance: LocalJsonParquetJob = new LocalJsonParquetJob()
}