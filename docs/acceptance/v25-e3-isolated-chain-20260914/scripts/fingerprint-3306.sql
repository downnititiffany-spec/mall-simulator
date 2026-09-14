-- V25-E3 宿主 3306 零写入指纹（只读）
-- 算法（本泳道自定义并显式声明，见 README §2）：
--   行指纹 h = MD5(CONCAT_WS('|', IFNULL(CAST(col AS CHAR),'\N'), ... 按 ORDINAL_POSITION 全列))
--   表指纹 fp = MD5(GROUP_CONCAT(h ORDER BY h SEPARATOR ''))   —— 按行指纹排序 ⇒ 行序无关（集合语义）
--   合计指纹   = MD5(CONCAT(fp_snapshot, ':', fp_value))
-- group_concat_max_len 提升到 1GB，避免静默截断（截断会让指纹失去意义）。
SET SESSION group_concat_max_len = 1073741824;

SELECT 'metric_snapshot_rows' AS k, COUNT(*) AS v FROM analytics_metric.metric_snapshot;
SELECT 'metric_value_rows'    AS k, COUNT(*) AS v FROM analytics_metric.metric_value;

SELECT 'metric_snapshot_fp' AS k,
       MD5(GROUP_CONCAT(h ORDER BY h SEPARATOR '')) AS v
FROM (SELECT MD5(CONCAT_WS('|',
        IFNULL(CAST(id AS CHAR),'\\N'),
        IFNULL(CAST(snapshot_id AS CHAR),'\\N'),
        IFNULL(CAST(runtime_profile_id AS CHAR),'\\N'),
        IFNULL(CAST(runtime_profile_version AS CHAR),'\\N'),
        IFNULL(CAST(business_time AS CHAR),'\\N'),
        IFNULL(CAST(pipeline_run_id AS CHAR),'\\N'),
        IFNULL(CAST(status AS CHAR),'\\N'),
        IFNULL(CAST(version AS CHAR),'\\N'),
        IFNULL(CAST(definition_version AS CHAR),'\\N'),
        IFNULL(CAST(data_updated_at AS CHAR),'\\N'),
        IFNULL(CAST(published_at AS CHAR),'\\N'),
        IFNULL(CAST(source AS CHAR),'\\N'),
        IFNULL(CAST(failure_reason AS CHAR),'\\N'),
        IFNULL(CAST(active_flag AS CHAR),'\\N'),
        IFNULL(CAST(created_at AS CHAR),'\\N'))) AS h
     FROM analytics_metric.metric_snapshot) s;

SELECT 'metric_value_fp' AS k,
       MD5(GROUP_CONCAT(h ORDER BY h SEPARATOR '')) AS v
FROM (SELECT MD5(CONCAT_WS('|',
        IFNULL(CAST(id AS CHAR),'\\N'),
        IFNULL(CAST(snapshot_id AS CHAR),'\\N'),
        IFNULL(CAST(metric_code AS CHAR),'\\N'),
        IFNULL(CAST(metric_value AS CHAR),'\\N'),
        IFNULL(CAST(unit AS CHAR),'\\N'),
        IFNULL(CAST(period AS CHAR),'\\N'),
        IFNULL(CAST(dimension_json AS CHAR),'\\N'),
        IFNULL(CAST(dimension_key AS CHAR),'\\N'),
        IFNULL(CAST(definition_version AS CHAR),'\\N'),
        IFNULL(CAST(updated_at AS CHAR),'\\N'))) AS h
     FROM analytics_metric.metric_value) v;

-- ACTIVE 指针（全表只应有 1 行 active_flag=1）
SELECT 'active_pointer' AS k,
       CONCAT('id=', id, ' / ', snapshot_id, ' / version=', version, ' / active_flag=', active_flag) AS v
FROM analytics_metric.metric_snapshot WHERE active_flag = 1;

-- Flyway 历史（证明 3306 未被本轮的迁移触碰）
SELECT 'flyway_3306_count' AS k, COUNT(*) AS v FROM analytics_metric.flyway_schema_history;
SELECT 'flyway_3306_latest' AS k,
       CONCAT(MAX(installed_rank), ' / ', SUBSTRING_INDEX(GROUP_CONCAT(CONCAT(version, '@', installed_on) ORDER BY installed_rank DESC), ',', 1)) AS v
FROM analytics_metric.flyway_schema_history;

-- 旁证：本次 run 前缀的档案登记不得出现在正式库
SELECT 'runtime_profile_v25it_rows' AS k, COUNT(*) AS v
FROM analytics_meta.runtime_profile WHERE profile_code LIKE 'v25it-%';
