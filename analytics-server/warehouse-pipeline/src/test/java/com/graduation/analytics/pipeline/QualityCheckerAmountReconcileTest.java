package com.graduation.analytics.pipeline;

import com.graduation.analytics.contracts.EventEnvelope;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEF-04 回归：金额对账（AMOUNT_RECONCILE，BLOCKING）的**对账范围**必须覆盖整批订单，而不是业务日切片。
 *
 * <p>实测来源（2026-09-11 run 35，生成器 1000 行契约制品 → ODS→ADS 全链路）：平台按业务日装载事件
 * （`PipelineService.datePrefix=2026-09-01`，切片 42/1000 条），54 笔支付里有 27 笔的 `order_created`
 * 不在同一天（T 日下单、T+1 日支付），旧实现按切片建订单总额索引 → 27 笔"查无订单总额" → 判为对账失败
 * → 阻断整条链路（`PIPELINE_QUALITY_FAILED`），而生成器制品侧独立核对为 54/54 笔金额**完全一致**。
 */
class QualityCheckerAmountReconcileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final QualityChecker checker = new QualityChecker();

    @Test
    @DisplayName("跨日支付：order_created 不在业务日切片内，但整批索引里有且金额一致 → 对账通过")
    void crossDayPaymentReconcilesWithBatchWideIndex() {
        // 切片内只有支付事件；下单事件属于前一天（切片视角看不到）
        List<EventEnvelope> slice = List.of(paid("O1", "321.09"));

        Map<String, BigDecimal> batchTotals = new HashMap<>();
        batchTotals.put("O1", new BigDecimal("321.09"));

        DataQualityResult rule = amountRule(checker.check(slice, 35L, batchTotals).results());

        assertThat(rule.getCheckCount()).isEqualTo(1);
        assertThat(rule.getErrorCount()).isEqualTo(0);
        assertThat(rule.getPassed()).isEqualTo(1);
        assertThat(rule.getDetail()).contains("整批无订单 0 笔");
    }

    @Test
    @DisplayName("整批索引缺失该订单（真孤儿支付）→ 仍然阻断，不放宽核心规则")
    void orphanPaymentStillBlocks() {
        List<EventEnvelope> slice = List.of(paid("O9", "10.00"));

        QualityChecker.QualitySummary summary = checker.check(slice, 35L, new HashMap<>());

        DataQualityResult rule = amountRule(summary.results());
        assertThat(rule.getErrorCount()).isEqualTo(1);
        assertThat(rule.getPassed()).isEqualTo(0);
        assertThat(summary.corePassed()).isFalse();
        assertThat(rule.getDetail()).contains("整批无订单 1 笔");
    }

    @Test
    @DisplayName("整批有订单但金额不等 → 仍判失败（对账性不能被跨日修复掩盖）")
    void amountMismatchStillFails() {
        List<EventEnvelope> slice = List.of(paid("O2", "99.99"));
        Map<String, BigDecimal> batchTotals = Map.of("O2", new BigDecimal("100.00"));

        QualityChecker.QualitySummary summary = checker.check(slice, 35L, batchTotals);

        DataQualityResult rule = amountRule(summary.results());
        assertThat(rule.getErrorCount()).isEqualTo(1);
        assertThat(rule.getPassed()).isEqualTo(0);
        assertThat(summary.corePassed()).isFalse();
        assertThat(rule.getDetail()).contains("金额不等 1 笔");
    }

    @Test
    @DisplayName("两参重载保持旧语义：只用传入事件集建索引（同日下单+支付可对账）")
    void twoArgOverloadKeepsPreviousSemantics() {
        List<EventEnvelope> sameDay = List.of(
                created("O3", "58.00"), paid("O3", "58.00"));

        // 注意：两参重载的切片里没有 order_created 时必然判失败——这正是 DEF-04 的成因，
        // 生产路径已改用三参重载传入整批索引。
        assertThat(amountRule(checker.check(sameDay, 35L).results()).getPassed()).isEqualTo(1);
        assertThat(amountRule(checker.check(List.of(paid("O3", "58.00")), 35L).results()).getPassed())
                .isEqualTo(0);
    }

    private static DataQualityResult amountRule(List<DataQualityResult> results) {
        return results.stream()
                .filter(r -> "AMOUNT_RECONCILE".equals(r.getRuleCode()))
                .findFirst()
                .orElseThrow();
    }

    private static EventEnvelope paid(String orderId, String amount) {
        return envelope("order_paid", "{\"order_id\":\"" + orderId + "\",\"user_id\":\"U1\",\"amount\":\""
                + amount + "\"}");
    }

    private static EventEnvelope created(String orderId, String totalAmount) {
        return envelope("order_created", "{\"order_id\":\"" + orderId + "\",\"user_id\":\"U1\",\"total_amount\":\""
                + totalAmount + "\"}");
    }

    private static EventEnvelope envelope(String eventType, String payloadJson) {
        String line = "{\"event_id\":\"E-" + eventType + "-" + payloadJson.hashCode() + "\","
                + "\"event_type\":\"" + eventType + "\",\"event_time\":\"2026-09-01T10:00:00+08:00\","
                + "\"ingest_time\":\"2026-09-11T10:00:00+08:00\",\"source_system\":\"mock-mall\","
                + "\"schema_version\":\"1.0\",\"trace_id\":\"T1\",\"payload\":" + payloadJson + "}";
        return EventEnvelope.fromJson(line, MAPPER);
    }
}
