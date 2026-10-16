package com.graduation.analytics.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 不可变的源画像（Source Profile）内存模型（Deliverable 1）。
 *
 * <p>包含 SHA-256 内容 hash（对画像文件字节计算）和版本标识（Rule-14）。</p>
 * <p>Rule-2：同组两个来源写同一目标时画像无效（PROFILE_INVALID）——在 validate() 中检查。</p>
 * <p>Rule-3：@keep 撞 canonical 字段或其他扩展则拒绝，在 validate() 中检查。</p>
 */
public final class SourceProfile {

    /** 声明 FEN 单位时使用 */
    public static final String AMOUNT_UNIT_FEN = "FEN";
    /** 声明 YUAN 单位时使用 */
    public static final String AMOUNT_UNIT_YUAN = "YUAN";

    /** Ruling R-2A-2: 信封字段全集（权威单一来源） */
    public static final Set<String> ENVELOPE_FIELDS = Set.of(
            "event_id", "event_type", "event_time", "ingest_time", "source_system",
            "schema_version", "trace_id", "payload"
    );

    /** 标准业务 payload 字段全集 (event-contract.md) */
    public static final Set<String> STANDARD_PAYLOAD_FIELDS = Set.of(
            "user_id", "product_id", "session_id", "behavior_type", "channel",
            "order_id", "items", "total_amount", "amount", "status", "created_at",
            "paid_at", "cancelled_at", "payment_id", "refund_id", "completed_at",
            "category_id", "brand_id", "price", "cost", "product_name",
            "age_group", "city_level", "member_level", "register_time",
            "quantity", "reserved_qty", "available_qty", "change_type",
            "unit_price", "discount", "reason"
    );

    // 规范事件字段（Rule-3：@keep 不能撞这些信封或标准 payload 名称）
    private static final Set<String> CANONICAL_FIELDS;
    static {
        Set<String> all = new HashSet<>(ENVELOPE_FIELDS);
        all.addAll(STANDARD_PAYLOAD_FIELDS);
        CANONICAL_FIELDS = Collections.unmodifiableSet(all);
    }

    /**
     * 判断字段名是否为信封字段（Ruling R-2A-2）。
     */
    public static boolean isEnvelopeField(String fieldName) {
        return fieldName != null && ENVELOPE_FIELDS.contains(fieldName);
    }

    /**
     * 判断字段名是否为 payload 业务字段（除信封字段外的所有规范目标均为 payload 字段，Ruling R-2A-2）。
     */
    public static boolean isPayloadField(String fieldName) {
        return !isEnvelopeField(fieldName);
    }

    public static Set<String> envelopeFields() {
        return ENVELOPE_FIELDS;
    }

    public static Set<String> standardPayloadFields() {
        return STANDARD_PAYLOAD_FIELDS;
    }

    public static Set<String> canonicalFields() {
        return CANONICAL_FIELDS;
    }

    private final String profileVersion;
    private final String sourceCode;
    private final String canonicalSchemaVersion;
    private final String contentHash;
    private final byte[] rawBytes;

    // eventTypeMapping: sourceEventType -> canonicalEventType (null = unknown, quarantine)
    private final Map<String, String> eventTypeMapping;

    // fieldMapping per eventType group: sourcePath -> targetPath or "@keep"
    // Key: canonical event type; Value: map of sourcePath -> targetPath
    // For mock-mall style (single flat fieldMapping without per-event grouping),
    // we store under key "_global_"
    private final Map<String, Map<String, String>> fieldMappingByGroup;

    // enumSemantics: fieldName -> (sourceValue -> canonicalValue or null)
    // null canonicalValue = ENUM_UNRESOLVED (observed but undecided)
    private final Map<String, Map<String, String>> enumSemantics;

    // timePolicy
    private final String timePolicyField;
    private final List<String> timePolicyFormats;
    private final String timePolicyTimezone;

    // amountPolicy per field: fieldPath -> unit (FEN or YUAN)
    private final Map<String, String> amountUnitByField;

    // keep fields (from @keep values in fieldMapping)
    private final Set<String> keepFields;

    // sensitive fields (Rule-10: fields to desensitize in dry-run preview)
    private final Set<String> sensitiveFields;

    // Validation errors collected during construction (Rule-2, Rule-3)
    private final List<MapperViolation> validationErrors;

    // quarantine policy
    private final String unknownEventTypePolicy; // QUARANTINE or KEEP_IN_PAYLOAD
    private final String unknownFieldPolicy;     // KEEP_IN_PAYLOAD or DROP

    private SourceProfile(Builder b) {
        this.profileVersion = b.profileVersion;
        this.sourceCode = b.sourceCode;
        this.canonicalSchemaVersion = b.canonicalSchemaVersion;
        this.contentHash = b.contentHash;
        this.rawBytes = b.rawBytes;
        this.eventTypeMapping = Collections.unmodifiableMap(b.eventTypeMapping);
        this.fieldMappingByGroup = Collections.unmodifiableMap(b.fieldMappingByGroup);
        this.enumSemantics = Collections.unmodifiableMap(b.enumSemantics);
        this.timePolicyField = b.timePolicyField;
        this.timePolicyFormats = Collections.unmodifiableList(b.timePolicyFormats);
        this.timePolicyTimezone = b.timePolicyTimezone != null ? b.timePolicyTimezone : "Asia/Shanghai";
        this.amountUnitByField = Collections.unmodifiableMap(b.amountUnitByField);
        this.keepFields = Collections.unmodifiableSet(b.keepFields);
        this.sensitiveFields = Collections.unmodifiableSet(b.sensitiveFields);
        this.validationErrors = Collections.unmodifiableList(b.validationErrors);
        this.unknownEventTypePolicy = b.unknownEventTypePolicy;
        this.unknownFieldPolicy = b.unknownFieldPolicy;
    }

    // ---- Accessors ----

    public String profileVersion() { return profileVersion; }
    public String sourceCode() { return sourceCode; }
    public String canonicalSchemaVersion() { return canonicalSchemaVersion; }

    /** SHA-256 hex digest over the raw profile file bytes (Rule-14). */
    public String contentHash() { return contentHash; }

    public Map<String, String> eventTypeMapping() { return eventTypeMapping; }

    /** Get field mapping for the given canonical event type group (or _global_ for flat). */
    public Map<String, String> fieldMappingForGroup(String canonicalEventType) {
        Map<String, String> group = fieldMappingByGroup.get(canonicalEventType);
        Map<String, String> global = fieldMappingByGroup.get("_global_");
        if (group == null) {
            return global != null ? global : Map.of();
        }
        if (global == null || global.isEmpty()) {
            return group;
        }
        Map<String, String> merged = new LinkedHashMap<>(global);
        merged.putAll(group);
        return merged;
    }

    public Map<String, Map<String, String>> enumSemantics() { return enumSemantics; }

    /**
     * Look up enum mapping: returns null if the sourceValue is not registered (BAD_ENUM),
     * returns the entry if found (value may be null = ENUM_UNRESOLVED).
     */
    public EnumLookupResult lookupEnum(String fieldName, String sourceValue) {
        Map<String, String> mapping = enumSemantics.get(fieldName);
        if (mapping == null) {
            // No enum declared for this field - not an enum field, pass through
            return EnumLookupResult.NOT_AN_ENUM_FIELD;
        }
        if (!mapping.containsKey(sourceValue)) {
            // Registered enum field, value not in list -> BAD_ENUM
            return EnumLookupResult.bad();
        }
        String canonicalValue = mapping.get(sourceValue);
        if (canonicalValue == null) {
            // Observed but undecided -> ENUM_UNRESOLVED (Rule-12)
            return EnumLookupResult.unresolved(sourceValue);
        }
        return EnumLookupResult.resolved(canonicalValue);
    }

    public String timePolicyField() { return timePolicyField; }
    public List<String> timePolicyFormats() { return timePolicyFormats; }
    public String timePolicyTimezone() { return timePolicyTimezone; }
    public Map<String, String> amountUnitByField() { return amountUnitByField; }
    public Set<String> keepFields() { return keepFields; }
    public Set<String> sensitiveFields() { return sensitiveFields; }
    public List<MapperViolation> validationErrors() { return validationErrors; }
    public boolean isValid() { return validationErrors.isEmpty(); }

    public String unknownEventTypePolicy() {
        return unknownEventTypePolicy != null ? unknownEventTypePolicy : "QUARANTINE";
    }
    public String unknownFieldPolicy() {
        return unknownFieldPolicy != null ? unknownFieldPolicy : "KEEP_IN_PAYLOAD";
    }

    // ---- Loader ----

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Parse a source-profile JSON file into an immutable SourceProfile.
     * SHA-256 is computed over the raw file bytes (Rule-14).
     */
    public static SourceProfile fromFile(Path profileFile) throws IOException {
        byte[] bytes = Files.readAllBytes(profileFile);
        return fromBytes(bytes);
    }

    /**
     * Parse a source-profile JSON from bytes.
     * SHA-256 is computed over the raw bytes (Rule-14).
     */
    public static SourceProfile fromBytes(byte[] bytes) throws IOException {
        String contentHash = sha256Hex(bytes);
        JsonNode root = OBJECT_MAPPER.readTree(bytes);
        return parseJson(root, bytes, contentHash);
    }

    /**
     * Parse from a JSON string (useful in tests).
     */
    public static SourceProfile fromJson(String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return fromBytes(bytes);
    }

    private static SourceProfile parseJson(JsonNode root, byte[] rawBytes, String contentHash) {
        Builder b = new Builder();
        b.rawBytes = rawBytes;
        b.contentHash = contentHash;

        b.profileVersion = text(root, "profileVersion");
        b.sourceCode = text(root, "sourceCode");

        JsonNode canonical = root.path("canonical");
        b.canonicalSchemaVersion = text(canonical, "schemaVersion");

        // eventTypeMapping
        JsonNode etm = root.path("eventTypeMapping");
        if (etm.isObject()) {
            etm.fields().forEachRemaining(e -> {
                String target = e.getValue().isNull() ? null : e.getValue().asText();
                b.eventTypeMapping.put(e.getKey(), target);
            });
        }

        // fieldMapping - support both per-group and flat
        JsonNode fm = root.path("fieldMapping");
        if (fm.isObject()) {
            boolean isPerGroup = false;
            Iterator<Map.Entry<String, JsonNode>> it = fm.fields();
            while (it.hasNext()) {
                if (it.next().getValue().isObject()) {
                    isPerGroup = true;
                    break;
                }
            }
            if (isPerGroup) {
                fm.fields().forEachRemaining(e -> {
                    String group = e.getKey();
                    Map<String, String> groupMap = new LinkedHashMap<>();
                    e.getValue().fields().forEachRemaining(f -> {
                        String target = f.getValue().isNull() ? null : f.getValue().asText();
                        groupMap.put(f.getKey(), target);
                        if ("@keep".equals(target)) {
                            b.keepFields.add(f.getKey());
                        }
                    });
                    b.fieldMappingByGroup.put(group, groupMap);
                });
            } else {
                // Flat mapping -> stored under _global_
                Map<String, String> globalMap = new LinkedHashMap<>();
                fm.fields().forEachRemaining(e -> {
                    String target = e.getValue().isNull() ? null : e.getValue().asText();
                    globalMap.put(e.getKey(), target);
                    if ("@keep".equals(target)) {
                        b.keepFields.add(e.getKey());
                    }
                });
                b.fieldMappingByGroup.put("_global_", globalMap);
            }
        }

        // enumSemantics
        JsonNode es = root.path("enumSemantics");
        if (es.isObject()) {
            es.fields().forEachRemaining(e -> {
                String fieldName = e.getKey();
                Map<String, String> valMap = new LinkedHashMap<>();
                e.getValue().fields().forEachRemaining(f -> {
                    String canonical2 = f.getValue().isNull() ? null : f.getValue().asText();
                    valMap.put(f.getKey(), canonical2);
                });
                b.enumSemantics.put(fieldName, valMap);
            });
        }

        // timePolicy
        JsonNode tp = root.path("timePolicy");
        if (tp.isObject()) {
            b.timePolicyField = text(tp, "field");
            b.timePolicyTimezone = text(tp, "timezone");
            if (b.timePolicyTimezone == null) {
                b.timePolicyTimezone = text(root, "timezone");
            }
            JsonNode fmts = tp.path("formats");
            if (fmts.isArray()) {
                fmts.forEach(f -> b.timePolicyFormats.add(f.asText()));
            }
        }
        if (b.timePolicyTimezone == null) {
            b.timePolicyTimezone = text(root, "timezone");
        }
        if (b.timePolicyTimezone == null) {
            b.timePolicyTimezone = "Asia/Shanghai";
        }

        // amountPolicy (optional)
        JsonNode ap = root.path("amountPolicy");
        if (ap.isObject()) {
            ap.fields().forEachRemaining(e -> {
                b.amountUnitByField.put(e.getKey(), e.getValue().asText());
            });
        }

        // sensitiveFields (Rule-10)
        JsonNode sf = root.path("sensitiveFields");
        if (sf.isArray()) {
            sf.forEach(s -> b.sensitiveFields.add(s.asText()));
        }

        // quarantinePolicy
        JsonNode qp = root.path("quarantinePolicy");
        if (qp.isObject()) {
            b.unknownEventTypePolicy = text(qp, "unknownEventType");
            b.unknownFieldPolicy = text(qp, "unknownField");
        }

        // Rule-2 validation: same event-type group, two source paths -> same target = PROFILE_INVALID
        validateRule2(b);
        // Rule-3 validation: @keep collision with canonical field or another @keep (same name)
        validateRule3(b);

        return new SourceProfile(b);
    }

    /**
     * Rule-2 (Ruling R-2A-1): Within explicitly declared eventType groups, if two source field
     * paths map to the same canonical target, the profile is PROFILE_INVALID.
     * The flat fieldMapping ("_global_") is the shared table, NOT an event group; static rule-2
     * validation skips "_global_". Conflict protection moves to map time for records using the
     * shared table. Cross-group field-name reuse is legal.
     */
    private static void validateRule2(Builder b) {
        for (Map.Entry<String, Map<String, String>> groupEntry : b.fieldMappingByGroup.entrySet()) {
            String group = groupEntry.getKey();
            if ("_global_".equals(group)) {
                continue;
            }
            Map<String, String> mapping = groupEntry.getValue();
            Map<String, List<String>> targetToSources = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                String target = e.getValue();
                if (target == null || "@keep".equals(target)) continue;
                targetToSources.computeIfAbsent(target, k -> new ArrayList<>()).add(e.getKey());
            }
            for (Map.Entry<String, List<String>> e : targetToSources.entrySet()) {
                if (e.getValue().size() > 1) {
                    b.validationErrors.add(MapperViolation.of(
                            MapperViolationCode.PROFILE_INVALID,
                            group + "." + e.getKey(),
                            "Rule-2: multiple source paths " + e.getValue()
                                    + " map to same target in group [" + group + "]"
                    ));
                }
            }
        }
    }

    /**
     * Rule-3: @keep field collides with a canonical field or another extension.
     */
    private static void validateRule3(Builder b) {
        // Check keepFields against canonical fields (envelope + standard payload)
        for (String keepField : b.keepFields) {
            String leaf = keepField.contains(".") ? keepField.substring(keepField.lastIndexOf('.') + 1) : keepField;
            if (CANONICAL_FIELDS.contains(leaf) || CANONICAL_FIELDS.contains(keepField)) {
                b.validationErrors.add(MapperViolation.of(
                        MapperViolationCode.PROFILE_INVALID,
                        keepField,
                        "Rule-3: @keep field [" + keepField + "] collides with canonical field"
                ));
            }
        }
        // Check duplicate @keep names within each group
        for (Map.Entry<String, Map<String, String>> groupEntry : b.fieldMappingByGroup.entrySet()) {
            Map<String, String> mapping = groupEntry.getValue();
            Set<String> keepLeafNames = new HashSet<>();
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                if ("@keep".equals(e.getValue())) {
                    String leaf = e.getKey().contains(".") ? e.getKey().substring(e.getKey().lastIndexOf('.') + 1) : e.getKey();
                    if (!keepLeafNames.add(leaf)) {
                        b.validationErrors.add(MapperViolation.of(
                                MapperViolationCode.PROFILE_INVALID,
                                leaf,
                                "Rule-3: duplicate @keep extension name [" + leaf + "] in group [" + groupEntry.getKey() + "]"
                        ));
                    }
                }
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ---- Builder ----

    private static final class Builder {
        byte[] rawBytes;
        String contentHash;
        String profileVersion;
        String sourceCode;
        String canonicalSchemaVersion;
        final Map<String, String> eventTypeMapping = new LinkedHashMap<>();
        final Map<String, Map<String, String>> fieldMappingByGroup = new LinkedHashMap<>();
        final Map<String, Map<String, String>> enumSemantics = new LinkedHashMap<>();
        String timePolicyField;
        final List<String> timePolicyFormats = new ArrayList<>();
        String timePolicyTimezone;
        final Map<String, String> amountUnitByField = new LinkedHashMap<>();
        final Set<String> keepFields = new HashSet<>();
        final Set<String> sensitiveFields = new HashSet<>();
        final List<MapperViolation> validationErrors = new ArrayList<>();
        String unknownEventTypePolicy;
        String unknownFieldPolicy;
    }

    // ---- Enum lookup result ----

    public sealed interface EnumLookupResult
            permits EnumLookupResult.NotAnEnumField, EnumLookupResult.Resolved,
            EnumLookupResult.Unresolved, EnumLookupResult.Bad {

        EnumLookupResult NOT_AN_ENUM_FIELD = new NotAnEnumField();

        static EnumLookupResult resolved(String canonicalValue) { return new Resolved(canonicalValue); }
        static EnumLookupResult unresolved(String rawValue) { return new Unresolved(rawValue); }
        static EnumLookupResult bad() { return new Bad(); }

        /** Field is not declared as an enum field in the profile. */
        record NotAnEnumField() implements EnumLookupResult {}

        /** Value resolved to a canonical value. */
        record Resolved(String canonicalValue) implements EnumLookupResult {}

        /** Value present in profile with null mapping (ENUM_UNRESOLVED). */
        record Unresolved(String rawValue) implements EnumLookupResult {}

        /** Value not in the profile's enum list (BAD_ENUM). */
        record Bad() implements EnumLookupResult {}
    }
}
