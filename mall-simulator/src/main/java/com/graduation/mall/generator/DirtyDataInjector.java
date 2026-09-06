package com.graduation.mall.generator;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脏数据注入器（§20.5）：在事件流之外按配置注入 7 类脏数据，
 * 独立写入 {landing}/dirty/{yyyyMMddHH}.jsonl（不触碰业务库，不破坏有效订单），
 * 每类保存期望处理结果（拒绝/隔离/去重/告警）供质量模块与论文实验使用。
 */
@Component
public class DirtyDataInjector {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final Environment environment;
    private final ObjectMapper objectMapper;

    public DirtyDataInjector(Environment environment, ObjectMapper objectMapper) {
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    public record DirtySample(String type, String expectedHandling, String json) {
    }

    /**
     * 按 dirtyDataRate 比例注入脏数据。
     *
     * @param normalEventCount 正常事件数（决定注入条数）
     * @param normalEventIds   正常 event_id 样本（duplicate_event_id 复用其一）
     */
    public List<DirtySample> inject(double dirtyDataRate, int normalEventCount,
                                    List<String> normalEventIds, DistributionKit rng) {
        int count = (int) Math.round(dirtyDataRate * normalEventCount);
        if (count <= 0) {
            return List.of();
        }
        List<DirtySample> samples = new ArrayList<>();
        String[] types = {"missing_field", "illegal_enum", "future_time", "negative_qty",
                "amount_mismatch", "duplicate_event_id", "unknown_schema"};
        OffsetDateTime base = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        for (int i = 0; i < count; i++) {
            String type = types[i % types.length];
            String json = build(type, i, base, normalEventIds);
            samples.add(new DirtySample(type, expectedHandling(type), json));
        }

        Path dir = Path.of(environment.getProperty("mall.landing.path", "./landing")).resolve("dirty");
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(HOUR.format(base) + ".jsonl");
            for (DirtySample s : samples) {
                Files.writeString(file, s.json() + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("脏数据写入失败", e);
        }
        return samples;
    }

    /** 每种脏类型对应的期望处理（§20.5：拒绝/隔离/去重/告警） */
    public static String expectedHandling(String type) {
        return switch (type) {
            case "missing_field", "illegal_enum", "negative_qty" -> "reject";
            case "amount_mismatch" -> "isolate";
            case "duplicate_event_id" -> "dedupe";
            case "future_time" -> "warn";
            case "unknown_schema" -> "quarantine";
            default -> "reject";
        };
    }

    private String build(String type, int seq, OffsetDateTime base, List<String> normalEventIds) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        String eventId = switch (type) {
            case "duplicate_event_id" -> normalEventIds.isEmpty()
                    ? "dirty-dup-" + seq : normalEventIds.get(seq % normalEventIds.size());
            default -> "dirty-" + type + "-" + seq;
        };
        envelope.put("event_id", eventId);
        envelope.put("event_type", "behavior");
        envelope.put("event_time", switch (type) {
            case "future_time" -> base.plusDays(1).toString();
            default -> base.toString();
        });
        envelope.put("ingest_time", base.toString());
        envelope.put("source_system", "mock-mall");
        envelope.put("schema_version", type.equals("unknown_schema") ? "9.9" : "1.0");
        envelope.put("trace_id", "dirty-trace-" + seq);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", type.equals("missing_field") ? null : String.valueOf(100000 + seq));
        payload.put("product_id", String.valueOf(1000 + seq % 100));
        payload.put("session_id", "dirty-session-" + seq);
        payload.put("behavior_type", type.equals("illegal_enum") ? "fly" : "view");
        payload.put("channel", "app");
        if (type.equals("negative_qty")) {
            payload.put("quantity", -1);
        }
        if (type.equals("amount_mismatch")) {
            payload.put("order_id", "dirty-order-" + seq);
            payload.put("amount", "200.00");
            payload.put("total_amount", "100.00");
        }
        envelope.put("payload", payload);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}