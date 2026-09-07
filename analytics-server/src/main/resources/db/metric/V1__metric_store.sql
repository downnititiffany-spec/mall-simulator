# R1 指标服务：快照与指标值（拆分自 V3__metrics_pipeline.sql，整改书 §7.3/§15）

CREATE TABLE metric_snapshot (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    snapshot_id        VARCHAR(64)  NOT NULL,
    runtime_profile_id BIGINT       NOT NULL DEFAULT 1,
    business_time      DATETIME(3)  NOT NULL COMMENT '快照业务时间',
    pipeline_run_id    BIGINT       NULL,
    status             VARCHAR(24)  NOT NULL COMMENT 'BUILDING/VERIFYING/ACTIVE/ARCHIVED/FAILED',
    version            INT          NOT NULL DEFAULT 1,
    data_updated_at    DATETIME(3)  NOT NULL,
    published_at       DATETIME(3)  NULL,
    source              VARCHAR(32)  NOT NULL DEFAULT 'local-calculator' COMMENT 'spark-job / local-calculator',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshot_id (snapshot_id),
    KEY idx_snapshot_status (runtime_profile_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标快照';

-- 快照指标值（看板/AI 统一入口）
CREATE TABLE metric_value (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    snapshot_id        VARCHAR(64)  NOT NULL,
    metric_code        VARCHAR(64)  NOT NULL,
    metric_value       DECIMAL(18,4) NOT NULL,
    unit               VARCHAR(16)  NOT NULL DEFAULT '',
    period             VARCHAR(32)  NOT NULL COMMENT 'day:2026-09-01',
    definition_version VARCHAR(16)  NOT NULL,
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshot_metric (snapshot_id, metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='快照指标值';

-- 流水线运行实例（§23.1：幂等键唯一；attempt_no 重跑）
