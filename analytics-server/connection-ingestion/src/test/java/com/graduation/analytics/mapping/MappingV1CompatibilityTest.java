package com.graduation.analytics.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-01A/A.1：既有 v1 画像（扁平 {@code fieldMapping}）兼容性与真实夹具回归。
 *
 * <p>v1 = **只读兼容模式**（{@link MappingProfile.ProfileSyntax#V1_COMPATIBILITY}）：历史文件一字不改，
 * 但只保留「能装载/能执行」的兼容行为，未实现能力只登记为能力缺口，不静默假装支持。</p>
 *
 * <p>两份 v1 画像都是**只读样本**（本轮一字未改）：</p>
 * <ul>
 *   <li>{@code analytics-server/source-profiles/mock-mall.v1.json}：能装载，但在新规则下**不可激活**
 *       （有金额目标却缺逐源字段单位、且绑定到必填枚举字段的组含未裁定 null）。</li>
 *   <li>{@code docs/acceptance/p5-heterogeneous-source-20260912/mapping/fixture-b.v1.json}：扁平
 *       {@code fieldMapping} 有 3 个目标被两个来源同时写入（quantity/status/amount）⇒ 按规则 2 直接
 *       {@code PROFILE_INVALID}，这正是 P5 批次 B3B「字段映射歧义」要暴露的问题。</li>
 * </ul>
 *
 * <p>真实夹具取自 P5 验收域的 {@code b3a-missing-required.jsonl}（该夹具本身已是 canonical 形状的
 * 「闸门」样本）：用它核对「缺必填 ⇒ EMPTY_FIELD + 隔离」与 P5 预期结果一致，并顺带证明
 * {@code ingest_time} 不由 Mapper 生成。</p>
 */
class MappingV1CompatibilityTest {

    private static final String MOCK_MALL_V1 = "analytics-server/source-profiles/mock-mall.v1.json";
    private static final String FIXTURE_B_V1 =
            "docs/acceptance/p5-heterogeneous-source-20260912/mapping/fixture-b.v1.json";
    private static final String B3A_JSONL =
            "docs/acceptance/p5-heterogeneous-source-20260912/fixtures/b3a-missing-required.jsonl";
    private static final String B3_EXPECTED_TSV =
            "docs/acceptance/p5-heterogeneous-source-20260912/fixtures/expected/B3-expected-outcomes.tsv";

    /** v1 扁平语法 + 身份信封（信封字段名与 canonical 同名，不声明 envelope 映射）。 */
    private static final String INLINE_V1_ORDER_PAID = """
            {
              "profileVersion": "1.0",
              "sourceCode": "inline-v1-order-paid",
              "canonical": { "schemaVersion": "1.0" },
              "eventTypeMapping": { "order_paid": "order_paid" },
              "fieldMapping": {
                "order_id": "order_id", "user_id": "user_id", "payment_id": "payment_id",
                "amount": "amount", "paid_at": "paid_at"
              },
              "enumSemantics": {},
              "timePolicy": { "field": "event_time", "formats": ["ISO_OFFSET_DATE_TIME"] },
              "amountPolicy": { "bySourceField": { "amount": "YUAN" } }
            }
            """;

    private static final String INLINE_V1_ORDER_CREATED = """
            {
              "profileVersion": "1.0",
              "sourceCode": "inline-v1-order-created",
              "canonical": { "schemaVersion": "1.0" },
              "eventTypeMapping": { "order_created": "order_created" },
              "fieldMapping": {
                "order_id": "order_id", "user_id": "user_id", "items": "items",
                "total_amount": "total_amount", "status": "status", "created_at": "created_at"
              },
              "enumSemantics": {},
              "timePolicy": { "field": "event_time", "formats": ["ISO_OFFSET_DATE_TIME"] },
              "amountPolicy": { "bySourceField": { "total_amount": "YUAN" } }
            }
            """;

    /** v1 兼容：有金额目标却不声明单位（历史 mock-mall 就是这样）⇒ 可装载 + 禁止激活 + 执行期 fail-closed。 */
    private static final String INLINE_V1_ORDER_PAID_NO_UNIT = """
            {
              "profileVersion": "1.0",
              "sourceCode": "inline-v1-order-paid-no-unit",
              "canonical": { "schemaVersion": "1.0" },
              "eventTypeMapping": { "order_paid": "order_paid" },
              "fieldMapping": {
                "order_id": "order_id", "user_id": "user_id", "payment_id": "payment_id",
                "amount": "amount", "paid_at": "paid_at"
              },
              "enumSemantics": {},
              "timePolicy": { "field": "event_time", "formats": ["ISO_OFFSET_DATE_TIME"] }
            }
            """;

    /** v1 兼容回退验证：显式声明 {@code body → payload}，但 raw 里没有 body 容器。 */
    private static final String INLINE_V1_ROOT_FALLBACK = """
            {
              "profileVersion": "1.0",
              "sourceCode": "inline-v1-root-fallback",
              "contractVersion": "1.0",
              "eventTypeMapping": { "order_paid": "order_paid" },
              "fieldMapping": {
                "eid": "event_id", "ekind": "event_type", "eat": "event_time", "esys": "source_system",
                "erev": "schema_version", "etr": "trace_id", "eig": "ingest_time", "body": "payload",
                "ord": "order_id", "buyer": "user_id", "pay": "payment_id",
                "paid": "amount", "paid_at": "paid_at"
              },
              "enumSemantics": {},
              "timePolicy": { "field": "eat", "formats": ["ISO_OFFSET_DATE_TIME"] },
              "amountPolicy": { "bySourceField": { "paid": "YUAN" } }
            }
            """;

    // ---------- 真实 v1 画像 ----------

    @Test
    @DisplayName("v1（mock-mall）：可装载；信封走同名身份模式；枚举组经取值桥接到契约字段")
    void realV1MockMallLoadsInIdentityEnvelopeMode() {
        MappingProfileLoad load = MappingTestSupport.load(MappingTestSupport.repoText(MOCK_MALL_V1), MOCK_MALL_V1);

        assertThat(load.ok()).as("issues=%s", MappingTestSupport.codes(load.issues())).isTrue();
        MappingProfile profile = load.profile();
        assertThat(profile.profileVersion()).isEqualTo("1.0");
        assertThat(profile.sourceCode()).isEqualTo("mock-mall");
        // v1 无 contractVersion ⇒ 回退到 canonical.schemaVersion
        assertThat(profile.contractVersion()).isEqualTo(MappingTestSupport.contract().contractVersion());
        assertThat(profile.syntax()).isEqualTo(MappingProfile.ProfileSyntax.V1_COMPATIBILITY);
        assertThat(profile.envelopeSourceMode())
                .isEqualTo(MappingProfile.EnvelopeSourceMode.CANONICAL_NAME_IDENTITY);
        assertThat(profile.eventTypeMappings()).hasSize(12);
        assertThat(profile.payloadFieldMappings()).hasSize(profile.eventTypeMappings().size());
        assertThat(profile.payloadFieldMappings("behavior"))
                .containsEntry("user_id", "user_id")
                .containsEntry("behavior_type", "behavior_type")
                .containsEntry("channel", "channel")
                .containsEntry("event_time_utc", MappingProfileLoader.KEEP);
        assertThat(profile.enumBindings())
                .containsEntry("behavior", "behavior_type")
                .containsEntry("channel", "channel")
                .containsEntry("cityLevel", "city_level");
        assertThat(profile.enumUnboundGroups()).isEmpty();
        assertThat(profile.enumAmbiguousGroups()).isEmpty();
        assertThat(profile.timePolicy().field()).isEqualTo("event_time");
        assertThat(profile.amountPolicy()).isNull();
        assertThat(profile.profileChecksum()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("v1（mock-mall）：缺 amountPolicy + 必填枚举含未裁定 null ⇒ 可装载但禁止激活")
    void realV1MockMallIsLoadableButActivationBlocked() {
        MappingProfile profile = MappingTestSupport.load(MappingTestSupport.repoText(MOCK_MALL_V1), MOCK_MALL_V1)
                .profile();

        assertThat(profile.activationBlocked()).isTrue();
        assertThat(profile.activationBlocks())
                .contains("amountPolicy", "enum:behavior_type", "enum:channel", "enum:change_type");
        assertThat(profile.capabilityGaps()).contains("amountPolicy:有金额目标但画像未声明单位");
    }

    @Test
    @DisplayName("v1 遗留策略：identityPolicy/quarantinePolicy 只登记为能力缺口（既不执行，也不伪装成错误）")
    void legacyPoliciesAreCapabilityGapsOnly() {
        MappingProfile profile = MappingTestSupport.load(MappingTestSupport.repoText(MOCK_MALL_V1), MOCK_MALL_V1)
                .profile();

        assertThat(profile.capabilityGaps())
                .contains("identityPolicy:未执行（身份策略归后续身份体系，Mapper 不生成代理键）",
                        "quarantinePolicy:未执行（隔离持久化策略归 S2-02）");
        // 能力缺口≠激活阻断：这两项由后续阶段负责，不阻止本阶段可判定的映射执行
        assertThat(profile.activationBlocks()).doesNotContain("identityPolicy", "quarantinePolicy");
    }

    @Test
    @DisplayName("v1 历史 checksum：仅兼容元数据缺口，权威哈希仍是原始画像字节的 sha256（不比对声明值）")
    void v1DeclaredChecksumIsCompatibilityMetadataOnly() {
        String withChecksum = INLINE_V1_ORDER_PAID.replace("\"profileVersion\": \"1.0\",",
                "\"profileVersion\": \"1.0\", \"checksum\": \"deadbeef\",");

        MappingProfileLoad load = MappingTestSupport.load(withChecksum, "inline/v1-checksum.json");

        assertThat(load.ok()).as("issues=%s", MappingTestSupport.codes(load.issues())).isTrue();
        MappingProfile profile = load.profile();
        assertThat(profile.capabilityGaps()).contains(
                "checksum:仅兼容元数据（不参与权威画像哈希比较；权威值 = Loader 对原始画像字节的 sha256）");
        assertThat(profile.profileChecksum())
                .isEqualTo(MappingHash.sha256Hex(withChecksum))
                .isNotEqualTo("deadbeef");
    }

    @Test
    @DisplayName("v1 兼容模式：未识别的顶层键只记缺口，不判 PROFILE_INVALID（历史文件不改）")
    void v1UnknownTopLevelKeyIsOnlyCapabilityGap() {
        String withLegacyKnob = INLINE_V1_ORDER_PAID.replace("\"enumSemantics\": {},",
                "\"enumSemantics\": {}, \"someLegacyKnob\": { \"x\": 1 },");

        MappingProfileLoad load = MappingTestSupport.load(withLegacyKnob, "inline/v1-legacy-knob.json");

        assertThat(load.ok()).isTrue();
        assertThat(load.profile().capabilityGaps()).contains("unknownTopLevelKey:someLegacyKnob");
    }

    @Test
    @DisplayName("v1 兼容模式：缺 contractVersion ⇒ 回退 canonical.schemaVersion（显式断言，不靠默认值）")
    void v1ContractVersionFallsBackToCanonicalSchemaVersion() {
        String withoutContractVersion = INLINE_V1_ORDER_PAID;
        assertThat(withoutContractVersion).doesNotContain("\"contractVersion\"");

        MappingProfile profile = MappingTestSupport.load(withoutContractVersion, "inline/v1-no-contract-version.json")
                .profile();

        assertThat(profile.contractVersion()).isEqualTo("1.0");
        assertThat(profile.syntax()).isEqualTo(MappingProfile.ProfileSyntax.V1_COMPATIBILITY);
    }

    @Test
    @DisplayName("v1 兼容模式：contractVersion 与 canonical.schemaVersion 冲突 ⇒ PROFILE_INVALID（v2 已无回退路径）")
    void v1ContractVersionConflictWithCanonicalIsProfileInvalid() {
        String conflicting = INLINE_V1_ORDER_PAID.replace("\"profileVersion\": \"1.0\",",
                "\"profileVersion\": \"1.0\", \"contractVersion\": \"1.1\",");

        MappingProfileLoad load = MappingTestSupport.load(conflicting, "inline/v1-conflict.json");

        assertThat(load.ok()).isFalse();
        assertThat(load.profile()).isNull();
        MappingIssue issue = MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("contractVersion");
        assertThat(issue.detail()).contains("1.1").contains("1.0").contains("CONFLICT");
    }

    @Test
    @DisplayName("v1 兼容回退（仅 v1）：声明的载荷容器缺失时回落根仍是兼容行为，且单位已声明可正常执行")
    void v1PayloadRootFallbackStaysCompatibilityOnly() {
        // 故意让 raw 没有 body 容器：v1 允许回落根（历史行为），字段仍在根上
        String raw = "{\"eid\":\"v1-fb-1\",\"ekind\":\"order_paid\","
                + "\"eat\":\"2026-09-20T15:00:47+08:00\",\"eig\":\"2026-09-20T15:00:48+08:00\","
                + "\"esys\":\"fixture-b\",\"erev\":\"1.0\",\"etr\":\"v1-trc-1\","
                + "\"ord\":\"1001\",\"buyer\":\"1\",\"pay\":\"P-1001\",\"paid\":\"199.00\","
                + "\"paid_at\":\"2026-09-20T15:00:47+08:00\"}";

        MappingOutcome outcome = MappingTestSupport.executeText(INLINE_V1_ROOT_FALLBACK,
                "inline/v1-root-fallback.json", raw);

        assertThat(outcome.quarantined()).isFalse();
        assertThat(MappingTestSupport.payloadText(outcome, "amount")).isEqualTo("199.00");
        assertThat(outcome.stats().payloadBase()).isEqualTo(MappingStats.BASE_ROOT);
    }

    @Test
    @DisplayName("v1 兼容但无单位：可装载 + 禁止激活，执行期 fail-closed（不猜 FEN/YUAN）")
    void v1WithoutAmountUnitFailsClosedAtRuntime() {
        MappingProfileLoad load = MappingTestSupport.load(INLINE_V1_ORDER_PAID_NO_UNIT,
                "inline/v1-no-unit.json");

        assertThat(load.ok()).isTrue();
        assertThat(load.profile().activationBlocked()).isTrue();
        assertThat(load.profile().activationBlocks()).contains("amountPolicy");
        assertThat(load.profile().capabilityGaps()).contains("amountPolicy.missingUnit:amount");

        String raw = "{\"event_id\":\"v1-nu-1\",\"event_type\":\"order_paid\","
                + "\"event_time\":\"2026-09-20T15:00:47+08:00\",\"ingest_time\":\"2026-09-20T15:00:48+08:00\","
                + "\"source_system\":\"fixture-b\",\"schema_version\":\"1.0\",\"trace_id\":\"v1-trc-2\","
                + "\"payload\":{\"order_id\":\"1001\",\"user_id\":\"1\",\"payment_id\":\"P-1001\","
                + "\"amount\":\"199.00\",\"paid_at\":\"2026-09-20T15:00:47+08:00\"}}";

        MappingOutcome outcome = new MappingExecutor(MappingTestSupport.contract(), MappingTestSupport.mapper())
                .execute(load.profile(), raw);

        assertThat(outcome.quarantined()).isTrue();
        assertThat(outcome.canonical()).isNull();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("payload.amount");
        assertThat(issue.detail()).isEqualTo("MISSING_AMOUNT_POLICY");
    }

    @Test
    @DisplayName("v1（fixture-b）：同一目标被两个来源写入 ⇒ PROFILE_INVALID（扁平语法歧义）")
    void realV1FixtureBAmbiguousFlatTargetsAreInvalid() {
        MappingProfileLoad load = MappingTestSupport.load(MappingTestSupport.repoText(FIXTURE_B_V1), FIXTURE_B_V1);

        assertThat(load.ok()).isFalse();
        assertThat(load.profile()).isNull();
        List<String> paths = load.issues().stream().map(MappingIssue::path).toList();
        assertThat(paths)
                .contains("fieldMapping.quantity", "fieldMapping.status", "fieldMapping.amount");
        assertThat(MappingTestSupport.codes(load.issues())).contains("PROFILE_INVALID");
    }

    // ---------- v1 语法跑真实/等价数据 ----------

    @Test
    @DisplayName("v1 身份画像：canonical 形状事件直通映射成功；ingest_time 不落 canonical，只登记为平台待生成")
    void inlineV1IdentityProfileMapsCanonicalShapedEvent() {
        String raw = "{\"event_id\":\"s2-01a-v1-0001\",\"event_type\":\"order_paid\","
                + "\"event_time\":\"2026-09-20T15:00:47+08:00\",\"ingest_time\":\"2026-09-20T15:00:48+08:00\","
                + "\"source_system\":\"fixture-b\",\"schema_version\":\"1.0\",\"trace_id\":\"s2-01a-trc-0001\","
                + "\"payload\":{\"order_id\":\"1001\",\"user_id\":\"1\",\"payment_id\":\"P-1001\","
                + "\"amount\":\"199.00\",\"paid_at\":\"2026-09-20T15:00:47+08:00\"}}";

        MappingOutcome outcome = MappingTestSupport.executeText(INLINE_V1_ORDER_PAID, "inline/v1-order-paid.json", raw);

        assertThat(outcome.quarantined()).isFalse();
        JsonNode canonical = outcome.canonical();
        assertThat(canonical.path("event_type").asText()).isEqualTo("order_paid");
        assertThat(canonical.path("event_time").asText()).isEqualTo("2026-09-20T15:00:47+08:00");
        assertThat(canonical.has("ingest_time")).isFalse();
        assertThat(canonical.size()).isEqualTo(7);
        assertThat(outcome.pendingPlatformFields()).containsExactly("ingest_time");
        assertThat(outcome.ingestTimeCandidate()).isEqualTo("2026-09-20T15:00:48+08:00");
        assertThat(MappingTestSupport.payloadText(outcome, "amount")).isEqualTo("199.00");
        assertThat(outcome.stats().requiredCoverage()).isEqualTo(1.0);
        assertThat(outcome.rawChecksum()).isNotEqualTo(outcome.canonicalChecksum());
    }

    @Test
    @DisplayName("真实夹具 b3a-evt-0001（order_paid 缺 payment_id）⇒ EMPTY_FIELD(MISSING) 且隔离，与 P5 预期一致")
    void realB3aMissingPaymentIdIsEmptyFieldMissing() {
        MappingOutcome outcome = MappingTestSupport.executeText(INLINE_V1_ORDER_PAID, "inline/v1-order-paid.json",
                b3aLine(1));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.EMPTY_FIELD);
        assertThat(issue.path()).isEqualTo("payload.payment_id");
        assertThat(issue.detail()).isEqualTo("MISSING");
        assertThat(b3ExpectedRow("b3a-evt-0001")).contains("QUARANTINE").contains("payment_id");
    }

    @Test
    @DisplayName("真实夹具 b3a-evt-0002（order_paid 缺 amount）⇒ EMPTY_FIELD(MISSING) 且隔离，与 P5 预期一致")
    void realB3aMissingAmountIsEmptyFieldMissing() {
        MappingOutcome outcome = MappingTestSupport.executeText(INLINE_V1_ORDER_PAID, "inline/v1-order-paid.json",
                b3aLine(2));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.EMPTY_FIELD);
        assertThat(issue.path()).isEqualTo("payload.amount");
        assertThat(issue.detail()).isEqualTo("MISSING");
        assertThat(b3ExpectedRow("b3a-evt-0002")).contains("QUARANTINE").contains("amount");
    }

    @Test
    @DisplayName("v1 扁平语法表达不了 ITEM_MAP：真实 order_created（含 items 数组）⇒ fail-closed 隔离")
    void v1FlatSyntaxCannotExpressItemMap() {
        MappingOutcome outcome = MappingTestSupport.executeText(INLINE_V1_ORDER_CREATED,
                "inline/v1-order-created.json", b3aLine(7));

        assertThat(outcome.quarantined()).isTrue();
        assertThat(MappingTestSupport.has(outcome.violations(), MappingReason.PROFILE_INVALID)).isTrue();
        MappingIssue items = MappingTestSupport.issue(outcome.violations(), MappingReason.PROFILE_INVALID);
        assertThat(items.path()).isEqualTo("payload.items");
        assertThat(items.detail()).isEqualTo("ITEMS_REQUIRES_ITEM_MAP");
        // 该行同时缺 total_amount（P5 预期 B3A-2 也判隔离）⇒ 两个违例都记在案
        assertThat(MappingTestSupport.has(outcome.violations(), MappingReason.EMPTY_FIELD)).isTrue();
        assertThat(outcome.canonical()).isNull();
    }

    // ---------- 夹具读取 ----------

    private static String b3aLine(int oneBasedLine) {
        List<String> lines = MappingTestSupport.repoText(B3A_JSONL).lines()
                .filter(line -> !line.isBlank())
                .toList();
        assertThat(lines.size()).isGreaterThanOrEqualTo(oneBasedLine);
        return lines.get(oneBasedLine - 1);
    }

    private static String b3ExpectedRow(String rowId) {
        return MappingTestSupport.repoText(B3_EXPECTED_TSV).lines()
                .filter(line -> line.startsWith(rowId + "\t"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("B3 预期结果缺行: " + rowId));
    }
}
