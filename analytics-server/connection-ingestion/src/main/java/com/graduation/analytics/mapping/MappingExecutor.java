package com.graduation.analytics.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 确定性映射执行器（设计 §7.3 的 14 条规则里可纯转换落地的部分；§8.3 line 277
 * 「Mapper（目标）| raw+mapping→canonical 或 quarantine | 纯转换与持久化分离」）。
 *
 * <p><b>纯函数</b>：无 Controller、无 HTTP、无 DB、无 checkpoint、无 Spark、无前端；不读时钟、不用随机数，
 * 因此「同一 raw + 同一画像」必须得到逐字节相同的 canonical 与两个 checksum（规则 14）。</p>
 *
 * <p>口径要点（未在设计里逐字冻结的部分已登记在 S2-01A 回报的歧义项）：</p>
 * <ul>
 *   <li>只要存在 1 条**违例**（{@link MappingReason#warningOnly()} 为 false）就隔离整事件：不产出 canonical，
 *       违例全部保留在 {@code violations}（规则 13：按违例计，可多于隔离行数）；告警（第三态枚举）不隔离。</li>
 *   <li>{@code ingest_time} 由平台 ingestion 层生成（S2-02），Mapper **不写** canonical，只登记
 *       {@code pendingPlatformFields} 与源侧候选 {@code ingestTimeCandidate}（仅作证据，不计入覆盖率分母）。</li>
 *   <li>金额单位一律来自画像 {@code amountPolicy.bySourceField}（源字段 → FEN/YUAN）；没有默认单位，
 *       缺声明即 fail-closed（{@code PROFILE_INVALID/MISSING_AMOUNT_POLICY}），绝不猜 FEN 或 YUAN。</li>
 *   <li>时间：{@code timePolicy} 只规范化 {@code event_time}；其余契约时间字段（如 {@code paid_at}）按原文
 *       文本直通，非文本一律 {@code TYPE_MISMATCH}（不猜 epoch 语义）。本地时间/epoch 必须由画像显式声明
 *       IANA 时区；只声明 {@code ISO_OFFSET_DATE_TIME} 时保留原文偏移，不再隐式折算到业务时区。</li>
 *   <li>载荷基准：v2 严格模式要求声明的 payload 容器真实存在，否则整事件隔离（不做「回落根」兜底）；
 *       v1 兼容模式保留历史回落行为。</li>
 * </ul>
 */
public final class MappingExecutor {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final DateTimeFormatter OFFSET_OUT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final String EVENT_TYPE = "event_type";
    private static final String EVENT_TIME = "event_time";
    private static final String SCHEMA_VERSION = "schema_version";
    private static final String PAYLOAD = "payload";

    private final CanonicalContract contract;
    private final ObjectMapper mapper;

    public MappingExecutor(CanonicalContract contract, ObjectMapper mapper) {
        this.contract = Objects.requireNonNull(contract, "contract");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 文本入口：raw 文本即原始证据，其 sha256 就是 {@code rawChecksum}。 */
    public MappingOutcome execute(MappingProfile profile, String rawJson) {
        Objects.requireNonNull(profile, "profile");
        if (rawJson == null) {
            return parseFailure(profile, NODES.nullNode(), "", "EMPTY_TEXT");
        }
        JsonNode raw;
        try {
            raw = mapper.readTree(rawJson);
        } catch (IOException e) {
            return parseFailure(profile, NODES.textNode(rawJson), rawJson, "PARSE_FAILED:" + e.getMessage());
        }
        return run(profile, raw, MappingHash.sha256Hex(rawJson));
    }

    /** 已解析入口（同进程复用，避免重复解析）；rawChecksum 取该节点的确定性序列化。 */
    public MappingOutcome execute(MappingProfile profile, JsonNode raw) {
        Objects.requireNonNull(profile, "profile");
        if (raw == null) {
            return parseFailure(profile, NODES.nullNode(), "", "EMPTY_TEXT");
        }
        return run(profile, raw, MappingHash.sha256Hex(raw.toString()));
    }

    private MappingOutcome parseFailure(MappingProfile profile, JsonNode raw, String rawText, String detail) {
        Ctx ctx = new Ctx(profile, raw, MappingHash.sha256Hex(rawText));
        ctx.violate(MappingReason.JSON_PARSE_ERROR, "raw", detail);
        return ctx.toOutcome(null);
    }

    private MappingOutcome run(MappingProfile profile, JsonNode raw, String rawChecksum) {
        Ctx ctx = new Ctx(profile, raw, rawChecksum);
        if (!raw.isObject()) {
            ctx.violate(MappingReason.TYPE_MISMATCH, "raw", "RAW_NOT_AN_OBJECT");
            return ctx.toOutcome(null);
        }
        String canonicalType = resolveEventType(ctx);
        if (canonicalType == null) {
            return ctx.toOutcome(null);
        }
        ObjectNode payload = buildPayload(ctx, canonicalType);
        ObjectNode canonical = buildEnvelope(ctx, canonicalType, payload);
        return ctx.toOutcome(canonical);
    }

    // ------------------------------------------------------------------ 规则 12：事件类型

    private String resolveEventType(Ctx ctx) {
        JsonNode node = MappingPath.resolve(ctx.raw, ctx.profile.eventTypeSourceField());
        if (node == null) {
            ctx.violate(MappingReason.EMPTY_FIELD, EVENT_TYPE, "MISSING");
            return null;
        }
        if (node.isNull()) {
            ctx.violate(MappingReason.EMPTY_FIELD, EVENT_TYPE, "NULL");
            return null;
        }
        if (!node.isTextual()) {
            ctx.violate(MappingReason.TYPE_MISMATCH, EVENT_TYPE, "TYPE_NOT_TEXT");
            return null;
        }
        String rawValue = node.asText();
        if (rawValue.trim().isEmpty()) {
            ctx.violate(MappingReason.EMPTY_FIELD, EVENT_TYPE, "BLANK");
            return null;
        }
        if (!ctx.profile.eventTypeMappings().containsKey(rawValue)) {
            ctx.violate(MappingReason.UNKNOWN_EVENT_TYPE, EVENT_TYPE, "UNKNOWN");
            return null;
        }
        String canonicalType = ctx.profile.eventTypeMappings().get(rawValue);
        if (canonicalType == null) {
            ctx.violate(MappingReason.UNKNOWN_EVENT_TYPE, EVENT_TYPE, "TYPE_UNRESOLVED");
            return null;
        }
        return canonicalType;
    }

    // ------------------------------------------------------------------ 规则 2/4/5/6：信封

    private ObjectNode buildEnvelope(Ctx ctx, String canonicalType, ObjectNode payload) {
        ObjectNode canonical = NODES.objectNode();
        for (Map.Entry<String, CanonicalContract.FieldSpec> entry : contract.envelopeFields().entrySet()) {
            String target = entry.getKey();
            CanonicalContract.FieldSpec spec = entry.getValue();

            if (target.equals(contract.platformIngestField())) {
                // 平台生成（S2-02）：不写 canonical，只登记源侧候选
                String source = ctx.envelopeSource(target);
                JsonNode candidate = source == null ? null : MappingPath.resolve(ctx.raw, source);
                ctx.ingestTimeCandidate = candidate != null && candidate.isTextual() ? candidate.asText() : null;
                continue;
            }
            if (target.equals(PAYLOAD)) {
                canonical.set(PAYLOAD, payload);
                ctx.required(true);
                continue;
            }
            if (target.equals(EVENT_TYPE)) {
                canonical.put(EVENT_TYPE, canonicalType);
                ctx.required(true);
                continue;
            }
            if (target.equals(SCHEMA_VERSION)) {
                Mapped mapped = mapSchemaVersion(ctx);
                ctx.required(mapped.node() != null);
                if (mapped.node() != null) {
                    canonical.set(SCHEMA_VERSION, mapped.node());
                }
                continue;
            }
            if (target.equals(EVENT_TIME)) {
                Mapped mapped = mapEventTime(ctx);
                ctx.required(mapped.node() != null);
                if (mapped.node() != null) {
                    canonical.set(EVENT_TIME, mapped.node());
                }
                continue;
            }

            String source = ctx.envelopeSource(target);
            JsonNode value = source == null ? null : MappingPath.resolve(ctx.raw, source);
            if (!ctx.present(value, target, spec.required())) {
                ctx.required(false);
                continue;
            }
            Mapped mapped = convert(ctx, spec, value, target, source);
            ctx.required(mapped.node() != null);
            if (mapped.node() != null) {
                canonical.set(target, mapped.node());
            }
        }
        return canonical;
    }

    /** 规则 4：有序 formats 第一个成功者生效；epoch 必须显式声明；本地时间按 IANA 时区判 DST 跳空/重叠。 */
    private Mapped mapEventTime(Ctx ctx) {
        TimePolicySpec policy = ctx.profile.timePolicy();
        JsonNode node = MappingPath.resolve(ctx.raw, policy.field());
        if (node == null) {
            ctx.violate(MappingReason.EMPTY_FIELD, EVENT_TIME, "MISSING");
            return Mapped.failed();
        }
        if (node.isNull()) {
            ctx.violate(MappingReason.EMPTY_FIELD, EVENT_TIME, "NULL");
            return Mapped.failed();
        }
        if (node.isTextual() && node.asText().trim().isEmpty()) {
            ctx.violate(MappingReason.EMPTY_FIELD, EVENT_TIME, "BLANK");
            return Mapped.failed();
        }
        if (node.isObject() || node.isArray()) {
            ctx.violate(MappingReason.TYPE_MISMATCH, EVENT_TIME, "TIME_NOT_SCALAR");
            return Mapped.failed();
        }

        // 时区：只声明自带偏移格式时可以为 null（保留原文偏移）；本地时间/epoch 由装载期保证已声明
        ZoneId zone = policy.zone() == null ? null : ZoneId.of(policy.zone());
        if (zone == null && policy.requiresZone()) {
            ctx.violate(MappingReason.PROFILE_INVALID, "timePolicy.zone", "MISSING_KEY:timePolicy.zone");
            return Mapped.failed();
        }
        String text = node.asText();
        String usedToken = null;
        String normalized = null;
        String ambiguous = null;

        for (TimePolicySpec.Format format : policy.formats()) {
            try {
                switch (format.kind()) {
                    case OFFSET -> {
                        OffsetDateTime parsed = OffsetDateTime.parse(text, format.formatter());
                        ctx.timeFormatsMatched++;
                        if (normalized == null) {
                            normalized = zone == null
                                    ? parsed.format(OFFSET_OUT)
                                    : parsed.atZoneSameInstant(zone).format(OFFSET_OUT);
                            usedToken = format.token();
                        }
                    }
                    case LOCAL -> {
                        LocalDateTime parsed = LocalDateTime.parse(text, format.formatter());
                        ctx.timeFormatsMatched++;
                        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(parsed);
                        if (offsets.isEmpty()) {
                            if (ambiguous == null) {
                                ambiguous = "DST_GAP";
                            }
                        } else if (offsets.size() > 1) {
                            if (normalized == null) {
                                ambiguous = "DST_OVERLAP";
                            }
                        } else if (normalized == null) {
                            normalized = ZonedDateTime.of(parsed, zone).format(OFFSET_OUT);
                            usedToken = format.token();
                        }
                    }
                    case EPOCH_MILLIS -> {
                        Instant instant = Instant.ofEpochMilli(Long.parseLong(text.trim()));
                        ctx.timeFormatsMatched++;
                        if (normalized == null) {
                            normalized = instant.atZone(zone).format(OFFSET_OUT);
                            usedToken = format.token();
                        }
                    }
                    case EPOCH_SECONDS -> {
                        Instant instant = Instant.ofEpochSecond(Long.parseLong(text.trim()));
                        ctx.timeFormatsMatched++;
                        if (normalized == null) {
                            normalized = instant.atZone(zone).format(OFFSET_OUT);
                            usedToken = format.token();
                        }
                    }
                }
            } catch (DateTimeParseException | NumberFormatException | ArithmeticException e) {
                // 该格式不匹配 ⇒ 继续尝试下一个（顺序即语义）
            }
        }
        if (normalized != null) {
            ctx.timeFormatUsed = usedToken;
            return Mapped.ok(NODES.textNode(normalized));
        }
        if (ambiguous != null) {
            ctx.violate(MappingReason.TIME_AMBIGUOUS_LOCAL, EVENT_TIME, ambiguous);
            return Mapped.failed();
        }
        ctx.violate(MappingReason.BAD_TIME_FORMAT, EVENT_TIME, "NO_DECLARED_FORMAT_MATCHED");
        return Mapped.failed();
    }

    /**
     * 规则 6：契约版本必须是画像声明的受支持版本，否则整事件隔离（不按普通枚举处理，
     * 也不计入枚举覆盖率——它不是业务枚举）。
     */
    private Mapped mapSchemaVersion(Ctx ctx) {
        String source = ctx.envelopeSource(SCHEMA_VERSION);
        JsonNode node = source == null ? null : MappingPath.resolve(ctx.raw, source);
        if (!ctx.present(node, SCHEMA_VERSION, true)) {
            return Mapped.failed();
        }
        if (!node.isTextual()) {
            return ctx.typeMismatch(SCHEMA_VERSION, CanonicalContract.Kind.STRING, node);
        }
        String declared = node.asText();
        if (!declared.equals(contract.contractVersion())) {
            ctx.violate(MappingReason.UNSUPPORTED_SCHEMA_VERSION, SCHEMA_VERSION,
                    "DECLARED=" + declared + " SUPPORTED=" + contract.contractVersion());
            return Mapped.failed();
        }
        return Mapped.ok(NODES.textNode(declared));
    }

    // ------------------------------------------------------------------ 规则 3/5/7/9/12：载荷

    private ObjectNode buildPayload(Ctx ctx, String canonicalType) {
        ObjectNode payload = NODES.objectNode();
        if (ctx.payloadContainerMissing()) {
            // v2 严格模式：声明的 payload 容器不存在 ⇒ 不逐字段刷 EMPTY_FIELD 噪音，
            // 只保留构造期那一条 PROFILE_INVALID，但把契约必填位置全部计入分母（诚实统计）。
            contract.payloadFields(canonicalType).keySet().forEach(field -> ctx.required(false));
            return payload;
        }
        JsonNode base = ctx.base();
        Map<String, String> scalars = ctx.profile.payloadFieldMappings(canonicalType);
        Map<String, String> sourceByTarget = new LinkedHashMap<>();
        List<String> keepSources = new ArrayList<>();
        scalars.forEach((source, target) -> {
            if (MappingProfileLoader.KEEP.equals(target)) {
                keepSources.add(source);
            } else {
                sourceByTarget.put(target, source);
            }
        });
        Map<String, ItemMapSpec> itemMapByArrayField = new LinkedHashMap<>();
        Map<String, String> sourceByArrayField = new LinkedHashMap<>();
        ctx.profile.itemMaps(canonicalType).forEach((source, spec) -> {
            itemMapByArrayField.put(spec.arrayField(), spec);
            sourceByArrayField.put(spec.arrayField(), source);
        });

        for (Map.Entry<String, CanonicalContract.FieldSpec> entry : contract.payloadFields(canonicalType).entrySet()) {
            String field = entry.getKey();
            CanonicalContract.FieldSpec spec = entry.getValue();
            String path = PAYLOAD + "." + field;

            ItemMapSpec itemMap = itemMapByArrayField.get(field);
            if (itemMap != null) {
                Mapped mapped = mapItems(ctx, itemMap, sourceByArrayField.get(field), base, path);
                ctx.required(mapped.countsAsOk());
                if (mapped.node() != null) {
                    payload.set(field, mapped.node());
                }
                continue;
            }

            String source = sourceByTarget.get(field);
            if (source == null) {
                ctx.violate(MappingReason.EMPTY_FIELD, path, "NOT_MAPPED");
                ctx.required(false);
                continue;
            }
            JsonNode value = MappingPath.resolve(base, source);
            if (!ctx.present(value, path, spec.required())) {
                ctx.required(false);
                continue;
            }
            Mapped mapped = convert(ctx, spec, value, path, source);
            ctx.required(mapped.countsAsOk());
            if (mapped.node() != null) {
                payload.set(field, mapped.node());
            }
        }

        // 规则 3：@keep 扩展按原始叶子名保留，排在契约字段之后（声明顺序）
        for (String source : keepSources) {
            JsonNode value = MappingPath.resolve(base, source);
            if (value == null || value.isNull()) {
                continue;
            }
            payload.set(MappingPath.leafName(source), value);
            ctx.keptExtensions++;
        }
        return payload;
    }

    /** 规则 9：ITEM_MAP 保持顺序与数量；任一项必填失败 ⇒ 整事件隔离；字符串形态按 D-063 直通。 */
    private Mapped mapItems(Ctx ctx, ItemMapSpec itemMap, String arraySource, JsonNode base, String path) {
        JsonNode sourceNode = MappingPath.resolve(base, arraySource);
        if (sourceNode == null) {
            ctx.violate(MappingReason.EMPTY_FIELD, path, "MISSING");
            return Mapped.failed();
        }
        if (sourceNode.isNull()) {
            ctx.violate(MappingReason.EMPTY_FIELD, path, "NULL");
            return Mapped.failed();
        }
        if (sourceNode.isTextual()) {
            if (sourceNode.asText().trim().isEmpty()) {
                ctx.violate(MappingReason.EMPTY_FIELD, path, "BLANK");
                return Mapped.failed();
            }
            ctx.itemMapMode = MappingStats.ITEM_MAP_PASSTHROUGH_STRING;
            return Mapped.ok(NODES.textNode(sourceNode.asText()));
        }
        if (!sourceNode.isArray()) {
            ctx.violate(MappingReason.TYPE_MISMATCH, path, "ITEMS_NOT_ARRAY");
            return Mapped.failed();
        }

        Map<String, String> sourceByItemField = new LinkedHashMap<>();
        itemMap.itemFields().forEach((source, target) -> sourceByItemField.put(target, source));

        ArrayNode items = NODES.arrayNode();
        boolean allOk = true;
        for (int index = 0; index < sourceNode.size(); index++) {
            JsonNode element = sourceNode.get(index);
            String itemPath = path + "[" + index + "]";
            if (!element.isObject()) {
                ctx.violate(MappingReason.TYPE_MISMATCH, itemPath, "ITEM_NOT_OBJECT");
                allOk = false;
                break;
            }
            ObjectNode item = NODES.objectNode();
            boolean itemOk = true;
            for (Map.Entry<String, CanonicalContract.FieldSpec> entry : contract.itemFields().entrySet()) {
                String itemField = entry.getKey();
                CanonicalContract.FieldSpec itemSpec = entry.getValue();
                String fieldPath = itemPath + "." + itemField;
                String itemSource = sourceByItemField.get(itemField);
                if (itemSource == null) {
                    ctx.violate(MappingReason.EMPTY_FIELD, fieldPath, "NOT_MAPPED");
                    itemOk = false;
                    continue;
                }
                JsonNode value = element.get(itemSource);
                if (!ctx.present(value, fieldPath, itemSpec.required())) {
                    itemOk = false;
                    continue;
                }
                Mapped mapped = convert(ctx, itemSpec, value, fieldPath, itemSource);
                if (mapped.node() == null) {
                    itemOk = false;
                    continue;
                }
                item.set(itemField, mapped.node());
            }
            if (itemOk) {
                items.add(item);
                ctx.itemsMapped++;
            } else {
                allOk = false;
            }
        }
        if (!allOk) {
            // 规则 9：不产出半截 items（canonical 整体因违例而废弃）
            return Mapped.failed();
        }
        ctx.itemMapMode = MappingStats.ITEM_MAP_MAPPED;
        return Mapped.ok(items);
    }

    // ------------------------------------------------------------------ 规则 6/7/12：单值转换

    /** {@code node == null} 即失败（已记违例）；{@code countsAsOk == false} 且 node 非空 = 第三态 null（告警，不隔离）。 */
    private record Mapped(JsonNode node, boolean countsAsOk) {
        static Mapped ok(JsonNode node) {
            return new Mapped(node, true);
        }

        static Mapped thirdState() {
            return new Mapped(NODES.nullNode(), false);
        }

        static Mapped failed() {
            return new Mapped(null, false);
        }
    }

    private Mapped convert(Ctx ctx,
                           CanonicalContract.FieldSpec spec,
                           JsonNode value,
                           String path,
                           String sourceField) {
        switch (spec.kind()) {
            case STRING:
                if (value.isTextual() || value.isNumber() || value.isBoolean()) {
                    return Mapped.ok(NODES.textNode(value.asText()));
                }
                return ctx.typeMismatch(path, spec.kind(), value);
            case INTEGER:
                if (value.isIntegralNumber()) {
                    return Mapped.ok(NODES.numberNode(value.longValue()));
                }
                return ctx.typeMismatch(path, spec.kind(), value);
            case NUMBER:
                if (value.isNumber()) {
                    return Mapped.ok(value.deepCopy());
                }
                return ctx.typeMismatch(path, spec.kind(), value);
            case BOOLEAN:
                if (value.isBoolean()) {
                    return Mapped.ok(value.deepCopy());
                }
                return ctx.typeMismatch(path, spec.kind(), value);
            case ENUM:
                return mapEnum(ctx, spec, value, path);
            case AMOUNT:
                return mapAmount(ctx, value, path, sourceField);
            case TIME:
                if (value.isTextual()) {
                    return Mapped.ok(NODES.textNode(value.asText()));
                }
                return ctx.typeMismatch(path, spec.kind(), value);
            case ITEMS:
                // 规则 9：数组形态必须声明 ITEM_MAP，否则无法保证「顺序与数量」；不假设源侧已是 canonical 形状
                ctx.violate(MappingReason.PROFILE_INVALID, path, "ITEMS_REQUIRES_ITEM_MAP");
                return Mapped.failed();
            default:
                return ctx.typeMismatch(path, spec.kind(), value);
        }
    }

    private Mapped mapEnum(Ctx ctx, CanonicalContract.FieldSpec spec, JsonNode value, String path) {
        if (!value.isTextual()) {
            return ctx.typeMismatch(path, spec.kind(), value);
        }
        String rawValue = value.asText();
        ctx.enumObserved.add(pair(path, rawValue));
        Map<String, String> semantics = ctx.profile.enumSemantics().getOrDefault(spec.name(), Map.of());
        if (semantics.containsKey(rawValue)) {
            String mapped = semantics.get(rawValue);
            if (mapped == null) {
                // 规则 12 第三态：观察到但未裁定 ⇒ 保留 null + warning，不隔离
                ctx.warn(MappingReason.ENUM_UNRESOLVED, path, rawValue);
                return Mapped.thirdState();
            }
            ctx.enumResolved.add(pair(path, rawValue));
            return Mapped.ok(NODES.textNode(mapped));
        }
        if (spec.hasEnumValues() && spec.enumValues().contains(rawValue)) {
            ctx.enumResolved.add(pair(path, rawValue));
            return Mapped.ok(NODES.textNode(rawValue));
        }
        ctx.violate(MappingReason.BAD_ENUM, path, rawValue);
        return Mapped.failed();
    }

    private Mapped mapAmount(Ctx ctx, JsonNode value, String path, String sourceField) {
        AmountPolicySpec policy = ctx.profile.amountPolicy();
        if (policy == null) {
            // 不默认 FEN/YUAN：宁可隔离，也不产出可能差 100 倍的钱
            ctx.violate(MappingReason.PROFILE_INVALID, path, "MISSING_AMOUNT_POLICY");
            return Mapped.failed();
        }
        if (value.isObject() || value.isArray()) {
            return ctx.typeMismatch(path, CanonicalContract.Kind.AMOUNT, value);
        }
        BigDecimal amount;
        if (value.isNumber()) {
            amount = value.decimalValue();
        } else {
            try {
                amount = new BigDecimal(value.asText().trim());
            } catch (NumberFormatException e) {
                ctx.violate(MappingReason.BAD_AMOUNT, path, "NOT_NUMERIC");
                return Mapped.failed();
            }
        }
        if (amount.signum() < 0) {
            ctx.violate(MappingReason.BAD_AMOUNT, path, "NEGATIVE");
            return Mapped.failed();
        }
        AmountPolicySpec.Unit unit = policy.unitFor(sourceField);
        if (unit == null) {
            // 该源字段没声明单位：不默认 FEN/YUAN，宁可隔离，也不产出可能差 100 倍的钱
            ctx.violate(MappingReason.PROFILE_INVALID, path, "MISSING_AMOUNT_POLICY");
            return Mapped.failed();
        }
        if (unit == AmountPolicySpec.Unit.FEN) {
            if (amount.stripTrailingZeros().scale() > 0) {
                ctx.violate(MappingReason.BAD_AMOUNT, path, "FEN_NOT_INTEGRAL");
                return Mapped.failed();
            }
            amount = amount.movePointLeft(2);
        } else if (amount.stripTrailingZeros().scale() > 2) {
            ctx.amountRounded++;
        }
        return Mapped.ok(NODES.textNode(amount.setScale(2, RoundingMode.HALF_UP).toPlainString()));
    }

    private static String pair(String path, String rawValue) {
        return path + '\u0000' + rawValue;
    }

    // ------------------------------------------------------------------ 累积与产出

    /** 单事件映射的累积状态（可变中间量，最终折叠为不可变结果）。 */
    private static final class Ctx {

        private final MappingProfile profile;
        private final JsonNode raw;
        private final String rawChecksum;
        private final List<MappingIssue> violations = new ArrayList<>();
        private final List<MappingIssue> warnings = new ArrayList<>();
        private final Map<MappingReason, Integer> reasonCounts = new LinkedHashMap<>();
        private final Set<String> enumObserved = new LinkedHashSet<>();
        private final Set<String> enumResolved = new LinkedHashSet<>();

        private int requiredTotal;
        private int requiredOk;
        private int timeFormatsMatched;
        private int amountRounded;
        private int keptExtensions;
        private int itemsMapped;
        private String timeFormatUsed;
        private String itemMapMode = MappingStats.ITEM_MAP_NONE;
        private String ingestTimeCandidate;
        private final JsonNode payloadBase;
        private final boolean payloadContainerMissing;

        Ctx(MappingProfile profile, JsonNode raw, String rawChecksum) {
            this.profile = profile;
            this.raw = raw;
            this.rawChecksum = rawChecksum;
            // 载荷解析基准：信封声明的 payload 来源（同名身份模式下即 payload）。
            // v2 严格模式：声明的容器必须真实存在且是对象，否则 fail-closed（不做「回落到根」的启发式兜底）；
            // v1 兼容模式：保持历史行为（容器不在就回落根），这是兼容口径而不是目标语义。
            String container = envelopeSource(PAYLOAD);
            JsonNode node = container == null ? null : MappingPath.resolve(raw, container);
            if (node != null && node.isObject()) {
                this.payloadBase = node;
                this.payloadContainerMissing = false;
            } else if (profile.syntax() == MappingProfile.ProfileSyntax.V2_STRICT && raw.isObject()) {
                // raw 本身不可解析/不是对象时只报根因（JSON_PARSE_ERROR / TYPE_MISMATCH），不叠加容器缺失
                this.payloadBase = raw;
                this.payloadContainerMissing = true;
                violate(MappingReason.PROFILE_INVALID, PAYLOAD, "PAYLOAD_CONTAINER_NOT_FOUND:" + container);
            } else {
                this.payloadBase = raw;
                this.payloadContainerMissing = false;
            }
        }

        /** 载荷解析基准（v2 已保证容器存在；v1 可能是根）。 */
        JsonNode base() {
            return payloadBase;
        }

        /** v2 严格模式声明的载荷容器不存在 ⇒ 整个 payload 无法判定（单一根因违例已在构造期登记）。 */
        boolean payloadContainerMissing() {
            return payloadContainerMissing;
        }

        String envelopeSource(String target) {
            return profile.envelopeSourceMode() == MappingProfile.EnvelopeSourceMode.DECLARED_ONLY
                    ? profile.envelopeSourceFor(target)
                    : target;
        }

        void required(boolean ok) {
            requiredTotal++;
            if (ok) {
                requiredOk++;
            }
        }

        void violate(MappingReason reason, String path, String detail) {
            if (reason.warningOnly()) {
                throw new IllegalArgumentException("warningOnly 原因码不得作为违例: " + reason);
            }
            violations.add(MappingIssue.of(reason, path, detail));
            reasonCounts.merge(reason, 1, Integer::sum);
        }

        void warn(MappingReason reason, String path, String detail) {
            warnings.add(MappingIssue.of(reason, path, detail));
        }

        /** 规则 5：MISSING / NULL / BLANK 三态区分；必填与否只由 canonical 契约决定（契约 1.0 无可选字段）。 */
        boolean present(JsonNode value, String path, boolean required) {
            if (value == null) {
                if (required) {
                    violate(MappingReason.EMPTY_FIELD, path, "MISSING");
                }
                return false;
            }
            if (value.isNull()) {
                if (required) {
                    violate(MappingReason.EMPTY_FIELD, path, "NULL");
                }
                return false;
            }
            if (value.isTextual() && value.asText().trim().isEmpty()) {
                if (required) {
                    violate(MappingReason.EMPTY_FIELD, path, "BLANK");
                }
                return false;
            }
            return true;
        }

        Mapped typeMismatch(String path, CanonicalContract.Kind expected, JsonNode actual) {
            violate(MappingReason.TYPE_MISMATCH, path, "EXPECTED=" + expected + " ACTUAL=" + actual.getNodeType());
            return Mapped.failed();
        }

        MappingOutcome toOutcome(JsonNode canonical) {
            // 只要存在违例就不产出 canonical（告警不在此列）：禁止半成品进入下游
            JsonNode effective = violations.isEmpty() ? canonical : null;
            MappingStats stats = new MappingStats(
                    Collections.unmodifiableMap(new LinkedHashMap<>(reasonCounts)),
                    requiredTotal,
                    requiredOk,
                    requiredTotal == 0 ? null : (double) requiredOk / requiredTotal,
                    enumObserved.size(),
                    enumResolved.size(),
                    enumObserved.isEmpty() ? null : (double) enumResolved.size() / enumObserved.size(),
                    timeFormatUsed,
                    timeFormatsMatched,
                    amountRounded,
                    keptExtensions,
                    0,
                    itemsMapped,
                    itemMapMode,
                    base() == raw ? MappingStats.BASE_ROOT : MappingStats.BASE_PAYLOAD_CONTAINER);
            return new MappingOutcome(
                    raw,
                    effective,
                    List.copyOf(violations),
                    List.copyOf(warnings),
                    stats,
                    List.of(MappingOutcome.PLATFORM_INGEST_TIME),
                    ingestTimeCandidate,
                    rawChecksum,
                    effective == null ? null : MappingHash.sha256Hex(effective.toString()));
        }
    }
}
