package com.graduation.analytics.analysis;

import com.graduation.analytics.metric.MetricAdsReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * RFM 用户分层聚合（§18.2 用户分群、§21.6，R7-4 契约 §3.5/§3.6）。
 *
 * <p>R7-4 起本服务**只读指标库** {@code ads_user_profile_m}（MetricAdsReader 表名白名单内）：
 * r/f/m 分、八类 value_group、lifecycle_state、rule_version 都是 Spark RfmScorer 已发布的口径产物。
 * 为什么不再自己分位打分：三分位五档与八类标签的口径所有者是 Spark 侧，
 * 服务端重算等于第二个口径所有者，还可能和页面回显的 rule_version 对不上（§18.1）。</p>
 *
 * <p>只返回聚合结果，不返回 user_id 明细：普通员工不得查看真实个人敏感数据（§18.2 用户分群）。</p>
 */
@Service
@RequiredArgsConstructor
public class RfmService {

    /** 八类价值分组规范顺序（与 Spark RfmScorer 的八类标签一致；缺失类目补 0 人数而不是丢类） */
    public static final List<String> VALUE_GROUPS = List.of("重要价值", "重要发展", "重要保持", "重要挽留",
            "一般价值", "一般发展", "一般保持", "一般挽留");

    /** 生命周期规范顺序（与 RfmScorer 的 lifecycle_state 取值一致） */
    private static final List<String> LIFECYCLE_ORDER = List.of("活跃", "沉默", "流失风险");

    private static final String T_USER_PROFILE = "ads_user_profile_m";

    /** calc_date/末次购买日按 yyyyMMdd 落库（见 db/metric V3 的 VARCHAR 列），也兼容 ISO 日期 */
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MetricAdsReader adsReader;

    /**
     * 单个价值分组聚合行。
     *
     * <p>S3-16 起消费已落库的 RFM **原值**（设计 V3.0 §11.2 L435）：{@code amount} = 组内
     * Σ{@code m_amount}（观察期有效支付金额），{@code orders} = 组内 Σ{@code f_count}
     * （观察期有效支付订单数），{@code avgRecencyDays} 优先取 {@code r_days} 原值均值。
     * 原值列**不可用**时对应字段为 null 并挂降级警告——不用 m 分求和或 0 冒充
     * （§11.4 L449「M 不明时保留 null+警告，不能把缺列当 0」）。</p>
     *
     * @param avgRecencyDays 组内平均最近购买间隔（天，R 原值均值）；取不到原值且旧口径也推不出时为 null
     * @param orders         组内观察期有效支付订单数合计（F 原值）；列不可用时为 null
     */
    public record RfmSegment(String valueGroup, long users, BigDecimal share, BigDecimal amount,
                             BigDecimal avgRecencyDays, Long orders) {
    }

    public record LifecycleState(String state, long users) {
    }

    public record CategoryPreference(long categoryId, long users) {
    }

    /**
     * 快照级 RFM 画像聚合结果。
     *
     * @param segments   实际出现的价值分组（按八类规范顺序，未知分组排在后面）
     * @param matrix     八类全量（缺失补 0 人数与 0 占比），供 §3.6 的 rfmMatrix 使用
     * @param ruleVersion 画像规则版本（rule_version 列回显；多版本时取字典序最小并给 warning）
     * @param periodStart 本次评分实际使用的观察窗口起点（ISO，来自 period_start）；不可用时 null
     * @param periodEnd   观察窗口终点（ISO，来自 period_end）；不可用时 null
     */
    public record RfmProfile(String snapshotId, String ruleVersion, long userCount,
                             List<RfmSegment> segments, List<RfmSegment> matrix,
                             List<LifecycleState> lifecycle, List<CategoryPreference> preference,
                             String periodStart, String periodEnd, List<String> warnings) {
    }

    /**
     * 按快照聚合用户画像 ADS。调用方必须先固定 snapshotId（AnalysisService 负责解析 ACTIVE）。
     *
     * <p>只取该快照的**最新 dt 分区**：画像表按 (snapshot_id, dt, user_id) 存，
     * 一个快照含多天时把多天行混在一起会重复计数（契约 §3.5 只给 snapshotId，没有日期维度）。</p>
     */
    public RfmProfile profile(String snapshotId) {
        List<Map<String, Object>> rows = AdsRows.latestPartition(adsReader, T_USER_PROFILE, snapshotId);
        if (rows.isEmpty()) {
            return new RfmProfile(snapshotId, null, 0L, List.of(), List.of(), List.of(), List.of(),
                    null, null, List.of());
        }
        long total = rows.size();

        Map<String, GroupAccum> byGroup = new LinkedHashMap<>();
        Map<String, Long> byLifecycle = new TreeMap<>();
        Map<Long, Long> byCategory = new TreeMap<>();
        TreeSet<String> ruleVersions = new TreeSet<>();
        TreeSet<String> periodStarts = new TreeSet<>();
        TreeSet<String> periodEnds = new TreeSet<>();
        // 原值列「逐行完整性」：任一行缺该列即视为不可用（缺列与真值 0 必须区分）
        boolean recencyRawComplete = true;
        boolean ordersRawComplete = true;
        boolean amountRawComplete = true;
        for (Map<String, Object> row : rows) {
            GroupAccum acc = byGroup.computeIfAbsent(AdsRows.asString(row.get("value_group")), g -> new GroupAccum());
            acc.users++;
            Long rDays = AdsRows.asLongOrNull(row.get("r_days"));
            if (rDays == null) {
                recencyRawComplete = false;
                // 旧快照降级路径：calc_date − last_buy_date（口径与 r_days 不同，故必须挂警告）
                acc.addRecency(recencyDays(row));
            } else {
                acc.addRecency(rDays);
            }
            BigDecimal amount = AdsRows.asDecimal(row.get("m_amount"));
            if (amount == null) {
                amountRawComplete = false;
            } else {
                acc.addAmount(amount);
            }
            Long orders = AdsRows.asLongOrNull(row.get("f_count"));
            if (orders == null) {
                ordersRawComplete = false;
            } else {
                acc.addOrders(orders);
            }
            byLifecycle.merge(AdsRows.asString(row.get("lifecycle_state")), 1L, Long::sum);
            byCategory.merge(AdsRows.asLong(row.get("favorite_category")), 1L, Long::sum);
            String ruleVersion = AdsRows.asString(row.get("rule_version"));
            if (!ruleVersion.isEmpty()) {
                ruleVersions.add(ruleVersion);
            }
            String periodStart = AdsRows.asTrimmedOrNull(row.get("period_start"));
            if (periodStart != null) {
                periodStarts.add(periodStart);
            }
            String periodEnd = AdsRows.asTrimmedOrNull(row.get("period_end"));
            if (periodEnd != null) {
                periodEnds.add(periodEnd);
            }
        }

        List<RfmSegment> segments = new ArrayList<>();
        for (String group : orderedGroups(byGroup.keySet())) {
            GroupAccum acc = byGroup.get(group);
            segments.add(new RfmSegment(group, acc.users, share(acc.users, total), acc.amount(),
                    acc.avgRecencyDays(), acc.orders()));
        }

        List<RfmSegment> matrix = new ArrayList<>();
        for (String group : VALUE_GROUPS) {
            GroupAccum acc = byGroup.get(group);
            matrix.add(acc == null
                    ? new RfmSegment(group, 0L, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP), null, null, null)
                    : new RfmSegment(group, acc.users, share(acc.users, total), acc.amount(),
                            acc.avgRecencyDays(), acc.orders()));
        }

        List<LifecycleState> lifecycle = new ArrayList<>();
        byLifecycle.forEach((state, users) -> lifecycle.add(new LifecycleState(state, users)));
        lifecycle.sort(Comparator
                .comparingInt((LifecycleState s) -> AdsRows.orderIndex(LIFECYCLE_ORDER, s.state()))
                .thenComparing(LifecycleState::state));

        List<CategoryPreference> preference = new ArrayList<>();
        byCategory.forEach((categoryId, users) -> preference.add(new CategoryPreference(categoryId, users)));
        preference.sort(Comparator.comparingLong(CategoryPreference::users).reversed()
                .thenComparingLong(CategoryPreference::categoryId));

        // 观察窗口：只有「起点唯一且终点唯一」才敢回显；行间不一致 ⇒ null + 警告（不猜窗口）
        boolean windowAvailable = periodStarts.size() == 1 && periodEnds.size() == 1;
        String periodStart = windowAvailable ? periodStarts.first() : null;
        String periodEnd = windowAvailable ? periodEnds.first() : null;

        // 警告顺序固定（金额 → 原值 → 窗口 → 规则版本），便于对账与断言
        List<String> warnings = new ArrayList<>();
        if (!amountRawComplete) {
            warnings.add(AnalysisViewModel.WARN_RFM_AMOUNT_UNAVAILABLE);
        }
        if (!recencyRawComplete || !ordersRawComplete) {
            warnings.add(AnalysisViewModel.WARN_RFM_RAW_VALUES_UNAVAILABLE);
        }
        if (!windowAvailable) {
            warnings.add(AnalysisViewModel.WARN_RFM_PERIOD_UNAVAILABLE);
        }
        String ruleVersion = ruleVersions.isEmpty() ? null : ruleVersions.first();
        if (ruleVersions.size() > 1) {
            warnings.add(AnalysisViewModel.WARN_MULTIPLE_RULE_VERSIONS);
        }
        return new RfmProfile(snapshotId, ruleVersion, total, segments, matrix, lifecycle, preference,
                periodStart, periodEnd, warnings);
    }

    /** 八类规范顺序优先，未知分组排在八类之后（按名称升序） */
    private static List<String> orderedGroups(java.util.Set<String> groups) {
        List<String> ordered = new ArrayList<>(groups);
        ordered.sort(Comparator
                .comparingInt((String g) -> AdsRows.orderIndex(VALUE_GROUPS, g))
                .thenComparing(Comparator.naturalOrder()));
        return ordered;
    }

    private static BigDecimal share(long users, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(users).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    /**
     * 最近购买间隔（天）= 基准日 - 最近购买日。
     *
     * <p><b>S3-16 起这是降级路径</b>：R 原值的所有者是 ADS 列 {@code r_days}
     * （{@code DATEDIFF(period_end, last_buy_date)}，Spark 侧写入）。只有该列缺失/不可解析时
     * 才退回本方法，并在 {@code warnings} 里挂 {@link AnalysisViewModel#WARN_RFM_RAW_VALUES_UNAVAILABLE}
     * —— 两者**基准日不同**（{@code calc_date} vs {@code period_end}），静默切换等于换口径。</p>
     *
     * <p>基准日优先 calc_date（Spark 写画像时的计算日），缺失时退回分区列 dt；
     * 两列任一缺失或不可解析 → null（不臆造 R 值）。晚于基准日按 0 处理，不出现负天数。</p>
     */
    private static Long recencyDays(Map<String, Object> row) {
        LocalDate base = parseDate(AdsRows.asString(row.get("calc_date")));
        if (base == null) {
            base = parseDate(AdsRows.asString(row.get("dt")));
        }
        LocalDate lastBuy = parseDate(AdsRows.asString(row.get("last_buy_date")));
        if (base == null || lastBuy == null) {
            return null;
        }
        return Math.max(0L, ChronoUnit.DAYS.between(lastBuy, base));
    }

    static LocalDate parseDate(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return value.length() == 8 ? LocalDate.parse(value, COMPACT_DATE) : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 分组累加器：人数 + 有 R 值的用户数与天数合计（缺 R 值的用户不进平均值分母）+ 原值合计 */
    private static final class GroupAccum {
        long users = 0;
        long recencySum = 0;
        long recencyCount = 0;
        /** M 原值合计；null = 该组没有任何可读 m_amount（不用 0 冒充） */
        private BigDecimal amountSum;
        /** F 原值合计；hasOrders=false 时对外为 null */
        private long ordersSum = 0;
        private boolean hasOrders = false;

        void addRecency(Long days) {
            if (days != null) {
                recencySum += days;
                recencyCount++;
            }
        }

        void addAmount(BigDecimal amount) {
            amountSum = amountSum == null ? amount : amountSum.add(amount);
        }

        void addOrders(long orders) {
            ordersSum += orders;
            hasOrders = true;
        }

        /** M 原值合计（金额保留 2 位）；该组无可读原值 → null */
        BigDecimal amount() {
            return amountSum == null ? null : amountSum.setScale(2, RoundingMode.HALF_UP);
        }

        /** F 原值合计；该组无可读原值 → null */
        Long orders() {
            return hasOrders ? ordersSum : null;
        }

        BigDecimal avgRecencyDays() {
            if (recencyCount == 0) {
                return null;
            }
            return BigDecimal.valueOf(recencySum)
                    .divide(BigDecimal.valueOf(recencyCount), 1, RoundingMode.HALF_UP);
        }
    }
}
