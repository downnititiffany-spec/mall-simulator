-- =====================================================================
-- V26: 追加一条质量规则定义 —— `ADS_GMV_NET_SALE_INVARIANT`（F-55 / S3-22）
--
-- 依据（逐字）：设计 §12.3「质量12项」第 8 项 line 506「同归属口径 ADS GMV≥净销售≥0。」；
--   同节 line 512「每条规则记录作用域、阈值、版本、阶段、实际值、passed、原始/生效严重度。
--   付款 vs 订单、订单项公式、DWD/DWS 对账三者独立，不能用一个 AMOUNT_RECONCILE 覆盖。」
--   （设计 line 428「净销售 = 支付 − 成功退款」为口径来源；指导书 §7 阶段 3 line 148
--     「每个指标固定粒度、分子分母、时间窗口、金额/退款口径、空值规则、版本」。）
--   （落地前该缺口登记于 S3-21 / F-54 的 §12.3 12 项覆盖核对表：
--     `ads_operation_overview` 的关键列阻断断言只覆盖 `pv/uv/dau`，两个金额列
--     `sale_amount`/`net_sale_amount` 在**在产质量门里没有任何守卫**；等价断言此前只存在于
--     各 spec 的黄金值里。第 9 项「UV≤PV」是另一条规则，未在本迁移内、也**不得**并入本码。）
--
-- 为什么**新增迁移**而不是改 V19/V25：
--   V19、V25 都是**已发布**的迁移（F-88 / F-43），其内容按硬约束不得再改（改已发布迁移 = 决策门③）；
--   且本规则与既有 ADS 规则的作用域不同、语义不可合并：`ADS_STAGING_KEY_NOT_NULL` 判「关键列非空」，
--   `ADS_DWS_FUNNEL_RECONCILE` / `ADS_DWS_FUNNEL_RATE_RECONCILE` 判**跨层**一致性，
--   本规则判**同表同归属口径**的不变量（同一行的两列之间），三者互相不可替代（line 512）。
--   新规则码必须**先登记再产出**：§7.3.1 line 524 规定未登记规则码一律「停止发布并报未登记规则」，
--   若只加 Java 目录不落库，新码第一次产出结果就会被读侧判为未登记而整链翻红。
--   故按「一次迁移一件事」追加一条**只插一行**的加性迁移。
--
-- 本迁移做什么（可逐条核对，全文无第二条语句）：
--   1) `INSERT IGNORE` 一行 `(rule_code, version, source_scope, stage, severity, severity_mode,
--      threshold_json, enabled, effective_from, effective_to, checksum)`。
--   2) **不建表、不改列、不删行、不改任何既有行的档位或阈值**；对已手工登记过同键行的库幂等。
--
-- 档位为何是 BLOCKING（而不是 WARN/ERROR）：净销售是发布口径「支付 − 成功退款」（line 428），
--   一旦「净销售 > GMV」或「净销售 < 0」或金额列为 NULL，页面上 GMV、净销售、客单价、退款率
--   一整组结论都不可信，而 ADS 暂存存在性、关键列非空、漏斗对账可能同时全绿；
--   只判跨层一致性无法发现本类破坏。此项也**不**属设计 §12.3 第 10 项「宽松口径异常」
--   （那一项针对的是支付/浏览用户比与 cohort 解释），故不设阈值、`threshold_json` 留 NULL。
--
-- 空值规则为何判不通过：不变量「≥」在任一列 NULL 时求值为 NULL（三值逻辑），
--   若按「NULL 即跳过」处理，金额列整体未计算（空跑绿）会被静默放行 ——
--   与本表既有口径不一致（`AdsQualityJob.keyPredicates` 对 `ads_sale_trend` 已要求
--   `sale_amount`/`net_sale_amount` 非空）。故本规则显式把 NULL 判为不通过（不可证明不得放行）。
--
-- checksum 的算法（与 `QualityRuleDefinition#checksum()` 逐字节一致，不是另一套哈希）：
--   SHA-256( ruleCode \x1f version \x1f sourceScope \x1f stage \x1f severity \x1f
--            severityMode \x1f thresholdJson(缺省为空串) \x1f enabled(1/0) \x1f
--            effectiveFrom(缺省为空串) \x1f effectiveTo(缺省为空串) )
--   十六进制小写 64 字符；rationale 不参与（改错别字不应使指纹变化）。
--   本行输入 = `ADS_GMV_NET_SALE_INVARIANT` \x1f 1 \x1f `*` \x1f `ADS` \x1f `BLOCKING` \x1f
--              `FIXED` \x1f `` \x1f 1 \x1f `` \x1f ``
--   ⇒ 该行 checksum 与 `QualityRuleCatalog.DEFAULT` 里同名定义的 `checksum()` 必须相等，
--     由 `QualityRuleVersionMigrationScriptTest` 逐行对账（零漂移）。
--   （本值由独立脚本按上述算法重算，并先用 V25 的 `ADS_DWS_FUNNEL_RATE_RECONCILE`
--     已知值 3ded2e10…2121a8f 反向验证算法复现一致，再算本行。）
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
    ('ADS_GMV_NET_SALE_INVARIANT', 1, '*', 'ADS', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL,
     '8326bc0dfa8bcaeebbfb5e33b2e7dea0978891242b943a5df312bb6b111309a3');
