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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in profile-to-ingestion real-HDFS test; only a run-scoped HDFS namespace is written. */
class HdfsIngestionServiceIT {

    private static final long RUNTIME_PROFILE_ID = 5L;
    private static final long SOURCE_ID = 7L;
    private static final long BATCH_ID = 908001L;

    @BeforeAll
    static void initLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FileCheckpoint.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                IngestionBatchFile.class);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LANDING_HDFS_IT_URI", matches = "hdfs://.+")
    @DisplayName("真实 HDFS 输入经批次编排采集，READY manifest 发布后才提交 checkpoint")
    void ingestsOneCanonicalEventFromHdfsAndCommitsAfterManifest() throws Exception {
        URI baseUri = URI.create(System.getenv("LANDING_HDFS_IT_URI").trim());
        String runId = "ingest-it-" + java.util.UUID.randomUUID();
        URI runUri = URI.create(baseUri.toString().replaceAll("/+$", "") + "/" + runId);
        HdfsLandingStorage storage = new HdfsLandingStorage(runUri.toString());
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", baseUri.getScheme() + "://" + baseUri.getAuthority());

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

        RuntimeProfile active = new RuntimeProfile();
        active.setId(RUNTIME_PROFILE_ID);
        active.setLandingUri(runUri.toString());
        active.setLandingLayout("ROLLING_LOG");
        when(profiles.getActive()).thenReturn(active);
        when(sources.currentSourceId()).thenReturn(Optional.of(SOURCE_ID));
        SourceRegistry sourceRow = IngestionSourceNotBoundTest.sourceRow(SOURCE_ID, "hdfs-smoke", "1.0");
        when(sources.get(SOURCE_ID)).thenReturn(SourceRegistryView.of(sourceRow, SOURCE_ID));
        when(sourceMapper.prepare(any())).thenReturn(SourceMapping.legacy());
        when(checkpoints.selectOne(any())).thenReturn(null);
        when(batchFiles.selectCount(any())).thenReturn(0L);
        when(batches.insert(any(IngestionBatch.class))).thenAnswer(invocation -> {
            invocation.<IngestionBatch>getArgument(0).setId(BATCH_ID);
            return 1;
        });
        doAnswer(invocation -> {
            FileCheckpoint saved = invocation.getArgument(0);
            checkpointOffset.set(saved.getNextOffset());
            manifestExistedAtCheckpointInsert.set(storage.exists("manifests/" + BATCH_ID + ".json"));
            return 1;
        }).when(checkpoints).insert(any(FileCheckpoint.class));

        String event = "{\"event_id\":\"hdfs-e1\",\"event_type\":\"order_paid\","
                + "\"event_time\":\"2026-09-24T09:00:00+08:00\","
                + "\"ingest_time\":\"2026-09-24T09:01:00+08:00\","
                + "\"source_system\":\"hdfs-smoke\",\"schema_version\":\"1.0\","
                + "\"trace_id\":\"hdfs-it-trace\",\"payload\":{\"order_id\":\"order-1\","
                + "\"user_id\":\"user-1\",\"payment_id\":\"payment-1\",\"amount\":\"19.90\"}}\n";

        try {
            storage.createDirectories("events");
            try (var output = storage.createOrAppend("events/events-001.jsonl")) {
                output.write(event.getBytes(StandardCharsets.UTF_8));
            }

            LocalFileIngestor ingestor = new LocalFileIngestor(checkpoints, quarantines,
                    new EventContractValidator(objectMapper), objectMapper, sourceMapper);
            EventClock eventClock = new EventClock(Clock.fixed(Instant.parse("2026-09-24T01:00:00Z"),
                    ZoneId.of("Asia/Shanghai")));
            IngestionService service = new IngestionService(batches, batchFiles, ingestor, eventClock,
                    profiles, sources, objectMapper, sourceMapper);

            // No storage override: the service must resolve HdfsLandingStorage from ACTIVE landing_uri.
            IngestionService.RunResult result = service.runOne(TraceContext.create());

            assertThat(result.status()).isEqualTo(IngestionBatch.STATUS_SUCCESS);
            assertThat(result.recordCount()).isEqualTo(1L);
            assertThat(result.quarantineCount()).isZero();
            assertThat(result.errorCount()).isZero();
            assertThat(result.manifestPath()).isEqualTo(storage.uri("manifests/" + BATCH_ID + ".json"));
            assertThat(manifestExistedAtCheckpointInsert).isTrue();
            assertThat(checkpointOffset).hasValue(event.getBytes(StandardCharsets.UTF_8).length);
            assertThat(storage.exists("accepted/" + BATCH_ID + "/events-001.jsonl")).isTrue();
            try (InputStream accepted = storage.open("accepted/" + BATCH_ID + "/events-001.jsonl")) {
                assertThat(new String(accepted.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("\"event_id\":\"hdfs-e1\"");
            }
            try (InputStream manifest = storage.open("manifests/" + BATCH_ID + ".json")) {
                assertThat(new String(manifest.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("\"status\" : \"READY\"");
            }
        } finally {
            try (FileSystem fs = FileSystem.get(baseUri, conf)) {
                fs.delete(new Path(runUri), true);
            }
        }
    }
}
