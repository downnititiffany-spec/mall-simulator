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
        // 指标库没有消费额列：一律 null + 警告，不用 m 分求和冒充金额
        assertThat(profile.segments()).allSatisfy(segment -> assertThat(segment.amount()).isNull());
        assertThat(profile.warnings()).containsExactly(AnalysisViewModel.WARN_RFM_AMOUNT_UNAVAILABLE);

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

    private void stub(List<Map<String, Object>> rows) {
        when(adsReader.selectBySnapshot(anyString(), anyString(), any())).thenReturn(rows);
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
