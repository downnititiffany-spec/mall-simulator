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
--   （PipelineBatchConsumptionMigrationScriptTest）+ 真库夹具升级验证
--   （PipelineBatchConsumptionUpgradeMySqlIT，含「同批多次成功发布」）代替真库验证。
--
-- 事故与重构记录（G31-12，总控 G31-11 复核第 1 项）：
--   * 首版回填（2026-09-25 随 G31-11 落地，Flyway checksum -75448211）用
--     「INSERT ... SELECT ... ON DUPLICATE KEY UPDATE id = pipeline_batch_consumption.id」
--     做「撞行不覆盖」。当同一 (source_id, batch_id) 存在多条 SUCCESS run
--     （同批多次成功发布，例如正式库批 24 被 run 22/23 先后发布）时，SELECT 结果集
--     内部就含重复键，ODKU 无法消解语句内自撞 → 正式库执行触发 MySQL 1567，
--     flyway_schema_history 该行置 success=0。
--   * 现场补偿（按原貌保留，不算「Flyway repair 已修复迁移」）：人工将 history 行
--     置 success=1，并按既成事实逐行手工回填（sql-repair2-backfill.sql，已归档
--     v3-archive/g3111；批 22 publish_count=1、批 24 publish_count=2 快照
--     S20260901_23）。当前库恢复可用，但「从已有 V32 数据正常升级」路径未闭合。
--   * 本版重构（checksum 随之变更，需在依赖库重锚）：按构造去重——先对
--     (source_id, input_batch_id) GROUP BY 聚合（COUNT(*)=publish_count，MIN/MAX
--     run id 给出 first/last 消费 run），再 INSERT IGNORE 落行。语句内不存在重复键
--     ⇒ 不会 1567；重复执行或与 PIPELINE/人工补偿行撞键 ⇒ IGNORE 跳过，既有行
--     绝不改写。字段语义与人工补偿一致：consumed_by/快照取最新 run，
--     first_consumed/consumed_at 取最早 run（finished_at，缺省回退 updated_at）。
--     代价：INSERT IGNORE 会把部分数据类错误降级为告警——本语句源/目标列均为
--     受控字面量与 BIGINT/DATETIME 直拷，实际风险可忽略，故接受。
--   * 原始触发语句按原样保留于下方注释（证据留痕，永不执行）。
--
-- 【原始首版回填语句——已触发 1567，仅留痕，不执行】
-- INSERT INTO pipeline_batch_consumption
--     (source_id, batch_id, status, consumed_by_run_id, first_consumed_by_run_id,
--      target_snapshot_id, publish_count, recalc_count, consumed_at, created_via)
-- SELECT r.source_id, r.input_batch_id, 'CONSUMED', r.id, r.id,
--        r.target_snapshot_id, 1, 0,
--        COALESCE(r.finished_at, r.updated_at), 'BACKFILL_V33'
-- FROM pipeline_run r
-- WHERE r.status = 'SUCCESS'
--   AND r.input_batch_id IS NOT NULL
--   AND r.source_id IS NOT NULL
-- ON DUPLICATE KEY UPDATE id = pipeline_batch_consumption.id;
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

-- 历史回填（G31-12 重构：按构造去重 + INSERT IGNORE，幂等）：把「已发布过的批次」
-- 补进台账，防止 V33 上线后这些批次被 FIFO 重新消费造成重发布（尤其保护正式锚
-- S20260901_23 的 ACTIVE 快照不漂移）。同批多条 SUCCESS run 聚合为一行
-- （publish_count=COUNT(*)），语句内无重复键，撞既有行（含人工补偿行）不覆盖。
INSERT IGNORE INTO pipeline_batch_consumption
    (source_id, batch_id, status, consumed_by_run_id, first_consumed_by_run_id,
     target_snapshot_id, publish_count, recalc_count, consumed_at, created_via)
SELECT g.source_id, g.batch_id, 'CONSUMED', lr.id, fr.id,
       lr.target_snapshot_id, g.publish_count, 0,
       COALESCE(fr.finished_at, fr.updated_at), 'BACKFILL_V33'
FROM (
    SELECT r.source_id, r.input_batch_id AS batch_id,
           MIN(r.id) AS first_run_id, MAX(r.id) AS last_run_id,
           COUNT(*) AS publish_count
    FROM pipeline_run r
    WHERE r.status = 'SUCCESS'
      AND r.input_batch_id IS NOT NULL
      AND r.source_id IS NOT NULL
    GROUP BY r.source_id, r.input_batch_id
) g
JOIN pipeline_run lr ON lr.id = g.last_run_id
JOIN pipeline_run fr ON fr.id = g.first_run_id;
