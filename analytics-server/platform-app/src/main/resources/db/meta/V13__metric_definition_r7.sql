-- R7-1/R7-2（V2.0 §17.2 表所有权、§24.2 R7-0 口径、§24.3/§24.4）
-- 本迁移只动 metric_definition 与注释，**不 DROP 任何表**（数据迁移/读写切换完成前禁止丢数据）。

-- 1) refund_rate 口径修正（V2.0 §24.2）：只计「发生并已完成退款」的订单，不再计全部退款申请
UPDATE metric_definition
SET formula = '有已完成退款的订单数/支付订单数',
    definition_version = 'v2'
WHERE metric_code = 'refund_rate';

-- 2) 新增 full_refund_rate（全额退款率，与 Hive ADS / MySQL ads_operation_overview_m.full_refund_rate 对齐）
--    用 INSERT ... WHERE NOT EXISTS 而不是 ON DUPLICATE KEY UPDATE ... VALUES()：后者在 MySQL 8.0 起已弃用，
--    且本脚本只需幂等一次，不依赖 metric_definition 上是否已有 metric_code 唯一键。
INSERT INTO metric_definition (metric_code, metric_name, formula, grain, default_time_field, unit, definition_version)
SELECT 'full_refund_rate', '全额退款率', '全额退款订单数/支付订单数', 'day', 'paid_at', '', 'v1'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE metric_code = 'full_refund_rate');

-- 3) §17.2 表所有权记录（只记录，不执行 DDL）
--    metric_snapshot / metric_value 的**唯一所有者是 analytics_metric**（db/metric/V1__metric_store.sql）。
--    analytics_meta 中由 db/meta/V2__platform_pipeline_quality.sql 创建的 metric_snapshot / metric_value
--    自 R7 起为**弃用副本**：平台代码不再读写（MySqlMetricStore 走 metricPublish/metricRead JdbcTemplate，
--    metric.mapper 扫描已移除），本期保留数据不 DROP，待数据迁移与读写切换完成后单独清理迁移，禁止直接 drop 丢数据。
--    弃用副本在编写本迁移时的实际行数（2026-09-10 用 meta_app 账号实测，供后续清理时对账）：
--      analytics_meta.metric_snapshot = 9 行
--      analytics_meta.metric_value    = 132 行
--    清理前的期望：业务不再增长（两个数应在切换后保持不变，若继续增长说明仍有旧写入路径）。
