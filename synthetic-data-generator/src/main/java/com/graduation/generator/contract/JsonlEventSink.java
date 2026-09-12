package com.graduation.generator.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code CANONICAL_EVENT_FILE} 模式的 JSONL 落点（V2.1 §3.3 B / §4.1）。
 *
 * <p>产出：{@code <outputDir>/<runId>/events-0001.jsonl} + 同名 {@code .manifest.json}；
 * 达到 {@code maxRecordsPerFile} 时轮转，每个文件一份清单（契约清单是"单个制品文件的清单"）。</p>
 *
 * <p>三条硬约束：</p>
 * <ol>
 *   <li><b>绝不写分析平台 landing</b>（§3.3 B）：构造时拒绝路径段为 {@code landing} 的目录，且产物只写在本程序自己的输出根下。</li>
 *   <li><b>逐字节可复现</b>（§4.2）：字段顺序固定、UTF-8、换行统一 {@code \n}，同一事件序列必得同一 checksum。</li>
 *   <li><b>不触发平台流水线</b>（§3.3 B）：本类只碰文件系统，不依赖任何平台/商城类型。</li>
 * </ol>
 *
 * <p><b>未冻结项（算法）</b>：契约只要求 {@code checksum} 是字符串，未规定算法；采集侧清单用的是 CRC32 十六进制，
 * 两者是否相同未冻结（契约文件原话"不得默认相同"）。本实现取 {@link #CHECKSUM_ALGORITHM}，且**不**声称与采集侧等价；
 * 契约冻结后只需改这一个常量。</p>
 */
public final class JsonlEventSink implements EventSink {

    /** 制品校验算法（未冻结项，见类注释） */
    public static final String CHECKSUM_ALGORITHM = "SHA-256";

    private static final String FILE_PREFIX = "events-";
    private static final String JSONL_SUFFIX = ".jsonl";
    private static final String MANIFEST_SUFFIX = ".manifest.json";

    private final String runId;
    private final Path runDir;
    private final String schemaVersion;
    private final int maxRecordsPerFile;
    private final ObjectMapper mapper;
    private final List<Artifact> rotated = new ArrayList<>();

    private MessageDigest digest;
    private BufferedWriter writer;
    private Path currentFile;
    private long fileBytes;
    private long fileRecords;
    private long writtenRecords;
    private String minEventTime;
    private String maxEventTime;
    private OffsetDateTime minParsed;
    private OffsetDateTime maxParsed;
    private int fileSequence;
    private boolean closed;

    public JsonlEventSink(String runId, Path outputDir, String schemaVersion, int maxRecordsPerFile) {
        this(runId, outputDir, schemaVersion, maxRecordsPerFile, new ObjectMapper());
    }

    public JsonlEventSink(String runId, Path outputDir, String schemaVersion, int maxRecordsPerFile,
                          ObjectMapper mapper) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 必填（清单 run_id）");
        }
        if (outputDir == null) {
            throw new IllegalArgumentException("输出目录必填");
        }
        if (maxRecordsPerFile < 1) {
            throw new IllegalArgumentException("maxRecordsPerFile 必须 ≥ 1");
        }
        Path normalized = outputDir.toAbsolutePath().normalize();
        for (Path segment : normalized) {
            if ("landing".equalsIgnoreCase(segment.toString())) {
                throw new IllegalArgumentException(
                        "V2.1 §3.3 B：文件模式产物不得写入分析平台 landing 目录，实际=" + normalized);
            }
        }
        this.runId = runId;
        this.runDir = normalized.resolve(runId);
        this.schemaVersion = schemaVersion;
        this.maxRecordsPerFile = maxRecordsPerFile;
        this.mapper = mapper;
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new UncheckedIOException("输出目录创建失败：" + runDir, e);
        }
        openNextFile();
    }

    @Override
    public void write(CanonicalEvent event) {
        if (closed) {
            throw new IllegalStateException("sink 已关闭，不能再写事件（清单已产出）");
        }
        if (event == null) {
            throw new IllegalArgumentException("事件不得为 null");
        }
        String line;
        try {
            line = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("事件无法序列化为 JSONL：" + event.eventId(), e);
        }
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            writer.write(line);
            writer.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException("JSONL 写入失败：" + currentFile, e);
        }
        digest.update(bytes);
        fileBytes += bytes.length;
        fileRecords++;
        trackTime(event.eventTime());
    }

    @Override
    public Optional<Artifact> rotateIfNeeded() {
        if (fileRecords < maxRecordsPerFile) {
            return Optional.empty();
        }
        Path finishedFile = currentFile;
        Artifact artifact = finishCurrentFile();
        writeManifest(finishedFile, artifact);
        rotated.add(artifact);
        openNextFile();
        return Optional.of(artifact);
    }

    @Override
    public void flush() {
        if (closed) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("JSONL 落盘失败：" + currentFile, e);
        }
    }

    @Override
    public ArtifactManifest closeAndBuildManifest() {
        if (closed) {
            throw new IllegalStateException("sink 已关闭：closeAndBuildManifest 每个制品只允许一次");
        }
        Path finishedFile = currentFile;
        Artifact artifact = finishCurrentFile();
        ArtifactManifest manifest = writeManifest(finishedFile, artifact);
        rotated.add(artifact);
        closed = true;
        return manifest;
    }

    /**
     * {@link AutoCloseable} 语义：重复 close 必须无害（try-with-resources 常与显式关账并用），
     * 而 {@link #closeAndBuildManifest()} 仍只允许一次——避免二次关账覆盖已对账的清单。
     */
    @Override
    public void close() {
        if (!closed) {
            closeAndBuildManifest();
        }
    }

    /** 本 sink 生命周期内产出的全部制品（含末次 close 的那个），供 §4.2 {@code generation_artifact} 落库 */
    public List<Artifact> artifacts() {
        return List.copyOf(rotated);
    }

    /**
     * 本 sink 已写出的规范事件条数（只数 {@code events-*.jsonl} 里的行）。
     *
     * <p>运行失败时它是"真实入流了多少条"的唯一可信来源：{@code generation_artifact} 里还躺着
     * 清单和操作流水，把它们一起加进 {@code success_count} 会得出比 {@code event_count} 还大的数
     * （2026-09-11 真机 E3 就报过 {@code success_count=632}，实际入流 231 条）。</p>
     */
    public long eventRecords() {
        return writtenRecords + fileRecords;
    }

    /** 当前制品对应的清单文件路径（未 close 时为预期路径） */
    public Path currentManifestPath() {
        return currentFile.resolveSibling(
                currentFile.getFileName().toString().replace(JSONL_SUFFIX, MANIFEST_SUFFIX));
    }

    public Path currentFile() {
        return currentFile;
    }

    // ---------- 内部 ----------

    private void openNextFile() {
        fileSequence++;
        writtenRecords += fileRecords;
        currentFile = runDir.resolve(FILE_PREFIX + String.format(Locale.ROOT, "%04d", fileSequence) + JSONL_SUFFIX);
        fileBytes = 0;
        fileRecords = 0;
        minEventTime = null;
        maxEventTime = null;
        minParsed = null;
        maxParsed = null;
        digest = newDigest();
        try {
            writer = Files.newBufferedWriter(currentFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("制品文件创建失败：" + currentFile, e);
        }
    }

    /** 为刚关闭的制品写同名清单（轮转与最终关账共用；契约清单是"单个制品文件"的清单） */
    private ArtifactManifest writeManifest(Path finishedFile, Artifact artifact) {
        ArtifactManifest manifest = ArtifactManifest.of(runId, artifact.uri(), artifact.checksum(),
                artifact.bytes(), artifact.recordCount(), minEventTime, maxEventTime, schemaVersion);
        Path manifestPath = finishedFile.resolveSibling(
                finishedFile.getFileName().toString().replace(JSONL_SUFFIX, MANIFEST_SUFFIX));
        manifest.write(manifestPath, mapper);
        return manifest;
    }

    private Artifact finishCurrentFile() {
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("制品文件关闭失败：" + currentFile, e);
        }
        String checksum = HexFormat.of().formatHex(digest.digest());
        return new Artifact(currentFile.toUri().toString(), Artifact.KIND_EVENT_JSONL,
                checksum, fileBytes, fileRecords,
                minParsed == null ? null : minParsed.toInstant(),
                maxParsed == null ? null : maxParsed.toInstant());
    }

    private void trackTime(String eventTime) {
        OffsetDateTime parsed = OffsetDateTime.parse(eventTime);
        if (minParsed == null || parsed.isBefore(minParsed)) {
            minParsed = parsed;
            minEventTime = eventTime;
        }
        if (maxParsed == null || parsed.isAfter(maxParsed)) {
            maxParsed = parsed;
            maxEventTime = eventTime;
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(CHECKSUM_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(CHECKSUM_ALGORITHM + " 不可用", e);
        }
    }
}
