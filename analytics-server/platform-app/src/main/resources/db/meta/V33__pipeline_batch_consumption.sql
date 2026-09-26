-- =====================================================================
-- G31-11（总控 D-048 §(5) M3 发布语义 / D-049a+e+f）
-- pipeline_batch_consumption：批次消费独立台账（M3 语义②「消费状态独立记录」）
--   * 发布成功才落行：本表唯一写点是 metricPublisher.publish 返回 ok 之后
--     （FAILED/无发布 run 永不写），消费判定既非 manifest READY 单独判定、
--     亦非值相等去重。
--   * UK (source_id, batch_id)：一批次一源一行；显式重算再发布 UPDATE
--     publish_count/recalc_count/last_recalc_*，不改写 first_consumed_*。
--   * 历史回填：以 pipeline_run 中 SUCCESS 且带明确输入批次的 run 为既成
--     消费事实（source_id/input_batch_id 双非空才回填，不猜）；幂等。
-- pipeline_run.recalc_reason：显式重算入口（POST /api/v1/admin/pipeline-runs/recalculate）
--   的理由留痕（D-049e：理由必填，空理由 400 PARAM_INVALID）。
-- 回滚：DROP TABLE pipeline_batch_consumption;
--       ALTER TABLE pipeline_run DROP COLUMN recalc_reason;
--       （回滚前必须先回放导出的快照/消费事实，禁止在发布语义启用后回退）
-- 执行范围：analytics_meta（Flyway classpath:db/meta，MetaFlywayInitializer）
-- 未在 3306 上执行：3306 永久冻结红线（零接触），本脚本不会、也从未在 3306 运行；
--   仅随 MetaFlywayInitializer 在 analytics_meta 正式库迁移时执行；落地前以静态门禁
--   （PipelineBatchConsumptionMigrationScriptTest）+ 隔离库演练代替真库验证。
-- =====================================================================

CREATE TABLE pipeline_batch_consumption (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    source_id                BIGINT       NOT NULL COMMENT '数据源身份（source_registry.id）',
    batch_id                 BIGINT       NOT NULL COMMENT '采集批次号（ingestion_batch.id）',
    status                   VARCHAR(24)  NOT NULL DEFAULT 'CONSUMED' COMMENT 'CONSUMED（本表唯一状态；无待处理/已跳过等其余态）',
    consumed_by_run_id       BIGINT       NOT NULL COMMENT '最近一次消费（发布）本批次的流水线 run',
    first_consumed_by_run_id BIGINT       NOT NULL COMMENT '首次消费本批次的流水线 run（重算不改写）',
    target_snapshot_id       VARCHAR(64)  NULL COMMENT '最近一次消费产出的指标快照',
    publish_count            INT          NOT NULL DEFAULT 1 COMMENT '累计发布次数（首次=1，每次重算再发布递增）',
    recalc_count             INT          NOT NULL DEFAULT 0 COMMENT '显式重算次数',
    last_recalc_reason       VARCHAR(500) NULL COMMENT '最近一次显式重算的理由',
    last_recalc_by           VARCHAR(64)  NULL COMMENT '最近一次显式重算的操作人',
    last_recalc_at           DATETIME(3)  NULL COMMENT '最近一次显式重算时间',
    consumed_at              DATETIME(3)  NOT NULL COMMENT '首次消费时间',
    created_via              VARCHAR(24)  NOT NULL DEFAULT 'PIPELINE' COMMENT 'PIPELINE=发布时写入；BACKFILL_V33=历史回填',
    created_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_consumption (source_id, batch_id),
    KEY idx_batch_consumption_run (consumed_by_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集批次消费台账（发布成功后写入；消费独立记录，非 READY 判定/值去重）';

ALTER TABLE pipeline_run
    ADD COLUMN recalc_reason VARCHAR(500) NULL
        COMMENT '显式重算入口携带的理由（D-049e：必填；空理由 400）' AFTER source_data_version;

-- 历史回填（幂等）：把「已发布过的批次」补进台账，防止 V33 上线后这些批次被
-- FIFO 重新消费造成重发布（尤其保护正式锚 S20260901_23 的 ACTIVE 快照不漂移）。
-- ON DUPLICATE KEY UPDATE id=id：重复执行（或与 PIPELINE 写入撞行）不覆盖既有行。
INSERT INTO pipeline_batch_consumption
    (source_id, batch_id, status, consumed_by_run_id, first_consumed_by_run_id,
     target_snapshot_id, publish_count, recalc_count, consumed_at, created_via)
SELECT r.source_id, r.input_batch_id, 'CONSUMED', r.id, r.id,
       r.target_snapshot_id, 1, 0,
       COALESCE(r.finished_at, r.updated_at), 'BACKFILL_V33'
FROM pipeline_run r
WHERE r.status = 'SUCCESS'
  AND r.input_batch_id IS NOT NULL
  AND r.source_id IS NOT NULL
ON DUPLICATE KEY UPDATE id = pipeline_batch_consumption.id;
