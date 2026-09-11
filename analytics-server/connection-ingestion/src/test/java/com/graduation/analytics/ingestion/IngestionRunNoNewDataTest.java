package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-08 / D-022 候选①（最小明示）在**采集运行结果**上的口径（L0，不连库）。
 *
 * <p>钉住：数据源停机时，平台不能再只回一个 {@code SUCCESS + 0 条}。本次**没有读到任何新字节**时
 * {@code noNewData=true}；判定逻辑（批次状态）不变，也不探活生产者。</p>
 *
 * <p>关键区分：{@code noNewData} 看的是**断点是否推进**（{@code endOffset > startOffset}），
 * 而不是"产出了多少条记录"——全是坏行的文件同样证明数据源在产出，绝不能报"无新数据"。</p>
 */
class IngestionRunNoNewDataTest {

    private final IngestionBatchMapper batchMapper = mock(IngestionBatchMapper.class);
    private final RuntimeProfileService runtimeProfileService = mock(RuntimeProfileService.class);
    private final LocalFileIngestor ingestor = mock(LocalFileIngestor.class);
    private final EventClock clock = new EventClock(
            Clock.fixed(Instant.parse("2026-09-12T02:00:00Z"), ZoneId.of("Asia/Shanghai")));

    private IngestionService service() {
        return new IngestionService(batchMapper, mock(IngestionBatchFileMapper.class),
                ingestor, clock, runtimeProfileService, new ObjectMapper());
    }

    private Path prepareLanding(Path landingRoot) throws IOException {
        Path events = Files.createDirectories(landingRoot.resolve("events"));
        Files.writeString(events.resolve("events-001.jsonl"), "{\"eventId\":\"e1\"}\n", StandardCharsets.UTF_8);
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri(landingRoot.toUri().toString());
        when(runtimeProfileService.getActive()).thenReturn(profile);
        when(batchMapper.insert(any(IngestionBatch.class))).thenAnswer(inv -> {
            inv.<IngestionBatch>getArgument(0).setId(101L);
            return 1;
        });
        return events;
    }

    private void stubIngest(long startOffset, long endOffset, long collected, long quarantined) {
        when(ingestor.ingestFile(any(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new LocalFileIngestor.FileResult("events-001.jsonl", startOffset, endOffset,
                        "identity-1", collected, quarantined, collected > 0 ? 64L : 0L, Set.of("1.0")));
    }

    @Test
    @DisplayName("数据源未产出（断点未推进）时：noNewData=true，且状态判定不变（SUCCESS）")
    void reportsNoNewDataWhenSourceProducedNothing(@TempDir Path landingRoot) throws IOException {
        prepareLanding(landingRoot);
        stubIngest(120L, 120L, 0, 0);   // 文件已在上一轮采完：startOffset == endOffset

        IngestionService.RunResult result = service().runOne(TraceContext.create());

        assertThat(result.noNewData()).isTrue();
        assertThat(result.recordCount()).isZero();
        assertThat(result.fileCount()).isZero();
        assertThat(result.status()).isEqualTo(IngestionBatch.STATUS_SUCCESS);
        assertThat(result.manifestPath()).isNotNull();
    }

    @Test
    @DisplayName("有新字节时：noNewData=false，记录数与文件数如实统计")
    void reportsNewDataWhenBytesRead(@TempDir Path landingRoot) throws IOException {
        prepareLanding(landingRoot);
        stubIngest(0L, 120L, 3, 0);

        IngestionService.RunResult result = service().runOne(TraceContext.create());

        assertThat(result.noNewData()).isFalse();
        assertThat(result.recordCount()).isEqualTo(3L);
        assertThat(result.fileCount()).isEqualTo(1);
        assertThat(result.acceptedBytes()).isEqualTo(64L);
        assertThat(result.status()).isEqualTo(IngestionBatch.STATUS_SUCCESS);
    }

    @Test
    @DisplayName("新字节全是坏行时：仍属「数据源在产出」，不得报 noNewData，状态为 QUARANTINED")
    void quarantinedOnlyIsStillNewData(@TempDir Path landingRoot) throws IOException {
        prepareLanding(landingRoot);
        stubIngest(0L, 120L, 0, 4);   // 断点推进了，但一行没通过校验

        IngestionService.RunResult result = service().runOne(TraceContext.create());

        assertThat(result.noNewData()).isFalse();
        assertThat(result.quarantineCount()).isEqualTo(4L);
        assertThat(result.status()).isEqualTo(IngestionBatch.STATUS_QUARANTINED);
    }

    @Test
    @DisplayName("无 events 文件时：noNewData=true（空目录同样是「数据源没有新数据」）")
    void emptyEventsDirIsNoNewData(@TempDir Path landingRoot) throws IOException {
        Files.createDirectories(landingRoot.resolve("events"));
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri(landingRoot.toUri().toString());
        when(runtimeProfileService.getActive()).thenReturn(profile);
        when(batchMapper.insert(any(IngestionBatch.class))).thenAnswer(inv -> {
            inv.<IngestionBatch>getArgument(0).setId(102L);
            return 1;
        });

        IngestionService.RunResult result = service().runOne(TraceContext.create());

        assertThat(result.noNewData()).isTrue();
        assertThat(result.fileCount()).isZero();
        assertThat(result.batchNo()).startsWith("ing-");
        assertThat(LocalDateTime.parse(result.batchNo().substring(4, 18),
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
                .isEqualTo(LocalDateTime.of(2026, 9, 12, 10, 0));   // 固定时钟：不把系统时间当业务时间
    }
}
