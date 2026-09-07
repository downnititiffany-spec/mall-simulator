package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.QuarantineRecord;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper;
import com.graduation.analytics.common.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * 本地文件采集器 —— Flume Taildir 语义的本机等价实现（整改书 §9.2）：
 * - 【字节偏移】基于 FileChannel 读取，checkpoint 保存【字节】偏移；逐字节扫描
 *   换行符（LF/CRLF 均支持），完整行才消费 —— 旧实现用 BufferedReader.skip()
 *   按字符跳而 Files.size() 按字节算，UTF-8 中文会断点错位；
 * - 仅在一整行成功处理后推进偏移（文件尾部无换行的残行留在原地等文件增长）；
 * - 文件被截断或 file identity（Windows=创建时间戳）变化时视为新版本，从头读取，
 *   不沿用旧偏移；文件删除重建（创建时间变化）即新版本；
 * - checkpoint 唯一键 = runtime_profile_id + file_path(绝对路径) + file_identity（§9.2）；
 * - 干净行 → accepted/{batchId}/，坏行 → quarantine/{batchId}/ + quarantine_record；
 * - 数据落地成功后 checkpoint 才推进（可恢复顺序）；at-least-once 重复投递由
 *   DWD 的 event_id 去重兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalFileIngestor {

    private static final byte LF = 10;
    private static final byte CR = 13;

    private final FileCheckpointMapper checkpointMapper;
    private final QuarantineRecordMapper quarantineRecordMapper;
    private final EventContractValidator validator;
    private final ObjectMapper objectMapper;

    /**
     * 采集一个文件从断点之后的新内容（字节偏移）。
     *
     * @return 文件级结果
     */
    public FileResult ingestFile(Path file, long batchId, long runtimeProfileId,
                                 Path acceptedDir, Path quarantineDir, TraceContext trace,
                                 CRC32 checksum) {
        String abs = file.toAbsolutePath().toString();
        String identity = fileIdentity(file);
        FileCheckpoint ckpt = findCheckpoint(runtimeProfileId, abs);
        long size = sizeOf(file);
        // 文件版本变化或 size 小于偏移（被截断）→ 新版本从头读取，不沿用旧偏移（§9.2）
        boolean newVersion = ckpt == null || !identity.equals(ckpt.getFileIdentity()) || size < ckpt.getNextOffset();
        long startOffset = newVersion ? 0 : ckpt.getNextOffset();
        if (!newVersion && startOffset >= size) {
            return result(file, startOffset, startOffset, identity, 0, 0, 0, Set.of());
        }

        long collected = 0;
        long quarantined = 0;
        long acceptedBytes = 0;
        long endOffset = startOffset;
        Set<String> schemaVersions = new HashSet<>();
        try {
            Files.createDirectories(acceptedDir);
            Files.createDirectories(quarantineDir);
            Path acceptedFile = acceptedDir.resolve(file.getFileName().toString());
            Path quarantineFile = quarantineDir.resolve(file.getFileName().toString());

            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
                 OutputStream acceptedOut = Files.newOutputStream(acceptedFile,
                         StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                 OutputStream quarantineOut = Files.newOutputStream(quarantineFile,
                         StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                channel.position(startOffset);
                ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
                ByteArrayOutputStream line = new ByteArrayOutputStream(1024);

                // consumed 累计自 startOffset 起已消费的字节数；行起点 = startOffset + consumedAtLineStart
                long consumed = 0;
                long consumedAtLineStart = 0; // 当前行起点（相对 startOffset）
                boolean eof = false;

                outer:
                while (true) {
                    buf.clear();
                    int n = channel.read(buf);
                    if (n == -1) {
                        eof = true;
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }
                    buf.flip();
                    while (buf.hasRemaining()) {
                        byte b = buf.get();
                        consumed++;
                        if (b == LF) {
                            // 完整行结束：line 含 \r\n 之前的字节（剔除尾部 \r）
                            byte[] raw = line.toByteArray();
                            int len = raw.length;
                            if (len > 0 && raw[len - 1] == CR) {
                                len--;
                            }
                            String text = new String(raw, 0, len, StandardCharsets.UTF_8);
                            EventContractValidator.Violation v = validator.check(text, 0);
                            if (v == null) {
                                byte[] out = (text + "\n").getBytes(StandardCharsets.UTF_8);
                                acceptedOut.write(out);
                                acceptedBytes += out.length;
                                if (checksum != null) {
                                    checksum.update(out);
                                }
                                collected++;
                                schemaVersions.add(schemaVersionOf(text));
                            } else {
                                quarantineOut.write(raw);
                                quarantineOut.write(LF);
                                QuarantineRecord record = new QuarantineRecord();
                                record.setBatchId(batchId);
                                record.setEventId(v.eventId());
                                record.setSchemaVersion(v.schemaVersion());
                                record.setReason(v.reason());
                                record.setRawPath(quarantineFile.toString());
                                quarantineRecordMapper.insert(record);
                                quarantined++;
                            }
                            // 行起点推进到 LF 之后
                            consumedAtLineStart = consumed;
                            line.reset();
                        } else {
                            line.write(b);
                        }
                    }
                }

                // 统计剩余缓冲：为保证不丢字节，还需要处理 buffer 未消费的部分？
                // （上面内层循环已消费全部 hasRemaining，无需二次处理）

                if (!eof) {
                    throw new IOException("读取中断（非 EOF 退出）");
                }
                if (line.size() == 0) {
                    // 干净 EOF：所有字节均为完整行，偏移推进到文件尾
                    endOffset = startOffset + consumed;
                } else {
                    // 尾部残行（无换行）：不消费，偏移停在最后一个完整行之后（Taildir 语义）
                    endOffset = startOffset + consumedAtLineStart;
                    log.info("ingest {}: 尾部残行 {} 字节留待文件增长", file.getFileName(), line.size());
                }
                acceptedOut.flush();
                quarantineOut.flush();
            }
            // 落地成功后才推进 checkpoint（可恢复顺序，§9.2）
            if (endOffset > startOffset || collected > 0 || quarantined > 0) {
                upsertCheckpoint(runtimeProfileId, abs, identity, endOffset);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("采集失败: " + file, e);
        }
        log.info("ingest {}: collected={} quarantined={} acceptedBytes={} offset {}→{} identity={}",
                file.getFileName(), collected, quarantined, acceptedBytes, startOffset, endOffset, identity);
        return result(file, startOffset, endOffset, identity, collected, quarantined,
                acceptedBytes, schemaVersions);
    }

    private static FileResult result(Path file, long start, long end, String identity,
                                     long collected, long quarantined, long acceptedBytes,
                                     Set<String> schemaVersions) {
        return new FileResult(file.getFileName().toString(), start, end, identity,
                collected, quarantined, acceptedBytes, schemaVersions);
    }

    private FileCheckpoint findCheckpoint(long runtimeProfileId, String absPath) {
        return checkpointMapper.selectOne(new LambdaQueryWrapper<FileCheckpoint>()
                .eq(FileCheckpoint::getRuntimeProfileId, runtimeProfileId)
                .eq(FileCheckpoint::getFilePath, absPath));
    }

    private void upsertCheckpoint(long runtimeProfileId, String absPath, String identity, long nextOffset) {
        FileCheckpoint existing = findCheckpoint(runtimeProfileId, absPath);
        if (existing == null) {
            FileCheckpoint ckpt = new FileCheckpoint();
            ckpt.setRuntimeProfileId(runtimeProfileId);
            ckpt.setFilePath(absPath);
            ckpt.setFileIdentity(identity);
            ckpt.setNextOffset(nextOffset);
            ckpt.setUpdatedAt(LocalDateTime.now());
            checkpointMapper.insert(ckpt);
        } else {
            existing.setFileIdentity(identity);
            existing.setNextOffset(nextOffset);
            existing.setUpdatedAt(LocalDateTime.now());
            checkpointMapper.updateById(existing);
        }
    }

    /** Windows 文件身份：创建时间戳（文件删除重建即变化 → 新版本从头读） */
    private static String fileIdentity(Path file) {
        try {
            java.nio.file.attribute.BasicFileAttributes attrs =
                    Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return String.valueOf(attrs.creationTime().toMillis());
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private String schemaVersionOf(String jsonLine) {
        try {
            JsonNode node = objectMapper.readTree(jsonLine);
            JsonNode v = node.get("schema_version");
            return v == null || v.isNull() ? "unknown" : v.asText();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 文件级采集结果 */
    public record FileResult(String filePath, long startOffset, long endOffset, String fileIdentity,
                             long collected, long quarantined, long acceptedBytes, Set<String> schemaVersions) {
    }
}