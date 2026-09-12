package com.graduation.analytics.sql

import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.types.{
  ArrayType, DecimalType, StringType, StructField, StructType
}

/**
 * ODS 装载 SQL（§10.1/§10.2 全主题）：
 * 显式 Schema 事件视图（EventOdsLoadJob 用 EventLandingSchema 读取后注册）
 * → ns.ods 四主题表（user/product/behavior/trade）。
 * 模板函数返回 SQL 字符串，便于单元测试关键子句；真实布局由 EventOdsLoadJob 执行。
 *
 * 设计约定（§10.2）：
 *  - 显式 StructType（EventLandingSchema），视图 schema 固定，不依赖 json 自动推断
 *    （因此模板 FROM 视图而非 json.`path`，避免推断缺失缺口字段）；
 *  - 校验 schema_version='1.0'、event_id/event_type/event_time 非空；
 *  - 未知版本/非法行 → 隔离统计（rejected，§10.2 步骤 5 计数，页面可展示）；
 *  - 按 dt（yyyyMMdd）/hour（HH）分区；INSERT OVERWRITE 幂等，重跑不重复；
 *  - 保留 ingest_batch_id（mandatory 由作业注入）。
 *
 * **P2-01 / ODS v2 口径（D-052…D-058）**：
 *  - 每个模板的 SELECT 列表**逐位对齐**目标表的数据列（`OdsV2Columns.dataColumns`），
 *    Hive/Spark 的 `INSERT … SELECT` 按位置匹配，位置错不报错只写错数据 ⇒ 列映射由
 *    {@link odsSelect} 一处产出，不再在四条模板里各抄一份；
 *  - `source_system` **必须**由平台参数通道注入（D-056），**不再**信任行内值；
 *    行内原值原样落到 `raw_source_system`；
 *  - `'landing' AS source_file` 已删除（D-057）：`source_file` 与 `landing_file` **同源**，
 *    都取 `_metadata.file_path`（真实落地文件），不再有常量占位；
 *  - 新增 `payload_json` / `payload_hash`：来自 {@link JsonObjectSlicer} 的**原样切片**，
 *    UDF 由 `EventOdsLoadJob` 注册（`OdsLoadSql.UDF_SLICE_PAYLOAD` / `UDF_SHA256_HEX`）。
 */
object OdsLoadSql {

  /** 作业注册的显式视图名（EventOdsLoadJob 创建） */
  val LANDING_VIEW = "landing_valid"

  // ── 原样切片 UDF 名（EventOdsLoadJob 注册；模板里按名引用）──────────────────
  val UDF_SLICE_PAYLOAD = "p2_slice_payload"
  val UDF_SHA256_HEX = "p2_sha256_hex"

  /** 视图中的原样保真辅助列（由 EventOdsLoadJob 从 `_metadata.file_path` / 原始行填） */
  val ColRawLine = "raw_line"
  val ColLandingFile = "landing_file"

  /** 视图里「载荷原文」列：一级解析把 payload 取成字符串，别名到这个列名 */
  val ColPayloadText = "landing_payload_text"

  /** 视图里「载荷结构体」列：二级解析（文本 → v1 payload 结构体）的产物 */
  val ColPayload = "payload"

  /** 注入参数名（平台参数通道 `--k=v`，见 `JobCommandBuilder` 的 extra 通道） */
  val ArgSourceSystem = "sourceSystem"

  /**
   * 视图里 `payload` 的显式 StructType。
   *
   * 与 v1 `landingSchema.payload` **逐字一致**（同一个对象），保证 DWD/DIM 投影口径零变化。
   *
   * **`lazy` 是必需的，不是风格选择**：`landingSchema` 在本对象里定义在下方，普通 `val` 会按
   * 声明顺序初始化 ⇒ 这里读到 `null`，类初始化抛 `ExceptionInInitializerError`
   * （实测：`OdsV2SqlContractSpec` 直接 ABORT，`Cannot invoke "StructType.fields()" because … is null`）。
   */
  lazy val landingPayloadStruct: StructType =
    landingSchema.fields.find(_.name == ColPayload)
      .getOrElse(throw new IllegalStateException("landingSchema 缺 payload 字段"))
      .dataType.asInstanceOf[StructType]
  /** `source_system` 注入值的校验 + SQL 字面量渲染（含 `'` 转义） */
  def sourceSystemLiteral(sourceSystem: String): String = {
    if (sourceSystem == null || sourceSystem.trim.isEmpty) {
      throw new IllegalArgumentException(
        s"source_system 注入值为空：必须由平台参数通道 --$ArgSourceSystem=<source_code> 注入（D-056）")
    }
    "'" + sourceSystem.replace("'", "''") + "'"
  }

  /**
   * v1 信封段（前 7 列）：四张表**共享**，因此这里是唯一一份定义。
   * 顺序即物理列序（`OdsV2Columns` 的 v1 段），改这里等于改所有表。
   */
  private def envelopeSelect(sourceSystemLiteralValue: String): Seq[(String, String)] = Seq(
    "event_id" -> "event_id",
    "event_type" -> "event_type",
    "event_time" -> "event_time",
    "ingest_time" -> "ingest_time",
    "source_system" -> sourceSystemLiteralValue,
    "schema_version" -> "schema_version",
    "trace_id" -> "trace_id"
  )

  /** v2 新增段（5 列）：追加在 v1 列之后（D-059） */
  private def v2NewSelect: Seq[(String, String)] = Seq(
    "raw_event_type" -> "event_type",
    "raw_source_system" -> "source_system",
    "landing_file" -> ColLandingFile,
    "payload_json" -> s"$UDF_SLICE_PAYLOAD($ColRawLine)",
    "payload_hash" -> s"$UDF_SHA256_HEX($ColRawLine)"
  )

  /** 全表 SELECT 列表（列名 → 表达式），**列序与 `OdsV2Columns.dataColumns` 必须逐位相同** */
  def odsSelect(table: String, sourceSystem: String): Seq[(String, String)] = {
    val envelope = envelopeSelect(sourceSystemLiteral(sourceSystem))
    val payload = table match {
      case "ods_user_event" => Seq(
        "payload_user_id" -> s"$ColPayload.user_id",
        "payload_age_group" -> s"$ColPayload.age_group",
        "payload_city_level" -> s"$ColPayload.city_level",
        "payload_member_level" -> s"$ColPayload.member_level",
        "payload_register_time" -> s"$ColPayload.register_time")
      case "ods_product_event" => Seq(
        "payload_product_id" -> s"$ColPayload.product_id",
        "payload_product_name" -> s"$ColPayload.product_name",
        "payload_category_id" -> s"$ColPayload.category_id",
        "payload_category_name" -> s"$ColPayload.category_name",
        "payload_parent_category_id" -> s"$ColPayload.parent_category_id",
        "payload_parent_category_name" -> s"$ColPayload.parent_category_name",
        "payload_brand_id" -> s"$ColPayload.brand_id",
        "payload_price" -> decimalCase(s"$ColPayload.price"),
        "payload_cost" -> decimalCase(s"$ColPayload.cost"),
        "payload_status" -> s"$ColPayload.status")
      case "ods_behavior_event" => Seq(
        "payload_user_id" -> s"$ColPayload.user_id",
        "payload_product_id" -> s"$ColPayload.product_id",
        "payload_session_id" -> s"$ColPayload.session_id",
        "payload_behavior_type" -> s"$ColPayload.behavior_type",
        "payload_channel" -> s"$ColPayload.channel")
      case "ods_trade_event" => Seq(
        "payload_order_id" -> s"$ColPayload.order_id",
        "payload_user_id" -> s"$ColPayload.user_id",
        "payload_payment_id" -> s"$ColPayload.payment_id",
        "payload_refund_id" -> s"$ColPayload.refund_id",
        "payload_product_id" -> s"$ColPayload.product_id",
        "payload_amount" -> decimalCase(s"$ColPayload.amount"),
        "payload_total_amount" -> decimalCase(s"$ColPayload.total_amount"),
        "payload_status" -> s"$ColPayload.status",
        "payload_reason" -> s"$ColPayload.reason",
        "payload_items" -> s"$ColPayload.items")
      case other => throw new IllegalArgumentException(s"未登记的 ODS 表：$other")
    }
    val audit = Seq(
      "source_file" -> ColLandingFile, // D-057：与 landing_file 同源的真实落地文件
      "ingest_batch_id" -> BatchIdPlaceholder)
    envelope ++ payload ++ audit ++ v2NewSelect
  }

  /** `ingest_batch_id` 在 SELECT 里的占位（渲染时替换为字面量） */
  private val BatchIdPlaceholder = "__INGEST_BATCH_ID__"

  /** v1 口径原样保留：空串/NULL → NULL，否则 CAST 成 DECIMAL(18,2) */
  private def decimalCase(expr: String): String =
    s"CASE WHEN $expr IS NULL OR $expr = '' THEN NULL ELSE CAST($expr AS DECIMAL(18,2)) END"

  /** SELECT 列表 → SQL 文本（`expr AS name`，逐位对齐目标表） */
  private def renderSelect(select: Seq[(String, String)], batchId: Long): String =
    select.map { case (name, expr) =>
      val e = expr.replace(BatchIdPlaceholder, batchId.toString)
      if (e == name) s"  $e" else s"  $e AS $name"
    }.mkString(",\n")

  /** 分区派生表达式（四条模板共享） */
  private def partitionSelect: String =
    "  REGEXP_REPLACE(SUBSTR(event_time, 1, 10), '-', '') AS dt,\n" +
      "  SUBSTR(event_time, 12, 2) AS hour"

  private def insert(ns: WarehouseNamespace, table: String, sourceSystem: String,
                     batchId: Long, whereClause: String): String =
    s"""
       |INSERT OVERWRITE TABLE ${ns.ods}.$table PARTITION (dt, hour)
       |SELECT
       |${renderSelect(odsSelect(table, sourceSystem), batchId)},
       |$partitionSelect
       |FROM $LANDING_VIEW
       |WHERE schema_version = '1.0'
       |  AND $whereClause
       |  AND event_id IS NOT NULL AND event_time IS NOT NULL
       |""".stripMargin

  /** 用户事件：显式视图 → ods_user_event（§10.1 用户主题核心字段） */
  def userFromLanding(ns: WarehouseNamespace, sourceSystem: String, batchId: Long): String =
    insert(ns, "ods_user_event", sourceSystem, batchId, "event_type IN ('user_registered')")

  /** 商品事件：显式视图 → ods_product_event（§10.1 商品主题，含库存事件） */
  def productFromLanding(ns: WarehouseNamespace, sourceSystem: String, batchId: Long): String =
    insert(ns, "ods_product_event", sourceSystem, batchId,
      "event_type IN ('product_created', 'product_updated',\n" +
        "                     'stock_reserved', 'stock_released', 'stock_changed')")

  /** 行为事件：显式视图 → ods_behavior_event（§10.1 行为主题） */
  def behaviorFromLanding(ns: WarehouseNamespace, sourceSystem: String, batchId: Long): String =
    insert(ns, "ods_behavior_event", sourceSystem, batchId, "event_type = 'behavior'")

  /** 交易事件：显式视图 → ods_trade_event（§10.1 交易主题，订单/支付/退款） */
  def tradeFromLanding(ns: WarehouseNamespace, sourceSystem: String, batchId: Long): String =
    insert(ns, "ods_trade_event", sourceSystem, batchId,
      "event_type IN ('order_created', 'order_cancelled', 'order_paid',\n" +
        "                     'refund_created', 'refund_completed')")

  /** Landing 事件显式 Schema（§10.2 步骤 2：不自动推断生产 Schema） */
  val landingSchema: StructType = StructType(Seq(
    StructField("event_id", StringType, nullable = false),
    StructField("event_type", StringType, nullable = false),
    StructField("event_time", StringType, nullable = false),
    StructField("ingest_time", StringType, nullable = true),
    StructField("source_system", StringType, nullable = true),
    StructField("schema_version", StringType, nullable = true),
    StructField("trace_id", StringType, nullable = true),
    StructField("payload", StructType(Seq(
      // 用户画像
      StructField("user_id", StringType, nullable = true),
      StructField("age_group", StringType, nullable = true),
      StructField("city_level", StringType, nullable = true),
      StructField("member_level", StringType, nullable = true),
      StructField("register_time", StringType, nullable = true),
      // 商品
      StructField("product_id", StringType, nullable = true),
      StructField("product_name", StringType, nullable = true),
      StructField("category_id", StringType, nullable = true),
      StructField("category_name", StringType, nullable = true),
      StructField("parent_category_id", StringType, nullable = true),
      StructField("parent_category_name", StringType, nullable = true),
      StructField("brand_id", StringType, nullable = true),
      StructField("price", StringType, nullable = true),
      StructField("cost", StringType, nullable = true),
      StructField("status", StringType, nullable = true),
      StructField("available", StringType, nullable = true),
      StructField("reserved", StringType, nullable = true),
      StructField("change_type", StringType, nullable = true),
      // 行为
      StructField("session_id", StringType, nullable = true),
      StructField("behavior_type", StringType, nullable = true),
      StructField("channel", StringType, nullable = true),
      // 交易
      StructField("order_id", StringType, nullable = true),
      StructField("payment_id", StringType, nullable = true),
      StructField("refund_id", StringType, nullable = true),
      StructField("amount", StringType, nullable = true),
      StructField("total_amount", StringType, nullable = true),
      StructField("pay_amount", StringType, nullable = true),
      StructField("items", StringType, nullable = true),
      StructField("reason", StringType, nullable = true),
      StructField("paid_at", StringType, nullable = true),
      StructField("completed_at", StringType, nullable = true)
    )), nullable = true)
  ))

  /** 合法事件类型 → ODS 目标表映射（§10.1，与 docs/contracts/event-contract.md §3 12 类对齐） */
  val eventTypeToTable: Map[String, String] = Map(
    "user_registered" -> "ods_user_event",
    "product_created" -> "ods_product_event",
    "product_updated" -> "ods_product_event",
    "stock_reserved" -> "ods_product_event",
    "stock_released" -> "ods_product_event",
    "stock_changed" -> "ods_product_event",
    "behavior" -> "ods_behavior_event",
    "order_created" -> "ods_trade_event",
    "order_cancelled" -> "ods_trade_event",
    "order_paid" -> "ods_trade_event",
    "refund_created" -> "ods_trade_event",
    "refund_completed" -> "ods_trade_event"
  )

  /** 版本/必填字段隔离行（未知 schema_version 或缺失事件主键，§10.2 步骤 5） */
  def rejectedSelect(): String =
    s"""
       |SELECT event_id, event_type, schema_version, event_time, trace_id,
       |       'BAD_VERSION_OR_KEY' AS reject_reason
       |FROM $LANDING_VIEW
       |WHERE schema_version <> '1.0' OR schema_version IS NULL
       |   OR event_id IS NULL OR event_type IS NULL OR event_time IS NULL
       |""".stripMargin
}

/** 显式 Landing Schema 别名（保持模板与作业共用一份定义） */
object EventLandingSchema {
  val structType: StructType = OdsLoadSql.landingSchema
  val itemsArrayType: ArrayType = ArrayType(StructType(Seq(
    StructField("product_id", StringType, nullable = true),
    StructField("product_name", StringType, nullable = true),
    StructField("quantity", StringType, nullable = true),
    StructField("unit_price", StringType, nullable = true),
    StructField("discount", StringType, nullable = true),
    StructField("amount", StringType, nullable = true)
  )), containsNull = true)

  val itemsDecimal: DecimalType = DecimalType(18, 2)
}