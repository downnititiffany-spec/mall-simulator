package com.graduation.generator.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.generator.contract.Artifact;
import com.graduation.generator.contract.ContractFormat;
import com.graduation.generator.contract.EventTypes;
import com.graduation.generator.contract.JsonlEventSink;
import com.graduation.generator.core.DirtySample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileModeGenerationEngine} + {@link JsonlEventSink} 的端到端测试（V2.1 §4.2 可复现 / §4.3 事件预算与异常样本）。
 *
 * <p>七组断言：可复现性、主流契约有效性、清单配对与 {@code synthetic=true}、引用完整性、金额一致性、
 * 事件预算精确性、脏样本与取消语义。所有断言都读回磁盘上真实写出的 JSONL 再判断，不 mock 任何协作方。</p>
 */
class FileModeGenerationEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 场景键 = {@code ScenarioRegistry} 的枚举名；normal 的因子全为 1.0 且价格乘数为 1.0（不产生 product_updated） */
    private static final String SCENARIO = "normal";

    /**
     * {@code refund_rise}：refundMultiplier=4.0（支付后退款概率 0.08 → 0.32），其余因子中性。
     * 退款/金额口径的断言必须在这个场景下跑，否则退款链路大概率根本不发生，netSale 断言会空转。
     */
    private static final String REFUND_SCENARIO = "refund_rise";
    private static final String RUN_ID = "run-engine-test";
    private static final Instant WINDOW_START = Instant.parse("2026-03-02T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-03-08T00:00:00Z");
    private static final int RATE_PER_SECOND = 500;
    private static final int MAX_RECORDS_PER_FILE = 50;

    /** 契约信封的 8 个必填字段（canonical-event.v1.schema.json 顶层 required） */
    private static final Set<String> ENVELOPE_FIELDS = Set.of("event_id", "event_type", "event_time", "ingest_time",
            "source_system", "schema_version", "trace_id", "payload");

    /** 金额形态字段（契约 $defs/amount）：只要是字符串就必须匹配 {@link ContractFormat#AMOUNT_PATTERN} */
    private static final Set<String> AMOUNT_FIELDS = Set.of("amount", "total_amount", "price", "cost", "unit_price",
            "discount", "available_qty", "reserved_qty");

    private static final Set<String> CHANNELS = Set.of("app", "pc", "h5");
    private static final Set<String> BEHAVIOR_TYPES = Set.of("view", "favorite", "cart_add", "cart_remove", "search");

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------ 1. 确定性

    @Test
    void sameRequestKeyProducesByteIdenticalJsonlAndDifferentSeedDoesNot() {
        RunResult first = run(RUN_ID, tempDir.resolve("first"), 240, GenerationRequest.DIRTY_NONE, 20260302L,
                () -> false);
        RunResult sameKey = run(RUN_ID, tempDir.resolve("second"), 240, GenerationRequest.DIRTY_NONE, 20260302L,
                () -> false);

        List<Path> firstFiles = first.files();
        List<Path> sameKeyFiles = sameKey.files();
        assertTrue(firstFiles.size() > 1, () -> "轮转应产出多个制品，实际=" + firstFiles);
        assertEquals(firstFiles.size(), sameKeyFiles.size(), "同参两次运行的制品数必须一致");

        for (int i = 0; i < firstFiles.size(); i++) {
            Path left = firstFiles.get(i);
            Path right = sameKeyFiles.get(i);
            assertEquals(left.getFileName().toString(), right.getFileName().toString(), "制品文件名必须一致");
            assertEquals(sha256(left), sha256(right), () -> "制品字节不一致：" + left + " vs " + right);
            assertEquals(first.jsonlArtifacts().get(i).checksum(), sameKey.jsonlArtifacts().get(i).checksum(),
                    "声明的 checksum 必须一致");
            assertEquals(sha256(left), first.jsonlArtifacts().get(i).checksum(),
                    "清单声明的 checksum 必须等于文件真实 SHA-256");
        }

        // runId 不参与随机数派生（只写清单）：换 runId 仍必须逐字节相同
        RunResult otherRunId = run("run-engine-test-copy", tempDir.resolve("other-run-id"), 240,
                GenerationRequest.DIRTY_NONE, 20260302L, () -> false);
        assertEquals(runDigest(first), runDigest(otherRunId), "runId 不得影响产物字节（可复现性键不含 runId）");

        // 换 seed 必须换字节
        RunResult otherSeed = run(RUN_ID, tempDir.resolve("other-seed"), 240, GenerationRequest.DIRTY_NONE, 20260303L,
                () -> false);
        assertEquals(firstFiles.size(), otherSeed.files().size());
        assertNotEquals(runDigest(first), runDigest(otherSeed), "不同 seed 必须产出不同字节");
    }

    // ------------------------------------------------------------------ 2. 主流契约有效性

    @Test
    void mainStreamIsFullyContractValidWithDirtyProfileNone() {
        RunResult result = run(RUN_ID, tempDir.resolve("contract"), 300, GenerationRequest.DIRTY_NONE, 4242L,
                () -> false);

        assertEquals(12, EventTypes.ALL.size(), "契约 event_type 词表应为 12 类");
        assertFalse(result.lines().isEmpty(), "必须写出事件");

        for (EventLine line : result.lines()) {
            assertNull(contractViolation(line.raw(), line.node()),
                    () -> line.where() + " 违反契约：" + line.raw());
        }

        Map<String, Integer> counts = typeCounts(result);
        for (String required : List.of(EventTypes.USER_REGISTERED, EventTypes.PRODUCT_CREATED, EventTypes.BEHAVIOR,
                EventTypes.ORDER_CREATED, EventTypes.ORDER_PAID, EventTypes.ORDER_CANCELLED,
                EventTypes.STOCK_RESERVED)) {
            assertTrue(counts.getOrDefault(required, 0) > 0,
                    () -> "校验覆盖不足：本次运行没有 " + required + "，实际分布=" + counts);
        }
        for (String type : counts.keySet()) {
            assertTrue(EventTypes.ALL.contains(type), () -> "事件类型不在契约 12 类内：" + type);
        }
    }

    // ------------------------------------------------------------------ 3. 清单配对 + synthetic

    @Test
    void eachJsonlArtifactHasSiblingManifestWithChecksumAndSyntheticFlag() throws IOException {
        RunResult result = run(RUN_ID, tempDir.resolve("manifest"), 130, GenerationRequest.DIRTY_NONE, 7L,
                () -> false);

        List<Path> files = result.files();
        assertEquals(3, files.size(), () -> "130 条 / 每文件 50 条应轮转成 3 个制品，实际=" + files);
        assertEquals(files.size(), result.jsonlArtifacts().size(), "artifacts() 应逐份对应 JSONL 制品");

        for (int i = 0; i < files.size(); i++) {
            Artifact artifact = result.jsonlArtifacts().get(i);
            Path jsonl = files.get(i);
            assertEquals(jsonl, Path.of(URI.create(artifact.uri())), "uri 必须可还原为磁盘路径");
            assertTrue(Files.exists(jsonl), () -> "制品文件不存在：" + jsonl);

            Path manifestPath = jsonl.resolveSibling(
                    jsonl.getFileName().toString().replace(".jsonl", ".manifest.json"));
            assertTrue(Files.exists(manifestPath), () -> "缺少同名清单：" + manifestPath);

            JsonNode manifest = MAPPER.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
            assertTrue(manifest.path("synthetic").asBoolean(),
                    () -> "清单必须声明 synthetic=true：" + manifestPath);
            assertEquals(RUN_ID, manifest.path("run_id").asText(), "清单 run_id 必须等于请求 runId");
            assertEquals(artifact.uri(), manifest.path("uri").asText(), "清单 uri 必须指向它描述的制品");
            assertEquals(sha256(jsonl), manifest.path("checksum").asText(),
                    "清单 checksum 必须是该 JSONL 的 SHA-256");
            assertEquals(Files.readAllLines(jsonl, StandardCharsets.UTF_8).size(),
                    manifest.path("record_count").asLong(), "清单 record_count 必须等于文件行数");
            assertEquals(artifact.checksum(), manifest.path("checksum").asText());
            assertEquals(artifact.recordCount(), manifest.path("record_count").asLong());
            assertEquals(artifact.bytes(), Files.size(jsonl), "清单 bytes 必须等于文件字节数");
        }

        Path runDir = files.get(0).getParent();
        try (var entries = Files.list(runDir)) {
            List<String> names = entries.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(files.size() * 2, names.size(), () -> "每个 JSONL 必须恰好配一份清单，实际=" + names);
        }
    }

    // ------------------------------------------------------------------ 4. 引用完整性

    @Test
    void referentialIntegrityHoldsAcrossTheWholeStream() {
        RunResult result = runIn(REFUND_SCENARIO, RUN_ID, tempDir.resolve("refs"), 600, GenerationRequest.DIRTY_NONE,
                99L, () -> false);

        Set<String> users = new HashSet<>();
        Set<String> products = new HashSet<>();
        Set<String> orders = new HashSet<>();

        for (EventLine line : result.lines()) {
            JsonNode event = line.node();
            assertNotNull(event, () -> line.where() + " 不是合法 JSON");
            String type = event.path("event_type").asText();
            JsonNode payload = event.path("payload");

            String orderId = text(payload, "order_id");
            if (EventTypes.ORDER_CREATED.equals(type)) {
                assertNotNull(orderId, () -> line.where() + " order_created 缺 order_id");
                assertTrue(orders.add(orderId), () -> line.where() + " 重复创建订单 " + orderId);
            } else if (orderId != null) {
                assertTrue(orders.contains(orderId),
                        () -> line.where() + " 引用未创建的 order_id=" + orderId + "（type=" + type + "）");
            }

            String userId = text(payload, "user_id");
            if (EventTypes.USER_REGISTERED.equals(type)) {
                assertNotNull(userId, () -> line.where() + " user_registered 缺 user_id");
                users.add(userId);
            } else if (userId != null) {
                assertTrue(users.contains(userId),
                        () -> line.where() + " 引用未注册的 user_id=" + userId + "（type=" + type + "）");
            }

            String productId = text(payload, "product_id");
            if (EventTypes.PRODUCT_CREATED.equals(type)) {
                assertNotNull(productId, () -> line.where() + " product_created 缺 product_id");
                products.add(productId);
            } else if (productId != null) {
                assertTrue(products.contains(productId),
                        () -> line.where() + " 引用未创建的 product_id=" + productId + "（type=" + type + "）");
            }
        }

        Map<String, Integer> counts = typeCounts(result);
        assertEquals(600, result.lineCount());
        for (String required : List.of(EventTypes.USER_REGISTERED, EventTypes.PRODUCT_CREATED, EventTypes.BEHAVIOR,
                EventTypes.ORDER_CREATED, EventTypes.ORDER_PAID, EventTypes.ORDER_CANCELLED,
                EventTypes.REFUND_CREATED, EventTypes.REFUND_COMPLETED, EventTypes.STOCK_RESERVED,
                EventTypes.STOCK_RELEASED)) {
            assertTrue(counts.getOrDefault(required, 0) > 0,
                    () -> "引用完整性覆盖不足：本次运行没有 " + required + "，实际分布=" + counts);
        }
        assertTrue(users.size() >= 3, () -> "用户池过小：" + users.size());
        assertTrue(products.size() >= 4, () -> "商品池过小：" + products.size());
    }

    // ------------------------------------------------------------------ 5. 金额一致性

    @Test
    void moneyIsConsistentWithOrderItemsAndRunTotals() {
        RunResult result = runIn(REFUND_SCENARIO, RUN_ID, tempDir.resolve("money"), 600,
                GenerationRequest.DIRTY_NONE, 20260401L, () -> false);

        int orderCreated = 0;
        int orderPaid = 0;
        int refundCompleted = 0;
        BigDecimal paidSum = BigDecimal.ZERO;
        BigDecimal refundSum = BigDecimal.ZERO;

        for (EventLine line : result.lines()) {
            JsonNode payload = line.node().path("payload");
            String type = line.node().path("event_type").asText();
            switch (type) {
                case EventTypes.ORDER_CREATED -> {
                    orderCreated++;
                    JsonNode items = payload.path("items");
                    assertTrue(items.isArray() && !items.isEmpty(), () -> line.where() + " items 缺失");
                    BigDecimal itemSum = BigDecimal.ZERO;
                    for (JsonNode item : items) {
                        BigDecimal unitPrice = new BigDecimal(text(item, "unit_price"));
                        BigDecimal discount = new BigDecimal(text(item, "discount"));
                        BigDecimal amount = new BigDecimal(text(item, "amount"));
                        int quantity = item.path("quantity").asInt();
                        assertEquals(0, unitPrice.multiply(BigDecimal.valueOf(quantity)).subtract(discount)
                                        .compareTo(amount),
                                () -> line.where() + " items[].amount != unit_price * quantity - discount：" + item);
                        itemSum = itemSum.add(amount);
                    }
                    BigDecimal total = new BigDecimal(text(payload, "total_amount"));
                    BigDecimal itemsSum = itemSum;
                    assertEquals(0, total.compareTo(itemsSum),
                            () -> line.where() + " total_amount=" + total.toPlainString()
                                    + " != Σ items.amount=" + itemsSum.toPlainString());
                }
                case EventTypes.ORDER_PAID -> {
                    orderPaid++;
                    paidSum = paidSum.add(new BigDecimal(text(payload, "amount")));
                }
                case EventTypes.REFUND_COMPLETED -> {
                    refundCompleted++;
                    refundSum = refundSum.add(new BigDecimal(text(payload, "amount")));
                }
                default -> {
                    // 其余类型不参与金额对账
                }
            }
        }

        assertTrue(orderCreated > 0, "本次运行没有 order_created");
        assertTrue(orderPaid > 0, "本次运行没有 order_paid");
        assertTrue(refundCompleted > 0,
                () -> "refund_rise 场景下仍无 refund_completed，退款口径未被覆盖："
                        + "orderPaid=" + result.outcome().result().ordersPaid());

        assertEquals(orderCreated, result.outcome().result().ordersCreated(), "ordersCreated 必须等于磁盘订单数");
        assertEquals(orderPaid, result.outcome().result().ordersPaid(), "ordersPaid 必须等于磁盘支付数");
        assertEquals(refundCompleted, result.outcome().result().refundsCompleted(),
                "refundsCompleted 必须等于磁盘退款完成数");

        BigDecimal gmv = result.outcome().result().gmv();
        BigDecimal paidTotal = paidSum;
        assertEquals(0, gmv.compareTo(paidTotal),
                () -> "outcome.gmv=" + gmv.toPlainString()
                        + " != Σ order_paid.amount=" + paidTotal.toPlainString());
        BigDecimal expectedNet = gmv.subtract(refundSum);
        assertEquals(0, result.outcome().result().netSale().compareTo(expectedNet),
                () -> "netSale=" + result.outcome().result().netSale().toPlainString()
                        + " != gmv - Σ refund_completed.amount=" + expectedNet.toPlainString());
    }

    // ------------------------------------------------------------------ 6. 事件预算精确性

    @Test
    void eventBudgetIsExactAndStatsMatchDisk() {
        long eventCount = 137L;
        RunResult result = run(RUN_ID, tempDir.resolve("budget"), eventCount, GenerationRequest.DIRTY_NONE, 1234L,
                () -> false);
        EngineOutcome outcome = result.outcome();

        assertEquals(eventCount, outcome.successCount(), "dirtyProfile=none 时 successCount 必须等于事件预算");
        assertEquals(0L, outcome.failedCount(), "本次运行不得有写入失败");
        assertEquals(eventCount, outcome.result().totalEvents(), "result.totalEvents 必须等于事件预算");
        assertEquals(eventCount, result.lineCount(), "全部 EVENT_JSONL 制品的行数之和必须等于事件预算");

        Map<String, Long> onDisk = new TreeMap<>();
        for (EventLine line : result.lines()) {
            onDisk.merge(line.node().path("event_type").asText(), 1L, Long::sum);
        }
        Map<String, Long> declared = new TreeMap<>();
        outcome.eventStats().forEach((type, stat) -> declared.put(type, stat.count()));

        assertEquals(onDisk, declared, "eventStats 的逐类型计数必须等于磁盘逐类型行数");
        assertEquals(eventCount, declared.values().stream().mapToLong(Long::longValue).sum(),
                "eventStats 总数必须等于事件预算");
        assertEquals(outcome.successCount(),
                outcome.eventStats().values().stream().mapToLong(EventTypeStat::count).sum());
    }

    // ------------------------------------------------------------------ 7a. 脏样本

    @Test
    void lightDirtyProfileQuarantinesSamplesAndKeepsMainStreamValid() {
        RunResult result = run(RUN_ID, tempDir.resolve("dirty-light"), 400, "light", 555L, () -> false);
        EngineOutcome outcome = result.outcome();

        List<DirtySample> samples = outcome.dirtySamples();
        assertFalse(samples.isEmpty(), "light 档位必须注入异常样本");

        Set<String> vocabulary = new TreeSet<>(Arrays.asList(FileModeGenerationEngine.DIRTY_TYPES));
        Map<String, Long> expectedByType = new TreeMap<>();
        for (DirtySample sample : samples) {
            assertTrue(vocabulary.contains(sample.type()),
                    () -> "脏样本类型不在词表内：" + sample.type() + "，词表=" + vocabulary);
            assertFalse(sample.expectedHandling() == null || sample.expectedHandling().isBlank(),
                    () -> "脏样本必须给出期望处置：" + sample.type());
            expectedByType.merge(sample.type(), 1L, Long::sum);

            String violation = contractViolation(sample.json(), parseOrNull(sample.json()));
            assertNotNull(violation,
                    () -> "脏样本居然通过契约校验：type=" + sample.type() + " json=" + sample.json());
        }

        Map<String, Long> quarantine = new TreeMap<>(outcome.expectedQuarantineCounts());
        assertEquals(expectedByType, quarantine, "expectedQuarantineCounts 必须逐类型等于实际样本分布");
        assertEquals((long) samples.size(), quarantine.values().stream().mapToLong(Long::longValue).sum(),
                "期望隔离数之和必须等于样本条数");

        // 脏样本不进主事件流：主流仍然条条合法，且不含脏样本标记
        assertEquals(400L, result.lineCount(), "脏样本不得占用事件预算，也不得写入制品");
        for (EventLine line : result.lines()) {
            assertNull(contractViolation(line.raw(), line.node()),
                    () -> line.where() + " 主流在 dirtyProfile=light 下仍须 100% 合法：" + line.raw());
            assertFalse(line.raw().contains("ODIRTY") || line.raw().contains("SDIRTY")
                            || line.raw().contains("PDIRTY"),
                    () -> line.where() + " 主流混入了脏样本：" + line.raw());
        }
    }

    @Test
    void everyTypeInTheDirtyVocabularyIsRejectedByTheContractRules() {
        RunResult result = run(RUN_ID, tempDir.resolve("dirty-heavy"), 140, "heavy", 8080L, () -> false);
        EngineOutcome outcome = result.outcome();

        List<DirtySample> samples = outcome.dirtySamples();
        Set<String> seen = new TreeSet<>();
        for (DirtySample sample : samples) {
            seen.add(sample.type());
            assertNotNull(contractViolation(sample.json(), parseOrNull(sample.json())),
                    () -> "脏样本居然通过契约校验：type=" + sample.type() + " json=" + sample.json());
        }
        assertEquals(new TreeSet<>(Arrays.asList(FileModeGenerationEngine.DIRTY_TYPES)), seen,
                "heavy 档位（140 条 → 7 条样本）应覆盖全部脏样本词表");
        assertEquals(140L, result.lineCount(), "脏样本不得进入 EVENT_JSONL 制品");
        assertEquals(140L, outcome.successCount());
    }

    // ------------------------------------------------------------------ 7b. 取消语义

    @Test
    void cancellationReturnsEarlyAndCountsMatchDisk() {
        long eventCount = 400L;
        RunResult result = run(RUN_ID, tempDir.resolve("cancel"), eventCount, GenerationRequest.DIRTY_NONE, 20260505L,
                () -> true);
        EngineOutcome outcome = result.outcome();

        assertTrue(outcome.successCount() < eventCount,
                () -> "取消后仍写满预算：" + outcome.successCount() + "/" + eventCount);
        assertTrue(outcome.successCount() > 0, "取消前应已写入部分事件");
        assertEquals(0L, outcome.failedCount(), "取消不是写入失败，failedCount 必须为 0");
        assertEquals(outcome.successCount(), result.lineCount(), "磁盘行数必须等于 successCount");
        assertEquals(outcome.successCount(), outcome.result().totalEvents());
        assertEquals(outcome.successCount(),
                outcome.eventStats().values().stream().mapToLong(EventTypeStat::count).sum(),
                "eventStats 总数必须等于磁盘行数");
        assertTrue(outcome.notes().stream().anyMatch(note -> note.contains("取消")),
                () -> "取消必须记入运行报告 notes，实际=" + outcome.notes());
    }

    // ------------------------------------------------------------------ 7c. 逐类账本的归属（D10）

    @Test
    void perTypeLedgerIsOwnedByCallerAndMatchesTheStream() {
        long eventCount = 160L;
        GenerationRequest request = new GenerationRequest(RUN_ID, SCENARIO, 20260601L, WINDOW_START, WINDOW_END,
                eventCount, RATE_PER_SECOND, GenerationRequest.DIRTY_NONE);
        JsonlEventSink sink = new JsonlEventSink(RUN_ID, tempDir.resolve("ledger"), ContractFormat.SCHEMA_VERSION,
                MAX_RECORDS_PER_FILE);
        EventStatsRecorder ledger = new EventStatsRecorder();

        EngineOutcome outcome = new FileModeGenerationEngine().run(request, sink, () -> false, ledger);
        sink.closeAndBuildManifest();

        assertFalse(ledger.isEmpty(), "账本必须记下写进规范流的事件");
        assertEquals(outcome.eventStats(), ledger.snapshot(),
                "outcome 的逐类统计必须就是调用方账本的快照（同源 ⇒ 失败路径不必另算一份）");
        assertEquals(sink.eventRecords(), ledger.totalCount(), "账本条数之和必须等于规范流真实条数");
        assertEquals(eventCount, ledger.totalCount(), "正常跑完时账本条数必须等于事件预算");
    }

    // ------------------------------------------------------------------ 运行与校验工具

    private RunResult run(String runId, Path outputRoot, long eventCount, String dirtyProfile, long seed,
                          BooleanSupplier cancelled) {
        return runIn(SCENARIO, runId, outputRoot, eventCount, dirtyProfile, seed, cancelled);
    }

    private RunResult runIn(String scenario, String runId, Path outputRoot, long eventCount, String dirtyProfile,
                            long seed, BooleanSupplier cancelled) {
        GenerationRequest request = new GenerationRequest(runId, scenario, seed, WINDOW_START, WINDOW_END, eventCount,
                RATE_PER_SECOND, dirtyProfile);
        JsonlEventSink sink = new JsonlEventSink(runId, outputRoot, ContractFormat.SCHEMA_VERSION,
                MAX_RECORDS_PER_FILE);
        EngineOutcome outcome = new FileModeGenerationEngine().run(request, sink, cancelled);
        sink.closeAndBuildManifest();

        List<Artifact> artifacts = sink.artifacts();
        List<EventLine> lines = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            if (!Artifact.KIND_EVENT_JSONL.equals(artifact.kind())) {
                continue;
            }
            Path file = Path.of(URI.create(artifact.uri()));
            assertTrue(Files.exists(file), () -> "制品文件不存在：" + file);
            List<String> raw;
            try {
                raw = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("读取制品失败：" + file, e);
            }
            for (int i = 0; i < raw.size(); i++) {
                lines.add(new EventLine(file, i + 1, raw.get(i), parseOrNull(raw.get(i))));
            }
        }
        return new RunResult(outcome, List.copyOf(artifacts), List.copyOf(lines));
    }

    private record RunResult(EngineOutcome outcome, List<Artifact> artifacts, List<EventLine> lines) {

        List<Artifact> jsonlArtifacts() {
            return artifacts.stream().filter(a -> Artifact.KIND_EVENT_JSONL.equals(a.kind())).toList();
        }

        List<Path> files() {
            return jsonlArtifacts().stream().map(a -> Path.of(URI.create(a.uri()))).toList();
        }

        long lineCount() {
            return lines.size();
        }
    }

    private record EventLine(Path file, int lineNumber, String raw, JsonNode node) {

        String where() {
            return file.getFileName() + ":" + lineNumber;
        }
    }

    /**
     * 契约校验：返回 {@code null} 表示该行合法，否则返回第一条违规说明。
     * 覆盖信封字段集、source_system/schema_version 常量、event_type 词表、ISO8601 形态、金额形态、
     * 枚举取值与跨字段一致性（order_created 的 total_amount、stock_changed 的数量非负）。
     */
    private static String contractViolation(String raw, JsonNode event) {
        if (event == null) {
            return "JSON 无法解析（unparseable）：" + raw;
        }
        if (!event.isObject()) {
            return "信封不是 JSON 对象：" + raw;
        }
        Set<String> keys = new LinkedHashSet<>();
        event.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(ENVELOPE_FIELDS)) {
            return "信封字段集必须是 " + ENVELOPE_FIELDS + "，实际=" + keys;
        }
        if (!"mock-mall".equals(text(event, "source_system"))) {
            return "source_system 必须是 mock-mall，实际=" + text(event, "source_system");
        }
        if (!"1.0".equals(text(event, "schema_version"))) {
            return "schema_version 必须是 1.0，实际=" + text(event, "schema_version");
        }
        String type = text(event, "event_type");
        if (type == null || !EventTypes.ALL.contains(type)) {
            return "event_type 必须是契约 12 类之一，实际=" + type;
        }
        for (String field : List.of("event_time", "ingest_time")) {
            String value = text(event, field);
            if (value == null || !ContractFormat.ISO8601_PATTERN.matcher(value).matches()) {
                return field + " 必须匹配 " + ContractFormat.ISO8601_PATTERN.pattern() + "，实际=" + value;
            }
        }
        JsonNode payload = event.get("payload");
        if (payload == null || !payload.isObject() || payload.isEmpty()) {
            return "payload 必须是非空对象，实际=" + payload;
        }

        List<String> amountViolations = new ArrayList<>();
        collectAmountShapeViolations(payload, "payload", amountViolations);
        if (!amountViolations.isEmpty()) {
            return "金额字段形态违规（" + ContractFormat.AMOUNT_PATTERN.pattern() + "）：" + amountViolations;
        }

        String channel = text(payload, "channel");
        if (channel != null && !CHANNELS.contains(channel)) {
            return "channel 必须是 " + CHANNELS + " 之一，实际=" + channel;
        }
        String behaviorType = text(payload, "behavior_type");
        if (behaviorType != null && !BEHAVIOR_TYPES.contains(behaviorType)) {
            return "behavior_type 必须是 " + BEHAVIOR_TYPES + " 之一，实际=" + behaviorType;
        }

        if (EventTypes.STOCK_CHANGED.equals(type)) {
            JsonNode quantity = payload.get("quantity");
            if (quantity == null || !quantity.isNumber()) {
                return "stock_changed.quantity 必须是数字，实际=" + quantity;
            }
            if (quantity.decimalValue().compareTo(BigDecimal.ZERO) < 0) {
                return "库存变动数量不得为负，实际=" + quantity;
            }
        }
        if (EventTypes.ORDER_CREATED.equals(type)) {
            JsonNode items = payload.get("items");
            if (items == null || !items.isArray() || items.isEmpty()) {
                return "order_created.items 必须是非空数组，实际=" + items;
            }
            BigDecimal itemSum = BigDecimal.ZERO;
            for (JsonNode item : items) {
                String amount = text(item, "amount");
                if (amount == null) {
                    return "items[].amount 必填，实际=" + item;
                }
                itemSum = itemSum.add(new BigDecimal(amount));
            }
            String total = text(payload, "total_amount");
            if (total == null) {
                return "order_created.total_amount 必填";
            }
            if (new BigDecimal(total).compareTo(itemSum) != 0) {
                return "total_amount=" + total + " != Σ items.amount=" + itemSum.toPlainString();
            }
        }
        return null;
    }

    private static void collectAmountShapeViolations(JsonNode node, String path, List<String> violations) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (AMOUNT_FIELDS.contains(field.getKey()) && value.isTextual()
                        && !ContractFormat.AMOUNT_PATTERN.matcher(value.asText()).matches()) {
                    violations.add(path + "." + field.getKey() + "=" + value.asText());
                }
                collectAmountShapeViolations(value, path + "." + field.getKey(), violations);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectAmountShapeViolations(node.get(i), path + "[" + i + "]", violations);
            }
        }
    }

    private static Map<String, Integer> typeCounts(RunResult result) {
        Map<String, Integer> counts = new TreeMap<>();
        for (EventLine line : result.lines()) {
            counts.merge(line.node().path("event_type").asText(), 1, Integer::sum);
        }
        return counts;
    }

    private static JsonNode parseOrNull(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(newDigest().digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取制品失败：" + file, e);
        }
    }

    private static String runDigest(RunResult result) {
        MessageDigest digest = newDigest();
        for (Path file : result.files()) {
            digest.update(sha256(file).getBytes(StandardCharsets.US_ASCII));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(JsonlEventSink.CHECKSUM_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(JsonlEventSink.CHECKSUM_ALGORITHM + " 不可用", e);
        }
    }
}
