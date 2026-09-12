package com.graduation.analytics.job

import com.graduation.analytics.sql.{EventLandingSchema, JsonObjectSlicer, OdsLoadSql}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.functions.{col, from_json, udf}
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, SparkSession}

/**
 * Job01 全主题 ODS 装载（§10.2 OdsEventLoadJob 入口）：
 * Landing JSON 目录 → ns.ods 四主题表（ods_user_event / ods_product_event /
 * ods_behavior_event / ods_trade_event）。
 *
 * §10.2 落实：
 *  1. 使用显式 StructType（EventLandingSchema），不依赖自动推断；
 *  2. 校验 schema_version / event_id / event_type / event_time；
 *  3. 未知版本或缺失主键 → rejected（隔离计数，不进入正式分区）；
 *  4. 按业务日期 dt/hour 分区；
 *  5. INSERT OVERWRITE 幂等（重跑相同 inputVersion 分区内容不重复，§10.3）；
 *  6. 返回 input / accepted / rejected / output 四类计数。
 *
 * **P2-01 / ODS v2 读取路径（D-054…D-057）**：
 *  1. **先读原文行**（`spark.read.text`）而不是直接 `read.json`——因为「payload 原样字节」只能从
 *     原始行文本里切出来；`read.json` 把 payload 解析成 31 个标量后，原始对象字符串**已经不存在**。
 *  2. `raw_line` 原样保留，并用 `JsonObjectSlicer` 切出 `payload_json`、算出 `payload_hash`
 *     （UDF 在此注册，模板按名引用）。**不做** `to_json(payload)` 重序列化——红检实测
 *     「语义等价但键序/空白不同」的文本 SHA-256 与源行不同，重序列化不满足字节保真。
 *  3. `payload` 结构体走**第二次** `from_json`（载荷文本 → `EventLandingSchema` 的 payload 段）
 *     —— 实测该路径与 v1 的「闭合 schema 一次解析」**逐行等价**（含 DECIMAL(18,2) 与 STRING items），
 *     所以 v1 的 `payload_*` 双写口径零变化。
 *  4. `landing_file` / `source_file` 取 `_metadata.file_path`（实测：`file:/D:/…/golden-20260901.jsonl`；
 *     `input_file_name()` 在相对/`file:///` 输入下可能给空串，故不用它）——常量 `'landing'` 已消失（D-057）。
 *  5. `source_system` **必须**由平台参数通道注入（`--sourceSystem=<source_registry.source_code>`，D-056），
 *     行内原值只落到 `raw_source_system`；缺失注入值直接判参数错误，不再退回信任行内值。
 */
class EventOdsLoadJob extends WarehouseJob {
  override val code: String = "odl"
  override val description: String = "Landing JSON → ODS 四主题表（用户/商品/行为/交易）"

  override def validate(args: JobArgs): Either[String, Unit] = {
    val landingOk = args.extra.contains("landingDir")
    val sourceSystemOk = args.extra.get(OdsLoadSql.ArgSourceSystem)
      .exists(v => v != null && v.trim.nonEmpty)
    if (!landingOk) Left("缺少参数 --landingDir")
    else if (!sourceSystemOk) {
      Left(s"缺少参数 --${OdsLoadSql.ArgSourceSystem}" +
        "（D-056：source_system 只能由平台参数通道注入，不接受行内值、不接受缺省）")
    } else Right(())
  }

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val landingDir = args.extra("landingDir")
    val batchId = args.extra.get("batchId").flatMap(v => scala.util.Try(v.toLong).toOption).getOrElse(0L)
    val ns = WarehouseNamespace.fromArgs(args)
    val sourceSystem = args.extra.getOrElse(OdsLoadSql.ArgSourceSystem,
      throw new IllegalArgumentException(
        s"缺少参数 --${OdsLoadSql.ArgSourceSystem}（D-056：必须由平台参数通道注入）"))

    EventOdsLoadJob.registerUdfs(spark)

    spark.sparkContext.setJobDescription(s"$code read-text: $landingDir")
    // §10.2(1)：显式 Schema；P2-01：先拿原文行，再从原文里切 payload
    val rawLines = spark.read.text(landingDir)
      .withColumn(OdsLoadSql.ColLandingFile, col("_metadata.file_path"))
      .select(col("value").as(OdsLoadSql.ColRawLine), col(OdsLoadSql.ColLandingFile))
    val inputCount = rawLines.count()

    // 一级解析：外层信封（payload 取成**字符串**，保留其原文，不解析成结构体）
    val landing = rawLines
      .select(from_json(col(OdsLoadSql.ColRawLine), EventOdsLoadJob.envelopeSchema).as("e"),
        col(OdsLoadSql.ColRawLine), col(OdsLoadSql.ColLandingFile))
      .select(col("e.*"), col(OdsLoadSql.ColRawLine), col(OdsLoadSql.ColLandingFile))

    // 二级解析：载荷文本 → v1 的 payload 结构体（实测与 v1 一次解析逐行等价）
    val projected = landing
      .withColumn(OdsLoadSql.ColPayload,
        from_json(col(OdsLoadSql.ColPayloadText), OdsLoadSql.landingPayloadStruct))

    // 校验：schema_version='1.0' + event_id/event_type/event_time 非空 + 类型在映射表
    val valid = projected.filter(
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
      // D-056：注入值经参数通道进入 SQL 字面量（含 ' 转义），模板不再读行内 source_system
      val sql = sqlName match {
        case "userFromLanding"     => OdsLoadSql.userFromLanding(ns, sourceSystem, batchId)
        case "productFromLanding"  => OdsLoadSql.productFromLanding(ns, sourceSystem, batchId)
        case "behaviorFromLanding" => OdsLoadSql.behaviorFromLanding(ns, sourceSystem, batchId)
        case "tradeFromLanding"    => OdsLoadSql.tradeFromLanding(ns, sourceSystem, batchId)
      }
      spark.sql(sql) // INSERT OVERWRITE 幂等：分区内重跑内容相同，不重复累加（§10.3 第 4 条）
      outputCount += spark.sql(s"SELECT COUNT(*) c FROM ${ns.ods}.$table").collect()(0).getLong(0)
    }

    spark.sparkContext.setJobDescription(s"$code rejected summary")
    val rejectedByVersion = projected.filter(
      col("schema_version") =!= "1.0" || col("event_id").isNull ||
        col("event_type").isNull || col("event_time").isNull).count()

    JobResult.success(code, inputCount, outputCount, rejectedCount,
      args.outputSnapshotId, args.attemptNo, System.currentTimeMillis() - start,
      PartitionEvidence.collect(spark, EventOdsLoadJob.outputTables(ns), args.outputSnapshotId))
      .copy(message = s"accepted=$acceptedCount rejectedVersionKeys=$rejectedByVersion topics=4" +
        s" sourceSystem=$sourceSystem")
  }
}

object EventOdsLoadJob {
  val instance: EventOdsLoadJob = new EventOdsLoadJob()

  /**
   * 一级解析用的**显式信封 Schema**：与 v1 `landingSchema` 的字段逐字一致，
   * 两处差别：
   *  - `payload` 声明为 `STRING`（保留原文），而不是闭合 STRUCT——`from_json` 对 struct 字段
   *    会解析并**丢弃**原始文本，之后无论 `to_json` 还是逐字段拼都拿不回原样字节（D-054 要求原样字节）；
   *  - 它被**改名**成 `landing_payload_text`，这样二级解析产出的 `payload` 结构体不会同名冲突。
   *
   * `lazy` 同 `OdsLoadSql.landingPayloadStruct` 的理由：跨对象初始化的顺序不保证。
   */
  lazy val envelopeSchema: StructType = StructType(
    OdsLoadSql.landingSchema.fields.map { f =>
      if (f.name == OdsLoadSql.ColPayload) {
        StructField(OdsLoadSql.ColPayloadText, StringType, nullable = true)
      } else f
    })

  /** 注册原样切片 UDF（幂等：重复调用只覆盖同名实现） */
  def registerUdfs(spark: SparkSession): Unit = {
    spark.udf.register(OdsLoadSql.UDF_SLICE_PAYLOAD,
      udf((rawLine: String) => JsonObjectSlicer.sliceAndHash(rawLine)._1))
    spark.udf.register(OdsLoadSql.UDF_SHA256_HEX,
      udf((rawLine: String) => JsonObjectSlicer.sliceAndHash(rawLine)._2))
  }

  /** 切片辅助（供作业内联使用/测试对照）：raw_line → (payload_json, payload_hash) */
  def sliceColumns(rawLines: DataFrame): DataFrame = {
    val slice = udf((rawLine: String) => JsonObjectSlicer.sliceAndHash(rawLine)._1)
    val hash = udf((rawLine: String) => JsonObjectSlicer.sliceAndHash(rawLine)._2)
    rawLines
      .withColumn("payload_json", slice(col(OdsLoadSql.ColRawLine)))
      .withColumn("payload_hash", hash(col(OdsLoadSql.ColRawLine)))
  }
  /** 本作业写出的目标表（R6-12 分区证据采集范围）；库名由唯一所有者派生 */
  def outputTables(ns: WarehouseNamespace): Seq[String] = Seq(
    ns.table("ods", "ods_user_event"), ns.table("ods", "ods_product_event"),
    ns.table("ods", "ods_behavior_event"), ns.table("ods", "ods_trade_event"))
}
