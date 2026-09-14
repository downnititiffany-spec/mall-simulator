-- =====================================================================
-- V19: 质量规则定义的版本化载体（F-88 / V25-Q01；§7.3.1 line 520、line 524）
--
-- 改什么：新建 `quality_rule_definition` —— 质量规则的**版本化契约表**。
--   在它之前，「某条规则是什么档位」只存在于 Java 的 `RuleSeverity` 全局 switch 里，
--   没有任何数据载体；因此一次 run 冻结的规则集无处落库，历史 run 的结论会随代码
--   改动被**追溯改写**（已登记缺陷 F-93）。本表是「某规则在某版本是什么档位」的唯一落点。
--
-- 表结构出处（§7.3.1 line 520 原文要求）：`rule_code`、`version`、`source_scope`、
--   `stage`、`severity`、`threshold_json`、`enabled`、`effective_from/to`、`checksum`，
--   且 `(scope, rule_code, version)` 唯一。本表逐条对齐，另加：
--     * `severity_mode` —— 本泳道新增的语义，line 522 要求「原始重复事件仅在确定性去重已证
--       且重复率不超批准阈值时为观察项；超过阈值阻断」。固定 WARN 无法表达「超阈值即阻断」，
--       故必须把「档位是固定值还是按阈值条件判定」也存下来（见 `QualityRuleDefinition.SeverityMode`）。
--     * `rationale` —— 中文依据，审计与页面展示用；**不参与** checksum。
--
-- 唯一键为什么是 `(source_scope, rule_code, version)`（不含 source_id 列）：
--   决策 D-10 已裁定沿用 `uk_active_profile` 口径，不新增 source_id 列；
--   「源作用域」由 `source_scope` 表达（`*` = 全源，否则为具体 source id）。
--
-- checksum 的算法（与 `QualityRuleDefinition#checksum()` **逐字节一致**，不是另一套哈希）：
--   SHA-256( ruleCode \x1f version \x1f sourceScope \x1f stage \x1f severity \x1f
--            severityMode \x1f thresholdJson(缺省为空串) \x1f enabled(1/0) \x1f
--            effectiveFrom(缺省为空串) \x1f effectiveTo(缺省为空串) )
--   十六进制小写 64 字符。rationale 不参与 —— 改错别字不应使指纹变化。
--   ⇒ **本表若与 Java 目录不一致，checksum 就会不一致**，这是刻意的可对账设计：
--     指纹 `QualityRuleCatalog.fingerprint()` 由全部 checksum 聚合而成。
--
-- 种子（35 行）为什么是 35 行、而不是「库里实际出现过的码数」：
--   本表登记的是**契约全集**（`QualityRuleCatalog.DEFAULT.definitions()` 的 35 条），
--   不是「历史数据里碰巧出现过的子集」。理由是 §7.3.1 line 524：
--   未登记的规则码一律「停止发布并报未登记规则」——登记必须**先于**该码首次产出结果，
--   否则新码一上线就被自己的读侧判为未登记。只登记历史出现过的码会让
--   `MXP_*`/`MP_*` 这些尚未在本机产出过结果的码在下次启用时集体翻红。
--   **35 行的每条 `(severity, severity_mode, threshold_json, checksum)` 均由
--   `QualityRuleCatalog.DEFAULT` 直接导出**（生成脚本与导出物留档于
--   `docs/acceptance/f88-dq-severity-20260912/raw/q01-catalog-seed-rows.tsv`），
--   即种子与 Java 目录同源，不存在第二份手抄口径。
--   导出时实测指纹 = `6bc272d7324b435d2a3c7d1afabfb5f9ca18310bad03970219b0ef132f4f3ac6`，
--   与运行期 E2 日志中 `frozen rules fingerprint` 实测值相同。
--
-- 【严重度口径的既成事实，不得被误读】
--   `EVENT_ID_UNIQUE` 与 `PUB_DQ_EVENT_ID_UNIQUE` 登记为
--   `severity=WARN` + `severity_mode=THRESHOLD_OBSERVATION`，阈值 `dupRateMax=0.0005`。
--   这**不是**「降格放行」：未超阈值时是观察项，**超阈值即阻断**（`RuleSeverity.resolve`）。
--   阈值 0.0005 来自设计文稿 §5.4.2，§7.3.1 line 522 明确「未经新裁决不得修改」，
--   本迁移**一个阈值都没有改**。
--   ⇒ 直接后果：历史 run 24/47 的 `EVENT_ID_UNIQUE` 实测 0.020408 / 0.071429，
--     分别为阈值的高倍，按本口径**合法地**判为阻断级失败。这是契约收紧，不是缺陷。
--
-- 本迁移只 **新建一张表**（建表 + 种子），不改任何既有表、不删列、不删行。
--   对既有 `data_quality_result` 的列增补**另立 V20**（一次迁移一件事）。
--
-- 【本迁移在真库上的执行状态：未执行】
--   按本泳道硬约束（DB 冻结：不起停服务、不对 3306 执行任何 DDL/DML），本脚本
--   **只在仓库存档**，将在下次平台（8091）启动时由 `MetaFlywayInitializer`
--   （`classpath:db/meta`）对真实 `analytics_meta` 执行。本轮**未在 3306 上执行**。
--   3306 只做过只读 `SELECT` 取证。
--
-- 回退方式（仅供人工按需执行，本迁移不执行）：
--   `DROP TABLE quality_rule_definition;` —— 只删本迁移新建的空表，不含业务事实数据。
-- =====================================================================

-- 1) 建表
CREATE TABLE IF NOT EXISTS quality_rule_definition (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    rule_code       VARCHAR(64)  NOT NULL
        COMMENT '规则码（大写、去空白后的规范形式，与 QualityRuleDefinition.normalize 同口径）',
    version         INT          NOT NULL
        COMMENT '规则版本号，同一 rule_code 逐版升；本条 V19 种子全部为 1',
    source_scope    VARCHAR(64)  NOT NULL DEFAULT '*'
        COMMENT '适用作用域（* = 全源；否则为具体 source_id）。D-10：不新增 source_id 列，源作用域由本列表达',
    stage           VARCHAR(32)  NOT NULL
        COMMENT '所属阶段：LANDING/DWD/DWS/ADS/PUBLISH/METRIC_PUBLISH',
    severity        VARCHAR(16)  NOT NULL
        COMMENT '该版本的严重度：BLOCKING/ERROR/WARN/INFO。取值为 THRESHOLD_OBSERVATION 时必须是 WARN（观察项档），阻断档由 RuleSeverity 给出',
    severity_mode   VARCHAR(32)  NOT NULL DEFAULT 'FIXED'
        COMMENT 'FIXED=档位恒为 severity；THRESHOLD_OBSERVATION=未超阈值是观察项、超阈值即阻断（§7.3.1 line 522）。固定 WARN 无法表达后者，故必须存',
    threshold_json  VARCHAR(512) NULL
        COMMENT '阈值（JSON 文本），无阈值时为 NULL。THRESHOLD_OBSERVATION 必须有值',
    enabled         TINYINT(1)   NOT NULL DEFAULT 1
        COMMENT '是否启用：1=启用 0=停用',
    effective_from  DATETIME(3)  NULL
        COMMENT '生效起始；NULL = 不设下界',
    effective_to    DATETIME(3)  NULL
        COMMENT '生效结束；NULL = 不设上界',
    checksum        CHAR(64)     NOT NULL
        COMMENT '本定义语义字段的 SHA-256（小写十六进制，64 字符）；算法与 QualityRuleDefinition#checksum 逐字节一致，rationale 不参与',
    rationale       VARCHAR(1000) NULL
        COMMENT '中文依据（审计与页面展示用）；不参与 checksum',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_scope_version (source_scope, rule_code, version)
        COMMENT '§7.3.1 line 520 要求 (scope, rule_code, version) 唯一',
    KEY idx_rule_code (rule_code),
    KEY idx_stage_enabled (stage, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='质量规则定义的版本化契约（F-88/V25-Q01 §7.3.1 line 520）；一次 run 冻结一组 (rule_code, version) 与指纹';

-- 2) 种子：`QualityRuleCatalog.DEFAULT` 的 35 条定义，version 全部为 1。
--    列序：(rule_code, version, source_scope, stage, severity, severity_mode,
--            threshold_json, enabled, effective_from, effective_to, checksum)
--    rationale 本迁移留 NULL：它是纯说明文本、不参与 checksum，且若在此手抄 35 段中文依据
--    就会制造「第二份口径」。依据的唯一所有者在 Java 目录（QualityRuleCatalog 的 rationale 字段）
--    与 `RuleSeverity.rationale(String)`，本表只存契约（档位/模式/阈值/指纹）。
--    `INSERT IGNORE` 使本迁移对「已手工登记过同键行」幂等，不覆盖既有行（不静默改写已生效契约）。
INSERT IGNORE INTO quality_rule_definition
    (rule_code, version, source_scope, stage, severity, severity_mode,
     threshold_json, enabled, effective_from, effective_to, checksum)
VALUES
    ('ADS_DWS_FUNNEL_RECONCILE', 1, '*', 'ADS', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '970d21d9b104f26168e0bdaab63bd8e5981256d6f7909ef40586e23101c25801'),
    ('ADS_STAGING_KEY_NOT_NULL', 1, '*', 'ADS', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, 'd7f95164d8fef4f74049b321e3a4739dd242ee9d7ef9026e769f22ad02ad8bd2'),
    ('ADS_STAGING_PRESENT', 1, '*', 'ADS', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, 'a05d761153903e925017b4fb496b335e6c67c170750b56fa88b6b4031cbff95a'),
    ('ADS_STAGING_SNAPSHOT_ISOLATION', 1, '*', 'ADS', 'WARN', 'FIXED', NULL, 1, NULL, NULL, '20b72796b338edf0d907796134132df8cf8b412060cbec3a7bac5aad53ef6d65'),
    ('AMOUNT_RECONCILE', 1, '*', 'LANDING', 'BLOCKING', 'FIXED', '{"maxAbsDiff":0.01}', 1, NULL, NULL, 'cbcd3e5987cc50615dc33a3ee4ff74a143f1ff36e6fcaf1df7b077202e4f5760'),
    ('DWD_DWS_AMOUNT_RECONCILE', 1, '*', 'DWS', 'BLOCKING', 'FIXED', '{"maxAbsDiff":0.01}', 1, NULL, NULL, '6b7af8b94c619fd3365bc73775437ae8ce0d8091ad4d55792a43b651377e4d7e'),
    ('ENUM_WHITELIST', 1, '*', 'LANDING', 'BLOCKING', 'FIXED', '{"illegalRatio":0}', 1, NULL, NULL, 'f0feb1015002c7a048a68b20d1b8b711bd58e0b54f877fd7f100b3f8a1fe348b'),
    ('EVENT_ID_UNIQUE', 1, '*', 'LANDING', 'WARN', 'THRESHOLD_OBSERVATION', '{"dupRateMax":0.0005,"dedupDeterministic":true}', 1, NULL, NULL, '8dde97499a9cf2093ea13f7050a944415ecc097921e3f34f8fae09b7f9c17abf'),
    ('MP_ACTIVE_SNAPSHOT', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '096afdaf9b61839c4e16b2fc7b50703c6713b44c385d06bce1505441ebf7750d'),
    ('MP_ADS_ROWS_DB_MATCH', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, 'e97dba0d9e7824edc9008ee9b1b591b07a1a866c18f1586d43cef325f02c29e6'),
    ('MP_ADS_ROWS_MATCH', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '57842951278718087938baa532114b157982cc5da237a25e6fa9aed1ecee9d63'),
    ('MP_ADS_WRITE_MATCH', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, 'a0598d4bedc195420124bad0b43cb37614c072a441412d7f6027b2c422fd05ef'),
    ('MP_EXPORT_FILES', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, 'c3ead5ea81e99a4dcb2138b985b62110769c5948aa2f238770b242bd825f42f5'),
    ('MP_HIVE_PATH_PINNED', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '6201a2301e850b08d8f948b6e01e3ccb56438049793dbca565a5ef1d14cb4100'),
    ('MP_MANIFEST_SNAPSHOT', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, 'b4874fe11c50b787d4ec2603370407050c8bc2708c61faf51eaeba592101825a'),
    ('MP_MANIFEST_TABLES', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, 'ec15a1db5e4000bb212d2cd2f7a3979f74a7a6cc1c8fbcf63733deaf6fe13ca4'),
    ('MP_METRIC_DICT_VERSION', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '563228177cf17449117c1a339aff21373c1c39f90b331836bc52eb68fb09f1fb'),
    ('MP_METRIC_VALUE_COUNT', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, 'fed789dba64640aae04350113097e1208b30ede967f90962122e5d47cdbb90f6'),
    ('MP_METRIC_VALUE_DB_MATCH', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '1788854c639de39005fac7fa36f9fd068865ac936889ade1eb73364eebd291f5'),
    ('MP_OLD_ACTIVE_ARCHIVED', 1, '*', 'METRIC_PUBLISH', 'INFO', 'FIXED', NULL, 1, NULL, NULL, '946ab52615fdd61346613505558d746c5a8f05f4be7ea5146657fd3316e942d8'),
    ('MP_OVERVIEW_CORE_NOT_NULL', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, 'c82a01e341371060dcb254b66ad11eb5c5a16df4f2a13e0a2996dfb2161389fb'),
    ('MP_REQUIRED_TABLES_NONEMPTY', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '129fe9b0ca3101d813213f69426381577a39fb626b2a47f71c47784f2b672db7'),
    ('MP_ROW_SHAPE_CONSISTENT', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '320b247de27a4cce926484c1cebd7268752502fc88b9fc9aeb77be591c736540'),
    ('MP_VALUE_MATCH_ADS', 1, '*', 'METRIC_PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '53cecf33ef0ee22f3cae7f66f7f954b6a47739be1868f061c8bd836374e3b3ac'),
    ('MXP_EXPORT_COMPLETE', 1, '*', 'PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '26601bc5729c1945bdc386d38b7f04ce13a9a8f78f2894b98b8c3252aad1e8ad'),
    ('MXP_EXPORT_ROWS', 1, '*', 'PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '9bb9de1de61c10ad944ca0e685a10ba6eb5766cc46b9a44d55ffa0e3db51b471'),
    ('MXP_SNAPSHOT_PINNED', 1, '*', 'PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, 'c2f5a3e00baf0de563d1ecac33d025a925e2e420fe7d7e730f8839a270df1990'),
    ('ORDER_ITEM_AMOUNT_FORMULA', 1, '*', 'DWD', 'BLOCKING', 'FIXED', '{"maxAbsDiff":0.01}', 1, NULL, NULL, '1a67141871345939bdb632b2d95b51a1692706b09309d127929598f76a165826'),
    ('PUB_DQ_BLOCKING_RULES', 1, '*', 'ADS', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '83e27ea50793a44397c39bc55d91af848ed23ed88fb6e98305253d3088e61dd1'),
    ('PUB_DQ_EVENT_ID_UNIQUE', 1, '*', 'PUBLISH', 'WARN', 'THRESHOLD_OBSERVATION', '{"dupRateMax":0.0005,"dedupDeterministic":true}', 1, NULL, NULL, '83d624fc2ce1a0efbca8a71703cd6cb5e1a47d4fe8191f629996b1d3539f2d89'),
    ('PUB_FORMAL_PARTITION_MATCH', 1, '*', 'PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, '1953722919d161390b0927a3cf397fe92e985c34c3b3c48f289378ac282db5b7'),
    ('PUB_POINTER_SWITCH', 1, '*', 'PUBLISH', 'INFO', 'FIXED', NULL, 1, NULL, NULL, 'c314d849e8f72d31301e43ca58b42accd25137117994b555c4a7388f49c5a411'),
    ('PUB_STAGING_PRUNE', 1, '*', 'PUBLISH', 'INFO', 'FIXED', NULL, 1, NULL, NULL, '0b8c23eff795e84ac64a271d1001ed261ba986e58e73fa8542c56b47443a3cdb'),
    ('PUB_STAGING_READY', 1, '*', 'PUBLISH', 'BLOCKING', 'FIXED', NULL, 1, NULL, NULL, 'a7d077cecc82d2a672e72fb29ff0c27b7906ae6785c8bb4a0416bf4337482812'),
    ('REQUIRED_FIELD_NULL_RATE', 1, '*', 'LANDING', 'BLOCKING', 'FIXED', '{"nullRateMax":0.001}', 1, NULL, NULL, '8ffdee7ceef65d1095cec0d8ad58d2e76bda7c3a610f21f50f9c925bf7f71a4e');
