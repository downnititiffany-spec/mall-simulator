package com.graduation.analytics.metric.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.metric.MetricAdsCatalog;
import com.graduation.analytics.metric.MetricAdsWriter;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricPublisherSourceIdentityTest {

    private static final String SNAPSHOT_ID = "S20260923_SOURCE_ID";

    @Test
    @DisplayName("发布请求的冻结业务来源身份传递给快照登记")
    void forwardsFrozenSourceIdWhenRegisteringSnapshot(@TempDir Path dir) throws Exception {
        MetricPublishRepository repository = mock(MetricPublishRepository.class);
        when(repository.activeSnapshotId(7L)).thenReturn(null);
        MetricAdsWriter adsWriter = mock(MetricAdsWriter.class);
        when(adsWriter.deleteSnapshot(SNAPSHOT_ID)).thenReturn(0);

        MetricPublisher publisher = new MetricPublisher(repository, adsWriter, new AdsExportReader(),
                mock(MetricStore.class), new MetricPublishValidator(), new ObjectMapper());
        writeEmptyManifest(dir);

        PublishRequest request = new PublishRequest(7L, 3, 42L, SNAPSHOT_ID, "2026-09-23",
                "2026-09-23T12:00:00", 99L, dir, Map.of());
        publisher.publish(request);

        verify(repository).createBuilding(7L, 3, SNAPSHOT_ID, "2026-09-23", "2026-09-23T12:00:00",
                99L, "", 42L);
    }

    private static void writeEmptyManifest(Path dir) throws Exception {
        List<Map<String, Object>> tables = new ArrayList<>();
        for (MetricAdsCatalog catalog : MetricAdsCatalog.ALL) {
            Path exportFile = dir.resolve(catalog.name() + ".jsonl");
            Files.writeString(exportFile, "");
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("hiveTable", catalog.name());
            table.put("mysqlTable", catalog.name());
            table.put("rowCount", 0);
            table.put("columns", catalog.columns());
            table.put("hivePath", "/warehouse/" + catalog.name() + "/snapshot_id=" + SNAPSHOT_ID
                    + "/dt=2026-09-23");
            table.put("exportFile", exportFile.toString());
            table.put("checksum", MetricExportManifest.crc32(exportFile));
            tables.add(table);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("snapshotId", SNAPSHOT_ID);
        manifest.put("businessDate", "2026-09-23");
        manifest.put("dt", "2026-09-23");
        manifest.put("source", "spark-ads");
        manifest.put("totalRows", 0);
        manifest.put("tables", tables);
        new ObjectMapper().writeValue(dir.resolve(MetricPublisher.MANIFEST_FILE).toFile(), manifest);
    }
}
