-- =====================================================================
-- V3: 指标服务与流水线元数据（§21.11 快照发布、§23.1 流水线、§24.1 元数据表）
-- =====================================================================

-- 指标字典（唯一口径来源的落库副本；docs/contracts/metric-dictionary.md 为权威文档）
CREATE TABLE metric_definition (
    metric_code        VARCHAR(64)  NOT NULL,
    metric_name        VARCHAR(128) NOT NULL,
    formula            VARCHAR(512) NOT NULL COMMENT '口径公式',
    grain              VARCHAR(32)  NOT NULL COMMENT 'day/hour/user×period',
    default_time_field VARCHAR(32)  NOT NULL COMMENT 'event_time/paid_at',
    unit               VARCHAR(16)  NOT NULL DEFAULT '',
    definition_version VARCHAR(16)  NOT NULL,
    PRIMARY KEY (metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标字典';

INSERT INTO metric_definition (metric_code, metric_name, formula, grain, default_time_field, unit, definition_version) VALUES
 ('pv', '浏览量', 'count(view 行为事件)', 'day', 'event_time', '次', 'v1'),
 ('uv', '浏览用户数', 'count(distinct user_id where behavior_type=view)', 'day', 'event_time', '人', 'v1'),
 ('dau', '日活跃用户数', 'count(distinct user_id 当日任一有效行为)', 'day', 'event_time', '人', 'v1'),
 ('cart_rate', '加购率', '加购用户数/浏览用户数', 'day', 'event_time', '', 'v1'),
 ('buy_rate', '购买转化率', '支付用户数/浏览用户数', 'day', 'event_time', '', 'v1'),
 ('paid_order_cnt', '支付订单数', 'count(distinct order_id where final_paid_flag=1)', 'day', 'paid_at', '单', 'v1'),
 ('gmv', '销售额(GMV)', 'sum(paid_amount)', 'day', 'paid_at', '元', 'v1'),
 ('net_sale', '净销售额', 'GMV - sum(refund_completed.amount)', 'day', 'paid_at', '元', 'v1'),
 ('avg_order_value', '客单价', 'GMV/支付订单数', 'day', 'paid_at', '元', 'v1'),
 ('refund_rate', '退款率', '退款订单数/支付订单数', 'day', 'paid_at', '', 'v1'),
 ('repeat_rate', '复购率(有效)', '支付订单数>=2 的用户数/支付用户数', 'user×period', 'paid_at', '', 'v1'),
 ('product_heat', '商品热度', '1×ln(1+PV)+2×ln(1+收藏)+3×ln(1+加购)+5×ln(1+支付件数)', 'day', 'event_time', '', 'v1'),
 ('user_value_level', '用户价值等级', 'RFM 分位数八类', 'user×period', '截止日', '', 'v1'),
 ('stock_days', '库存覆盖天数', '可售库存/日均销量', 'day', '', '天', 'v1'),
 ('stock_shortage_rate', '缺货率', '缺货商品数/在售商品数', 'day', '', '', 'v1');

-- 指标快照（§21.11：BUILDING→VERIFYING→ACTIVE；旧 ACTIVE→ARCHIVED；同环境同业务时间唯一 ACTIVE）
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
CREATE TABLE pipeline_run (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    idempotency_key     VARCHAR(128) NOT NULL,
    runtime_profile_id  BIGINT       NOT NULL,
    pipeline_code       VARCHAR(64)  NOT NULL COMMENT 'DAILY_CORE/HOURLY_CORE',
    business_time       DATETIME(3)  NOT NULL,
    source_data_version VARCHAR(64)  NULL,
    attempt_no          INT          NOT NULL DEFAULT 1,
    status              VARCHAR(24)  NOT NULL,
    error_code          VARCHAR(64)  NULL,
    trace_id            VARCHAR(64)  NOT NULL,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流水线实例';

-- 阶段实例（§23.1：每阶段记录输入/输出/外部任务 ID/错误码）
CREATE TABLE pipeline_stage_run (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    run_id         BIGINT       NOT NULL,
    stage_code     VARCHAR(32)  NOT NULL,
    status         VARCHAR(24)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/SKIPPED',
    external_job_id VARCHAR(64) NULL COMMENT '集群模式 Spark 任务 ID',
    records        BIGINT       NOT NULL DEFAULT 0,
    error_code     VARCHAR(64)  NULL,
    started_at     DATETIME(3)  NULL,
    finished_at    DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_stage_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流水线阶段实例';

-- 数据质量结果（§5.4）
CREATE TABLE data_quality_result (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    run_id      BIGINT       NOT NULL,
    rule_code   VARCHAR(64)  NOT NULL,
    check_count BIGINT       NOT NULL,
    error_count BIGINT       NOT NULL,
    error_rate  DECIMAL(10,6) NOT NULL,
    threshold   VARCHAR(64)  NOT NULL,
    passed      INT          NOT NULL COMMENT '1=通过 0=失败',
    detail      VARCHAR(512) NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_quality_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据质量结果';