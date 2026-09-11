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
     * <p>{@code amount} 恒为 null 并伴随 {@link AnalysisViewModel#WARN_RFM_AMOUNT_UNAVAILABLE}：
     * ads_user_profile_m 只有 m **分**（1..5）没有消费额列，用 m 分求和冒充金额属于造数。</p>
     *
     * @param avgRecencyDays 组内平均最近购买间隔（天）；列缺失/不可解析时为 null
     */
    public record RfmSegment(String valueGroup, long users, BigDecimal share, BigDecimal amount,
                             BigDecimal avgRecencyDays) {
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
     */
    public record RfmProfile(String snapshotId, String ruleVersion, long userCount,
                             List<RfmSegment> segments, List<RfmSegment> matrix,
                             List<LifecycleState> lifecycle, List<CategoryPreference> preference,
                             List<String> warnings) {
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
            return new RfmProfile(snapshotId, null, 0L, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        long total = rows.size();

        Map<String, GroupAccum> byGroup = new LinkedHashMap<>();
        Map<String, Long> byLifecycle = new TreeMap<>();
        Map<Long, Long> byCategory = new TreeMap<>();
        TreeSet<String> ruleVersions = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            GroupAccum acc = byGroup.computeIfAbsent(AdsRows.asString(row.get("value_group")), g -> new GroupAccum());
            acc.users++;
            acc.addRecency(recencyDays(row));
            byLifecycle.merge(AdsRows.asString(row.get("lifecycle_state")), 1L, Long::sum);
            byCategory.merge(AdsRows.asLong(row.get("favorite_category")), 1L, Long::sum);
            String ruleVersion = AdsRows.asString(row.get("rule_version"));
            if (!ruleVersion.isEmpty()) {
                ruleVersions.add(ruleVersion);
            }
        }

        List<RfmSegment> segments = new ArrayList<>();
        for (String group : orderedGroups(byGroup.keySet())) {
            GroupAccum acc = byGroup.get(group);
            // amount 恒 null：指标库没有消费额列，见 RfmSegment javadoc
            segments.add(new RfmSegment(group, acc.users, share(acc.users, total), null, acc.avgRecencyDays()));
        }

        List<RfmSegment> matrix = new ArrayList<>();
        for (String group : VALUE_GROUPS) {
            GroupAccum acc = byGroup.get(group);
            matrix.add(acc == null
                    ? new RfmSegment(group, 0L, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP), null, null)
                    : new RfmSegment(group, acc.users, share(acc.users, total), null, acc.avgRecencyDays()));
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

        List<String> warnings = new ArrayList<>();
        warnings.add(AnalysisViewModel.WARN_RFM_AMOUNT_UNAVAILABLE);
        String ruleVersion = ruleVersions.isEmpty() ? null : ruleVersions.first();
        if (ruleVersions.size() > 1) {
            warnings.add(AnalysisViewModel.WARN_MULTIPLE_RULE_VERSIONS);
        }
        return new RfmProfile(snapshotId, ruleVersion, total, segments, matrix, lifecycle, preference, warnings);
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

    /** 分组累加器：人数 + 有 R 值的用户数与天数合计（缺 R 值的用户不进平均值分母） */
    private static final class GroupAccum {
        long users = 0;
        long recencySum = 0;
        long recencyCount = 0;

        void addRecency(Long days) {
            if (days != null) {
                recencySum += days;
                recencyCount++;
            }
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
