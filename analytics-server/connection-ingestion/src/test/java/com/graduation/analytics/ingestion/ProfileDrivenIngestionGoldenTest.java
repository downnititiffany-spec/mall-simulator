package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.entity.IngestionBatchFile;
import com.graduation.analytics.ingestion.entity.QuarantineRecord;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper;
import com.graduation.analytics.mapper.PureMapper;
import com.graduation.analytics.mapper.RepoPathLocator;
import com.graduation.analytics.mapper.SourceProfile;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WP-2B 交付项 4 行为测试：mock-mall + 真实 55 行黄金集采集。
 *
 * <p>口径继承：
 * <ul>
 *   <li>总行数 55 行；</li>
 *   <li>PureMapper 映射结果：54 行 Canonical，1 行 Quarantined（Line 53 畸形 JSON，违规 JSON_PARSE_ERROR）；</li>
 *   <li>EventContractValidator 闸门（对 54 行 Canonical 产物）：51 行通过，3 行被拦截
 *       （Line 38 非法 behavior_type: purchase，Line 54 未支持 schema_version: 2.0，Line 55 缺失 event_id）；</li>
 *   <li>最终批次计数：acceptedRecords = 51，quarantinedRecords = 4（复现清单 30 冻结口径）；</li>
 *   <li>严格口径：strictContractAccepted = 44（51 接受中扣除 6 行 channel=web 与 1 行 change_type=restock 的 ENUM_UNRESOLVED）；</li>
 *   <li>隔离区输出保留原始行，且 QuarantineRecord 清晰区分 mapper 隔离原因码与 validator 违规原因。</li>
 * </ul>
 * </p>
 */
class ProfileDrivenIngestionGoldenTest {

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FileCheckpoint.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), IngestionBatch.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), IngestionBatchFile.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QuarantineRecord.class);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventClock clock = new EventClock(
            Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneId.of("Asia/Shanghai")));

    private final FileCheckpointMapper checkpointMapper = mock(FileCheckpointMapper.class);
    private final QuarantineRecordMapper quarantineRecordMapper = mock(QuarantineRecordMapper.class);
    private final IngestionBatchMapper batchMapper = mock(IngestionBatchMapper.class);
    private final IngestionBatchFileMapper batchFileMapper = mock(IngestionBatchFileMapper.class);
    private final RuntimeProfileService runtimeProfileService = mock(RuntimeProfileService.class);
    private final SourceRegistryService sourceRegistryService = mock(SourceRegistryService.class);

    private final Map<String, FileCheckpoint> checkpointStore = new HashMap<>();
    private final List<QuarantineRecord> quarantineRecords = new ArrayList<>();
    private final List<IngestionBatchFile> batchFiles = new ArrayList<>();

    private Path repoRoot() {
        return RepoPathLocator.locateRepoRoot(".");
    }

    @Test
    @DisplayName("mock-mall + 55 行真实黄金集：复现 51 接受 / 4 隔离口径，规范信封输出，违规原因清晰")
    void goldenDatasetIngestionMatchesFrozenManifest30Baseline(@TempDir Path tempLanding) throws IOException {
        Path goldenFile = repoRoot().resolve("tests/golden-dataset/events/golden-20260901.jsonl");
        assertThat(Files.isRegularFile(goldenFile)).as("黄金集文件必须存在: %s", goldenFile).isTrue();

        // 1. 设置运行时与登记源
        RuntimeProfile runtime = new RuntimeProfile();
        runtime.setId(1L);
        runtime.setLandingUri(tempLanding.toUri().toString());
        when(runtimeProfileService.getActive()).thenReturn(runtime);
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(runtime));

        SourceRegistryView mockMallView = new SourceRegistryView(
                1L, "mock-mall", "参考商城（源 A）", "FILE",
                "analytics-server/source-profiles/mock-mall.v1.json",
                "Asia/Shanghai", "CNY", "ACTIVE", "1.0", true,
                LocalDateTime.now(), LocalDateTime.now(), "ods"
        );
        when(sourceRegistryService.currentSourceId()).thenReturn(Optional.of(1L));
        when(sourceRegistryService.get(1L)).thenReturn(mockMallView);
        when(sourceRegistryService.list()).thenReturn(List.of(mockMallView));

        // 2. 打桩持久化
        when(checkpointMapper.insert(any(FileCheckpoint.class))).thenAnswer(inv -> {
            FileCheckpoint cp = inv.getArgument(0);
            checkpointStore.put(cp.getFilePath(), cp);
            return 1;
        });
        when(checkpointMapper.updateById(any(FileCheckpoint.class))).thenAnswer(inv -> {
            FileCheckpoint cp = inv.getArgument(0);
            checkpointStore.put(cp.getFilePath(), cp);
            return 1;
        });
        when(checkpointMapper.selectOne(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<FileCheckpoint> w = inv.getArgument(0);
            // 简单按文件绝对路径命中
            String targetKey = LocalFileIngestor.checkpointKey(goldenFile);
            return checkpointStore.get(targetKey);
        });

        when(quarantineRecordMapper.insert(any(QuarantineRecord.class))).thenAnswer(inv -> {
            quarantineRecords.add(inv.getArgument(0));
            return 1;
        });

        when(batchMapper.insert(any(IngestionBatch.class))).thenAnswer(inv -> {
            IngestionBatch b = inv.getArgument(0);
            b.setId(30L);
            return 1;
        });
        when(batchMapper.updateById(any(IngestionBatch.class))).thenReturn(1);

        when(batchFileMapper.insert(any(IngestionBatchFile.class))).thenAnswer(inv -> {
            batchFiles.add(inv.getArgument(0));
            return 1;
        });

        // 3. 构建真实采集器与编排服务
        EventContractValidator validator = new EventContractValidator(objectMapper);
        PureMapper pureMapper = new PureMapper();
        LocalFileIngestor ingestor = new LocalFileIngestor(checkpointMapper, quarantineRecordMapper, validator, objectMapper, pureMapper);
        IngestionService ingestionService = new IngestionService(batchMapper, batchFileMapper, ingestor, clock,
                runtimeProfileService, sourceRegistryService, objectMapper);

        // 4. 执行单文件画像驱动采集
        IngestionService.RunResult result = ingestionService.ingestFileForSource(goldenFile, "mock-mall", TraceContext.create());

        // 5. 校验运行结果
        assertThat(result.batchId()).isEqualTo(30L);
        assertThat(result.status()).isEqualTo("QUARANTINED");
        assertThat(result.recordCount()).isEqualTo(51L);
        assertThat(result.quarantineCount()).isEqualTo(4L);
        assertThat(result.noNewData()).isFalse();

        // 6. 校验主清单 (landing/manifests/30.json)
        Path manifestFile = tempLanding.resolve("manifests/30.json");
        assertThat(Files.isRegularFile(manifestFile)).isTrue();
        JsonNode manifest = objectMapper.readTree(Files.readString(manifestFile, StandardCharsets.UTF_8));

        assertThat(manifest.get("batchId").asLong()).isEqualTo(30L);
        assertThat(manifest.get("source").asText()).isEqualTo("local-file");
        assertThat(manifest.get("status").asText()).isEqualTo("READY");
        assertThat(manifest.get("acceptedRecords").asLong()).isEqualTo(51L);
        assertThat(manifest.get("quarantinedRecords").asLong()).isEqualTo(4L);
        assertThat(manifest.get("sourceCode").asText()).isEqualTo("mock-mall");
        assertThat(manifest.get("sourceId").asLong()).isEqualTo(1L);
        assertThat(manifest.get("profileVersion").asText()).isEqualTo("1.0");

        // WP-2B 加法扩展字段断言
        assertThat(manifest.get("totalRows").asLong()).isEqualTo(55L);
        assertThat(manifest.get("canonicalRows").asLong()).isEqualTo(54L);
        assertThat(manifest.get("validatorAccepted").asLong()).isEqualTo(51L);
        assertThat(manifest.get("strictContractAccepted").asLong()).isEqualTo(44L);
        assertThat(manifest.has("profileContentHash")).isTrue();
        assertThat(manifest.get("profileContentHash").asText()).isNotBlank();

        JsonNode unresCounts = manifest.get("unresolvedEnumCounts");
        assertThat(unresCounts).isNotNull();
        assertThat(unresCounts.get("channel=web").asLong()).isEqualTo(6L);
        assertThat(unresCounts.get("change_type=restock").asLong()).isEqualTo(1L);

        // 7. 校验伴随清单 (landing/manifests/30.mapper.json)
        Path companionFile = tempLanding.resolve("manifests/30.mapper.json");
        assertThat(Files.isRegularFile(companionFile)).isTrue();
        JsonNode companion = objectMapper.readTree(Files.readString(companionFile, StandardCharsets.UTF_8));
        assertThat(companion.get("manifestType").asText()).isEqualTo("MAPPER_DIAGNOSTIC");
        assertThat(companion.get("batchId").asLong()).isEqualTo(30L);
        assertThat(companion.get("totalRows").asLong()).isEqualTo(55L);
        assertThat(companion.get("canonicalRows").asLong()).isEqualTo(54L);
        assertThat(companion.get("validatorAccepted").asLong()).isEqualTo(51L);
        assertThat(companion.get("strictContractAccepted").asLong()).isEqualTo(44L);

        // 8. 校验 accepted 目录下的落盘文件 (accepted/30/golden-20260901.jsonl)
        Path acceptedFile = tempLanding.resolve("accepted/30/golden-20260901.jsonl");
        assertThat(Files.isRegularFile(acceptedFile)).isTrue();
        List<String> acceptedLines = Files.readAllLines(acceptedFile, StandardCharsets.UTF_8);
        assertThat(acceptedLines).hasSize(51);

        for (String line : acceptedLines) {
            JsonNode rec = objectMapper.readTree(line);
            assertThat(rec.has("event_id")).isTrue();
            assertThat(rec.has("event_type")).isTrue();
            assertThat(rec.has("event_time")).isTrue();
            assertThat(rec.has("ingest_time")).isTrue();
            assertThat(rec.has("source_system")).isTrue();
            assertThat(rec.has("schema_version")).isTrue();
            assertThat(rec.has("trace_id")).isTrue();
            assertThat(rec.has("payload")).isTrue();
            assertThat(rec.get("payload").isObject()).isTrue();
        }

        // 9. 校验 quarantine 目录下的落盘文件与原因区分
        Path quarantineFile = tempLanding.resolve("quarantine/30/golden-20260901.jsonl");
        assertThat(Files.isRegularFile(quarantineFile)).isTrue();
        List<String> quarantineLines = Files.readAllLines(quarantineFile, StandardCharsets.UTF_8);
        assertThat(quarantineLines).hasSize(4);

        // 4 行隔离的原始行包含：Line 38(purchase), Line 53(malformed JSON), Line 54(schema_version 2.0), Line 55(missing event_id)
        assertThat(quarantineLines).anyMatch(l -> l.contains("not-valid-json-line-with-no-braces-{{{"));
        assertThat(quarantineLines).anyMatch(l -> l.contains("\"behavior_type\":\"purchase\""));
        assertThat(quarantineLines).anyMatch(l -> l.contains("\"schema_version\":\"2.0\""));
        assertThat(quarantineLines).anyMatch(l -> l.contains("golden-trace-055") && !l.contains("\"event_id\""));

        // 10. 校验 QuarantineRecord 隔离原因区分（mapper 隔离 vs validator 拦截）
        assertThat(quarantineRecords).hasSize(4);

        // (a) Mapper 隔离原因码 (JSON_PARSE_ERROR)
        assertThat(quarantineRecords)
                .filteredOn(r -> r.getReason().contains("JSON_PARSE_ERROR"))
                .hasSize(1);

        // (b) Validator 拦截原因
        assertThat(quarantineRecords)
                .filteredOn(r -> r.getReason().contains("非法 behavior_type: purchase"))
                .hasSize(1);
        assertThat(quarantineRecords)
                .filteredOn(r -> r.getReason().contains("未支持 schema_version: 2.0"))
                .hasSize(1);
        assertThat(quarantineRecords)
                .filteredOn(r -> r.getReason().contains("缺失必要字段: event_id"))
                .hasSize(1);

        // 11. 校验 Checkpoint 推进至文件末尾
        FileCheckpoint cp = checkpointStore.get(LocalFileIngestor.checkpointKey(goldenFile));
        assertThat(cp).isNotNull();
        assertThat(cp.getNextOffset()).isEqualTo(Files.size(goldenFile));
    }
}
