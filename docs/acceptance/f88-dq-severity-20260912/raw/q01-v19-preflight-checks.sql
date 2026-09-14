-- =====================================================================
-- V19 迁移预检（pre-flight）：在 `quality_rule_definition` 落地**之前**，用**只读 SELECT**
-- 预演 V19 的种子会不会违反自己的表约束。
--
-- 背景：本泳道处在 DB 冻结下（不对 3306 执行任何 DDL/DML），因此 V19 建表+种子**未在真库验证过**。
-- 本文件把「V19 会失败的地方」全部改写成只读查询 —— 结果集**必须为空**才算预检通过。
-- 全部语句均为 `SELECT`，不写库；刻意用 `EXPLAIN` 做纯语法校验（不执行、不建表）。
--
-- 用法（只读；库名随环境替换）：
--   mysql --host=127.0.0.1 --port=3306 --user=<只读账号> -e "source V19__quality_rule_definition.preflight.sql"
-- 逐条看结果集：空白 = 通过；有行 = 该行就是 V19 落地时会撞上的问题。
--
-- 判读要点：
--   Q0 不是断言，是**语法校验**：解析成功即 V19 的 CREATE TABLE 语法成立（未执行）。
--   Q4 的 `threshold_json` JSON 合法性依赖 8.0 的 `JSON_VALID()`；5.7 也可用，5.6 会报未知函数。
-- =====================================================================

-- ── Q0 纯语法校验：V19 的 CREATE TABLE 能否被解析（EXPLAIN 不建表、不写库）────────
-- 期望：解析成功（输出执行计划），无 syntax error。
EXPLAIN
SELECT rule_code, version, source_scope, stage, severity, severity_mode,
       threshold_json, enabled, effective_from, effective_to, checksum
FROM quality_rule_definition
WHERE source_scope = '*' AND rule_code = 'EVENT_ID_UNIQUE' AND version = 1;

-- ── Q1 V19 的 `severity` 取值域：必须都是 BLOCKING/ERROR/WARN/INFO ──────────────
-- 期望：0 行（有行 ⇒ V19 种子写入后会被 RuleSeverity.isKnownSeverity 判为非法）。
SELECT 'Q1 severity 越界' AS issue, rule_code, severity
FROM quality_rule_definition
WHERE severity NOT IN ('BLOCKING', 'ERROR', 'WARN', 'INFO');

-- ── Q2 V19 的 `severity_mode` 取值域 ────────────────────────────────────────────
-- 期望：0 行。
SELECT 'Q2 severity_mode 越界' AS issue, rule_code, severity_mode
FROM quality_rule_definition
WHERE severity_mode NOT IN ('FIXED', 'THRESHOLD_OBSERVATION');

-- ── Q3 `THRESHOLD_OBSERVATION` 的两条不变式（对应 QualityRuleDefinition 紧凑构造器）──
--   ① 基准 severity 必须是 WARN（观察项档）；
--   ② 必须有 threshold_json。
-- 期望：0 行（有行 ⇒ Java 目录加载该行时会抛 IllegalArgumentException）。
SELECT 'Q3 THRESHOLD_OBSERVATION 不变式被破' AS issue, rule_code, severity, threshold_json,
       CASE WHEN severity <> 'WARN' THEN '基准档不是 WARN'
            WHEN threshold_json IS NULL OR TRIM(threshold_json) = '' THEN '缺 threshold_json'
            ELSE '其他' END AS which
FROM quality_rule_definition
WHERE severity_mode = 'THRESHOLD_OBSERVATION'
  AND (severity <> 'WARN' OR threshold_json IS NULL OR TRIM(threshold_json) = '');

-- ── Q4 `threshold_json` 非空时必须是合法 JSON ───────────────────────────────────
-- 期望：0 行。
SELECT 'Q4 threshold_json 非合法 JSON' AS issue, rule_code, threshold_json
FROM quality_rule_definition
WHERE threshold_json IS NOT NULL AND TRIM(threshold_json) <> ''
  AND JSON_VALID(threshold_json) = 0;

-- ── Q5 `checksum` 必须是 64 位小写十六进制（SHA-256 十六进制形式）────────────────
-- 期望：0 行。
SELECT 'Q5 checksum 形状不对' AS issue, rule_code, checksum
FROM quality_rule_definition
WHERE checksum NOT REGEXP '^[0-9a-f]{64}$';

-- ── Q6 唯一键 `(source_scope, rule_code, version)` 不得重复 ─────────────────────
-- 期望：0 行（有行 ⇒ V19 的 uk_rule_scope_version 会拒绝，迁移中止）。
SELECT 'Q6 唯一键重复' AS issue, source_scope, rule_code, version, COUNT(*) AS dup_cnt
FROM quality_rule_definition
GROUP BY source_scope, rule_code, version
HAVING COUNT(*) > 1;

-- ── Q7 种子行数：契约全集应为 35 行、且 version 全为 1 ──────────────────────────
-- 期望：total_defs = 35，codes = 35，versions = 1，scopes = 1。
SELECT COUNT(*) AS total_defs,
       COUNT(DISTINCT rule_code) AS codes,
       COUNT(DISTINCT version) AS versions,
       COUNT(DISTINCT source_scope) AS scopes
FROM quality_rule_definition;

-- ── Q8 与 V20 的耦合检查：`data_quality_result` 的 4 个新列若已存在，必须可空且无默认 ──
--   （V20 与 V19 是同一泳道的两个迁移；此处只读确认落点，不改动）
-- 期望：4 行，且每行 IS_NULLABLE='YES'、COLUMN_DEFAULT IS NULL。
SELECT 'Q8 V20 目标列' AS issue, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_quality_result'
  AND COLUMN_NAME IN ('rule_version', 'effective_severity', 'compat_policy_version', 'rule_fingerprint')
ORDER BY COLUMN_NAME;

-- ── Q9 「严禁回填」的只读证据：历史行版本列现状必须全为 NULL ────────────────────
--   V20 落地后此查询应报 total_rows = 467、null_rows = 467（100% NULL）。
--   若出现非 NULL 值，说明有人回填了猜测值 —— 与总控裁决「严禁回填任何猜测值」冲突。
-- 期望（V20 落地后）：total_rows = 467 且 null_rows = 467。
SELECT COUNT(*) AS total_rows,
       SUM(rule_version IS NULL) AS null_rule_version,
       SUM(effective_severity IS NULL) AS null_effective_severity,
       SUM(compat_policy_version IS NULL) AS null_compat_policy_version,
       SUM(rule_fingerprint IS NULL) AS null_rule_fingerprint
FROM data_quality_result;
