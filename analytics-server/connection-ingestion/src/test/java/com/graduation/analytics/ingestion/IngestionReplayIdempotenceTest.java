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
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WP-2B 交付项 3 & 4 行为测试：重放幂等性保证（Replay / Idempotence）。
 *
 * <p>保证机制（Replay Guarantee Mechanism）：
 * <ul>
 *   <li>基于 {@link FileCheckpoint} 的字节偏移（byte offset）机制；</li>
 *   <li>断点持久化键按 (runtime_profile_id, source_id, file_path, file_identity) 严格隔离；</li>
 *   <li>当对同源、同画像、同一文件进行重放时，{@code LocalFileIngestor} 检测到断点已存在且
 *       {@code startOffset >= size}，直接判定无新数据（{@code noNewData = true}），
 *       立即返回 0 接受、0 隔离，不追加写入任何已入库数据，从而从源头消除重复入库。</li>
 * </ul>
 * </p>
 */
class IngestionReplayIdempotenceTest {

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FileCheckpoint.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), IngestionBatch.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), IngestionBatchFile.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QuarantineRecord.class);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventClock clock = new EventClock(
            Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneId.of("Asia/Shanghai")));

    private final FileCheckpointMapper checkpointMapper = mock(FileCheckpointMapper.class);
    private final QuarantineRecordMapper quarantineRecordMapper = mock(QuarantineRecordMapper.class);
    private final IngestionBatchMapper batchMapper = mock(IngestionBatchMapper.class);
    private final IngestionBatchFileMapper batchFileMapper = mock(IngestionBatchFileMapper.class);
    private final RuntimeProfileService runtimeProfileService = mock(RuntimeProfileService.class);
    private final SourceRegistryService sourceRegistryService = mock(SourceRegistryService.class);

    private final Map<String, FileCheckpoint> checkpointStore = new HashMap<>();
    private final List<QuarantineRecord> quarantineRecords = new ArrayList<>();
    private final List<IngestionBatchFile> batchFiles = new ArrayList<>();
    private final AtomicLong batchIdSequence = new AtomicLong(100L);

    private Path repoRoot() {
        return RepoPathLocator.locateRepoRoot(".");
    }

    @Test
    @DisplayName("同一文件同源重复采集：第二批次零条入库、不重复接收、检查点偏移保持不变")
    void replayIngestionDoesNotDoubleAcceptRecords(@TempDir Path tempLanding, @TempDir Path tempInput) throws IOException {
        // 准备待采集文件
        Path testFile = tempInput.resolve("user-events-stream.jsonl");
        String initialContent = """
                {"event_id":"evt-replay-001","event_type":"user_registered","event_time":"2026-09-01T09:00:00+08:00","ingest_time":"2026-09-01T09:00:01+08:00","source_system":"mock-mall","schema_version":"1.0","trace_id":"trace-replay-001","payload":{"user_id":"101","age_group":"25-34","city_level":"tier1","member_level":"gold","register_time":"2026-09-01T09:00:00+08:00"}}
                {"event_id":"evt-replay-002","event_type":"user_registered","event_time":"2026-09-01T09:00:00+08:00","ingest_time":"2026-09-01T09:00:01+08:00","source_system":"mock-mall","schema_version":"1.0","trace_id":"trace-replay-002","payload":{"user_id":"102","age_group":"35-44","city_level":"tier2","member_level":"silver","register_time":"2026-09-01T09:00:00+08:00"}}
                """;
        Files.writeString(testFile, initialContent, StandardCharsets.UTF_8);
        long fileSizeBefore = Files.size(testFile);

        // 1. 设置运行时与 mock-mall 源
        RuntimeProfile runtime = new RuntimeProfile();
        runtime.setId(1L);
        runtime.setLandingUri(tempLanding.toUri().toString());
        when(runtimeProfileService.getActive()).thenReturn(runtime);
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(runtime));

        SourceRegistryView mockMallView = new SourceRegistryView(
                1L, "mock-mall", "参考商城", "FILE",
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
            String targetKey = LocalFileIngestor.checkpointKey(testFile);
            return checkpointStore.get(targetKey);
        });

        when(quarantineRecordMapper.insert(any(QuarantineRecord.class))).thenAnswer(inv -> {
            quarantineRecords.add(inv.getArgument(0));
            return 1;
        });

        when(batchMapper.insert(any(IngestionBatch.class))).thenAnswer(inv -> {
            IngestionBatch b = inv.getArgument(0);
            b.setId(batchIdSequence.incrementAndGet());
            return 1;
        });
        when(batchMapper.updateById(any(IngestionBatch.class))).thenReturn(1);

        when(batchFileMapper.insert(any(IngestionBatchFile.class))).thenAnswer(inv -> {
            batchFiles.add(inv.getArgument(0));
            return 1;
        });

        // 3. 构建采集组件
        EventContractValidator validator = new EventContractValidator(objectMapper);
        PureMapper pureMapper = new PureMapper();
        LocalFileIngestor ingestor = new LocalFileIngestor(checkpointMapper, quarantineRecordMapper, validator, objectMapper, pureMapper);
        IngestionService ingestionService = new IngestionService(batchMapper, batchFileMapper, ingestor, clock,
                runtimeProfileService, sourceRegistryService, objectMapper);

        // 4. 第一轮正常采集（First Run）
        IngestionService.RunResult firstRun = ingestionService.ingestFileForSource(testFile, "mock-mall", TraceContext.create());
        long firstBatchId = firstRun.batchId();

        assertThat(firstRun.recordCount()).isEqualTo(2L);
        assertThat(firstRun.quarantineCount()).isEqualTo(0L);
        assertThat(firstRun.noNewData()).isFalse();

        FileCheckpoint checkpointAfterFirst = checkpointStore.get(LocalFileIngestor.checkpointKey(testFile));
        assertThat(checkpointAfterFirst).isNotNull();
        assertThat(checkpointAfterFirst.getNextOffset()).isEqualTo(fileSizeBefore);

        Path firstBatchAccepted = tempLanding.resolve("accepted").resolve(String.valueOf(firstBatchId))
                .resolve(testFile.getFileName().toString());
        assertThat(Files.isRegularFile(firstBatchAccepted)).isTrue();
        assertThat(Files.readAllLines(firstBatchAccepted, StandardCharsets.UTF_8)).hasSize(2);

        // 5. 第二轮重放采集（Replay Run，文件无变化）
        IngestionService.RunResult replayRun = ingestionService.ingestFileForSource(testFile, "mock-mall", TraceContext.create());
        long replayBatchId = replayRun.batchId();

        // 断言重放结果：零记录采集，noNewData 为 true
        assertThat(replayRun.recordCount()).isEqualTo(0L);
        assertThat(replayRun.quarantineCount()).isEqualTo(0L);
        assertThat(replayRun.noNewData()).isTrue();

        // 断言检查点偏移保持不变，未被重复推高或损坏
        FileCheckpoint checkpointAfterReplay = checkpointStore.get(LocalFileIngestor.checkpointKey(testFile));
        assertThat(checkpointAfterReplay.getNextOffset()).isEqualTo(fileSizeBefore);

        // 断言第二批次落盘区域没有重复记录（文件不存在或内容为空）
        Path replayBatchAccepted = tempLanding.resolve("accepted").resolve(String.valueOf(replayBatchId))
                .resolve(testFile.getFileName().toString());
        if (Files.exists(replayBatchAccepted)) {
            assertThat(Files.readAllLines(replayBatchAccepted, StandardCharsets.UTF_8)).isEmpty();
        }

        // 断言第二批次主清单中明确体现 0 条接受记录
        Path replayManifest = tempLanding.resolve("manifests").resolve(replayBatchId + ".json");
        assertThat(Files.isRegularFile(replayManifest)).isTrue();
        JsonNode manifestNode = objectMapper.readTree(Files.readString(replayManifest, StandardCharsets.UTF_8));
        assertThat(manifestNode.get("acceptedRecords").asLong()).isEqualTo(0L);
        assertThat(manifestNode.get("quarantinedRecords").asLong()).isEqualTo(0L);

        // 6. 增量追加场景校验（验证检查点继续正常消费新追加行，且不重复消费旧行）
        String appendedLine = "{\"event_id\":\"evt-replay-003\",\"event_type\":\"user_registered\",\"event_time\":\"2026-09-01T09:00:00+08:00\",\"ingest_time\":\"2026-09-01T09:00:01+08:00\",\"source_system\":\"mock-mall\",\"schema_version\":\"1.0\",\"trace_id\":\"trace-replay-003\",\"payload\":{\"user_id\":\"103\",\"age_group\":\"18-24\",\"city_level\":\"tier3\",\"member_level\":\"normal\",\"register_time\":\"2026-09-01T09:00:00+08:00\"}}\n";
        Files.writeString(testFile, appendedLine, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        long fileSizeAfterAppend = Files.size(testFile);

        IngestionService.RunResult appendRun = ingestionService.ingestFileForSource(testFile, "mock-mall", TraceContext.create());
        long appendBatchId = appendRun.batchId();

        // 仅采集新增的 1 条记录，旧的 2 条记录不重复采集
        assertThat(appendRun.recordCount()).isEqualTo(1L);
        assertThat(appendRun.quarantineCount()).isEqualTo(0L);
        assertThat(appendRun.noNewData()).isFalse();

        FileCheckpoint checkpointAfterAppend = checkpointStore.get(LocalFileIngestor.checkpointKey(testFile));
        assertThat(checkpointAfterAppend.getNextOffset()).isEqualTo(fileSizeAfterAppend);

        Path appendBatchAccepted = tempLanding.resolve("accepted").resolve(String.valueOf(appendBatchId))
                .resolve(testFile.getFileName().toString());
        assertThat(Files.isRegularFile(appendBatchAccepted)).isTrue();
        List<String> appendAcceptedLines = Files.readAllLines(appendBatchAccepted, StandardCharsets.UTF_8);
        assertThat(appendAcceptedLines).hasSize(1);
        JsonNode appendedAcceptedRecord = objectMapper.readTree(appendAcceptedLines.get(0));
        assertThat(appendedAcceptedRecord.get("payload").get("user_id").asText()).isEqualTo("103");
    }
}
