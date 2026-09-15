package com.graduation.analytics.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 映射画像装载与**静态**校验（设计 §7.3 规则 1/2/3/4/7/12/14）。
 *
 * <p>纪律：**不猜默认值**。契约版本、时间格式与时区、金额单位、枚举绑定都必须来自画像明文或
 * canonical 契约的明文声明；缺声明一律 fail-closed（装载失败或禁止激活），不允许「先跑起来再说」。</p>
 *
 * <p>两种画像形状都支持，且**形状判定只看结构，不看取值**（避免用「键名像不像字段」这种启发式）：</p>
 * <ul>
 *   <li>v2（设计 §7.2/§7.3 描述的 <b>目标</b>形状，{@link MappingProfile.ProfileSyntax#V2_STRICT}）：
 *       {@code fieldMappings.envelope} + {@code fieldMappings.payload.<eventType>}，
 *       {@code enumSemantics} 键为 canonical 字段名。**严格模式**：contractVersion 必填、
 *       顶层/子层未知键即 {@code PROFILE_INVALID}、本地时间与 epoch 必须显式声明 IANA 时区、
 *       每个需要金额转换的源字段必须在 {@code amountPolicy.bySourceField} 里声明单位。</li>
 *   <li>v1（现存 {@code source-profiles/*.v1.json}，{@link MappingProfile.ProfileSyntax#V1_COMPATIBILITY}）：
 *       {@code eventTypeMapping} + {@code fieldMapping} 扁平一张表，{@code enumSemantics.<组名>}。
 *       v1 的扁平表按规则 2 当作**单一分组**做「同组两来源写同一目标」检查（fixture-b 因此在装载期即为
 *       PROFILE_INVALID），再按各事件类型的契约字段切出分组；组名到 canonical 字段的绑定用
 *       「非空映射值集合 ⊆ 契约枚举集」的**唯一候选**规则，绑不上/多候选只记为能力缺口，绝不猜。
 *       只读兼容：既有 v1 文件一字不改，允许 {@code canonical.schemaVersion} 回退契约版本、
 *       允许载荷容器缺失时回落根；{@code identityPolicy}/{@code quarantinePolicy} 等未实现能力
 *       记入 {@code capabilityGaps}，不假装生效。</li>
 * </ul>
 */
public final class MappingProfileLoader {

    /** 规则 3 的保留指令。 */
    public static final String KEEP = "@keep";

    private static final String EVENT_TIME = "event_time";
    private static final String EVENT_TYPE = "event_type";

    /**
     * v2 严格模式认识的全部顶层键；其它键（含 v1 遗留键与 {@code checksum}）一律 PROFILE_INVALID，
     * 不静默忽略。
     *
     * <p><b>为什么 v2 不接受 {@code checksum}</b>：画像哈希的唯一权威值是 Loader 对**原始画像字节**
     * 计算的 {@link MappingProfile#profileChecksum()}；若允许画像自声明 checksum，就必须定义它覆盖哪些
     * 字节（自引用循环）。S2-01A 收口裁决：v2 出现 {@code checksum} 即非法。</p>
     */
    private static final Set<String> V2_TOP_LEVEL_KEYS = Set.of(
            "profileVersion", "sourceCode", "contractVersion", "eventTypeMappings", "fieldMappings",
            "enumSemantics", "timePolicy", "amountPolicy");

    /** v1 兼容模式认识的顶层键；未列出的键进 capabilityGaps（登记不生效，不静默吞掉）。 */
    private static final Set<String> V1_TOP_LEVEL_KEYS = Set.of(
            "profileVersion", "sourceCode", "contractVersion", "canonical", "eventTypeMapping", "fieldMapping",
            "enumSemantics", "timePolicy", "amountPolicy", "identityPolicy", "quarantinePolicy", "checksum");

    /** v1 里存在但 S2-01A/A.1 不实现的策略键：只登记 capabilityGaps，绝不假装生效。 */
    private static final Map<String, String> V1_LEGACY_POLICY_KEYS = Map.of(
            "identityPolicy", "identityPolicy:未执行（身份策略归后续身份体系，Mapper 不生成代理键）",
            "quarantinePolicy", "quarantinePolicy:未执行（隔离持久化策略归 S2-02）",
            "checksum", "checksum:仅兼容元数据（不参与权威画像哈希比较；权威值 = Loader 对原始画像字节的 sha256）");

    private static final Set<String> TIME_POLICY_KEYS = Set.of("field", "formats", "zone");
    private static final Set<String> AMOUNT_POLICY_KEYS = Set.of("bySourceField");

    private final CanonicalContract contract;
    private final ObjectMapper mapper = MappingJson.mapper();

    public MappingProfileLoader(CanonicalContract contract) {
        this.contract = Objects.requireNonNull(contract, "contract");
    }

    public MappingProfileLoad load(String profileJson, String sourceRef) {
        List<MappingIssue> issues = new ArrayList<>();
        if (profileJson == null) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "profile", "EMPTY_TEXT:" + sourceRef));
            return new MappingProfileLoad(null, issues);
        }
        String profileChecksum = MappingHash.sha256Hex(profileJson);

        JsonNode root;
        try {
            root = mapper.readTree(profileJson);
        } catch (IOException e) {
            return new MappingProfileLoad(null, List.of(issue(MappingReason.JSON_PARSE_ERROR, "profile",
                    "PARSE_FAILED:" + e.getMessage())));
        }
        if (root == null || !root.isObject()) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "profile", "NOT_AN_OBJECT"));
            return new MappingProfileLoad(null, issues);
        }

        // 形状判定只看结构：fieldMappings 是对象 ⇒ v2 严格模式；否则 v1 扁平兼容模式
        boolean v2Shape = root.path("fieldMappings").isObject();
        MappingProfile.ProfileSyntax syntax = v2Shape
                ? MappingProfile.ProfileSyntax.V2_STRICT
                : MappingProfile.ProfileSyntax.V1_COMPATIBILITY;
        List<String> capabilityGaps = new ArrayList<>();
        List<String> activationBlocks = new ArrayList<>();
        checkTopLevelKeys(root, syntax, capabilityGaps, issues);

        // 规则 1：契约先行。版本必须来自明文声明；v2 必须声明 contractVersion，v1 允许回退 canonical.schemaVersion。
        String profileVersion = textOrNull(root.path("profileVersion"));
        if (isBlank(profileVersion)) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "profileVersion", "MISSING_KEY:profileVersion"));
        }
        String declaredContract = textOrNull(root.path("contractVersion"));
        String contractVersion;
        if (v2Shape) {
            contractVersion = isBlank(declaredContract) ? null : declaredContract;
            if (contractVersion == null) {
                issues.add(issue(MappingReason.PROFILE_INVALID, "contractVersion", "MISSING_KEY:contractVersion"));
            }
        } else {
            String canonicalSchema = textOrNull(root.path("canonical").path("schemaVersion"));
            if (declaredContract != null && canonicalSchema != null && !declaredContract.equals(canonicalSchema)) {
                issues.add(issue(MappingReason.PROFILE_INVALID, "contractVersion",
                        "CONFLICT:contractVersion=" + declaredContract + " canonical.schemaVersion=" + canonicalSchema));
            }
            contractVersion = declaredContract != null ? declaredContract : canonicalSchema;
            if (contractVersion == null) {
                issues.add(issue(MappingReason.PROFILE_INVALID, "contractVersion", "MISSING_KEY:contractVersion"));
            }
        }
        if (contractVersion != null && !contractVersion.equals(contract.contractVersion())) {
            issues.add(issue(MappingReason.PROFILE_VERSION, "contractVersion",
                    "SUPPORTED=" + contract.contractVersion() + " DECLARED=" + contractVersion));
        }

        // 规则 2：事件类型映射
        Map<String, String> typeMappings = new LinkedHashMap<>();
        String declaredTypeSource = null;
        JsonNode v2Types = root.path("eventTypeMappings");
        if (v2Types.isObject()) {
            declaredTypeSource = textOrNull(v2Types.path("sourceField"));
            if (isBlank(declaredTypeSource)) {
                issues.add(issue(MappingReason.PROFILE_INVALID, "eventTypeMappings.sourceField",
                        "MISSING_KEY:eventTypeMappings.sourceField"));
            }
            JsonNode values = v2Types.path("values");
            if (!values.isObject() || values.isEmpty()) {
                issues.add(issue(MappingReason.PROFILE_INVALID, "eventTypeMappings.values",
                        "MISSING_KEY:eventTypeMappings.values"));
            } else {
                readTypeMappings(values, typeMappings, issues);
            }
        } else if (root.path("eventTypeMapping").isObject() && !root.path("eventTypeMapping").isEmpty()) {
            readTypeMappings(root.path("eventTypeMapping"), typeMappings, issues);
        } else {
            issues.add(issue(MappingReason.PROFILE_INVALID, "eventTypeMappings", "MISSING_KEY:eventTypeMappings"));
        }

        // 规则 2/3/9：字段映射
        Map<String, String> envelope = new LinkedHashMap<>();
        Map<String, Map<String, String>> payloadScalars = new LinkedHashMap<>();
        Map<String, Map<String, ItemMapSpec>> itemMaps = new LinkedHashMap<>();
        JsonNode fieldMappings = root.path("fieldMappings");
        if (v2Shape) {
            readV2FieldMappings(fieldMappings, envelope, payloadScalars, itemMaps, issues);
            for (Map.Entry<String, String> entry : typeMappings.entrySet()) {
                String type = entry.getValue();
                if (type != null && !payloadScalars.containsKey(type)) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, "payload." + type,
                            "MISSING_PAYLOAD_GROUP:" + type));
                }
            }
        } else if (root.path("fieldMapping").isObject() && !root.path("fieldMapping").isEmpty()) {
            readV1FlatFieldMapping(root.path("fieldMapping"), typeMappings, envelope, payloadScalars, issues);
        } else {
            issues.add(issue(MappingReason.PROFILE_INVALID, "fieldMappings", "MISSING_KEY:fieldMappings"));
        }

        // 规则 3：@keep 叶子名不得撞 canonical 字段，也不得两个扩展同名
        checkKeepCollisions(payloadScalars, issues);

        // 信封来源模式：显式声明过信封映射 ⇒ 只认声明；否则按 canonical 名身份直取（v1 现状）
        MappingProfile.EnvelopeSourceMode envelopeMode = envelope.isEmpty()
                ? MappingProfile.EnvelopeSourceMode.CANONICAL_NAME_IDENTITY
                : MappingProfile.EnvelopeSourceMode.DECLARED_ONLY;

        String eventTypeSourceField = resolveEventTypeSourceField(declaredTypeSource, root, envelope);
        Set<String> declaredTypes = new LinkedHashSet<>();
        typeMappings.values().stream().filter(Objects::nonNull).forEach(declaredTypes::add);

        // 规则 12：枚举语义
        Map<String, Map<String, String>> enumSemantics = new LinkedHashMap<>();
        Map<String, String> enumBindings = new LinkedHashMap<>();
        List<String> enumUnbound = new ArrayList<>();
        List<String> enumAmbiguous = new ArrayList<>();
        JsonNode enumNode = root.path("enumSemantics");
        if (enumNode.isObject() && !enumNode.isEmpty()) {
            if (v2Shape) {
                readFieldKeyedEnumSemantics(enumNode, enumSemantics, issues);
            } else {
                readGroupKeyedEnumSemantics(enumNode, declaredTypes, enumSemantics, enumBindings, enumUnbound,
                        enumAmbiguous, issues);
            }
        } else if (!enumNode.isMissingNode() && !enumNode.isObject()) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "enumSemantics", "NOT_AN_OBJECT"));
        }

        // 规则 4：时间策略（本地时间/epoch 必须由画像显式声明 IANA 时区，无隐式默认）
        TimePolicySpec timePolicy = readTimePolicy(root, envelope, envelopeMode, syntax, capabilityGaps, issues);

        // 规则 7：金额单位。v2 缺声明即 PROFILE_INVALID；v1 兼容模式记能力缺口 + 禁止激活（运行期仍 fail-closed）
        AmountPolicySpec amountPolicy = readAmountPolicy(root, syntax, capabilityGaps, issues);

        collectEnumActivationBlocks(enumSemantics, declaredTypes, activationBlocks);
        collectUnmappedEnvelopeBlocks(envelope, envelopeMode, activationBlocks);
        collectPayloadGaps(payloadScalars, itemMaps, declaredTypes, capabilityGaps, activationBlocks);
        checkAmountUnits(payloadScalars, itemMaps, declaredTypes, amountPolicy, syntax,
                capabilityGaps, activationBlocks, issues);
        if (!enumUnbound.isEmpty()) {
            enumUnbound.forEach(group -> capabilityGaps.add("enumGroupUnbound:" + group));
        }
        if (!enumAmbiguous.isEmpty()) {
            enumAmbiguous.forEach(group -> capabilityGaps.add("enumGroupAmbiguous:" + group));
        }

        if (!issues.isEmpty()) {
            return new MappingProfileLoad(null, issues);
        }

        MappingProfile profile = new MappingProfile(
                profileVersion,
                textOrNull(root.path("sourceCode")),
                contractVersion,
                syntax,
                profileChecksum,
                eventTypeSourceField,
                typeMappings,
                envelopeMode,
                envelope,
                payloadScalars,
                itemMaps,
                enumSemantics,
                enumBindings,
                enumUnbound,
                enumAmbiguous,
                timePolicy,
                amountPolicy,
                activationBlocks,
                capabilityGaps);
        return new MappingProfileLoad(profile, List.of());
    }

    // ---------------------------------------------------------------- 顶层键纪律

    /**
     * v2 严格模式：任何未知顶层键（含 v1 遗留的 {@code canonical}/{@code identityPolicy}/
     * {@code quarantinePolicy}/{@code eventTypeMapping}/{@code fieldMapping}/{@code requiredPolicy}/{@code checksum}）
     * 一律 {@code PROFILE_INVALID}，不静默忽略。
     *
     * <p>v1 兼容模式：不认识但不影响正确性的键记入 {@code capabilityGaps}（登记而非吞掉）；
     * 未实现的策略键按 {@link #V1_LEGACY_POLICY_KEYS} 给出逐字缺口说明。</p>
     */
    private void checkTopLevelKeys(JsonNode root,
                                   MappingProfile.ProfileSyntax syntax,
                                   List<String> capabilityGaps,
                                   List<MappingIssue> issues) {
        List<String> keys = new ArrayList<>();
        root.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            if (syntax == MappingProfile.ProfileSyntax.V2_STRICT) {
                if (!V2_TOP_LEVEL_KEYS.contains(key)) {
                    String detail = V1_TOP_LEVEL_KEYS.contains(key)
                            ? "LEGACY_KEY_NOT_ALLOWED_IN_V2:" + key
                            : "UNKNOWN_TOP_LEVEL_KEY:" + key;
                    issues.add(issue(MappingReason.PROFILE_INVALID, key, detail));
                }
            } else if (!V1_TOP_LEVEL_KEYS.contains(key)) {
                capabilityGaps.add("unknownTopLevelKey:" + key);
            } else if (V1_LEGACY_POLICY_KEYS.containsKey(key)) {
                capabilityGaps.add(V1_LEGACY_POLICY_KEYS.get(key));
            }
        }
    }

    // ---------------------------------------------------------------- 事件类型映射

    private void readTypeMappings(JsonNode values, Map<String, String> typeMappings, List<MappingIssue> issues) {
        values.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            String path = "eventTypeMappings." + entry.getKey();
            if (value.isNull()) {
                typeMappings.put(entry.getKey(), null);
                return;
            }
            String mapped = textOrNull(value);
            if (mapped == null) {
                issues.add(issue(MappingReason.PROFILE_INVALID, path, "TYPE_VALUE_NOT_TEXT_OR_NULL"));
                return;
            }
            if (!contract.isKnownEventType(mapped)) {
                issues.add(issue(MappingReason.PROFILE_INVALID, path, "NOT_IN_CONTRACT_EVENT_TYPE:" + mapped));
                return;
            }
            typeMappings.put(entry.getKey(), mapped);
        });
    }

    private String resolveEventTypeSourceField(String declaredTypeSource, JsonNode root, Map<String, String> envelope) {
        if (declaredTypeSource != null) {
            return declaredTypeSource;
        }
        String found = envelopeSourceForValue(envelope, EVENT_TYPE);
        if (found != null) {
            return found;
        }
        JsonNode flat = root.path("fieldMapping");
        if (flat.isObject()) {
            for (Map.Entry<String, JsonNode> entry : iterable(flat)) {
                if (EVENT_TYPE.equals(textOrNull(entry.getValue()))) {
                    return entry.getKey();
                }
            }
        }
        for (Map.Entry<String, JsonNode> entry : iterable(root.path("fieldMappings").path("envelope"))) {
            if (EVENT_TYPE.equals(textOrNull(entry.getValue()))) {
                return entry.getKey();
            }
        }
        // 身份模式：源字段名就是 canonical 名
        return EVENT_TYPE;
    }

    // ---------------------------------------------------------------- 字段映射

    private void readV2FieldMappings(JsonNode fieldMappings,
                                     Map<String, String> envelope,
                                     Map<String, Map<String, String>> payloadScalars,
                                     Map<String, Map<String, ItemMapSpec>> itemMaps,
                                     List<MappingIssue> issues) {
        JsonNode env = fieldMappings.path("envelope");
        if (env.isObject()) {
            Map<String, String> owner = new LinkedHashMap<>();
            env.fields().forEachRemaining(entry -> {
                String source = entry.getKey();
                String target = textOrNull(entry.getValue());
                String path = "envelope." + source;
                if (target == null) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, path, "TARGET_NOT_TEXT"));
                    return;
                }
                if (contract.envelopeField(target) == null) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, path, "NOT_IN_CONTRACT_ENVELOPE:" + target));
                    return;
                }
                String previous = owner.put(target, source);
                if (previous != null) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, "envelope." + target,
                            "DUPLICATE_TARGET:" + previous + "," + source));
                }
                envelope.put(source, target);
            });
        }

        JsonNode payload = fieldMappings.path("payload");
        if (!payload.isObject() || payload.isEmpty()) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "fieldMappings.payload",
                    "MISSING_KEY:fieldMappings.payload"));
            return;
        }
        payload.fields().forEachRemaining(group -> {
            String eventType = group.getKey();
            if (!contract.isKnownEventType(eventType)) {
                issues.add(issue(MappingReason.PROFILE_INVALID, "payload." + eventType,
                        "NOT_IN_CONTRACT_EVENT_TYPE:" + eventType));
                return;
            }
            Map<String, String> scalars = new LinkedHashMap<>();
            Map<String, ItemMapSpec> maps = new LinkedHashMap<>();
            readPayloadGroup(eventType, group.getValue(), scalars, maps, issues);
            payloadScalars.put(eventType, scalars);
            if (!maps.isEmpty()) {
                itemMaps.put(eventType, maps);
            }
        });
    }

    private void readPayloadGroup(String eventType, JsonNode group,
                                  Map<String, String> scalars,
                                  Map<String, ItemMapSpec> itemMaps,
                                  List<MappingIssue> issues) {
        if (!group.isObject()) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "payload." + eventType, "GROUP_NOT_OBJECT"));
            return;
        }
        Map<String, String> owner = new LinkedHashMap<>();
        group.fields().forEachRemaining(entry -> {
            String source = entry.getKey();
            JsonNode value = entry.getValue();
            String path = "payload." + eventType + "." + source;
            if (value.isTextual()) {
                String target = value.asText();
                if (KEEP.equals(target)) {
                    scalars.put(source, KEEP);
                    return;
                }
                if (contract.payloadField(eventType, target) == null) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, path,
                            "NOT_IN_CONTRACT_PAYLOAD_FIELD:" + eventType + "." + target));
                    return;
                }
                String previous = owner.put(target, source);
                if (previous != null) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, "payload." + eventType + "." + target,
                            "DUPLICATE_TARGET:" + previous + "," + source));
                }
                scalars.put(source, target);
            } else if (value.isObject()) {
                readItemMap(eventType, source, value, itemMaps, issues);
            } else {
                issues.add(issue(MappingReason.PROFILE_INVALID, path, "MAPPING_VALUE_NOT_TEXT_OR_ITEM_MAP"));
            }
        });
    }

    private void readItemMap(String eventType, String source, JsonNode value,
                             Map<String, ItemMapSpec> itemMaps, List<MappingIssue> issues) {
        String path = "payload." + eventType + "." + source;
        String targetPath = textOrNull(value.path("target"));
        if (targetPath == null) {
            issues.add(issue(MappingReason.PROFILE_INVALID, path, "ITEM_MAP_TARGET_MISSING"));
            return;
        }
        String arrayField = ItemMapSpec.arrayFieldOf(targetPath);
        if (arrayField == null) {
            issues.add(issue(MappingReason.PROFILE_INVALID, path, "ITEM_MAP_TARGET_NOT_ARRAY:" + targetPath));
            return;
        }
        CanonicalContract.FieldSpec arraySpec = contract.payloadField(eventType, arrayField);
        if (arraySpec == null || arraySpec.kind() != CanonicalContract.Kind.ITEMS) {
            issues.add(issue(MappingReason.PROFILE_INVALID, path,
                    "ITEM_MAP_ARRAY_FIELD_NOT_IN_CONTRACT:" + arrayField));
            return;
        }
        JsonNode itemFields = value.path("itemFields");
        if (!itemFields.isObject() || itemFields.isEmpty()) {
            issues.add(issue(MappingReason.PROFILE_INVALID, path, "ITEM_MAP_FIELDS_MISSING"));
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> owner = new LinkedHashMap<>();
        itemFields.fields().forEachRemaining(entry -> {
            String target = textOrNull(entry.getValue());
            String fieldPath = path + "." + entry.getKey();
            if (target == null || contract.itemFields().get(target) == null) {
                issues.add(issue(MappingReason.PROFILE_INVALID, fieldPath,
                        "ITEM_FIELD_NOT_IN_CONTRACT:" + entry.getValue()));
                return;
            }
            String previous = owner.put(target, entry.getKey());
            if (previous != null) {
                issues.add(issue(MappingReason.PROFILE_INVALID, path + "." + target,
                        "DUPLICATE_ITEM_TARGET:" + previous + "," + entry.getKey()));
            }
            fields.put(entry.getKey(), target);
        });
        if (!fields.isEmpty()) {
            itemMaps.put(source, new ItemMapSpec(targetPath, arrayField, fields));
        }
    }

    private void readV1FlatFieldMapping(JsonNode flat,
                                        Map<String, String> typeMappings,
                                        Map<String, String> envelope,
                                        Map<String, Map<String, String>> payloadScalars,
                                        List<MappingIssue> issues) {
        Map<String, String> flatScalars = new LinkedHashMap<>();
        Map<String, String> keeps = new LinkedHashMap<>();
        Map<String, String> keepLeaves = new LinkedHashMap<>();
        Map<String, String> owner = new LinkedHashMap<>();

        flat.fields().forEachRemaining(entry -> {
            String source = entry.getKey();
            String target = textOrNull(entry.getValue());
            String path = "fieldMapping." + source;
            if (target == null) {
                issues.add(issue(MappingReason.PROFILE_INVALID, path, "TARGET_NOT_TEXT"));
                return;
            }
            if (KEEP.equals(target)) {
                String leaf = MappingPath.leafName(source);
                String previous = keepLeaves.put(leaf, source);
                if (previous != null) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, path,
                            "DUPLICATE_KEEP_LEAF:" + leaf + ":" + previous + "," + source));
                }
                keeps.put(source, KEEP);
                return;
            }
            if (contract.envelopeField(target) != null) {
                envelope.put(source, target);
                return;
            }
            if (!contract.isKnownPayloadFieldName(target)) {
                issues.add(issue(MappingReason.PROFILE_INVALID, path, "NOT_IN_CONTRACT:" + target));
                return;
            }
            String previous = owner.put(target, source);
            if (previous != null) {
                issues.add(issue(MappingReason.PROFILE_INVALID, "fieldMapping." + target,
                        "DUPLICATE_TARGET:" + previous + "," + source));
            }
            flatScalars.put(source, target);
        });

        for (String type : contract.eventTypes()) {
            if (!typeMappings.containsValue(type)) {
                continue;
            }
            Map<String, String> scalars = new LinkedHashMap<>();
            flatScalars.forEach((source, target) -> {
                if (contract.payloadField(type, target) != null) {
                    scalars.put(source, target);
                }
            });
            keeps.forEach(scalars::put);
            payloadScalars.put(type, scalars);
        }
    }

    private void checkKeepCollisions(Map<String, Map<String, String>> payloadScalars, List<MappingIssue> issues) {
        payloadScalars.forEach((eventType, scalars) -> {
            Map<String, String> leaves = new LinkedHashMap<>();
            scalars.forEach((source, target) -> {
                if (!KEEP.equals(target)) {
                    return;
                }
                String leaf = MappingPath.leafName(source);
                String path = "payload." + eventType + "." + leaf;
                if (contract.payloadField(eventType, leaf) != null || contract.envelopeField(leaf) != null) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, path, "KEEP_NAME_COLLIDES_CANONICAL_FIELD:" + leaf));
                }
                String previous = leaves.put(leaf, source);
                if (previous != null) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, path,
                            "DUPLICATE_KEEP_LEAF:" + leaf + ":" + previous + "," + source));
                }
            });
        });
    }

    // ---------------------------------------------------------------- 枚举语义

    private void readFieldKeyedEnumSemantics(JsonNode enumNode,
                                             Map<String, Map<String, String>> enumSemantics,
                                             List<MappingIssue> issues) {
        enumNode.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            JsonNode mapping = entry.getValue();
            String path = "enumSemantics." + field;
            if (!mapping.isObject()) {
                issues.add(issue(MappingReason.PROFILE_INVALID, path, "ENUM_GROUP_NOT_OBJECT"));
                return;
            }
            if (!contract.isKnownPayloadFieldName(field)) {
                issues.add(issue(MappingReason.PROFILE_INVALID, path, "UNKNOWN_ENUM_FIELD:" + field));
                return;
            }
            List<String> types = contract.eventTypesWithPayloadField(field);
            Map<String, String> values = new LinkedHashMap<>();
            mapping.fields().forEachRemaining(value -> {
                String mapped = value.getValue().isNull() ? null : textOrNull(value.getValue());
                if (!value.getValue().isNull() && mapped == null) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, path + "." + value.getKey(), "ENUM_VALUE_NOT_TEXT_OR_NULL"));
                    return;
                }
                if (mapped != null) {
                    boolean inContract = types.stream().anyMatch(type -> {
                        CanonicalContract.FieldSpec spec = contract.payloadField(type, field);
                        return spec != null && spec.hasEnumValues() && spec.enumValues().contains(mapped);
                    });
                    if (!inContract) {
                        issues.add(issue(MappingReason.PROFILE_INVALID, path + "." + value.getKey(),
                                "VALUE_NOT_IN_CONTRACT_ENUM:" + mapped));
                        return;
                    }
                }
                values.put(value.getKey(), mapped);
            });
            enumSemantics.put(field, values);
        });
    }

    private void readGroupKeyedEnumSemantics(JsonNode enumNode,
                                             Set<String> declaredTypes,
                                             Map<String, Map<String, String>> enumSemantics,
                                             Map<String, String> enumBindings,
                                             List<String> enumUnbound,
                                             List<String> enumAmbiguous,
                                             List<MappingIssue> issues) {
        enumNode.fields().forEachRemaining(entry -> {
            String group = entry.getKey();
            JsonNode mapping = entry.getValue();
            String path = "enumSemantics." + group;
            if (!mapping.isObject()) {
                issues.add(issue(MappingReason.PROFILE_INVALID, path, "ENUM_GROUP_NOT_OBJECT"));
                return;
            }
            Map<String, String> values = new LinkedHashMap<>();
            Set<String> mappedValues = new LinkedHashSet<>();
            mapping.fields().forEachRemaining(value -> {
                String mapped = value.getValue().isNull() ? null : textOrNull(value.getValue());
                if (!value.getValue().isNull() && mapped == null) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, path + "." + value.getKey(), "ENUM_VALUE_NOT_TEXT_OR_NULL"));
                    return;
                }
                values.put(value.getKey(), mapped);
                if (mapped != null) {
                    mappedValues.add(mapped);
                }
            });

            Set<String> candidates = new LinkedHashSet<>();
            if (!mappedValues.isEmpty()) {
                for (String type : declaredTypes) {
                    contract.payloadFields(type).forEach((field, spec) -> {
                        if (spec.hasEnumValues() && spec.enumValues().containsAll(mappedValues)) {
                            candidates.add(field);
                        }
                    });
                }
            }
            if (candidates.isEmpty()) {
                enumUnbound.add(group);
                return;
            }
            if (candidates.size() > 1) {
                enumAmbiguous.add(group + "->" + String.join("/", candidates));
                return;
            }
            String field = candidates.iterator().next();
            enumBindings.put(group, field);
            Map<String, String> target = enumSemantics.computeIfAbsent(field, key -> new LinkedHashMap<>());
            values.forEach((raw, mapped) -> {
                String previous = target.put(raw, mapped);
                if (previous != null && !previous.equals(mapped)) {
                    enumAmbiguous.add(group + "->" + field + ":" + raw);
                }
            });
        });
    }

    private void collectEnumActivationBlocks(Map<String, Map<String, String>> enumSemantics,
                                             Set<String> declaredTypes,
                                             List<String> activationBlocks) {
        enumSemantics.forEach((field, values) -> {
            if (!values.containsValue(null)) {
                return;
            }
            boolean requiredEnumField = declaredTypes.stream().anyMatch(type -> {
                CanonicalContract.FieldSpec spec = contract.payloadField(type, field);
                return spec != null && spec.required() && spec.hasEnumValues();
            });
            if (requiredEnumField) {
                activationBlocks.add("enum:" + field);
            }
        });
    }

    // ---------------------------------------------------------------- 策略

    private TimePolicySpec readTimePolicy(JsonNode root,
                                          Map<String, String> envelope,
                                          MappingProfile.EnvelopeSourceMode envelopeMode,
                                          MappingProfile.ProfileSyntax syntax,
                                          List<String> capabilityGaps,
                                          List<MappingIssue> issues) {
        JsonNode node = root.path("timePolicy");
        if (!node.isObject()) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "timePolicy", "MISSING_KEY:timePolicy"));
            return null;
        }
        checkSubKeys(node, "timePolicy", TIME_POLICY_KEYS, syntax, capabilityGaps, issues);
        String field = textOrNull(node.path("field"));
        if (isBlank(field)) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "timePolicy.field", "MISSING_KEY:timePolicy.field"));
        }
        List<TimePolicySpec.Format> formats = new ArrayList<>();
        JsonNode formatsNode = node.path("formats");
        if (!formatsNode.isArray() || formatsNode.isEmpty()) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "timePolicy.formats", "MISSING_KEY:timePolicy.formats"));
        } else {
            for (JsonNode format : formatsNode) {
                String token = textOrNull(format);
                if (token == null) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, "timePolicy.formats", "FORMAT_NOT_TEXT"));
                    continue;
                }
                TimePolicySpec.Format parsed = parseFormat(token, issues);
                if (parsed != null) {
                    formats.add(parsed);
                }
            }
        }
        // 时区：只有自带偏移的格式不需要时区；本地时间与 epoch 一律要求画像明文声明（无隐式默认）
        boolean needsZone = formats.stream().anyMatch(f -> f.kind() != TimePolicySpec.Format.Kind.OFFSET);
        String zone = null;
        String zoneSource = null;
        String declaredZone = textOrNull(node.path("zone"));
        if (!isBlank(declaredZone)) {
            try {
                ZoneId.of(declaredZone);
                zone = declaredZone;
                zoneSource = TimePolicySpec.ZONE_FROM_PROFILE;
            } catch (DateTimeException e) {
                issues.add(issue(MappingReason.PROFILE_INVALID, "timePolicy.zone", "UNKNOWN_ZONE:" + declaredZone));
            }
        } else if (needsZone) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "timePolicy.zone", "MISSING_KEY:timePolicy.zone"));
        }
        if (field != null) {
            String mappedTimeSource = envelopeSourceForValue(envelope, EVENT_TIME);
            boolean reachable = envelopeMode == MappingProfile.EnvelopeSourceMode.DECLARED_ONLY
                    ? field.equals(mappedTimeSource)
                    : EVENT_TIME.equals(field);
            if (!reachable) {
                issues.add(issue(MappingReason.PROFILE_INVALID, "timePolicy.field",
                        "TIME_FIELD_NOT_MAPPED:" + field + " mappedTo=" + mappedTimeSource));
            }
        }
        if (field == null || formats.isEmpty()) {
            return null;
        }
        return new TimePolicySpec(field, formats, zone, zoneSource);
    }

    private TimePolicySpec.Format parseFormat(String token, List<MappingIssue> issues) {
        switch (token) {
            case TimePolicySpec.ISO_OFFSET_DATE_TIME:
                return new TimePolicySpec.Format(token, TimePolicySpec.Format.Kind.OFFSET,
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            case TimePolicySpec.ISO_LOCAL_DATE_TIME:
                return new TimePolicySpec.Format(token, TimePolicySpec.Format.Kind.LOCAL,
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            case TimePolicySpec.EPOCH_MILLIS:
                return new TimePolicySpec.Format(token, TimePolicySpec.Format.Kind.EPOCH_MILLIS, null);
            case TimePolicySpec.EPOCH_SECONDS:
                return new TimePolicySpec.Format(token, TimePolicySpec.Format.Kind.EPOCH_SECONDS, null);
            default:
                try {
                    return new TimePolicySpec.Format(token, TimePolicySpec.Format.Kind.LOCAL,
                            DateTimeFormatter.ofPattern(token));
                } catch (IllegalArgumentException e) {
                    issues.add(issue(MappingReason.PROFILE_INVALID, "timePolicy.formats",
                            "UNKNOWN_TIME_FORMAT:" + token));
                    return null;
                }
        }
    }

    /**
     * 规则 7 的机器形状：{@code {"bySourceField": {"<源字段路径/名>": "FEN"|"YUAN"}}}。
     * 没有画像级默认单位、没有 defaultUnit；缺声明由 {@link #checkAmountUnits} 按语法模式判定。
     */
    private AmountPolicySpec readAmountPolicy(JsonNode root,
                                              MappingProfile.ProfileSyntax syntax,
                                              List<String> capabilityGaps,
                                              List<MappingIssue> issues) {
        JsonNode node = root.path("amountPolicy");
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "amountPolicy", "NOT_AN_OBJECT"));
            return null;
        }
        checkSubKeys(node, "amountPolicy", AMOUNT_POLICY_KEYS, syntax, capabilityGaps, issues);
        Map<String, AmountPolicySpec.Unit> bySourceField = new LinkedHashMap<>();
        JsonNode byField = node.path("bySourceField");
        if (byField.isMissingNode() || byField.isNull()) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "amountPolicy.bySourceField",
                    "MISSING_KEY:amountPolicy.bySourceField"));
            return null;
        }
        if (!byField.isObject()) {
            issues.add(issue(MappingReason.PROFILE_INVALID, "amountPolicy.bySourceField", "NOT_AN_OBJECT"));
            return null;
        }
        byField.fields().forEachRemaining(entry -> {
            AmountPolicySpec.Unit unit = parseUnit(textOrNull(entry.getValue()),
                    "amountPolicy.bySourceField." + entry.getKey(), issues);
            if (unit != null) {
                bySourceField.put(entry.getKey(), unit);
            }
        });
        return new AmountPolicySpec(bySourceField);
    }

    /** 子层键纪律：v2 未知子键 ⇒ PROFILE_INVALID；v1 ⇒ capabilityGaps（登记不生效）。 */
    private void checkSubKeys(JsonNode node,
                              String path,
                              Set<String> allowed,
                              MappingProfile.ProfileSyntax syntax,
                              List<String> capabilityGaps,
                              List<MappingIssue> issues) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            if (allowed.contains(key)) {
                continue;
            }
            if (syntax == MappingProfile.ProfileSyntax.V2_STRICT) {
                issues.add(issue(MappingReason.PROFILE_INVALID, path + "." + key, "UNKNOWN_KEY:" + path + "." + key));
            } else {
                capabilityGaps.add("unknownKey:" + path + "." + key);
            }
        }
    }

    private AmountPolicySpec.Unit parseUnit(String text, String path, List<MappingIssue> issues) {
        if (text == null) {
            issues.add(issue(MappingReason.PROFILE_INVALID, path, "UNIT_NOT_TEXT"));
            return null;
        }
        try {
            return AmountPolicySpec.Unit.valueOf(text);
        } catch (IllegalArgumentException e) {
            issues.add(issue(MappingReason.PROFILE_INVALID, path, "UNKNOWN_AMOUNT_UNIT:" + text));
            return null;
        }
    }

    // ---------------------------------------------------------------- 能力缺口与激活门

    private void collectUnmappedEnvelopeBlocks(Map<String, String> envelope,
                                               MappingProfile.EnvelopeSourceMode envelopeMode,
                                               List<String> activationBlocks) {
        if (envelopeMode != MappingProfile.EnvelopeSourceMode.DECLARED_ONLY) {
            return;
        }
        for (String target : contract.requiredEnvelopeFields()) {
            if (target.equals(contract.platformIngestField())) {
                continue;
            }
            if (!envelope.containsValue(target)) {
                activationBlocks.add("unmappedEnvelope:" + target);
            }
        }
    }

    private void collectPayloadGaps(Map<String, Map<String, String>> payloadScalars,
                                    Map<String, Map<String, ItemMapSpec>> itemMaps,
                                    Set<String> declaredTypes,
                                    List<String> capabilityGaps,
                                    List<String> activationBlocks) {
        for (String type : declaredTypes) {
            Set<String> targets = new LinkedHashSet<>(payloadScalars.getOrDefault(type, Map.of()).values());
            itemMaps.getOrDefault(type, Map.of()).values().forEach(spec -> targets.add(spec.arrayField()));
            for (String field : contract.requiredPayloadFields(type)) {
                if (!targets.contains(field)) {
                    capabilityGaps.add("unmapped:" + type + "." + field);
                    activationBlocks.add("unmapped:" + type + "." + field);
                }
            }
        }
    }

    /**
     * 规则 7 的单位完备性：**每个需要金额转换的源字段都必须显式声明单位**。
     * 键是源字段路径/名（含 ITEM_MAP 的逐项源字段）。
     *
     * <p>v2 严格模式：缺一个即 {@code PROFILE_INVALID/MISSING_AMOUNT_POLICY}（路径指向该源字段）。
     * v1 兼容模式：既有文件里没有 amountPolicy，记能力缺口 + 禁止激活；运行期执行器仍 fail-closed，
     * 因此这些源不会被静默按某种单位转换。</p>
     */
    private void checkAmountUnits(Map<String, Map<String, String>> payloadScalars,
                                  Map<String, Map<String, ItemMapSpec>> itemMaps,
                                  Set<String> declaredTypes,
                                  AmountPolicySpec amountPolicy,
                                  MappingProfile.ProfileSyntax syntax,
                                  List<String> capabilityGaps,
                                  List<String> activationBlocks,
                                  List<MappingIssue> issues) {
        Set<String> missing = new LinkedHashSet<>();
        for (String type : declaredTypes) {
            for (String source : payloadScalars.getOrDefault(type, Map.of()).keySet()) {
                String target = payloadScalars.getOrDefault(type, Map.of()).get(source);
                CanonicalContract.FieldSpec spec = contract.payloadField(type, target);
                if (spec != null && spec.kind() == CanonicalContract.Kind.AMOUNT
                        && unitOf(amountPolicy, source) == null) {
                    missing.add(source);
                }
            }
            for (ItemMapSpec itemMap : itemMaps.getOrDefault(type, Map.of()).values()) {
                for (Map.Entry<String, String> entry : itemMap.itemFields().entrySet()) {
                    CanonicalContract.FieldSpec spec = contract.itemFields().get(entry.getValue());
                    if (spec != null && spec.kind() == CanonicalContract.Kind.AMOUNT
                            && unitOf(amountPolicy, entry.getKey()) == null) {
                        missing.add(entry.getKey());
                    }
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        if (syntax == MappingProfile.ProfileSyntax.V2_STRICT) {
            for (String source : missing) {
                issues.add(issue(MappingReason.PROFILE_INVALID, "amountPolicy.bySourceField." + source,
                        "MISSING_AMOUNT_POLICY"));
            }
        } else {
            capabilityGaps.add("amountPolicy:有金额目标但画像未声明单位");
            for (String source : missing) {
                capabilityGaps.add("amountPolicy.missingUnit:" + source);
            }
            activationBlocks.add("amountPolicy");
        }
    }

    private static AmountPolicySpec.Unit unitOf(AmountPolicySpec amountPolicy, String sourceField) {
        return amountPolicy == null ? null : amountPolicy.unitFor(sourceField);
    }

    // ---------------------------------------------------------------- 小工具

    private static String envelopeSourceForValue(Map<String, String> envelope, String target) {
        for (Map.Entry<String, String> entry : envelope.entrySet()) {
            if (entry.getValue().equals(target)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static Iterable<Map.Entry<String, JsonNode>> iterable(JsonNode node) {
        return node != null && node.isObject() ? node.properties() : List.<Map.Entry<String, JsonNode>>of();
    }

    private static MappingIssue issue(MappingReason reason, String path, String detail) {
        return MappingIssue.of(reason, path, detail);
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
