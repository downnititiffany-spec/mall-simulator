-- =====================================================================
-- M2 S2-03.1 映射激活指针落库（设计 §7.4 预览→激活；D-035 单一所有者口径）
--
-- source_mapping_active：**某个源当前生效的映射画像**的唯一正式所有者。
--   一源一行（PRIMARY KEY (source_id)）：一个源最多一个激活指针，替换即覆盖本行。
--   谁需要"这个源现在用哪份画像"（采集侧 SourceMapper、激活侧 MappingActivationService、
--   后续 ODS 生产链）都只读这张表——不得再各自维护内存映射或"取最新画像"兜底。
--
-- 为什么是独立表而不是 runtime_profile 的新列：
--   ① runtime_profile 的语义是"运行环境参数"（landing/库名等），激活指针是"源级词汇表事实"，
--      两者生命周期与所有者不同（设计 §4.1 分工：源身份低频长期 / 运行参数高频）；
--   ② 唯一 ACTIVE runtime_profile 行已经承担"当前源是谁"的指针（D-035），
--      再往里塞第二个指针会让同一张表承载两种"当前"，激活一处就要同时改另一处。
--
-- 字段口径：
--   profile_ref       激活时的画像文件**仓库相对路径**（与 source_registry.profile_path 同形状）。
--                     必须落库：采集侧要用它比对"登记画像 == 激活画像"，否则重启后无从判断。
--   profile_checksum  激活那一刻画像文件的字节 SHA-256（小写 64 位 hex）。激活只是**指向**文件，
--                     不复制文件内容（设计 §7.2：文件为不可变画像、DB 只登记引用，不得双 owner）。
--   contract_checksum 激活时刻 canonical 契约文件的字节 SHA-256：契约事后被改可被检出（漂移）。
--   report_id         授权这次激活的 dry-run 报告 id（预览与激活的可追溯链，报告本身仅在内存）。
--
-- 本迁移是**加性**的：只新建一张表。不改任何已有表/列/约束，不插入任何行（没有"激活"事实可造），
--   不动 source_registry 与 runtime_profile 的既有语义。
--   回退方式（仅供人工按需执行，本迁移不执行）：DROP TABLE source_mapping_active——
--   它只存"哪份画像当前生效"的引用，画像文件本身与业务数据都在别处，删表不损失业务事实。
-- =====================================================================

CREATE TABLE source_mapping_active (
    source_id         BIGINT       NOT NULL COMMENT 'source_registry.id；一源一行，本列即主键',
    profile_ref       VARCHAR(255) NOT NULL COMMENT '激活的源画像文件仓库相对路径（同 source_registry.profile_path 形状）',
    profile_version   VARCHAR(32)  NOT NULL COMMENT '画像声明版本（与画像文件内 profileVersion 一致，激活时已校验）',
    profile_checksum  CHAR(64)     NOT NULL COMMENT '激活时刻画像文件字节 SHA-256（小写 hex）',
    contract_version  VARCHAR(32)  NOT NULL COMMENT 'canonical 契约声明版本',
    contract_checksum CHAR(64)     NOT NULL COMMENT '激活时刻契约文件字节 SHA-256（小写 hex），用于检出契约漂移',
    report_id         VARCHAR(64)  NOT NULL COMMENT '授权本次激活的 dry-run 报告 id',
    activated_at      DATETIME(3)  NOT NULL COMMENT '激活时刻（事件时钟，非入库时刻）',
    activated_by      VARCHAR(64)  NOT NULL COMMENT '激活操作者用户名',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (source_id),
    KEY idx_source_mapping_active_activated_at (activated_at),
    KEY idx_source_mapping_active_profile_checksum (profile_checksum),
    CONSTRAINT fk_source_mapping_active_source FOREIGN KEY (source_id) REFERENCES source_registry (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '源当前生效的映射画像指针（一源一行，正式唯一所有者）';
