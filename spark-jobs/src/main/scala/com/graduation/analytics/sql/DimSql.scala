package com.graduation.analytics.sql

import com.graduation.analytics.warehouse.WarehouseNamespace

/**
 * 维度快照 SQL（§11.2 DimensionBuildJob）：
 * ODS 用户/商品事件 → dim_user / dim_product 每日全量快照。
 * - 来源：ODS（解析后的 Landing），禁止 Spark 直连商城数据库；
 * - 每日全量快照（SCD 简化：每天覆盖最新画像）；
 * - 每次更新保留生效日期（dt 分区）与来源批次（ingest_batch_id 最新值）。
 */
object DimSql {

  /** dim_user：每个 user_id 取最近一次用户事件（event_time 最新）生成全量快照 */
  def userSnapshot(ns: WarehouseNamespace, dt: String): String =
    s"""
       |INSERT OVERWRITE TABLE ${ns.dim}.dim_user PARTITION(dt = '$dt')
       |SELECT
       |  ${IdCodec.toBIGINT("u.payload_user_id")} AS user_id,
       |  u.payload_age_group AS age_group,
       |  u.payload_city_level AS city_level,
       |  u.payload_member_level AS member_level,
       |  SUBSTR(u.event_time, 1, 10) AS register_date,
       |  TO_TIMESTAMP(u.event_time) AS register_time,
       |  u.ingest_batch_id AS source_batch_id
       |FROM (
       |  SELECT *,
       |         ROW_NUMBER() OVER (PARTITION BY payload_user_id ORDER BY event_time DESC) AS rn
       |  FROM ${ns.ods}.ods_user_event
       |  WHERE dt = '$dt'
       |    AND schema_version = '1.0'
       |    AND payload_user_id IS NOT NULL
       |) u
       |WHERE u.rn = 1
       |""".stripMargin

  /** dim_product：每个 product_id 取最近一次商品建档/变更事件生成全量快照 */
  def productSnapshot(ns: WarehouseNamespace, dt: String): String =
    s"""
       |INSERT OVERWRITE TABLE ${ns.dim}.dim_product PARTITION(dt = '$dt')
       |SELECT
       |  ${IdCodec.toBIGINT("p.payload_product_id")} AS product_id,
       |  COALESCE(p.payload_product_name, 'UNKNOWN') AS product_name,
       |  CASE WHEN p.payload_category_id IS NULL OR p.payload_category_id = ''
       |       THEN -1 ELSE ${IdCodec.toBIGINT("p.payload_category_id")} END AS category_id,
       |  COALESCE(p.payload_category_name, 'UNKNOWN') AS category_name,
       |  CASE WHEN p.payload_parent_category_id IS NULL OR p.payload_parent_category_id = ''
       |       THEN -1 ELSE ${IdCodec.toBIGINT("p.payload_parent_category_id")} END AS parent_category_id,
       |  COALESCE(p.payload_parent_category_name, 'UNKNOWN') AS parent_category_name,
       |  CASE WHEN p.payload_brand_id IS NULL OR p.payload_brand_id = ''
       |       THEN -1 ELSE ${IdCodec.toBIGINT("p.payload_brand_id")} END AS brand_id,
       |  p.payload_price AS price,
       |  p.payload_cost AS cost,
       |  COALESCE(p.payload_status, 'active') AS status,
       |  p.ingest_batch_id AS source_batch_id
       |FROM (
       |  SELECT *,
       |         ROW_NUMBER() OVER (PARTITION BY payload_product_id ORDER BY event_time DESC) AS rn
       |  FROM ${ns.ods}.ods_product_event
       |  WHERE dt = '$dt'
       |    AND schema_version = '1.0'
       |    AND payload_product_id IS NOT NULL
       |    AND event_type IN ('product_created', 'product_updated')
       |) p
       |WHERE p.rn = 1
       |""".stripMargin
}