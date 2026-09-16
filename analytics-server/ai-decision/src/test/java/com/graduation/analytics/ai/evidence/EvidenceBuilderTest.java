package com.graduation.analytics.ai.evidence;

import com.graduation.analytics.analysis.AnalysisService;
import com.graduation.analytics.analysis.AnalysisViewModel;
import com.graduation.analytics.metric.MySqlMetricStore;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.warehouse.RunSourceIdentity;
import com.graduation.analytics.warehouse.WarehouseNamespace;
import com.graduation.analytics.warehouse.WarehouseNamespaceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R8-1 证据包构建测试（§19.2/§19.3、§24.7）。
 *
 * <p>只测「装配与诚实边界」，不测 metric-analysis 的取数（那是它自己的测试）：
 * 事实是否带证据引用、上期对比是否真的可比、缺口是否如实登记、无快照是否留空。</p>
 */
@ExtendWith(MockitoExtension.class)
class EvidenceBuilderTest {

    private static final String SNAP = "S20260901_24";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    @Mock
    private AnalysisService analysisService;
    @Mock
    private MySqlMetricStore metricStore;

    private EvidenceBuilder builder() {
        return new EvidenceBuilder(analysisService, metricStore, defaultNamespaceProvider());
    }

    /**
     * P2-07 起 {@link WarehouseNamespaceProvider} 不再是单抽象方法接口（新增
     * {@code runSource}/{@code currentRunSource} 两个读取点），原先的方法引用
     * {@code WarehouseNamespace::defaultNamespace} 已不合法（F-46 编译回归）。
     *
     * <p>此处显式实现两个读取点，语义与旧写法**完全一致**：无论按哪个源解析，都得到
     * {@link WarehouseNamespace#defaultNamespace()}。之所以只要求这一点，是因为本测试的对象是
     * 「证据装配与诚实边界」，{@code EvidenceBuilder} 对 provider 的唯一调用点是
     * {@code namespaceProvider.current()}（库名视图），源级解析的真实行为由
     * {@code ActiveProfileWarehouseNamespaceProviderTest} 与
     * {@code SparkStageExecutorFactoryTest} 覆盖。</p>
     */
    private static WarehouseNamespaceProvider defaultNamespaceProvider() {
        RunSourceIdentity identity =
                new RunSourceIdentity("mock-mall", WarehouseNamespace.defaultNamespace());
        return new WarehouseNamespaceProvider() {
            @Override
            public RunSourceIdentity runSource(Long sourceId) {
                return identity;
            }

            @Override
            public RunSourceIdentity currentRunSource() {
                return identity;
            }
        };
    }

    // ── 夹具 ───────────────────────────────────────────────────────────────

    private MetricSnapshot snapshot(String id, String status, LocalDate date, Long pipelineRunId) {
        MetricSnapshot s = new MetricSnapshot();
        s.setSnapshotId(id);
        s.setStatus(status);
        s.setBusinessTime(date.atStartOfDay());
        s.setDefinitionVersion("v2");
        s.setPipelineRunId(pipelineRunId);
        return s;
    }

    private List<AnalysisService.MetricItem> metrics(String gmv) {
        return List.of(
                new AnalysisService.MetricItem("gmv", "销售额(GMV)", new BigDecimal(gmv), "元",
                        "day:2026-09-01", "v1"),
                new AnalysisService.MetricItem("refund_rate", "退款率", new BigDecimal("0.6000"), "",
                        "day:2026-09-01", "v2"),
                new AnalysisService.MetricItem("pv", "浏览量", new BigDecimal("7.0000"), "次",
                        "day:2026-09-01", "v1"));
    }

    private AnalysisViewModel<AnalysisService.OverviewData> overview(String gmv) {
        AnalysisService.OverviewData data = new AnalysisService.OverviewData(metrics(gmv), List.of(), List.of(),
                new AnalysisService.QualitySummary(4, 3, List.of("EVENT_ID_UNIQUE")), List.of());
        return AnalysisViewModel.of(SNAP, "spark-ads", "2026-09-01T00:00:00", "2026-09-01T00:10:00", "v2", "PASS",
                Map.of("snapshotId", SNAP), data, List.of());
    }

    private void stubProducts() {
        AnalysisService.ProductsData products = new AnalysisService.ProductsData(
                List.of(new AnalysisService.HotProduct(3L, "保温杯", new BigDecimal("11.0904"), 1, 1, 1, 3, 1),
                        new AnalysisService.HotProduct(2L, "机械键盘", new BigDecimal("10.3972"), 3, 0, 1, 3, 2)),
                List.of(), 10);
        when(analysisService.products(eq(SNAP), anyInt(), eq(DAY), eq(DAY)))
                .thenReturn(AnalysisViewModel.of(SNAP, "spark-ads", "2026-09-01T00:00:00", "x", "v2", "PASS",
                        Map.of(), products, List.of()));
    }

    private void stubSales() {
        AnalysisService.SalesData sales = new AnalysisService.SalesData(List.of(), null, null, null, null,
                new AnalysisService.QualitySummary(4, 3, List.of("EVENT_ID_UNIQUE")), List.of(), List.of());
        when(analysisService.sales(eq(SNAP), eq(DAY), eq(DAY)))
                .thenReturn(AnalysisViewModel.of(SNAP, "spark-ads", "2026-09-01T00:00:00", "x", "v2", "PASS",
                        Map.of(), sales, List.of(AnalysisViewModel.WARN_UNKNOWN_DIMENSION_TABLE)));
    }

    // ── 1. 字段齐全 + 证据引用 ─────────────────────────────────────────────

    @Test
    @DisplayName("证据包：事实带证据引用、维度有占比、质量与血缘齐全（§19.2）")
    void buildsCompletePackage() {
        when(metricStore.activeSnapshotId()).thenReturn(SNAP);
        when(metricStore.findSnapshot(SNAP)).thenReturn(snapshot(SNAP, "ACTIVE", DAY, 21L));
        when(metricStore.listSnapshots(anyInt())).thenReturn(List.of(snapshot(SNAP, "ACTIVE", DAY, 21L)));
        when(analysisService.overview(eq(SNAP), eq(DAY), eq(DAY))).thenReturn(overview("2042.0000"));
        stubProducts();
        stubSales();

        EvidencePackage pkg = builder().build(EvidenceRequest.latest("admin"));

        assertThat(pkg.evidenceId()).startsWith("EV-");
        assertThat(pkg.templateVersion()).isEqualTo(EvidencePackage.TEMPLATE_VERSION);
        assertThat(pkg.snapshotId()).isEqualTo(SNAP);
        assertThat(pkg.definitionVersion()).isEqualTo("v2");
        assertThat(pkg.currentPeriod()).isEqualTo(new EvidencePackage.Period("2026-09-01", "2026-09-01"));

        assertThat(pkg.facts()).hasSize(3);
        assertThat(pkg.facts()).allSatisfy(f ->
                assertThat(f.evidenceRef()).contains("@").contains(SNAP));
        assertThat(pkg.facts()).extracting(EvidencePackage.Fact::metricCode)
                .containsExactly("gmv", "refund_rate", "pv");

        // 无上期快照 → 不做对比，且如实登记缺口（不用 0 冒充基线）
        assertThat(pkg.comparisonPeriod()).isNull();
        assertThat(pkg.comparisons()).isEmpty();
        assertThat(pkg.warnings()).contains(EvidencePackage.WARN_NO_COMPARISON_PERIOD);

        // 商品维度：贡献值 + 占比；分类/地区/渠道无来源 → 空数组 + 警告
        List<EvidencePackage.DimensionContribution> product = pkg.dimensions().get("product");
        assertThat(product).hasSize(2);
        assertThat(product.get(0).label()).isEqualTo("保温杯");
        assertThat(product.get(0).metricCode()).isEqualTo("product_heat");
        assertThat(product.get(0).evidenceRef()).isEqualTo("ads_hot_product_m.heat_score@" + SNAP);
        assertThat(new BigDecimal(product.get(0).share()))
                .isEqualByComparingTo(BigDecimal.valueOf(11.0904 / 21.4876).setScale(4, java.math.RoundingMode.HALF_UP));
        assertThat(pkg.dimensions().get("category")).isEmpty();
        assertThat(pkg.dimensions().get("channel")).isEmpty();
        assertThat(pkg.warnings()).contains(EvidencePackage.WARN_UNKNOWN_DIMENSION_TABLE);

        // 质量与血缘
        assertThat(pkg.dataQuality().gateStatus()).isEqualTo("PASS");
        assertThat(pkg.dataQuality().ruleTotal()).isEqualTo(4);
        assertThat(pkg.dataQuality().rulePassed()).isEqualTo(3);
        assertThat(pkg.dataQuality().failedRules()).containsExactly("EVENT_ID_UNIQUE");
        assertThat(pkg.lineage().pipelineRunId()).isEqualTo(21L);
        assertThat(pkg.lineage().mysqlTables())
                .contains("ads_operation_overview_m", "ads_data_quality_m");
        assertThat(pkg.lineage().hiveAdsTables()).contains("dw_ads.ads_operation_overview");

        // 候选异常：退款率 0.6 > 0.3（MEDIUM）+ 质量规则未通过；文案必须是非因果表述
        assertThat(pkg.anomalies()).extracting(EvidencePackage.AnomalyCandidate::ruleCode)
                .contains(AnomalyRules.RULE_REFUND_RATE_HIGH, AnomalyRules.RULE_QUALITY_RULE_FAILED);
        assertThat(pkg.anomalies()).allSatisfy(a ->
                assertThat(a.statement()).contains("可能相关，不构成因果"));
    }

    // ── 2. 无 ACTIVE 快照 ──────────────────────────────────────────────────

    @Test
    @DisplayName("无 ACTIVE 快照：返回空证据包 + 警告，绝不回退归档或落地区（§17.1）")
    void emptyWhenNoActiveSnapshot() {
        when(metricStore.activeSnapshotId()).thenReturn(null);

        EvidencePackage pkg = builder().build(EvidenceRequest.latest("admin"));

        assertThat(pkg.snapshotId()).isNull();
        assertThat(pkg.facts()).isEmpty();
        assertThat(pkg.comparisons()).isEmpty();
        assertThat(pkg.anomalies()).isEmpty();
        assertThat(pkg.dataQuality().gateStatus()).isEqualTo(EvidencePackage.DataQuality.GATE_UNKNOWN);
        assertThat(pkg.dimensions().keySet())
                .containsExactlyElementsOf(EvidencePackage.DIMENSION_KEYS);
        assertThat(pkg.warnings()).containsExactly(EvidencePackage.WARN_NO_ACTIVE_SNAPSHOT);
        verify(analysisService, never()).overview(any(), any(), any());
    }

    @Test
    @DisplayName("显式指定不存在的快照：UNKNOWN_SNAPSHOT 警告，不假装读过它")
    void unknownExplicitSnapshot() {
        when(metricStore.findSnapshot("S-NOT-EXIST")).thenReturn(null);

        EvidencePackage pkg = builder().build(new EvidenceRequest("S-NOT-EXIST", null, null, "admin"));

        assertThat(pkg.snapshotId()).isNull();
        assertThat(pkg.warnings()).containsExactly(EvidencePackage.WARN_UNKNOWN_SNAPSHOT);
    }

    // ── 3. 上期对比 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("上期快照存在：给出环比，基线引用指向基线快照；环比超 30% 记候选异常")
    void comparesWithPreviousDaySnapshot() {
        String prev = "S20260831_20";
        when(metricStore.activeSnapshotId()).thenReturn(SNAP);
        when(metricStore.findSnapshot(SNAP)).thenReturn(snapshot(SNAP, "ACTIVE", DAY, 21L));
        when(metricStore.listSnapshots(anyInt())).thenReturn(List.of(
                snapshot(SNAP, "ACTIVE", DAY, 21L),
                snapshot(prev, "ARCHIVED", DAY.minusDays(1), 19L)));
        when(analysisService.overview(eq(SNAP), eq(DAY), eq(DAY))).thenReturn(overview("2042.0000"));
        when(analysisService.overview(eq(prev), eq(DAY.minusDays(1)), eq(DAY.minusDays(1))))
                .thenReturn(overview("1000.0000"));
        stubProducts();
        stubSales();

        EvidencePackage pkg = builder().build(EvidenceRequest.latest("admin"));

        assertThat(pkg.comparisonPeriod()).isEqualTo(new EvidencePackage.Period("2026-08-31", "2026-08-31"));
        assertThat(pkg.warnings()).doesNotContain(EvidencePackage.WARN_NO_COMPARISON_PERIOD);

        EvidencePackage.Comparison gmv = pkg.comparisons().stream()
                .filter(c -> "gmv".equals(c.metricCode())).findFirst().orElseThrow();
        assertThat(gmv.baseline()).isEqualTo("1000.0000");
        assertThat(gmv.delta()).isEqualTo("1042.0000");
        assertThat(gmv.deltaRate()).isEqualTo("1.0420");
        assertThat(gmv.baselineRef()).isEqualTo("metric_value.gmv@" + prev);

        assertThat(pkg.anomalies()).extracting(EvidencePackage.AnomalyCandidate::ruleCode)
                .contains(AnomalyRules.RULE_PERIOD_SHIFT);
    }

    @Test
    @DisplayName("多日窗口：metric_value 只有单日聚合 → 显式登记不支持，不估算，当期退回快照业务日")
    void multiDayWindowHasNoComparison() {
        when(metricStore.activeSnapshotId()).thenReturn(SNAP);
        when(metricStore.findSnapshot(SNAP)).thenReturn(snapshot(SNAP, "ACTIVE", DAY, 21L));
        when(analysisService.overview(eq(SNAP), eq(DAY), eq(DAY))).thenReturn(overview("2042.0000"));
        stubProducts();
        stubSales();

        EvidencePackage pkg = builder().build(
                new EvidenceRequest(null, null, "2026-09-01~2026-09-03", "admin"));

        // period = 用户请求的区间（如实回显），currentPeriod = 实际取数的快照业务日
        assertThat(pkg.period()).isEqualTo(new EvidencePackage.Period("2026-09-01", "2026-09-03"));
        assertThat(pkg.currentPeriod()).isEqualTo(new EvidencePackage.Period("2026-09-01", "2026-09-01"));
        assertThat(pkg.comparisons()).isEmpty();
        assertThat(pkg.warnings()).contains(EvidencePackage.WARN_COMPARISON_WINDOW_UNSUPPORTED,
                EvidencePackage.WARN_REQUESTED_PERIOD_NOT_SNAPSHOT_DATE,
                EvidencePackage.WARN_NO_COMPARISON_PERIOD);
        // 不支持的是"多日窗口对比"：上期快照确实去找了（找的是快照业务日的前一天），没找到才留空
        verify(metricStore).listSnapshots(anyInt());
    }

    // ── 4. 降级 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADS 读取失败：维度降级为空 + 警告，事实仍可用（不整体失败）")
    void degradesWhenAdsReadFails() {
        when(metricStore.activeSnapshotId()).thenReturn(SNAP);
        when(metricStore.findSnapshot(SNAP)).thenReturn(snapshot(SNAP, "ACTIVE", DAY, 21L));
        when(metricStore.listSnapshots(anyInt())).thenReturn(List.of(snapshot(SNAP, "ACTIVE", DAY, 21L)));
        when(analysisService.overview(eq(SNAP), eq(DAY), eq(DAY))).thenReturn(overview("2042.0000"));
        when(analysisService.products(eq(SNAP), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("metricReadDataSource 未配置"));
        stubSales();

        EvidencePackage pkg = builder().build(EvidenceRequest.latest("admin"));

        assertThat(pkg.facts()).hasSize(3);
        assertThat(pkg.dimensions().get("product")).isEmpty();
        assertThat(pkg.warnings()).contains(EvidencePackage.WARN_ADS_READ_UNAVAILABLE);
    }

    @Test
    @DisplayName("模型不可用也能出完整结论：模板六段齐全，且不调模型")
    void templateSectionsAlwaysPresent() {
        when(metricStore.activeSnapshotId()).thenReturn(SNAP);
        when(metricStore.findSnapshot(SNAP)).thenReturn(snapshot(SNAP, "ACTIVE", DAY, 21L));
        when(metricStore.listSnapshots(anyInt())).thenReturn(List.of(snapshot(SNAP, "ACTIVE", DAY, 21L)));
        when(analysisService.overview(eq(SNAP), eq(DAY), eq(DAY))).thenReturn(overview("2042.0000"));
        stubProducts();
        stubSales();

        EvidenceTemplates.Narrative narrative =
                EvidenceTemplates.render(builder().build(EvidenceRequest.latest("admin")));

        assertThat(narrative.titles()).containsExactly(
                EvidenceTemplates.T_WHAT_HAPPENED, EvidenceTemplates.T_VS_PREVIOUS,
                EvidenceTemplates.T_DIMENSIONS, EvidenceTemplates.T_QUALITY,
                EvidenceTemplates.T_ACTIONS, EvidenceTemplates.T_LIMITS);
        assertThat(narrative.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        assertThat(narrative.summary()).contains(SNAP).contains("PASS");
        // 事实行必须带证据引用，可回溯到表@快照
        assertThat(narrative.sections().get(0).lines())
                .anySatisfy(line -> assertThat(line).contains("metric_value.gmv@" + SNAP));
        assertThat(narrative.limitations()).isNotEmpty();
        assertThat(narrative.toText()).contains("可能相关，不构成因果");
    }
}
