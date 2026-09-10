-- R7-2（V2.0 §17.3 ADS 服务表 / §24.4）：
-- 页面固定宽表 → MySQL 物化服务表（Hive ADS 为权威来源）。
-- 所有主键都含 snapshot_id：禁止 dt 单列主键，否则新快照写入会覆盖旧快照，无法失败回滚/审计历史。
-- 主键已含 snapshot_id，故不再重复建 snapshot_id 单列索引；按 dt 跨快照查询另建 idx_dt。
-- 列语义与 spark-jobs 的 ads_* 输出一致。

-- 运营大盘（Hive ads_operation_overview）
CREATE TABLE ads_operation_overview_m (
    snapshot_id      VARCHAR(64)   NOT NULL,
    dt               VARCHAR(16)   NOT NULL,
    pv               BIGINT        NOT NULL DEFAULT 0,
    uv               BIGINT        NOT NULL DEFAULT 0,
    dau              BIGINT        NOT NULL DEFAULT 0,
    order_count      BIGINT        NOT NULL DEFAULT 0,
    sale_amount      DECIMAL(18,2) NOT NULL DEFAULT 0,
    net_sale_amount  DECIMAL(18,2) NOT NULL DEFAULT 0,
    avg_order_value  DECIMAL(18,2) NULL,
    refund_rate      DECIMAL(8,4)  NULL,
    full_refund_rate DECIMAL(8,4)  NULL,
    PRIMARY KEY (snapshot_id, dt),
    KEY idx_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADS 运营大盘（快照发布后写入）';

-- 销售趋势（Hive ads_sale_trend）
CREATE TABLE ads_sale_trend_m (
    snapshot_id     VARCHAR(64)   NOT NULL,
    dt              VARCHAR(16)   NOT NULL,
    order_count     BIGINT        NOT NULL DEFAULT 0,
    buyer_count     BIGINT        NOT NULL DEFAULT 0,
    sale_amount     DECIMAL(18,2) NOT NULL DEFAULT 0,
    avg_order_value DECIMAL(18,2) NULL,
    PRIMARY KEY (snapshot_id, dt),
    KEY idx_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADS 销售趋势';

-- 行为转化漏斗（Hive ads_behavior_funnel；每 dt 多行 stage）
CREATE TABLE ads_behavior_funnel_m (
    snapshot_id     VARCHAR(64)  NOT NULL,
    dt              VARCHAR(16)  NOT NULL,
    stage           VARCHAR(16)  NOT NULL COMMENT 'view/intent/order/pay',
    user_count      BIGINT       NOT NULL DEFAULT 0,
    conversion_rate DECIMAL(8,4) NULL COMMENT '相对上一阶段转化率',
    overall_buy_rate DECIMAL(8,4) NULL COMMENT '相对首阶段累计购买率',
    PRIMARY KEY (snapshot_id, dt, stage),
    KEY idx_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADS 行为转化漏斗';
