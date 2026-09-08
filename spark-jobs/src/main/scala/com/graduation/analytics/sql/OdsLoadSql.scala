package com.graduation.analytics.sql

import org.apache.spark.sql.types.{
  ArrayType, DecimalType, StringType, StructField, StructType
}

/**
 * ODS 装载 SQL（§10.1/§10.2 全主题）：
 * 显式 Schema 事件视图（EventOdsLoadJob 用 EventLandingSchema 读取后注册）
 * → dw_ods 四主题表（user/product/behavior/trade）。
 * 模板函数返回 SQL 字符串，便于单元测试关键子句；真实布局由 EventOdsLoadJob 执行。
 *
 * 设计约定（§10.2）：
 *  - 显式 StructType（EventLandingSchema），视图 schema 固定，不依赖 json 自动推断
 *    （因此模板 FROM 视图而非 json.`path`，避免推断缺失缺口字段）；
 *  - 校验 schema_version='1.0'、event_id/event_type/event_time 非空；
 *  - 未知版本/非法行 → 隔离统计（rejected，§10.2 步骤 5 计数，页面可展示）；
 *  - 按 dt（yyyyMMdd）/hour（HH）分区；INSERT OVERWRITE 幂等，重跑不重复；
 *  - 保留 source_file（'landing'）/ingest_batch_id（mandatory 由作业注入）。
 */
object OdsLoadSql {

  /** 作业注册的显式视图名（EventOdsLoadJob 创建） */
  val LANDING_VIEW = "landing_valid"

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

  /** 用户事件：显式视图 → ods_user_event（§10.1 用户主题核心字段） */
  def userFromLanding(batchId: Long): String =
    s"""
       |INSERT OVERWRITE TABLE dw_ods.ods_user_event PARTITION (dt, hour)
       |SELECT
       |  event_id, event_type, event_time, ingest_time, source_system,
       |  schema_version, trace_id,
       |  payload.user_id AS payload_user_id,
       |  payload.age_group AS payload_age_group,
       |  payload.city_level AS payload_city_level,
       |  payload.member_level AS payload_member_level,
       |  payload.register_time AS payload_register_time,
       |  'landing' AS source_file, $batchId AS ingest_batch_id,
       |  REGEXP_REPLACE(SUBSTR(event_time, 1, 10), '-', '') AS dt,
       |  SUBSTR(event_time, 12, 2) AS hour
       |FROM $LANDING_VIEW
       |WHERE schema_version = '1.0'
       |  AND event_type IN ('user_registered')
       |  AND event_id IS NOT NULL AND event_time IS NOT NULL
       |""".stripMargin

  /** 商品事件：显式视图 → ods_product_event（§10.1 商品主题，含库存事件） */
  def productFromLanding(batchId: Long): String =
    s"""
       |INSERT OVERWRITE TABLE dw_ods.ods_product_event PARTITION (dt, hour)
       |SELECT
       |  event_id, event_type, event_time, ingest_time, source_system,
       |  schema_version, trace_id,
       |  payload.product_id AS payload_product_id,
       |  payload.product_name AS payload_product_name,
       |  payload.category_id AS payload_category_id,
       |  payload.category_name AS payload_category_name,
       |  payload.parent_category_id AS payload_parent_category_id,
       |  payload.parent_category_name AS payload_parent_category_name,
       |  payload.brand_id AS payload_brand_id,
       |  CASE WHEN payload.price IS NULL OR payload.price = ''
       |       THEN NULL ELSE CAST(payload.price AS DECIMAL(18,2)) END AS payload_price,
       |  CASE WHEN payload.cost IS NULL OR payload.cost = ''
       |       THEN NULL ELSE CAST(payload.cost AS DECIMAL(18,2)) END AS payload_cost,
       |  payload.status AS payload_status,
       |  'landing' AS source_file, $batchId AS ingest_batch_id,
       |  REGEXP_REPLACE(SUBSTR(event_time, 1, 10), '-', '') AS dt,
       |  SUBSTR(event_time, 12, 2) AS hour
       |FROM $LANDING_VIEW
       |WHERE schema_version = '1.0'
       |  AND event_type IN ('product_created', 'product_updated',
       |                     'stock_reserved', 'stock_released', 'stock_changed')
       |  AND event_id IS NOT NULL AND event_time IS NOT NULL
       |""".stripMargin

  /** 行为事件：显式视图 → ods_behavior_event（§10.1 行为主题） */
  def behaviorFromLanding(batchId: Long): String =
    s"""
       |INSERT OVERWRITE TABLE dw_ods.ods_behavior_event PARTITION (dt, hour)
       |SELECT
       |  event_id, event_type, event_time, ingest_time, source_system,
       |  schema_version, trace_id,
       |  payload.user_id AS payload_user_id,
       |  payload.product_id AS payload_product_id,
       |  payload.session_id AS payload_session_id,
       |  payload.behavior_type AS payload_behavior_type,
       |  payload.channel AS payload_channel,
       |  'landing' AS source_file, $batchId AS ingest_batch_id,
       |  REGEXP_REPLACE(SUBSTR(event_time, 1, 10), '-', '') AS dt,
       |  SUBSTR(event_time, 12, 2) AS hour
       |FROM $LANDING_VIEW
       |WHERE schema_version = '1.0'
       |  AND event_type = 'behavior'
       |  AND event_id IS NOT NULL AND event_time IS NOT NULL
       |""".stripMargin

  /** 交易事件：显式视图 → ods_trade_event（§10.1 交易主题，订单/支付/退款） */
  def tradeFromLanding(batchId: Long): String =
    s"""
       |INSERT OVERWRITE TABLE dw_ods.ods_trade_event PARTITION (dt, hour)
       |SELECT
       |  event_id, event_type, event_time, ingest_time, source_system,
       |  schema_version, trace_id,
       |  payload.order_id AS payload_order_id,
       |  payload.user_id AS payload_user_id,
       |  payload.payment_id AS payload_payment_id,
       |  payload.refund_id AS payload_refund_id,
       |  payload.product_id AS payload_product_id,
       |  CASE WHEN payload.amount IS NULL OR payload.amount = ''
       |       THEN NULL ELSE CAST(payload.amount AS DECIMAL(18,2)) END AS payload_amount,
       |  CASE WHEN payload.total_amount IS NULL OR payload.total_amount = ''
       |       THEN NULL ELSE CAST(payload.total_amount AS DECIMAL(18,2)) END AS payload_total_amount,
       |  payload.status AS payload_status,
       |  payload.reason AS payload_reason,
       |  payload.items AS payload_items,
       |  'landing' AS source_file, $batchId AS ingest_batch_id,
       |  REGEXP_REPLACE(SUBSTR(event_time, 1, 10), '-', '') AS dt,
       |  SUBSTR(event_time, 12, 2) AS hour
       |FROM $LANDING_VIEW
       |WHERE schema_version = '1.0'
       |  AND event_type IN ('order_created', 'order_cancelled', 'order_paid',
       |                     'refund_created', 'refund_completed')
       |  AND event_id IS NOT NULL AND event_time IS NOT NULL
       |""".stripMargin

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