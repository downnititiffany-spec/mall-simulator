package com.graduation.analytics.job

import com.graduation.analytics.sql.{EventLandingSchema, OdsLoadSql}
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.SparkSession

/**
 * Job01 全主题 ODS 装载（§10.2 OdsEventLoadJob 入口）：
 * Landing JSON 目录 → dw_ods 四主题表（ods_user_event / ods_product_event /
 * ods_behavior_event / ods_trade_event）。
 *
 * §10.2 落实：
 *  1. 使用显式 StructType（EventLandingSchema），不依赖自动推断；
 *  2. 校验 schema_version / event_id / event_type / event_time；
 *  3. 未知版本或缺失主键 → rejected（隔离计数，不进入正式分区）；
 *  4. 按业务日期 dt/hour 分区；
 *  5. INSERT OVERWRITE 幂等（重跑相同 inputVersion 分区内容不重复，§10.3）；
 *  6. 返回 input / accepted / rejected / output 四类计数。
 * 集群版由发布流程切换临时分区，本作业保持 SQL 模板可移植。
 */
class EventOdsLoadJob extends WarehouseJob {
  override val code: String = "odl"
  override val description: String = "Landing JSON → ODS 四主题表（用户/商品/行为/交易）"

  override def validate(args: JobArgs): Either[String, Unit] =
    Either.cond(args.extra.contains("landingDir"), (), "缺少参数 --landingDir")

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val landingDir = args.extra("landingDir")
    val batchId = args.extra.get("batchId").flatMap(v => scala.util.Try(v.toLong).toOption).getOrElse(0L)

    spark.sparkContext.setJobDescription(s"$code read-json: $landingDir")
    // §10.2(1)(2)：显式 Schema 读取，payload.* 结构完整保留（原始 JSON 可重放）
    val raw = spark.read.schema(EventLandingSchema.structType).json(landingDir)
    val inputCount = raw.count()

    // 校验：schema_version='1.0' + event_id/event_type/event_time 非空 + 类型在映射表
    val valid = raw.filter(
      col("schema_version") === "1.0" &&
        col("event_id").isNotNull && col("event_type").isNotNull && col("event_time").isNotNull &&
        col("event_type").isin(OdsLoadSql.eventTypeToTable.keys.toSeq: _*))
    val acceptedCount = valid.count()
    val rejectedCount = inputCount - acceptedCount

    // §10.2(1)：注册显式 Schema 视图，模板 SQL 从视图读取（schema 固定，不依赖 json 推断）
    valid.createOrReplaceTempView(OdsLoadSql.LANDING_VIEW)

    spark.sparkContext.setJobDescription(s"$code load four topics")
    val tables: Seq[(String, String)] = Seq(
      ("ods_user_event", "userFromLanding"),
      ("ods_product_event", "productFromLanding"),
      ("ods_behavior_event", "behaviorFromLanding"),
      ("ods_trade_event", "tradeFromLanding")
    )

    var outputCount = 0L
    tables.foreach { case (table, sqlName) =>
      val sql = sqlName match {
        case "userFromLanding"     => OdsLoadSql.userFromLanding(batchId)
        case "productFromLanding"  => OdsLoadSql.productFromLanding(batchId)
        case "behaviorFromLanding" => OdsLoadSql.behaviorFromLanding(batchId)
        case "tradeFromLanding"    => OdsLoadSql.tradeFromLanding(batchId)
      }
      spark.sql(sql) // INSERT OVERWRITE 幂等：分区内重跑内容相同，不重复累加（§10.3 第 4 条）
      outputCount += spark.sql(s"SELECT COUNT(*) c FROM dw_ods.$table").collect()(0).getLong(0)
    }

    spark.sparkContext.setJobDescription(s"$code rejected summary")
    val rejectedByVersion = raw.filter(
      col("schema_version") =!= "1.0" || col("event_id").isNull ||
        col("event_type").isNull || col("event_time").isNull).count()

    JobResult.success(code, inputCount, outputCount, rejectedCount,
      args.outputSnapshotId, args.attemptNo, System.currentTimeMillis() - start,
      PartitionEvidence.collect(spark, EventOdsLoadJob.OUTPUT_TABLES, args.outputSnapshotId))
      .copy(message = s"accepted=$acceptedCount rejectedVersionKeys=$rejectedByVersion topics=4")
  }
}

object EventOdsLoadJob {
  val instance: EventOdsLoadJob = new EventOdsLoadJob()

  /** 本作业写出的目标表（R6-12 分区证据采集范围） */
  val OUTPUT_TABLES: Seq[String] = Seq(
    "dw_ods.ods_user_event", "dw_ods.ods_product_event",
    "dw_ods.ods_behavior_event", "dw_ods.ods_trade_event")
}