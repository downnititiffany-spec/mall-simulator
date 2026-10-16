package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.ingestion.entity.QuarantineRecord;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper;
import com.graduation.analytics.mapping.MappingProfile;
import com.graduation.analytics.mapping.ingest.MappedLine;
import com.graduation.analytics.mapping.ingest.SourceMapper;
import com.graduation.analytics.mapping.ingest.SourceMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2-02：采集器里的**逐行映射决策**（L0，不连库）。
 *
 * <p>钉住的是"映射器说什么，采集器就落什么盘"，以及两道闸门的**串行关系**：
 * 映射生效时 raw 行先过 {@link SourceMapper}（源侧字段名，契约校验器根本认不得），
 * 映射产物**再**过 {@link EventContractValidator}（canonical 契约）。
 * 若实现改成"raw 先过契约校验"，本源的所有行都会被判"缺失必要字段: event_id"而全量隔离——
 * 那是静默的数据损失，必须由测试挡住。</p>
 *
 * <p>这里用**替身**映射器：本测试考的是采集器，不是映射语义（映射语义由
 * {@code SourceMapperTest} + {@code MappingExecutorTest} 覆盖；真实链路见
 * {@code IngestionServiceMappingTest}）。</p>
 */
class LocalFileIngestorMappingTest {

    private static final String RAW_LINE = "{\"id\":\"e-1\",\"kind\":\"paid_se\"}";
    private static final String CANONICAL_LINE = "{\"event_id\":\"e-1\",\"event_type\":\"order_paid\","
            + "\"event_time\":\"2026-09-21T09:30:00+08:00\",\"ingest_time\":\"2026-09-21T10:20:30+08:00\","
            + "\"source_system\":\"s2-02-raw-a\",\"schema_version\":\"1.0\",\"trace_id\":\"t-1\","
            + "\"payload\":{\"order_id\":\"o-1\"}}";

    private final FileCheckpointMapper checkpointMapper = mock(FileCheckpointMapper.class);
    private final QuarantineRecordMapper quarantineRecordMapper = mock(QuarantineRecordMapper.class);
    private final EventContractValidator validator = mock(EventContractValidator.class);
    private final SourceMapper sourceMapper = mock(SourceMapper.class);

    /** 生效映射（profile 只是签名占位：本测试只走 {@code applied()==true} 这条分支）。 */
    private final SourceMapping applied = SourceMapping.of(mock(MappingProfile.class));

    private LocalFileIngestor ingestor() {
        return new LocalFileIngestor(checkpointMapper, quarantineRecordMapper, validator,
                new ObjectMapper(), sourceMapper);
    }

    private static Path rawFile(Path dir, String line) throws IOException {
        Path events = Files.createDirectories(dir.resolve("events"));
        Path file = events.resolve("pay-001.jsonl");
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("映射生效：canonical 行（而非 raw 行）落盘，且契约校验的是映射产物")
    void mappedLineIsRevalidatedAndLandedAsCanonical(@TempDir Path dir) throws IOException {
        Path raw = rawFile(dir, RAW_LINE);
        when(sourceMapper.map(applied, RAW_LINE)).thenReturn(MappedLine.accepted(CANONICAL_LINE));

        LocalFileIngestor.FileResult result = ingestor().ingestFile(raw, 9L, 1L, 1L,
                dir.resolve("accepted"), dir.resolve("quarantine"), TraceContext.create(), new CRC32(),
                applied);

        assertThat(Files.readString(dir.resolve("accepted").resolve("pay-001.jsonl"), StandardCharsets.UTF_8))
                .as("落盘的必须是映射产物，不是原始行")
                .isEqualTo(CANONICAL_LINE + "\n");
        assertThat(result.collected()).isEqualTo(1);
        assertThat(result.quarantined()).isZero();
        assertThat(result.acceptedBytes())
                .isEqualTo((CANONICAL_LINE + "\n").getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.schemaVersions())
                .as("schemaVersions 取自**落盘文本**：映射产物才是被 canonical 契约认账的那一行")
                .containsExactly("1.0");
        verify(validator).check(eq(CANONICAL_LINE), eq(0));
        assertThat(Files.readString(dir.resolve("quarantine").resolve("pay-001.jsonl"), StandardCharsets.UTF_8))
                .isEmpty();
    }

    @Test
    @DisplayName("映射判隔离：原文逐字节保留、原因来自映射器、不再跑契约校验（事件 id 无从解析故为 null）")
    void mappingQuarantineKeepsRawBytesAndReason(@TempDir Path dir) throws IOException {
        Path raw = rawFile(dir, RAW_LINE);
        when(sourceMapper.map(applied, RAW_LINE))
                .thenReturn(MappedLine.quarantined("MAPPING:UNKNOWN_EVENT_TYPE@event_type"));

        LocalFileIngestor.FileResult result = ingestor().ingestFile(raw, 9L, 1L, 1L,
                dir.resolve("accepted"), dir.resolve("quarantine"), TraceContext.create(), new CRC32(),
                applied);

        assertThat(Files.readString(dir.resolve("quarantine").resolve("pay-001.jsonl"), StandardCharsets.UTF_8))
                .as("隔离目录必须留下**原文**：映射不认得的行更要能事后复现")
                .isEqualTo(RAW_LINE + "\n");
        assertThat(result.collected()).isZero();
        assertThat(result.quarantined()).isEqualTo(1);
        assertThat(Files.readString(dir.resolve("accepted").resolve("pay-001.jsonl"), StandardCharsets.UTF_8))
                .isEmpty();

        ArgumentCaptor<QuarantineRecord> captor = ArgumentCaptor.forClass(QuarantineRecord.class);
        verify(quarantineRecordMapper).insert(captor.capture());
        QuarantineRecord record = captor.getValue();
        assertThat(record.getReason()).isEqualTo("MAPPING:UNKNOWN_EVENT_TYPE@event_type");
        assertThat(record.getBatchId()).isEqualTo(9L);
        assertThat(record.getEventId())
                .as("raw 行用的是源侧字段名，取不到 event_id 就写 null（DDL 注释：能解析出则填）")
                .isNull();
        assertThat(record.getSchemaVersion()).isNull();
        verify(validator, never())
                .check(anyString(), anyInt());
    }

    @Test
    @DisplayName("不映射（v1 兼容画像直通）：完全不碰映射器，行为与 S2-01 之前逐字节一致")
    void legacyMappingNeverCallsSourceMapper(@TempDir Path dir) throws IOException {
        Path raw = rawFile(dir, CANONICAL_LINE);
        when(validator.check(anyString(), anyInt())).thenReturn(null);

        LocalFileIngestor.FileResult result = ingestor().ingestFile(raw, 9L, 1L, 1L,
                dir.resolve("accepted"), dir.resolve("quarantine"), TraceContext.create(), new CRC32(),
                SourceMapping.legacy());

        assertThat(Files.readString(dir.resolve("accepted").resolve("pay-001.jsonl"), StandardCharsets.UTF_8))
                .isEqualTo(CANONICAL_LINE + "\n");
        assertThat(result.collected()).isEqualTo(1);
        verify(sourceMapper, never()).map(any(), anyString());
        verify(validator).check(eq(CANONICAL_LINE), eq(0));
    }
}
