package com.graduation.analytics.runtime.storage;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 落地存储（整改书 §8.2）：source/events 之外的统一落地层入口。
 * 目录职责（§9.1）：landing/accepted/{batchId} 校验通过、landing/quarantine/{batchId} 坏行、
 * landing/manifests/{batchId}.json 批次清单。实现：LocalLandingStorage / HdfsLandingStorage。
 * ODS 只能读取 accepted 或 Flume 的 HDFS 落地区，禁止直接读 source/events（§9.1）。
 */
public interface LandingStorage {

    /** local / hdfs / flume-hdfs */
    String type();

    /** 命名空间（所在 RuntimeProfile 的 landing_uri） */
    String namespace();

    /** 路径是否存在（相对 storage 根的相对路径） */
    boolean exists(String relativePath);

    /**
     * 列出目录下的直接子项名；目录不存在时返回空列表，存储 I/O 故障必须抛出异常，不能伪装为空目录。
     */
    List<String> list(String relativeDir);

    /**
     * 确定性枚举相对目录内的文件；按相对路径排序，遇到枚举期间消失的条目即失败，不能静默漏读。
     */
    default List<FileEntry> listFiles(String relativeDir, boolean recursive) {
        String root = normalizeRelativeDirectory(relativeDir);
        Deque<String> pendingDirectories = new ArrayDeque<>();
        pendingDirectories.add(root);
        List<FileEntry> files = new ArrayList<>();
        while (!pendingDirectories.isEmpty()) {
            String current = pendingDirectories.removeFirst();
            for (String childName : list(current)) {
                requireChildName(childName);
                String childPath = current.isEmpty() ? childName : current + "/" + childName;
                FileStat childStat = stat(childPath);
                if (childStat == null) {
                    throw new IllegalStateException("枚举期间 landing 条目消失: " + childPath);
                }
                if (childStat.directory()) {
                    if (recursive) {
                        pendingDirectories.addLast(childPath);
                    }
                } else {
                    files.add(new FileEntry(childPath, childStat));
                }
            }
        }
        files.sort(Comparator.comparing(FileEntry::relativePath));
        return List.copyOf(files);
    }

    /** 打开可读流（调用方负责关闭） */
    InputStream open(String relativePath);

    /** 打开可按字节定位的输入（调用方负责关闭）；用于基于字节偏移断点恢复的采集。 */
    SeekableInput openSeekable(String relativePath);

    /** 文件统计（大小/最后修改/是否目录）；路径不存在时返回 null，存储 I/O 故障必须抛出异常。 */
    FileStat stat(String relativePath);

    /**
     * 文件版本身份：文件被替换或重建时应变化，用于避免把旧字节偏移套到新文件上。
     * 实现应优先返回后端可获得的稳定版本标识；不能把 I/O 故障伪装成固定身份。
     */
    String fileIdentity(String relativePath);

    /** Checkpoint identity for one file; local storage must preserve the historical canonical absolute-path key. */
    String checkpointKey(String relativePath);

    /** 创建目录（含父目录）。 */
    void createDirectories(String relativeDir);

    /** 打开输出；append=true 表示文件不存在则创建、存在则追加。 */
    OutputStream createOrAppend(String relativePath);

    /** 同一 namespace 内移动路径；目标已存在时返回 false，不覆盖目标。 */
    boolean move(String sourceRelativePath, String targetRelativePath);

    /** 删除路径；路径不存在时返回 false。 */
    boolean deleteIfExists(String relativePath);

    /** 返回相对路径对应的绝对 URI。 */
    String uri(String relativePath);

    /**
     * 发布批次清单：先写隐藏临时文件，再同 namespace rename；同 batchId 与内容完全相同时幂等成功，
     * 同 batchId 内容冲突则失败，不覆盖已发布凭据。
     */
    default String writeManifest(String batchId, String manifestJson) {
        if (batchId == null || !batchId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || ".".equals(batchId) || "..".equals(batchId)) {
            throw new IllegalArgumentException("batchId 必须是安全单段标识符");
        }
        if (manifestJson == null) {
            throw new IllegalArgumentException("manifestJson 不能为空");
        }
        String target = "manifests/" + batchId + ".json";
        byte[] content = manifestJson.getBytes(StandardCharsets.UTF_8);
        if (exists(target)) {
            return requireSameManifest(target, content, batchId);
        }

        createDirectories("manifests");
        String temporary = "manifests/." + batchId + "-" + UUID.randomUUID() + ".tmp";
        try {
            try (OutputStream output = createOrAppend(temporary)) {
                output.write(content);
            }
            if (move(temporary, target)) {
                return uri(target);
            }
            // 并发发布同一 batchId 时，只有完全相同的 READY 凭据可视为幂等。
            return requireSameManifest(target, content, batchId);
        } catch (IOException e) {
            throw new IllegalStateException("写入 manifest 失败: " + batchId, e);
        } finally {
            deleteIfExists(temporary);
        }
    }

    /** 连通性检查：可写/可读探针 */
    HealthResult healthCheck();

    record FileStat(long size, long lastModified, boolean directory) {
    }

    record FileEntry(String relativePath, FileStat stat) {
    }

    record HealthResult(boolean ok, String detail) {
    }

    interface SeekableInput extends AutoCloseable {
        void seek(long offset) throws IOException;

        int read(byte[] buffer, int offset, int length) throws IOException;

        long length() throws IOException;

        @Override
        void close() throws IOException;
    }

    private static String normalizeRelativeDirectory(String relativeDir) {
        if (relativeDir == null || relativeDir.isBlank()) {
            return "";
        }
        String value = relativeDir.trim();
        if (value.startsWith("/") || value.contains("\\") || value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("Landing 目录必须是相对 POSIX 路径");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Landing 目录包含非法路径段");
            }
        }
        return value;
    }

    private static void requireChildName(String childName) {
        if (childName == null || childName.isBlank() || childName.contains("/") || childName.contains("\\")
                || ".".equals(childName) || "..".equals(childName)) {
            throw new IllegalStateException("存储后端返回非法子项名称");
        }
    }

    private String requireSameManifest(String target, byte[] expected, String batchId) {
        try (InputStream input = open(target)) {
            if (java.util.Arrays.equals(input.readAllBytes(), expected)) {
                return uri(target);
            }
            throw new IllegalStateException("manifest batchId 已存在且内容冲突: " + batchId);
        } catch (IOException e) {
            throw new IllegalStateException("读取既有 manifest 失败: " + batchId, e);
        }
    }
}
