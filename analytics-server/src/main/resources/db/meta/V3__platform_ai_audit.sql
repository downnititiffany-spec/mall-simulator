# R1 平台元数据：AI 审计（拆分自 V4__ai_audit.sql，整改书 §7.3）

CREATE TABLE ai_query_history (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      VARCHAR(64)  NOT NULL DEFAULT 'demo',
    question     VARCHAR(1024) NOT NULL,
    sql_text     TEXT         NULL,
    tables       VARCHAR(255) NULL,
    status       VARCHAR(32)  NOT NULL COMMENT 'GENERATED/SAFE/REPAIRED/EXECUTED/FAILED',
    rows_returned INT         NULL,
    elapsed_ms   BIGINT       NULL,
    feedback     VARCHAR(16)  NULL COMMENT 'GOOD/BAD',
    errors       VARCHAR(512) NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_history_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 自然语言查询历史';

-- AI 模型调用日志（§6.6 ai_model_call_log：供应商、令牌、耗时、状态）
CREATE TABLE ai_call_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    use_case        VARCHAR(32)  NOT NULL COMMENT 'sql_generation/sql_repair/explanation/analysis',
    provider        VARCHAR(64)  NOT NULL COMMENT 'openai-compat/mock/rule-based',
    model           VARCHAR(64)  NULL,
    prompt_version  VARCHAR(32)  NULL,
    input_tokens    INT          NULL,
    output_tokens   INT          NULL,
    elapsed_ms      BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    error           VARCHAR(512) NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模型调用日志';

-- 物化 ADS：运营大盘（§6.5 ads_operation_overview 的指标宽表形态）
