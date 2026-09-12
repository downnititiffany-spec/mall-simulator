-- E3 探针（只读）：定位「ods_user_event 的 v1 payload_* 列为 NULL」的根因
-- 只做 SELECT + 会话级 TEMPORARY VIEW，不写任何库表。
CREATE OR REPLACE TEMPORARY VIEW raw_text USING text OPTIONS (path 'file:///D:/Develop_code/GraduationProject/tests/golden-dataset/events');

-- Q1：一级解析（envelope）把 payload 对象取成 STRING 是否成功？
SELECT 'Q1_steps' AS chk,
       get_json_object(value, '$.payload') AS p_getjson,
       from_json(value, 'event_id STRING, landing_payload_text STRING').landing_payload_text AS p_env_twofield,
       from_json(value, 'event_id STRING, event_type STRING, event_time STRING, ingest_time STRING, source_system STRING, schema_version STRING, trace_id STRING, landing_payload_text STRING').landing_payload_text AS p_env_full,
       from_json(get_json_object(value, '$.payload'), 'user_id STRING, age_group STRING') AS p_struct_from_text,
       from_json(from_json(value, 'event_id STRING, landing_payload_text STRING').landing_payload_text, 'user_id STRING, age_group STRING') AS p_two_step
  FROM raw_text LIMIT 1;

-- Q2：表内 v1 payload 列 vs payload_json（同一行的两个独立读数）
SELECT 'Q2_table' AS chk, event_id AS id, payload_user_id AS ods_user_id, payload_age_group AS ods_age,
       substr(payload_json, 1, 40) AS pj_head
  FROM p201v2_ods.ods_user_event ORDER BY event_id LIMIT 2;
