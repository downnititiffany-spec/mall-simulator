package com.graduation.generator.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JsonlEventSink} 真实文件测试（E2，真写盘、真读回，不用 Mock）。
 *
 * <p>规模取 55 行：与既有黄金链夹具（55 行 / 接受 51 / 隔离 4）同量级，跑得快且足以暴露轮转、
 * 计数、时间范围与 checksum 的错误。</p>
 */
class JsonlEventSinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RUN_ID = "gen-20260911-0001";

    @Test
    @DisplayName("55 行 JSONL + 清单：行数、字节数、checksum、时间范围、synthetic 标记全部可对账")
    void writesFiftyFiveRecordsAndReconcilableManifest(@TempDir Path tmp) throws IOException {
        Path jsonl;
        ArtifactManifest manifest;
        try (JsonlEventSink sink = new JsonlEventSink(RUN_ID, tmp, ContractFormat.SCHEMA_VERSION, 1000)) {
            for (CanonicalEvent event : events(55)) {
                sink.write(event);
                assertThat(sink.rotateIfNeeded()).as("未达阈值不得轮转").isEmpty();
            }
            sink.flush();
            manifest = sink.closeAndBuildManifest();
            jsonl = sink.currentFile();
            assertThat(sink.artifacts()).as("清单入账的制品数为 1").hasSize(1);
        }

        List<String> lines = Files.readAllLines(jsonl, StandardCharsets.UTF_8);
        assertThat(lines).as("55 行事件").hasSize(55);

        // 每行都是合法 JSON，且信封 8 字段齐全
        for (String line : lines) {
            JsonNode node = MAPPER.readTree(line);
            assertThat(node.size()).as("信封字段数固定为 8").isEqualTo(8);
            for (String field : List.of("event_id", "event_type", "event_time", "ingest_time",
                    "source_system", "schema_version", "trace_id", "payload")) {
                assertThat(node.has(field)).as("信封缺字段 " + field).isTrue();
            }
        }

        long actualBytes = Files.size(jsonl);
        assertThat(manifest.bytes()).as("清单字节数必须等于真实文件字节数").isEqualTo(actualBytes);
        assertThat(manifest.recordCount()).as("清单记录数").isEqualTo(55);
        assertThat(manifest.checksum()).as("清单 checksum 必须等于文件内容 SHA-256")
                .isEqualTo(sha256(jsonl));
        assertThat(manifest.synthetic()).as("V2.1 §3.3 B：文件模式清单必须 synthetic=true").isTrue();
        assertThat(manifest.runId()).isEqualTo(RUN_ID);
        assertThat(manifest.schemaVersion()).isEqualTo(ContractFormat.SCHEMA_VERSION);
        assertThat(manifest.uri()).as("制品 uri 为绝对 file:/// URI").startsWith("file:///").contains(".jsonl");
        assertThat(manifest.minEventTime()).as("最小 event_time（第 1 条）").isEqualTo("2026-09-01T00:00:00+08:00");
        assertThat(manifest.maxEventTime()).as("最大 event_time（第 55 条=54 分钟后）")
                .isEqualTo("2026-09-01T00:54:00+08:00");

        // 清单文件真实落盘，且内容与返回对象一致
        Path manifestPath = jsonl.resolveSibling(
                jsonl.getFileName().toString().replace(".jsonl", ".manifest.json"));
        assertThat(manifestPath).exists();
        JsonNode onDisk = MAPPER.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        assertThat(onDisk.get("record_count").asLong()).isEqualTo(55);
        assertThat(onDisk.get("synthetic").asBoolean()).isTrue();
        assertThat(onDisk.size()).as("清单字段数固定为 9（契约 additionalProperties:false）").isEqualTo(9);
    }

    @Test
    @DisplayName("轮转：25 行 / 每制品 10 行 → 3 个 JSONL、3 份清单、2 次轮转")
    void rotatesArtifactsAndKeepsPerFileCounts(@TempDir Path tmp) throws IOException {
        List<Artifact> rotated = new ArrayList<>();
        ArtifactManifest last;
        try (JsonlEventSink sink = new JsonlEventSink(RUN_ID, tmp, ContractFormat.SCHEMA_VERSION, 10)) {
            for (CanonicalEvent event : events(25)) {
                sink.write(event);
                Optional<Artifact> artifact = sink.rotateIfNeeded();
                artifact.ifPresent(rotated::add);
            }
            last = sink.closeAndBuildManifest();
            assertThat(sink.artifacts()).hasSize(3);
        }

        assertThat(rotated).as("轮转两次").hasSize(2);
        assertThat(rotated).extracting(Artifact::recordCount).containsExactly(10L, 10L);
        assertThat(last.recordCount()).as("末个制品 5 行").isEqualTo(5);

        Path runDir = tmp.resolve(RUN_ID);
        try (var files = Files.list(runDir)) {
            List<String> names = files.map(path -> path.getFileName().toString()).sorted().toList();
            assertThat(names).containsExactly(
                    "events-0001.jsonl", "events-0001.manifest.json",
                    "events-0002.jsonl", "events-0002.manifest.json",
                    "events-0003.jsonl", "events-0003.manifest.json");
        }
        assertThat(Files.readAllLines(runDir.resolve("events-0002.jsonl"))).hasSize(10);
        assertThat(Files.readAllLines(runDir.resolve("events-0003.jsonl"))).hasSize(5);
    }

    @Test
    @DisplayName("可复现：同一事件序列两次生成，checksum 逐字节一致（V2.1 §4.2）")
    void sameInputProducesIdenticalChecksum(@TempDir Path tmp) throws IOException {
        String first = writeAndChecksum(tmp.resolve("a"), 20);
        String second = writeAndChecksum(tmp.resolve("b"), 20);
        assertThat(second).as("相同 plan_version + seed + time_window 必须得到相同制品").isEqualTo(first);
        assertThat(first).hasSize(64).as("SHA-256 十六进制长度");
    }

    @Test
    @DisplayName("边界：产物目录不得落在分析平台 landing（§3.3 B）")
    void refusesToWriteIntoPlatformLanding(@TempDir Path tmp) {
        assertThatThrownBy(() -> new JsonlEventSink(RUN_ID, tmp.resolve("landing").resolve("events"), "1.0", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("landing");
        assertThatThrownBy(() -> new JsonlEventSink(RUN_ID, tmp.resolve("LANDING"), "1.0", 10))
                .as("大小写不敏感")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("关闭后再写必须失败，且清单只能产出一次（避免二次关闭覆盖对账数据）")
    void rejectsWriteAfterClose(@TempDir Path tmp) {
        JsonlEventSink sink = new JsonlEventSink(RUN_ID, tmp, ContractFormat.SCHEMA_VERSION, 10);
        sink.write(events(1).get(0));
        sink.closeAndBuildManifest();
        assertThatThrownBy(() -> sink.write(events(1).get(0))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(sink::closeAndBuildManifest).isInstanceOf(IllegalStateException.class);
    }

    // ---------- 工具 ----------

    private static String writeAndChecksum(Path outDir, int count) {
        try (JsonlEventSink sink = new JsonlEventSink(RUN_ID, outDir, ContractFormat.SCHEMA_VERSION, 1000)) {
            events(count).forEach(sink::write);
            return sink.closeAndBuildManifest().checksum();
        }
    }

    /** 55 行量级的事件序列：确定性子 seed 可复现（ID 为序号，时间按分钟递增） */
    private static List<CanonicalEvent> events(int count) {
        AtomicInteger seq = new AtomicInteger();
        CanonicalEventFactory factory = new CanonicalEventFactory(
                () -> "evt-" + seq.get(), () -> "trace-" + RUN_ID);
        List<CanonicalEvent> events = new ArrayList<>();
        Instant base = Instant.parse("2026-08-31T16:00:00Z"); // = 2026-09-01T00:00:00+08:00
        for (int i = 0; i < count; i++) {
            seq.set(i + 1);
            Instant eventTime = base.plusSeconds(60L * i);
            String eventTimeText = ContractFormat.time(eventTime);
            events.add(switch (i % 5) {
                case 0 -> factory.behavior("u" + (i % 3 + 1), "p" + (i % 4 + 1), "sess-" + i,
                        "view", i % 2 == 0 ? "app" : "pc", eventTimeText, base.plusSeconds(300));
                case 1 -> factory.create(EventTypes.PRODUCT_CREATED,
                        CanonicalPayloads.productCreated("p" + i, "商品" + i, "c1", "b1",
                                ContractFormat.amount(1999), ContractFormat.amount(1200), "on_sale"),
                        eventTimeText, base.plusSeconds(300));
                case 2 -> factory.create(EventTypes.ORDER_CREATED,
                        CanonicalPayloads.orderCreated("o" + i, "u1",
                                List.of(new CanonicalPayloads.OrderItem("p1", 2,
                                        ContractFormat.amount(999), ContractFormat.amount(0),
                                        ContractFormat.amount(1998))),
                                ContractFormat.amount(1998), eventTimeText),
                        eventTimeText, base.plusSeconds(300));
                case 3 -> factory.create(EventTypes.ORDER_PAID,
                        CanonicalPayloads.orderPaid("o" + i, "u1", "pay-" + i,
                                ContractFormat.amount(1998), eventTimeText),
                        eventTimeText, base.plusSeconds(300));
                default -> factory.create(EventTypes.STOCK_CHANGED,
                        CanonicalPayloads.stockChanged("p1", "inbound", 10, ContractFormat.amount(110)),
                        eventTimeText, base.plusSeconds(300));
            });
        }
        return events;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(JsonlEventSink.CHECKSUM_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
