package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper;
import com.graduation.analytics.mapping.ingest.SourceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DEF-12：「有新数据可读」的唯一判定（{@link LocalFileIngestor#consumableEnd} /
 * {@link LocalFileIngestor#hasConsumableData}）。
 *
 * <p>实测缺陷（M1-8 验收 §5 门禁捕获）：landing 目录里存在一个**末尾无换行的残行**文件
 * （{@code golden-r615-20260910164859.jsonl}，18429 字节，断点 18139），旧判定用
 * "文件长度 != 断点偏移"，于是该文件**永远**被算作"有新数据"，{@code newFileCount} 永远归不了零
 * ——数据源停机时的"无新数据"明示就被这个假信号污染。</p>
 *
 * <p>采集端本身是对的（Taildir 语义：残行留在原地等文件增长），错的是状态总览另写了一份判定。
 * 本测试钉住：规则只有一处所有者，且与采集端逐条同源。</p>
 */
class LocalFileIngestorConsumableDataTest {

    private final FileCheckpointMapper checkpointMapper = mock(FileCheckpointMapper.class);

    private LocalFileIngestor ingestor() {
        return new LocalFileIngestor(checkpointMapper, mock(QuarantineRecordMapper.class),
                mock(EventContractValidator.class), new ObjectMapper(), mock(SourceMapper.class));
    }

    private static FileCheckpoint checkpointOf(Path file, String identity, Long nextOffset) {
        FileCheckpoint ckpt = new FileCheckpoint();
        ckpt.setRuntimeProfileId(1L);
        ckpt.setSourceId(1L);
        ckpt.setFilePath(file.toAbsolutePath().toString());
        ckpt.setFileIdentity(identity);
        ckpt.setNextOffset(nextOffset);
        return ckpt;
    }

    private boolean consumable(Path file) {
        return ingestor().hasConsumableData(file, 1L, 1L);
    }

    // ---------- consumableEnd：可消费边界 = 最后一个换行符之后 ----------

    @Test
    @DisplayName("consumableEnd：末尾残行不计入可消费字节")
    void consumableEndStopsAtLastNewline(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, "{\"a\":1}\n{\"b\":2}", StandardCharsets.UTF_8);
        assertThat(LocalFileIngestor.consumableEnd(file)).isEqualTo(8);      // {"a":1}\n
        assertThat(Files.size(file)).isEqualTo(15);                          // 含 7 字节残行
    }

    @Test
    @DisplayName("consumableEnd：整个文件没有任何换行 → 0（没有一条完整行）")
    void consumableEndOfFileWithoutNewlineIsZero(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, "{\"b\":2}", StandardCharsets.UTF_8);
        assertThat(LocalFileIngestor.consumableEnd(file)).isZero();
    }

    @Test
    @DisplayName("consumableEnd：CRLF 换行同样按换行符边界计算")
    void consumableEndHandlesCrlf(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, "{\"a\":1}\r\n{\"b\":2}", StandardCharsets.UTF_8);
        assertThat(LocalFileIngestor.consumableEnd(file)).isEqualTo(9);
    }

    @Test
    @DisplayName("consumableEnd：可消费边界跨 64KB 扫描块时仍能向前找到换行（大文件残行场景）")
    void consumableEndScansAcrossChunks(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("big.jsonl");
        String complete = "x".repeat(80_000) + "\n";
        String partial = "y".repeat(69_999);
        Files.writeString(file, complete + partial, StandardCharsets.UTF_8);
        assertThat(LocalFileIngestor.consumableEnd(file)).isEqualTo(complete.length());
    }

    // ---------- hasConsumableData：与采集端同源 ----------

    @Test
    @DisplayName("空文件不算有新数据")
    void emptyFileIsNotConsumable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.jsonl");
        Files.writeString(file, "", StandardCharsets.UTF_8);
        assertThat(consumable(file)).isFalse();
    }

    @Test
    @DisplayName("从未采集过（无断点）且存在完整行 → 有新数据")
    void neverCollectedIsConsumable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, "{\"a\":1}\n", StandardCharsets.UTF_8);
        when(checkpointMapper.selectOne(any())).thenReturn(null);
        assertThat(consumable(file)).isTrue();
    }

    @Test
    @DisplayName("断点已到完整行边界 → 没有新数据")
    void fullyConsumedIsNotConsumable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, "{\"a\":1}\n{\"b\":2}\n", StandardCharsets.UTF_8);
        long size = Files.size(file);
        when(checkpointMapper.selectOne(any()))
                .thenReturn(checkpointOf(file, LocalFileIngestor.fileIdentity(file), size));
        assertThat(consumable(file)).isFalse();
    }

    @Test
    @DisplayName("DEF-12：断点停在完整行边界、其后只有残行 → 没有新数据（残行不可消费）")
    void partialTailIsNotConsumable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, "{\"a\":1}\n", StandardCharsets.UTF_8);
        long boundary = Files.size(file);
        Files.writeString(file, "{\"b\":2}", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        when(checkpointMapper.selectOne(any()))
                .thenReturn(checkpointOf(file, LocalFileIngestor.fileIdentity(file), boundary));
        assertThat(consumable(file)).isFalse();
    }

    @Test
    @DisplayName("残行补全（文件继续增长）→ 重新算有新数据")
    void completedTailBecomesConsumable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, "{\"a\":1}\n", StandardCharsets.UTF_8);
        long boundary = Files.size(file);
        Files.writeString(file, "{\"b\":2}", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        when(checkpointMapper.selectOne(any()))
                .thenReturn(checkpointOf(file, LocalFileIngestor.fileIdentity(file), boundary));
        assertThat(consumable(file)).isFalse();

        Files.writeString(file, "}\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThat(consumable(file)).isTrue();
    }

    @Test
    @DisplayName("文件被删除重建（身份变化）→ 采集端从头读，算有新数据")
    void staleIdentityIsConsumable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, "{\"a\":1}\n", StandardCharsets.UTF_8);
        when(checkpointMapper.selectOne(any()))
                .thenReturn(checkpointOf(file, "旧的创建时间戳", Files.size(file)));
        assertThat(consumable(file)).isTrue();
    }

    @Test
    @DisplayName("文件被就地截断（长度小于断点）→ 采集端按新版本重读，算有新数据")
    void truncatedFileIsConsumable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, "{\"a\":1}\n", StandardCharsets.UTF_8);
        when(checkpointMapper.selectOne(any()))
                .thenReturn(checkpointOf(file, LocalFileIngestor.fileIdentity(file), 9_999L));
        assertThat(consumable(file)).isTrue();
    }
}
