-- =====================================================================
-- V23: 追加一条质量规则定义 —— `MP_EXPORT_CHECKSUM`（F-39 / S3-06）
--
-- 依据：设计 §12.5 L528 的发布顺序「… mxp 导出 + manifest/checksum → MySQL BUILDING/staging →
--   表形/行数/定义版本/内容验证 …」与 L529 的「内容验证」；指导书 §7 阶段 3 L151
--   「导出 ADS 制品核 schema/行数/checksum」。
--   （落地前该缺口已登记于 docs/audit/v2-completeness-audit.md:213「无 checksum（仅路径 + 行数）」。
--     仅核对行数时，行数相同但内容被截断或错位搬运的导出制品在发布侧没有任何判据。）
--
-- 为什么**新增迁移**而不是改 V19：
--   V19 是**已发布**的迁移（F-88），其内容按硬约束不得再改（改已发布迁移 = 决策门③）。
--   而 `MP_EXPORT_CHECKSUM` 作为新规则码必须**先登记再产出**：§7.3.1 line 524 规定未登记规则码
--   一律「停止发布并报未登记规则」，若只加 Java 目录不落库，新码第一次产出结果就会被读侧判为
--   未登记而整链翻红。故按「一次迁移一件事」追加一条**只插一行**的加性迁移。
--
-- 本迁移做什么（可逐条核对，全文无第二条语句）：
--   1) `INSERT IGNORE` 一行 `(rule_code, version, source_scope, stage, severity, severity_mode,
--      threshold_json, enabled, effective_from, effective_to, checksum)`。
--   2) **不建表、不改列、不删行、不改任何既有行的档位或阈值**；对已手工登记过同键行的库幂等。
--
-- checksum 的算法（与 `QualityRuleDefinition#checksum()` 逐字节一致，不是另一套哈希）：
--   SHA-256( ruleCode \x1f version \x1f sourceScope \x1f stage \x1f severity \x1f
--            severityMode \x1f thresholdJson(缺省为空串) \x1f enabled(1/0) \x1f
--            effectiveFrom(缺省为空串) \x1f effectiveTo(缺省为空串) )
--   十六进制小写 64 字符；rationale 不参与（改错别字不应使指纹变化）。
--   本行输入 = `MP_EXPORT_CHECKSUM` \x1f 1 \x1f `*` \x1f `METRIC_PUBLISH` \x1f `BLOCKING` \x1f
--              `FIXED` \x1f `` \x1f 1 \x1f `` \x1f ``
--   ⇒ 该行 checksum 与 `QualityRuleCatalog.DEFAULT` 里同名定义的 `checksum()` 必须相等，
--     由 `QualityRuleVersionMigrationScriptTest` 逐行对账（零漂移）。
--
-- rationale 继续留 NULL：依据文本的唯一所有者在 Java 目录（`QualityRuleCatalog` 的 rationale
--   字段）与 `RuleSeverity.rationale(String)`，本表只存契约（档位/模式/阈值/指纹）。
--
-- 【本迁移在真库上的执行状态：未执行】
--   按本泳道硬约束（DB 冻结：不起停服务、不对 3306 执行任何 DDL/DML），本脚本只入版本库，
--   未在任何正式库执行；「已在真库生效」不得由本文件推断。
-- =====================================================================

INSERT IGNORE INTO quality_rule_definition
    (rule_code, version, source_scope, stage, severity, severity_mode,
     threshold_json, enabled, effective_from, effective_to, checksum)
VALUES
    ('MP_EXPORT_CHECKSUM', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL,
     'eab2b86904bf3b892956900087e5eae4816c4a63d9b1a0f3e5115ae3ebf775c3');
