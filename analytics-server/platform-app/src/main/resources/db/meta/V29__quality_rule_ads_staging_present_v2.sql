-- =====================================================================
-- V29: ADS_STAGING_PRESENT v2 —— 区分“合法 0 行专题”与“分区缺失”
--
-- Stage 7 T-R1 的真实 REFERENCE_MALL_HTTP 链路证明：同一业务日可以有合法交易，
-- 但没有通用 behavior 事件。此时 ads_hot_product / ads_product_conversion 等专题
-- 会形成真实存在、Location 可读、rowCount=0 的暂存分区。v1 将 rowCount=0 与
-- “分区不存在”合并为失败，会把合法空态误判为发布缺失。
--
-- 为什么发 v2 而不修改 V19：V19 已发布，且 quality_rule_definition 的唯一键就是
-- (source_scope, rule_code, version)。历史 run 仍需能够解释 v1 的“存在且非空”语义；
-- 当前运行由目录选择同规则码的最高启用版本 v2。
--
-- v2 只收窄“就绪”的判据：8 张本次 snapshot+dt 暂存分区必须真实存在，且 Hive
-- 元数据 Location 非空；rowCount=0 允许。缺分区或无 Location 仍是 BLOCKING。
-- 严重度、阈值模式均未改变，也没有修改任何既有行。
--
-- checksum = SHA-256(
--   ADS_STAGING_PRESENT \x1f 2 \x1f * \x1f ADS \x1f BLOCKING \x1f FIXED \x1f
--   <empty> \x1f 1 \x1f <empty> \x1f <empty>)
-- 与 QualityRuleDefinition#checksum() 的字段顺序完全一致。
--
-- 【本迁移在真库上的执行状态：未执行】
-- 本文件仅进入版本库；不得据此声称已在 3306 或任何正式库生效。
-- =====================================================================

INSERT IGNORE INTO quality_rule_definition
    (rule_code, version, source_scope, stage, severity, severity_mode,
     threshold_json, enabled, effective_from, effective_to, checksum)
VALUES
    ('ADS_STAGING_PRESENT', 2, '*', 'ADS', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL,
     '0c2240d1e998625cc319689f50d2810ccefa8ad7f1b2740961bafcd37b58014c');
