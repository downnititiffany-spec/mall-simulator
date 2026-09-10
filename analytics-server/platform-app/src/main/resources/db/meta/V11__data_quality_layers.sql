-- R6-13（V2.0 §16.1/§16.5）：data_quality_result 增加规则层次/严重度/作用对象/快照号。
-- 质量规则不再只有"规则名 + 计数"：运维页需要按层次（LANDING/DWD/DWS/ADS_STAGING/PUBLISH）与
-- 严重度（BLOCKING 阻断发布 / ERROR 记录 / INFO 操作审计）展示，并可追溯到具体快照与目标表。
-- 既有行为 NULL（历史数据不臆造层次），新规则一律显式写入。
ALTER TABLE data_quality_result
    ADD COLUMN layer VARCHAR(32) NULL COMMENT 'R6-13 规则层次：LANDING/DWD/DWS/ADS_STAGING/PUBLISH' AFTER rule_code,
    ADD COLUMN severity VARCHAR(16) NULL COMMENT 'R6-13 严重度：BLOCKING/ERROR/INFO' AFTER layer,
    ADD COLUMN target_table VARCHAR(255) NULL COMMENT 'R6-13 规则作用对象（表名/分区范围）' AFTER severity,
    ADD COLUMN snapshot_id VARCHAR(64) NULL COMMENT 'R6-13 本次快照号' AFTER target_table;
