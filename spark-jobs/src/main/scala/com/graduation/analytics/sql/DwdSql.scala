package com.graduation.analytics.sql

import com.graduation.analytics.warehouse.WarehouseNamespace

/**
 * DWD 清洗 SQL（§7.1/§7.2）：event_id 去重、枚举/空值过滤、时间标准化、reject 记录。
 *
 * P1-04：库名不再写字面量，一律由 {@link WarehouseNamespace} 派生（首个参数）。
 */
object DwdSql {

  /** 行为清洗：回到正常行（row_number 去重 + 过滤非法枚举/空 user_id）；id 归一化见 IdCodec（DEF-05） */
  def behaviorClean(ns: WarehouseNamespace, dt: String): String =
    s"""
       |INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '$dt')
       |SELECT
       |  rn.event_id AS behavior_id,
       |  ${IdCodec.toBIGINT("rn.payload_user_id")} AS user_id,
       |  ${IdCodec.toBIGINT("rn.payload_product_id")} AS product_id,
       |  COALESCE(p.category_id, -1) AS category_id,
       |  rn.payload_behavior_type AS behavior_type,
       |  FROM_UTC_TIMESTAMP(FROM_UNIXTIME(UNIX_TIMESTAMP(rn.event_time)), 'Asia/Shanghai') AS event_time,
       |  SUBSTR(rn.event_time, 1, 10) AS event_date,
       |  CAST(SUBSTR(rn.event_time, 12, 2) AS INT) AS event_hour,
       |  u.city_level AS city_level,
       |  rn.payload_channel AS channel,
       |  rn.payload_session_id AS session_id,
       |  rn.ingest_batch_id AS source_batch_id
       |FROM (
       |  SELECT *,
       |         ROW_NUMBER() OVER (PARTITION BY event_id ORDER BY ingest_time) AS rn
       |  FROM ${ns.ods}.ods_behavior_event
       |  WHERE dt = '$dt'
       |    AND schema_version = '1.0'
       |) rn
       |LEFT JOIN ${ns.dim}.dim_user u ON u.user_id = ${IdCodec.toBIGINT("rn.payload_user_id")} AND u.dt = '$dt'
       |LEFT JOIN ${ns.dim}.dim_product p ON p.product_id = ${IdCodec.toBIGINT("rn.payload_product_id")} AND p.dt = '$dt'
       |WHERE rn.rn = 1                       -- event_id 去重
       |  AND rn.payload_user_id IS NOT NULL
       |  AND rn.payload_product_id IS NOT NULL
       |  AND rn.payload_behavior_type IN ('view','favorite','cart_add','cart_remove','search')
       |""".stripMargin

  /** 重复事件（去重淘汰行）进入拒绝记录 */
  def duplicateReject(ns: WarehouseNamespace, dt: String): String =
    s"""
       |INSERT OVERWRITE TABLE ${ns.dwd}.dwd_reject_record PARTITION(dt = '$dt')
       |SELECT event_id AS reject_id, 'ods_behavior_event' AS source_table,
       |       'DUPLICATE_EVENT' AS reject_reason, NULL AS raw_payload,
       |       CURRENT_TIMESTAMP() AS reject_time
       |FROM (
       |  SELECT event_id, COUNT(*) AS c
       |  FROM ${ns.ods}.ods_behavior_event WHERE dt = '$dt'
       |  GROUP BY event_id HAVING COUNT(*) > 1
       |) t
       |""".stripMargin
}