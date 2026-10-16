package com.graduation.analytics.job

import com.graduation.analytics.sql.{EventLandingSchema, JsonObjectSlicer, OdsLoadSql}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.functions.{col, from_json, udf}
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
 *  5. 增量合并语义（G31-08 / F-G4-1·D-040，§10.3 幂等在分区内与跨批次两个维度同时成立）：
 *     按「命中分区 读-合并-去重-覆写」写出——新批行 ∪ 命中分区旧行（event_id 未被新批
 *     覆盖者）整体覆写回命中分区；非命中分区物理不动，同批重放 anti-join 幂等；
 *  6. 返回 input / accepted / rejected / output 四类计数。
 *
 * **P2-01 / ODS v2 读取路径（D-054…D-057）**：
 *  1. **先读原文行**（`spark.read.text`）而不是直接 `read.json`——因为「payload 原样字节」只能从
 *     原始行文本里切出来；`read.json` 把 payload 解析成 31 个标量后，原始对象字符串**已经不存在**。
 *  2. `raw_line` 原样保留，并用 `JsonObjectSlicer` 切出 `payload_json`、算出 `payload_hash`
 *     （UDF 在此注册，模板按名引用）。**不做** `to_json(payload)` 重序列化——红检实测
 *     「语义等价但键序/空白不同」的文本 SHA-256 与源行不同，重序列化不满足字节保真。
 *  3. `payload` 结构体由 **v1 的闭合 schema 一次 `from_json`** 产出（`OdsLoadSql.landingSchema`
 *     是信封+载荷的唯一所有者），与 v1 「闭合 schema 一次解析」逐行等价（含 DECIMAL(18,2) 与
 *     STRING items），所以 v1 的 `payload_*` 双写口径零变化。
 *     **E3 真链纠错**：曾用「payload 取成 STRING 再二次 from_json」实现，实测 `from_json`
 *     把 JSON 对象塞进 STRING 字段时给 NULL（不是对象原文），导致全部 v1 `payload_*` 列静默为 NULL；
 *     已回到一次闭合解析。证据：`evidence/e3-probe-payload-struct-null.log`。
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
    // §10.2(1)：显式 Schema；P2-01：原文行原样保留（payload 的字节只能从原文里切），
    // 结构体则回 v1 的**闭合 schema 一次解析**产出（见下方 E3 实测纠错）。
    val rawLines = spark.read.text(landingDir)
      .withColumn(OdsLoadSql.ColLandingFile, col("_metadata.file_path"))
      .select(col("value").as(OdsLoadSql.ColRawLine), col(OdsLoadSql.ColLandingFile))
    val inputCount = rawLines.count()

    // 【E3 真链实测纠错，替换掉「先把 payload 取成 STRING 再二次 from_json」的写法】
    // 旧写法：envelope 里把 `payload` 声明为 STRING（别名 `landing_payload_text`），再用第二次
    // from_json 把该文本解析成 payload 结构体。真链实测该写法**静默失效**：
    // `from_json`（PERMISSIVE）遇到「JSON 对象 → STRING 字段」时给 **NULL**，而不是对象的原文，
    // 于是 payload 结构体整列为 NULL ⇒ 四张 ODS 表的全部 v1 `payload_*` 列全 NULL。
    // 无异常、无拒绝计数，属静默数据丢失。原始读数（只读探针，未写任何库表）：
    //   evidence/e3-probe-payload-struct-null.log
    //   Q1_steps: get_json_object(value,'$.payload') = {"user_id":"1",…}
    //             from_json(value, '… landing_payload_text STRING').landing_payload_text = NULL
    //             from_json(<payload 文本>, 'user_id STRING, age_group STRING') = {"user_id":"1",…}
    //   Q2_table: ods_user_event.payload_user_id / payload_age_group = NULL，而 payload_json 正确
    //
    // 修法：一次闭合解析（`OdsLoadSql.landingSchema` 是信封+载荷的唯一所有者，与 v1 完全同口径，
    // 满足 D-058「v1 一个不动」）：`e.*` 同时给出 7 个信封列与 `payload` 结构体；
    // 原文行 `raw_line` 单独保留，`payload_json`/`payload_hash` 仍由 JsonObjectSlicer UDF 从原文切出，
    // 完全不经过 from_json（字节保真与结构解析互不依赖）。
    val projected = rawLines
      .select(from_json(col(OdsLoadSql.ColRawLine), OdsLoadSql.landingSchema).as("e"),
        col(OdsLoadSql.ColRawLine), col(OdsLoadSql.ColLandingFile))
      .select(col("e.*"), col(OdsLoadSql.ColRawLine), col(OdsLoadSql.ColLandingFile))

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
    val tables: Seq[String] = Seq(
      "ods_user_event", "ods_product_event", "ods_behavior_event", "ods_trade_event")

    // G31-08 / F-G4-1（D-040）：裸 INSERT OVERWRITE 未设 partitionOverwriteMode 时是 STATIC
    // 整表覆写——同日第二批增量会把历史清空（G31-04 run2 实测：3 张 ODS 表 parquet 归零）；
    // 且仅开动态覆盖也不够：同一分区内的旧记录仍会被覆写掉（D-044④ 硬约束）。
    // 修复 = 分区作用域「读-合并-去重-覆写」：
    //   newRows       = 现行 SELECT 模板输出（模板字节不变，见 OdsLoadSql.selectFromLanding）；
    //   hits          = newRows 的 distinct (dt, hour)——本批命中的分区；
    //   staleExisting = 目标表 ⋈hits（只取命中分区旧行） anti-join newRows.event_id
    //                   （同 event_id 旧副本让位新批 = new-wins）；
    //   merged        = newRows ∪ staleExisting，整体覆写回命中分区。
    // 性质：非命中分区物理不动；命中分区旧行保留；同批重放 anti-join 幂等；批内不去重
    // （现状保持，重放稳定性由 anti-join 保证）；空批零写入。
    val overwriteModeKey = "spark.sql.sources.partitionOverwriteMode"
    val previousOverwriteMode = spark.conf.getOption(overwriteModeKey)
    var outputCount = 0L
    try {
      spark.conf.set(overwriteModeKey, "dynamic") // 只在写期间生效，finally 还原（防跨作业泄漏）
      tables.foreach { table =>
        val newRows = spark.sql(OdsLoadSql.selectFromLanding(table, sourceSystem, batchId))
        if (!newRows.take(1).isEmpty) {
          val hits = newRows.select(OdsLoadSql.ColDt, OdsLoadSql.ColHour).distinct()
          val staleExisting = spark.table(s"${ns.ods}.$table")
            .join(hits, Seq(OdsLoadSql.ColDt, OdsLoadSql.ColHour), "left_semi")
            .join(newRows.select(OdsLoadSql.ColEventId), Seq(OdsLoadSql.ColEventId), "left_anti")
          val mergedView = OdsLoadSql.mergeView(table)
          newRows.unionByName(staleExisting).createOrReplaceTempView(mergedView)
          spark.sql(OdsLoadSql.mergeInsertOverwrite(ns, table, mergedView))
        }
        outputCount += spark.sql(s"SELECT COUNT(*) c FROM ${ns.ods}.$table").collect()(0).getLong(0)
      }
    } finally {
      previousOverwriteMode match {
        case Some(v) => spark.conf.set(overwriteModeKey, v)
        case None    => spark.conf.unset(overwriteModeKey)
      }
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

  // `envelopeSchema`（把 `payload` 声明成 STRING 的信封 schema）已删除：它的唯一用途是
  // 「一级解析保留 payload 原文」，而真链实测该路径给 NULL（见 run 内注释与
  // evidence/e3-probe-payload-struct-null.log）。信封 schema 现在**就是** `OdsLoadSql.landingSchema`
  // 本身——少一份可以漂移的副本。

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
