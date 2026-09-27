package com.graduation.analytics.runtime.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;

import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * HDFS 落地存储（§8.2/REMOTE_CLUSTER）：通过 Hadoop FileSystem API 操作 hdfs://
 * 命名空间。hadoop-client 与 spark-jobs 同版本 3.3.4；platform-app 将其放入可执行运行包。
 * LOCAL 演示环境不实例化本类（由 LandingStorageResolver 按 profile.landing_uri 选择实现）。
 */
@Slf4j
public class HdfsLandingStorage implements LandingStorage {

    private final String hdfsUri;
    private final Configuration conf;
    private final FileSystem fs;
    private final Path basePath;

    public HdfsLandingStorage(String hdfsUri) {
        this(hdfsUri, new Configuration(), null);
    }

    /** package-private seam：允许离线测试路径策略，不需要启动 NameNode。 */
    HdfsLandingStorage(String hdfsUri, Configuration conf, FileSystem fileSystem) {
        this.hdfsUri = requireHdfsUri(hdfsUri);
        this.basePath = new Path(URI.create(this.hdfsUri));
        this.conf = Objects.requireNonNull(conf, "conf");
        this.conf.set("fs.defaultFS", this.hdfsUri);
        try {
            this.fs = fileSystem == null ? FileSystem.get(basePath.toUri(), this.conf) : fileSystem;
        } catch (IOException e) {
            throw new IllegalStateException("初始化 HDFS FileSystem 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String type() {
        return "hdfs";
    }

    @Override
    public String namespace() {
        return hdfsUri;
    }

    @Override
    public boolean exists(String relativePath) {
        try {
            return fs.exists(toPath(relativePath));
        } catch (IOException e) {
            throw new IllegalStateException("HDFS exists 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> list(String relativeDir) {
        try {
            Path dir = toPath(relativeDir);
            FileStatus[] statuses = fs.listStatus(dir);
            List<String> names = new ArrayList<>();
            for (FileStatus s : statuses) {
                names.add(s.getPath().getName());
            }
            return names;
        } catch (FileNotFoundException e) {
            return List.of();
        } catch (IOException e) {
            throw new IllegalStateException("HDFS list 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream open(String relativePath) {
        try {
            return fs.open(toPath(relativePath));
        } catch (IOException e) {
            throw new IllegalStateException("HDFS open 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public SeekableInput openSeekable(String relativePath) {
        try {
            FSDataInputStream input = fs.open(toPath(relativePath));
            return new SeekableInput() {
                @Override
                public void seek(long offset) throws IOException {
                    if (offset < 0) {
                        throw new IllegalArgumentException("offset 不得小于 0");
                    }
                    input.seek(offset);
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    return input.read(buffer, offset, length);
                }

                @Override
                public long length() throws IOException {
                    return fs.getFileStatus(toPath(relativePath)).getLen();
                }

                @Override
                public void close() throws IOException {
                    input.close();
                }
            };
        } catch (IOException e) {
            throw new IllegalStateException("打开可定位 HDFS landing 文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public FileStat stat(String relativePath) {
        try {
            FileStatus s = fs.getFileStatus(toPath(relativePath));
            return new FileStat(s.getLen(), s.getModificationTime(), s.isDirectory());
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("HDFS stat 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String fileIdentity(String relativePath) {
        Path path = toPath(relativePath);
        try {
            FileStatus status = fs.getFileStatus(path);
            org.apache.hadoop.fs.FileChecksum checksum = fs.getFileChecksum(path);
            if (checksum != null) {
                return "hdfs:" + checksum.getAlgorithmName() + ":"
                        + java.util.HexFormat.of().formatHex(checksum.getBytes());
            }
            // Some compatible filesystems do not expose checksums. Include path and full metadata
            // so a replacement is unlikely to reuse the same identity; do not hide stat failures.
            return "hdfs-meta:" + path.toUri().normalize().getPath() + ":"
                    + status.getLen() + ":" + status.getModificationTime();
        } catch (IOException e) {
            throw new IllegalStateException("读取 HDFS 文件身份失败: " + relativePath, e);
        }
    }

    @Override
    public String checkpointKey(String relativePath) {
        String key = namespace() + "|" + toPath(relativePath).toUri().normalize().getPath();
        if (key.length() > 500) {
            throw new IllegalArgumentException("HDFS checkpoint key 超过 file_checkpoint.file_path VARCHAR(500)");
        }
        return key;
    }

    @Override
    public void createDirectories(String relativeDir) {
        try {
            Path directory = toPath(relativeDir);
            if (!fs.mkdirs(directory) && !fs.exists(directory)) {
                throw new IOException("HDFS mkdirs 返回 false");
            }
        } catch (IOException e) {
            throw new IllegalStateException("HDFS createDirectories 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public java.io.OutputStream createOrAppend(String relativePath) {
        Path target = toPath(relativePath);
        try {
            Path parent = target.getParent();
            if (parent != null && !fs.mkdirs(parent) && !fs.exists(parent)) {
                throw new IOException("HDFS mkdirs 返回 false: " + parent);
            }
            return fs.exists(target) ? fs.append(target) : fs.create(target, false);
        } catch (IOException e) {
            throw new IllegalStateException("HDFS 打开输出失败: " + relativePath, e);
        }
    }

    @Override
    public boolean move(String sourceRelativePath, String targetRelativePath) {
        Path source = toPath(sourceRelativePath);
        Path target = toPath(targetRelativePath);
        try {
            Path parent = target.getParent();
            if (parent != null && !fs.mkdirs(parent) && !fs.exists(parent)) {
                throw new IOException("HDFS mkdirs 返回 false: " + parent);
            }
            return fs.rename(source, target);
        } catch (IOException e) {
            try {
                if (fs.exists(target)) {
                    return false;
                }
            } catch (IOException probeError) {
                e.addSuppressed(probeError);
            }
            throw new IllegalStateException("HDFS move 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean deleteIfExists(String relativePath) {
        Path target = toPath(relativePath);
        try {
            return fs.delete(target, false);
        } catch (IOException e) {
            throw new IllegalStateException("HDFS 删除路径失败: " + relativePath, e);
        }
    }

    @Override
    public String uri(String relativePath) {
        return toPath(relativePath).toUri().toString();
    }

    @Override
    public HealthResult healthCheck() {
        try {
            FileStatus[] statuses = fs.listStatus(basePath);
            return new HealthResult(true, "hdfs reachable @ " + hdfsUri
                    + " (landing entries=" + statuses.length + ")");
        } catch (IOException e) {
            return new HealthResult(false, "hdfs probe failed: " + e.getMessage());
        }
    }

    private Path toPath(String relativePath) {
        String rp = relativePath == null ? "" : relativePath.trim();
        if (rp.isEmpty()) {
            return basePath;
        }
        requireRelativePath(rp);
        Path target = new Path(basePath, rp);
        String base = normalizedPath(basePath.toUri().getPath());
        String candidate = normalizedPath(target.toUri().getPath());
        String prefix = base.endsWith("/") ? base : base + "/";
        if (!candidate.equals(base) && !candidate.startsWith(prefix)) {
            throw new IllegalArgumentException("HDFS 相对路径逃逸出 landing namespace");
        }
        return target;
    }

    private static String requireHdfsUri(String raw) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.trim());
            if (!"hdfs".equalsIgnoreCase(uri.getScheme()) || uri.getAuthority() == null
                    || uri.getAuthority().isBlank() || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            String path = uri.getPath();
            if (path != null) {
                for (String segment : path.split("/")) {
                    if ("..".equals(segment)) {
                        throw new IllegalArgumentException();
                    }
                }
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("hdfs landing uri 必须形如 hdfs://namenode:8020/landing-root", e);
        }
    }

    private static void requireRelativePath(String path) {
        if (path.startsWith("/") || path.contains("\\") || path.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("HDFS 路径必须是相对 landing namespace 的 POSIX 路径");
        }
        for (String segment : path.split("/", -1)) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("HDFS 路径不得包含 ..");
            }
        }
    }

    private static String normalizedPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.replaceAll("/+$", "");
        return normalized.isEmpty() ? "/" : normalized;
    }

}
