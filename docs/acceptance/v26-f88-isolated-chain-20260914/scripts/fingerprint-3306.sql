-- V26-F88 | 宿主正式实例 3306 只读指纹（S1 前置 / S6 收尾 各跑一次，逐项 diff）
--
-- 【硬约束】本文件**只能**包含 SET / SELECT；由 00-fingerprint-3306-readonly.ps1 逐语句静态检查，
--           出现任何其它语句立即 throw，不依赖人的自觉。全程零 DDL / 零 DML / 零清理。
--
-- 指纹算法（沿用 V25-E3 scripts/fingerprint-3306.sql:1-7 的显式声明，保证与历史读数可比）：
--   行指纹 h  = MD5(CONCAT_WS('|', IFNULL(CAST(col AS CHAR),'\N') … 全列按 ORDINAL_POSITION))
--   表指纹 fp = MD5(GROUP_CONCAT(h ORDER BY h SEPARATOR ''))   —— 按行指纹排序 ⇒ 行序无关（集合语义）
--   group_concat_max_len 提升到 1GB，避免静默截断（截断会让指纹失去意义）。
--
-- 本泳道相对 E3 的**增量**（E3 只覆盖 analytics_metric 两张表）：
--   ① analytics_meta.data_quality_result 的行数 + 全列指纹（F-88 的直接对象）
--   ② 该表上 V20 的 4 个列是否**仍不存在**（迁移版本是否被本窗口推进）
--   ③ analytics_meta.flyway_schema_history 的完整版本清单（不只 count/latest）
--   ④ 本次与前序 runId 前缀在 3306 上的「零痕迹」旁证
SET SESSION group_concat_max_len = 1073741824;

-- ── A. 实例身份（必须与宿主 uuid 匹配，否则 runner 拒绝继续）──────────────
SELECT 'instance' AS k,
       CONCAT(@@port, '|', @@server_uuid, '|', @@datadir, '|', @@hostname, '|', @@version) AS v;

-- ── B. analytics_metric：E3 同算法两表指纹（跨轮可比）────────────────────
SELECT 'metric_snapshot_rows' AS k, COUNT(*) AS v FROM analytics_metric.metric_snapshot;
SELECT 'metric_value_rows'    AS k, COUNT(*) AS v FROM analytics_metric.metric_value;

SELECT 'metric_snapshot_fp' AS k, MD5(GROUP_CONCAT(h ORDER BY h SEPARATOR '')) AS v
FROM (SELECT MD5(CONCAT_WS('|',
        IFNULL(CAST(id AS CHAR),'\\N'), IFNULL(CAST(snapshot_id AS CHAR),'\\N'),
        IFNULL(CAST(runtime_profile_id AS CHAR),'\\N'), IFNULL(CAST(runtime_profile_version AS CHAR),'\\N'),
        IFNULL(CAST(business_time AS CHAR),'\\N'), IFNULL(CAST(pipeline_run_id AS CHAR),'\\N'),
        IFNULL(CAST(status AS CHAR),'\\N'), IFNULL(CAST(version AS CHAR),'\\N'),
        IFNULL(CAST(definition_version AS CHAR),'\\N'), IFNULL(CAST(data_updated_at AS CHAR),'\\N'),
        IFNULL(CAST(published_at AS CHAR),'\\N'), IFNULL(CAST(source AS CHAR),'\\N'),
        IFNULL(CAST(failure_reason AS CHAR),'\\N'), IFNULL(CAST(active_flag AS CHAR),'\\N'),
        IFNULL(CAST(created_at AS CHAR),'\\N'))) AS h
     FROM analytics_metric.metric_snapshot) s;

SELECT 'metric_value_fp' AS k, MD5(GROUP_CONCAT(h ORDER BY h SEPARATOR '')) AS v
FROM (SELECT MD5(CONCAT_WS('|',
        IFNULL(CAST(id AS CHAR),'\\N'), IFNULL(CAST(snapshot_id AS CHAR),'\\N'),
        IFNULL(CAST(metric_code AS CHAR),'\\N'), IFNULL(CAST(metric_value AS CHAR),'\\N'),
        IFNULL(CAST(unit AS CHAR),'\\N'), IFNULL(CAST(period AS CHAR),'\\N'),
        IFNULL(CAST(dimension_json AS CHAR),'\\N'), IFNULL(CAST(dimension_key AS CHAR),'\\N'),
        IFNULL(CAST(definition_version AS CHAR),'\\N'), IFNULL(CAST(updated_at AS CHAR),'\\N'))) AS h
     FROM analytics_metric.metric_value) v;

SELECT 'active_pointer' AS k,
       CONCAT('id=', id, ' / ', snapshot_id, ' / version=', version, ' / active_flag=', active_flag) AS v
FROM analytics_metric.metric_snapshot WHERE active_flag = 1;

-- ── C. 迁移版本：3306 是否被本窗口推进 ──────────────────────────────────
SELECT 'flyway_metric_count' AS k, COUNT(*) AS v FROM analytics_metric.flyway_schema_history;
SELECT 'flyway_metric_latest' AS k,
       CONCAT(MAX(installed_rank), ' / ', SUBSTRING_INDEX(GROUP_CONCAT(CONCAT(version, '@', installed_on) ORDER BY installed_rank DESC), ',', 1)) AS v
FROM analytics_metric.flyway_schema_history;
SELECT 'flyway_meta_count' AS k, COUNT(*) AS v FROM analytics_meta.flyway_schema_history;
SELECT 'flyway_meta_versions' AS k,
       GROUP_CONCAT(version ORDER BY installed_rank) AS v
FROM analytics_meta.flyway_schema_history;
SELECT 'flyway_meta_latest_installed_on' AS k,
       CONCAT(MAX(installed_rank), ' / ', MAX(installed_on)) AS v
FROM analytics_meta.flyway_schema_history;
SELECT 'flyway_meta_checksum_sum' AS k, SUM(checksum) AS v FROM analytics_meta.flyway_schema_history;

-- ── D. F-88 直接对象：analytics_meta.data_quality_result ────────────────
SELECT 'dqr_rows' AS k, COUNT(*) AS v FROM analytics_meta.data_quality_result;
SELECT 'dqr_severity_null_rows' AS k, COUNT(*) AS v
FROM analytics_meta.data_quality_result WHERE severity IS NULL;
SELECT 'dqr_fp' AS k, MD5(GROUP_CONCAT(h ORDER BY h SEPARATOR '')) AS v
FROM (SELECT MD5(CONCAT_WS('|',
        IFNULL(CAST(id AS CHAR),'\\N'), IFNULL(CAST(run_id AS CHAR),'\\N'),
        IFNULL(CAST(rule_code AS CHAR),'\\N'), IFNULL(CAST(layer AS CHAR),'\\N'),
        IFNULL(CAST(severity AS CHAR),'\\N'), IFNULL(CAST(target_table AS CHAR),'\\N'),
        IFNULL(CAST(snapshot_id AS CHAR),'\\N'), IFNULL(CAST(check_count AS CHAR),'\\N'),
        IFNULL(CAST(error_count AS CHAR),'\\N'), IFNULL(CAST(error_rate AS CHAR),'\\N'),
        IFNULL(CAST(threshold AS CHAR),'\\N'), IFNULL(CAST(passed AS CHAR),'\\N'),
        IFNULL(CAST(detail AS CHAR),'\\N'), IFNULL(CAST(created_at AS CHAR),'\\N'))) AS h
     FROM analytics_meta.data_quality_result) d;

-- V20 的 4 列：本窗口内必须**仍为 0**（迁移未前向推进）
SELECT 'dqr_v20_columns_present_on_3306' AS k, COUNT(*) AS v
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='data_quality_result'
  AND COLUMN_NAME IN ('rule_version','effective_severity','compat_policy_version','rule_fingerprint');
SELECT 'dqr_column_count_on_3306' AS k, COUNT(*) AS v
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='data_quality_result';
-- V19 的定义表：本窗口内必须**仍为 0**
SELECT 'qrd_table_present_on_3306' AS k, COUNT(*) AS v
FROM information_schema.TABLES
WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='quality_rule_definition';
SELECT 'meta_table_count_on_3306' AS k, COUNT(*) AS v
FROM information_schema.TABLES WHERE TABLE_SCHEMA='analytics_meta';

-- ── E. 本次 / 前序 runId 前缀在 3306 上的零痕迹旁证 ─────────────────────
SELECT 'v25it_databases_on_3306' AS k, COUNT(*) AS v FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'v25it%';
SELECT 'v25f88_databases_on_3306' AS k, COUNT(*) AS v FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'v25f88%';
SELECT 'v25it_accounts_on_3306' AS k, COUNT(*) AS v FROM mysql.user WHERE User LIKE 'v25it%';
SELECT 'v25f88_accounts_on_3306' AS k, COUNT(*) AS v FROM mysql.user WHERE User LIKE 'v25f88%';
SELECT 'runtime_profile_v25_prefix_rows' AS k, COUNT(*) AS v
FROM analytics_meta.runtime_profile WHERE profile_code LIKE 'v25%';
SELECT 'runtime_profile_total_rows' AS k, COUNT(*) AS v FROM analytics_meta.runtime_profile;

-- ── F. 窗口内「新行」判据（不需要 baseline 的第二路）─────────────────────
SELECT 'dqr_rows_created_in_window' AS k, COUNT(*) AS v
FROM analytics_meta.data_quality_result
WHERE created_at >= '__WIN_START__' AND created_at <= '__WIN_END__';
SELECT 'pipeline_run_rows_in_window' AS k, COUNT(*) AS v
FROM analytics_meta.pipeline_run
WHERE created_at >= '__WIN_START__' AND created_at <= '__WIN_END__';
