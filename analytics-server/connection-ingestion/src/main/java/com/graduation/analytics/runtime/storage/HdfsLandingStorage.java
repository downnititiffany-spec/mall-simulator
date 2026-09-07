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

    public HdfsLandingStorage(String hdfsUri) {
        if (hdfsUri == null || !hdfsUri.startsWith("hdfs://")) {
            throw new IllegalArgumentException("hdfs landing uri 必须形如 hdfs://namenode:8020/...: " + hdfsUri);
        }
        this.hdfsUri = hdfsUri;
        this.conf = new Configuration();
        this.conf.set("fs.defaultFS", hdfsUri);
        try {
            this.fs = FileSystem.get(URI.create(hdfsUri), conf);
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
            Path manifests = new Path(toPath("manifests"), batchId + ".json");
            try (org.apache.hadoop.fs.FSDataOutputStream out = fs.create(manifests, true)) {
                out.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            }
            return manifests.toUri().toString();
        } catch (IOException e) {
            throw new IllegalStateException("HDFS writeManifest 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public HealthResult healthCheck() {
        try {
            Path rootPath = new Path("/");
            FileStatus[] statuses = fs.listStatus(rootPath);
            return new HealthResult(true, "hdfs reachable @ " + hdfsUri + " (root entries=" + statuses.length + ")");
        } catch (IOException e) {
            return new HealthResult(false, "hdfs probe failed: " + e.getMessage());
        }
    }

    private Path toPath(String relativePath) {
        String rp = relativePath == null ? "" : relativePath;
        return new Path(fullPath(rp));
    }

    private String fullPath(String relativePath) {
        String base = hdfsUri;
        String p = relativePath == null || relativePath.isBlank() ? ""
                : relativePath.startsWith("/") ? relativePath : "/" + relativePath;
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + p;
    }
}