package com.graduation.analytics.sql

/**
 * ODS 装载 SQL（§7.1 输入：Landing JSON 目录；输出：dw_ods 主题表）。
 * 模板函数返回 SQL 字符串，便于单元测试关键子句；真实布局由作业执行。
 */
object OdsLoadSql {

  /** 行为事件：Landing JSON → ods_behavior_event（版本过滤：未知版本进隔离分支） */
  def behaviorFromLanding(landingDir: String, dt: String, hour: String): String =
    s"""
       |SELECT
       |  event_id, event_type, event_time, ingest_time, source_system,
       |  schema_version, trace_id,
       |  payload.user_id AS payload_user_id,
       |  payload.product_id AS payload_product_id,
       |  payload.session_id AS payload_session_id,
       |  payload.behavior_type AS payload_behavior_type,
       |  payload.channel AS payload_channel,
       |  source_file, ingest_batch_id
       |FROM (SELECT *, '$landingDir' AS source_file, CAST('0' AS BIGINT) AS ingest_batch_id
       |      FROM json.`$landingDir/runtime.json`) t
       |WHERE schema_version = '1.0'
       |  AND event_type = 'behavior'
       |  AND dt = '$dt' AND hour = '$hour'
       |""".stripMargin

  /** 版本隔离行（未知 schema_version，后续转换器适配） */
  def unknownVersionSelect(landingDir: String): String =
    s"""
       |SELECT event_id, event_type, schema_version, trace_id
       |FROM json.`$landingDir/runtime.json`
       |WHERE schema_version <> '1.0' OR schema_version IS NULL
       |""".stripMargin
}