-- =====================================================================
-- V25: 追加一条质量规则定义 —— `ADS_DWS_FUNNEL_RATE_RECONCILE`（F-43 / S3-10）
--
-- 依据：指导书 §7 阶段 3 L149-151「每个指标需要固定…口径…并**逐层对账**」「每项指标固定
--   粒度/分子分母/时间窗口/金额退款口径/空值规则/版本」；设计 §12.3 L512「每条规则记录作用域、
--   阈值、版本、阶段、实际值、passed；付款 vs 订单、订单项公式、DWD/DWS 对账三者独立，
--   不能用一个 AMOUNT_RECONCILE 覆盖」。
--   （落地前该缺口登记于 docs/PROJECT_STATUS.md:203 —— S3-04 R-1：ADS 漏斗新列
--     `overall_cart_rate`/`overall_buy_rate` 在**在产质量门里没有任何跨层对账守卫**，
--     等价断言只存在于 S3-04 的 spec 里。原因：既有阻断规则 `ADS_DWS_FUNNEL_RECONCILE`
--     只对账 4 个 stage 的 `user_count` 汇总，率列不在其作用域内。）
--
-- 为什么**新增迁移**而不是改 V19：
--   V19 是**已发布**的迁移（F-88），其内容按硬约束不得再改（改已发布迁移 = 决策门③），
--   且 V19 里 `ADS_DWS_FUNNEL_RECONCILE` 那一行是「计数汇总对账」的登记，语义不同、不可合并：
--   把率列并进同一码会让「率错但计数对」与「计数错」无法区分（设计 §12.3 的独立性要求）。
--   新规则码必须**先登记再产出**：§7.3.1 line 524 规定未登记规则码一律「停止发布并报未登记规则」，
--   若只加 Java 目录不落库，新码第一次产出结果就会被读侧判为未登记而整链翻红。
--   故按「一次迁移一件事」追加一条**只插一行**的加性迁移。
--
-- 本迁移做什么（可逐条核对，全文无第二条语句）：
--   1) `INSERT IGNORE` 一行 `(rule_code, version, source_scope, stage, severity, severity_mode,
--      threshold_json, enabled, effective_from, effective_to, checksum)`。
--   2) **不建表、不改列、不删行、不改任何既有行的档位或阈值**；对已手工登记过同键行的库幂等。
--
-- 档位为何是 BLOCKING（而不是 WARN/ERROR）：本规则只判**跨层是否一致**——ADS 漏斗率列由
--   `AdsSql.funnel` 从 DWS 同 dt 全站行**透传**（S3-04 口径④：ADS 只透传不重算），
--   「不一致」等价于口径被破坏（改写/串列/取错 dt 或维度），而「行数一致」「关键列非空」
--   都可能同时正常；不一致时发布出去的漏斗结论是错的。注意本规则**不**判比率数值是否异常：
--   设计 §12.3 第 10 项明确「支付/浏览用户比及 cohort 解释：宽松口径异常不一概作为阻断规则」，
--   故不引入任何比率阈值，`threshold_json` 留 NULL。
--
-- checksum 的算法（与 `QualityRuleDefinition#checksum()` 逐字节一致，不是另一套哈希）：
--   SHA-256( ruleCode \x1f version \x1f sourceScope \x1f stage \x1f severity \x1f
--            severityMode \x1f thresholdJson(缺省为空串) \x1f enabled(1/0) \x1f
--            effectiveFrom(缺省为空串) \x1f effectiveTo(缺省为空串) )
--   十六进制小写 64 字符；rationale 不参与（改错别字不应使指纹变化）。
--   本行输入 = `ADS_DWS_FUNNEL_RATE_RECONCILE` \x1f 1 \x1f `*` \x1f `ADS` \x1f `BLOCKING` \x1f
--              `FIXED` \x1f `` \x1f 1 \x1f `` \x1f ``
--   ⇒ 该行 checksum 与 `QualityRuleCatalog.DEFAULT` 里同名定义的 `checksum()` 必须相等，
--     由 `QualityRuleVersionMigrationScriptTest` 逐行对账（零漂移）。
--   （本值由独立脚本按上述算法重算过，并先用 V19 的 `ADS_DWS_FUNNEL_RECONCILE` 与
--     V23 的 `MP_EXPORT_CHECKSUM` 两行已知值反向验证算法复现一致，再算本行。）
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
    ('ADS_DWS_FUNNEL_RATE_RECONCILE', 1, '*', 'ADS', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL,
     '3ded2e10b8b4217f00aefe3b1955923cddb35cdfe0a7e07be7edbc3672121a8f');
