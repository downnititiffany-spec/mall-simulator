package com.graduation.analytics.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.graduation.analytics.mapping.CanonicalContract.FieldSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 canonical 契约 JSON 解析 {@link CanonicalContract}（只读，不落库、不缓存到磁盘）。
 *
 * <p>解析失败一律抛 {@link IllegalStateException}：契约读不出来属于「基线坏了」，不应降级成默认值继续跑。</p>
 */
public final class CanonicalContractLoader {

    private static final String AMOUNT_REF_SUFFIX = "/$defs/amount";
    private static final String TIME_REF_SUFFIX = "/$defs/iso8601_time";

    private CanonicalContractLoader() {
    }

    public static CanonicalContract load(Path schemaFile) {
        try {
            return load(MappingJson.mapper().readTree(Files.readString(schemaFile, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取 canonical 契约失败: " + schemaFile, e);
        }
    }

    public static CanonicalContract load(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            throw new IllegalStateException("canonical 契约不是 JSON 对象");
        }
        JsonNode properties = schema.path("properties");
        String contractVersion = textOrNull(properties.path("schema_version").path("const"));
        if (contractVersion == null) {
            throw new IllegalStateException("canonical 契约缺少 properties.schema_version.const，无法确定契约版本");
        }
        Set<String> eventTypes = new LinkedHashSet<>();
        for (JsonNode type : properties.path("event_type").path("enum")) {
            if (type.isTextual()) {
                eventTypes.add(type.asText());
            }
        }
        if (eventTypes.isEmpty()) {
            throw new IllegalStateException("canonical 契约缺少 properties.event_type.enum，无法确定事件类型全集");
        }

        JsonNode defs = schema.path("$defs");
        Map<String, FieldSpec> envelopeFields = fieldSpecs(properties, requiredNames(properties));
        Map<String, Map<String, FieldSpec>> payloadFields = new LinkedHashMap<>();
        Map<String, FieldSpec> itemFields = Map.of();
        boolean itemsAllowString = false;

        for (String eventType : eventTypes) {
            JsonNode def = defs.path(eventType);
            if (!def.isObject()) {
                throw new IllegalStateException("canonical 契约缺少 $defs." + eventType);
            }
            Map<String, FieldSpec> fields = fieldSpecs(def.path("properties"), requiredNames(def));
            payloadFields.put(eventType, fields);

            JsonNode itemsProperty = def.path("properties").path("items");
            if (itemsProperty.has("oneOf")) {
                for (JsonNode branch : itemsProperty.path("oneOf")) {
                    String branchType = textOrNull(branch.path("type"));
                    if ("array".equals(branchType)) {
                        JsonNode itemSchema = branch.path("items");
                        itemFields = fieldSpecs(itemSchema.path("properties"), requiredNames(itemSchema));
                    } else if ("string".equals(branchType)) {
                        itemsAllowString = true;
                    }
                }
            }
        }

        return new CanonicalContract(
                contractVersion,
                eventTypes,
                envelopeFields,
                payloadFields,
                itemFields,
                itemsAllowString,
                textOrNull(defs.path("amount").path("pattern")),
                textOrNull(defs.path("iso8601_time").path("pattern")));
    }

    private static Map<String, FieldSpec> fieldSpecs(JsonNode properties, List<String> required) {
        Map<String, FieldSpec> specs = new LinkedHashMap<>();
        properties.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            specs.put(name, new FieldSpec(name, kindOf(entry.getValue()), enumValuesOf(entry.getValue()),
                    required.contains(name)));
        });
        return specs;
    }

    private static CanonicalContract.Kind kindOf(JsonNode node) {
        String ref = textOrNull(node.path("$ref"));
        if (ref != null) {
            if (ref.endsWith(AMOUNT_REF_SUFFIX)) {
                return CanonicalContract.Kind.AMOUNT;
            }
            if (ref.endsWith(TIME_REF_SUFFIX)) {
                return CanonicalContract.Kind.TIME;
            }
            return CanonicalContract.Kind.UNKNOWN;
        }
        if (node.has("enum") || node.has("const")) {
            return CanonicalContract.Kind.ENUM;
        }
        if (node.has("oneOf")) {
            return CanonicalContract.Kind.ITEMS;
        }
        String type = textOrNull(node.path("type"));
        if (type == null) {
            return CanonicalContract.Kind.UNKNOWN;
        }
        return switch (type) {
            case "string" -> CanonicalContract.Kind.STRING;
            case "integer" -> CanonicalContract.Kind.INTEGER;
            case "number" -> CanonicalContract.Kind.NUMBER;
            case "boolean" -> CanonicalContract.Kind.BOOLEAN;
            case "object" -> CanonicalContract.Kind.OBJECT;
            case "array" -> CanonicalContract.Kind.ITEMS;
            default -> CanonicalContract.Kind.UNKNOWN;
        };
    }

    private static List<String> enumValuesOf(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : node.path("enum")) {
            if (value.isTextual()) {
                values.add(value.asText());
            }
        }
        String constant = textOrNull(node.path("const"));
        if (values.isEmpty() && constant != null) {
            values.add(constant);
        }
        return values;
    }

    private static List<String> requiredNames(JsonNode node) {
        List<String> required = new ArrayList<>();
        for (JsonNode name : node.path("required")) {
            if (name.isTextual()) {
                required.add(name.asText());
            }
        }
        return required;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
