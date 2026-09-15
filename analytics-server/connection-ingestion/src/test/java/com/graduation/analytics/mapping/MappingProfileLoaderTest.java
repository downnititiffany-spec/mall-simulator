package com.graduation.analytics.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-01A/A.1 画像装载与静态校验（设计 §7.3 规则 1/2/4/7/12/14）。
 *
 * <p>口径：**不猜默认值**——版本、金额单位、时区、枚举绑定都必须来自画像明文声明；缺声明一律 fail-closed。
 * v2 是严格模式（顶层/子层未知键、缺 contractVersion、本地时间或 epoch 缺 IANA 时区、金额源字段缺单位
 * 都是 {@code PROFILE_INVALID}）；v1 是只读兼容模式（允许 {@code canonical.schemaVersion} 回退，
 * 未实现能力只记能力缺口），其用例见 {@link MappingV1CompatibilityTest}。</p>
 */
class MappingProfileLoaderTest {

    private static final String PAYLOAD_BEHAVIOR = """
            { "behavior": { "buyer": "user_id", "item": "product_id", "visit": "session_id",
                            "act": "behavior_type", "term": "channel" } }""";
    private static final String TIME_OFFSET = """
            { "field": "at", "formats": ["ISO_OFFSET_DATE_TIME"], "zone": "Asia/Shanghai" }""";
    /** 行为类画像没有金额目标 ⇒ 不需要 amountPolicy（v2 只在「有金额目标」时要求逐源字段声明单位）。 */
    private static final String AMOUNT_NONE = "";
    /** 冻结形状：按**源字段名**声明单位，没有画像级默认单位。 */
    private static final String AMOUNT_BY_FIELD = """
            , "amountPolicy": { "bySourceField": { "paid_fen": "FEN", "paid_yuan": "YUAN" } }""";

    /** v2 内部测试模型：设计 §7.2 的字段名（profileVersion/sourceCode/contractVersion/eventTypeMappings/fieldMappings/enumSemantics/timePolicy/amountPolicy）。 */
    private static String v2(String versionJson, String valuesJson, String payloadGroup,
                            String enumSemantics, String timePolicy, String amountJson) {
        return """
                {
                  "profileVersion": "2.0",
                  "sourceCode": "t",
                  %s
                  "eventTypeMappings": { "sourceField": "kind", "values": %s },
                  "fieldMappings": {
                    "envelope": { "id": "event_id", "kind": "event_type", "at": "event_time", "sys": "source_system",
                                  "rev": "schema_version", "tr": "trace_id", "data": "payload" },
                    "payload": %s
                  },
                  "enumSemantics": %s,
                  "timePolicy": %s
                  %s
                }
                """.formatted(versionLine(versionJson), valuesJson, payloadGroup, enumSemantics, timePolicy, amountJson);
    }

    private static String versionLine(String versionJson) {
        return versionJson == null ? "" : "\"contractVersion\": " + versionJson + ",";
    }

    private static String valid() {
        return v2("\"1.0\"", "{ \"browse\": \"behavior\" }", PAYLOAD_BEHAVIOR, "{}", TIME_OFFSET, AMOUNT_NONE);
    }

    private static MappingProfileLoad loadValid() {
        return MappingTestSupport.load(valid(), "inline/valid.v2.json");
    }

    @Test
    @DisplayName("规则6：画像不是合法 JSON ⇒ JSON_PARSE_ERROR，不给 profile")
    void jsonParseFailureIsJsonParseError() {
        MappingProfileLoad load = MappingTestSupport.load("{ not json ", "inline/broken.json");

        assertThat(load.ok()).isFalse();
        assertThat(load.profile()).isNull();
        assertThat(MappingTestSupport.codes(load.issues())).containsExactly("JSON_PARSE_ERROR");
    }

    @Test
    @DisplayName("规则2：顶层不是 JSON 对象 ⇒ PROFILE_INVALID")
    void topLevelNonObjectIsProfileInvalid() {
        MappingProfileLoad load = MappingTestSupport.load("[1, 2, 3]", "inline/array.json");

        assertThat(load.ok()).isFalse();
        assertThat(MappingTestSupport.has(load.issues(), MappingReason.PROFILE_INVALID)).isTrue();
    }

    @Test
    @DisplayName("profileVersion 缺失 ⇒ PROFILE_INVALID（不猜版本）")
    void missingProfileVersionIsProfileInvalid() {
        MappingProfileLoad load = MappingTestSupport.load(
                valid().replace("\"profileVersion\": \"2.0\",", ""), "inline/no-version-key.json");

        assertThat(load.ok()).isFalse();
        assertThat(MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID).detail())
                .contains("profileVersion");
    }

    @Test
    @DisplayName("规则1：契约版本无任何声明（既无 contractVersion 又无 canonical.schemaVersion）⇒ PROFILE_INVALID")
    void missingContractVersionIsProfileInvalid() {
        MappingProfileLoad load = MappingTestSupport.load(
                valid().replace("\"contractVersion\": \"1.0\",", ""), "inline/no-contract-version.json");

        assertThat(load.ok()).isFalse();
        assertThat(MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID).detail())
                .contains("contractVersion");
    }

    @Test
    @DisplayName("规则6：契约版本不受支持 ⇒ PROFILE_VERSION（fail-closed）")
    void unsupportedContractVersionIsProfileVersion() {
        MappingProfileLoad load = MappingTestSupport.load(
                v2("\"2.0\"", "{ \"browse\": \"behavior\" }", PAYLOAD_BEHAVIOR, "{}", TIME_OFFSET, AMOUNT_NONE),
                "inline/contract-2-0.json");

        assertThat(load.ok()).isFalse();
        assertThat(MappingTestSupport.codes(load.issues())).containsExactly("PROFILE_VERSION");
    }

    @Test
    @DisplayName("S2-01A.1：v2 顶层未知键 ⇒ PROFILE_INVALID（严格模式不静默忽略）")
    void v2UnknownTopLevelKeyIsProfileInvalid() {
        String withUnknown = valid().replace("\"sourceCode\": \"t\",",
                "\"sourceCode\": \"t\", \"extraKnob\": { \"x\": 1 },");

        MappingProfileLoad load = MappingTestSupport.load(withUnknown, "inline/unknown-key.json");

        assertThat(load.ok()).isFalse();
        assertThat(load.profile()).isNull();
        MappingIssue issue = MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("extraKnob");
        assertThat(issue.detail()).isEqualTo("UNKNOWN_TOP_LEVEL_KEY:extraKnob");
    }

    @Test
    @DisplayName("S2-01A.1：v2 出现 v1 遗留键（canonical）⇒ PROFILE_INVALID，不当作契约版本回退")
    void v2LegacyCanonicalKeyIsProfileInvalid() {
        String withLegacy = valid().replace("\"sourceCode\": \"t\",",
                "\"sourceCode\": \"t\", \"canonical\": { \"schemaVersion\": \"1.0\" },");

        MappingProfileLoad load = MappingTestSupport.load(withLegacy, "inline/legacy-canonical.json");

        assertThat(load.ok()).isFalse();
        MappingIssue issue = MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("canonical");
        assertThat(issue.detail()).isEqualTo("LEGACY_KEY_NOT_ALLOWED_IN_V2:canonical");
    }

    @Test
    @DisplayName("必填唯一来源：v2 声明 requiredPolicy ⇒ PROFILE_INVALID（画像无权改必填集合）")
    void requiredPolicyKeyIsRejectedInV2() {
        String withRequiredPolicy = valid().replace("\"sourceCode\": \"t\",",
                "\"sourceCode\": \"t\", \"requiredPolicy\": { \"optionalMissing\": \"OMIT\" },");

        MappingProfileLoad load = MappingTestSupport.load(withRequiredPolicy, "inline/required-policy.json");

        assertThat(load.ok()).isFalse();
        MappingIssue issue = MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("requiredPolicy");
        assertThat(issue.detail()).isEqualTo("UNKNOWN_TOP_LEVEL_KEY:requiredPolicy");
    }

    @Test
    @DisplayName("S2-01A 收口：v2 顶层声明 checksum ⇒ PROFILE_INVALID（权威哈希只能由 Loader 对原始字节计算）")
    void v2DeclaredChecksumIsProfileInvalid() {
        String withChecksum = valid().replace("\"sourceCode\": \"t\",",
                "\"sourceCode\": \"t\", \"checksum\": \"deadbeef\",");

        MappingProfileLoad load = MappingTestSupport.load(withChecksum, "inline/declared-checksum.json");

        assertThat(load.ok()).isFalse();
        assertThat(load.profile()).isNull();
        MappingIssue issue = MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("checksum");
        assertThat(issue.detail()).isEqualTo("LEGACY_KEY_NOT_ALLOWED_IN_V2:checksum");
    }

    @Test
    @DisplayName("规则2：同一事件组两个来源写同一目标 ⇒ PROFILE_INVALID（不再静默后写覆盖）")
    void duplicateTargetInSameGroupIsProfileInvalid() {
        String duplicate = v2("\"1.0\"", "{ \"browse\": \"behavior\" }",
                """
                { "behavior": { "buyer": "user_id", "buyer_alias": "user_id" } }""",
                "{}", TIME_OFFSET, AMOUNT_NONE);

        MappingProfileLoad load = MappingTestSupport.load(duplicate, "inline/duplicate-target.json");

        assertThat(load.ok()).isFalse();
        MappingIssue issue = MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("payload.behavior.user_id");
        assertThat(issue.detail()).contains("buyer").contains("buyer_alias");
    }

    @Test
    @DisplayName("规则12：enumSemantics 的键不是契约枚举字段 ⇒ PROFILE_INVALID")
    void enumSemanticsKeyOutsideContractEnumIsProfileInvalid() {
        MappingProfileLoad load = MappingTestSupport.load(
                v2("\"1.0\"", "{ \"browse\": \"behavior\" }", PAYLOAD_BEHAVIOR,
                        "{ \"nope_field\": { \"A\": \"B\" } }", TIME_OFFSET, AMOUNT_NONE),
                "inline/enum-key.json");

        assertThat(load.ok()).isFalse();
        assertThat(MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID).detail())
                .contains("nope_field");
    }

    @Test
    @DisplayName("规则12：枚举目标值不在契约枚举内 ⇒ PROFILE_INVALID（不得把非法值写进 canonical）")
    void enumSemanticsValueOutsideContractEnumIsProfileInvalid() {
        MappingProfileLoad load = MappingTestSupport.load(
                v2("\"1.0\"", "{ \"browse\": \"behavior\" }", PAYLOAD_BEHAVIOR,
                        "{ \"behavior_type\": { \"BROWSE\": \"vieww\" } }", TIME_OFFSET, AMOUNT_NONE),
                "inline/enum-value.json");

        assertThat(load.ok()).isFalse();
        assertThat(MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID).detail())
                .contains("vieww");
    }

    @Test
    @DisplayName("规则2：声明的规范事件类型没有对应 payload 组 ⇒ PROFILE_INVALID")
    void declaredEventTypeWithoutPayloadGroupIsProfileInvalid() {
        MappingProfileLoad load = MappingTestSupport.load(
                v2("\"1.0\"", "{ \"pay\": \"order_paid\" }", PAYLOAD_BEHAVIOR, "{}", TIME_OFFSET, AMOUNT_NONE),
                "inline/no-group.json");

        assertThat(load.ok()).isFalse();
        assertThat(MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID).detail())
                .contains("order_paid");
    }

    @Test
    @DisplayName("规则4：未知时间格式令牌 ⇒ PROFILE_INVALID（不猜格式）")
    void unknownTimeFormatTokenIsProfileInvalid() {
        MappingProfileLoad load = MappingTestSupport.load(
                v2("\"1.0\"", "{ \"browse\": \"behavior\" }", PAYLOAD_BEHAVIOR, "{}",
                        "{ \"field\": \"at\", \"formats\": [\"NO_SUCH_FORMAT\"] }", AMOUNT_NONE),
                "inline/time-format.json");

        assertThat(load.ok()).isFalse();
        assertThat(MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID).detail())
                .contains("NO_SUCH_FORMAT");
    }

    @Test
    @DisplayName("规则4：IANA 时区名非法 ⇒ PROFILE_INVALID")
    void unknownZoneIsProfileInvalid() {
        MappingProfileLoad load = MappingTestSupport.load(
                v2("\"1.0\"", "{ \"browse\": \"behavior\" }", PAYLOAD_BEHAVIOR, "{}",
                        "{ \"field\": \"at\", \"formats\": [\"ISO_OFFSET_DATE_TIME\"], \"zone\": \"Mars/Olympus\" }",
                        AMOUNT_NONE),
                "inline/zone.json");

        assertThat(load.ok()).isFalse();
        assertThat(MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID).detail())
                .contains("Mars/Olympus");
    }

    @Test
    @DisplayName("S2-01A.1：本地时间格式没有声明 IANA 时区 ⇒ PROFILE_INVALID（不再隐式取 Asia/Shanghai）")
    void localTimeFormatWithoutZoneIsProfileInvalid() {
        MappingProfileLoad load = MappingTestSupport.load(
                v2("\"1.0\"", "{ \"browse\": \"behavior\" }", PAYLOAD_BEHAVIOR, "{}",
                        "{ \"field\": \"at\", \"formats\": [\"ISO_LOCAL_DATE_TIME\"] }", AMOUNT_NONE),
                "inline/local-no-zone.json");

        assertThat(load.ok()).isFalse();
        MappingIssue issue = MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("timePolicy.zone");
        assertThat(issue.detail()).isEqualTo("MISSING_KEY:timePolicy.zone");
    }

    @Test
    @DisplayName("S2-01A.1：epoch 格式同样需要显式时区（渲染瞬时必须有基准）⇒ 缺声明 PROFILE_INVALID")
    void epochFormatWithoutZoneIsProfileInvalid() {
        MappingProfileLoad load = MappingTestSupport.load(
                v2("\"1.0\"", "{ \"browse\": \"behavior\" }", PAYLOAD_BEHAVIOR, "{}",
                        "{ \"field\": \"at\", \"formats\": [\"EPOCH_MILLIS\"] }", AMOUNT_NONE),
                "inline/epoch-no-zone.json");

        assertThat(load.ok()).isFalse();
        MappingIssue issue = MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("timePolicy.zone");
        assertThat(issue.detail()).isEqualTo("MISSING_KEY:timePolicy.zone");
    }

    @Test
    @DisplayName("S2-01A.1：只声明 ISO_OFFSET_DATE_TIME 的画像可以不带时区（保留原文偏移）")
    void offsetOnlyTimePolicyNeedsNoZone() {
        MappingProfile profile = MappingTestSupport.load(
                v2("\"1.0\"", "{ \"browse\": \"behavior\" }", PAYLOAD_BEHAVIOR, "{}",
                        "{ \"field\": \"at\", \"formats\": [\"ISO_OFFSET_DATE_TIME\"] }", AMOUNT_NONE),
                "inline/offset-no-zone.json").profile();

        assertThat(profile.timePolicy().requiresZone()).isFalse();
        assertThat(profile.timePolicy().zone()).isNull();
        assertThat(profile.timePolicy().zoneSource()).isNull();
    }

    @Test
    @DisplayName("S2-01A.1：金额单位按源字段声明（冻结形状 bySourceField），未声明的源字段取不到单位")
    void amountUnitsAreDeclaredPerSourceField() {
        MappingProfile profile = MappingTestSupport.load(
                v2("\"1.0\"", "{ \"pay\": \"order_paid\" }",
                        """
                        { "order_paid": { "ord": "order_id", "buyer": "user_id", "pay": "payment_id",
                                          "paid_fen": "amount", "paid_at": "paid_at" } }""",
                        "{}", TIME_OFFSET, AMOUNT_BY_FIELD),
                "inline/amount-by-field.json").profile();

        assertThat(profile.syntax()).isEqualTo(MappingProfile.ProfileSyntax.V2_STRICT);
        assertThat(profile.amountPolicy().bySourceField())
                .containsOnlyKeys("paid_fen", "paid_yuan")
                .containsEntry("paid_fen", AmountPolicySpec.Unit.FEN)
                .containsEntry("paid_yuan", AmountPolicySpec.Unit.YUAN);
        // 单位是源数据的属性：取单位按**源字段名**查，查不到就是 null（没有画像级默认单位可回退）
        assertThat(profile.amountPolicy().unitFor("paid_fen")).isEqualTo(AmountPolicySpec.Unit.FEN);
        assertThat(profile.amountPolicy().unitFor("paid_yuan")).isEqualTo(AmountPolicySpec.Unit.YUAN);
        assertThat(profile.amountPolicy().unitFor("paid_unknown")).isNull();
    }

    @Test
    @DisplayName("S2-01A.1：有金额目标却缺该源字段的单位 ⇒ PROFILE_INVALID(MISSING_AMOUNT_POLICY)，不再只是禁止激活")
    void amountUnitMissingIsProfileInvalidAtLoadTime() {
        MappingProfileLoad load = MappingTestSupport.load(
                MappingTestSupport.resourceText("order-no-amount-policy.v2.json"),
                "s2-01a/order-no-amount-policy.v2.json");

        assertThat(load.ok()).isFalse();
        assertThat(load.profile()).isNull();
        MappingIssue issue = MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("amountPolicy.bySourceField.paid_fen");
        assertThat(issue.detail()).isEqualTo("MISSING_AMOUNT_POLICY");
    }

    @Test
    @DisplayName("S2-01A.1：v2 使用旧的画像级 unit 键 ⇒ PROFILE_INVALID（没有默认单位可回退）")
    void v2LegacyUnitKeyIsRejected() {
        MappingProfileLoad load = MappingTestSupport.load(
                v2("\"1.0\"", "{ \"pay\": \"order_paid\" }",
                        """
                        { "order_paid": { "ord": "order_id", "buyer": "user_id", "pay": "payment_id",
                                          "paid_fen": "amount", "paid_at": "paid_at" } }""",
                        "{}", TIME_OFFSET, """
                        , "amountPolicy": { "unit": "FEN" }"""),
                "inline/legacy-unit.json");

        assertThat(load.ok()).isFalse();
        MappingIssue issue = MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("amountPolicy.unit");
        assertThat(issue.detail()).isEqualTo("UNKNOWN_KEY:amountPolicy.unit");
    }

    @Test
    @DisplayName("规则12：必填枚举字段存在未裁定(null)声明 ⇒ 装载成功但禁止激活")
    void keyEnumUnresolvedBlocksActivation() {
        MappingProfile profile = MappingTestSupport.profile("behavior-enum-keep.v2.json");

        assertThat(profile.activationBlocked()).isTrue();
        assertThat(String.join(" | ", profile.activationBlocks())).contains("behavior_type");
    }

    @Test
    @DisplayName("v2 画像按设计 §7.2 字段名装载：严格模式、信封/载荷分组、时区、校验和齐备且零能力缺口")
    void v2ProfileLoadsWithDesignNamedFields() {
        MappingProfile profile = MappingTestSupport.profile("behavior-enum-keep.v2.json");

        assertThat(profile.sourceCode()).isEqualTo("s2-01a-behavior");
        assertThat(profile.contractVersion()).isEqualTo("1.0");
        assertThat(profile.syntax()).isEqualTo(MappingProfile.ProfileSyntax.V2_STRICT);
        assertThat(profile.eventTypeSourceField()).isEqualTo("msg_kind");
        assertThat(profile.envelopeFieldMappings()).hasSize(7).containsEntry("msg_id", "event_id");
        assertThat(profile.payloadFieldMappings().get("behavior")).hasSize(6).containsEntry("act", "behavior_type");
        assertThat(profile.payloadFieldMappings().get("behavior")).containsEntry("ext_coupon", "@keep");
        assertThat(profile.enumSemantics().keySet()).containsExactlyInAnyOrder("behavior_type", "channel");
        assertThat(profile.timePolicy().field()).isEqualTo("occurred");
        assertThat(profile.timePolicy().zone()).isEqualTo("Asia/Shanghai");
        assertThat(profile.timePolicy().zoneSource()).isEqualTo(TimePolicySpec.ZONE_FROM_PROFILE);
        // 该画像没有金额目标 ⇒ 不需要 amountPolicy；严格模式下也没有遗留键缺口
        assertThat(profile.amountPolicy()).isNull();
        assertThat(profile.capabilityGaps()).isEmpty();
        assertThat(profile.profileChecksum()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("规则14：画像内容校验和同文相等、异文不同（预览与激活比对同一 hash）")
    void profileChecksumStableAndInputSensitive() {
        MappingProfileLoad first = loadValid();
        MappingProfileLoad second = loadValid();
        MappingProfileLoad changed = MappingTestSupport.load(
                valid().replace("\"sourceCode\": \"t\"", "\"sourceCode\": \"t2\""), "inline/valid.v2.json");

        assertThat(first.profile().profileChecksum()).isEqualTo(second.profile().profileChecksum());
        assertThat(changed.profile().profileChecksum()).isNotEqualTo(first.profile().profileChecksum());
        assertThat(first.profile().profileChecksum()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("实测缺陷登记（S2-03 发现，未修）：契约的**信封**必填表恒为空 ⇒ 信封缺映射不会阻断激活")
    void envelopeRequiredTableIsAlwaysEmptyBecauseLoaderReadsTheWrongNode() {
        // 事实链：CanonicalContractLoader:59 用 requiredNames(properties) 读信封必填，
        // 而契约把 required 放在**根**（与 properties 同级），properties 内**没有** required 键
        // （实测 contract-specs/schemas/canonical-event.v1.schema.json：根 required 有 8 个字段、
        //  properties 无 required）⇒ 每个信封字段的 required 都是 false。
        // 后果：MappingProfileLoader.collectUnmappedEnvelopeBlocks（:837-851）遍历的集合恒空，
        // 该分支至今是**死代码** —— 画像漏映射某个信封字段**不会**进 activationBlocks。
        CanonicalContract contract = MappingTestSupport.contract();
        assertThat(contract.requiredEnvelopeFields())
                .as("这条断言变红 = 有人修好了信封必填解析；请同时复核 activationBlocks 语义、"
                        + "既有 v1/v2 画像的可激活判定与 SourceMapper 闸门，不要只改断言")
                .isEmpty();

        // 对照组：payload 侧读的是 $defs.<type>.required（与 properties 同级，存在）⇒ 工作正常
        assertThat(contract.requiredPayloadFields("order_created")).isNotEmpty();
    }
}
