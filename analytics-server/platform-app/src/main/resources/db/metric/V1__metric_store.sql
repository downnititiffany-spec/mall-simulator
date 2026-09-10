-- R7-2（V2.0 §17.2 表所有权 / §17.3 快照数据结构 / §24.4）：
--   analytics_metric 是指标快照的唯一所有者：metric_snapshot / metric_value / 所有 ads_*_m。
--   analytics_meta 中 V2 建的同名表自 R7 起为「弃用副本」（见 db/meta/V13__metric_definition_r7.sql 注释），
--   本期不 DROP，数据迁移与读写切换完成后再清理。
-- 库当前为新建空库（0 表、Flyway 未执行过），因此本脚本直接按 R7 目标结构编写，无需兼容历史版本。

-- 指标快照：BUILDING→VERIFYING→ACTIVE；旧 ACTIVE→ARCHIVED；验证/发布失败 → FAILED（§17.5）
CREATE TABLE metric_snapshot (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    snapshot_id             VARCHAR(64)  NOT NULL COMMENT '快照发布号（唯一）',
    runtime_profile_id      BIGINT       NOT NULL DEFAULT 1 COMMENT '运行环境；同 profile 最多一个 ACTIVE',
    runtime_profile_version INT          NULL COMMENT '§8.1 实际 profile 版本',
    business_time           DATETIME(3)  NOT NULL COMMENT '快照业务时间',
    pipeline_run_id         BIGINT       NULL COMMENT '来源流水线实例',
    status                  VARCHAR(24)  NOT NULL COMMENT 'BUILDING/VERIFYING/ACTIVE/ARCHIVED/FAILED',
    version                 INT          NOT NULL DEFAULT 1 COMMENT '同一业务时间的第 N 次发布',
    definition_version      VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '本次快照使用的指标口径版本',
    data_updated_at         DATETIME(3)  NOT NULL COMMENT 'ADS 数据时间',
    published_at            DATETIME(3)  NULL COMMENT '切换为 ACTIVE 的时间',
    source                  VARCHAR(32)  NOT NULL DEFAULT 'spark-ads' COMMENT '§17.6 成功快照只接受 spark-ads',
    failure_reason          VARCHAR(512) NULL COMMENT 'FAILED 原因（§17.5 第 6 步）',
    active_flag             TINYINT      NULL COMMENT 'ACTIVE=1，其它状态=NULL：可空唯一列实现同 profile 唯一 ACTIVE',
    created_at              DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshot_id (snapshot_id),
    -- MySQL 唯一索引允许多个 NULL → 非 ACTIVE 行不受限，ACTIVE 行按 profile 唯一
    UNIQUE KEY uk_active_profile (runtime_profile_id, active_flag),
    KEY idx_snapshot_status (runtime_profile_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标快照（唯一所有者 analytics_metric）';

-- 快照指标值（看板/AI 统一入口）：唯一键含 period 与规范化维度串，支持多日期/多维度值（§17.3）
CREATE TABLE metric_value (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    snapshot_id        VARCHAR(64)   NOT NULL,
    metric_code        VARCHAR(64)   NOT NULL,
    metric_value       DECIMAL(18,4) NOT NULL,
    unit               VARCHAR(16)   NOT NULL DEFAULT '',
    period             VARCHAR(32)   NOT NULL DEFAULT '' COMMENT 'day:2026-09-01',
    dimension_json     VARCHAR(512)  NULL COMMENT '维度原始 JSON（可空）',
    dimension_key      VARCHAR(255)  NULL COMMENT '规范化维度串（无维度写空串，参与唯一键；NULL 不参与唯一约束）',
    definition_version VARCHAR(16)   NOT NULL DEFAULT '',
    updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshot_metric_period_dim (snapshot_id, metric_code, period, dimension_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='快照指标值';
