-- E3 真链读回（只读；不建表、不写库，只建会话级 TEMPORARY VIEW）
-- 前置：p201v2（主）与 p201v2b（注入值探针）的 sci/odl 都已跑过。
--
-- 本文件的三条实测教训（都已按实测改写，别再回退）：
--  1) spark-sql `-f` 的语句分隔符是「**以 `;` 结尾的行**」。少一个 `;`，下一条语句会被并进上一条
--     ⇒ [PARSE_SYNTAX_ERROR] Syntax error at or near 'SELECT'。每条语句的 `;` 必须落在行尾。
--  2) 非交互输出的单元格分隔符是 **TAB**，且因 spark.sql.cli.print.header=true 会先打一行**表头**
--     （`chk<TAB>value`）；NULL 原样打印成 NULL。按管道符 `|` 解析会一条都读不到。
--  3) SELECT 列表里不要用标量子查询 `(SELECT …)`：拆成 UNION ALL 行（本文件已全部拆开）。
--
-- 源侧两个视图的分工：
--  * `raw_text`：落地区 55 行原文（原样，用于「任一源行能对账」类检查）。
--  * `src`：按 event_id 取**一行规范原文**（min(value)，env_fp 用 min_by 与它同源）。
--    存在该视图的原因是一条**实测的夹具属性**：golden 里有 event_id 出现两次的源行，
--    直接 ⋈ raw_text 会把同一 ODS 行配到两行源行上（B0 从 52 变 54）。A10～A14 把这个属性显式记下来。
--
-- 检查行约定：键必须以**大写字母**开头（harness 只认这个形状，避免把表头 col_name/event_id 误收），值里不含 TAB。

CREATE OR REPLACE TEMPORARY VIEW raw_text USING text OPTIONS (path 'file:///D:/Develop_code/GraduationProject/tests/golden-dataset/events');

CREATE OR REPLACE TEMPORARY VIEW src AS
SELECT s.event_id AS event_id, min(s.value) AS value, min_by(s.env_fp, s.value) AS env_fp
  FROM (
    SELECT get_json_object(value, '$.event_id') AS event_id,
           value,
           concat_ws('|', coalesce(get_json_object(value, '$.event_id'), '<null>'),
                          coalesce(get_json_object(value, '$.event_type'), '<null>'),
                          coalesce(get_json_object(value, '$.event_time'), '<null>'),
                          coalesce(get_json_object(value, '$.ingest_time'), '<null>'),
                          coalesce(get_json_object(value, '$.schema_version'), '<null>'),
                          coalesce(get_json_object(value, '$.trace_id'), '<null>')) AS env_fp
      FROM raw_text
     WHERE get_json_object(value, '$.event_id') IS NOT NULL
  ) s
 GROUP BY s.event_id;

-- 四张 ODS 表 ⋈ 规范源行：52 行 = 4 用户 + 14 商品 + 16 行为 + 18 交易。
CREATE OR REPLACE TEMPORARY VIEW landing_join AS
SELECT 'ods_user_event' AS tbl, o.event_id AS event_id, o.payload_json AS payload_json, o.payload_hash AS payload_hash, s.value AS raw_line
  FROM p201v2_ods.ods_user_event o JOIN src s ON s.event_id = o.event_id
UNION ALL
SELECT 'ods_product_event', o.event_id, o.payload_json, o.payload_hash, s.value
  FROM p201v2_ods.ods_product_event o JOIN src s ON s.event_id = o.event_id
UNION ALL
SELECT 'ods_behavior_event', o.event_id, o.payload_json, o.payload_hash, s.value
  FROM p201v2_ods.ods_behavior_event o JOIN src s ON s.event_id = o.event_id
UNION ALL
SELECT 'ods_trade_event', o.event_id, o.payload_json, o.payload_hash, s.value
  FROM p201v2_ods.ods_trade_event o JOIN src s ON s.event_id = o.event_id;

-- ── SHOW/DESCRIBE 结构读数（供 harness 解析，不进 chk 表）────────────────────────
SELECT 'SHOW_DATABASES_BEGIN' AS marker;
SHOW DATABASES;
SELECT 'SHOW_DATABASES_END' AS marker;

SELECT 'SHOW_TABLES_BEGIN' AS marker;
SHOW TABLES IN p201v2_ods;
SELECT 'SHOW_TABLES_END' AS marker;

SELECT 'DESCRIBE_RESULT_BEGIN' AS marker;
DESCRIBE p201v2_ods.ods_user_event;
SELECT 'DESCRIBE_RESULT_END' AS marker;

SELECT 'SHOW_CREATE_BEGIN' AS marker;
SHOW CREATE TABLE p201v2_ods.ods_user_event;
SELECT 'SHOW_CREATE_END' AS marker;

SELECT 'SHOW_PARTITIONS_BEGIN' AS marker;
SHOW PARTITIONS p201v2_ods.ods_user_event;
SELECT 'SHOW_PARTITIONS_END' AS marker;

-- ── A：行数 / 新增列非空 / 原样列取值 / 夹具属性 ────────────────────────────────
SELECT 'A1_total_accepted', CAST(count(*) AS STRING) FROM landing_join
UNION ALL
SELECT 'A4_newcol_nulls', CAST(count(*) AS STRING) FROM (
  SELECT raw_event_type, raw_source_system, landing_file, payload_json, payload_hash FROM p201v2_ods.ods_user_event
  UNION ALL SELECT raw_event_type, raw_source_system, landing_file, payload_json, payload_hash FROM p201v2_ods.ods_product_event
  UNION ALL SELECT raw_event_type, raw_source_system, landing_file, payload_json, payload_hash FROM p201v2_ods.ods_behavior_event
  UNION ALL SELECT raw_event_type, raw_source_system, landing_file, payload_json, payload_hash FROM p201v2_ods.ods_trade_event
) n WHERE n.raw_event_type IS NULL OR n.raw_source_system IS NULL OR n.landing_file IS NULL OR n.payload_json IS NULL OR n.payload_hash IS NULL
UNION ALL
SELECT 'A5_raw_event_type_mismatch', CAST(count(*) AS STRING) FROM (
  SELECT o.raw_event_type, s.value FROM p201v2_ods.ods_user_event o JOIN src s ON s.event_id = o.event_id
  UNION ALL SELECT o.raw_event_type, s.value FROM p201v2_ods.ods_product_event o JOIN src s ON s.event_id = o.event_id
  UNION ALL SELECT o.raw_event_type, s.value FROM p201v2_ods.ods_behavior_event o JOIN src s ON s.event_id = o.event_id
  UNION ALL SELECT o.raw_event_type, s.value FROM p201v2_ods.ods_trade_event o JOIN src s ON s.event_id = o.event_id
) r WHERE r.raw_event_type <> get_json_object(r.value, '$.event_type')
UNION ALL
SELECT 'A6_landing_file', min(landing_file) FROM p201v2_ods.ods_user_event
UNION ALL
SELECT 'A7_source_file_ne_landing_file', CAST(count(*) AS STRING) FROM p201v2_ods.ods_user_event WHERE source_file <> landing_file
UNION ALL
SELECT 'A10_src_lines', CAST(count(*) AS STRING) FROM raw_text
UNION ALL
SELECT 'A11_src_lines_with_event_id', CAST(count(*) AS STRING) FROM raw_text WHERE get_json_object(value, '$.event_id') IS NOT NULL
UNION ALL
SELECT 'A12_src_distinct_event_ids', CAST(count(*) AS STRING) FROM src
UNION ALL
SELECT 'A13_src_dup_event_id_lines', CAST(count(*) AS STRING) FROM (
  SELECT get_json_object(value, '$.event_id') AS event_id FROM raw_text
   WHERE get_json_object(value, '$.event_id') IS NOT NULL GROUP BY 1 HAVING count(*) > 1
) d
UNION ALL
SELECT 'A14_ods_dup_event_ids', CAST(count(*) AS STRING) FROM (
  SELECT event_id FROM p201v2_ods.ods_user_event
  UNION ALL SELECT event_id FROM p201v2_ods.ods_product_event
  UNION ALL SELECT event_id FROM p201v2_ods.ods_behavior_event
  UNION ALL SELECT event_id FROM p201v2_ods.ods_trade_event
) u GROUP BY u.event_id HAVING count(*) > 1;

-- ── B：payload 字节保真（D-054）──────────────────────────────────────────────────
-- 源行切片 oracle：`"payload":` 之后到行尾（golden 每行 payload 都是最后一个键），
-- 与实现里的切片 UDF 是**两条独立路径**（这里用 instr/substr，不走 JsonObjectSlicer）。
SELECT 'B0_join_rows', CAST(count(*) AS STRING) FROM landing_join
UNION ALL
SELECT 'B1_payload_ne_source_slice', CAST(count(*) AS STRING) FROM landing_join
 WHERE payload_json <> substr(raw_line, instr(raw_line, '"payload":') + 10, length(raw_line) - instr(raw_line, '"payload":') - 10)
UNION ALL
SELECT 'B2_hash_ne_sha2_payload', CAST(count(*) AS STRING) FROM landing_join WHERE payload_hash <> sha2(payload_json, 256)
UNION ALL
SELECT 'B3_hash_ne_sha2_source_slice', CAST(count(*) AS STRING) FROM landing_join
 WHERE payload_hash <> sha2(substr(raw_line, instr(raw_line, '"payload":') + 10, length(raw_line) - instr(raw_line, '"payload":') - 10), 256)
UNION ALL
SELECT 'B4_avg_payload_len', CAST(CAST(avg(length(payload_json)) AS BIGINT) AS STRING) FROM landing_join
UNION ALL
SELECT 'B4_min_payload_len', CAST(min(length(payload_json)) AS STRING) FROM landing_join
UNION ALL
SELECT 'B4_max_payload_len', CAST(max(length(payload_json)) AS STRING) FROM landing_join
UNION ALL
SELECT 'B5_payload_braced', CAST(count(*) AS STRING) FROM landing_join WHERE payload_json LIKE '{%}'
UNION ALL
SELECT 'B6_payload_hash_lowerhex64', CAST(count(*) AS STRING) FROM landing_join
 WHERE payload_hash <> lower(payload_hash) OR length(payload_hash) <> 64 OR payload_hash RLIKE '[^0-9a-f]'
UNION ALL
SELECT 'B7_rows_user', CAST(count(*) AS STRING) FROM landing_join WHERE tbl = 'ods_user_event'
UNION ALL
SELECT 'B8_rows_trade', CAST(count(*) AS STRING) FROM landing_join WHERE tbl = 'ods_trade_event';

-- ── C1：v1 信封列取值仍等于源行（source_system 除外：D-056 走注入通道）────────────
-- 用「指纹 + 任一源行可对账」的写法，容忍同一 event_id 的多行源行（见 A13/A14）。
SELECT 'C1_v1_envelope_mismatch', CAST(count(*) AS STRING) FROM (
  SELECT j.ods_fp FROM (
    SELECT 'ods_user_event' AS tbl, concat_ws('|', coalesce(event_id, '<null>'), coalesce(event_type, '<null>'), coalesce(event_time, '<null>'), coalesce(ingest_time, '<null>'), coalesce(schema_version, '<null>'), coalesce(trace_id, '<null>')) AS ods_fp, event_id FROM p201v2_ods.ods_user_event
    UNION ALL
    SELECT 'ods_product_event', concat_ws('|', coalesce(event_id, '<null>'), coalesce(event_type, '<null>'), coalesce(event_time, '<null>'), coalesce(ingest_time, '<null>'), coalesce(schema_version, '<null>'), coalesce(trace_id, '<null>')), event_id FROM p201v2_ods.ods_product_event
    UNION ALL
    SELECT 'ods_behavior_event', concat_ws('|', coalesce(event_id, '<null>'), coalesce(event_type, '<null>'), coalesce(event_time, '<null>'), coalesce(ingest_time, '<null>'), coalesce(schema_version, '<null>'), coalesce(trace_id, '<null>')), event_id FROM p201v2_ods.ods_behavior_event
    UNION ALL
    SELECT 'ods_trade_event', concat_ws('|', coalesce(event_id, '<null>'), coalesce(event_type, '<null>'), coalesce(event_time, '<null>'), coalesce(ingest_time, '<null>'), coalesce(schema_version, '<null>'), coalesce(trace_id, '<null>')), event_id FROM p201v2_ods.ods_trade_event
  ) j JOIN (
    SELECT get_json_object(value, '$.event_id') AS event_id,
           concat_ws('|', coalesce(get_json_object(value, '$.event_id'), '<null>'),
                          coalesce(get_json_object(value, '$.event_type'), '<null>'),
                          coalesce(get_json_object(value, '$.event_time'), '<null>'),
                          coalesce(get_json_object(value, '$.ingest_time'), '<null>'),
                          coalesce(get_json_object(value, '$.schema_version'), '<null>'),
                          coalesce(get_json_object(value, '$.trace_id'), '<null>')) AS env_fp
      FROM raw_text WHERE get_json_object(value, '$.event_id') IS NOT NULL
  ) s ON s.event_id = j.event_id
  GROUP BY j.ods_fp HAVING max(CASE WHEN s.env_fp = j.ods_fp THEN 1 ELSE 0 END) = 0
) m
UNION ALL
SELECT 'C3_ingest_batch_id', concat_ws('|', collect_set(CAST(ingest_batch_id AS STRING))) FROM p201v2_ods.ods_user_event;

-- ── C2/C4：v1 业务列（payload_*）取值 —— 独立 oracle（get_json_object）────────────
-- 这是**抓住「payload 结构体整列 NULL」缺陷的那条检查**，四个主题表都做。
-- 十进制列的 oracle 必须先用 CAST(... AS DECIMAL(18,2)) 归一，否则 '99.9' 与 '99.90' 会假红。
SELECT 'C2_user_payload_mismatch', CAST(count(*) AS STRING)
  FROM p201v2_ods.ods_user_event o JOIN src s ON s.event_id = o.event_id
 WHERE coalesce(o.payload_user_id, '<null>')      <> coalesce(nullif(get_json_object(s.value, '$.payload.user_id'), 'null'), '<null>')
    OR coalesce(o.payload_age_group, '<null>')    <> coalesce(nullif(get_json_object(s.value, '$.payload.age_group'), 'null'), '<null>')
    OR coalesce(o.payload_city_level, '<null>')   <> coalesce(nullif(get_json_object(s.value, '$.payload.city_level'), 'null'), '<null>')
    OR coalesce(o.payload_member_level, '<null>') <> coalesce(nullif(get_json_object(s.value, '$.payload.member_level'), 'null'), '<null>')
    OR coalesce(o.payload_register_time, '<null>') <> coalesce(nullif(get_json_object(s.value, '$.payload.register_time'), 'null'), '<null>')
UNION ALL
SELECT 'C4_product_payload_mismatch', CAST(count(*) AS STRING)
  FROM p201v2_ods.ods_product_event o JOIN src s ON s.event_id = o.event_id
 WHERE coalesce(o.payload_product_id, '<null>')           <> coalesce(nullif(get_json_object(s.value, '$.payload.product_id'), 'null'), '<null>')
    OR coalesce(o.payload_product_name, '<null>')         <> coalesce(nullif(get_json_object(s.value, '$.payload.product_name'), 'null'), '<null>')
    OR coalesce(o.payload_category_id, '<null>')          <> coalesce(nullif(get_json_object(s.value, '$.payload.category_id'), 'null'), '<null>')
    OR coalesce(o.payload_category_name, '<null>')        <> coalesce(nullif(get_json_object(s.value, '$.payload.category_name'), 'null'), '<null>')
    OR coalesce(o.payload_parent_category_id, '<null>')   <> coalesce(nullif(get_json_object(s.value, '$.payload.parent_category_id'), 'null'), '<null>')
    OR coalesce(o.payload_parent_category_name, '<null>') <> coalesce(nullif(get_json_object(s.value, '$.payload.parent_category_name'), 'null'), '<null>')
    OR coalesce(o.payload_brand_id, '<null>')             <> coalesce(nullif(get_json_object(s.value, '$.payload.brand_id'), 'null'), '<null>')
    OR coalesce(CAST(o.payload_price AS STRING), '<null>') <> coalesce(CAST(CAST(nullif(get_json_object(s.value, '$.payload.price'), 'null') AS DECIMAL(18,2)) AS STRING), '<null>')
    OR coalesce(CAST(o.payload_cost AS STRING), '<null>')  <> coalesce(CAST(CAST(nullif(get_json_object(s.value, '$.payload.cost'), 'null') AS DECIMAL(18,2)) AS STRING), '<null>')
    OR coalesce(o.payload_status, '<null>')               <> coalesce(nullif(get_json_object(s.value, '$.payload.status'), 'null'), '<null>')
UNION ALL
SELECT 'C4_behavior_payload_mismatch', CAST(count(*) AS STRING)
  FROM p201v2_ods.ods_behavior_event o JOIN src s ON s.event_id = o.event_id
 WHERE coalesce(o.payload_user_id, '<null>')       <> coalesce(nullif(get_json_object(s.value, '$.payload.user_id'), 'null'), '<null>')
    OR coalesce(o.payload_product_id, '<null>')    <> coalesce(nullif(get_json_object(s.value, '$.payload.product_id'), 'null'), '<null>')
    OR coalesce(o.payload_session_id, '<null>')    <> coalesce(nullif(get_json_object(s.value, '$.payload.session_id'), 'null'), '<null>')
    OR coalesce(o.payload_behavior_type, '<null>') <> coalesce(nullif(get_json_object(s.value, '$.payload.behavior_type'), 'null'), '<null>')
    OR coalesce(o.payload_channel, '<null>')       <> coalesce(nullif(get_json_object(s.value, '$.payload.channel'), 'null'), '<null>')
UNION ALL
SELECT 'C4_trade_payload_mismatch', CAST(count(*) AS STRING)
  FROM p201v2_ods.ods_trade_event o JOIN src s ON s.event_id = o.event_id
 WHERE coalesce(o.payload_order_id, '<null>')     <> coalesce(nullif(get_json_object(s.value, '$.payload.order_id'), 'null'), '<null>')
    OR coalesce(o.payload_user_id, '<null>')      <> coalesce(nullif(get_json_object(s.value, '$.payload.user_id'), 'null'), '<null>')
    OR coalesce(o.payload_payment_id, '<null>')   <> coalesce(nullif(get_json_object(s.value, '$.payload.payment_id'), 'null'), '<null>')
    OR coalesce(o.payload_refund_id, '<null>')    <> coalesce(nullif(get_json_object(s.value, '$.payload.refund_id'), 'null'), '<null>')
    OR coalesce(o.payload_product_id, '<null>')   <> coalesce(nullif(get_json_object(s.value, '$.payload.product_id'), 'null'), '<null>')
    OR coalesce(CAST(o.payload_amount AS STRING), '<null>')       <> coalesce(CAST(CAST(nullif(get_json_object(s.value, '$.payload.amount'), 'null') AS DECIMAL(18,2)) AS STRING), '<null>')
    OR coalesce(CAST(o.payload_total_amount AS STRING), '<null>') <> coalesce(CAST(CAST(nullif(get_json_object(s.value, '$.payload.total_amount'), 'null') AS DECIMAL(18,2)) AS STRING), '<null>')
    OR coalesce(o.payload_status, '<null>')       <> coalesce(nullif(get_json_object(s.value, '$.payload.status'), 'null'), '<null>')
    OR coalesce(o.payload_reason, '<null>')       <> coalesce(nullif(get_json_object(s.value, '$.payload.reason'), 'null'), '<null>');

-- ── C5：直接抗回归读数（缺陷期这些值全为 0）─────────────────────────────────────
SELECT 'C5_user_payload_nonnull_rows', CAST(count(*) AS STRING) FROM p201v2_ods.ods_user_event WHERE payload_user_id IS NOT NULL
UNION ALL
SELECT 'C5_product_payload_nonnull_rows', CAST(count(*) AS STRING) FROM p201v2_ods.ods_product_event WHERE payload_product_id IS NOT NULL
UNION ALL
SELECT 'C5_behavior_payload_nonnull_rows', CAST(count(*) AS STRING) FROM p201v2_ods.ods_behavior_event WHERE payload_user_id IS NOT NULL
UNION ALL
SELECT 'C5_trade_payload_nonnull_rows', CAST(count(*) AS STRING) FROM p201v2_ods.ods_trade_event WHERE payload_order_id IS NOT NULL
UNION ALL
SELECT 'C5_trade_items_ods', concat_ws('|', collect_set(coalesce(CAST(payload_items AS STRING), '<null>'))) FROM p201v2_ods.ods_trade_event
UNION ALL
SELECT 'C5_trade_items_src_head', substr(min(get_json_object(value, '$.payload.items')), 1, 60) FROM raw_text WHERE get_json_object(value, '$.event_type') IN ('order_created', 'order_paid', 'order_cancelled', 'order_completed', 'refund_requested', 'refund_completed');

-- ── C7：v1 口径投影 oracle（把 payload 原文再喂给 v1 的闭合 schema 解析）──────────
-- 注意 from_json 的输入必须是 **payload 原文**（get_json_object(...,'$.payload')），
-- 不是整行——整行喂进去会去找顶层 order_id/user_id，必然全 NULL（踩过）。
SELECT 'C7_user_projection_mismatch', CAST(count(*) AS STRING)
  FROM p201v2_ods.ods_user_event o JOIN src s ON s.event_id = o.event_id
 WHERE coalesce(o.payload_user_id, '<null>') <> coalesce(from_json(get_json_object(s.value, '$.payload'), 'user_id STRING, age_group STRING, city_level STRING, member_level STRING, register_time STRING').user_id, '<null>')
    OR coalesce(o.payload_age_group, '<null>') <> coalesce(from_json(get_json_object(s.value, '$.payload'), 'user_id STRING, age_group STRING, city_level STRING, member_level STRING, register_time STRING').age_group, '<null>')
    OR coalesce(o.payload_register_time, '<null>') <> coalesce(from_json(get_json_object(s.value, '$.payload'), 'user_id STRING, age_group STRING, city_level STRING, member_level STRING, register_time STRING').register_time, '<null>')
UNION ALL
SELECT 'C7_trade_projection_mismatch', CAST(count(*) AS STRING)
  FROM p201v2_ods.ods_trade_event o JOIN src s ON s.event_id = o.event_id
 WHERE coalesce(o.payload_order_id, '<null>') <> coalesce(from_json(get_json_object(s.value, '$.payload'), 'order_id STRING, user_id STRING, payment_id STRING, refund_id STRING, product_id STRING, amount DECIMAL(18,2), total_amount DECIMAL(18,2), status STRING, reason STRING, items STRING').order_id, '<null>')
    OR coalesce(CAST(o.payload_amount AS STRING), '<null>') <> coalesce(CAST(from_json(get_json_object(s.value, '$.payload'), 'order_id STRING, user_id STRING, payment_id STRING, refund_id STRING, product_id STRING, amount DECIMAL(18,2), total_amount DECIMAL(18,2), status STRING, reason STRING, items STRING').amount AS STRING), '<null>')
    OR coalesce(CAST(o.payload_items AS STRING), '<null>') <> coalesce(from_json(get_json_object(s.value, '$.payload'), 'order_id STRING, user_id STRING, payment_id STRING, refund_id STRING, product_id STRING, amount DECIMAL(18,2), total_amount DECIMAL(18,2), status STRING, reason STRING, items STRING').items, '<null>');

-- ── D：注入通道 vs 行内原值（D-056；探针库 = p201v2b）──────────────────────────
SELECT 'D1_probe_source_system', concat_ws('|', collect_set(source_system)) FROM p201v2b_ods.ods_user_event
UNION ALL
SELECT 'D2_probe_raw_source_system', concat_ws('|', collect_set(raw_source_system)) FROM p201v2b_ods.ods_user_event
UNION ALL
SELECT 'D3_main_source_pair', concat_ws('|', collect_set(concat(source_system, '#', raw_source_system))) FROM p201v2_ods.ods_user_event
UNION ALL
SELECT 'D4_main_v1_payload_rowcount', CAST(count(*) AS STRING) FROM p201v2_ods.ods_user_event;

-- ── F：每表行数（主库 + 探针库）────────────────────────────────────────────────
SELECT 'F1_rows_user', CAST(count(*) AS STRING) FROM p201v2_ods.ods_user_event
UNION ALL
SELECT 'F1_rows_product', CAST(count(*) AS STRING) FROM p201v2_ods.ods_product_event
UNION ALL
SELECT 'F1_rows_behavior', CAST(count(*) AS STRING) FROM p201v2_ods.ods_behavior_event
UNION ALL
SELECT 'F1_rows_trade', CAST(count(*) AS STRING) FROM p201v2_ods.ods_trade_event
UNION ALL
SELECT 'F2_rows_probe_user', CAST(count(*) AS STRING) FROM p201v2b_ods.ods_user_event
UNION ALL
SELECT 'F3_probe_dual_channel_ok', CAST(count(*) AS STRING) FROM (
  SELECT source_system, raw_source_system FROM p201v2b_ods.ods_user_event
  UNION ALL SELECT source_system, raw_source_system FROM p201v2b_ods.ods_product_event
  UNION ALL SELECT source_system, raw_source_system FROM p201v2b_ods.ods_behavior_event
  UNION ALL SELECT source_system, raw_source_system FROM p201v2b_ods.ods_trade_event
) p WHERE p.source_system = 'probe-inj-2026' AND p.raw_source_system = 'mock-mall'
UNION ALL
SELECT 'F4_probe_payload_nonnull_rows', CAST(count(*) AS STRING) FROM (
  SELECT payload_user_id AS c FROM p201v2b_ods.ods_user_event
  UNION ALL SELECT payload_product_id FROM p201v2b_ods.ods_product_event
  UNION ALL SELECT payload_user_id FROM p201v2b_ods.ods_behavior_event
  UNION ALL SELECT payload_order_id FROM p201v2b_ods.ods_trade_event
) q WHERE q.c IS NOT NULL;

-- ── SAMPLE：原始读数（供报告逐字引用）──────────────────────────────────────────
SELECT 'SAMPLE_5NEWCOLUMNS' AS marker;
SELECT event_id, raw_event_type, raw_source_system, landing_file, payload_hash, length(payload_json) AS payload_len
  FROM p201v2_ods.ods_user_event ORDER BY event_id;

SELECT 'SAMPLE_V1_PAYLOAD_USER' AS marker;
SELECT event_id, payload_user_id, payload_age_group, payload_city_level, payload_member_level, payload_register_time
  FROM p201v2_ods.ods_user_event ORDER BY event_id;

SELECT 'SAMPLE_V1_PAYLOAD_TRADE' AS marker;
SELECT event_id, payload_order_id, payload_user_id, payload_amount, payload_total_amount, payload_status, substr(payload_items, 1, 40) AS items_head
  FROM p201v2_ods.ods_trade_event ORDER BY event_id LIMIT 5;

SELECT 'SAMPLE_PAYLOAD_JSON_HEAD' AS marker;
SELECT event_id, substr(payload_json, 1, 100) AS payload_head FROM p201v2_ods.ods_user_event ORDER BY event_id LIMIT 2;
