-- =====================================================================
-- M2 P1-02 源登记表与存量 profile 回填（商城无关化实施书 §3.1）
--
-- source_registry：一台被分析的「源」（商城）的长期身份登记——它是谁、
--   词汇表（源画像文件）在哪、按什么接入、处于什么状态。
--   与 runtime_profile 的分工：源身份变更频率低、生命周期长；
--   运行参数（landing、库名前缀、激活态）变更频率高，仍归 runtime_profile。
--   （择一不折叠的理由：设计 §4.1 「备选（未采纳）」一段，指导书 §「已裁决」第 3 条）
--
-- runtime_profile.source_id：该运行环境绑定到哪个源。兼容期可空（存量行先于
--   源登记存在），启动流水线时由服务层要求非空；本迁移只回填空值行。
--
-- 本迁移是**加性**的：只新建表、只增列、只插种子行、只在空值上回填。
--   不删表、不删列、不改写已有列定义、不激活库名前缀（库名前缀属 P1-04 边界）。
--   回退方式（仅供人工按需执行，本迁移不执行）：先删外键与 source_id 列、
--   再删 source_registry 表——两者都是本次新增的登记结构，不含业务事实数据。
-- =====================================================================

-- 源登记（实施书 §3.1 字段全集；凭据不进本表，运行凭据仍由 runtime_profile.credential_ref 引用）
CREATE TABLE source_registry (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    source_code     VARCHAR(64)  NOT NULL COMMENT '源业务键，写入 ODS 的 source_system；[a-z][a-z0-9-]{1,63}，创建后不可改名',
    display_name    VARCHAR(128) NOT NULL COMMENT '看板/页面展示名',
    ingest_mode     VARCHAR(16)  NOT NULL COMMENT '接入方式：本期唯一实现 FILE；JDBC/HTTP 为能力状态（预留）',
    profile_path    VARCHAR(255) NOT NULL COMMENT '源画像文件仓库相对路径（设计 §4.2），禁止 .. 与绝对路径',
    timezone        VARCHAR(64)  NOT NULL COMMENT '源所在时区（IANA）',
    currency        CHAR(3)      NOT NULL COMMENT '源记账币种（ISO 4217）',
    status          VARCHAR(16)  NOT NULL COMMENT 'DRAFT/ACTIVE/PAUSED/DISABLED',
    profile_version VARCHAR(32)  NOT NULL COMMENT '与源画像文件内的 profileVersion 一致',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_registry_code (source_code),
    KEY idx_source_registry_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '被分析源（商城）登记：长期身份与词汇表位置';

-- 种子源：参考商城（源 A）。source_code 必须等于冻结契约 canonical-event.v1 的 source_system 取值。
-- profile_version 取设计 §4.2 示例中的画像版本形状 1.0；画像文件本身由 P3-01（SourceProfile 加载）落盘，
-- 因此这里登记的是**声明的路径与版本**，尚未经加载器校验（见验收 README「未取证」一节）。
-- WHERE NOT EXISTS 让脚本可重复执行（Flyway 本身只跑一次，此处是第二道保险）。
INSERT INTO source_registry (source_code, display_name, ingest_mode, profile_path, timezone, currency, status, profile_version)
SELECT 'mock-mall', '参考商城（源 A）', 'FILE', 'analytics-server/source-profiles/mock-mall.v1.json',
       'Asia/Shanghai', 'CNY', 'ACTIVE', '1.0'
WHERE NOT EXISTS (SELECT 1 FROM source_registry WHERE source_code = 'mock-mall');

-- 运行环境绑定源：可空以兼容存量行；外键防止登记被删后留下悬空引用
ALTER TABLE runtime_profile
    ADD COLUMN source_id BIGINT NULL COMMENT '所属数据源（source_registry.id）；兼容期可空，启动流水线时必须非空',
    ADD KEY idx_runtime_profile_source (source_id),
    ADD CONSTRAINT fk_runtime_profile_source FOREIGN KEY (source_id) REFERENCES source_registry (id);

-- 存量回填：只覆盖 source_id 为空的 profile；显式保持 updated_at 原值，
-- 使这次回填不改动版本、状态与激活/更新时间（那些是 operator 的语义，不是迁移的）。
UPDATE runtime_profile p
JOIN source_registry s ON s.source_code = 'mock-mall'
SET p.source_id = s.id,
    p.updated_at = p.updated_at
WHERE p.source_id IS NULL;
