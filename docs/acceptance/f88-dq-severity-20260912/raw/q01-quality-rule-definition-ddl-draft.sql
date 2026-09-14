-- =====================================================================
-- DRAFT ONLY —— 质量规则版本表 DDL 草案
-- 文件：docs/acceptance/f88-dq-severity-20260912/raw/q01-quality-rule-definition-ddl-draft.sql
--
-- 【授权状态】
--   本文件是**草案 / DRAFT**，不是迁移文件。
--   本文件**未被授权**（NOT AUTHORIZED）。
--   本文件**未被执行**（NOT EXECUTED）——没有对任何数据库执行过任何 DDL/DML。
--   写入位置刻意放在 docs/acceptance/**/raw/（证据目录），**不是** db/migration、db/meta、
--   db/metric、warehouse/ddl 等迁移目录；泳道不得自行占用迁移号。
--   迁移文件名与版本号 MUST be allocated by the 总控 owner（总控）；本草案不自编号。
--
-- 【需求出处】docs/项目完整实施指导书 V2.5.md
--   line 501 §7.3     ：「建立 `quality_rule_definition` 和版本/生效区间，严重度行为如下：
--                        BLOCKING/ERROR 阻断发布，WARN/INFO 持久化并展示但不阻断。」
--   line 516 §7.3      ：「每条 BLOCKING 规则必须有一条『构造失败→流水线失败→不产生新 ACTIVE→
--                        旧 ACTIVE 可读』的负向验收。」
--   line 520 §7.3.1    ：「quality_rule_definition 需含 rule_code、version、source_scope、stage、
--                        severity、threshold_json、enabled、effective_from/to、checksum；
--                        (scope,rule_code,version) 唯一。一次 run 冻结完整规则版本与指纹，
--                        结果记录该版本、实际值、阈值、passed、原始及有效严重度、兼容策略版本。」
--   line 522 §7.3.1    ：执行顺序「选择作用域/版本 → 计算指标与阈值判定 → 决定严重度 → 汇总门禁」；
--                        「必填/主键/金额对账失败不能被全局规则码映射降 WARN」；
--                        「原始重复事件仅在确定性去重已证且重复率不超批准阈值时为观察项；超过阈值阻断」；
--                        「EVENT_ID_UNIQUE 历史阈值 0.0005 未经新裁决不修改」。
--   line 524 §7.3.1    ：「历史兼容需 (source_id, rule_code, rule_version 或明确批次范围,
--                        compatPolicyVersion)……保留原始结果字段，不回填历史结论」；
--                        「未知规则码不可盲信传来的 WARN，应停止发布并报未登记规则」。
--   line 526 §7.3.1    ：三种金额校验（付款 vs 订单总额 / 订单项公式 / DWD↔DWS）互相独立。
--   line 528 §7.3.1    ：「每条 BLOCKING/ERROR 验一次：构造失败 → 本次流水线失败 → 不生成新 ACTIVE
--                        → 原 ACTIVE 数值/指纹可读且不变。失败必须可定位到 stage/rule。」
--   line 536 §7.4      ：快照唯一性按**发布作用域**（至少 source_id + runtime_profile_id）建立约束。
--   line 663-667 §9.4  ：IT 防误写门禁（测试库白名单先校验后 DDL/DML、只授必要权限、cleanup 用
--                        本次 testRunId、跑前后记录计数与 checksum）。**本草案不涉及任何执行**。
--
-- 【本草案的既定事实（只读观察，非本次执行结果）】
--   1) 全仓不存在 quality_rule_definition 表或同名标识符。证据：
--      docs/acceptance/guideline-v24-coverage-20260912/raw/07-platform-entity-greps.txt:12
--      「git grep -ln "quality_rule_definition" HEAD -- "*.sql" "*.java" → 0 命中」；
--      docs/v2-completeness-audit.md:340,518 同结论。⇒ 这是**新增表**，不是改表。
--   2) 代码侧已有该表的**投影**，本草案字段与它逐列对齐（将来把目录换成 mapper 查询即可）：
--      analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/
--        QualityRuleDefinition.java（11 字段 record + checksum() = SHA-256 hex 64 字符）
--        QualityRuleCatalog.java（4 条 Landing 规则、规则集整体指纹、兼容策略版本 compat-v1）
--        RuleSeverity.java（有效严重度 / 原始严重度分离，未登记码保守阻断）
--   3) 现库 data_quality_result 的真实列型（只读转储，非本次执行）：
--      docs/acceptance/p1-05-8091-swap-20260911/pre-v17-schema.sql:77-94
--      —— threshold 是 varchar(64) 文本（真值是 "<=0.0005" 这类字符串）、passed int、
--         severity varchar(16) DEFAULT NULL、COLLATE utf8mb4_0900_ai_ci。
--      ⇒ 规范要求的「阈值、实际值、passed、原始及有效严重度」在现表里**只能部分表达**，
--         缺列清单见文件末尾 §3 注释块。
--
-- 【MySQL 版本假设 —— UNVERIFIED】
--   假设目标为 MySQL 8.0（依据：MySQL 8.0 专属排序规则 utf8mb4_0900_ai_ci 已存在于
--   db/meta/V16__source_registry.sql:35 与真实转储 pre-v17-schema.sql:94；
--   analytics-server/platform-app/.../SourceRegistryMigrationMySqlIT.java:21 注释亦写「真 MySQL 8.0」）。
--   **本次未连接任何数据库复核版本号**，故该假设标记为 UNVERIFIED。
--   若实际为 5.7：JSON 列类型与 utf8mb4_0900_* 排序规则均不可用，本草案需重写。
--
-- 【库名（schema）归属 —— 留给总控裁决】
--   平台元数据迁移由 MetaFlywayInitializer 执行 classpath:db/meta，脚本内**不写库名**，
--   落到连接的默认库（warehouse/migrations/init-three-dbs.sql:6 建 analytics_meta）。
--   故本草案沿用房内风格写**非限定表名**；但同名表跨库确实存在
--   （docs/acceptance/b13-db-forensics-20260912/raw/05-rowlevel-two-schemas.sql:15-19
--    记录 analytics_meta.metric_snapshot 与 analytics_metric.metric_snapshot 同名不同库）。
--   落地时应写成 analytics_meta.quality_rule_definition（见 notes 的决策项 D2）。
-- =====================================================================


-- =====================================================================
-- §1 quality_rule_definition —— 规则版本表（新增，非改表）
-- =====================================================================
-- 列顺序刻意与指导书 line 520 的字段枚举顺序一致，便于逐字段对照审阅。
--
-- threshold_json 为何用 JSON 而不是 VARCHAR：
--   ① 阈值是**结构化**的判定契约（比较符 + 数值 + 前提标志），例如
--      {"dupRateMax":0.0005,"dedupDeterministic":true}，不同规则的键集不同；
--      用 VARCHAR 只能存 "<=0.0005" 这类**丢失结构**的文本，判定方需自行再解析（易生第二口径）。
--   ② MySQL 8 的 JSON 列会校验合法性并在写入时即拒绝非法 JSON —— 与
--      db/meta/V15__stage_evidence_mediumtext.sql:5 记录的「截断产生非法 JSON」事故相反，
--      这里要的是**结构可校验**而不是「能塞进去就行」。
--   ③ 代价：JSON 列不能直接建普通索引（除非生成列）——本表按 (source_scope,rule_code,version)
--      与生效区间索引查询，不按 JSON 内部键查询，故该代价可接受。
--
-- checksum 为何用 CHAR(64)：代码侧指纹算法已固定为 SHA-256 十六进制小写（64 字符）
--   —— QualityRuleDefinition.java:112-133「SHA-256 十六进制小写，64 字符」，
--      参与哈希的字段 = 全部语义字段（rule_code/version/source_scope/stage/severity/
--      severity_mode/threshold_json/enabled/effective_from/effective_to），**不含** rationale。
--   故 char(64) 恰好容纳且可 char 定长比较。（severity_mode 见列注释：DDL 里以
--   severity_mode 承载「固定 vs 阈值条件」，否则 EVENT_ID_UNIQUE 的「原始 WARN、
--   超阈值转阻断」在表里无法表达。）
--
-- 未加 CREATE TABLE IF NOT EXISTS：房内 db/meta 建表脚本（V2__platform_pipeline_quality.sql:36、
--   V16__source_registry.sql:20）一律裸 CREATE TABLE，仅 V5/V14 例外；
--   Flyway 本身只跑一次，重复执行应由迁移而非 IF NOT EXISTS 保证。

CREATE TABLE quality_rule_definition (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    rule_code       VARCHAR(64)  NOT NULL COMMENT '规则码（大写、去空白；如 EVENT_ID_UNIQUE）',
    version         INT          NOT NULL COMMENT '规则版本号，同一码逐版递增，>=1（指导书 line 520 version）',
    source_scope    VARCHAR(64)  NOT NULL DEFAULT '*' COMMENT '适用作用域：* = 全源；否则为具体 source_id（line 520 source_scope）',
    stage           VARCHAR(32)  NOT NULL COMMENT '所属阶段：LANDING/DWD/DWS/ADS/PUBLISH/METRIC_PUBLISH（line 528 要求失败可定位到 stage）',
    severity        VARCHAR(16)  NOT NULL COMMENT '原始严重度（定义声明的档）：BLOCKING/ERROR/WARN/INFO（line 501：BLOCKING/ERROR 阻断，WARN/INFO 不阻断）',
    severity_mode   VARCHAR(32)  NOT NULL DEFAULT 'FIXED' COMMENT '严重度判定方式：FIXED = 恒等于 severity；THRESHOLD_OBSERVATION = 未超阈值时 severity（须为 WARN）、超阈值转 BLOCKING（line 522：超过阈值阻断）',
    threshold_json  JSON         NULL COMMENT '阈值（结构化 JSON；无阈值规则为 NULL）。THRESHOLD_OBSERVATION 必须非空。例：{"dupRateMax":0.0005,"dedupDeterministic":true}',
    enabled         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用（1=启用 0=停用）；解析生效版本时只取 enabled=1（line 520 enabled）',
    effective_from  DATETIME(3)  NULL COMMENT '生效起始（NULL = 不设下界；line 520 effective_from）',
    effective_to    DATETIME(3)  NULL COMMENT '生效结束（NULL = 不设上界；line 520 effective_to）',
    checksum        CHAR(64)     NOT NULL COMMENT '定义指纹：SHA-256 十六进制小写 64 字符，覆盖全部语义字段（不含 rationale）；一次 run 的整体指纹 = 各条 checksum 再哈希（line 520 冻结完整规则版本与指纹）',
    rationale       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '中文依据（审计/页面展示；不参与 checksum，改错别字不应改指纹）',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 规范硬要求（line 520）：(scope, rule_code, version) 唯一
    UNIQUE KEY uk_qrd_scope_rule_version (source_scope, rule_code, version),
    -- 支撑「解析 T 时刻该作用域下启用的版本」这条查询（line 522 第 1 步）：
    --   SELECT ... WHERE source_scope IN (?, '*') AND stage = ? AND enabled = 1
    --     AND (effective_from IS NULL OR effective_from <= ?)
    --     AND (effective_to   IS NULL OR effective_to   >  ?)
    --   ORDER BY rule_code, version DESC;
    -- 等值前缀 (source_scope, stage, enabled) 走索引，区间列 effective_from 放在最后
    -- （MySQL 对索引中第一个范围列之后的列不再用于定位）。
    KEY idx_qrd_resolve (source_scope, stage, enabled, effective_from),
    -- 按规则码跨作用域/版本追溯（页面与兼容解释按 rule_code 聚合）
    KEY idx_qrd_rule_code (rule_code, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '质量规则定义（版本化 + 生效区间 + 指纹）；line 520 §7.3.1';


-- =====================================================================
-- §2 种子数据 —— 本项目真实的 4 条 Landing 规则
--     ★★★ 整块注释掉，NOT AUTHORIZED / NOT EXECUTED ★★★
--     ★★★ 迁移号与是否随首次迁移插种子，由总控裁决 ★★★
-- =====================================================================
-- 严重度来源（只读观察，非本次执行）：
--   analytics-server/platform-common/.../RuleSeverity.java:94,97,100（三条 BLOCKING）
--   analytics-server/platform-common/.../QualityRuleCatalog.java:60-77（4 条定义 + 阈值 + 模式）
--   analytics-server/warehouse-pipeline/.../QualityChecker.java:71,88,97,112（阈值文本字面量）
-- 阈值来源：设计文稿 V2.2 §5.4.2「默认阈值」（docs/design/...V2.2.md:583-586）
--   空值率 <= 0.1%（0.001）、主键重复率 <= 0.05%（0.0005）、非法枚举比例 = 0。
--   ★ EVENT_ID_UNIQUE 的 0.0005 是**历史批准阈值**，line 522 明令「未经新裁决不修改」，
--     本草案原样搬运，**不做任何放宽**。
--
-- 逐条说明 EVENT_ID_UNIQUE 的「条件阻断」：
--   severity = WARN、severity_mode = THRESHOLD_OBSERVATION、threshold_json 携带 0.0005。
--   语义：确定性去重已证（dedupDeterministic=true）**且**重复率 <= 0.0005 时为观察项；
--        重复率 > 0.0005 时**有效严重度升为 BLOCKING**（line 522「超过阈值阻断」）。
--   ⇒ 写成固定 WARN 是错的（会把高重复率直接放行）；写成固定 BLOCKING 也是错的
--     （与 line 522「仅在……时为观察项」冲突）。
--   ⇒ 故「原始严重度 = WARN」与「有效严重度 = 运行时判定」必须分列记录（见 §3）。
--
-- INSERT INTO quality_rule_definition
--     (rule_code, version, source_scope, stage, severity, severity_mode, threshold_json,
--      enabled, effective_from, effective_to, checksum, rationale)
-- VALUES
--     ('AMOUNT_RECONCILE', 1, '*', 'LANDING', 'BLOCKING', 'FIXED',
--      CAST('{"maxAbsDiff":0.01}' AS JSON), 1, NULL, NULL, -- <<< checksum 由代码侧计算后回填，禁止手填猜测值 >>>
--      '金额对账不一致（支付金额 vs 订单总额）⇒ 必须阻断；line 522 明确此类失败不得被降 WARN；line 526 三种金额校验互相独立，本条不能替代订单项公式与 DWD↔DWS'),
--
--     ('REQUIRED_FIELD_NULL_RATE', 1, '*', 'LANDING', 'BLOCKING', 'FIXED',
--      CAST('{"nullRateMax":0.001}' AS JSON), 1, NULL, NULL, -- <<< checksum 待回填 >>>
--      '必需字段缺失；下游 DwdSql.behaviorClean 静默丢弃该行 ⇒ 必须阻断；阈值 0.001 出自设计文稿 §5.4.2：「必要字段空值率不超过 0.1%」'),
--
--     ('ENUM_WHITELIST', 1, '*', 'LANDING', 'BLOCKING', 'FIXED',
--      CAST('{"illegalRatio":0}' AS JSON), 1, NULL, NULL, -- <<< checksum 待回填 >>>
--      '非法枚举；下游静默丢弃该行 ⇒ 必须阻断；阈值 0 出自设计文稿 §5.4.2：「非法行为类型比例为 0」'),
--
--     ('EVENT_ID_UNIQUE', 1, '*', 'LANDING', 'WARN', 'THRESHOLD_OBSERVATION',
--      CAST('{"dupRateMax":0.0005,"dedupDeterministic":true}' AS JSON), 1, NULL, NULL, -- <<< checksum 待回填 >>>
--      '原始事件重复：确定性去重已证且重复率 <= 0.0005 时为观察项（WARN，不阻断）；超过 0.0005 即阻断（有效严重度 → BLOCKING）。阈值 0.0005 出自设计文稿 §5.4.2「主键重复率不超过 0.05%」，line 522：未经新裁决不修改，测试不得为通过把高重复率直接放行');
--
-- -- 幂等写法（房内风格，见 db/meta/V16__source_registry.sql:41-44 的 WHERE NOT EXISTS）：
-- --   INSERT INTO quality_rule_definition (...)
-- --   SELECT 'EVENT_ID_UNIQUE', 1, '*', 'LANDING', 'WARN', 'THRESHOLD_OBSERVATION', ...
-- --   WHERE NOT EXISTS (SELECT 1 FROM quality_rule_definition
-- --                     WHERE source_scope = '*' AND rule_code = 'EVENT_ID_UNIQUE' AND version = 1);
-- -- 说明：line 524 要求「不回填历史结论」——种子只新增定义行，不改任何已有运行结果。


-- =====================================================================
-- §3 结果侧缺列 —— 「结果记录该版本、实际值、阈值、passed、原始及有效严重度、兼容策略版本」
--     （line 520 后半句）★ 整块注释掉，NOT AUTHORIZED / NOT EXECUTED ★
-- =====================================================================
-- 现状（只读转储 pre-v17-schema.sql:77-94）data_quality_result 已有：
--   run_id / rule_code / layer / severity / target_table / snapshot_id /
--   check_count / error_count / error_rate / threshold / passed / detail / created_at
-- 逐项比对 line 520 的要求：
--   该版本            → ✗ 缺 rule_version
--   实际值            → △ 只有 error_rate（decimal(10,6)）+ check_count/error_count；
--                        纯检查型规则（如 ADS_STAGING_PRESENT）没有"比率"这个实际值
--   阈值              → △ 有 threshold，但它是 varchar(64) **文本**（真值形如 "<=0.0005"），
--                        与 quality_rule_definition.threshold_json 的结构化阈值是两份口径
--   passed            → ✓ 有（int，1=通过 0=失败）
--   原始严重度        → △ severity 列语义含糊：历史上既被写成 BLOCKING 又被写成 ERROR/INFO，
--                        同一 rule_code 跨 run 不一致（证据：f88 raw/db-a-rule-severity-inventory.txt:18-28）
--   有效严重度        → ✗ 缺 effective_severity
--   兼容策略版本      → ✗ 缺 compat_policy_version
--
-- ── 方案 A（加列在既有表上）：改动最小，结果与规则版本同行使一次 run 自洽 ──
-- ALTER TABLE data_quality_result
--     ADD COLUMN rule_version          INT          NULL COMMENT 'line 520：本次判定所用规则版本（未登记/历史行 NULL，不回填历史结论）' AFTER rule_code,
--     ADD COLUMN declared_severity     VARCHAR(16)  NULL COMMENT 'line 520：原始严重度（作业回传/定义声明值）' AFTER severity,
--     ADD COLUMN effective_severity    VARCHAR(16)  NULL COMMENT 'line 520：有效严重度（按规则版本判定；未登记码为保守阻断档）' AFTER declared_severity,
--     ADD COLUMN compat_policy_version VARCHAR(32)  NULL COMMENT 'line 520/524：兼容策略版本（当前代码常量 compat-v1）' AFTER effective_severity,
--     ADD COLUMN actual_value          DECIMAL(18,6) NULL COMMENT 'line 520：实际值（比率类=error_rate；计数类=NULL，由 check/error 表达）' AFTER error_rate,
--     ADD COLUMN threshold_json        JSON         NULL COMMENT 'line 520：结构化阈值快照（判定时冻结值，与文本 threshold 并存以备复算）' AFTER threshold,
--     ADD COLUMN rule_set_fingerprint  CHAR(64)     NULL COMMENT 'line 520：本次 run 冻结的整套规则集指纹' AFTER rule_version;
-- -- 注意 1：severity 既有列的历史语义是"原始严重度"（varchar(16) NULL）——若采用方案 A，
-- --         须由总控裁决是复用 severity 还是保留它并新增 declared_severity（不要静默改历史行含义）。
-- -- 注意 2：旧列 threshold(varchar 64) 不得删除（line 524「保留原始结果字段，不回填历史结论」）。
-- -- 注意 3：一次 run 的整套指纹更适合放 run 级载体（如 pipeline_stage_run.evidence 或新表），
-- --         每行都存一份属于冗余；此处列出仅为选项完整。
--
-- ── 方案 B（伴生表，房内已有同类风格：pipeline_stage_run 与 pipeline_run 分列）──
-- CREATE TABLE data_quality_result_rule_version (
--     id                   BIGINT       NOT NULL AUTO_INCREMENT,
--     run_id               BIGINT       NOT NULL COMMENT '对应 data_quality_result.run_id / pipeline_run.id',
--     rule_code            VARCHAR(64)  NOT NULL,
--     rule_version         INT          NOT NULL COMMENT 'line 520：本次判定所用版本',
--     source_scope         VARCHAR(64)  NOT NULL COMMENT '本次选择的作用域（line 522 第 1 步）',
--     stage                VARCHAR(32)  NOT NULL COMMENT 'line 528：失败可定位到 stage',
--     declared_severity    VARCHAR(16)  NOT NULL COMMENT 'line 520：原始严重度',
--     effective_severity   VARCHAR(16)  NOT NULL COMMENT 'line 520：有效严重度（条件规则超阈值时为 BLOCKING）',
--     compat_policy_version VARCHAR(32) NOT NULL COMMENT 'line 520/524：兼容策略版本',
--     rule_checksum        CHAR(64)     NOT NULL COMMENT '该规则定义的 checksum（来自 quality_rule_definition）',
--     rule_set_fingerprint CHAR(64)     NOT NULL COMMENT '本次 run 冻结的整套规则集指纹（line 520）',
--     frozen_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
--     PRIMARY KEY (id),
--     UNIQUE KEY uk_dqrrv_run_rule (run_id, rule_code, source_scope),
--     KEY idx_dqrrv_fingerprint (rule_set_fingerprint)
-- ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '一次 run 冻结的规则版本/严重度判定（line 520）';
--
-- 一句话取舍：方案 A 读取只需单表 JOIN（结果与版本一体）、但把"每次运行的判定"与
--   "结果明细"耦合进同一张 15 列宽表并需处理 severity 旧列语义；方案 B 保持
--   data_quality_result 的"原始结果字段"不动（更贴合 line 524「保留原始结果字段」），
--   代价是查询与幂等清理多一张表。★ 选哪个由总控裁决，本草案不预设立场。★


-- =====================================================================
-- §4 配套约束提示（不写语句，只登记与 line 536 的关系）
-- =====================================================================
-- line 536 的唯一性是**快照发布作用域**（至少 source_id + runtime_profile_id），
-- 属 metric_snapshot / 发布路径的约束，**不在本草案范围**（本草案只建规则定义表）。
-- 本草案里唯一的唯一键是 line 520 明文要求的 (source_scope, rule_code, version)。
-- 若要按 businessDate 再切分快照唯一性，line 536 要求「先与现有快照契约统一」——
-- 那是另一个 owner 的边界，此处不启动。


-- =====================================================================
-- §5 未验证 / 未测（不得当作已验证）
-- =====================================================================
-- 1. MySQL 版本号：UNVERIFIED（未连接）。
-- 2. analytics_meta 是否接受新表（账号 meta_app 是否有 CREATE 权限）：UNVERIFIED。
--    init-three-dbs.sql:15 授的是 ALL PRIVILEGES ON analytics_meta.*，但未实测。
-- 3. 是否存在"同名表跨库冲突"：quality_rule_definition 全仓 0 命中（见文件头事实 1），
--    但**未**核查是否存在"11 个库"这一数量说法；同名跨库的**实例**另有 metric_snapshot。
-- 4. 本 DDL 语法未在任何 MySQL 上解析过（未执行 CREATE/EXPLAIN）。
-- 5. 索引 idx_qrd_resolve 的实际选择性未测。
-- 6. 种子行的 checksum 值未计算（须由代码侧 QualityRuleDefinition.checksum() 生成后回填）。
-- =====================================================================
-- END OF DRAFT
-- =====================================================================
