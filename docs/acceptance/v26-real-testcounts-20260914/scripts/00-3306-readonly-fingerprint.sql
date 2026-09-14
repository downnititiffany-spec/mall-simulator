-- 3306 只读指纹（冻结基线；before/after 必须用同一份）
-- 只读：SHOW / SELECT / CHECKSUM TABLE，无任何 DML/DDL
SET SESSION group_concat_max_len=4194304;
SELECT NOW() AS captured_at;
SHOW MASTER STATUS;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Uptime','Questions','Com_insert','Com_update','Com_delete','Com_replace','Com_alter_table','Com_create_table','Com_drop_table','Com_create_database','Com_drop_database','Com_truncate','Com_grant','Com_create_user');
CHECKSUM TABLE analytics_meta.data_quality_result, analytics_metric.metric_snapshot, analytics_metric.metric_value;
SELECT COUNT(*) AS analytics_schemas FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'analytics%';
SELECT COUNT(*) AS analytics_tables FROM information_schema.TABLES WHERE TABLE_SCHEMA LIKE 'analytics%';
SELECT MAX(CAST(version AS UNSIGNED)) AS max_flyway_meta FROM analytics_meta.flyway_schema_history;
SELECT COUNT(*) AS qrd_table_present FROM information_schema.TABLES WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='quality_rule_definition';
SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) AS dqr_colnames FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='data_quality_result';