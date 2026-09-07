# R1 指标服务：ADS 物化宽表（拆分自 V4__ai_audit.sql，AI 白名单，整改书 §8）

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
