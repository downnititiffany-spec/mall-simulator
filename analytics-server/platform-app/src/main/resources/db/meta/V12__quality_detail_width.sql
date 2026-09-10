-- R6-13 修正（真实冒烟 run 16 实测触发）：quality 规则的作用对象与明细可能列出多张表/多条计数，
-- 原 VARCHAR(255)/VARCHAR(512) 在多表规则（如 ADS_STAGING_PRESENT 列出 8 张暂存表）下溢出：
--   Data truncation: Data too long for column 'target_table'
-- 规则明细是排障依据，不应因列宽丢失，故按"一次 run 的全部规则明细"实际体量放宽；
-- 写入侧同时做长度上限保护（不依赖 DDL 兜底）。
ALTER TABLE data_quality_result
    MODIFY COLUMN target_table VARCHAR(500) NULL COMMENT 'R6-13 规则作用对象（表名/分区范围，多表时列出）',
    MODIFY COLUMN detail VARCHAR(2000) NULL COMMENT 'R6-13 规则明细（失败原因/实测计数）';
