-- B-13 勘误卷 / 复核：以下仅为逐行 SELECT（READ ONLY，无任何写语句）
-- 1) analytics_metric.metric_snapshot —— 现行生产指标库
SELECT 'analytics_metric.metric_snapshot' AS src, COUNT(*) AS rows_cnt, MIN(id) AS min_id, MAX(id) AS max_id,
       MIN(created_at) AS min_created, MAX(created_at) AS max_created,
       SUM(status='ACTIVE') AS active_cnt
FROM analytics_metric.metric_snapshot;
SELECT id, snapshot_id, runtime_profile_id, runtime_profile_version, business_time,
       pipeline_run_id, status, version, data_updated_at, published_at, source, created_at
FROM analytics_metric.metric_snapshot ORDER BY id;
SELECT 'analytics_metric.metric_value' AS src, COUNT(*) AS rows_cnt, MIN(id) AS min_id, MAX(id) AS max_id,
       MIN(created_at) AS min_created, MAX(created_at) AS max_created
FROM analytics_metric.metric_value;
SELECT snapshot_id, COUNT(*) AS value_rows FROM analytics_metric.metric_value GROUP BY snapshot_id ORDER BY snapshot_id;

-- 2) analytics_meta.metric_snapshot —— 陈旧种子表（同名不同库）
SELECT 'analytics_meta.metric_snapshot' AS src, COUNT(*) AS rows_cnt, MIN(id) AS min_id, MAX(id) AS max_id,
       MIN(created_at) AS min_created, MAX(created_at) AS max_created,
       SUM(status='ACTIVE') AS active_cnt
FROM analytics_meta.metric_snapshot;
SELECT id, snapshot_id, runtime_profile_id, runtime_profile_version, business_time,
       pipeline_run_id, status, version, data_updated_at, published_at, source, created_at
FROM analytics_meta.metric_snapshot ORDER BY id;
SELECT 'analytics_meta.metric_value' AS src, COUNT(*) AS rows_cnt, MIN(id) AS min_id, MAX(id) AS max_id,
       MIN(created_at) AS min_created, MAX(created_at) AS max_created
FROM analytics_meta.metric_value;
SELECT snapshot_id, COUNT(*) AS value_rows FROM analytics_meta.metric_value GROUP BY snapshot_id ORDER BY snapshot_id;
