package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.entity.IngestionBatchFile;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.mapping.ingest.SourceMapper;
import com.graduation.analytics.mapping.ingest.SourceMapping;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.source.SourceRegistryService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2-04B：采集端按 {@code runtime_profile.landing_layout} 选**输入根**（设计 §8.2 规则 3/4、§8.4）。
 *
 * <p>钉住三件事，都在"读哪个目录 / 记哪个键"这一层：</p>
 * <ol>
 *   <li>{@code FLUME_RAW}：读 {@code <landing>/raw} 且**递归**（Flume HDFS Sink 的源级
 *       {@code dt=/hour=} 分区形态），in-use 临时文件不进批次账；</li>
 *   <li>批次**账本**（{@code ingestion_batch_file.file_path}）记**输入根相对键**：
 *       {@code raw/dt=…/hour=…/events.1}。滚动日志布局下它就是文件名，因此既有行为逐字节不变；
 *       嵌套布局下它才让"同名不同分区"成为两个输入，而不是撞
 *       {@code uk_batch_file(batch_id, file_path)} 后整批 FAILED。清单 {@code files[].file}
 *       仍按冻结契约记文件名（同名条目会重复，属已登记的待裁决契约问题，见下）；</li>
 *   <li>布局未配置（V22 之前的存量行）＝滚动日志：只认 {@code events/*.jsonl}，
 *       {@code raw/} 树对它**完全不可见**（不得出现"改个目录就多读一倍数据"）。</li>
 * </ol>
 *
 * <p>本类是 L0（不连库）：断点/契约校验由被 mock 的 {@link LocalFileIngestor} 代表，
 * 这里只考"目录与键"的装配。</p>
 */
class IngestionLandingLayoutTest {

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FileCheckpoint.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                IngestionBatchFile.class);
    }

    private static final long SOURCE_ID = 1L;
    private static final TraceContext TRACE = new TraceContext("trace-layout");

    private final IngestionBatchMapper batchMapper = mock(IngestionBatchMapper.class);
    private final IngestionBatchFileMapper batchFileMapper = mock(IngestionBatchFileMapper.class);
    private final LocalFileIngestor ingestor = mock(LocalFileIngestor.class);
    private final RuntimeProfileService runtimeProfileService = mock(RuntimeProfileService.class);
    private final SourceRegistryService sourceRegistryService = mock(SourceRegistryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventClock clock = new EventClock(
            Clock.fixed(Instant.parse("2026-09-16T02:00:00Z"), ZoneId.of("Asia/Shanghai")));

    private IngestionService service() {
        return new IngestionService(batchMapper, batchFileMapper, ingestor, clock, runtimeProfileService,
                sourceRegistryService, objectMapper, stubMapper());
    }

    private static SourceMapper stubMapper() {
        SourceMapper mapper = mock(SourceMapper.class);
        when(mapper.prepare(any())).thenReturn(SourceMapping.legacy());
        return mapper;
    }

    private RuntimeProfile profile(Path landingRoot, String landingLayout) {
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri(landingRoot.toUri().toString());
        profile.setLandingLayout(landingLayout);
        when(runtimeProfileService.getActive()).thenReturn(profile);
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile));
        when(sourceRegistryService.currentSourceId()).thenReturn(Optional.of(SOURCE_ID));
        when(sourceRegistryService.get(SOURCE_ID)).thenReturn(
                com.graduation.analytics.source.dto.SourceRegistryView.of(
                        sourceRow(SOURCE_ID), 1L));
        return profile;
    }

    private static com.graduation.analytics.source.entity.SourceRegistry sourceRow(Long id) {
        com.graduation.analytics.source.entity.SourceRegistry row =
                new com.graduation.analytics.source.entity.SourceRegistry();
        row.setId(id);
        row.setSourceCode("mock-mall");
        row.setDisplayName("mock-mall");
        row.setIngestMode("FILE");
        row.setProfilePath("analytics-server/source-profiles/mock-mall.v1.json");
        row.setTimezone("Asia/Shanghai");
        row.setCurrency("CNY");
        row.setStatus("ACTIVE");
        row.setProfileVersion("1.0");
        return row;
    }

    /** 每个被采文件都"采到 3 条"，批次账里因此有行、清单里因此有 files[]。 */
    private void stubIngestCollected() {
        when(ingestor.ingestFile(any(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Path file = inv.getArgument(0);
                    return new LocalFileIngestor.FileResult(file.getFileName().toString(), 0L, 64L,
                            "identity-" + file.getFileName(), 3L, 0L, 64L, Set.of("1.0"));
                });
        when(batchMapper.insert(any(IngestionBatch.class))).thenAnswer(inv -> {
            inv.<IngestionBatch>getArgument(0).setId(101L);
            return 1;
        });
        when(batchFileMapper.selectCount(any())).thenReturn(0L);
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    private List<String> ingestedRelativePaths(Path landingRoot) {
        ArgumentCaptor<Path> captor = ArgumentCaptor.forClass(Path.class);
        verify(ingestor, org.mockito.Mockito.atLeast(0))
                .ingestFile(captor.capture(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any());
        List<String> keys = new ArrayList<>();
        for (Path p : captor.getAllValues()) {
            keys.add(landingRoot.relativize(p).toString().replace('\\', '/'));
        }
        return keys;
    }

    private List<String> ledgerKeys() {
        ArgumentCaptor<IngestionBatchFile> captor = ArgumentCaptor.forClass(IngestionBatchFile.class);
        verify(batchFileMapper, org.mockito.Mockito.atLeast(0)).insert(captor.capture());
        return captor.getAllValues().stream().map(IngestionBatchFile::getFilePath).toList();
    }

    private JsonNode manifestOf(IngestionService.RunResult result) throws IOException {
        Path manifest = Path.of(URI.create(result.manifestPath()));
        assertThat(Files.isRegularFile(manifest)).as("清单必须落盘：%s", manifest).isTrue();
        return objectMapper.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
    }

    private static List<String> manifestFileKeys(JsonNode manifest) {
        List<String> keys = new ArrayList<>();
        manifest.get("files").forEach(node -> keys.add(node.get("file").asText()));
        return keys;
    }

    @Test
    @DisplayName("FLUME_RAW：递归读 raw 分区的完成文件，账/清单记输入根相对键，临时文件不进批次")
    void flumeRawReadsNestedCompletedFilesWithRelativeKeys(@TempDir Path landingRoot) throws IOException {
        profile(landingRoot, "FLUME_RAW");
        stubIngestCollected();
        Path raw = landingRoot.resolve("raw");
        write(raw.resolve("dt=20260901").resolve("hour=09"), "events.1", "{\"a\":1}\n");
        write(raw.resolve("dt=20260901").resolve("hour=10"), "events.1", "{\"a\":2}\n");
        write(raw.resolve("dt=20260902").resolve("hour=09"), "events.2", "{\"a\":3}\n");
        write(raw.resolve("dt=20260902").resolve("hour=09"), ".events.3.tmp", "{\"a\":4}");

        IngestionService.RunResult result = service().runOne(TRACE);

        assertThat(ingestedRelativePaths(landingRoot)).containsExactly(
                "raw/dt=20260901/hour=09/events.1",
                "raw/dt=20260901/hour=10/events.1",
                "raw/dt=20260902/hour=09/events.2");
        assertThat(ledgerKeys()).as("同名不同分区必须是两行账（否则撞唯一键整批 FAILED）")
                .containsExactlyInAnyOrder(
                        "dt=20260901/hour=09/events.1",
                        "dt=20260901/hour=10/events.1",
                        "dt=20260902/hour=09/events.2");
        // 清单的 files[].file 按冻结契约仍是**文件名**（schema：「文件名（非绝对路径）」，引用
        // LocalFileIngestor.getFileName()）。因此嵌套布局下同名条目会重复出现——这是已登记的
        // 待裁决契约问题（F-31），本用例把它**钉成已知形态**而不是假装不存在：账本侧已用相对键消歧。
        assertThat(manifestFileKeys(manifestOf(result))).containsExactlyInAnyOrder(
                "events.1", "events.1", "events.2");
        assertThat(result.noNewData()).isFalse();
    }

    @Test
    @DisplayName("布局未配置（存量行）＝滚动日志：raw 树对它完全不可见")
    void absentLayoutNeverReadsRawTree(@TempDir Path landingRoot) throws IOException {
        profile(landingRoot, null);
        stubIngestCollected();
        write(landingRoot.resolve("raw").resolve("dt=20260901").resolve("hour=09"), "events.1", "{\"a\":1}\n");
        write(landingRoot.resolve("events"), "events-0900.jsonl", "{\"a\":2}\n");

        IngestionService.RunResult result = service().runOne(TRACE);

        assertThat(ingestedRelativePaths(landingRoot)).containsExactly("events/events-0900.jsonl");
        assertThat(ledgerKeys()).as("滚动日志布局的账仍是文件名（既有语义不变）")
                .containsExactly("events-0900.jsonl");
    }

    @Test
    @DisplayName("显式 ROLLING_LOG 而 raw 有数据：本轮如实 noNewData，不偷偷换目录去读")
    void rollingLogLayoutIgnoresRawOnlyData(@TempDir Path landingRoot) throws IOException {
        profile(landingRoot, "ROLLING_LOG");
        stubIngestCollected();
        write(landingRoot.resolve("raw").resolve("dt=20260901").resolve("hour=09"), "events.1", "{\"a\":1}\n");
        Files.createDirectories(landingRoot.resolve("events"));

        IngestionService.RunResult result = service().runOne(TRACE);

        verify(ingestor, never()).ingestFile(any(), anyLong(), anyLong(), anyLong(), any(), any(), any(),
                any(), any());
        assertThat(result.fileCount()).isZero();
        assertThat(result.noNewData()).as("raw 里那一个文件不属于本布局：0 新字节就是 0 新字节").isTrue();
    }

    @Test
    @DisplayName("状态总览与采集同源：FLUME_RAW 的待采文件数＝raw 树的完成文件数")
    void statusCountsPendingFilesByLayout(@TempDir Path landingRoot) throws IOException {
        profile(landingRoot, "FLUME_RAW");
        Path raw = landingRoot.resolve("raw");
        write(raw.resolve("dt=20260901").resolve("hour=09"), "events.1", "{\"a\":1}\n");
        write(raw.resolve("dt=20260901").resolve("hour=10"), "events.2", "{\"a\":2}\n");
        write(raw.resolve("dt=20260901").resolve("hour=10"), ".events.3.tmp", "{\"a\":3}");

        var status = service().status();

        assertThat(status.get("pendingFiles")).as("状态口若按 events/ 扫，运维会看到 0 而采到 3 个文件")
                .isEqualTo(2L);
        assertThat(status.get("landingLayout")).isEqualTo("FLUME_RAW");
        assertThat(status.get("eventsDir").toString().replace('\\', '/'))
                .as("状态口报的目录必须就是采集端真的会读的那个目录")
                .endsWith("/raw");
    }
}
