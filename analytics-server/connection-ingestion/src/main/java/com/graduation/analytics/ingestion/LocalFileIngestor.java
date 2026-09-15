package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.QuarantineRecord;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.mapping.ingest.MappedLine;
import com.graduation.analytics.mapping.ingest.SourceMapper;
import com.graduation.analytics.mapping.ingest.SourceMapping;
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
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

/**
 * 本地文件采集器 —— Flume Taildir 语义的本机等价实现（整改书 §9.2）：
 * - 【字节偏移】基于 FileChannel 读取，checkpoint 保存【字节】偏移；逐字节扫描
 *   换行符（LF/CRLF 均支持），完整行才消费 —— 旧实现用 BufferedReader.skip()
 *   按字符跳而 Files.size() 按字节算，UTF-8 中文会断点错位；
 * - 仅在一整行成功处理后推进偏移（文件尾部无换行的残行留在原地等文件增长）；
 * - 文件被截断或 file identity（Windows=创建时间戳）变化时视为新版本，从头读取，
 *   不沿用旧偏移；文件删除重建（创建时间变化）即新版本；
 * - checkpoint 唯一键 = runtime_profile_id + **source_id** + file_path(绝对路径) + file_identity
 *   （§9.2；P1-05 / D-037 裁决 1：源必须参与键，否则切换源会**静默少采** —— 见 {@code FileCheckpoint}）；
 * - 干净行 → accepted/{batchId}/，坏行 → quarantine/{batchId}/ + quarantine_record；
 * - 数据落地成功后 checkpoint 才推进（可恢复顺序）；at-least-once 重复投递由
 *   DWD 的 event_id 去重兜底。
 *
 * <p><b>S2-02 起的第二道闸门</b>：本源若登记了可执行的 v2 画像，本类先把原始行交给
 * {@link SourceMapper}（raw → canonical 或隔离），**再**让 canonical 产物过
 * {@link EventContractValidator}——两道闸门串行，顺序不能颠倒：raw 行用的是源侧字段名，
 * 契约校验器把它当 canonical 读只会得到"缺失必要字段: event_id"，全量隔离等于静默丢数据。
 * v1 只读兼容画像走 {@link SourceMapping#legacy()} 直通，行为与 S2-01 之前逐字节一致。</p>
 *
 * <p><b>源从哪来</b>：本类不依赖源登记（不注入 {@code SourceRegistryService}）——源由调用方
 * 解析后**显式传入**，与 {@code runtimeProfileId} 同样的处理方式。这样"写入用的源"和
 * "查询用的源"必然是同一个值，不会出现"写 A 查 B"这类只有并发时才暴露的错配；
 * "未绑定源即拒绝采集"的判定留在编排层（{@code IngestionService}）单点负责。</p>
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
     * 逐行映射器（S2-02）。采集器**不认识**画像：它只知道"本轮的映射绑定是什么"
     * （{@link SourceMapping}，由编排层在本轮开始前定妥），因此装载/激活判定不在这里重复。
     */
    private final SourceMapper sourceMapper;

    /**
     * 采集一个文件从断点之后的新内容（字节偏移），按 {@code mapping} 决定是否逐行映射（S2-02）。
     *
     * <p><b>签名里不允许出现"少一个参数"的重载</b>：本方法**唯一**，且同时强制
     * {@code sourceId}（D-037 裁决 1：断点按源隔离）与 {@code mapping}（"没传映射"与"故意不映射"
     * 必须由调用点显式声明，不能靠默认值兜底）。给 S2-02 之前的八参形态留一个转 legacy 的便捷重载
     * 看似无害，实则是把"跳过映射"变成一个静默默认值——已有守卫用例
     * {@code LocalFileIngestorSourceIsolationTest.noSourceLessOverloadExists} 正是为了防止这种重载增殖。</p>
     *
     * @param runtimeProfileId 归属运行环境
     * @param sourceId         归属数据源（{@code source_registry.id}，**必填**）：
     *                         断点按源隔离（D-037 裁决 1），缺省就会造出"来源不明"的断点行
     * @param mapping          本轮本源的映射绑定：{@link SourceMapping#legacy()} 表示不映射（v1 兼容画像直通），
     *                         否则每一行先经 {@link SourceMapper#map} 再落盘。**不能传 null**
     * @return 文件级结果
     */
    public FileResult ingestFile(Path file, long batchId, long runtimeProfileId, long sourceId,
                                 Path acceptedDir, Path quarantineDir, TraceContext trace,
                                 CRC32 checksum, SourceMapping mapping) {
        String abs = checkpointKey(file);
        String identity = fileIdentity(file);
        FileCheckpoint ckpt = findCheckpoint(runtimeProfileId, sourceId, abs);
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
                            // S2-02：两道闸门**串行**——映射（可选）在前，canonical 契约在后。
                            // decision 是要落盘的文本：映射生效时是 canonical 行，否则就是原始行。
                            String decision = text;
                            String reason = null;          // 非空 ⇒ 隔离，且已是终态原因
                            String eventId = null;
                            String schemaVersion = null;
                            if (mapping.applied()) {
                                MappedLine mapped = sourceMapper.map(mapping, text);
                                if (mapped.accepted()) {
                                    decision = mapped.canonicalText();
                                } else {
                                    // 映射判死：原因来自映射器。event_id/schema_version 留 null——
                                    // raw 行用源侧字段名，取不到就别猜（DDL 注释：能解析出则填）。
                                    reason = mapped.quarantineReason();
                                }
                            }
                            if (reason == null) {
                                EventContractValidator.Violation v = validator.check(decision, 0);
                                if (v != null) {
                                    reason = v.reason();
                                    eventId = v.eventId();
                                    schemaVersion = v.schemaVersion();
                                }
                            }
                            if (reason == null) {
                                byte[] out = (decision + "\n").getBytes(StandardCharsets.UTF_8);
                                acceptedOut.write(out);
                                acceptedBytes += out.length;
                                if (checksum != null) {
                                    checksum.update(out);
                                }
                                collected++;
                                schemaVersions.add(schemaVersionOf(decision));
                            } else {
                                quarantineOut.write(raw);
                                quarantineOut.write(LF);
                                QuarantineRecord record = new QuarantineRecord();
                                record.setBatchId(batchId);
                                record.setEventId(eventId);
                                record.setSchemaVersion(schemaVersion);
                                record.setReason(reason);
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
                upsertCheckpoint(runtimeProfileId, sourceId, abs, identity, endOffset);
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

    /**
     * 按 {@code (runtime_profile_id, source_id, file_path)} 取断点行——与 V17 的
     * {@code uk_ckpt_source} 前三个列**同序同集**（第四列 file_identity 只用于判"是否新版本"，
     * 不参与定位，因为同一键下 identity 唯一）。
     *
     * <p>源必须参与定位：少了它，"同一 profile 切源"会读到上一源的偏移并直接跳过数据。</p>
     */
    private FileCheckpoint findCheckpoint(long runtimeProfileId, long sourceId, String absPath) {
        return checkpointMapper.selectOne(new LambdaQueryWrapper<FileCheckpoint>()
                .eq(FileCheckpoint::getRuntimeProfileId, runtimeProfileId)
                .eq(FileCheckpoint::getSourceId, sourceId)
                .eq(FileCheckpoint::getFilePath, absPath));
    }

    /**
     * checkpoint 物理键 = 规范化绝对路径。
     *
     * <p>为什么键的规范形式必须由**键的所有者**负责：唯一键是
     * {@code runtime_profile_id + source_id + file_path + file_identity}，而 {@code file_path} 由调用方传入的
     * {@link Path} 拼出。只要有一个调用方传进带冗余片段（{@code ./}、{@code ../}、重复分隔符）的路径，
     * 同一个物理文件就会占**两行**：一行写、另一行读不到 → 采集端按"从未采集"从头读
     * （目录里最大单文件 19.4MB）并重复写 ODS 分区（最终由 DWD 的 {@code event_id} 去重兜底，
     * 代价是白读磁盘）。</p>
     *
     * <p>实测（DEF-13）：50 个文件 × 2 行 = 100 行，两种写法只差一个 {@code \.\}，
     * {@code file_identity} 与 {@code next_offset} 完全相同。</p>
     */
    static String checkpointKey(Path file) {
        return canonical(file.toAbsolutePath().toString());
    }

    /**
     * 键的规范形式：去掉 {@code ./}、{@code ../} 与重复分隔符。
     *
     * <p>读写两端都走这一个表达式——这是"同一物理文件只有一条键"的唯一保证。</p>
     */
    private static String canonical(String absolutePath) {
        return Paths.get(absolutePath).normalize().toString();
    }

    /**
     * 当前运行环境**在指定源下已有断点**的文件（规范路径集合）。
     *
     * <p>读数端与写入端共用同一条规范形式，因此历史遗留写法不会把同一物理文件算两次
     * （DEF-13：{@code checkpointFiles=101} 对 {@code pendingFiles=51}）。</p>
     *
     * <p>P1-05：必须同时按 {@code source_id} 过滤。否则切源后状态总览会把**另一个源**的断点
     * 算到当前源头上（虚报 {@code checkpointFiles}），与采集侧"按源隔离"的口径自相矛盾。</p>
     */
    public Set<String> checkpointKeys(long runtimeProfileId, long sourceId) {
        return checkpointMapper.selectList(new LambdaQueryWrapper<FileCheckpoint>()
                        .eq(FileCheckpoint::getRuntimeProfileId, runtimeProfileId)
                        .eq(FileCheckpoint::getSourceId, sourceId))
                .stream()
                .map(ckpt -> canonical(ckpt.getFilePath()))
                .collect(Collectors.toSet());
    }

    private void upsertCheckpoint(long runtimeProfileId, long sourceId, String absPath,
                                  String identity, long nextOffset) {
        FileCheckpoint existing = findCheckpoint(runtimeProfileId, sourceId, absPath);
        if (existing == null) {
            FileCheckpoint ckpt = new FileCheckpoint();
            ckpt.setRuntimeProfileId(runtimeProfileId);
            ckpt.setSourceId(sourceId);
            ckpt.setFilePath(absPath);
            ckpt.setFileIdentity(identity);
            ckpt.setNextOffset(nextOffset);
            ckpt.setUpdatedAt(LocalDateTime.now());
            checkpointMapper.insert(ckpt);
        } else {
            // sourceId 不改：本行的源就是查询条件里的源，改它等于把 A 的断点搬给 B
            existing.setFileIdentity(identity);
            existing.setNextOffset(nextOffset);
            existing.setUpdatedAt(LocalDateTime.now());
            checkpointMapper.updateById(existing);
        }
    }

    /**
     * Windows 文件身份：创建时间戳（文件删除重建即变化 → 新版本从头读）。
     *
     * <p>包内共享（B-08 / D-022 候选①）：{@link IngestionService#status()} 判定"自上次采集以来是否新增"
     * 必须与采集端用**同一套**身份与偏移语义，故此处是唯一所有者，不另写第二份实现。</p>
     */
    static String fileIdentity(Path file) {
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

    /**
     * 文件当前**可消费**的结束偏移 = 最后一个换行符之后的字节数（与 {@link #ingestFile} 的 Taildir 语义同源）。
     *
     * <p>末尾没有换行的残行不计入：采集端会把残行留在原地等文件增长，因此
     * "文件长度 &gt; 断点偏移"**并不等于**"有新数据可读"（DEF-12）。</p>
     */
    static long consumableEnd(Path file) {
        long size = sizeOf(file);
        if (size <= 0) {
            return 0;
        }
        ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long pos = size;
            while (pos > 0) {
                int len = (int) Math.min(buf.capacity(), pos);
                long start = pos - len;
                buf.clear();
                buf.limit(len);
                channel.position(start);
                int read = 0;
                while (read < len) {
                    int n = channel.read(buf);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                byte[] bytes = buf.array();
                for (int i = len - 1; i >= 0; i--) {
                    if (bytes[i] == LF) {
                        return start + i + 1;
                    }
                }
                pos = start;
            }
        } catch (IOException e) {
            log.warn("consumableEnd {} 读取失败，按文件长度处理: {}", file.getFileName(), e.getMessage());
            return size;
        }
        return 0;   // 整个文件没有任何换行 → 没有完整行
    }

    /**
     * 该文件是否还有**可被本次采集消费**的数据（B-08 / D-022 候选①：数据源停机时的"最小明示"）。
     *
     * <p>这里是"什么算有新数据"的**唯一所有者**，判定与 {@link #ingestFile} 同源：
     * 文件从未采集 / 身份（创建时间）变化 / 长度小于断点 → 采集端会从头读取，此时只要存在完整行即为有新数据；
     * 否则比较 {@link #consumableEnd} 与断点偏移，**严格大于**才算有新数据（尾部残行不算，DEF-12）。</p>
     *
     * <p>P1-05：断点查找也必须带 {@code sourceId}——读数端若按 profile 找断点，切源后会把"本源的
     * 新数据"误判成"已采完"（正是 D-037 要关的那个洞）。</p>
     */
    public boolean hasConsumableData(Path file, long runtimeProfileId, long sourceId) {
        long consumable = consumableEnd(file);
        if (consumable <= 0) {
            return false;   // 空文件，或整个文件只有一条没有换行的残行
        }
        FileCheckpoint ckpt = findCheckpoint(runtimeProfileId, sourceId, checkpointKey(file));
        if (ckpt == null) {
            return true;    // 从未采集过
        }
        Long next = ckpt.getNextOffset();
        boolean newVersion = !fileIdentity(file).equals(ckpt.getFileIdentity())
                || next == null || sizeOf(file) < next;
        if (newVersion) {
            return true;    // 采集端按新版本从头读 → 有完整行即有新数据
        }
        return consumable > next;
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