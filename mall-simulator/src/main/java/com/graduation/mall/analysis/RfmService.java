package com.graduation.mall.analysis;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFM 用户分层（§21.6，与 spark-jobs/RfmScorer 同口径）：
 * R=最近支付间隔(天，小=好)、F=有效支付订单数、M=净支付金额（大=好）；
 * 三分位五档评分（1..5，R 反向），八类标签规则与 RfmScorer.octant 一致；
 * 生命周期：流失风险>60 天 / 沉默>30 天 / 活跃。
 */
@Service
@RequiredArgsConstructor
public class RfmService {

    public static final List<String> PAID_STATUSES = List.of("PAID", "COMPLETED", "REFUNDED");

    private static final List<String> LABEL_ORDER = List.of("重要价值", "重要发展", "重要保持", "重要挽留",
            "一般价值", "一般发展", "一般保持", "一般挽留");

    private final JdbcTemplate jdbc;

    public record RfmUser(Long userId, BigDecimal recency, Long frequency, BigDecimal monetary,
                          int rScore, int fScore, int mScore, String label, String lifecycle) {
    }

    public record RfmReport(List<RfmUser> users, Map<String, Long> distribution) {
    }

    /** 可变的中间行（分桶过程中使用） */
    private static final class Row {
        final Long userId;
        final BigDecimal recency;
        final Long frequency;
        final BigDecimal monetary;
        int r = 1, f = 1, m = 1;
        String label;
        String lifecycle;

        Row(Long userId, BigDecimal recency, Long frequency, BigDecimal monetary) {
            this.userId = userId;
            this.recency = recency;
            this.frequency = frequency;
            this.monetary = monetary;
        }
    }

    public RfmReport rfmReport(int limit) {
        List<Row> rows = jdbc.query(
                "SELECT user_id, DATEDIFF(NOW(), MAX(paid_at)) AS r_days, COUNT(*) AS f_cnt, SUM(total_amount) AS m_sum " +
                        "FROM mall_order WHERE status IN ('PAID','COMPLETED','REFUNDED') AND paid_at IS NOT NULL " +
                        "GROUP BY user_id",
                (rs, i) -> new Row(rs.getLong("user_id"),
                        BigDecimal.valueOf(Math.max(0, rs.getLong("r_days"))),
                        rs.getLong("f_cnt"),
                        rs.getBigDecimal("m_sum") == null ? BigDecimal.ZERO : rs.getBigDecimal("m_sum")));
        if (rows.isEmpty()) {
            return new RfmReport(List.of(), Map.of());
        }
        // 三分位五档：R 反向（越小分越高），F/M 正向
        assign(rows, r -> r.recency.doubleValue(), true, (r, s) -> r.r = s);
        assign(rows, r -> (double) r.frequency, false, (r, s) -> r.f = s);
        assign(rows, r -> r.monetary.doubleValue(), false, (r, s) -> r.m = s);

        List<RfmUser> users = new ArrayList<>();
        for (Row row : rows) {
            row.label = octant(row.r, row.f, row.m);
            int days = row.recency.intValue();
            row.lifecycle = days > 60 ? "流失风险" : days > 30 ? "沉默" : "活跃";
            users.add(new RfmUser(row.userId, row.recency, row.frequency, row.monetary,
                    row.r, row.f, row.m, row.label, row.lifecycle));
        }

        Map<String, Long> distribution = new LinkedHashMap<>();
        LABEL_ORDER.forEach(l -> distribution.put(l, users.stream().filter(u -> l.equals(u.label())).count()));

        List<RfmUser> top = users.stream()
                .sorted(Comparator.comparing((RfmUser u) -> u.monetary()).reversed()
                        .thenComparing(RfmUser::recency))
                .limit(Math.max(1, Math.min(100, limit)))
                .toList();
        return new RfmReport(top, distribution);
    }

    private static void assign(List<Row> rows, java.util.function.Function<Row, Double> metric,
                               boolean reversed, java.util.function.BiConsumer<Row, Integer> setter) {
        List<Double> sorted = rows.stream().map(metric).sorted(Double::compareTo).toList();
        int n = sorted.size();
        double[] thresholds = new double[4];
        for (int i = 0; i < 4; i++) {
            thresholds[i] = sorted.get(Math.min(n - 1, (int) ((i + 1) * (n + 1) / 5.0 - 1)));
        }
        for (Row row : rows) {
            double v = metric.apply(row);
            int score = 1;
            for (double t : thresholds) {
                if (reversed ? v <= t : v > t) {
                    score++;
                }
            }
            setter.accept(row, Math.min(5, Math.max(1, score)));
        }
    }

    /** 八类标签（与 RfmScorer.octant 同规则） */
    static String octant(int rScore, int fScore, int mScore) {
        if (mScore >= 4) {
            if (rScore >= 4) {
                return fScore >= 4 ? "重要价值" : "重要发展";
            }
            return fScore >= 4 ? "重要保持" : "重要挽留";
        }
        if (rScore >= 4) {
            return fScore >= 4 ? "一般价值" : "一般发展";
        }
        return fScore >= 4 ? "一般保持" : "一般挽留";
    }
}