package com.graduation.analytics.sql

import com.graduation.analytics.warehouse.WarehouseNamespace

/**
 * 维度快照 SQL（§11.2 DimensionBuildJob）：
 * ODS 用户/商品事件 → dim_user / dim_product 每日全量快照。
 * - 来源：ODS（解析后的 Landing），禁止 Spark 直连商城数据库；
 * - 每日全量快照（SCD 简化：每天覆盖最新画像）；
 * - 每次更新保留生效日期（dt 分区）与来源批次（ingest_batch_id 最新值）。
 *
 * P2-03（代理键，裁决 D-083…D-092）：**只加不改**——
 *  - 旧列 `*_id`（`IdCodec` 旧口径，含 `-1` 哨兵）逐字保留，下游 DWS/ADS 不受影响（D-094）；
 *  - 新增列 `<entity>_key`（契约口径代理键，算法唯一所有者 `SurrogateKey`）**追加在列尾**：
 *    `INSERT OVERWRITE` 是**按位置**对齐的，故新列表达式必须排在 SELECT 列表末尾。
 *  - 两列语义必须显式区分：`*_id` = 旧口径编码（含哨兵、已被 `IdCodec` 破坏源命名空间，
 *    `U00000001` 与 `O00000001` 都折叠成 1）；`*_key` = 契约代理键（无哨兵、跨源不合并）。
 */
object DimSql {

  /**
   * dim_user：每个 user_id 取最近一次用户事件（event_time 最新）生成全量快照。
   *
   * 追加列：`user_key`（= `SurrogateKey` 对**原始** `u.payload_user_id` 的派生，
   * 源编码取本行 `source_system`，即 D-056 参数通道注入值）。
   */
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
       |  u.ingest_batch_id AS source_batch_id,
       |  ${SurrogateKey.toBIGINT("u.source_system", "user", "u.payload_user_id")} AS user_key
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

  /**
   * dim_product：每个 product_id 取最近一次商品建档/变更事件生成全量快照。
   *
   * 追加列（4 个，全部在列尾）：`product_key` / `category_key` / `parent_category_key` / `brand_key`。
   * 类目与品牌键取**原始** `payload_*_id`（A2）；空/缺 ⇒ **NULL 键**（D-087），
   * 与旧列 `category_id` 等的 `-1` 哨兵**故意不同**（D-094 要求的「双列并存」）。
   */
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
       |  p.ingest_batch_id AS source_batch_id,
       |  ${SurrogateKey.toBIGINT("p.source_system", "product", "p.payload_product_id")} AS product_key,
       |  ${SurrogateKey.toBIGINT("p.source_system", "category", "p.payload_category_id")} AS category_key,
       |  ${SurrogateKey.toBIGINT("p.source_system", "category", "p.payload_parent_category_id")} AS parent_category_key,
       |  ${SurrogateKey.toBIGINT("p.source_system", "brand", "p.payload_brand_id")} AS brand_key
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

  // P2-03 新增列的列清单已移至算法所有者 `SurrogateKey.KeyColumns`
  // （建表 DDL、逐列对账、测试三处共用一份，避免「模板一处、建表一处」漂移）。
}
