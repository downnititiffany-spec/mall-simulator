package com.graduation.analytics.mapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * canonical 契约的**运行时真相**，由 {@code contract-specs/schemas/canonical-event.v1.schema.json} 解析而来。
 *
 * <p>本类刻意不复制契约内容：事件类型全集取自 {@code properties.event_type.enum}，契约版本取自
 * {@code properties.schema_version.const}，必填字段与字段类型取自 {@code $defs.<type>.required/.properties}，
 * 金额/时间形状取自 {@code $defs.amount.pattern} 与引用关系。这样「必填真相」只有一个所有者（契约），
 * Java 侧不再维护第二份清单（历史上 {@code EventContract} 只有 12 类事件名与 ODS 路由，没有必填表）。</p>
 */
public final class CanonicalContract {

    /** 契约字段的规范化类型。 */
    public enum Kind {
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN,
        /** {@code $ref: #/$defs/amount}：十进制字符串金额。 */
        AMOUNT,
        /** {@code $ref: #/$defs/iso8601_time}：ISO-8601 带时区。 */
        TIME,
        /** {@code enum} 或 {@code const}（常量按单值枚举处理）。 */
        ENUM,
        /** {@code oneOf: [数组, 字符串]}，逐项映射见 {@link ItemMapSpec}。 */
        ITEMS,
        OBJECT,
        UNKNOWN
    }

    /** 一个契约字段的声明。 */
    public record FieldSpec(String name, Kind kind, List<String> enumValues, boolean required) {
        public FieldSpec {
            enumValues = List.copyOf(enumValues);
        }

        public boolean hasEnumValues() {
            return kind == Kind.ENUM && !enumValues.isEmpty();
        }
    }

    private static final String PLATFORM_INGEST_FIELD = "ingest_time";

    private final String contractVersion;
    private final Set<String> eventTypes;
    private final Map<String, FieldSpec> envelopeFields;
    private final Map<String, Map<String, FieldSpec>> payloadFields;
    private final Map<String, FieldSpec> itemFields;
    private final boolean itemsAllowString;
    private final String amountPattern;
    private final String timePattern;

    CanonicalContract(String contractVersion,
                      Set<String> eventTypes,
                      Map<String, FieldSpec> envelopeFields,
                      Map<String, Map<String, FieldSpec>> payloadFields,
                      Map<String, FieldSpec> itemFields,
                      boolean itemsAllowString,
                      String amountPattern,
                      String timePattern) {
        this.contractVersion = contractVersion;
        this.eventTypes = Collections.unmodifiableSet(new LinkedHashSet<>(eventTypes));
        this.envelopeFields = Collections.unmodifiableMap(new LinkedHashMap<>(envelopeFields));
        Map<String, Map<String, FieldSpec>> payloadCopy = new LinkedHashMap<>();
        payloadFields.forEach((k, v) -> payloadCopy.put(k, Collections.unmodifiableMap(new LinkedHashMap<>(v))));
        this.payloadFields = Collections.unmodifiableMap(payloadCopy);
        this.itemFields = Collections.unmodifiableMap(new LinkedHashMap<>(itemFields));
        this.itemsAllowString = itemsAllowString;
        this.amountPattern = amountPattern;
        this.timePattern = timePattern;
    }

    public String contractVersion() {
        return contractVersion;
    }

    public Set<String> eventTypes() {
        return eventTypes;
    }

    public boolean isKnownEventType(String eventType) {
        return eventType != null && eventTypes.contains(eventType);
    }

    public Map<String, FieldSpec> envelopeFields() {
        return envelopeFields;
    }

    public FieldSpec envelopeField(String name) {
        return envelopeFields.get(name);
    }

    /** 契约必填的信封字段（含 ingest_time；调用方按平台生成边界自行排除）。 */
    public List<String> requiredEnvelopeFields() {
        List<String> required = new ArrayList<>();
        envelopeFields.forEach((name, spec) -> {
            if (spec.required()) {
                required.add(name);
            }
        });
        return required;
    }

    /** 由平台 ingestion 层生成、Mapper 不得写入的信封字段。 */
    public String platformIngestField() {
        return PLATFORM_INGEST_FIELD;
    }

    public Map<String, FieldSpec> payloadFields(String eventType) {
        return payloadFields.getOrDefault(eventType, Map.of());
    }

    public FieldSpec payloadField(String eventType, String name) {
        return payloadFields(eventType).get(name);
    }

    /** 该字段名是否出现在任意事件类型的 payload 里（v1 扁平映射与 enumSemantics 键校验用）。 */
    public boolean isKnownPayloadFieldName(String name) {
        if (name == null) {
            return false;
        }
        for (Map<String, FieldSpec> fields : payloadFields.values()) {
            if (fields.containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    /** 含该字段名的事件类型（按契约声明顺序）。 */
    public List<String> eventTypesWithPayloadField(String name) {
        List<String> types = new ArrayList<>();
        for (String type : eventTypes) {
            if (payloadFields(type).containsKey(name)) {
                types.add(type);
            }
        }
        return types;
    }

    /** 契约必填的载荷字段（按契约声明顺序）。 */
    public List<String> requiredPayloadFields(String eventType) {
        List<String> required = new ArrayList<>();
        payloadFields(eventType).forEach((name, spec) -> {
            if (spec.required()) {
                required.add(name);
            }
        });
        return required;
    }

    public Map<String, FieldSpec> itemFields() {
        return itemFields;
    }

    public boolean itemsAllowString() {
        return itemsAllowString;
    }

    public String amountPattern() {
        return amountPattern;
    }

    public String timePattern() {
        return timePattern;
    }
}
