-- V31: decision evaluation must persist full equal-length daily window lineage.
-- Existing single-snapshot columns remain as compatibility/display anchors. Historical rows
-- without the new fields are not silently treated as complete windows.
ALTER TABLE decision_task
    ADD COLUMN source_id BIGINT NULL COMMENT '批准时固定的数据源身份' AFTER baseline_snapshot_id,
    ADD COLUMN runtime_profile_id BIGINT NULL COMMENT '批准时固定的运行环境身份' AFTER source_id,
    ADD COLUMN baseline_snapshot_refs TEXT NULL COMMENT '完整基线窗口快照血缘，URL-safe Base64 ids' AFTER runtime_profile_id,
    ADD COLUMN baseline_window_start DATE NULL COMMENT '完整基线窗口起日（含）' AFTER baseline_snapshot_refs,
    ADD COLUMN baseline_window_end DATE NULL COMMENT '完整基线窗口止日（含）' AFTER baseline_window_start;

ALTER TABLE decision_evaluation
    ADD COLUMN baseline_window_start DATE NULL COMMENT '基线窗口起日（含）' AFTER eval_window_days,
    ADD COLUMN baseline_window_end DATE NULL COMMENT '基线窗口止日（含）' AFTER baseline_window_start,
    ADD COLUMN source_id BIGINT NULL COMMENT '本次评价固定的数据源身份' AFTER baseline_window_end,
    ADD COLUMN runtime_profile_id BIGINT NULL COMMENT '本次评价固定的运行环境身份' AFTER source_id,
    ADD COLUMN metric_definition_version VARCHAR(32) NULL COMMENT '指标自身口径版本' AFTER runtime_profile_id,
    ADD COLUMN baseline_sample_count INT NULL COMMENT '完整基线窗口日样本数' AFTER metric_definition_version,
    ADD COLUMN actual_sample_count INT NULL COMMENT '完整实际窗口日样本数' AFTER baseline_sample_count,
    ADD COLUMN baseline_snapshot_refs TEXT NULL COMMENT '完整基线窗口快照血缘，URL-safe Base64 ids' AFTER actual_sample_count,
    ADD COLUMN actual_snapshot_refs TEXT NULL COMMENT '完整实际窗口快照血缘，URL-safe Base64 ids' AFTER baseline_snapshot_refs;
