-- V25-S02 宿主 3306 只读指纹（before/after 对比用）
-- 方法沿用 docs/acceptance/v25-e3-isolated-chain-20260914/scripts/fingerprint-3306.sql：
--   行指纹 h = MD5(CONCAT_WS('|', 全列按 ORDINAL_POSITION))
--   表指纹 fp = MD5(GROUP_CONCAT(h ORDER BY h SEPARATOR ''))  —— 行序无关（集合语义）
-- 本脚本**只读**：全部是 SELECT；不执行任何 DDL/DML。
SET SESSION group_concat_max_len = 1073741824;

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

SELECT 'flyway_metric_count' AS k, COUNT(*) AS v FROM analytics_metric.flyway_schema_history;
SELECT 'flyway_metric_latest' AS k,
       CONCAT(MAX(installed_rank), ' / ', SUBSTRING_INDEX(GROUP_CONCAT(CONCAT(version, '@', installed_on) ORDER BY installed_rank DESC), ',', 1)) AS v
FROM analytics_metric.flyway_schema_history;
SELECT 'flyway_meta_count' AS k, COUNT(*) AS v FROM analytics_meta.flyway_schema_history;
SELECT 'flyway_meta_latest' AS k,
       CONCAT(MAX(installed_rank), ' / ', SUBSTRING_INDEX(GROUP_CONCAT(CONCAT(version, '@', installed_on) ORDER BY installed_rank DESC), ',', 1)) AS v
FROM analytics_meta.flyway_schema_history;

-- 旁证：本轮与历史 IT 前缀的库/账号/档案不得出现在正式实例
SELECT 'v25it_databases_on_3306' AS k, COUNT(*) AS v FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'v25it-%';
SELECT 'v25it_accounts_on_3306'  AS k, COUNT(*) AS v FROM mysql.user WHERE User LIKE 'v25it-%';
SELECT 'runtime_profile_v25it_rows' AS k, COUNT(*) AS v FROM analytics_meta.runtime_profile WHERE profile_code LIKE 'v25it-%';
SELECT 'runtime_profile_total_rows' AS k, COUNT(*) AS v FROM analytics_meta.runtime_profile;
