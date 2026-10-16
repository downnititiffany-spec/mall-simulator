package com.graduation.analytics.pipeline;

import com.graduation.analytics.contracts.EventEnvelope;
import com.graduation.analytics.metric.QualityRuleCatalog;
import com.graduation.analytics.metric.RuleSeverity;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-88（D-142 §1）：Landing 层内联质量规则的**严重度与阻断口径**。
 *
 * <p>旧实现把 AMOUNT_RECONCILE 以外三条规则一律写成 ERROR，而旧的阻断判据只看 BLOCKING，
 * 于是"必需字段缺失/非法枚举"即使未通过也会继续发布 → 与 D-142 §1「必需字段缺失 ⇒ 必须阻断」
 * 冲突。本测试锁死新口径：</p>
 * <ul>
 *   <li>AMOUNT_RECONCILE / REQUIRED_FIELD_NULL_RATE / ENUM_WHITELIST = BLOCKING（未过即阻断）；</li>
 *   <li>EVENT_ID_UNIQUE = <b>条件观察项</b>（{@code THRESHOLD_OBSERVATION}）：重复率未超批准阈值
 *       （0.0005，设计文稿 §5.4.2，未放宽）时为 WARN 观察项；<b>超阈值即升为 BLOCKING</b>。</li>
 * </ul>
 *
 * <p><b>V2.5 §7.3.1 line 522 的收窄（本轮返工）</b>：F-88 时期把 EVENT_ID_UNIQUE 无条件映射为 WARN，
 * 于是「重复率超阈值」（{@code passed=0}）也被放行 —— line 522 明令
 * 「原始重复事件<b>仅在</b>确定性去重已证<b>且重复率不超批准阈值时</b>为观察项；超过阈值阻断」，
 * 并特别指出「测试不得为通过把高重复率直接放行」。
 * 实测线上该规则确实超阈值（run 24/47 {@code error_rate=0.020408} vs {@code <=0.0005}，约 40 倍），
 * 因此本类相应把「不阻断」用例改为**未超阈值**的夹具，并新增超阈值必须阻断的用例。</p>
 */
class QualityCheckerSeverityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final QualityChecker checker = new QualityChecker();

    @Test
    @DisplayName("四条 Landing 规则的严重度固定：3 条 BLOCKING + event_id 重复率 WARN")
    void landingRuleSeveritiesAreCatalogDriven() {
        List<DataQualityResult> results = checker.check(List.of(behavior("e1", "u1", "p1", "view")), 47L,
                new HashMap<>()).results();

        assertThat(results).extracting(DataQualityResult::getRuleCode)
                .containsExactlyInAnyOrder("AMOUNT_RECONCILE", "REQUIRED_FIELD_NULL_RATE",
                        "EVENT_ID_UNIQUE", "ENUM_WHITELIST");
        assertThat(effectiveSeverityOf(results, "AMOUNT_RECONCILE")).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(effectiveSeverityOf(results, "REQUIRED_FIELD_NULL_RATE")).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(effectiveSeverityOf(results, "ENUM_WHITELIST")).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(effectiveSeverityOf(results, "EVENT_ID_UNIQUE")).isEqualTo(RuleSeverity.WARN);
        // F-88：本用例的前提是「四项判定都跟声明档位一致」——声明档位列也必须如实落库。
        // 旧断言读的是被写成生效档位的那一列，两列同值时会掩盖「声明档位丢失」这个缺陷，
        // 故此处按新契约把声明档位列也钉住。
        for (String code : List.of("AMOUNT_RECONCILE", "REQUIRED_FIELD_NULL_RATE", "ENUM_WHITELIST",
                "EVENT_ID_UNIQUE")) {
            assertThat(ruleOf(results, code).getSeverity())
                    .as("%s 的声明档位列必须等于目录声明档位", code)
                    .isEqualTo(QualityRuleCatalog.DEFAULT.find(code, null).orElseThrow().severity());
        }
    }

    @Test
    @DisplayName("event_id 重复率【未超】批准阈值 ⇒ 观察项 WARN，不阻断核心结论（下游会确定性去重）")
    void duplicateEventIdsWithinThresholdDoNotBlock() {
        // 夹具必须真的**未超阈值**：100 个事件里 0 个重复 ⇒ 重复率 0.000000 <= 0.0005。
        // （旧夹具只有 2 个事件且互为重复 ⇒ 重复率 0.5，是超阈值的，按 line 522 必须阻断 ——
        //  原用例却断言「不阻断」，等于把超阈值放行，已按 §7.3.1 line 522 修正。）
        List<EventEnvelope> events = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            events.add(behavior("e" + i, "u1", "p1", "view"));
        }
        QualityChecker.QualitySummary summary = checker.check(events, 47L, new HashMap<>());

        DataQualityResult dup = ruleOf(summary.results(), "EVENT_ID_UNIQUE");
        assertThat(dup.getPassed()).isEqualTo(1);      // 未超阈值 ⇒ 通过
        // F-88 契约：severity=声明档位（WARN）、effectiveSeverity=生效档位。未超阈值时两者同值。
        assertThat(dup.getSeverity()).isEqualTo(RuleSeverity.WARN);
        assertThat(dup.getEffectiveSeverity()).isEqualTo(RuleSeverity.WARN);
        assertThat(summary.corePassed()).isTrue();     // 观察项，不阻断发布
    }

    @Test
    @DisplayName("event_id 重复率【超】批准阈值 ⇒ 升为 BLOCKING 并阻断（§7.3.1 line 522：不得为通过而放行）")
    void duplicateEventIdsBeyondThresholdBlock() {
        QualityChecker.QualitySummary summary = checker.check(
                List.of(behavior("dup", "u1", "p1", "view"), behavior("dup", "u1", "p1", "view")), 47L,
                new HashMap<>());

        DataQualityResult dup = ruleOf(summary.results(), "EVENT_ID_UNIQUE");
        assertThat(dup.getPassed()).isEqualTo(0);        // 重复率 0.5 > 0.0005
        // 写侧落库的生效严重度必须是阻断级（原始档位是 WARN，超阈值后升为 BLOCKING）
        assertThat(dup.getEffectiveSeverity()).isEqualTo(RuleSeverity.BLOCKING);
        // F-88 核心断言：同一行两列**必须不同** —— severity 仍是**声明**档位 WARN，
        // effectiveSeverity 升为 BLOCKING。两列并存才能事后区分
        // 「声明 WARN 但生效阻断」与「本来就是阻断」。
        // （旧实现把 severity 写成生效档位，这一行被写成 BLOCKING，声明档位 WARN 永久丢失。）
        assertThat(dup.getSeverity()).isEqualTo(RuleSeverity.WARN);
        assertThat(dup.getEffectiveSeverity()).isNotEqualTo(dup.getSeverity());
        // F-88：版本化四要要素也必须在同一行落齐，且与本次冻结集一致（期望值从冻结集算，不硬编码指纹）。
        QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze(null);
        assertThat(dup.getCompatPolicyVersion()).isEqualTo(rules.compatPolicyVersion());
        assertThat(dup.getRuleFingerprint()).isEqualTo(rules.fingerprint());
        assertThat(dup.getRuleFingerprint()).hasSize(64);
        assertThat(dup.getRuleVersion()).isEqualTo(
                rules.find("EVENT_ID_UNIQUE").orElseThrow().version());
        assertThat(summary.corePassed()).isFalse();      // 必须阻断发布
        assertThat(dup.getDetail()).contains("超批准阈值");
    }

    /**
     * F88-INV-09 阈值边界用例 1：errorRate 恰为 0.0005（<= 批准阈值 0.0005）。
     *
     * <p>构造夹具：2000 个事件中恰好 1 对重复（1999 个唯一 event_id），
     * 重复率 = 1 / 2000 = 0.000500。
     * 按 QualityChecker:129 判定 {@code rate.compareTo(DUP_RATE_MAX) <= 0} 成立，
     * 判定为观察项（WARN），不阻断核心结论（corePassed = true）。</p>
     */
    @Test
    @DisplayName("F88-INV-09: event_id 重复率恰为 0.0005 ⇒ 观察项 WARN，不阻断发布")
    void duplicateRateExactlyAtApprovedThreshold00005IsObservedAndDoesNotBlock() {
        List<EventEnvelope> events = new java.util.ArrayList<>();
        // 构造 1999 个唯一事件 e0 .. e1998
        for (int i = 0; i < 1999; i++) {
            events.add(behavior("e" + i, "u1", "p1", "view"));
        }
        // 再加入 1 个重复事件 e0，总事件数 2000，重复数 1，重复率 = 1 / 2000 = 0.000500
        events.add(behavior("e0", "u1", "p1", "view"));

        QualityChecker.QualitySummary summary = checker.check(events, 47L, new HashMap<>());

        DataQualityResult dup = ruleOf(summary.results(), "EVENT_ID_UNIQUE");
        assertThat(dup.getPassed()).as("重复率 0.000500 <= 0.0005 必须判定通过").isEqualTo(1);
        assertThat(dup.getSeverity()).as("声明严重度为 WARN").isEqualTo(RuleSeverity.WARN);
        assertThat(dup.getEffectiveSeverity()).as("有效严重度为 WARN（观察项）").isEqualTo(RuleSeverity.WARN);
        assertThat(summary.corePassed()).as("观察项不阻断发布").isTrue();
        assertThat(dup.getDetail()).contains("未超批准阈值，观察项");
    }

    /**
     * F88-INV-09 阈值边界用例 2：errorRate 超过批准阈值 0.0005（如 1/1996 ≈ 0.000501 > 0.0005）。
     *
     * <p>构造夹具：1996 个事件中 1 个重复，重复率按 scale=6 HALF_UP 算得 0.000501。
     * 按 QualityChecker:129 判定 {@code rate.compareTo(DUP_RATE_MAX) <= 0} 为 false，
     * 判定未通过（passed=0），升为 BLOCKING 并阻断发布（corePassed = false）。</p>
     */
    @Test
    @DisplayName("F88-INV-09: event_id 重复率超阈值（0.000501 > 0.0005）⇒ 升为 BLOCKING 并阻断发布")
    void duplicateRateExceedingApprovedThresholdBlocks() {
        List<EventEnvelope> events = new java.util.ArrayList<>();
        // 1995 个唯一事件
        for (int i = 0; i < 1995; i++) {
            events.add(behavior("e" + i, "u1", "p1", "view"));
        }
        // 1 个重复事件，总数 1996，重复数 1，1 / 1996 = 0.000501002... -> scale=6 HALF_UP: 0.000501
        events.add(behavior("e0", "u1", "p1", "view"));

        QualityChecker.QualitySummary summary = checker.check(events, 47L, new HashMap<>());

        DataQualityResult dup = ruleOf(summary.results(), "EVENT_ID_UNIQUE");
        assertThat(dup.getPassed()).as("重复率 0.000501 > 0.0005 必须判定未通过").isEqualTo(0);
        assertThat(dup.getSeverity()).as("声明严重度仍为 WARN").isEqualTo(RuleSeverity.WARN);
        assertThat(dup.getEffectiveSeverity()).as("有效严重度升为 BLOCKING").isEqualTo(RuleSeverity.BLOCKING);
        assertThat(summary.corePassed()).as("超阈值必须阻断发布").isFalse();
        assertThat(dup.getDetail()).contains("超批准阈值，阻断");
    }

    /**
     * F88-INV-09 判据语义契约与 0.0005 vs 0.0005001 边界解析：
     *
     * <p>验证以下事实：
     * <ol>
     *   <li>直接数值比较：{@code new BigDecimal("0.0005").compareTo(DUP_RATE_MAX) <= 0} 为 true；
     *       {@code new BigDecimal("0.0005001").compareTo(DUP_RATE_MAX) <= 0} 为 false（大于 0.0005）。</li>
     *   <li>严重度判据：passed=1 映射为 WARN（不阻断）；passed=0 升为 BLOCKING（阻断）。</li>
     *   <li>源码精度语义记录：QualityChecker:128 采用 {@code divide(..., 6, RoundingMode.HALF_UP)}。
     *       对于 7 位小数（如 0.0005001），在 scale=6 HALF_UP 规则下会四舍五入为 0.000500；
     *       而在直接 BigDecimal 比较中 0.0005001 > 0.0005。此语义留总控裁定。</li>
     * </ol></p>
     */
    @Test
    @DisplayName("F88-INV-09: 阈值 0.0005 与 0.0005001 比较语义与严重度映射验证")
    void thresholdComparisonBoundaryContractAndScaleSemantic() {
        BigDecimal threshold = new BigDecimal("0.0005");
        BigDecimal exact = new BigDecimal("0.0005");
        BigDecimal over = new BigDecimal("0.0005001");

        // 1. 直接比较语义
        assertThat(exact.compareTo(threshold) <= 0).as("0.0005 <= 0.0005 成立").isTrue();
        assertThat(over.compareTo(threshold) > 0).as("0.0005001 > 0.0005 成立").isTrue();

        // 2. 规则解析严重度映射
        QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze(null);
        RuleSeverity.RuleVerdict passVerdict = RuleSeverity.resolve(rules, "EVENT_ID_UNIQUE", 1);
        assertThat(passVerdict.effectiveSeverity()).isEqualTo(RuleSeverity.WARN);
        assertThat(passVerdict.blocks()).isFalse();

        RuleSeverity.RuleVerdict failVerdict = RuleSeverity.resolve(rules, "EVENT_ID_UNIQUE", 0);
        assertThat(failVerdict.effectiveSeverity()).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(failVerdict.blocks()).isTrue();

        // 3. 证明 QualityChecker:128 舍入语义（scale=6, HALF_UP）
        BigDecimal scale6Over = BigDecimal.valueOf(5001)
                .divide(BigDecimal.valueOf(10000000), 6, RoundingMode.HALF_UP);
        assertThat(scale6Over)
                .as("5001/10000000=0.0005001 在 scale=6 HALF_UP 下舍入为 0.000500")
                .isEqualTo(new BigDecimal("0.000500"));

        BigDecimal scale6RoundUp = BigDecimal.valueOf(5005)
                .divide(BigDecimal.valueOf(10000000), 6, RoundingMode.HALF_UP);
        assertThat(scale6RoundUp)
                .as("5005/10000000=0.0005005 在 scale=6 HALF_UP 下进位为 0.000501")
                .isEqualTo(new BigDecimal("0.000501"));
        assertThat(scale6RoundUp.compareTo(threshold) > 0)
                .as("进位后的 0.000501 严格大于阈值 0.0005")
                .isTrue();
    }

    @Test
    @DisplayName("必需字段缺失（BLOCKING 未过）阻断核心结论：下游会静默丢弃该行")
    void missingRequiredFieldBlocks() {
        QualityChecker.QualitySummary summary = checker.check(
                List.of(behavior("e1", null, "p1", "view")), 47L, new HashMap<>());

        DataQualityResult rule = ruleOf(summary.results(), "REQUIRED_FIELD_NULL_RATE");
        assertThat(rule.getPassed()).isEqualTo(0);
        assertThat(rule.getEffectiveSeverity()).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(summary.corePassed()).isFalse();
    }

    @Test
    @DisplayName("非法枚举（BLOCKING 未过）阻断核心结论：下游 WHERE 白名单会静默丢弃该行")
    void illegalEnumBlocks() {
        QualityChecker.QualitySummary summary = checker.check(
                List.of(behavior("e1", "u1", "p1", "not_a_behavior")), 47L, new HashMap<>());

        DataQualityResult rule = ruleOf(summary.results(), "ENUM_WHITELIST");
        assertThat(rule.getPassed()).isEqualTo(0);
        assertThat(rule.getEffectiveSeverity()).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(summary.corePassed()).isFalse();
    }

    /**
     * R4(b)：**未登记规则码**的写侧契约（V20/F-88）。
     *
     * <p>既有 Landing 四条规则码全部已登记，走不到这条分支；但写侧对「未登记码」的落库约定
     * 必须独立钉住，否则一旦某天目录删了某个码，库里会出现「看起来像历史行」的新行：</p>
     * <ul>
     *   <li>{@code severity = null}（目录里没有这条规则，没有声明档位可记）——
     *       不是 WARN/PASS，不能被读成「无害」；</li>
     *   <li>{@code effectiveSeverity = RuleSeverity.UNREGISTERED}。注意本仓库里
     *       {@code UNREGISTERED} 是 {@code BLOCKING} 的**别名常量**（保守默认，见
     *       {@code RuleSeverity:52-62}），所以该列落库字面是 {@code "BLOCKING"}；</li>
     *   <li>{@code ruleVersion = null} —— <b>不是 0</b>。0 是内存哨兵，落库会被下游读成
     *       「登记过的第 0 版规则」；</li>
     *   <li>{@code compatPolicyVersion}/{@code ruleFingerprint} **仍非空** ——
     *       这样「写侧接入后新产生的未登记行」（{@code effective_severity} 非 NULL）
     *       与「版本化之前的历史行」（{@code effective_severity} IS NULL）可靠区分。</li>
     * </ul>
     *
     * <p><b>由此暴露的一条口径局限（已记入报告「须总控裁决」）</b>：因为 {@code UNREGISTERED} 与
     * {@code BLOCKING} 同值，单看 {@code effective_severity} <b>无法</b>区分「未登记规则按保守默认阻断」
     * 与「已登记且声明即阻断」。可靠的区分方式是 {@code rule_version IS NULL AND severity IS NULL}
     * （未登记行两列都为 NULL，已登记行两列都非空）。本用例把这一组合钉住，避免下游只看一列就下结论。</p>
     */
    @Test
    @DisplayName("未登记规则码：severity/ruleVersion 落 null（不落 0），effectiveSeverity=UNREGISTERED(别名 BLOCKING)，策略版本与指纹仍写")
    void unregisteredRuleCodeWritesNullSeverityAndStillCarriesVersionInfo() {
        QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze(null);
        String unregistered = "F88_WRITE_SIDE_NOT_REGISTERED";
        assertThat(rules.find(unregistered)).as("前提：该码确实未登记").isEmpty();

        RuleSeverity.RuleVerdict verdict = RuleSeverity.resolve(rules, unregistered, 0);
        assertThat(verdict.registered()).isFalse();

        DataQualityResult r = new DataQualityResult();
        r.setRuleCode(unregistered);
        QualityChecker.applyVersionedSeverity(r, verdict, rules);

        assertThat(r.getSeverity()).as("未登记 ⇒ 没有声明档位可记，必须落 null").isNull();
        assertThat(r.getEffectiveSeverity()).isEqualTo(RuleSeverity.UNREGISTERED);
        assertThat(RuleSeverity.UNREGISTERED).as("前提：本仓库 UNREGISTERED 是 BLOCKING 的别名")
                .isEqualTo(RuleSeverity.BLOCKING);
        assertThat(r.getRuleVersion()).as("未登记不得落版本哨兵 0").isNull();
        assertThat(r.getCompatPolicyVersion()).isEqualTo(rules.compatPolicyVersion()).isNotBlank();
        assertThat(r.getRuleFingerprint()).isEqualTo(rules.fingerprint()).isNotBlank();
        assertThat(r.getRuleFingerprint()).hasSize(64);
        // 与本类「已登记」用例的关键差别：未登记行的 severity 与 rule_version **都是 NULL**，
        // 已登记行两列都非空 —— 这是读侧区分「未登记新行」与「声明即阻断的已登记行」的可靠依据。
        assertThat(r.getSeverity()).isNull();
        assertThat(r.getRuleVersion()).isNull();
        assertThat(r.getEffectiveSeverity()).isNotNull();
    }

    /** 生效档位（V20/F-88 起落在 {@code effective_severity} 列，不再与声明档位共用一列）。 */
    private static String effectiveSeverityOf(List<DataQualityResult> results, String ruleCode) {
        return ruleOf(results, ruleCode).getEffectiveSeverity();
    }

    private static DataQualityResult ruleOf(List<DataQualityResult> results, String ruleCode) {
        return results.stream()
                .filter(r -> ruleCode.equals(r.getRuleCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未产出规则 " + ruleCode));
    }

    private static EventEnvelope behavior(String eventId, String userId, String productId, String behaviorType) {
        String payload = "{\"user_id\":" + json(userId) + ",\"product_id\":" + json(productId)
                + ",\"behavior_type\":" + json(behaviorType) + "}";
        String line = "{\"event_id\":\"" + eventId + "\",\"event_type\":\"behavior\","
                + "\"event_time\":\"2026-09-01T10:00:00+08:00\",\"ingest_time\":\"2026-09-11T10:00:00+08:00\","
                + "\"source_system\":\"mock-mall\",\"schema_version\":\"1.0\",\"trace_id\":\"T1\","
                + "\"payload\":" + payload + "}";
        return EventEnvelope.fromJson(line, MAPPER);
    }

    /** null → JSON 字面量 null（用于构造"必需字段缺失"）；其余加引号。 */
    private static String json(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }
}
