package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.QuarantineRecord;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper;
import com.graduation.analytics.common.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * 本地文件采集器 —— Flume Taildir 语义的本机等价实现（§5.2.5）：
 * - 文件级断点（file_checkpoint.next_offset）：暂停→恢复→从断点继续，不丢不重；
 * - 逐行契约校验：干净行 → landed 目录；坏行 → quarantine 文件 + quarantine_record；
 * - at-least-once 语义：若读取期间文件增长，end_offset=文件末尾，重复行由 DWD 按 event_id 去重。
 * 集群部署时由 Flume Taildir Source（ingestion/flume/flume-taildir.conf）替换本实现，接口语义一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalFileIngestor {

    private final FileCheckpointMapper checkpointMapper;
    private final QuarantineRecordMapper quarantineRecordMapper;
    private final EventContractValidator validator;

    /**
     * 采集一个文件从断点之后的新内容。
     *
     * @return 文件级结果
     */
    public FileResult ingestFile(Path file, long batchId, Path landedDir, Path quarantineFile,
                                 TraceContext trace) {
        // 断点键 = hours 文件名（yyyyMMddHH.jsonl），稳定且跨环境唯一
        String rel = file.getFileName().toString();
        FileCheckpoint ckpt = checkpointMapper.selectById(rel);
        long startOffset = ckpt == null ? 0 : ckpt.getNextOffset();

        long collected = 0;
        long quarantined = 0;
        String quarantineContent = "";
        int lineNo = 0;
        long endOffset = 0;
        try {
            long fileSize = Files.size(file);
            if (startOffset >= fileSize) {
                return new FileResult(rel, startOffset, fileSize, 0, 0);
            }
            Path landedFile = landedDir.resolve(file.getFileName().toString());
            Files.createDirectories(landedDir);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    Files.newInputStream(file), StandardCharsets.UTF_8))) {
                long skipped = 0;
                while (skipped < startOffset) {
                    long n = Math.min(8192L, startOffset - skipped);
                    long actual = reader.skip(n);
                    if (actual <= 0) {
                        break;
                    }
                    skipped += actual;
                }
                if (skipped < startOffset) {
                    throw new IOException("断点跳过失败: expected " + startOffset + " got " + skipped);
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    EventContractValidator.Violation v = validator.check(line, lineNo);
                    if (v == null) {
                        Files.writeString(landedFile, line + System.lineSeparator(),
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        collected++;
                    } else {
                        Files.writeString(quarantineFile, line + System.lineSeparator(),
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        QuarantineRecord record = new QuarantineRecord();
                        record.setBatchId(batchId);
                        record.setEventId(v.eventId());
                        record.setSchemaVersion(v.schemaVersion());
                        record.setReason(v.reason());
                        record.setRawPath(quarantineFile.toString());
                        quarantineRecordMapper.insert(record);
                        quarantined++;
                    }
                }
            }
            // at-least-once：以读取后文件大小为终点（读取期间增长的内容下轮从该点继续，重复由 event_id 去重）
            endOffset = Files.size(file);
            upsertCheckpoint(rel, endOffset);
        } catch (IOException e) {
            throw new UncheckedIOException("采集失败: " + file, e);
        }
        log.info("ingest {}: collected={} quarantined={} offset {}→{}", rel, collected, quarantined,
                startOffset, endOffset);
        return new FileResult(rel, startOffset, endOffset, collected, quarantined);
    }

    private void upsertCheckpoint(String rel, long nextOffset) {
        FileCheckpoint existing = checkpointMapper.selectById(rel);
        if (existing == null) {
            FileCheckpoint ckpt = new FileCheckpoint();
            ckpt.setFilePath(rel);
            ckpt.setNextOffset(nextOffset);
            ckpt.setUpdatedAt(LocalDateTime.now());
            checkpointMapper.insert(ckpt);
        } else {
            existing.setNextOffset(nextOffset);
            checkpointMapper.updateById(existing);
        }
    }

    /** 文件级采集结果 */
    public record FileResult(String filePath, long startOffset, long endOffset,
                             long collected, long quarantined) {
    }
}