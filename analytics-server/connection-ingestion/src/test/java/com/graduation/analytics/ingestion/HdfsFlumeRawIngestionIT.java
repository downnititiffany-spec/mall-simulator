package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.entity.IngestionBatchFile;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper;
import com.graduation.analytics.landing.LandingLayout;
import com.graduation.analytics.mapping.ingest.SourceMapper;
import com.graduation.analytics.mapping.ingest.SourceMapping;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.storage.HdfsLandingStorage;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import com.graduation.analytics.source.entity.SourceRegistry;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Consumes a file emitted by a live Flume HDFS sink through the real HDFS FLUME_RAW scanner. */
class HdfsFlumeRawIngestionIT {

    private static final long RUNTIME_PROFILE_ID = 5L;
    private static final long SOURCE_ID = 7L;
    private static final long BATCH_ID = 908002L;

    @BeforeAll
    static void initLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FileCheckpoint.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                IngestionBatchFile.class);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LANDING_FLUME_IT_URI", matches = "hdfs://.+")
    @EnabledIfEnvironmentVariable(named = "LANDING_FLUME_IT_FILE", matches = "hdfs://.+")
    @DisplayName("真实 Flume HDFS Sink 文件经 FLUME_RAW 递归采集并生成 READY manifest")
    void consumesRealFlumeOutputFromHdfsRawLayout() throws Exception {
        URI baseUri = URI.create(System.getenv("LANDING_FLUME_IT_URI").trim());
        Path flumeOutput = new Path(URI.create(System.getenv("LANDING_FLUME_IT_FILE").trim()));
        String runId = "flume-ingest-it-" + UUID.randomUUID();
        URI runUri = URI.create(baseUri.toString().replaceAll("/+$", "") + "/" + runId);
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", baseUri.getScheme() + "://" + baseUri.getAuthority());
        // Copying the fixture must not close Hadoop's cached FileSystem instance used by
        // HdfsLandingStorage below; otherwise the service's later manifest/checkpoint probes
        // fail with "Filesystem closed" even though the copied input itself is valid.
        conf.set("fs.hdfs.impl.disable.cache", "true");
        HdfsLandingStorage storage = new HdfsLandingStorage(runUri.toString());

        String relativeRawPath = "raw/dt=20260924/hour=21/flume-output.jsonl";
        try (FileSystem fs = FileSystem.get(baseUri, conf)) {
            assertThat(fs.exists(flumeOutput)).as("input must be the preserved output from the real Flume run")
                    .isTrue();
            fs.mkdirs(new Path(runUri));
            try (InputStream input = fs.open(flumeOutput);
                 OutputStream copy = fs.create(new Path(new Path(runUri), relativeRawPath), false)) {
                input.transferTo(copy);
            }
        }

        FileCheckpointMapper checkpoints = mock(FileCheckpointMapper.class);
        QuarantineRecordMapper quarantines = mock(QuarantineRecordMapper.class);
        IngestionBatchMapper batches = mock(IngestionBatchMapper.class);
        IngestionBatchFileMapper batchFiles = mock(IngestionBatchFileMapper.class);
        RuntimeProfileService profiles = mock(RuntimeProfileService.class);
        SourceRegistryService sources = mock(SourceRegistryService.class);
        SourceMapper sourceMapper = mock(SourceMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicBoolean manifestExistedAtCheckpointInsert = new AtomicBoolean();
        AtomicLong checkpointOffset = new AtomicLong(-1L);
        AtomicLong currentBatchId = new AtomicLong(BATCH_ID - 1);
        AtomicReference<FileCheckpoint> persistedCheckpoint = new AtomicReference<>();
        AtomicInteger checkpointInsertCount = new AtomicInteger();

        RuntimeProfile active = new RuntimeProfile();
        active.setId(RUNTIME_PROFILE_ID);
        active.setLandingUri(runUri.toString());
        active.setLandingLayout(LandingLayout.FLUME_RAW.name());
        when(profiles.getActive()).thenReturn(active);
        when(sources.currentSourceId()).thenReturn(Optional.of(SOURCE_ID));
        SourceRegistry sourceRow = IngestionSourceNotBoundTest.sourceRow(SOURCE_ID, "flume-smoke", "1.0");
        when(sources.get(SOURCE_ID)).thenReturn(SourceRegistryView.of(sourceRow, SOURCE_ID));
        when(sourceMapper.prepare(any())).thenReturn(SourceMapping.legacy());
        when(checkpoints.selectOne(any())).thenAnswer(invocation -> persistedCheckpoint.get());
        when(batchFiles.selectCount(any())).thenReturn(0L);
        when(batches.insert(any(IngestionBatch.class))).thenAnswer(invocation -> {
            long batchId = currentBatchId.incrementAndGet();
            invocation.<IngestionBatch>getArgument(0).setId(batchId);
            return 1;
        });
        doAnswer(invocation -> {
            FileCheckpoint saved = invocation.getArgument(0);
            checkpointOffset.set(saved.getNextOffset());
            persistedCheckpoint.set(saved);
            checkpointInsertCount.incrementAndGet();
            manifestExistedAtCheckpointInsert.set(storage.exists("manifests/" + currentBatchId.get() + ".json"));
            return 1;
        }).when(checkpoints).insert(any(FileCheckpoint.class));

        boolean passed = false;
        boolean preserveOutput = Boolean.parseBoolean(System.getenv("LANDING_FLUME_IT_PRESERVE"));
        try {
            LocalFileIngestor ingestor = new LocalFileIngestor(checkpoints, quarantines,
                    new EventContractValidator(objectMapper), objectMapper, sourceMapper);
            EventClock eventClock = new EventClock(Clock.fixed(Instant.parse("2026-09-24T13:24:00Z"),
                    ZoneId.of("Asia/Shanghai")));
            IngestionService service = new IngestionService(batches, batchFiles, ingestor, eventClock,
                    profiles, sources, objectMapper, sourceMapper);

            IngestionService.RunResult result = service.runOne(TraceContext.create());
            IngestionService.RunResult replay = service.runOne(TraceContext.create());

            assertThat(result.status()).isEqualTo(IngestionBatch.STATUS_SUCCESS);
            assertThat(result.recordCount()).isEqualTo(2L);
            assertThat(result.quarantineCount()).isZero();
            assertThat(result.errorCount()).isZero();
            assertThat(result.manifestPath()).isEqualTo(storage.uri("manifests/" + BATCH_ID + ".json"));
            assertThat(manifestExistedAtCheckpointInsert).isTrue();
            assertThat(checkpointOffset.get()).isGreaterThan(0L);
            assertThat(replay.status()).isEqualTo(IngestionBatch.STATUS_SUCCESS);
            assertThat(replay.recordCount()).isZero();
            assertThat(replay.noNewData()).isTrue();
            assertThat(checkpointInsertCount).hasValue(1);
            assertThat(storage.exists("accepted/" + BATCH_ID + "/flume-output.jsonl")).isTrue();
            try (InputStream accepted = storage.open("accepted/" + BATCH_ID + "/flume-output.jsonl")) {
                assertThat(new String(accepted.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("\"event_id\":\"flume-smoke-order-created-20260924-03\"")
                        .contains("\"event_id\":\"flume-smoke-order-paid-20260924-03\"");
            }
            try (InputStream manifest = storage.open("manifests/" + BATCH_ID + ".json")) {
                assertThat(new String(manifest.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("flume-output.jsonl")
                        .contains("\"status\" : \"READY\"");
            }
            passed = true;
        } finally {
            if (!passed || !preserveOutput) {
                try (FileSystem fs = FileSystem.get(baseUri, conf)) {
                    fs.delete(new Path(runUri), true);
                }
            } else {
                System.out.println("LANDING_FLUME_IT_PRESERVED_ACCEPTED_URI="
                        + runUri + "/accepted/" + BATCH_ID);
            }
        }
    }
}
