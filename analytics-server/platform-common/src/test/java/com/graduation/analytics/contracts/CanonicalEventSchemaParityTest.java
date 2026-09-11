package com.graduation.analytics.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-5 契约防漂移：中立目录 {@code contract-specs/schemas/canonical-event.v1.schema.json}
 * 必须与本模块的 Java 契约常量/信封定义逐项一致。
 *
 * <p>目的：机器可读契约与运行时实现不能各自漂移——任何一侧改字段、改枚举、改版本号、
 * 改金额正则，本测试立即失败。语义权威仍是 {@code docs/contracts/event-contract.md}
 * （V2.1 §2.1 权威顺序：冻结契约 &gt; 指导书 &gt; 看板）。
 *
 * <p>本测试只做"结构对账"：不引入 JSON Schema 校验器依赖（离线构建无法新增依赖），
 * 逐行数据校验由采集链路的既有校验与生成器文件模式（M1-4）各自的实现承担。
 */
class CanonicalEventSchemaParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENVELOPE_SCHEMA = "canonical-event.v1.schema.json";

    @Test
    @DisplayName("信封字段集：properties 与 required 都与 EventEnvelope 的 8 个 JSON 字段一致")
    void envelopeFieldsMatchRecordComponents() throws IOException {
        JsonNode root = schema(ENVELOPE_SCHEMA);
        Set<String> schemaProps = fieldNames(root.path("properties"));
        Set<String> declaredRequired = arrayToSet(root.path("required"));
        Set<String> envelopeFields = envelopeJsonPropertyNames();

        assertEquals(envelopeFields, schemaProps,
                "信封属性集与 EventEnvelope 不一致：" + diff(envelopeFields, schemaProps));
        assertEquals(envelopeFields, declaredRequired,
                "required 必须覆盖全部 8 个信封字段（事件契约 §1）：" + diff(envelopeFields, declaredRequired));
    }

    @Test
    @DisplayName("event_type 枚举 = EventContract 的 12 类事件")
    void eventTypeEnumMatchesContract() throws IOException {
        JsonNode root = schema(ENVELOPE_SCHEMA);
        Set<String> schemaEnum = arrayToSet(root.path("properties").path("event_type").path("enum"));
        assertEquals(new TreeSet<>(EventContract.EVENT_TYPES), new TreeSet<>(schemaEnum),
                "event_type 枚举与 EventContract.EVENT_TYPES 不一致");
    }

    @Test
    @DisplayName("schema_version / source_system 常量与 EventContract 一致")
    void versionAndSourceConstMatchContract() throws IOException {
        JsonNode root = schema(ENVELOPE_SCHEMA);
        assertEquals(EventContract.SCHEMA_VERSION,
                root.path("properties").path("schema_version").path("const").asText(),
                "schema_version 必须锁定为 EventContract.SCHEMA_VERSION");
        assertEquals(EventContract.SOURCE_SYSTEM,
                root.path("properties").path("source_system").path("const").asText(),
                "source_system 必须锁定为 EventContract.SOURCE_SYSTEM");
    }

    @Test
    @DisplayName("12 类事件在 $defs 中都有 payload 定义且被 event_type 路由引用")
    void everyEventTypeHasRoutedPayloadDefinition() throws IOException {
        JsonNode root = schema(ENVELOPE_SCHEMA);
        Set<String> defs = fieldNames(root.path("$defs"));
        Set<String> missingDefs = new TreeSet<>(EventContract.EVENT_TYPES);
        missingDefs.removeAll(defs);
        assertTrue(missingDefs.isEmpty(), "$defs 缺少事件类型定义: " + missingDefs);

        Set<String> routed = new TreeSet<>();
        Set<String> brokenRefs = new TreeSet<>();
        for (JsonNode branch : root.path("allOf")) {
            String type = branch.path("if").path("properties").path("event_type").path("const").asText(null);
            String ref = branch.path("then").path("properties").path("payload").path("$ref").asText(null);
            if (type == null || ref == null) {
                continue;
            }
            routed.add(type);
            if (!defs.contains(ref.substring(ref.lastIndexOf('/') + 1))) {
                brokenRefs.add(type + " -> " + ref);
            }
        }
        assertEquals(new TreeSet<>(EventContract.EVENT_TYPES), routed,
                "allOf 中的 event_type→payload 路由未覆盖全部 12 类事件");
        assertTrue(brokenRefs.isEmpty(), "payload $ref 指向不存在的 $defs 定义: " + brokenRefs);
    }

    @Test
    @DisplayName("金额正则：schema 中声明且等于 EventContract.AMOUNT_PATTERN")
    void amountPatternMatchesContract() throws IOException {
        JsonNode root = schema(ENVELOPE_SCHEMA);
        List<String> patterns = new ArrayList<>();
        collectTextValues(root, "pattern", patterns);
        assertFalse(patterns.isEmpty(), "schema 未声明任何金额正则（事件契约 §4）");
        assertTrue(patterns.contains(EventContract.AMOUNT_PATTERN),
                "schema 未使用 EventContract.AMOUNT_PATTERN（" + EventContract.AMOUNT_PATTERN + "），实际: " + patterns);
    }

    // ---------- helpers ----------

    /** 信封字段的 JSON 名：显式 {@code @JsonProperty} 优先，缺省时用 record 组件名（Jackson 隐式命名）。 */
    private static Set<String> envelopeJsonPropertyNames() {
        Set<String> names = new LinkedHashSet<>();
        for (RecordComponent component : EventEnvelope.class.getRecordComponents()) {
            JsonProperty annotation = component.getAccessor().getAnnotation(JsonProperty.class);
            names.add(annotation == null ? component.getName() : annotation.value());
        }
        return names;
    }

    private static JsonNode schema(String fileName) throws IOException {
        Path path = repoRoot().resolve("contract-specs").resolve("schemas").resolve(fileName);
        assertTrue(Files.isRegularFile(path), "契约文件不存在: " + path);
        return MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** 从模块目录向上找含 contract-specs/schemas 的仓库根，避免依赖 surefire 工作目录。 */
    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("contract-specs").resolve("schemas"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("未找到 contract-specs/schemas（从 " + dir + " 向上查找）");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> arrayToSet(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private static void collectTextValues(JsonNode node, String fieldName, List<String> sink) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getKey().equals(fieldName) && entry.getValue().isTextual()) {
                    sink.add(entry.getValue().asText());
                }
                collectTextValues(entry.getValue(), fieldName, sink);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectTextValues(child, fieldName, sink));
        }
    }

    private static String diff(Set<String> expected, Set<String> actual) {
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        return "缺少=" + missing + " 多出=" + extra;
    }
}
