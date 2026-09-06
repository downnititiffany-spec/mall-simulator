package com.graduation.mall.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 按小时滚动的 JSON 事件日志写入器（§5.2.5 第一阶段采集链路）：
 * {landing}/events/{yyyyMMddHH}.jsonl —— 该目录即 Flume Taildir Source 的采集入口。
 * landing 路径每次写入时从 Environment 解析（测试可动态切换目录，生产可用配置覆盖）。
 */
@Component
public class RollingJsonEventWriter {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final Environment environment;
    private final ObjectMapper objectMapper;

    public RollingJsonEventWriter(Environment environment, ObjectMapper objectMapper) {
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    /**
     * 追加一行事件到对应小时的 jsonl（调用方负责幂等，writer 仅保证落盘）。
     */
    public void write(EventEnvelope envelope) {
        try {
            Path dir = eventsDir();
            Files.createDirectories(dir);
            String hourKey = HOUR.format(OffsetDateTime.parse(envelope.eventTime()));
            Path file = dir.resolve(hourKey + ".jsonl");
            Files.writeString(file, envelope.toJson(objectMapper) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("事件日志写入失败: " + envelope.eventId(), e);
        }
    }

    /** 事件目录根（由配置 mall.landing.path 决定，默认 ./landing） */
    public Path eventsDir() {
        String landing = environment.getProperty("mall.landing.path", "./landing");
        return Path.of(landing).resolve("events");
    }

    /** 最近一个滚动文件（可能为空） */
    public Optional<Path> latestFile() {
        Path dir = eventsDir();
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .max((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("事件目录扫描失败", e);
        }
    }
}