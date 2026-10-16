-- S3-03（设计 §11.2 L433 复购率口径 / §11.4 L449 复购变体 / 字典 `metric-dictionary.md:30` 源表 dws_user_trade_period）
-- `ads_operation_overview_m` 补复购率及其**观察期声明**：
--   repeat_rate          = 有效复购率 = 观察期内有效购买 ≥2 次用户数 / 观察期支付用户数（完全退款订单不算有效购买）
--   repeat_period_start  = 该复购率实际使用的观察期起点（ISO yyyy-MM-dd）
--   repeat_period_end    = 观察期终点（ISO yyyy-MM-dd）
--
-- 为什么窗口要和指标一起落库：设计 §11.2 L433 要求"必须声明观察期和变体"。复购率不是单日指标，
-- 但发布侧原先只能写 `metric_value.period = day:<dt>`（单日）；这三列把上游 DWS 行自己写入的
-- `period_start/period_end`（唯一所有者 = `DwsSql.userTradePeriod` 的作业入参）随行声明出来，
-- 发布侧据此写 `window:<起>..<止>`，避免"作业按 A 窗口算、指标谎报单日"。
--
-- 为什么是**加性 ALTER**而不是改 V2 的建表语句：V2 是已发布迁移，改动会破坏 Flyway checksum（治理门 ③）。
-- Hive 侧的 `ALTER TABLE … ADD COLUMNS` 只能追加列，故 MySQL 也追加在同一位置，
-- 与 spark-jobs 的 `MetricAdsSpec`（列真源）和 `MetricAdsCatalog`（白名单）末尾列序保持一致。
--
-- 空值语义：三列都允许 NULL —— 该业务日**没有支付用户**时分母为 0，复购率与观察期声明同时为 NULL
-- （不伪造窗口、不写 0 冒充"没有人复购"）。历史快照行同样为 NULL，表示"未计算"而非"复购率 0"；
-- 需要历史复购率时应重跑发布，由 Hive ADS 重算并覆盖写入。
ALTER TABLE ads_operation_overview_m
    ADD COLUMN repeat_rate DECIMAL(8,4) NULL COMMENT '有效复购率=有效购买≥2次用户数/支付用户数（全退订单不算有效购买）',
    ADD COLUMN repeat_period_start VARCHAR(16) NULL COMMENT '复购率观察期起点（ISO yyyy-MM-dd，来自 DWS 行声明）',
    ADD COLUMN repeat_period_end VARCHAR(16) NULL COMMENT '复购率观察期终点（ISO yyyy-MM-dd，来自 DWS 行声明）';
