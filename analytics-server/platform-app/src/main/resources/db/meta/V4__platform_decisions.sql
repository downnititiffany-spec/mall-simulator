-- =====================================================================
-- V5: 决策中心（§22.6 决策生命周期、§21.10 效果评价）
-- =====================================================================

CREATE TABLE decision_task (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    decision_no            VARCHAR(64)   NOT NULL,
    source                 VARCHAR(16)   NOT NULL COMMENT 'ai=AI草稿(只能DRAFT) / human=人工创建',
    suggestion_snapshot_id VARCHAR(64)   NULL COMMENT 'AI 建议对应的证据快照',
    title                  VARCHAR(255)  NOT NULL,
    action                 VARCHAR(1024) NOT NULL,
    target_metric_code     VARCHAR(64)   NULL COMMENT '目标指标（无目标指标只能作为一般提示，§22.3）',
    target_direction       VARCHAR(8)    NULL COMMENT 'UP=越高越好 / DOWN=越低越好',
    baseline_value         DECIMAL(18,4) NULL COMMENT '批准时从当前 ACTIVE 快照锁定的基线值',
    target_value           DECIMAL(18,4) NULL,
    eval_window_days       INT           NOT NULL DEFAULT 3,
    owner                  VARCHAR(64)   NULL,
    due_date               DATE          NULL,
    status                 VARCHAR(24)   NOT NULL COMMENT '12 态状态机（§22.6）',
    risk                   VARCHAR(255)  NULL,
    reject_reason          VARCHAR(255)  NULL,
    cancel_reason          VARCHAR(255)  NULL,
    created_by             VARCHAR(64)   NOT NULL,
    approved_by            VARCHAR(64)   NULL,
    created_at             DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at             DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    started_at             DATETIME(3)   NULL,
    completed_at           DATETIME(3)   NULL,
    evaluated_at           DATETIME(3)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_decision_no (decision_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='决策任务（AI草稿/人工审核/执行/评价）';

CREATE TABLE decision_evaluation (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    decision_id      BIGINT         NOT NULL,
    baseline_value   DECIMAL(18,4)  NOT NULL,
    actual_value     DECIMAL(18,4)  NULL,
    improvement_rate DECIMAL(10,4)  NULL,
    result           VARCHAR(24)    NOT NULL COMMENT 'EFFECTIVE/PARTIAL/INEFFECTIVE/INSUFFICIENT_DATA',
    note             VARCHAR(512)   NULL,
    evaluated_by     VARCHAR(64)    NOT NULL,
    created_at       DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_eval_decision (decision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='决策效果评价（前后对比，非因果推断，§21.10）';