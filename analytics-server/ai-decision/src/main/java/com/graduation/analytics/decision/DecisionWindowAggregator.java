package com.graduation.analytics.decision;

import com.graduation.analytics.metric.MetricStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic, fail-closed aggregation for decision evaluation windows. */
final class DecisionWindowAggregator {

    /** These are additive daily facts in the currently published metric contract. */
    private static final Set<String> ADDITIVE_METRICS = Set.of(
            "gmv", "net_sale", "paid_order_cnt", "pv", "fav_cnt", "cart_add_cnt");

    private DecisionWindowAggregator() {
    }

    static Result aggregate(String metricCode, Long runtimeProfileId, Long sourceId, String definitionVersion,
                            LocalDate from, LocalDate to, List<MetricStore.WindowMetricValue> points) {
        if (metricCode == null || !ADDITIVE_METRICS.contains(metricCode)) {
            return Result.insufficient("指标 " + metricCode
                    + " 暂无已验证的窗口聚合公式；比率、UV/DAU、复购率和客单价不能按日值直接求和或平均");
        }
        if (runtimeProfileId == null || runtimeProfileId <= 0 || sourceId == null || sourceId <= 0
                || definitionVersion == null || definitionVersion.isBlank()) {
            return Result.insufficient("缺少可靠的 runtimeProfileId、sourceId 或指标口径版本，不能跨环境/来源/口径评价");
        }
        if (from == null || to == null || from.isAfter(to)) {
            return Result.insufficient("评价窗口日期无效");
        }

        List<MetricStore.WindowMetricValue> rows = points == null ? List.of() : points;
        if (rows.stream().anyMatch(java.util.Objects::isNull)) {
            return Result.insufficient("窗口包含空指标行，无法可靠验证日覆盖与快照血缘");
        }
        rows = rows.stream()
                .sorted(Comparator.comparing(MetricStore.WindowMetricValue::businessDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Set<LocalDate> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        List<String> snapshotIds = new ArrayList<>();
        for (MetricStore.WindowMetricValue point : rows) {
            if (point.value() == null || point.businessDate() == null
                    || point.snapshotId() == null || point.snapshotId().isBlank()) {
                return Result.insufficient("窗口包含空指标值或缺少快照血缘");
            }
            if (!runtimeProfileId.equals(point.runtimeProfileId()) || !metricCode.equals(point.metricCode())
                    || !sourceId.equals(point.sourceId())
                    || !definitionVersion.equals(point.definitionVersion())) {
                return Result.insufficient("窗口数据混入其他运行环境、指标、来源或口径版本");
            }
            if (point.businessDate().isBefore(from) || point.businessDate().isAfter(to)) {
                return Result.insufficient("窗口数据包含范围外业务日 " + point.businessDate());
            }
            if (!seen.add(point.businessDate())) {
                return Result.insufficient("窗口业务日重复：" + point.businessDate());
            }
            total = total.add(point.value());
            snapshotIds.add(point.snapshotId());
        }

        long expectedDays = from.datesUntil(to.plusDays(1)).count();
        if (seen.size() != expectedDays) {
            List<LocalDate> missing = from.datesUntil(to.plusDays(1)).filter(day -> !seen.contains(day)).toList();
            return new Result(null, seen.size(), List.copyOf(snapshotIds), "窗口覆盖不足：需要 "
                    + expectedDays + " 个完整业务日，实际 " + seen.size() + " 个；缺失 " + missing);
        }

        BigDecimal sum = total.setScale(4, RoundingMode.HALF_UP);
        return new Result(sum, rows.size(), List.copyOf(snapshotIds), null);
    }

    record Result(BigDecimal value, int sampleCount, List<String> snapshotIds, String insufficientReason) {
        boolean sufficient() {
            return insufficientReason == null;
        }

        static Result insufficient(String reason) {
            return new Result(null, 0, List.of(), reason);
        }
    }
}
