package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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

    /** 状态总览的"新数据"判定唯一所有者在采集器里（DEF-12），因此这里注入真实采集器（依赖全部是 mock）。 */
    private LocalFileIngestor ingestor() {
        return new LocalFileIngestor(checkpointMapper, mock(QuarantineRecordMapper.class),
                mock(EventContractValidator.class), new ObjectMapper());
    }

    private IngestionService service() {
        return new IngestionService(batchMapper, mock(IngestionBatchFileMapper.class), checkpointMapper,
                ingestor(), null, runtimeProfileService, new ObjectMapper());
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
        // B-08 / D-022 候选①：从未采集过（断点缺失）→ 两个文件都算"新增"；到达时间如实给出
        assertThat(status.get("newFileCount")).isEqualTo(2L);
        assertThat(status.get("lastArrivalAt")).isNotNull();
        assertThat(LocalDateTime.parse((String) status.get("lastArrivalAt"))).isBefore(LocalDateTime.now().plusMinutes(1));
    }

    @Test
    @DisplayName("B-08：已采完的文件不重复计入 newFileCount，但仍计入 pendingFiles（累计语义不变）")
    void countsOnlyFilesWithUnreadContentAsNew(@TempDir Path landingRoot) throws IOException {
        Path events = Files.createDirectories(landingRoot.resolve("events"));
        Path file = events.resolve("events-001.jsonl");
        Files.writeString(file, "{\"eventId\":\"e1\"}\n", StandardCharsets.UTF_8);
        long size = Files.size(file);

        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri(landingRoot.toUri().toString());
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile));
        when(batchMapper.selectOne(any())).thenReturn(null);
        // 断点已推进到文件末尾，且文件身份未变（与 LocalFileIngestor 同源的判定）
        FileCheckpoint done = new FileCheckpoint();
        done.setRuntimeProfileId(1L);
        done.setFilePath(file.toAbsolutePath().toString());
        done.setFileIdentity(LocalFileIngestor.fileIdentity(file));
        done.setNextOffset(size);
        when(checkpointMapper.selectOne(any())).thenReturn(done);

        Map<String, Object> status = service().status();

        assertThat(status.get("newFileCount")).isEqualTo(0L);
        assertThat(status.get("pendingFiles")).isEqualTo(1L);

        // 追加新行后（断点 < 文件长度）→ 重新计为"新增"
        Files.writeString(file, "{\"eventId\":\"e2\"}\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        Map<String, Object> afterAppend = service().status();
        assertThat(afterAppend.get("newFileCount")).isEqualTo(1L);
    }

    @Test
    @DisplayName("B-08：文件被删除重建（身份变化）同样算新增——与采集端「从头读」语义一致")
    void recreatedFileCountsAsNew(@TempDir Path landingRoot) throws IOException {
        Path events = Files.createDirectories(landingRoot.resolve("events"));
        Path file = events.resolve("events-001.jsonl");
        Files.writeString(file, "{\"eventId\":\"e1\"}\n", StandardCharsets.UTF_8);

        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri(landingRoot.toUri().toString());
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile));
        when(batchMapper.selectOne(any())).thenReturn(null);
        FileCheckpoint stale = new FileCheckpoint();
        stale.setRuntimeProfileId(1L);
        stale.setFilePath(file.toAbsolutePath().toString());
        stale.setFileIdentity("旧的创建时间戳");
        stale.setNextOffset(Files.size(file));
        when(checkpointMapper.selectOne(any())).thenReturn(stale);

        Map<String, Object> status = service().status();

        assertThat(status.get("newFileCount")).isEqualTo(1L);
    }

    @Test
    @DisplayName("B-08：空文件不算新增（没有内容可读）")
    void emptyFileIsNotNew(@TempDir Path landingRoot) throws IOException {
        Path events = Files.createDirectories(landingRoot.resolve("events"));
        Files.writeString(events.resolve("events-001.jsonl"), "", StandardCharsets.UTF_8);

        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri(landingRoot.toUri().toString());
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile));
        when(batchMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> status = service().status();

        assertThat(status.get("newFileCount")).isEqualTo(0L);
        assertThat(status.get("lastArrivalAt")).isNotNull();   // 文件存在 → 有到达时间
    }

    @Test
    @DisplayName("DEF-12：尾部无换行的残行不算新增数据（残行永远消费不掉，否则 newFileCount 永久非零）")
    void partialTailLineIsNotNewData(@TempDir Path landingRoot) throws IOException {
        Path events = Files.createDirectories(landingRoot.resolve("events"));
        Path file = events.resolve("events-001.jsonl");
        Files.writeString(file, "{\"eventId\":\"e1\"}\n", StandardCharsets.UTF_8);
        long completeBoundary = Files.size(file);
        // 写入一条没有换行的残行（模拟写入中途被杀 / 文件尚未写完）
        Files.writeString(file, "{\"eventId\":\"e2\"", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertThat(LocalFileIngestor.consumableEnd(file)).isEqualTo(completeBoundary);

        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri(landingRoot.toUri().toString());
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile));
        when(batchMapper.selectOne(any())).thenReturn(null);
        FileCheckpoint done = new FileCheckpoint();
        done.setRuntimeProfileId(1L);
        done.setFilePath(file.toAbsolutePath().toString());
        done.setFileIdentity(LocalFileIngestor.fileIdentity(file));
        done.setNextOffset(completeBoundary);           // 断点停在完整行边界，落后于文件长度
        when(checkpointMapper.selectOne(any())).thenReturn(done);

        assertThat(service().status().get("newFileCount")).isEqualTo(0L);

        // 文件继续增长、残行补全 → 才重新算"有新数据"
        Files.writeString(file, "}\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertThat(service().status().get("newFileCount")).isEqualTo(1L);
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
        assertThat(status.get("newFileCount")).isEqualTo(0L);
        assertThat(status.get("lastArrivalAt")).isNull();
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
