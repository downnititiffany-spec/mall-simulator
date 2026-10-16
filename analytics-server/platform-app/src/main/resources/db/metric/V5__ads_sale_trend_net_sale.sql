-- S3-02（设计 §9.3 L333「ads_sale_trend：历史已发布，net_sale 等字段需补」/ §11.2 L428 净销售额口径）
-- `ads_sale_trend_m` 补净销售额列，与 `ads_operation_overview_m.net_sale_amount` 同义（同 dt 必须逐值相等）。
--
-- 为什么是**加性 ALTER**而不是改 V2 的建表语句：V2 是已发布迁移，改动会破坏 Flyway checksum
-- （治理门 ③）。Hive 侧的 `ALTER TABLE … ADD COLUMNS` 只能追加列，故 MySQL 也追加在同一位置，
-- 与 spark-jobs 的 `MetricAdsSpec`（列真源）和 `MetricAdsCatalog`（白名单）末尾列序保持一致。
--
-- 回填语义：`NOT NULL DEFAULT 0` —— 历史快照行补 0 而不是 NULL。历史行确实没有净额数据，
-- 0 是"未知/未回填"的占位；旧快照的净额**不得**据此当作真实 0 参与分析（需要旧快照净额时应重跑发布，
-- 由 Hive ADS 重算并覆盖写入）。新快照由 Hive 侧 `net_sale_amount` 直接决定，缺列时发布侧会先报错。
ALTER TABLE ads_sale_trend_m
    ADD COLUMN net_sale_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '净销售额=销售额-已支付订单退款额（同 ads_operation_overview_m.net_sale_amount）';
