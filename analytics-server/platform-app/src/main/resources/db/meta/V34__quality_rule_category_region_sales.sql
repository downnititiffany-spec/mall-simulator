-- =====================================================================
-- V34: 分类/城市等级销售 ADS 的质量规则契约（N31-02 / D-058）
--
-- 本迁移为新 ADS 生产表登记分类与城市等级逐日销售额对账规则，并追加：
--   * ADS_STAGING_PRESENT v3：对当前登记的全部 ADS 暂存表检查分区存在及 Location；
--   * MXP_EXPORT_COMPLETE v2：对当前 ADS 白名单表集检查导出完成和行数。
--
-- V1–V33 均为历史迁移，不在此处修改。INSERT IGNORE 使重复部署幂等；
-- 不删除或覆盖任何历史规则版本。
-- 【本迁移在真库上的执行状态：未执行】本文件仅定义迁移内容。
--
-- checksum 使用 QualityRuleDefinition#checksum() 的 SHA-256 字段协议：
-- ruleCode \x1f version \x1f * \x1f stage \x1f BLOCKING \x1f FIXED \x1f
-- <empty> \x1f 1 \x1f <empty> \x1f <empty>
-- =====================================================================

INSERT IGNORE INTO quality_rule_definition
    (rule_code, version, source_scope, stage, severity, severity_mode,
     threshold_json, enabled, effective_from, effective_to, checksum)
VALUES
    ('ADS_CATEGORY_SALE_RECONCILE', 1, '*', 'ADS', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL,
     'eadefec013697b36d989a470e0204fcf73709a36f80f75a81ecb1b08f880cc8b'),
    ('ADS_REGION_SALE_RECONCILE', 1, '*', 'ADS', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL,
     'aeb3a926fec8564c4bbb03de997bdf6b11560b4f17290a7fbea9ba8bde6cd8e8'),
    ('ADS_STAGING_PRESENT', 3, '*', 'ADS', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL,
     '6faf3b4bb3c1fff74e303acf9eaa35183ed6ae3223bcfec53259267384d308e8'),
    ('MXP_EXPORT_COMPLETE', 2, '*', 'PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL,
     '9a6f340e63633cc40e6199753c25ab106c5c501b5d833e59b5975ed690a76944');
