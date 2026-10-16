package com.graduation.analytics.controller;

import com.graduation.analytics.metric.MySqlMetricStore;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsSourceOptionControllerTest {

    private SourceRegistryService sourceRegistryService;
    private MySqlMetricStore metricStore;
    private AnalyticsSourceOptionController controller;

    @BeforeEach
    void setUp() {
        sourceRegistryService = mock(SourceRegistryService.class);
        metricStore = mock(MySqlMetricStore.class);
        controller = new AnalyticsSourceOptionController(sourceRegistryService, metricStore);
    }

    @Test
    void exposesOnlySourcesWithPublishedSnapshotsAndOnlySafeDisplayFields() {
        when(metricStore.listSnapshots(100)).thenReturn(List.of(
                snapshot(11L, MetricSnapshot.STATUS_ACTIVE, 2L),
                snapshot(10L, MetricSnapshot.STATUS_ARCHIVED, 1L),
                snapshot(9L, MetricSnapshot.STATUS_FAILED, 3L),
                snapshot(8L, MetricSnapshot.STATUS_BUILDING, 4L),
                snapshot(7L, MetricSnapshot.STATUS_ARCHIVED, null)));
        when(sourceRegistryService.list()).thenReturn(List.of(
                source(1L, "mall-a", "商城甲", "profiles/mall-a.json"),
                source(2L, "mall-b", "商城乙", "profiles/mall-b.json"),
                source(3L, "mall-c", "商城丙", "profiles/mall-c.json"),
                source(5L, "no-snapshot", "暂无快照", "profiles/no-snapshot.json")));

        var response = controller.listSources();

        assertThat(response.data()).containsExactlyInAnyOrder(
                new AnalyticsSourceOptionController.AnalyticsSourceOption(1L, "mall-a", "商城甲"),
                new AnalyticsSourceOptionController.AnalyticsSourceOption(2L, "mall-b", "商城乙"));
        assertThat(response.data().toString()).doesNotContain("profiles/");
        verify(metricStore).listSnapshots(100);
        verify(sourceRegistryService).list();
    }

    private static MetricSnapshot snapshot(Long id, String status, Long sourceId) {
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setId(id);
        snapshot.setStatus(status);
        snapshot.setSourceId(sourceId);
        return snapshot;
    }

    private static SourceRegistryView source(Long id, String code, String name, String profilePath) {
        return new SourceRegistryView(id, code, name, "FILE", profilePath,
                "Asia/Shanghai", "CNY", "ACTIVE", "v1", false, null, null, "mall_" + id);
    }
}
