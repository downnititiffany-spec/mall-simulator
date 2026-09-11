package com.graduation.generator.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生成器 ↔ 契约对账（L0，负向+正向）：把生成器侧的 12 类载荷、信封字段、枚举与格式化正则
 * 逐项钉到中立契约文件 {@code contract-specs/schemas/canonical-event.v1.schema.json} 上。
 *
 * <p>为什么必须有：生成器**不能**共享分析平台的 Java 实体（V2.1 §3.4-3），也就没有编译器帮它发现契约漂移。
 * 真实数据里已经出现过 {@code order_created.items} 写成字符串、{@code behavior.channel="web"}、
 * {@code stock_changed.change_type="restock"} 这类"平台收得下但按契约属脏"的行——本测试让生成器不再制造它们。</p>
 *
 * <p>为什么用文本对账而不是引入 JSON-Schema 校验器：本机 Maven 处于离线模式，且契约尚未冻结（B-06）；
 * 用常量集比较能给出同样强度的"改了契约就会红"的证据，且不引入新依赖。</p>
 */
class GeneratorContractParityTest {

    private static final Path CONTRACT = repoRoot()
            .resolve("contract-specs/schemas/canonical-event.v1.schema.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode contract() {
        try {
            return MAPPER.readTree(Files.readString(CONTRACT, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("契约文件读取失败：" + CONTRACT, e);
        }
    }

    @Test
    @DisplayName("信封字段名与顺序与契约一致（8 个必填字段）")
    void envelopeFieldsMatchContract() {
        JsonNode schema = contract();
        Set<String> contractFields = new TreeSet<>();
        schema.get("properties").fieldNames().forEachRemaining(contractFields::add);
        Set<String> contractRequired = new TreeSet<>();
        schema.get("required").forEach(node -> contractRequired.add(node.asText()));

        CanonicalEvent sample = sampleEvent(EventTypes.BEHAVIOR);
        ObjectNode json = MAPPER.valueToTree(sample);
        Set<String> produced = new TreeSet<>();
        json.fieldNames().forEachRemaining(produced::add);

        assertThat(contractFields).as("信封字段集必须与契约完全一致").isEqualTo(produced);
        assertThat(contractRequired).as("契约 required 必须覆盖生成器产出的每个字段").isEqualTo(produced);
        assertThat(json.get("payload").isObject()).as("payload 必须是对象而不是字符串（真实数据曾写成字符串）").isTrue();
    }

    @Test
    @DisplayName("事件类型常量集与契约 enum 完全一致（12 类，含顺序）")
    void eventTypeEnumMatchesContract() {
        List<String> contractEnum = new ArrayList<>();
        contract().get("properties").get("event_type").get("enum").forEach(node -> contractEnum.add(node.asText()));
        assertThat(EventTypes.ALL).as("事件类型集必须与契约 enum 一致").containsExactlyElementsOf(contractEnum);
        assertThat(EventTypes.ALL).hasSize(12);
    }

    @Test
    @DisplayName("schema_version / source_system / 金额与时间正则在生成器侧取同一值")
    void constantsAndPatternsMatchContract() {
        JsonNode schema = contract();
        assertThat(ContractFormat.SCHEMA_VERSION)
                .isEqualTo(schema.get("properties").get("schema_version").get("const").asText());
        assertThat(ContractFormat.SOURCE_SYSTEM)
                .isEqualTo(schema.get("properties").get("source_system").get("const").asText());
        assertThat(ContractFormat.AMOUNT_PATTERN.pattern())
                .isEqualTo(schema.get("$defs").get("amount").get("pattern").asText());
        assertThat(ContractFormat.ISO8601_PATTERN.pattern())
                .isEqualTo(schema.get("$defs").get("iso8601_time").get("pattern").asText());
    }

    @Test
    @DisplayName("12 类载荷的键集覆盖契约 required 且不超出契约 properties")
    void everyEventTypePayloadMatchesContract() {
        JsonNode defs = contract().get("$defs");
        List<String> problems = new ArrayList<>();

        samples().forEach((eventType, payload) -> {
            JsonNode def = defs.get(eventType);
            if (def == null || !def.isObject()) {
                problems.add(eventType + " 在契约 $defs 中不存在对应载荷定义");
                return;
            }
            Set<String> allowed = new TreeSet<>();
            def.get("properties").fieldNames().forEachRemaining(allowed::add);
            Set<String> required = new TreeSet<>();
            def.get("required").forEach(node -> required.add(node.asText()));
            Set<String> produced = new TreeSet<>(payload.keySet());

            for (String field : required) {
                if (!produced.contains(field)) {
                    problems.add("%s 缺少契约必填字段 %s（生成器会产出被隔离的行）".formatted(eventType, field));
                }
            }
            for (String field : produced) {
                if (!allowed.contains(field)) {
                    problems.add("%s 产出了契约未定义的字段 %s（additionalProperties 未冻结前不得新增）"
                            .formatted(eventType, field));
                }
            }
        });

        assertThat(problems).as("生成器载荷必须与契约 $defs 对齐").isEmpty();
        assertThat(samples().keySet()).as("12 类事件都必须有载荷样例").hasSize(12);
    }

    @Test
    @DisplayName("枚举型字段取值落在契约 enum 内（channel/behavior_type/change_type/age_group 等）")
    void enumValuesStayWithinContract() {
        JsonNode defs = contract().get("$defs");
        List<String> problems = new ArrayList<>();
        samples().forEach((eventType, payload) -> checkEnums(defs.get(eventType), payload, eventType, problems));
        assertThat(problems).as("枚举取值必须落在契约 enum 内（真实数据曾用 web/restock/purchase 越界）").isEmpty();
    }

    @Test
    @DisplayName("生成器侧显式拒绝越界与畸形容器（不允许静默产出脏行）")
    void generatorRejectsMalformedInputLocally() {
        assertThatThrownBy(() -> CanonicalPayloads.behavior("u1", "p1", "s1", "purchase", "app"))
                .as("behavior_type 越界必须在生成器侧就抛")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalPayloads.behavior("u1", "p1", "s1", "view", "web"))
                .as("channel 越界必须在生成器侧就抛")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalPayloads.userRegistered("u1", "45-54", "tier1", "gold",
                "2026-09-01T09:00:00+08:00"))
                .as("age_group 越界（真实夹具出现过 45-54）必须抛")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalPayloads.stockChanged("p1", "restock", 5, "100.00"))
                .as("change_type 越界（真实夹具出现过 restock）必须抛")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContractFormat.requireAmount("12.345"))
                .as("金额超过 2 位小数必须抛")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContractFormat.requireTime("2026-09-01T10:00:00"))
                .as("时间不带时区必须抛")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtifactManifest("r1", "file:///x", "abc", 1, 1,
                "2026-09-01T10:00:00+08:00", "2026-09-01T10:00:01+08:00", "1.0", false))
                .as("synthetic=false 的清单必须被拒绝")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("枚举常量集与契约 enum 完全一致（7 组，含取值顺序）")
    void enumSetsMatchContract() {
        JsonNode defs = contract().get("$defs");
        assertThat(ContractEnums.AGE_GROUP)
                .containsExactlyElementsOf(enumOf(defs, "user_registered", "age_group"));
        assertThat(ContractEnums.CITY_LEVEL)
                .containsExactlyElementsOf(enumOf(defs, "user_registered", "city_level"));
        assertThat(ContractEnums.MEMBER_LEVEL)
                .containsExactlyElementsOf(enumOf(defs, "user_registered", "member_level"));
        assertThat(ContractEnums.PRODUCT_STATUS)
                .containsExactlyElementsOf(enumOf(defs, "product_created", "status"));
        assertThat(ContractEnums.BEHAVIOR_TYPE)
                .containsExactlyElementsOf(enumOf(defs, "behavior", "behavior_type"));
        assertThat(ContractEnums.CHANNEL)
                .containsExactlyElementsOf(enumOf(defs, "behavior", "channel"));
        assertThat(ContractEnums.STOCK_CHANGE_TYPE)
                .containsExactlyElementsOf(enumOf(defs, "stock_changed", "change_type"));
    }

    // ---------- 样例载荷 ----------

    private static List<String> enumOf(JsonNode defs, String defName, String field) {
        List<String> values = new ArrayList<>();
        defs.get(defName).get("properties").get(field).get("enum").forEach(node -> values.add(node.asText()));
        return values;
    }

    private static void checkEnums(JsonNode def, Map<String, Object> payload, String eventType, List<String> problems) {
        if (def == null || !def.isObject()) {
            return;
        }
        JsonNode properties = def.get("properties");
        payload.forEach((field, value) -> {
            JsonNode property = properties.get(field);
            if (property == null) {
                return;
            }
            if (property.has("enum")) {
                Set<String> allowed = new TreeSet<>();
                property.get("enum").forEach(node -> allowed.add(node.asText()));
                if (!allowed.contains(String.valueOf(value))) {
                    problems.add("%s.%s=%s 不在契约 enum %s 内".formatted(eventType, field, value, allowed));
                }
            }
            if (property.has("const") && !property.get("const").asText().equals(String.valueOf(value))) {
                problems.add("%s.%s=%s 与契约 const %s 不一致"
                        .formatted(eventType, field, value, property.get("const").asText()));
            }
            if (property.has("$ref") && property.get("$ref").asText().endsWith("/amount")) {
                if (!ContractFormat.AMOUNT_PATTERN.matcher(String.valueOf(value)).matches()) {
                    problems.add("%s.%s=%s 不是契约金额字符串".formatted(eventType, field, value));
                }
            }
        });
    }

    /** 12 类事件的样例载荷（与 {@link CanonicalPayloads} 的工厂方法一一对应） */
    private static Map<String, Map<String, Object>> samples() {
        Map<String, Map<String, Object>> samples = new LinkedHashMap<>();
        samples.put(EventTypes.USER_REGISTERED, CanonicalPayloads.userRegistered(
                "1", "25-34", "tier1", "gold", "2026-09-01T09:00:00+08:00"));
        samples.put(EventTypes.PRODUCT_CREATED, CanonicalPayloads.productCreated(
                "2", "机械键盘", "3", "4", "399.00", "210.00", "on_sale"));
        samples.put(EventTypes.PRODUCT_UPDATED, CanonicalPayloads.productUpdated(
                "2", "机械键盘", "3", "4", "379.00", "210.00", "on_sale"));
        samples.put(EventTypes.BEHAVIOR, CanonicalPayloads.behavior(
                "1", "2", "sess-1", "view", "app"));
        samples.put(EventTypes.ORDER_CREATED, CanonicalPayloads.orderCreated(
                "1001", "1",
                List.of(new CanonicalPayloads.OrderItem("2", 2, "399.00", "0.00", "798.00")),
                "798.00", "2026-09-01T10:00:00+08:00"));
        samples.put(EventTypes.ORDER_PAID, CanonicalPayloads.orderPaid(
                "1001", "1", "pay-1", "798.00", "2026-09-01T10:05:00+08:00"));
        samples.put(EventTypes.ORDER_CANCELLED, CanonicalPayloads.orderCancelled(
                "1001", "1", "用户取消", "2026-09-01T10:10:00+08:00"));
        samples.put(EventTypes.REFUND_CREATED, CanonicalPayloads.refundCreated(
                "r-1", "1001", "1", "200.00", "七天无理由", "2026-09-02T10:00:00+08:00"));
        samples.put(EventTypes.REFUND_COMPLETED, CanonicalPayloads.refundCompleted(
                "r-1", "1001", "1", "200.00", "2026-09-03T10:00:00+08:00"));
        samples.put(EventTypes.STOCK_RESERVED, CanonicalPayloads.stockReserved(
                "2", "1001", 2, "2.00", "98.00"));
        samples.put(EventTypes.STOCK_RELEASED, CanonicalPayloads.stockReleased(
                "2", "1001", 2, "0.00", "100.00"));
        samples.put(EventTypes.STOCK_CHANGED, CanonicalPayloads.stockChanged(
                "2", "inbound", 50, "150.00"));
        return samples;
    }

    private static CanonicalEvent sampleEvent(String eventType) {
        return CanonicalEventFactory.of(eventType,
                samples().get(eventType),
                "2026-09-01T10:00:00+08:00",
                Instant.parse("2026-09-01T02:00:05Z"));
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("contract-specs/schemas"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("未能从 " + dir + " 向上找到 contract-specs/schemas");
    }
}
