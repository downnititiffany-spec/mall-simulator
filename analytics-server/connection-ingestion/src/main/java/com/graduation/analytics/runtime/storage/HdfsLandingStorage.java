package com.graduation.analytics.runtime.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * HDFS 落地存储（§8.2/REMOTE_CLUSTER）：通过 Hadoop FileSystem API 操作 hdfs://
 * 命名空间。hadoop-client 为 provided 依赖（与 spark-jobs 同版本 3.3.4）：客户端
 * jar 由部署端 Spark/Hadoop 环境提供（HADOOP_CLASSPATH），LOCAL 演示环境不加载
 * 本类（RuntimeProfileService 按 profile.type 选择实现）。
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
    public FileStat stat(String relativePath) {
        try {
            FileStatus s = fs.getFileStatus(toPath(relativePath));
            return new FileStat(s.getLen(), s.getModificationTime(), s.isDirectory());
        } catch (IOException e) {
            throw new IllegalStateException("HDFS stat 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String writeManifest(String batchId, String manifestJson) {
        try {
            Path manifest = manifestPath(batchId);
            try (org.apache.hadoop.fs.FSDataOutputStream out = fs.create(manifest, true)) {
                out.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            }
            return manifest.toUri().toString();
        } catch (IOException e) {
            throw new IllegalStateException("HDFS writeManifest 失败: " + e.getMessage(), e);
        }
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

    private Path manifestPath(String batchId) {
        if (batchId == null || !batchId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || ".".equals(batchId) || "..".equals(batchId)) {
            throw new IllegalArgumentException("batchId 必须是单段安全标识符");
        }
        return toPath("manifests/" + batchId + ".json");
    }
}
