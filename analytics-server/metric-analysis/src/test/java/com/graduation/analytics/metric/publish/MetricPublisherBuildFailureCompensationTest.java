package com.graduation.analytics.metric.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.metric.MetricAdsCatalog;
import com.graduation.analytics.metric.MetricAdsWriter;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.publish.MetricPublisherPort.DefinitionRef;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricPublisherBuildFailureCompensationTest {

    @Test
    @DisplayName("核心指标构建失败时清理已写 ADS 行，避免 FAILED 快照遗留孤儿数据")
    void buildFailureCompensatesWrittenAdsRows(@TempDir Path exportDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MetricPublishRepository repository = mock(MetricPublishRepository.class);
        MetricAdsWriter adsWriter = mock(MetricAdsWriter.class);
        MetricStore metricStore = mock(MetricStore.class);
        when(adsWriter.deleteSnapshot("S_TEST_BUILD_FAIL")).thenReturn(0, 10);
        when(adsWriter.insertRows(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("S_TEST_BUILD_FAIL"), org.mockito.ArgumentMatchers.eq("20260923"),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(1);

        writeValidManifest(exportDir, mapper, true);
        Map<String, DefinitionRef> definitions = new LinkedHashMap<>();
        for (String code : List.of("pv", "uv", "dau", "paid_order_cnt", "gmv", "net_sale",
                "avg_order_value", "refund_rate", "full_refund_rate", "repeat_rate", "fav_cnt", "cart_add_cnt",
                "buy_rate", "cart_rate")) {
            definitions.put(code, new DefinitionRef("v1", ""));
        }
        PublishRequest request = new PublishRequest(1L, 1, 10L, "S_TEST_BUILD_FAIL", "20260923",
                "2026-09-23T00:00:00", 99L, exportDir, definitions);

        MetricPublisher publisher = new MetricPublisher(repository, adsWriter, new AdsExportReader(), metricStore,
                new MetricPublishValidator(), mapper);
        var report = publisher.publish(request);

        assertThat(report.ok()).isFalse();
        assertThat(report.errorCode()).isEqualTo("MP_VALUE_BUILD");
        assertThat(report.evidence()).containsEntry("compensatedAdsRows", 10);
        verify(adsWriter, times(2)).deleteSnapshot("S_TEST_BUILD_FAIL");
        verify(metricStore, org.mockito.Mockito.never()).publish(
                org.mockito.ArgumentMatchers.any(MetricStore.SnapshotRef.class), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("激活更新 0 行时补偿已写 ADS 行，旧快照仍由指标库事务回滚保护")
    void activationNoopCompensatesAdsRows(@TempDir Path exportDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MetricPublishRepository repository = mock(MetricPublishRepository.class);
        MetricAdsWriter adsWriter = mock(MetricAdsWriter.class);
        MetricStore metricStore = mock(MetricStore.class);
        when(adsWriter.deleteSnapshot("S_TEST_BUILD_FAIL")).thenReturn(0, 10);
        when(adsWriter.insertRows(anyString(), org.mockito.ArgumentMatchers.eq("S_TEST_BUILD_FAIL"),
                org.mockito.ArgumentMatchers.eq("20260923"), anyList())).thenReturn(1);
        when(metricStore.publish(any(MetricStore.SnapshotRef.class), anyList())).thenReturn(0);
        writeValidManifest(exportDir, mapper, false);

        Map<String, DefinitionRef> definitions = definitions();
        PublishRequest request = new PublishRequest(1L, 1, 10L, "S_TEST_BUILD_FAIL", "20260923",
                "2026-09-23T00:00:00", 99L, exportDir, definitions);
        MetricPublisher publisher = new MetricPublisher(repository, adsWriter, new AdsExportReader(), metricStore,
                new MetricPublishValidator(), mapper);

        var report = publisher.publish(request);

        assertThat(report.ok()).isFalse();
        assertThat(report.errorCode()).isEqualTo("MP_ACTIVATE_NOOP");
        assertThat(report.evidence()).containsEntry("compensatedAdsRows", 10);
        verify(adsWriter, times(2)).deleteSnapshot("S_TEST_BUILD_FAIL");
        verify(repository).markStatus("S_TEST_BUILD_FAIL", "FAILED", "MP_ACTIVATE_NOOP: 快照未处于 VERIFYING");
    }

    @Test
    @DisplayName("激活后只读对账失败时调用原子回退，再清理失败快照 ADS 行")
    void postActivationVerificationFailureRestoresPriorSnapshot(@TempDir Path exportDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MetricPublishRepository repository = mock(MetricPublishRepository.class);
        MetricAdsWriter adsWriter = mock(MetricAdsWriter.class);
        MetricStore metricStore = mock(MetricStore.class);
        when(repository.activeSnapshotId(1L)).thenReturn("S_OLD", "S_OLD");
        when(repository.countAdsRows(anyString(), org.mockito.ArgumentMatchers.eq("S_TEST_BUILD_FAIL")))
                .thenReturn(1L);
        when(repository.countMetricValues("S_TEST_BUILD_FAIL")).thenReturn(14L);
        when(repository.metricValues("S_TEST_BUILD_FAIL")).thenReturn(Map.of());
        when(adsWriter.deleteSnapshot("S_TEST_BUILD_FAIL")).thenReturn(0, 10);
        when(adsWriter.insertRows(anyString(), org.mockito.ArgumentMatchers.eq("S_TEST_BUILD_FAIL"),
                org.mockito.ArgumentMatchers.eq("20260923"), anyList())).thenReturn(1);
        when(metricStore.publish(any(MetricStore.SnapshotRef.class), anyList())).thenReturn(1);
        when(metricStore.failActivationAndRestore(any(MetricStore.SnapshotRef.class),
                org.mockito.ArgumentMatchers.eq("S_OLD"), anyString())).thenReturn("S_OLD");
        writeValidManifest(exportDir, mapper, false);

        PublishRequest request = new PublishRequest(1L, 1, 10L, "S_TEST_BUILD_FAIL", "20260923",
                "2026-09-23T00:00:00", 99L, exportDir, definitions());
        MetricPublisher publisher = new MetricPublisher(repository, adsWriter, new AdsExportReader(), metricStore,
                new MetricPublishValidator(), mapper);

        var report = publisher.publish(request);

        assertThat(report.ok()).isFalse();
        assertThat(report.errorCode()).isEqualTo("MP_POST_VERIFY_FAILED");
        assertThat(report.evidence()).containsEntry("postVerificationRecovery", "COMPLETED")
                .containsEntry("activeSnapshotAfterRecovery", "S_OLD")
                .containsEntry("compensatedAdsRows", 10);
        verify(metricStore).failActivationAndRestore(
                eq(new MetricStore.SnapshotRef("S_TEST_BUILD_FAIL", 1L, "day:2026-09-23")), eq("S_OLD"),
                anyString());
        verify(adsWriter, times(2)).deleteSnapshot("S_TEST_BUILD_FAIL");
    }

    @Test
    @DisplayName("MetricPublisher 可实际读取 manifest 中 file URI 指向的十张 ADS 导出文件")
    void publisherReadsFileUriExports(@TempDir Path exportDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MetricPublishRepository repository = mock(MetricPublishRepository.class);
        MetricAdsWriter adsWriter = mock(MetricAdsWriter.class);
        MetricStore metricStore = mock(MetricStore.class);
        when(adsWriter.deleteSnapshot("S_TEST_BUILD_FAIL")).thenReturn(0, 8);
        when(adsWriter.insertRows(anyString(), org.mockito.ArgumentMatchers.eq("S_TEST_BUILD_FAIL"),
                org.mockito.ArgumentMatchers.eq("20260923"), anyList())).thenReturn(1);
        when(metricStore.publish(any(MetricStore.SnapshotRef.class), anyList())).thenReturn(0);
        writeValidManifest(exportDir, mapper, false, true);

        PublishRequest request = new PublishRequest(1L, 1, 10L, "S_TEST_BUILD_FAIL", "20260923",
                "2026-09-23T00:00:00", 99L, exportDir, definitions());
        MetricPublisher publisher = new MetricPublisher(repository, adsWriter, new AdsExportReader(), metricStore,
                new MetricPublishValidator(), mapper);

        var report = publisher.publish(request);

        assertThat(report.errorCode())
                .as("10 张 URI 文件已被读取并进入预期的激活边界；模拟激活 0 行后 fail-closed")
                .isEqualTo("MP_ACTIVATE_NOOP");
        verify(adsWriter, times(MetricAdsCatalog.ALL.size())).insertRows(anyString(),
                org.mockito.ArgumentMatchers.eq("S_TEST_BUILD_FAIL"), org.mockito.ArgumentMatchers.eq("20260923"),
                anyList());
    }

    private Map<String, DefinitionRef> definitions() {
        Map<String, DefinitionRef> definitions = new LinkedHashMap<>();
        for (String code : List.of("pv", "uv", "dau", "paid_order_cnt", "gmv", "net_sale",
                "avg_order_value", "refund_rate", "full_refund_rate", "repeat_rate", "fav_cnt", "cart_add_cnt",
                "buy_rate", "cart_rate")) {
            definitions.put(code, new DefinitionRef("v1", ""));
        }
        return definitions;
    }

    private void writeValidManifest(Path exportDir, ObjectMapper mapper, boolean malformedSaleAmount) throws Exception {
        writeValidManifest(exportDir, mapper, malformedSaleAmount, false);
    }

    private void writeValidManifest(Path exportDir, ObjectMapper mapper, boolean malformedSaleAmount,
                                    boolean fileUri) throws Exception {
        Files.createDirectories(exportDir);
        var tables = new java.util.ArrayList<Map<String, Object>>();
        for (MetricAdsCatalog spec : MetricAdsCatalog.ALL) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String column : spec.columns()) {
                row.put(column, spec.keyColumns().contains(column) ? "key" : 0);
            }
            if (malformedSaleAmount && spec.name().equals("ads_operation_overview_m")) {
                row.put("sale_amount", "not-a-decimal");
            }
            Path file = exportDir.resolve(spec.name() + ".jsonl");
            Files.writeString(file, mapper.writeValueAsString(row) + "\n");
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("hiveTable", "dw_ads." + spec.name());
            table.put("mysqlTable", spec.name());
            table.put("rowCount", 1);
            table.put("columns", spec.columns());
            table.put("hivePath", "file:/warehouse/" + spec.name() + "/snapshot_id=S_TEST_BUILD_FAIL/dt=20260923");
            table.put("exportFile", fileUri ? file.toUri().toString() : file.toString());
            table.put("checksum", MetricExportManifest.crc32(file));
            tables.add(table);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("snapshotId", "S_TEST_BUILD_FAIL");
        manifest.put("businessDate", "20260923");
        manifest.put("dt", "20260923");
        manifest.put("source", "spark-ads");
        manifest.put("totalRows", tables.size());
        manifest.put("tables", tables);
        Files.writeString(exportDir.resolve(MetricPublisher.MANIFEST_FILE), mapper.writeValueAsString(manifest));
    }
}
