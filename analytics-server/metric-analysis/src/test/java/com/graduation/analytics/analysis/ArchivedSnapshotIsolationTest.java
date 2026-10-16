package com.graduation.analytics.analysis;

import com.graduation.analytics.metric.MetricAdsReader;
import com.graduation.analytics.metric.MetricQualityGate;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.MySqlMetricStore;
import com.graduation.analytics.metric.dict.MetricDefinitionMapper;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-F8b / F88-INV-06：ARCHIVED 历史快照只读隔离与不渗透性测试。
 *
 * <p>本测试规约要求：
 * <ol>
 *   <li>默认查询（未传 snapshotId 或为 null/空白）：严格只解析并返回当前 ACTIVE 快照数据；</li>
 *   <li>显式传 ARCHIVED 快照 ID：能够正常精确读取该历史归档快照数据；</li>
 *   <li>隔离与防渗透：ARCHIVED 快照的数据与 ACTIVE 快照的数据完全隔离，指标值、趋势数据互不污染渗透；</li>
 *   <li>在 MySqlMetricStore 层：默认 latestActive 绑定 ACTIVE 快照号，显式查询绑定 ARCHIVED 快照号，
 *       SQL 参数中隔离性得到严格保证。</li>
 * </ol>
 * 全部使用 Mock 替身，不连接真实数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
class ArchivedSnapshotIsolationTest {

    private static final String ACTIVE_SID = "S_20261016_ACTIVE";
    private static final String ARCHIVED_SID = "S_20261015_ARCHIVED";

    private static final BigDecimal ACTIVE_GMV = new BigDecimal("8888.00");
    private static final BigDecimal ARCHIVED_GMV = new BigDecimal("3333.00");

    @Mock
    private MetricAdsReader adsReader;

    @Mock
    private MetricStore metricStore;

    @Mock
    private MetricDefinitionMapper definitionMapper;

    @Mock
    private MetricQualityGate qualityGate;

    @Mock
    private RfmService rfmService;

    @Mock
    private JdbcTemplate readJdbc;

    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        analysisService = new AnalysisService(adsReader, metricStore, qualityGate, definitionMapper, rfmService);
        lenient().when(qualityGate.statusForRun(any())).thenReturn(MetricQualityGate.PASS);
    }

    @Test
    @DisplayName("默认查询（无快照入参）：只返回 ACTIVE 快照数据，绝不混入 ARCHIVED 历史数据")
    void defaultQueryReturnsOnlyActiveSnapshotDataWithoutArchivedPollution() {
        // 配置环境：存在一个 ACTIVE 快照和一个 ARCHIVED 快照
        stubActiveSnapshotMeta();

        // 默认查询（入参 snapshotId=null）
        AnalysisViewModel<AnalysisService.SalesData> model = analysisService.sales(null, null, null);

        // 1. 验证信封快照号为 ACTIVE_SID
        assertThat(model.snapshotId())
                .as("默认查询必须定位到 ACTIVE 快照")
                .isEqualTo(ACTIVE_SID);

        // 2. 验证指标值完全来自 ACTIVE 快照，绝无 ARCHIVED 渗透
        assertThat(model.data().gmv())
                .as("GMV 必须是 ACTIVE 快照的 8888.00，绝不能渗透 ARCHIVED 的 3333.00")
                .isEqualByComparingTo(ACTIVE_GMV);

        // 3. 验证调用链路：adsReader.activeSnapshotId() 被调用以解析当前 ACTIVE
        verify(adsReader).activeSnapshotId();

        // 4. 验证数据读取（ADS 与 MetricStore）全部严格以 ACTIVE_SID 发起，未以 ARCHIVED_SID 发起
        verify(metricStore).findSnapshot(ACTIVE_SID);
        verify(metricStore, never()).findSnapshot(ARCHIVED_SID);
    }

    @Test
    @DisplayName("显式传 ARCHIVED 快照 ID：精确读取该归档快照数据，且值不与 ACTIVE 快照渗透")
    void explicitArchivedSnapshotQueryReturnsArchivedDataWithoutActivePollution() {
        stubArchivedSnapshotMeta();

        // 显式查询归档快照
        AnalysisViewModel<AnalysisService.SalesData> model = analysisService.sales(ARCHIVED_SID, null, null);

        // 1. 验证信封快照号为 ARCHIVED_SID
        assertThat(model.snapshotId())
                .as("显式查询必须返回所请求的 ARCHIVED 快照")
                .isEqualTo(ARCHIVED_SID);
        assertThat(model.filters())
                .as("回显的 filters 中 snapshotId 必须是 ARCHIVED_SID")
                .containsEntry("snapshotId", ARCHIVED_SID);

        // 2. 验证指标值取自 ARCHIVED 快照，绝无 ACTIVE 快照数据渗透
        assertThat(model.data().gmv())
                .as("GMV 必须是 ARCHIVED 快照的 3333.00，绝不渗透 ACTIVE 的 8888.00")
                .isEqualByComparingTo(ARCHIVED_GMV);

        // 3. 关键断言：显式指定归档快照时，绝不调用 adsReader.activeSnapshotId()（不回退到当前 ACTIVE）
        verify(adsReader, never()).activeSnapshotId();

        // 4. 验证数据读取（ADS 与 MetricStore）全部严格以 ARCHIVED_SID 发起，不查询 ACTIVE_SID
        verify(metricStore).findSnapshot(ARCHIVED_SID);
        verify(metricStore, never()).findSnapshot(ACTIVE_SID);
    }

    @Test
    @DisplayName("两阶段独立对比：默认查询与显式归档查询在同一运行中完全正交隔离")
    void defaultAndExplicitQueriesRemainOrthogonalAndIsolated() {
        stubActiveSnapshotMeta();
        stubArchivedSnapshotMeta();

        // 第 1 步：先执行默认查询
        AnalysisViewModel<AnalysisService.SalesData> defaultModel = analysisService.sales(null, null, null);
        assertThat(defaultModel.snapshotId()).isEqualTo(ACTIVE_SID);
        assertThat(defaultModel.data().gmv()).isEqualByComparingTo(ACTIVE_GMV);

        // 第 2 步：再执行显式归档查询
        AnalysisViewModel<AnalysisService.SalesData> archivedModel = analysisService.sales(ARCHIVED_SID, null, null);
        assertThat(archivedModel.snapshotId()).isEqualTo(ARCHIVED_SID);
        assertThat(archivedModel.data().gmv()).isEqualByComparingTo(ARCHIVED_GMV);

        // 第 3 步：再次执行默认查询，确认无任何状态残留或缓存漂移
        AnalysisViewModel<AnalysisService.SalesData> defaultModel2 = analysisService.sales("", null, null);
        assertThat(defaultModel2.snapshotId()).isEqualTo(ACTIVE_SID);
        assertThat(defaultModel2.data().gmv()).isEqualByComparingTo(ACTIVE_GMV);
    }

    @Test
    @DisplayName("MySqlMetricStore 读侧：latestActive 走 ACTIVE 快照，显式传参走 ARCHIVED 快照")
    void mySqlMetricStoreQueryEnsuresSnapshotIsolation() {
        MySqlMetricStore store = new MySqlMetricStore(null, readJdbc);

        // 桩设：库内对 ACTIVE 快照与 ARCHIVED 快照分别返回不同的指标行
        MetricValue activeVal = new MetricValue();
        activeVal.setSnapshotId(ACTIVE_SID);
        activeVal.setMetricCode("gmv");
        activeVal.setMetricValue(ACTIVE_GMV);

        MetricValue archivedVal = new MetricValue();
        archivedVal.setSnapshotId(ARCHIVED_SID);
        archivedVal.setMetricCode("gmv");
        archivedVal.setMetricValue(ARCHIVED_GMV);

        when(readJdbc.query(anyString(),
                any(RowMapper.class), eq(ARCHIVED_SID),
                eq(MetricSnapshot.STATUS_ACTIVE), eq(MetricSnapshot.STATUS_ARCHIVED)))
                .thenReturn(List.of(archivedVal));

        // 显式指定 ARCHIVED 快照发起 MetricStore 查询
        MetricStore.MetricQuery archivedQuery = new MetricStore.MetricQuery(ARCHIVED_SID, false);
        List<MetricValue> results = store.query(archivedQuery);

        assertThat(results)
                .as("查询 ARCHIVED 快照结果必须只含 ARCHIVED 数据")
                .hasSize(1);
        assertThat(results.get(0).getSnapshotId()).isEqualTo(ARCHIVED_SID);
        assertThat(results.get(0).getMetricValue()).isEqualByComparingTo(ARCHIVED_GMV);

        // 验证 readJdbc 参数绑定：第一个占位符严格为 ARCHIVED_SID
        verify(readJdbc).query(anyString(), any(RowMapper.class),
                eq(ARCHIVED_SID), eq(MetricSnapshot.STATUS_ACTIVE), eq(MetricSnapshot.STATUS_ARCHIVED));
    }

    // ── 桩方法 ────────────────────────────────────────────────────────────────

    private void stubActiveSnapshotMeta() {
        when(adsReader.activeSnapshotId()).thenReturn(ACTIVE_SID);

        MetricSnapshot activeSnap = new MetricSnapshot();
        activeSnap.setSnapshotId(ACTIVE_SID);
        activeSnap.setStatus(MetricSnapshot.STATUS_ACTIVE);
        activeSnap.setSource("spark-ads");
        activeSnap.setSourceId(101L);
        activeSnap.setBusinessTime(LocalDateTime.of(2026, 10, 16, 0, 0, 0));
        activeSnap.setPublishedAt(LocalDateTime.of(2026, 10, 16, 1, 0, 0));
        activeSnap.setDefinitionVersion("v2");
        when(metricStore.findSnapshot(ACTIVE_SID)).thenReturn(activeSnap);

        MetricValue gmv = new MetricValue();
        gmv.setSnapshotId(ACTIVE_SID);
        gmv.setMetricCode("gmv");
        gmv.setMetricValue(ACTIVE_GMV);
        when(metricStore.query(new MetricStore.MetricQuery(ACTIVE_SID, false)))
                .thenReturn(List.of(gmv));

        when(adsReader.selectBySnapshot(anyString(), eq(ACTIVE_SID), any()))
                .thenReturn(List.of(Map.of("dt", "20261016", "sale_amount", ACTIVE_GMV)));
    }

    private void stubArchivedSnapshotMeta() {
        MetricSnapshot archivedSnap = new MetricSnapshot();
        archivedSnap.setSnapshotId(ARCHIVED_SID);
        archivedSnap.setStatus(MetricSnapshot.STATUS_ARCHIVED);
        archivedSnap.setSource("spark-ads");
        archivedSnap.setSourceId(101L);
        archivedSnap.setBusinessTime(LocalDateTime.of(2026, 10, 15, 0, 0, 0));
        archivedSnap.setPublishedAt(LocalDateTime.of(2026, 10, 15, 1, 0, 0));
        archivedSnap.setDefinitionVersion("v2");
        when(metricStore.findSnapshot(ARCHIVED_SID)).thenReturn(archivedSnap);

        MetricValue gmv = new MetricValue();
        gmv.setSnapshotId(ARCHIVED_SID);
        gmv.setMetricCode("gmv");
        gmv.setMetricValue(ARCHIVED_GMV);
        when(metricStore.query(new MetricStore.MetricQuery(ARCHIVED_SID, false)))
                .thenReturn(List.of(gmv));

        when(adsReader.selectBySnapshot(anyString(), eq(ARCHIVED_SID), any()))
                .thenReturn(List.of(Map.of("dt", "20261015", "sale_amount", ARCHIVED_GMV)));
    }
}
