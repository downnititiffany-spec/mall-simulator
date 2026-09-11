package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M1-1 采集状态总览的 Landing 根来源（L0，不连库）。
 *
 * <p>钉住 V2.1 §3.4-4：状态总览的 Landing 根**只能**来自 ACTIVE {@code RuntimeProfile.landingUri}，
 * 与 {@code runOne} 同源；不得读取 {@code mall.*} 配置键，也不得在没有 ACTIVE 环境时回退到任何默认路径。</p>
 */
class IngestionServiceStatusTest {

    private final IngestionBatchMapper batchMapper = mock(IngestionBatchMapper.class);
    private final FileCheckpointMapper checkpointMapper = mock(FileCheckpointMapper.class);
    private final RuntimeProfileService runtimeProfileService = mock(RuntimeProfileService.class);

    private IngestionService service() {
        return new IngestionService(batchMapper, mock(IngestionBatchFileMapper.class), checkpointMapper,
                null, null, runtimeProfileService, new ObjectMapper());
    }

    @Test
    @DisplayName("有 ACTIVE 环境时：eventsDir 来自 profile.landingUri，并回显环境标识")
    void usesActiveProfileLandingUri(@TempDir Path landingRoot) throws IOException {
        Path events = Files.createDirectories(landingRoot.resolve("events"));
        Files.writeString(events.resolve("events-001.jsonl"), "{\"eventId\":\"e1\"}\n", StandardCharsets.UTF_8);
        Files.writeString(events.resolve("events-002.jsonl"), "{\"eventId\":\"e2\"}\n", StandardCharsets.UTF_8);
        Files.writeString(events.resolve("readme.txt"), "非 jsonl 不计入", StandardCharsets.UTF_8);

        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri(landingRoot.toUri().toString());
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile));
        when(batchMapper.selectOne(any())).thenReturn(null);
        when(checkpointMapper.selectCount(null)).thenReturn(7L);

        Map<String, Object> status = service().status();

        assertThat(status.get("profileState")).isEqualTo("ACTIVE");
        assertThat(status.get("runtimeProfileId")).isEqualTo(1L);
        assertThat(status.get("landingUri")).isEqualTo(landingRoot.toUri().toString());
        assertThat(status.get("eventsDir")).isEqualTo(events.toAbsolutePath().toString());
        assertThat(status.get("pendingFiles")).isEqualTo(2L);
        assertThat((Long) status.get("pendingBytes")).isPositive();
        assertThat(status.get("checkpointFiles")).isEqualTo(7L);
        assertThat(status.get("latestBatch")).isNull();
    }

    @Test
    @DisplayName("无 ACTIVE 环境时：如实报告 NO_ACTIVE，不读取任何默认路径")
    void reportsNoActiveWithoutFallingBackToDefaultPath() {
        when(runtimeProfileService.findActive()).thenReturn(Optional.empty());
        when(batchMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> status = service().status();

        assertThat(status.get("profileState")).isEqualTo("NO_ACTIVE");
        assertThat(status.get("runtimeProfileId")).isNull();
        assertThat(status.get("landingUri")).isNull();
        assertThat(status.get("eventsDir")).isNull();
        assertThat(status.get("pendingFiles")).isEqualTo(0L);
        assertThat(status.get("pendingBytes")).isEqualTo(0L);
    }

    @Test
    @DisplayName("landingUri 用标准 file:/// 写法（含 Windows 盘符）时同样能定位 events 目录")
    void acceptsStandardFileUriFromProfile(@TempDir Path landingRoot) throws IOException {
        // 回归守卫：旧实现把 file:///C:/... 截成 /C:/... → InvalidPathException（详见 LandingUriTest）
        Path events = Files.createDirectories(landingRoot.resolve("events"));
        Files.writeString(events.resolve("events-001.jsonl"), "{\"eventId\":\"e1\"}\n", StandardCharsets.UTF_8);

        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(2L);
        profile.setLandingUri(landingRoot.toAbsolutePath().toUri().toString());
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile));
        when(batchMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> status = service().status();

        assertThat(status.get("landingError")).isNull();
        assertThat(status.get("eventsDir")).isEqualTo(events.toAbsolutePath().toString());
        assertThat(status.get("pendingFiles")).isEqualTo(1L);
    }

    @Test
    @DisplayName("landingUri 不可解析时：status 如实报错并给出 landingError，而不是换一个目录")
    void reportsLandingErrorInsteadOfFallingBack(@TempDir Path landingRoot) {
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri("hdfs://namenode:8020/landing");
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile));
        when(batchMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> status = service().status();

        assertThat(status.get("profileState")).isEqualTo("ACTIVE");
        assertThat(status.get("eventsDir")).isNull();
        assertThat(status.get("pendingFiles")).isEqualTo(0L);
        assertThat((String) status.get("landingError")).contains("hdfs");
    }

    @Test
    @DisplayName("最近批次信息仍按批次表输出（不受 Landing 根来源变化影响）")
    void stillReportsLatestBatch() {
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri("file://./landing");
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile));

        IngestionBatch batch = new IngestionBatch();
        batch.setId(28L);
        batch.setRuntimeProfileId(1L);
        batch.setBatchNo("ing-20260911110644-4223656e");
        batch.setStatus("QUARANTINED");
        batch.setRecordCount(51L);
        batch.setQuarantineCount(4L);
        when(batchMapper.selectOne(any())).thenReturn(batch);
        when(checkpointMapper.selectCount(null)).thenReturn(50L);

        Map<String, Object> status = service().status();

        @SuppressWarnings("unchecked")
        Map<String, Object> latest = (Map<String, Object>) status.get("latestBatch");
        assertThat(latest).containsEntry("batchId", 28L)
                .containsEntry("status", "QUARANTINED")
                .containsEntry("recordCount", 51L)
                .containsEntry("quarantineCount", 4L);
    }
}
