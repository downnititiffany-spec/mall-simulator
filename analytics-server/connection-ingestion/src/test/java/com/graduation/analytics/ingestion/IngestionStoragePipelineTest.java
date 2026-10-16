package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.entity.IngestionBatchFile;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.mapping.ingest.SourceMapper;
import com.graduation.analytics.mapping.ingest.SourceMapping;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.storage.LandingStorage;
import com.graduation.analytics.runtime.storage.LocalLandingStorage;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import com.graduation.analytics.source.entity.SourceRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionStoragePipelineTest {

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FileCheckpoint.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                IngestionBatchFile.class);
    }

    @Test
    @DisplayName("远端 storage 分支贯穿扫描、批次 manifest 与 checkpoint 提交")
    void remoteStorageBranchUsesStorageContracts(@TempDir Path landingRoot) throws Exception {
        Files.createDirectories(landingRoot.resolve("events"));
        Files.writeString(landingRoot.resolve("events/events-1.jsonl"), "{\"eventId\":\"e1\"}\n",
                StandardCharsets.UTF_8);

        IngestionBatchMapper batches = mock(IngestionBatchMapper.class);
        IngestionBatchFileMapper batchFiles = mock(IngestionBatchFileMapper.class);
        LocalFileIngestor ingestor = mock(LocalFileIngestor.class);
        RuntimeProfileService profiles = mock(RuntimeProfileService.class);
        SourceRegistryService sources = mock(SourceRegistryService.class);
        SourceMapper sourceMapper = mock(SourceMapper.class);
        LandingStorage storage = new HdfsShapedTestStorage(landingRoot.toString());

        RuntimeProfile active = new RuntimeProfile();
        active.setId(5L);
        active.setLandingUri("hdfs://test-namenode:8020/landing");
        when(profiles.getActive()).thenReturn(active);
        when(sourceMapper.prepare(any())).thenReturn(SourceMapping.legacy());
        when(sources.currentSourceId()).thenReturn(Optional.of(7L));
        SourceRegistry row = IngestionSourceNotBoundTest.sourceRow(7L, "test-shop", "1.0");
        when(sources.get(7L)).thenReturn(SourceRegistryView.of(row, 7L));
        when(batches.insert(any(IngestionBatch.class))).thenAnswer(invocation -> {
            invocation.<IngestionBatch>getArgument(0).setId(301L);
            return 1;
        });
        when(batchFiles.selectCount(any())).thenReturn(0L);
        when(ingestor.ingestFileDeferredCheckpoint(eq(storage), eq("events/events-1.jsonl"), anyLong(),
                anyLong(), anyLong(), eq("accepted/301"), eq("quarantine/301"), any(), any(), any()))
                .thenReturn(new LocalFileIngestor.FileResult("events-1.jsonl", 0, 20,
                        "hdfs-checksum-1", 1, 0, 20L, Set.of("1.0")));

        EventClock clock = new EventClock(Clock.fixed(Instant.parse("2026-09-24T01:00:00Z"),
                ZoneId.of("Asia/Shanghai")));
        IngestionService service = new IngestionService(batches, batchFiles, ingestor, clock,
                profiles, sources, new ObjectMapper(), sourceMapper);

        IngestionService.RunResult result = service.runOne(TraceContext.create(), storage);

        assertThat(result.status()).isEqualTo(IngestionBatch.STATUS_SUCCESS);
        assertThat(result.manifestPath()).isEqualTo("hdfs://test-namenode:8020/landing/manifests/301.json");
        assertThat(Files.isRegularFile(landingRoot.resolve("manifests/301.json"))).isTrue();
        verify(ingestor).commitCheckpoint(storage, "events/events-1.jsonl", 5L, 7L,
                new LocalFileIngestor.FileResult("events-1.jsonl", 0, 20,
                        "hdfs-checksum-1", 1, 0, 20L, Set.of("1.0")));
    }

    /** Local files underneath, but remote type/key/URI semantics: exercises orchestration without NameNode. */
    private static final class HdfsShapedTestStorage extends LocalLandingStorage {
        private HdfsShapedTestStorage(String root) {
            super(root);
        }

        @Override public String type() { return "hdfs"; }
        @Override public String namespace() { return "hdfs://test-namenode:8020/landing"; }
        @Override public String checkpointKey(String relativePath) {
            return namespace() + "|/" + relativePath;
        }
        @Override public String uri(String relativePath) {
            return namespace() + "/" + relativePath;
        }
    }
}
