package com.graduation.analytics.sql

/**
 * ODS 装载 SQL（§7.1 输入：Landing JSON 目录；输出：dw_ods 主题表）。
 * 模板函数返回 SQL 字符串，便于单元测试关键子句；真实布局由作业执行。
 */
object OdsLoadSql {

  /** 行为事件：Landing JSON 目录 → ods_behavior_event（版本过滤；dt/hour 由 event_time 派生） */
  def behaviorFromLanding(landingDir: String): String =
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
       |  'landing' AS source_file, 0 AS ingest_batch_id,
       |  REGEXP_REPLACE(SUBSTR(event_time, 1, 10), '-', '') AS dt,
       |  SUBSTR(event_time, 12, 2) AS hour
       |FROM json.`$landingDir`
       |WHERE schema_version = '1.0'
       |  AND event_type = 'behavior'
       |  AND event_time IS NOT NULL
       |""".stripMargin

  /** 版本隔离行（未知 schema_version，后续转换器适配；入隔离目录由采集侧承接） */
  def unknownVersionSelect(landingDir: String): String =
    s"""
       |SELECT event_id, event_type, schema_version, trace_id
       |FROM json.`$landingDir`
       |WHERE schema_version <> '1.0' OR schema_version IS NULL
       |""".stripMargin
}