-- =====================================================================
-- V18: P2-07 数仓库名前缀下沉到源（决策记录 D-070 / D-071 / D-080）
--
-- 改什么：`source_registry` 新增 `warehouse_prefix` —— 每个源在数仓里的命名空间前缀。
--   取值来源由 `runtime_profile.hive_database_prefix`（运行参数）迁到源级（源身份）。
--   契约：`contract-specs/specs/warehouse-namespace.v2.json`（`FROZEN-2026-09-12`），
--   `rule.prefixPattern = ^[a-z][a-z0-9_]{0,23}$`（≤24 字符）⇒ 列宽 VARCHAR(24)。
--   规则本体（形状/下划线/保留字/层后缀四类拒绝）仍在唯一所有者 `WarehouseNamespace`
--   （Java）与 `spark-jobs` 的同名 Scala 镜像里，**不在本迁移里写第二份**。
--
-- 为什么是"加列 + 回填 + 收紧"三步（沿用 V16/V17 的既有形状）：
--   1) 先加**可空**列：存量行此刻还没有源级前缀，语义上就是"未填"；
--   2) 回填只写新列，值 = `dw` —— 与今日解析结果**逐字相同**（今日 `runtime_profile`
--      该列为 NULL ⇒ `WarehouseNamespace.ofNullable(null)` ⇒ 缺省 `dw`）⇒ 零迁移等价，
--      五个层库名与既有数据一个字节都不动（D-072 的等价性口径）；
--      显式 `updated_at = updated_at`：这次回填不冒充 operator 的版本/时间语义（同 V16）；
--   3) 收紧为 NOT NULL，**收紧语句本身就是断言**：若仍有行未回填则迁移失败中止。
--
-- 为什么**不设** `DEFAULT 'dw'`（D-071 原文）：默认值会让"忘记填前缀"静默落进 `dw_*`，
--   即让第二个源悄悄住进第一个源的库 —— 正是本任务要防的场景。故缺省只能在服务层
--   被显式拒绝（`SourceRegistryServiceImpl` 的 create 校验），不能在 DDL 层被静默补上。
--
-- 只动 `source_registry`：**不删** `runtime_profile.hive_database_prefix`（D-073 本轮只断读、
--   不删列；删列是破坏性 DDL，另立任务，预期号位 V19）。本迁移不改任何既有列定义、
--   不删任何行、不删任何索引。
--
-- 前向效应（必须记账，D-080）：本脚本会在**下次平台（8091）启动时**由 Flyway 对真实
--   `analytics_meta` 执行；P2-07 本轮不重启进程 ⇒ **本轮未在真库执行**，只在只读转储
--   副本上取证（见 docs/acceptance/p2-07-source-prefix-20260912/）。
-- 回退方式（仅供人工按需执行，本迁移不执行）：`ALTER TABLE source_registry DROP COLUMN
--   warehouse_prefix;` —— 只删本轮新增列，不含业务事实数据。
-- =====================================================================

-- 1) 加列（可空：存量行此刻还没有源级前缀）
ALTER TABLE source_registry
    ADD COLUMN warehouse_prefix VARCHAR(24) NULL
        COMMENT '数仓命名空间前缀（P2-07 D-070）：^[a-z][a-z0-9_]{0,23}$，契约 warehouse-namespace.v2.json；不设 DEFAULT —— 缺省由服务层显式拒绝而非静默补 dw'
        AFTER profile_version;

-- 2) 回填：只写新列；值取今日解析结果（零迁移等价）；显式保持 updated_at 原值
UPDATE source_registry
SET warehouse_prefix = 'dw',
    updated_at = updated_at
WHERE warehouse_prefix IS NULL;

-- 3) 收紧为非空（若有行仍为空则本语句报错 → 整个迁移失败，人工介入；不静默补默认源）
ALTER TABLE source_registry
    MODIFY COLUMN warehouse_prefix VARCHAR(24) NOT NULL
        COMMENT '数仓命名空间前缀（P2-07 D-070）：^[a-z][a-z0-9_]{0,23}$，契约 warehouse-namespace.v2.json；不设 DEFAULT —— 缺省由服务层显式拒绝而非静默补 dw';
