-- =====================================================================
-- V4: AI 审计（§24.1 ai_query_history/ai_model_call_log）+ MySQL 侧物化 ADS 宽表
-- 物化表是 Text-to-SQL 的白名单查询目标（§8.1：AI 只查 ADS 白名单），
-- 由 AdsMaterializer 在快照发布成功后刷新（PipelineService PUBLISH_METRIC 后）。
-- =====================================================================

-- AI 查询历史（§6.6 ai_query_history：问题、SQL、状态、耗时、反馈）
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
CREATE TABLE IF NOT EXISTS ads_operation_overview_m (
    dt              VARCHAR(16) NOT NULL,
    pv              BIGINT      NOT NULL DEFAULT 0,
    uv              BIGINT      NOT NULL DEFAULT 0,
    dau             BIGINT      NOT NULL DEFAULT 0,
    paid_order_cnt  BIGINT      NOT NULL DEFAULT 0,
    gmv             DECIMAL(18,4) NOT NULL DEFAULT 0,
    net_sale_amount DECIMAL(18,4) NOT NULL DEFAULT 0,
    avg_order_value DECIMAL(18,4) NULL,
    refund_rate     DECIMAL(18,4) NULL,
    snapshot_id     VARCHAR(64) NOT NULL,
    PRIMARY KEY (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物化ADS 运营大盘(快照发布后刷新)';

-- 物化 ADS：销售趋势
CREATE TABLE IF NOT EXISTS ads_sale_trend_m (
    dt              VARCHAR(16) NOT NULL,
    order_count     BIGINT      NOT NULL DEFAULT 0,
    buyer_count     BIGINT      NOT NULL DEFAULT 0,
    sale_amount     DECIMAL(18,4) NOT NULL DEFAULT 0,
    net_sale_amount DECIMAL(18,4) NOT NULL DEFAULT 0,
    avg_order_value DECIMAL(18,4) NULL,
    snapshot_id     VARCHAR(64) NOT NULL,
    PRIMARY KEY (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物化ADS 销售趋势';

-- 物化 ADS：转化漏斗
CREATE TABLE IF NOT EXISTS ads_behavior_funnel_m (
    dt              VARCHAR(16) NOT NULL,
    stage           VARCHAR(16) NOT NULL,
    user_count      BIGINT      NOT NULL DEFAULT 0,
    conversion_rate DECIMAL(18,4) NULL,
    snapshot_id     VARCHAR(64) NOT NULL,
    PRIMARY KEY (dt, stage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物化ADS 转化漏斗';