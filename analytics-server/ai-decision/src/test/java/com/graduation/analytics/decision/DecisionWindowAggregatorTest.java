package com.graduation.analytics.decision;

import com.graduation.analytics.metric.MetricStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionWindowAggregatorTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 3);

    @Test
    @DisplayName("完整连续日窗口：按日加总并保留每个来源快照")
    void sumsCompleteDailyWindow() {
        DecisionWindowAggregator.Result result = DecisionWindowAggregator.aggregate("gmv", 8L, 7L, "v1",
                FROM, TO, List.of(point("S1", 7L, "gmv", "10", FROM),
                        point("S2", 7L, "gmv", "20", FROM.plusDays(1)),
                        point("S3", 7L, "gmv", "30", TO)));

        assertTrue(result.sufficient());
        assertEquals(new BigDecimal("60.0000"), result.value());
        assertEquals(3, result.sampleCount());
        assertEquals(List.of("S1", "S2", "S3"), result.snapshotIds());
    }

    @Test
    @DisplayName("缺少一个业务日：窗口数据不足，不按已有日求部分和")
    void rejectsMissingDay() {
        DecisionWindowAggregator.Result result = DecisionWindowAggregator.aggregate("gmv", 8L, 7L, "v1",
                FROM, TO, List.of(point("S1", 7L, "gmv", "10", FROM),
                        point("S3", 7L, "gmv", "30", TO)));

        assertFalse(result.sufficient());
        assertTrue(result.insufficientReason().contains("缺失 [2026-09-02]"));
        assertEquals(2, result.sampleCount());
    }

    @Test
    @DisplayName("同一个窗口不得混用运行环境、来源或指标版本")
    void rejectsMixedRuntimeProfileSourceAndDefinitionVersion() {
        List<MetricStore.WindowMetricValue> mixedSource = List.of(
                point("S1", 7L, "gmv", "10", FROM),
                point("S2", 8L, "gmv", "20", FROM.plusDays(1)),
                point("S3", 7L, "gmv", "30", TO));
        List<MetricStore.WindowMetricValue> mixedProfile = List.of(
                point("S1", 7L, "gmv", "10", FROM),
                new MetricStore.WindowMetricValue("S2", 9L, 7L, "gmv", new BigDecimal("20"),
                        FROM.plusDays(1), "v1", LocalDateTime.now()),
                point("S3", 7L, "gmv", "30", TO));
        List<MetricStore.WindowMetricValue> mixedVersion = List.of(
                point("S1", 7L, "gmv", "10", FROM),
                new MetricStore.WindowMetricValue("S2", 8L, 7L, "gmv", new BigDecimal("20"),
                        FROM.plusDays(1), "v2", LocalDateTime.now()),
                point("S3", 7L, "gmv", "30", TO));

        assertTrue(DecisionWindowAggregator.aggregate("gmv", 8L, 7L, "v1", FROM, TO, mixedSource)
                .insufficientReason().contains("运行环境、指标、来源"));
        assertTrue(DecisionWindowAggregator.aggregate("gmv", 8L, 7L, "v1", FROM, TO, mixedProfile)
                .insufficientReason().contains("运行环境、指标、来源"));
        assertTrue(DecisionWindowAggregator.aggregate("gmv", 8L, 7L, "v1", FROM, TO, mixedVersion)
                .insufficientReason().contains("运行环境、指标、来源"));
    }

    @Test
    @DisplayName("重复日期、范围外日期和未登记聚合公式均拒绝")
    void rejectsDuplicatesOutOfRangeAndUnsupportedFormula() {
        List<MetricStore.WindowMetricValue> duplicate = List.of(
                point("S1", 7L, "gmv", "10", FROM),
                point("S1b", 7L, "gmv", "11", FROM),
                point("S3", 7L, "gmv", "30", TO));
        List<MetricStore.WindowMetricValue> outside = List.of(
                point("S0", 7L, "gmv", "10", FROM.minusDays(1)),
                point("S2", 7L, "gmv", "20", FROM.plusDays(1)),
                point("S3", 7L, "gmv", "30", TO));

        assertTrue(DecisionWindowAggregator.aggregate("gmv", 8L, 7L, "v1", FROM, TO, duplicate)
                .insufficientReason().contains("重复"));
        assertTrue(DecisionWindowAggregator.aggregate("gmv", 8L, 7L, "v1", FROM, TO, outside)
                .insufficientReason().contains("范围外"));
        assertTrue(DecisionWindowAggregator.aggregate("refund_rate", 8L, 7L, "v1", FROM, TO, List.of())
                .insufficientReason().contains("暂无已验证"));
    }

    @Test
    @DisplayName("空指标行在排序前 fail-closed，不抛异常且不保留快照引用")
    void rejectsNullPointBeforeSorting() {
        List<MetricStore.WindowMetricValue> points = new ArrayList<>();
        points.add(point("S1", 7L, "gmv", "10", FROM));
        points.add(null);

        DecisionWindowAggregator.Result result = DecisionWindowAggregator.aggregate("gmv", 8L, 7L, "v1",
                FROM, FROM, points);

        assertFalse(result.sufficient());
        assertTrue(result.insufficientReason().contains("空指标行"));
        assertTrue(result.snapshotIds().isEmpty());
    }

    private static MetricStore.WindowMetricValue point(String snapshot, Long sourceId, String metric,
                                                       String value, LocalDate day) {
        return new MetricStore.WindowMetricValue(snapshot, 8L, sourceId, metric, new BigDecimal(value), day,
                "v1", day.atTime(23, 0));
    }
}
