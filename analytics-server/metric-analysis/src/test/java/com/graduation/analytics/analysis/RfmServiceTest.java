package com.graduation.analytics.analysis;

import com.graduation.analytics.metric.MetricAdsReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R7-4 RFM 聚合 L0 单测（不连库）。
 *
 * <p>覆盖点：八类矩阵补齐与排序、占比、rule_version 回显、多版本警告、只取最新 dt 分区、
 * 金额列缺失时的显式降级（不用 m 分冒充消费额）。</p>
 */
class RfmServiceTest {

    private static final String SID = "S20260901_24";

    private MetricAdsReader adsReader;
    private RfmService rfmService;

    @BeforeEach
    void setUp() {
        adsReader = mock(MetricAdsReader.class);
        rfmService = new RfmService(adsReader);
    }

    @Test
    @DisplayName("聚合三类分层：八类矩阵补齐 0 人数，规范顺序 + 占比，金额缺失显式降级")
    void aggregatesEightValueGroups() {
        stub(List.of(
                profileRow("20260901", "一般发展", 11L, "活跃", "rfm-v1", "20260901", "2026-09-01"),
                profileRow("20260901", "一般发展", 21L, "活跃", "rfm-v1", "20260901", "2026-09-01"),
                profileRow("20260901", "一般挽留", 11L, "活跃", "rfm-v1", "20260901", "2026-09-01")));

        RfmService.RfmProfile profile = rfmService.profile(SID);

        assertThat(profile.snapshotId()).isEqualTo(SID);
        assertThat(profile.userCount()).isEqualTo(3L);
        assertThat(profile.ruleVersion()).isEqualTo("rfm-v1");
        assertThat(profile.segments()).extracting(RfmService.RfmSegment::valueGroup)
                .containsExactly("一般发展", "一般挽留");
        assertThat(profile.segments().get(0).users()).isEqualTo(2L);
        assertThat(profile.segments().get(0).share()).isEqualByComparingTo("0.6667");
        assertThat(profile.segments().get(1).share()).isEqualByComparingTo("0.3333");
        // avgRecencyDays = calc_date(20260901) - last_buy_date(2026-09-01) = 0.0
        assertThat(profile.segments().get(0).avgRecencyDays()).isEqualByComparingTo("0.0");
        // 指标库旧快照没有原值列（无 m_amount/r_days/f_count/period_*）：一律 null + 三条降级警告，
        // 不用 m 分求和冒充金额（设计 V3.0 §11.4 L449「M 不明时保留 null+警告」）
        assertThat(profile.segments()).allSatisfy(segment -> {
            assertThat(segment.amount()).isNull();
            assertThat(segment.orders()).isNull();
        });
        assertThat(profile.periodStart()).isNull();
        assertThat(profile.periodEnd()).isNull();
        assertThat(profile.warnings()).containsExactly(AnalysisViewModel.WARN_RFM_AMOUNT_UNAVAILABLE,
                AnalysisViewModel.WARN_RFM_RAW_VALUES_UNAVAILABLE,
                AnalysisViewModel.WARN_RFM_PERIOD_UNAVAILABLE);

        assertThat(profile.matrix()).hasSize(8);
        assertThat(profile.matrix()).extracting(RfmService.RfmSegment::valueGroup)
                .containsExactlyElementsOf(RfmService.VALUE_GROUPS);
        assertThat(profile.matrix()).filteredOn(segment -> "一般发展".equals(segment.valueGroup()))
                .singleElement().satisfies(segment -> assertThat(segment.users()).isEqualTo(2L));
        assertThat(profile.matrix()).filteredOn(segment -> "重要价值".equals(segment.valueGroup()))
                .singleElement().satisfies(segment -> {
                    assertThat(segment.users()).isZero();
                    assertThat(segment.share()).isEqualByComparingTo("0.0000");
                });

        assertThat(profile.lifecycle()).extracting(RfmService.LifecycleState::state).containsExactly("活跃");
        assertThat(profile.lifecycle().get(0).users()).isEqualTo(3L);
        assertThat(profile.preference()).extracting(RfmService.CategoryPreference::categoryId)
                .containsExactly(11L, 21L);
    }

    @Test
    @DisplayName("同一快照多天分区：只聚合最新 dt，避免跨天重复计数")
    void onlyLatestPartitionIsAggregated() {
        stub(List.of(
                profileRow("20260831", "重要价值", 11L, "活跃", "rfm-v1", "20260831", "2026-08-30"),
                profileRow("20260901", "一般发展", 11L, "活跃", "rfm-v1", "20260901", "2026-09-01"),
                profileRow("20260901", "一般挽留", 21L, "流失风险", "rfm-v1", "20260901", "2026-08-01")));

        RfmService.RfmProfile profile = rfmService.profile(SID);

        assertThat(profile.userCount()).isEqualTo(2L);
        assertThat(profile.segments()).extracting(RfmService.RfmSegment::valueGroup)
                .containsExactly("一般发展", "一般挽留");
        assertThat(profile.lifecycle()).extracting(RfmService.LifecycleState::state)
                .containsExactly("活跃", "流失风险");
        // 20260901 - 2026-08-01 = 31 天
        assertThat(profile.segments().get(1).avgRecencyDays()).isEqualByComparingTo("31.0");
    }

    @Test
    @DisplayName("同一快照多个 rule_version：取字典序最小并给出警告，不静默挑一个")
    void multipleRuleVersionsAreReported() {
        stub(List.of(
                profileRow("20260901", "一般发展", 11L, "活跃", "rfm-v2", "20260901", "2026-09-01"),
                profileRow("20260901", "重要价值", 21L, "活跃", "rfm-v1", "20260901", "2026-09-01")));

        RfmService.RfmProfile profile = rfmService.profile(SID);

        assertThat(profile.ruleVersion()).isEqualTo("rfm-v1");
        assertThat(profile.warnings()).containsExactly(AnalysisViewModel.WARN_RFM_AMOUNT_UNAVAILABLE,
                AnalysisViewModel.WARN_RFM_RAW_VALUES_UNAVAILABLE,
                AnalysisViewModel.WARN_RFM_PERIOD_UNAVAILABLE,
                AnalysisViewModel.WARN_MULTIPLE_RULE_VERSIONS);
    }

    @Test
    @DisplayName("空快照：返回空聚合而不是抛异常或造数")
    void emptySnapshotReturnsEmptyProfile() {
        stub(List.of());

        RfmService.RfmProfile profile = rfmService.profile(SID);

        assertThat(profile.userCount()).isZero();
        assertThat(profile.segments()).isEmpty();
        assertThat(profile.matrix()).isEmpty();
        assertThat(profile.lifecycle()).isEmpty();
        assertThat(profile.preference()).isEmpty();
        assertThat(profile.ruleVersion()).isNull();
        assertThat(profile.warnings()).isEmpty();
    }

    @Test
    @DisplayName("取值缺失/格式异常：不猜 R 值（avgRecencyDays=null），也不把 NULL 分组写成字符串 null")
    void missingOrMalformedValuesDegradeToNull() {
        stub(List.of(
                profileRow("20260901", null, null, null, null, null, "看不懂的日期"),
                profileRow("20260901", "一般发展", 11L, "活跃", "rfm-v1", "20260901", "2026-09-01")));

        RfmService.RfmProfile profile = rfmService.profile(SID);

        assertThat(profile.userCount()).isEqualTo(2L);
        assertThat(profile.ruleVersion()).isEqualTo("rfm-v1");
        // NULL 分组聚合为空字符串分组（可见），绝不变成字面量 "null"
        assertThat(profile.segments()).extracting(RfmService.RfmSegment::valueGroup)
                .containsExactly("一般发展", "");
        assertThat(profile.segments()).filteredOn(segment -> segment.valueGroup().isEmpty())
                .singleElement().satisfies(segment -> {
                    assertThat(segment.users()).isEqualTo(1L);
                    assertThat(segment.avgRecencyDays()).isNull();
                });
        assertThat(profile.preference()).extracting(RfmService.CategoryPreference::categoryId).containsExactly(0L, 11L);
    }

    @Test
    @DisplayName("S3-16 原值列存在：M 取 m_amount 合计、R 优先 r_days 原值，且不再挂金额缺失警告")
    void rawValuesAreConsumedWhenColumnsExist() {
        stub(List.of(
                rawProfileRow("20260901", "一般发展", 11L, "活跃", "rfm-v1", "20260901", "2026-09-01",
                        "100.50", 3L, 7, "2026-08-02", "2026-09-01"),
                rawProfileRow("20260901", "一般发展", 21L, "活跃", "rfm-v1", "20260901", "2026-09-01",
                        "50.00", 1L, 9, "2026-08-02", "2026-09-01"),
                rawProfileRow("20260901", "一般挽留", 11L, "流失风险", "rfm-v1", "20260901", "2026-09-01",
                        "0.00", 0L, 31, "2026-08-02", "2026-09-01")));

        RfmService.RfmProfile profile = rfmService.profile(SID);

        // M 原值合计 = 100.50 + 50.00（不是 m 分求和）
        assertThat(profile.segments().get(0).amount()).isEqualByComparingTo("150.50");
        // F 原值合计 = 3 + 1
        assertThat(profile.segments().get(0).orders()).isEqualTo(4L);
        // R 原值 r_days=(7+9)/2=8.0；若走旧口径 calc_date − last_buy_date 则两行都是 0 天
        assertThat(profile.segments().get(0).avgRecencyDays()).isEqualByComparingTo("8.0");
        assertThat(profile.segments().get(1).amount()).isEqualByComparingTo("0.00");
        assertThat(profile.segments().get(1).orders()).isZero();
        // 观察窗口随响应返回（本次评分实际使用的窗口）
        assertThat(profile.periodStart()).isEqualTo("2026-08-02");
        assertThat(profile.periodEnd()).isEqualTo("2026-09-01");
        // 补 0 的空分层没有原值可加：保持 null，不写 0
        assertThat(profile.matrix()).filteredOn(segment -> "重要价值".equals(segment.valueGroup()))
                .singleElement().satisfies(segment -> {
                    assertThat(segment.amount()).isNull();
                    assertThat(segment.orders()).isNull();
                });
        // 原值齐备 + 窗口唯一 + 单一口径版本 ⇒ 不应有任何降级警告
        assertThat(profile.warnings()).isEmpty();
    }

    @Test
    @DisplayName("S3-16 观察窗口行间不一致：窗口留 null + RFM_PERIOD_UNAVAILABLE，不猜")
    void ambiguousWindowIsReportedInsteadOfGuessed() {
        stub(List.of(
                rawProfileRow("20260901", "一般发展", 11L, "活跃", "rfm-v1", "20260901", "2026-09-01",
                        "10.00", 1L, 1, "2026-08-02", "2026-09-01"),
                rawProfileRow("20260901", "一般挽留", 21L, "活跃", "rfm-v1", "20260901", "2026-09-01",
                        "20.00", 2L, 2, "2026-07-01", "2026-08-01")));

        RfmService.RfmProfile profile = rfmService.profile(SID);

        assertThat(profile.periodStart()).isNull();
        assertThat(profile.periodEnd()).isNull();
        assertThat(profile.warnings()).containsExactly(AnalysisViewModel.WARN_RFM_PERIOD_UNAVAILABLE);
    }

    @Test
    @DisplayName("S3-16 M 原值列缺失：金额仍 null + 金额警告，但 R/F 原值齐备时不报原值警告")
    void missingAmountColumnKeepsOnlyAmountWarning() {
        stub(List.of(
                rawProfileRow("20260901", "一般发展", 11L, "活跃", "rfm-v1", "20260901", "2026-09-01",
                        null, 2L, 4, "2026-08-02", "2026-09-01")));

        RfmService.RfmProfile profile = rfmService.profile(SID);

        assertThat(profile.segments().get(0).amount()).isNull();
        assertThat(profile.segments().get(0).orders()).isEqualTo(2L);
        assertThat(profile.segments().get(0).avgRecencyDays()).isEqualByComparingTo("4.0");
        assertThat(profile.warnings()).containsExactly(AnalysisViewModel.WARN_RFM_AMOUNT_UNAVAILABLE);
    }

    @Test
    @DisplayName("S3-16 r_days 逐行缺失：缺失行退回旧口径回算，并显式声明原值不可用")
    void missingRecencyRawValueFallsBackPerRow() {
        stub(List.of(
                rawProfileRow("20260901", "一般发展", 11L, "活跃", "rfm-v1", "20260901", "2026-09-01",
                        "10.00", 1L, 10, "2026-08-02", "2026-09-01"),
                legacyRecencyRow("20260901", "一般发展", 21L, "活跃", "rfm-v1", "20260901", "2026-08-27",
                        "10.00", 1L, "2026-08-02", "2026-09-01")));

        RfmService.RfmProfile profile = rfmService.profile(SID);

        // (r_days=10 + 旧口径 calc_date(20260901) − last_buy(2026-08-27)=5) / 2 = 7.5
        assertThat(profile.segments().get(0).avgRecencyDays()).isEqualByComparingTo("7.5");
        assertThat(profile.warnings()).containsExactly(AnalysisViewModel.WARN_RFM_RAW_VALUES_UNAVAILABLE);
    }

    private void stub(List<Map<String, Object>> rows) {
        when(adsReader.selectBySnapshot(anyString(), anyString(), any())).thenReturn(rows);
    }

    /** S3-16：带 RFM 原值列（m_amount/f_count/r_days）与观察窗口（period_start/period_end）的画像行 */
    private static Map<String, Object> rawProfileRow(String dt, String valueGroup, Long favoriteCategory,
                                                     String lifecycleState, String ruleVersion, String calcDate,
                                                     String lastBuyDate, String mAmount, Long fCount, Integer rDays,
                                                     String periodStart, String periodEnd) {
        Map<String, Object> row = profileRow(dt, valueGroup, favoriteCategory, lifecycleState, ruleVersion,
                calcDate, lastBuyDate);
        row.put("r_days", rDays);
        row.put("f_count", fCount);
        row.put("m_amount", mAmount == null ? null : new java.math.BigDecimal(mAmount));
        row.put("period_start", periodStart);
        row.put("period_end", periodEnd);
        return row;
    }

    /** S3-16：只有 R 原值（r_days）缺失的画像行，用于验证「逐行退回旧口径回算」 */
    private static Map<String, Object> legacyRecencyRow(String dt, String valueGroup, Long favoriteCategory,
                                                       String lifecycleState, String ruleVersion, String calcDate,
                                                       String lastBuyDate, String mAmount, Long fCount,
                                                       String periodStart, String periodEnd) {
        Map<String, Object> row = rawProfileRow(dt, valueGroup, favoriteCategory, lifecycleState, ruleVersion,
                calcDate, lastBuyDate, mAmount, fCount, null, periodStart, periodEnd);
        row.remove("r_days");
        return row;
    }

    private static Map<String, Object> profileRow(String dt, String valueGroup, Long favoriteCategory,
                                                 String lifecycleState, String ruleVersion, String calcDate,
                                                 String lastBuyDate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dt", dt);
        row.put("value_group", valueGroup);
        row.put("favorite_category", favoriteCategory);
        row.put("lifecycle_state", lifecycleState);
        row.put("rule_version", ruleVersion);
        row.put("calc_date", calcDate);
        row.put("last_buy_date", lastBuyDate);
        return row;
    }
}
