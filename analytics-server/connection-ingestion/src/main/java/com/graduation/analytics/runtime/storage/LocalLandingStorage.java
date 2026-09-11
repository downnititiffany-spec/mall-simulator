package com.graduation.analytics.runtime.storage;

import com.graduation.analytics.common.LandingUri;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地文件落地存储（§8.2/LOCAL 模式）：landing_uri 形如 file:///D:/data/landing
 * 或 file://./landing（相对进程工作目录）。manifest 目录固定 manifests/，
 * accepted/quarantine 由采集侧按批次写入（§9.1）。
 */
@Slf4j
@Component
public class LocalLandingStorage implements LandingStorage {

    private final Path root;

    public LocalLandingStorage(@Value("${platform.landing.local-root:./landing}") String localRoot) {
        this.root = toPath(localRoot);
        log.info("LocalLandingStorage root = {}", this.root.toAbsolutePath());
    }

    /**
     * 配置项（不是环境档案）→ 本地根路径：配置侧允许缺省为 ./landing，
     * 解析本身复用唯一实现 {@link LandingUri#resolve(String)}（标准 file:/// 写法可用，非法值明确报错）。
     */
    private static Path toPath(String uriOrPath) {
        String value = (uriOrPath == null || uriOrPath.isBlank()) ? "./landing" : uriOrPath;
        return LandingUri.resolve(value);
    }

    @Override
    public String type() {
        return "local";
    }

    @Override
    public String namespace() {
        return root.toUri().toString();
    }

    private Path resolve(String relativePath) {
        Path base = root.normalize();
        Path target = base.resolve(relativePath == null ? "" : relativePath).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("路径越界: " + relativePath);
        }
        return target;
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    @Override
    public List<String> list(String relativeDir) {
        Path dir = resolve(relativeDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            log.warn("list {} failed: {}", dir, e.getMessage());
            return List.of();
        }
    }

    @Override
    public InputStream open(String relativePath) {
        try {
            return Files.newInputStream(resolve(relativePath));
        } catch (IOException e) {
            throw new IllegalStateException("打开 landing 文件失败: " + relativePath, e);
        }
    }

    @Override
    public FileStat stat(String relativePath) {
        Path p = resolve(relativePath);
        try {
            if (!Files.exists(p)) {
                return null;
            }
            return new FileStat(Files.size(p), Files.getLastModifiedTime(p).toMillis(), Files.isDirectory(p));
        } catch (IOException e) {
            log.warn("stat {} failed: {}", p, e.getMessage());
            return null;
        }
    }

    @Override
    public String writeManifest(String batchId, String manifestJson) {
        Path dir = resolve("manifests");
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(batchId + ".json");
            Files.writeString(file, manifestJson, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return file.toUri().toURL().toString();
        } catch (IOException e) {
            throw new IllegalStateException("写入 manifest 失败: " + batchId, e);
        }
    }

    @Override
    public HealthResult healthCheck() {
        try {
            Files.createDirectories(root.resolve("manifests"));
            Path probe = root.resolve(".local-landing-probe");
            Files.writeString(probe, "ok", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            boolean readable = Files.readString(probe, StandardCharsets.UTF_8).equals("ok");
            return new HealthResult(readable, "local landing rw ok @ " + root);
        } catch (Exception e) {
            return new HealthResult(false, "local landing probe failed: " + e.getMessage());
        }
    }
}