package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code ingestion-manifest.v1} 的**极小 JSON Schema 关键字子集实现**（P1-05 / D-037 裁决 6）。
 *
 * <p>从 {@code IngestionManifestSourceSchemaTest} 里抽出来（V25-T02）：契约测试与运行时巡检
 * 必须用**同一套**校验实现，否则"契约通过"与"巡检通过"是两句无法互相印证的话。</p>
 *
 * <p><b>为什么不引 JSON Schema 校验器</b>：离线构建无法新增依赖，且仓内已有同一先例
 * （{@code CanonicalEventSchemaParityTest} 的说明："只做结构对账：不引入 JSON Schema 校验器依赖"）。
 * 本类因此自己实现该 schema 用到的关键字子集（{@code type}/{@code required}/{@code maxLength}/
 * {@code minLength}/{@code minimum}/{@code const}）。**明确不在覆盖范围内**的既有关键字：
 * {@code pattern}（如 {@code batchNo}）、{@code items}/{@code $defs} 的递归下沉、{@code format}。
 * <b>取证边界</b>：真机端到端落盘的四字段取值由 E3（真实例 + 真 MySQL 副本库）取证，两者不可互相替代。</p>
 */
final class IngestionManifestSchemaSubset {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 契约权威：{@code contract-specs/schemas/ingestion-manifest.v1.schema.json}（目录级 1.2.0） */
    static final String SCHEMA_RELATIVE = "contract-specs/schemas/ingestion-manifest.v1.schema.json";

    /** P1-05 新增的四个可选键（D-037 裁决 6） */
    static final List<String> NEW_SOURCE_KEYS =
            List.of("sourceCode", "sourceId", "profileVersion", "mappingVersion");

    private IngestionManifestSchemaSubset() {
    }

    static JsonNode schema() throws IOException {
        return MAPPER.readTree(java.nio.file.Files.readString(
                com.graduation.analytics.testsupport.RepoRoot.path(SCHEMA_RELATIVE), StandardCharsets.UTF_8));
    }

    /** 现行 schema 的深拷贝，去掉 P1-05 新增的四个属性（其余关键字与取值逐字不动）。 */
    static JsonNode withoutNewSourceProperties(JsonNode schema) {
        ObjectNode copy = schema.deepCopy();
        ObjectNode props = (ObjectNode) copy.path("properties");
        NEW_SOURCE_KEYS.forEach(props::remove);
        return copy;
    }

    /** 返回违规说明列表（空 = 通过）。只实现本 schema 用到的关键字，范围写在类注释里。 */
    static List<String> validate(JsonNode node, JsonNode schema) {
        List<String> out = new ArrayList<>();
        for (String key : fieldNames(schema.path("required"))) {
            if (!node.has(key)) {
                out.add("缺少 required 键: " + key);
            }
        }
        JsonNode props = schema.path("properties");
        node.fieldNames().forEachRemaining(name -> {
            JsonNode rule = props.path(name);
            if (rule.isMissingNode()) {
                return;   // additionalProperties: true
            }
            JsonNode value = node.get(name);
            List<String> types = typeOf(rule);
            if (!types.isEmpty() && !matchesAnyType(value, types)) {
                out.add(name + " 类型不符：期望 " + types + "，实际 " + value.getNodeType());
            }
            if (value.isTextual()) {
                int max = rule.path("maxLength").asInt(-1);
                if (max > 0 && value.asText().length() > max) {
                    out.add(name + " 超长：" + value.asText().length() + " > " + max);
                }
                int min = rule.path("minLength").asInt(-1);
                if (min > 0 && value.asText().length() < min) {
                    out.add(name + " 过短：" + value.asText().length() + " < " + min);
                }
            }
            if (value.isNumber()) {
                if (rule.has("minimum") && value.asLong() < rule.path("minimum").asLong()) {
                    out.add(name + " 小于 minimum：" + value.asLong());
                }
            }
            if (rule.has("const") && !rule.path("const").asText().equals(value.asText())) {
                out.add(name + " 不等于 const " + rule.path("const").asText());
            }
        });
        return out;
    }

    static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        if (node.isArray()) {
            node.forEach(element -> names.add(element.asText()));
            return names;
        }
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static List<String> typeOf(JsonNode rule) {
        JsonNode type = rule.path("type");
        if (type.isArray()) {
            List<String> out = new ArrayList<>();
            type.forEach(t -> out.add(t.asText()));
            return out;
        }
        return type.isMissingNode() ? List.of() : List.of(type.asText());
    }

    private static boolean matchesAnyType(JsonNode value, List<String> types) {
        for (String type : types) {
            switch (type) {
                case "string" -> {
                    if (value.isTextual()) {
                        return true;
                    }
                }
                case "integer" -> {
                    if (value.isIntegralNumber()) {
                        return true;
                    }
                }
                case "number" -> {
                    if (value.isNumber()) {
                        return true;
                    }
                }
                case "null" -> {
                    if (value.isNull()) {
                        return true;
                    }
                }
                case "object" -> {
                    if (value.isObject()) {
                        return true;
                    }
                }
                case "array" -> {
                    if (value.isArray()) {
                        return true;
                    }
                }
                case "boolean" -> {
                    if (value.isBoolean()) {
                        return true;
                    }
                }
                default -> throw new IllegalStateException("本极小子集未实现类型: " + type);
            }
        }
        return false;
    }
}
