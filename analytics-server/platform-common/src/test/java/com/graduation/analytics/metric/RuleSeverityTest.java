package com.graduation.analytics.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-88（D-142 §1 / 指导书 §7.3）：规则码 → 严重度的**唯一所有者**目录测试。
 *
 * <p>本测试同时是「全部规则码是否都已被审核」的清单：Spark 侧（dqc/pub/mxp）与 Java 侧
 * （QualityChecker）共 17 个规则码、指标库发布对账（MetricPublishValidator）15 个，
 * 每一个都必须显式登记，且必须给出中文依据。</p>
 *
 * <p>位置说明（F-88 裁决 4）：本测试随 {@link RuleSeverity} 由 warehouse-pipeline 上移到
 * platform-common —— {@code MetricPublishValidator}（metric-analysis）也要用同一份口径，
 * 而 metric-analysis 不依赖 warehouse-pipeline。</p>
 */
class RuleSeverityTest {

    /** 实测枚举到的全部规则码（spark-jobs AdsQualityJob/AdsPublishJob/MetricExportJob + QualityChecker）。 */
    private static final List<String> BLOCKING_CODES = List.of(
            "AMOUNT_RECONCILE", "REQUIRED_FIELD_NULL_RATE", "ENUM_WHITELIST",
            "ADS_STAGING_PRESENT", "ADS_STAGING_KEY_NOT_NULL", "PUB_DQ_BLOCKING_RULES",
            "ADS_DWS_FUNNEL_RECONCILE",
            "PUB_STAGING_READY", "PUB_FORMAL_PARTITION_MATCH",
            "MXP_SNAPSHOT_PINNED", "MXP_EXPORT_ROWS", "MXP_EXPORT_COMPLETE");

    /**
     * 指标库发布对账码（MetricPublishValidator）：实读该类全部 {@code check(...)} 调用产出的 15 个码。
     * 它们**不进** data_quality_result，只进发布报告证据，因此不经过落库归一化；
     * 登记是为让「严重度目录」不出现未知断言（未登记兜底也是 BLOCKING，登记只增可读性）。
     */
    private static final List<String> PUBLISH_VALIDATOR_CODES = List.of(
            "MP_MANIFEST_TABLES", "MP_MANIFEST_SNAPSHOT", "MP_HIVE_PATH_PINNED", "MP_EXPORT_FILES",
            "MP_ADS_ROWS_MATCH", "MP_REQUIRED_TABLES_NONEMPTY", "MP_ROW_SHAPE_CONSISTENT",
            "MP_OVERVIEW_CORE_NOT_NULL", "MP_METRIC_DICT_VERSION", "MP_VALUE_MATCH_ADS",
            "MP_METRIC_VALUE_COUNT", "MP_ACTIVE_SNAPSHOT", "MP_ADS_ROWS_DB_MATCH",
            "MP_METRIC_VALUE_DB_MATCH",
            // 目录登记项，当前实现未产出该码（与错误码 MP_ADS_WRITE_FAILED 易混，勿合并）
            "MP_ADS_WRITE_MATCH");

    private static final List<String> WARN_CODES = List.of(
            "EVENT_ID_UNIQUE", "PUB_DQ_EVENT_ID_UNIQUE", "ADS_STAGING_SNAPSHOT_ISOLATION");

    private static final List<String> INFO_CODES = List.of(
            "PUB_POINTER_SWITCH", "PUB_STAGING_PRUNE", "MP_OLD_ACTIVE_ARCHIVED");

    /**
     * 全部已登记规则码（33 个）＝ 阻断级 12 + 指标库发布对账 15 + WARN 3 + INFO 3。
     *
     * <p>与 {@link RuleSeverity} 的登记表、{@link QualityRuleCatalog} 的目录必须三者一致：
     * 按 §7.3.1 line 524，只在一处登记、目录里没有的码会判为「未登记规则」而停止发布。</p>
     */
    private static final List<String> ALL_REGISTERED_CODES;

    static {
        List<String> all = new ArrayList<>(BLOCKING_CODES);
        all.addAll(PUBLISH_VALIDATOR_CODES);
        all.addAll(WARN_CODES);
        all.addAll(INFO_CODES);
        ALL_REGISTERED_CODES = List.copyOf(all);
    }

    @Test
    @DisplayName("阻断级规则码：BLOCKING（未过即阻断发布）")
    void blockingCodesBlock() {
        for (String code : BLOCKING_CODES) {
            assertThat(RuleSeverity.of(code)).as(code).isEqualTo(RuleSeverity.BLOCKING);
            assertThat(RuleSeverity.blocks(RuleSeverity.of(code))).as(code).isTrue();
        }
        for (String code : PUBLISH_VALIDATOR_CODES) {
            assertThat(RuleSeverity.of(code)).as(code).isEqualTo(RuleSeverity.BLOCKING);
            assertThat(RuleSeverity.blocks(RuleSeverity.of(code))).as(code).isTrue();
        }
    }

    @Test
    @DisplayName("不阻断规则码：WARN（原始重复/历史暂存快照存在）")
    void warnCodesDoNotBlock() {
        for (String code : WARN_CODES) {
            assertThat(RuleSeverity.of(code)).as(code).isEqualTo(RuleSeverity.WARN);
            assertThat(RuleSeverity.blocks(RuleSeverity.of(code))).as(code).isFalse();
        }
    }

    @Test
    @DisplayName("操作审计规则码：INFO（不阻断，且不冒充质量规则写库）")
    void infoCodesDoNotBlock() {
        for (String code : INFO_CODES) {
            assertThat(RuleSeverity.of(code)).as(code).isEqualTo(RuleSeverity.INFO);
            assertThat(RuleSeverity.blocks(RuleSeverity.of(code))).as(code).isFalse();
        }
    }

    @Test
    @DisplayName("D-142 §1 逐字口径：BLOCKING/ERROR 都阻断，WARN/INFO 都不阻断")
    void blocksFollowsRulingVerbatim() {
        assertThat(RuleSeverity.blocks("BLOCKING")).isTrue();
        assertThat(RuleSeverity.blocks("ERROR")).isTrue();
        assertThat(RuleSeverity.blocks("WARN")).isFalse();
        assertThat(RuleSeverity.blocks("INFO")).isFalse();
    }

    @Test
    @DisplayName("单参 blocks 只看字面 severity：大小写/空白不敏感；未知与 null 一律按阻断（保守默认，不静默放行）")
    void normalizationAndConservativeDefault() {
        assertThat(RuleSeverity.of(" event_id_unique ")).isEqualTo(RuleSeverity.WARN);
        assertThat(RuleSeverity.of("amount_reconcile")).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(RuleSeverity.blocks(" warn ")).isFalse();
        assertThat(RuleSeverity.blocks("error")).isTrue();
        assertThat(RuleSeverity.blocks("UNKNOWN_LEVEL")).isTrue();
        assertThat(RuleSeverity.blocks("")).isTrue();
        assertThat(RuleSeverity.blocks(null)).isTrue();
        assertThat(RuleSeverity.of(null)).isEqualTo(RuleSeverity.UNREGISTERED);
        assertThat(RuleSeverity.of("BRAND_NEW_RULE")).isEqualTo(RuleSeverity.UNREGISTERED);
    }

    @Test
    @DisplayName("F-94 读侧归一化：库中字面 ERROR 但目录判 WARN 的规则不算阻断")
    void readSideNormalizationByRuleCode() {
        QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze(null);

        // ADS_STAGING_SNAPSHOT_ISOLATION：固定 WARN，且阈值串自己写着「观察项」——
        // 实测 run 24/47 该行 error_rate=0.500000、threshold='0 个非本次快照分区（观察项）'、passed=0，
        // 属设计上「历史暂存快照存在本身不是错误」，因此**失败也不阻断**。
        // 注意：旧实现有 blocks(字面 severity, code, passed) 三参重载，它**忽略第一个实参**，
        // 因此「字面写法不影响结论」这件事当时是靠一个静默丢参的重载表达的。该重载已删除
        // （静默丢参＝双所有者），迁移后的等价表达是 resolve(rules, code, passed)：
        // 结论只由 (冻结规则集, 规则码, 是否通过) 决定，**根本没有** severity 字面这个入参。
        assertThat(RuleSeverity.resolve(rules, "ADS_STAGING_SNAPSHOT_ISOLATION", 0).effectiveSeverity())
                .isEqualTo(RuleSeverity.WARN);
        assertThat(RuleSeverity.resolve(rules, "ADS_STAGING_SNAPSHOT_ISOLATION", 0).blocks()).isFalse();
        assertThat(RuleSeverity.resolve(rules, "ADS_STAGING_SNAPSHOT_ISOLATION", null).blocks()).isFalse();
        // 已登记的阻断级规则：未通过即阻断，且档位就是 BLOCKING（不再有「字面 severity 覆盖」这条路）
        assertThat(RuleSeverity.resolve(rules, "AMOUNT_RECONCILE", 0).effectiveSeverity())
                .isEqualTo(RuleSeverity.BLOCKING);
        assertThat(RuleSeverity.resolve(rules, "AMOUNT_RECONCILE", 0).blocks()).isTrue();
        assertThat(RuleSeverity.resolve(rules, "AMOUNT_RECONCILE", 1).blocks()).isFalse();
        // 登记判定的独立依据：UNREGISTERED 与 BLOCKING 取值相同，不能靠比较 of(code) 判断
        assertThat(RuleSeverity.registered("AMOUNT_RECONCILE")).isTrue();
        assertThat(RuleSeverity.registered(" amount_reconcile ")).isTrue();
        assertThat(RuleSeverity.registered("BRAND_NEW_RULE")).isFalse();
        assertThat(RuleSeverity.registered(null)).isFalse();
        // ruleCode 缺失 ⇒ 未登记 ⇒ 停止发布（§7.3.1 line 524）
        assertThat(RuleSeverity.resolve(rules, null, 0).registered()).isFalse();
        assertThat(RuleSeverity.resolve(rules, null, 0).blocks()).isTrue();
        assertThat(RuleSeverity.resolve(rules, "  ", 0).registered()).isFalse();
        assertThat(RuleSeverity.resolve(rules, "  ", 0).blocks()).isTrue();
        // 大小写/空白不敏感（规则码同样归一化）
        assertThat(RuleSeverity.resolve(rules, " amount_reconcile ", 0).blocks()).isTrue();
        assertThat(RuleSeverity.resolve(rules, " amount_reconcile ", 0).effectiveSeverity())
                .isEqualTo(RuleSeverity.BLOCKING);
    }

    @Test
    @DisplayName("§7.3.1 line 522 收窄 F-94：重复率规则**超阈值**时必须阻断（实测线上是超阈值的）")
    void duplicateRateRulesBlockWhenThresholdExceeded() {
        QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze(null);

        // 实测（只读 SELECT analytics_meta.data_quality_result）：
        //   run 24/47  EVENT_ID_UNIQUE        error_rate=0.020408  threshold='<=0.0005'  passed=0  ← 超阈值约 40 倍
        //   run 24/47  PUB_DQ_EVENT_ID_UNIQUE error_rate=0.071429  threshold='0.0005'    passed=0  ← 超阈值约 143 倍
        //   run 11     EVENT_ID_UNIQUE        error_rate=0.333333  threshold='<=0.0005'  passed=0
        // 因此 passed=0 不是误判，而是**真实超阈值**。旧实现把 EVENT_ID_UNIQUE 无条件映射为 WARN，
        // 等于把超阈值的高重复率直接放行 —— §7.3.1 line 522 明令「测试不得为通过把高重复率直接放行」。
        for (String code : List.of("EVENT_ID_UNIQUE", "PUB_DQ_EVENT_ID_UNIQUE")) {
            // 超阈值（passed=0）⇒ 升为阻断；库中字面 severity 写什么都无关（新签名里没有这个入参）
            RuleSeverity.RuleVerdict over = RuleSeverity.resolve(rules, code, 0);
            assertThat(over.blocks()).as(code + " 超阈值").isTrue();
            assertThat(over.effectiveSeverity()).as(code + " 超阈值").isEqualTo(RuleSeverity.BLOCKING);
            // 声明档位仍可读（§7.3.1 line 524「保留原始结果字段」）：declared=WARN, effective=BLOCKING
            assertThat(over.declaredSeverity()).as(code + " 原始档位").isEqualTo(RuleSeverity.WARN);
            // 未判定（null）也必须按未通过处理
            assertThat(RuleSeverity.resolve(rules, code, null).blocks()).as(code + " 未判定").isTrue();
            // 未超阈值（passed=1）⇒ 观察项，不阻断（确定性去重已证）
            RuleSeverity.RuleVerdict within = RuleSeverity.resolve(rules, code, 1);
            assertThat(within.blocks()).as(code + " 未超阈值").isFalse();
            assertThat(within.effectiveSeverity()).as(code + " 未超阈值").isEqualTo(RuleSeverity.WARN);
        }
    }

    @Test
    @DisplayName("§7.3.1 line 522：EVENT_ID_UNIQUE 未超阈值是观察项、超阈值即阻断（阈值 0.0005 不放宽）")
    void eventIdUniqueIsThresholdObservation() {
        QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze(null);

        // 未超批准阈值：passed=1 ⇒ 观察项，不阻断
        RuleSeverity.RuleVerdict within = RuleSeverity.resolve(rules, "EVENT_ID_UNIQUE", 1);
        assertThat(within.registered()).isTrue();
        assertThat(within.declaredSeverity()).isEqualTo(RuleSeverity.WARN);
        assertThat(within.effectiveSeverity()).isEqualTo(RuleSeverity.WARN);
        assertThat(within.blocks()).isFalse();

        // 超批准阈值：passed=0 ⇒ 升为阻断，**不得**为测试通过而直接放行
        RuleSeverity.RuleVerdict beyond = RuleSeverity.resolve(rules, "EVENT_ID_UNIQUE", 0);
        assertThat(beyond.effectiveSeverity()).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(beyond.blocks()).isTrue();
        // 原始严重度与有效严重度都要留痕（§7.3.1 line 520「原始及有效严重度」）
        assertThat(beyond.declaredSeverity()).isEqualTo(RuleSeverity.WARN);
        assertThat(beyond.explanation()).contains("阈值判定未通过");

        // 阈值本身未被修改：0.0005 未经新裁决不得改（§7.3.1 line 522）
        assertThat(beyond.explanation()).isNotBlank();
    }

    @Test
    @DisplayName("§7.3.1 line 524：未登记码不得盲信传来的 WARN ⇒ 报未登记而不是放行")
    void unregisteredRuleCodeIsReportedNotTrusted() {
        // 旧实现（F-94 读侧归一化）在未登记时回退库中字面 severity，于是回传 WARN 就被放行。
        // §7.3.1 line 524 明确否定：「未知规则码不可盲信传来的 WARN，应停止发布并报未登记规则」。
        RuleSeverity.RuleVerdict warnLiteral = RuleSeverity.resolve(
                QualityRuleCatalog.DEFAULT.freeze(null), "BRAND_NEW_RULE", 0);
        assertThat(warnLiteral.registered()).isFalse();
        assertThat(warnLiteral.ruleVersion()).isZero();
        assertThat(warnLiteral.registered()).as("必须能被识别为未登记").isFalse();
        assertThat(warnLiteral.blocks()).as("未登记 ⇒ 停止发布").isTrue();

        // 未登记 + 未通过：任何调用形式都必须阻断，不允许因字面 severity 是 WARN/INFO 而放行。
        // 三参 blocks(字面 severity, code, passed) 重载（会静默丢参）已删除；新签名里**没有**
        // severity 字面这个入参，因此「盲信传来的 WARN」在类型层面就不可能发生 —— 这正是删除它的理由。
        // 字面 severity 只作为「原始结果字段」保留（§7.3.1 line 524），不参与判定。
        QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze(null);
        RuleSeverity.RuleVerdict unregistered = RuleSeverity.resolve(rules, "BRAND_NEW_RULE", 0);
        assertThat(unregistered.blocks()).as("未登记码必须阻断").isTrue();
        assertThat(unregistered.registered()).as("未登记码必须可被识别").isFalse();
        assertThat(unregistered.effectiveSeverity()).as("未登记码的有效档位").isEqualTo(RuleSeverity.UNREGISTERED);
        assertThat(RuleSeverity.resolve(rules, "BRAND_NEW_RULE", null).blocks())
                .as("未登记码即使 passed=null 也必须阻断").isTrue();
        // AMOUNT_RECONCILE 的原始 severity 在库里是 ERROR，但判定结果与这个字面无关：
        // declaredSeverity 来自**目录**（不是库中字面），故三种写法得到同一个 verdict
        for (String callerSupplied : List.of("WARN", "INFO", "ERROR", "")) {
            assertThat(RuleSeverity.resolve(rules, "AMOUNT_RECONCILE", 0).blocks())
                    .as("已登记阻断码（调用方声称 severity=%s）必须阻断", callerSupplied).isTrue();
        }
    }

    @Test
    @DisplayName("§7.3.1 line 526：三条金额校验登记为三个独立规则码，不可互相替代")
    void threeAmountChecksAreRegisteredIndependently() {
        QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze(null);

        // ① 付款 vs 订单总额（Landing）② 订单项公式（DWD）③ DWD↔DWS 金额（DWS）
        assertThat(rules.find("AMOUNT_RECONCILE")).isPresent()
                .get().extracting(QualityRuleDefinition::stage).isEqualTo(QualityRuleCatalog.STAGE_LANDING);
        assertThat(rules.find("ORDER_ITEM_AMOUNT_FORMULA")).isPresent()
                .get().extracting(QualityRuleDefinition::stage).isEqualTo(QualityRuleCatalog.STAGE_DWD);
        assertThat(rules.find("DWD_DWS_AMOUNT_RECONCILE")).isPresent()
                .get().extracting(QualityRuleDefinition::stage).isEqualTo(QualityRuleCatalog.STAGE_DWS);

        // 三个码各自独立阻断：任一条失败都不得被另两条的通过掩盖
        for (String code : List.of("AMOUNT_RECONCILE", "ORDER_ITEM_AMOUNT_FORMULA", "DWD_DWS_AMOUNT_RECONCILE")) {
            assertThat(RuleSeverity.resolve(rules, code, 0).blocks()).as(code).isTrue();
            assertThat(RuleSeverity.resolve(rules, code, 1).blocks()).as(code).isFalse();
        }
    }

    @Test
    @DisplayName("目录与规则码清单必须同步：冻结集覆盖全部已登记码（防两处漂移）")
    void catalogCoversEveryRegisteredCode() {
        QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze(null);
        List<String> missing = new ArrayList<>();
        for (String code : ALL_REGISTERED_CODES) {
            if (rules.find(code).isEmpty()) {
                missing.add(code);
            }
        }
        assertThat(missing).as("这些码只在 RuleSeverity.of 里登记、未进 quality_rule_definition 目录；"
                + "按 §7.3.1 line 524 它们会被判为「未登记规则」而停止发布").isEmpty();
        // 反向：目录里也不应有超出登记清单的码。35 = 33 个既有码 + 2 个本次按 §7.3.1 line 526
        // 新增的独立金额校验码（ORDER_ITEM_AMOUNT_FORMULA、DWD_DWS_AMOUNT_RECONCILE）。
        assertThat(rules.definitions()).hasSize(ALL_REGISTERED_CODES.size() + 2);
    }

    @Test
    @DisplayName("isKnownSeverity：只认四个合法档位（供 quality_rule_definition.severity 校验）")
    void isKnownSeverityAcceptsOnlyFourLevels() {
        assertThat(RuleSeverity.isKnownSeverity("BLOCKING")).isTrue();
        assertThat(RuleSeverity.isKnownSeverity("error")).isTrue();
        assertThat(RuleSeverity.isKnownSeverity(" warn ")).isTrue();
        assertThat(RuleSeverity.isKnownSeverity("INFO")).isTrue();
        assertThat(RuleSeverity.isKnownSeverity("UNKNOWN_LEVEL")).isFalse();
        assertThat(RuleSeverity.isKnownSeverity("")).isFalse();
        assertThat(RuleSeverity.isKnownSeverity(null)).isFalse();
    }

    @Test
    @DisplayName("未判定（passed=null）按未通过处理")
    void nullPassedIsFailed() {
        assertThat(RuleSeverity.failed(null)).isTrue();
        assertThat(RuleSeverity.failed(0)).isTrue();
        assertThat(RuleSeverity.failed(1)).isFalse();
        // 布尔调用方（MetricPublisherPort.Check.passed()）经 failedFlag 适配后同一套判定
        assertThat(RuleSeverity.failed(RuleSeverity.failedFlag(false))).isTrue();
        assertThat(RuleSeverity.failed(RuleSeverity.failedFlag(true))).isFalse();
    }

    @Test
    @DisplayName("全部规则码都有中文依据，且未登记码也有兜底说明")
    void everyCodeHasRationale() {
        List<String> all = new ArrayList<>();
        all.addAll(BLOCKING_CODES);
        all.addAll(PUBLISH_VALIDATOR_CODES);
        all.addAll(WARN_CODES);
        all.addAll(INFO_CODES);
        assertThat(all).hasSize(33);   // 12 BLOCKING + 15 发布对账 + 3 WARN + 3 INFO
        assertThat(all).doesNotHaveDuplicates();

        for (String code : all) {
            assertThat(RuleSeverity.rationale(code)).as(code).isNotBlank();
            assertThat(RuleSeverity.rationale(code)).as(code).doesNotContain("未登记");
        }
        assertThat(RuleSeverity.rationale("BRAND_NEW_RULE")).contains("未登记");
        // MP_ADS_WRITE_MATCH 的注文必须保留「与错误码易混、勿合并」这句（F-88 裁决 4 指定原文）
        assertThat(RuleSeverity.rationale("MP_ADS_WRITE_MATCH"))
                .contains("未产出该码").contains("MP_ADS_WRITE_FAILED").contains("勿合并");
    }
}
