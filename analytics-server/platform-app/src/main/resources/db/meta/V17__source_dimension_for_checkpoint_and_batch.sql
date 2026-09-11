-- =====================================================================
-- V17: P1-05 断点与批次补「源」维度（决策记录 D-037）
-- 1) file_checkpoint：新增 source_id，并把唯一键由
--    uk_ckpt (runtime_profile_id, file_path, file_identity)
--    替换为 uk_ckpt_source (runtime_profile_id, source_id, file_path, file_identity)
-- 2) ingestion_batch：新增 source_id（归因列，允许可空）
--
-- 为什么必须改键（不是"更保险"，是"否则功能落空"）：
--   「当前激活源」= runtime_profile(ACTIVE).source_id（D-035 裁决 ②），切换源改的是
--   同一行 profile 的 source_id ⇒ 同一个 runtime_profile_id 会在不同时刻服务不同源。
--   旧键不含 source_id 且**比新键更严**：只要它还在，第二个源为同一路径插断点行会被
--   旧键直接拒绝，P1-05 的目的（同路径两源互不推进断点）无法达成；保留双键自相矛盾。
--   因此本迁移删除旧索引 —— Deletion Class: structural-only（只删索引，不动任何行、
--   不删列）。这不是数据删除：与 V8 已做过的 DROP PRIMARY KEY 同一性质。
--
-- 该列为什么必须非空：source_id 在唯一键里，可空会让唯一性失效（MySQL 唯一索引允许
--   多个 NULL 组合）。回填后由 MODIFY ... NOT NULL 收紧，**收紧语句本身就是断言**：
--   若仍有行 source_id 为空（profile 不存在 / profile.source_id 为空），迁移失败中止，
--   而不是静默给默认源。明确不做 DEFAULT 1 / SET source_id = 1 之类的兜底 —— 那会把
--   「来源不明」伪装成「来自 mock-mall」。
--
-- 回填口径：按 file_checkpoint.runtime_profile_id → runtime_profile.source_id 对齐
--   （本轮实测：file_checkpoint 102 行全部 runtime_profile_id=1，runtime_profile 该行
--   source_id=1 ⇒ 102 行全部回填为源 1=mock-mall）。历史 DEF-13 的 50 行冗余副本
--   **原样保留**（合并副本涉数据删除，须用户书面确认，不在 P1-05）。
--
-- 执行前置：本脚本要求 V16 已应用（source_registry 存在且已回填 runtime_profile.source_id）。
--   **代码支持（LocalFileIngestor/IngestionService 写 source_id）未上线前，不得对真实
--   analytics_meta 执行本脚本**：旧 jar 写断点会因 NOT NULL 报错（fail-closed，不会写脏）。
-- 回滚：无自动回滚。手工回退 = 删唯一键 uk_ckpt_source、重建 uk_ckpt、删列（须书面确认）。
-- =====================================================================

-- 1) file_checkpoint：先加可空列（存量行此刻还没有源归属）
ALTER TABLE file_checkpoint
    ADD COLUMN source_id BIGINT NULL COMMENT '源登记（source_registry.id，D-037）：断点归属的源，切换源不共享断点' AFTER runtime_profile_id;

-- 1.1) 回填：以 profile 上登记的源为准（只写新列，不改任何既有列）
UPDATE file_checkpoint c
    JOIN runtime_profile p ON p.id = c.runtime_profile_id
    SET c.source_id = p.source_id
    WHERE c.source_id IS NULL;

-- 1.2) 收紧为非空（若有行仍为空则本语句报错 → 整个迁移失败，人工介入）
ALTER TABLE file_checkpoint
    MODIFY COLUMN source_id BIGINT NOT NULL COMMENT '源登记（source_registry.id，D-037）：断点归属的源，切换源不共享断点';

-- 1.3) 唯一键替换 + 外键（MySQL 不会为外键列自动建索引，显式加 KEY）
ALTER TABLE file_checkpoint
    DROP INDEX uk_ckpt,
    ADD UNIQUE KEY uk_ckpt_source (runtime_profile_id, source_id, file_path, file_identity),
    ADD KEY idx_file_checkpoint_source (source_id),
    ADD CONSTRAINT fk_file_checkpoint_source FOREIGN KEY (source_id) REFERENCES source_registry (id);

-- 2) ingestion_batch：归因列（允许可空——不在唯一键里，NULL 诚实表示"未标注"）
ALTER TABLE ingestion_batch
    ADD COLUMN source_id BIGINT NULL COMMENT '源登记（source_registry.id，D-037）：批次归属的源；历史行可空' AFTER runtime_profile_id,
    ADD KEY idx_ingestion_batch_source (source_id),
    ADD CONSTRAINT fk_ingestion_batch_source FOREIGN KEY (source_id) REFERENCES source_registry (id);

UPDATE ingestion_batch b
    JOIN runtime_profile p ON p.id = b.runtime_profile_id
    SET b.source_id = p.source_id
    WHERE b.source_id IS NULL;
