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
 * WP-2B 交付项 4 行为测试：fixture-b 源画像端到端采集测试。
 *
 * <p>验证：
 * <ul>
 *   <li>fixture-b 原生异构字段输入（msg_id、msg_kind、occurred_ms 等）；</li>
 *   <li>FEN 转 YUAN（/100 精确除法）、时间戳毫秒归一化 ISO、@keep 扩展字段保留在 payload；</li>
 *   <li>Q6 闸门判定：mapper 规范化 ∧ 契约校验；</li>
 *   <li>accepted 输出为 envelope+payload 结构；</li>
 *   <li>quarantine 区分 mapper 违规（UNKNOWN_EVENT_TYPE）与 validator 违规（非法 behavior_type）。</li>
 * </ul>
 * </p>
 */
class ProfileDrivenIngestionFixtureBTest {

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FileCheckpoint.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), IngestionBatch.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), IngestionBatchFile.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QuarantineRecord.class);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventClock clock = new EventClock(
            Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneId.of("Asia/Shanghai")));

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
    @DisplayName("fixture-b 原生输入采集：接受 2 行（envelope+payload 形状），隔离 2 行（mapper 与 validator 分流）")
    void fixtureBIngestionEndToEnd(@TempDir Path tempLanding) throws IOException {
        Path sampleFile = repoRoot().resolve("analytics-server/connection-ingestion/src/test/resources/fixture-b/sample-events.jsonl");
        if (!Files.isRegularFile(sampleFile)) {
            // 兜底相对定位
            sampleFile = Path.of("src/test/resources/fixture-b/sample-events.jsonl");
        }
        assertThat(Files.isRegularFile(sampleFile)).as("fixture-b 样本文件必须存在: %s", sampleFile).isTrue();
        final Path samplePath = sampleFile;

        RuntimeProfile runtime = new RuntimeProfile();
        runtime.setId(1L);
        runtime.setLandingUri(tempLanding.toUri().toString());
        when(runtimeProfileService.getActive()).thenReturn(runtime);
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(runtime));

        SourceRegistryView fixtureBView = new SourceRegistryView(
                2L, "fixture-b", "测试商城 B", "FILE",
                "analytics-server/source-profiles/fixture-b.v1.json",
                "Asia/Shanghai", "CNY", "ACTIVE", "1.0", true,
                LocalDateTime.now(), LocalDateTime.now(), "fb"
        );
        when(sourceRegistryService.currentSourceId()).thenReturn(Optional.of(2L));
        when(sourceRegistryService.get(2L)).thenReturn(fixtureBView);
        when(sourceRegistryService.list()).thenReturn(List.of(fixtureBView));

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
            return checkpointStore.get(LocalFileIngestor.checkpointKey(samplePath));
        });

        when(quarantineRecordMapper.insert(any(QuarantineRecord.class))).thenAnswer(inv -> {
            quarantineRecords.add(inv.getArgument(0));
            return 1;
        });

        when(batchMapper.insert(any(IngestionBatch.class))).thenAnswer(inv -> {
            IngestionBatch b = inv.getArgument(0);
            b.setId(42L);
            return 1;
        });
        when(batchMapper.updateById(any(IngestionBatch.class))).thenReturn(1);

        when(batchFileMapper.insert(any(IngestionBatchFile.class))).thenAnswer(inv -> {
            batchFiles.add(inv.getArgument(0));
            return 1;
        });

        EventContractValidator validator = new EventContractValidator(objectMapper);
        PureMapper pureMapper = new PureMapper();
        LocalFileIngestor ingestor = new LocalFileIngestor(checkpointMapper, quarantineRecordMapper, validator, objectMapper, pureMapper);
        IngestionService ingestionService = new IngestionService(batchMapper, batchFileMapper, ingestor, clock,
                runtimeProfileService, sourceRegistryService, objectMapper);

        // 执行单文件采集
        IngestionService.RunResult result = ingestionService.ingestFileForSource(sampleFile, "fixture-b", TraceContext.create());

        // 校验批次结果
        assertThat(result.batchId()).isEqualTo(42L);
        assertThat(result.status()).isEqualTo("QUARANTINED");
        assertThat(result.recordCount()).isEqualTo(2L);
        assertThat(result.quarantineCount()).isEqualTo(2L);

        // 校验主清单
        Path manifestFile = tempLanding.resolve("manifests/42.json");
        assertThat(Files.isRegularFile(manifestFile)).isTrue();
        JsonNode manifest = objectMapper.readTree(Files.readString(manifestFile, StandardCharsets.UTF_8));
        assertThat(manifest.get("batchId").asLong()).isEqualTo(42L);
        assertThat(manifest.get("sourceCode").asText()).isEqualTo("fixture-b");
        assertThat(manifest.get("acceptedRecords").asLong()).isEqualTo(2L);
        assertThat(manifest.get("quarantinedRecords").asLong()).isEqualTo(2L);
        assertThat(manifest.get("totalRows").asLong()).isEqualTo(4L);
        assertThat(manifest.get("canonicalRows").asLong()).isEqualTo(3L);
        assertThat(manifest.get("validatorAccepted").asLong()).isEqualTo(2L);
        assertThat(manifest.get("strictContractAccepted").asLong()).isEqualTo(1L);
        assertThat(manifest.get("unresolvedEnumCounts").get("channel=MINI_PROG").asLong()).isEqualTo(1L);

        // 校验伴随清单
        Path companionFile = tempLanding.resolve("manifests/42.mapper.json");
        assertThat(Files.isRegularFile(companionFile)).isTrue();
        JsonNode companion = objectMapper.readTree(Files.readString(companionFile, StandardCharsets.UTF_8));
        assertThat(companion.get("manifestType").asText()).isEqualTo("MAPPER_DIAGNOSTIC");
        assertThat(companion.get("totalRows").asLong()).isEqualTo(4L);
        assertThat(companion.get("canonicalRows").asLong()).isEqualTo(3L);

        // 校验 accepted 记录形态：必须是 envelope + payload 规范结构
        Path acceptedFile = tempLanding.resolve("accepted/42/sample-events.jsonl");
        assertThat(Files.isRegularFile(acceptedFile)).isTrue();
        List<String> acceptedLines = Files.readAllLines(acceptedFile, StandardCharsets.UTF_8);
        assertThat(acceptedLines).hasSize(2);

        JsonNode rec1 = objectMapper.readTree(acceptedLines.get(0));
        assertThat(rec1.get("event_id").asText()).isEqualTo("fb-msg-001");
        assertThat(rec1.get("event_type").asText()).isEqualTo("behavior");
        assertThat(rec1.get("source_system").asText()).isEqualTo("fixture-b");
        assertThat(rec1.get("schema_version").asText()).isEqualTo("1.0");
        assertThat(rec1.path("payload").get("user_id").asText()).isEqualTo("buyer-123");
        assertThat(rec1.path("payload").get("product_id").asText()).isEqualTo("sku-456");
        assertThat(rec1.path("payload").get("behavior_type").asText()).isEqualTo("view");
        assertThat(rec1.path("payload").get("channel").asText()).isEqualTo("MINI_PROG");
        assertThat(rec1.path("payload").get("ext_coupon_code").asText()).isEqualTo("PROMO_FIXTURE_B");

        JsonNode rec2 = objectMapper.readTree(acceptedLines.get(1));
        assertThat(rec2.get("event_id").asText()).isEqualTo("fb-msg-002");
        assertThat(rec2.get("event_type").asText()).isEqualTo("order_created");
        assertThat(rec2.path("payload").get("order_id").asText()).isEqualTo("ord-fb-100");
        assertThat(rec2.path("payload").get("total_amount").asText()).isEqualTo("2042.00");
        assertThat(rec2.path("payload").get("items")).hasSize(1);
        assertThat(rec2.path("payload").get("items").get(0).get("unit_price").asText()).isEqualTo("1021.00");

        // 校验隔离原因清晰区分：1 个 UNKNOWN_EVENT_TYPE (mapper 隔离), 1 个非法 behavior_type (validator 拦截)
        assertThat(quarantineRecords).hasSize(2);
        assertThat(quarantineRecords).anyMatch(r -> r.getReason().contains("UNKNOWN_EVENT_TYPE"));
        assertThat(quarantineRecords).anyMatch(r -> r.getReason().contains("非法 behavior_type: PURCHASE"));
    }
}
