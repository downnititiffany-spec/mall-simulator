-- =====================================================================
-- V8: R3 采集整改（整改书 §9）
-- 1) file_checkpoint 重建：唯一键至少含 runtime_profile_id +
--    absolute_source_id + file_identity（整改书 §9.2："不能只用文件名"）
-- 2) ingestion_batch 增加 runtime_profile_id（每次采集归属运行环境）
-- 3) pipeline_stage_run 增加 evidence（§13.2 WAIT_LANDING 必须保存
--    batchId、URI、checksum、records 证据）
-- =====================================================================

-- 1) file_checkpoint：改为自增主键 + 复合唯一键
--    （Taildir inode 等价：Windows 用创建时间戳做 file_identity，文件删除重建即新版本）
--    注意：加 AUTO_INCREMENT 列不支持 LOCK=NONE，须用 LOCK=SHARED
ALTER TABLE file_checkpoint
    DROP PRIMARY KEY,
    ADD COLUMN id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键' FIRST,
    ADD COLUMN runtime_profile_id BIGINT   NOT NULL DEFAULT 1 COMMENT '运行环境（§9.2）',
    ADD COLUMN file_identity VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '文件身份（创建时间戳，变化视为新文件）',
    ADD PRIMARY KEY (id),
    ADD UNIQUE KEY uk_ckpt (runtime_profile_id, file_path, file_identity),
    ALGORITHM = INPLACE, LOCK = SHARED;

-- 2) ingestion_batch：批次归属运行环境（manifest 要写 runtimeProfileId）
ALTER TABLE ingestion_batch
    ADD COLUMN runtime_profile_id BIGINT NOT NULL DEFAULT 1 COMMENT '采集归属的运行环境' AFTER source,
    ALGORITHM = INPLACE, LOCK = SHARED;

-- 3) pipeline_stage_run：阶段证据（WAIT_LANDING 的 manifest 信息等，§13.2）
ALTER TABLE pipeline_stage_run
    ADD COLUMN evidence VARCHAR(500) NULL COMMENT '阶段证据 JSON（如 WAIT_LANDING 的 batchId/URI/checksum）' AFTER error_code,
    ALGORITHM = INPLACE, LOCK = SHARED;