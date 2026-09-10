-- R1 数据库边界（整改书 §7）：同实例三库 + 账号隔离（先于 analytics-server 迁移）
-- 用法: mysql -uroot -p < init-three-dbs.sql
-- 注意: 密码为占位默认值，生产部署必须修改（凭据不入库，见整改书 §8.1）

CREATE DATABASE IF NOT EXISTS mall_business   DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS analytics_meta  DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS analytics_metric DEFAULT CHARSET utf8mb4;

-- 商城应用账号：mall_business 读写（整改书 §7.2）
CREATE USER IF NOT EXISTS 'mall_app'@'localhost' IDENTIFIED BY 'mall_app_pw_2026';
GRANT ALL PRIVILEGES ON mall_business.* TO 'mall_app'@'localhost';

-- 平台元数据账号：analytics_meta 读写
CREATE USER IF NOT EXISTS 'meta_app'@'localhost' IDENTIFIED BY 'meta_app_pw_2026';
GRANT ALL PRIVILEGES ON analytics_meta.* TO 'meta_app'@'localhost';

-- 指标发布账号：analytics_metric 暂存/快照读写 + db/metric 迁移 DDL
-- （R7-1：MetricFlywayInitializer 用该账号执行 classpath:db/metric，需要 CREATE/ALTER/INDEX；
--  刻意不授 DROP —— 破坏性清理必须显式授权，避免迁移脚本误删快照数据）
CREATE USER IF NOT EXISTS 'metric_pub'@'localhost' IDENTIFIED BY 'metric_pub_pw_2026';
GRANT SELECT, INSERT, UPDATE, DELETE ON analytics_metric.* TO 'metric_pub'@'localhost';
GRANT CREATE, ALTER, INDEX, REFERENCES ON analytics_metric.* TO 'metric_pub'@'localhost';

-- 指标查询/AI 只读账号：仅已发布 ADS 表 SELECT（AI 执行器使用；禁止授权商城与元数据库）
CREATE USER IF NOT EXISTS 'metric_read'@'localhost' IDENTIFIED BY 'metric_read_pw_2026';
GRANT SELECT ON analytics_metric.* TO 'metric_read'@'localhost';

FLUSH PRIVILEGES;