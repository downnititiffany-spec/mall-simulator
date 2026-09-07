-- R1 指标查询/AI 只读账号：仅 analytics_metric SELECT（整改书 §7.2/17.3）
CREATE USER IF NOT EXISTS 'metric_read'@'localhost' IDENTIFIED BY 'metric_read_pw_2026';
GRANT SELECT ON analytics_metric.* TO 'metric_read'@'localhost';
FLUSH PRIVILEGES;
