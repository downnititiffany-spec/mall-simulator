package com.graduation.analytics.pipeline;

import com.graduation.analytics.contracts.EventEnvelope;
import com.graduation.analytics.metric.RuleSeverity;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        assertThat(severityOf(results, "AMOUNT_RECONCILE")).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(severityOf(results, "REQUIRED_FIELD_NULL_RATE")).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(severityOf(results, "ENUM_WHITELIST")).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(severityOf(results, "EVENT_ID_UNIQUE")).isEqualTo(RuleSeverity.WARN);
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
        assertThat(dup.getSeverity()).isEqualTo(RuleSeverity.WARN);
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
        // 写侧落库的有效严重度必须是阻断级（原始档位是 WARN，超阈值后升为 BLOCKING）
        assertThat(dup.getSeverity()).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(summary.corePassed()).isFalse();      // 必须阻断发布
        assertThat(dup.getDetail()).contains("超批准阈值");
    }

    @Test
    @DisplayName("必需字段缺失（BLOCKING 未过）阻断核心结论：下游会静默丢弃该行")
    void missingRequiredFieldBlocks() {
        QualityChecker.QualitySummary summary = checker.check(
                List.of(behavior("e1", null, "p1", "view")), 47L, new HashMap<>());

        DataQualityResult rule = ruleOf(summary.results(), "REQUIRED_FIELD_NULL_RATE");
        assertThat(rule.getPassed()).isEqualTo(0);
        assertThat(rule.getSeverity()).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(summary.corePassed()).isFalse();
    }

    @Test
    @DisplayName("非法枚举（BLOCKING 未过）阻断核心结论：下游 WHERE 白名单会静默丢弃该行")
    void illegalEnumBlocks() {
        QualityChecker.QualitySummary summary = checker.check(
                List.of(behavior("e1", "u1", "p1", "not_a_behavior")), 47L, new HashMap<>());

        DataQualityResult rule = ruleOf(summary.results(), "ENUM_WHITELIST");
        assertThat(rule.getPassed()).isEqualTo(0);
        assertThat(rule.getSeverity()).isEqualTo(RuleSeverity.BLOCKING);
        assertThat(summary.corePassed()).isFalse();
    }

    private static String severityOf(List<DataQualityResult> results, String ruleCode) {
        return ruleOf(results, ruleCode).getSeverity();
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
