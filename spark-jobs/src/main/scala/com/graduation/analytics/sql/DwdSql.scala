package com.graduation.analytics.sql

import com.graduation.analytics.warehouse.WarehouseNamespace

/**
 * DWD 清洗 SQL（§7.1/§7.2）：event_id 去重、枚举/空值过滤、时间标准化、reject 记录。
 *
 * P1-04：库名不再写字面量，一律由 {@link WarehouseNamespace} 派生（首个参数）。
 *
 * P2-03（代理键，裁决 D-083…D-092）：**只加不改**——旧列 `user_id`/`product_id`/`category_id`
 * 与其 `-1` 哨兵逐字保留（D-094），新增 `<entity>_key` 列**追加在 SELECT 列表末尾**
 * （`INSERT OVERWRITE` 按位置对齐）。**A5：JOIN 谓词本轮保持旧口径不变**（改 JOIN 属 P2-04）。
 *
 * P2-04-a（裁决 D-121/D-122/D-124）：**别名映射说明** —— 契约逻辑名 `source_instance_id`
 * ⇔ 物理列 `source_system`（注入值，per-source 下发，唯一所有者为 `OdsLoadSql`）。
 * 去重键由 `event_id` **单键扩为 (source_system, event_id) 复合键**（只扩键）：
 * 源身份只在**去重键输入**位置引用 ODS 既有列，**不新增任何列、不动 DDL**（D-122「不新增列」＋
 * 裁决「不授权任何 DDL」）；`event_id`/`behavior_id` 的生成、归一、编码**不得**随本轮改动，
 * 行内原值 `raw_source_system` **不得**充当去重键输入（D-122 L35）。
 */
object DwdSql {

  /**
   * 行为清洗：回到正常行（row_number 去重 + 过滤非法枚举/空 user_id）；id 归一化见 IdCodec（DEF-05）。
   *
   * P2-03 追加列：`user_key` / `product_key` / `category_key`。
   *  - `user_key`/`product_key` 取**原始** `rn.payload_user_id` / `rn.payload_product_id`（A2，不得由
   *    `IdCodec` 的结果再算——那条路已把 `U00000001`/`O00000001` 折叠成 1，命名空间永久丢失）；
   *  - `category_key` 取**维表**的 `p.category_key`（LEFT JOIN 未命中时随之为 NULL），
   *    **刻意不取** `COALESCE(p.category_id, -1)`——旧列保留哨兵，新键列不许有哨兵（D-087/D-094）。
   */
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
       |  rn.ingest_batch_id AS source_batch_id,
       |  ${SurrogateKey.toBIGINT("rn.source_system", "user", "rn.payload_user_id")} AS user_key,
       |  ${SurrogateKey.toBIGINT("rn.source_system", "product", "rn.payload_product_id")} AS product_key,
       |  p.category_key AS category_key
       |FROM (
       |  SELECT *,
       |         ROW_NUMBER() OVER (PARTITION BY source_system, event_id ORDER BY ingest_time) AS rn
       |  FROM ${ns.ods}.ods_behavior_event
       |  WHERE dt = '$dt'
       |    AND schema_version = '1.0'
       |) rn
       |LEFT JOIN ${ns.dim}.dim_user u ON u.user_id = ${IdCodec.toBIGINT("rn.payload_user_id")} AND u.dt = '$dt'
       |LEFT JOIN ${ns.dim}.dim_product p ON p.product_id = ${IdCodec.toBIGINT("rn.payload_product_id")} AND p.dt = '$dt'
       |WHERE rn.rn = 1                       -- (source_system, event_id) 复合去重（P2-04-a / D-121）
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
       |  GROUP BY source_system, event_id HAVING COUNT(*) > 1
       |) t
       |""".stripMargin
}