package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.entity.IngestionBatchFile;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import com.graduation.analytics.source.entity.SourceRegistry;
import com.graduation.analytics.mapping.ingest.SourceMapper;
import com.graduation.analytics.mapping.ingest.SourceMapping;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-05 / D-037 裁决 4·5·6·8：批次归因与 manifest 四个源身份字段（L0，不连库）。
 *
 * <p>钉住的具体取值口径：</p>
 * <ul>
 *   <li>{@code ingestion_batch.source_id} = 本轮解析出的源 id（<b>列可空，但新行必须写</b>）；</li>
 *   <li>manifest {@code sourceCode} / {@code sourceId} / {@code profileVersion} 一律取
 *       {@code source_registry} 的 {@code source_code} / {@code id} / {@code profile_version} <b>三列</b>
 *       （裁决 8：禁止解析画像 JSON 反推版本，那会造出第二个所有者）；</li>
 *   <li>manifest {@code mappingVersion} 在 P1-05 阶段<b>恒为 {@code null}</b>
 *       （裁决 6：禁止写 {@code "0"}/{@code ""}/{@code "v1"} 之类占位值冒充版本）；</li>
 *   <li>{@code source}（连接器类型）语义不变，仍是 {@code local-file}
 *       （裁决 5：不得改名、不得复用为源标识）。</li>
 * </ul>
 */
class IngestionSourceManifestTest {

    /**
     * 批次文件账（S2-02B 的重放口径读它）与断点查询都要经过 MyBatis-Plus 的 lambda 列名缓存
     * （平时由 SqlSessionFactory 建），L0 测试手工建一次。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FileCheckpoint.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                IngestionBatchFile.class);
    }

    private final IngestionBatchMapper batchMapper = mock(IngestionBatchMapper.class);
    private final IngestionBatchFileMapper batchFileMapper = mock(IngestionBatchFileMapper.class);
    private final LocalFileIngestor ingestor = mock(LocalFileIngestor.class);
    private final RuntimeProfileService runtimeProfileService = mock(RuntimeProfileService.class);
    private final SourceRegistryService sourceRegistryService = mock(SourceRegistryService.class);
    /** S2-02：本测试考的是清单四字段口径（登记列 vs 画像自述），映射由 SourceMapperTest 覆盖。 */
    private final SourceMapper sourceMapper = mock(SourceMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventClock clock = new EventClock(
            Clock.fixed(Instant.parse("2026-09-12T02:00:00Z"), ZoneId.of("Asia/Shanghai")));

    private IngestionService service() {
        when(sourceMapper.prepare(any())).thenReturn(SourceMapping.legacy());
        return new IngestionService(batchMapper, batchFileMapper, ingestor, clock,
                runtimeProfileService, sourceRegistryService, objectMapper, sourceMapper);
    }

    private void stubRun(Path landingRoot, long sourceId, String sourceCode, String profileVersion)
            throws IOException {
        Path events = Files.createDirectories(landingRoot.resolve("events"));
        Path file = events.resolve("events-001.jsonl");
        Files.writeString(file, "{\"eventId\":\"e1\"}\n", StandardCharsets.UTF_8);

        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri(landingRoot.toUri().toString());
        when(runtimeProfileService.getActive()).thenReturn(profile);

        when(sourceRegistryService.currentSourceId()).thenReturn(Optional.of(sourceId));
        SourceRegistry row = IngestionSourceNotBoundTest.sourceRow(sourceId, sourceCode, profileVersion);
        when(sourceRegistryService.get(sourceId)).thenReturn(SourceRegistryView.of(row, sourceId));
        when(ingestor.ingestFile(any(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(new LocalFileIngestor.FileResult("events-001.jsonl", 0, 16,
                        "identity-1", 1, 0, 16L, Set.of("1.0")));
        when(batchMapper.insert(any(IngestionBatch.class))).thenAnswer(inv -> {
            inv.<IngestionBatch>getArgument(0).setId(101L);
            return 1;
        });
    }

    private JsonNode manifestOf(IngestionService.RunResult result) throws IOException {
        // RunResult.manifestPath() 是 **URI 字符串**（writeManifestQuietly 返回 file.toUri()），
        // 不是文件系统路径——Path.of("file:///C:/...") 在 Windows 上会抛 InvalidPathException。
        assertThat(result.manifestPath()).as("清单 URI 必须存在").isNotNull();
        Path manifest = Path.of(URI.create(result.manifestPath()));
        assertThat(Files.isRegularFile(manifest)).as("清单必须真的落盘：%s", manifest).isTrue();
        return objectMapper.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
    }

    // ---------- ① 批次归因 ----------

    @Test
    @DisplayName("新批次行写入解析出的 source_id（列可空，但新行必须写）")
    void newBatchRowCarriesSourceId(@TempDir Path landingRoot) throws IOException {
        stubRun(landingRoot, 7L, "probe-seven", "2.1");

        service().runOne(TraceContext.create());

        ArgumentCaptor<IngestionBatch> captor = ArgumentCaptor.forClass(IngestionBatch.class);
        verify(batchMapper).insert(captor.capture());
        assertThat(captor.getValue().getSourceId())
                .as("批次必须归因到源：不能靠 profile 反推（profile 的 source_id 会被切换改掉，D-037 裁决 4）")
                .isEqualTo(7L);
        assertThat(captor.getValue().getRuntimeProfileId()).isEqualTo(1L);
        assertThat(captor.getValue().getSource())
                .as("连接器类型语义不变（D-037 裁决 5）")
                .isEqualTo("local-file");
    }

    // ---------- ② manifest 四字段 ----------

    @Test
    @DisplayName("manifest 四字段取值：sourceCode/sourceId/profileVersion 取自登记列，mappingVersion 恒为 null")
    void manifestCarriesSourceIdentityFields(@TempDir Path landingRoot) throws IOException {
        stubRun(landingRoot, 2L, "probe-two", "3.4");

        JsonNode manifest = manifestOf(service().runOne(TraceContext.create()));

        assertThat(manifest.path("sourceCode").asText())
                .as("sourceCode 取 source_registry.source_code")
                .isEqualTo("probe-two");
        assertThat(manifest.path("sourceId").isIntegralNumber())
                .as("sourceId 必须是 JSON 数字（schema: integer），不是字符串")
                .isTrue();
        assertThat(manifest.path("sourceId").asLong()).isEqualTo(2L);
        assertThat(manifest.path("profileVersion").asText())
                .as("profileVersion 取 source_registry.profile_version 登记列（裁决 8：不是画像文件里的自述值）")
                .isEqualTo("3.4");
        assertThat(manifest.has("mappingVersion"))
                .as("mappingVersion 这个键必须**存在**（否则读侧无法区分「未应用映射」与「字段缺失」）")
                .isTrue();
        assertThat(manifest.get("mappingVersion").isNull())
                .as("P1-05 阶段恒为 null（裁决 6：禁止 '0'/''/'v1' 之类占位值）")
                .isTrue();
    }

    @Test
    @DisplayName("回归：source（连接器类型）与 status 语义不变，仍是 local-file / READY")
    void connectorTypeAndStatusUnchanged(@TempDir Path landingRoot) throws IOException {
        stubRun(landingRoot, 1L, "mock-mall", "1.0");

        JsonNode manifest = manifestOf(service().runOne(TraceContext.create()));

        assertThat(manifest.path("source").asText())
                .as("D-037 裁决 5：source 是连接器类型，不许改名、不许复用为源标识")
                .isEqualTo("local-file");
        assertThat(manifest.path("status").asText()).isEqualTo("READY");
    }

    @Test
    @DisplayName("profileVersion 不来自画像文件：登记列与画像自述不一致时，写出的必须是登记值")
    void profileVersionComesFromRegistryNotProfileFile(@TempDir Path landingRoot) throws IOException {
        // 故意让登记列（9.9）与任何画像自述版本都不一致；真正的画像文件在本轮甚至不存在
        // （analytics-server/source-profiles/mock-mall.v1.json 尚未交付，见该目录 README）。
        stubRun(landingRoot, 1L, "mock-mall", "9.9");

        JsonNode manifest = manifestOf(service().runOne(TraceContext.create()));

        assertThat(manifest.path("profileVersion").asText())
                .as("若实现改成解析画像 JSON 反推，这里会变红——那正是裁决 8 禁止的第二所有者")
                .isEqualTo("9.9");
    }

    // ---------- ③ 清单其余 15 个既有键一个不少（加法，不是替换） ----------

    @Test
    @DisplayName("加法而非替换：既有 15 个契约键全部保留，四字段是新增的")
    void existingManifestKeysArePreserved(@TempDir Path landingRoot) throws IOException {
        stubRun(landingRoot, 1L, "mock-mall", "1.0");

        JsonNode manifest = manifestOf(service().runOne(TraceContext.create()));

        assertThat(manifest.fieldNames()).toIterable()
                .as("ingestion-manifest.v1 的 15 个 required 键必须一个不少")
                .contains("batchId", "batchNo", "runtimeProfileId", "source", "status",
                        "startedAt", "finishedAt", "files", "acceptedRecords", "quarantinedRecords",
                        "acceptedBytes", "schemaVersions", "acceptedUri", "quarantineUri", "checksum");
        assertThat(manifest.fieldNames()).toIterable()
                .as("四个源身份字段是加法新增")
                .contains("sourceCode", "sourceId", "profileVersion", "mappingVersion");
    }
}
