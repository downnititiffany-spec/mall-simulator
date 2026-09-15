package com.graduation.analytics.mapping;

import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.mapping.activation.ActiveMappingPointer;
import com.graduation.analytics.mapping.activation.TestActiveMappingPointerStore;
import com.graduation.analytics.source.dto.SourceRegistryView;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * S2-03（映射激活生命周期）测试支撑（仅测试作用域）。
 *
 * <p>放在 {@code com.graduation.analytics.mapping} 包，理由与 {@code MappingDryRunServiceTest} 相同：
 * 复用 S2-01A 的 {@link MappingTestSupport}（契约真相与仓根），不再写第三份"向上找仓根"实现
 * （已有的两份重复已登记为待合并债）。</p>
 *
 * <p><b>画像文本为什么在这里生成而不是放进 {@code src/test/resources}</b>：激活链路要比对"同一份字节"，
 * 测试需要的是**可控的若干份画像变体**（可激活 / 缺信封目标 / 缺必填映射 / 不可装载 / v1 兼容），
 * 且必须能把它们写到{@code @TempDir} 下去模拟"预览之后磁盘上的画像被改过"。内联文本让每个变体与
 * 它要触发的判据写在同一处，改动不会漂移。</p>
 */
final class MappingActivationTestSupport {

    static final String CONTRACT_REPO_PATH = "contract-specs/schemas/canonical-event.v1.schema.json";

    /** 固定业务时间：2026-09-21T02:20:30Z = Asia/Shanghai 10:20:30。 */
    static final EventClock CLOCK = new EventClock(
            Clock.fixed(Instant.parse("2026-09-21T02:20:30Z"), ZoneId.of("Asia/Shanghai")));

    static final long SOURCE_ID = 7L;
    static final String SOURCE_TOKEN = String.valueOf(SOURCE_ID);
    static final String SOURCE_CODE = "s2-03-raw-a";
    static final String PROFILE_PATH = "profiles/raw-a.v2.json";
    static final String V1_PROFILE_PATH = "profiles/raw-a.v1.json";

    private MappingActivationTestSupport() {
    }

    // ---------------------------------------------------------------- 画像变体

    /** 信封目标齐全（7 个 canonical 信封字段都有来源） */
    private static final String ENVELOPE_FULL = "{\"id\":\"event_id\",\"kind\":\"event_type\","
            + "\"at\":\"event_time\",\"sys\":\"source_system\",\"rev\":\"schema_version\","
            + "\"tr\":\"trace_id\",\"data\":\"payload\"}";

    /** 信封缺 {@code source_system} 目标 */
    private static final String ENVELOPE_NO_SOURCE_SYSTEM = "{\"id\":\"event_id\",\"kind\":\"event_type\","
            + "\"at\":\"event_time\",\"rev\":\"schema_version\",\"tr\":\"trace_id\",\"data\":\"payload\"}";

    private static final String ORDER_PAID_FULL = "{\"ord\":\"order_id\",\"buyer\":\"user_id\","
            + "\"pay\":\"payment_id\",\"paid_fen\":\"amount\",\"paidAt\":\"paid_at\"}";

    private static final String ORDER_PAID_MISSING_PAYMENT_ID = "{\"ord\":\"order_id\",\"buyer\":\"user_id\","
            + "\"paid_fen\":\"amount\",\"paidAt\":\"paid_at\"}";

    /**
     * 可执行 v2 画像：信封 7 个目标齐全、{@code order_paid} 必填 payload 全部映射、金额声明来源单位
     * ⇒ dry-run 既装载成功又 activationEligible=true（"预览通过"的那一份）。
     */
    static String v2Profile() {
        return v2Profile(ORDER_PAID_FULL, ENVELOPE_FULL);
    }

    /** 信封缺 {@code source_system} 目标 ⇒ 装载成功但 {@code activationBlocks} 非空（可装载≠可激活）。 */
    static String v2ProfileMissingEnvelopeTarget() {
        return v2Profile(ORDER_PAID_FULL, ENVELOPE_NO_SOURCE_SYSTEM);
    }

    /** 必填 payload 字段没有映射 ⇒ 能力缺口 + 激活阻断（两者同时出现，是本轮 loader 的既有事实）。 */
    static String v2ProfileMissingRequiredPayload() {
        return v2Profile(ORDER_PAID_MISSING_PAYMENT_ID, ENVELOPE_FULL);
    }

    /** v2 严格模式出现 v1 遗留顶层键 ⇒ 装载失败（{@code profileAccepted=false}）。 */
    static String rejectedV2Profile() {
        return """
                {
                  "profileVersion": "2.0",
                  "sourceCode": "%s",
                  "contractVersion": "1.0",
                  "eventTypeMapping": { "paid_se": "order_paid" },
                  "fieldMappings": {
                    "envelope": { "id": "event_id", "kind": "event_type", "at": "event_time",
                      "sys": "source_system", "rev": "schema_version", "tr": "trace_id", "data": "payload" },
                    "payload": { "order_paid": {
                      "ord": "order_id", "buyer": "user_id", "pay": "payment_id",
                      "paid_fen": "amount", "paidAt": "paid_at" } }
                  },
                  "enumSemantics": {},
                  "timePolicy": { "field": "at", "formats": ["ISO_OFFSET_DATE_TIME"], "zone": "Asia/Shanghai" },
                  "amountPolicy": { "bySourceField": { "paid_fen": "FEN" } }
                }
                """.formatted(SOURCE_CODE);
    }

    private static String v2Profile(String orderPaidPayload, String envelope) {
        return """
                {
                  "profileVersion": "2.0",
                  "sourceCode": "%s",
                  "contractVersion": "1.0",
                  "eventTypeMappings": {
                    "sourceField": "kind",
                    "values": { "paid_se": "order_paid" }
                  },
                  "fieldMappings": {
                    "envelope": %s,
                    "payload": { "order_paid": %s }
                  },
                  "enumSemantics": {},
                  "timePolicy": {
                    "field": "at",
                    "formats": ["ISO_OFFSET_DATE_TIME"],
                    "zone": "Asia/Shanghai"
                  },
                  "amountPolicy": { "bySourceField": { "paid_fen": "FEN" } }
                }
                """.formatted(SOURCE_CODE, envelope, orderPaidPayload);
    }

    /**
     * v1 兼容画像：可装载、{@code order_paid} 必填字段齐全、金额单位声明完备、
     * 信封走"同名身份"模式 ⇒ 无激活阻断。加一个 {@code identityPolicy} 键即可单独制造**只有能力缺口**
     * 的报告（v1 遗留策略键只进 {@code capabilityGaps}，不进 {@code activationBlocks}）。
     */
    static String v1Profile(boolean withIdentityGap) {
        String identityPolicy = withIdentityGap ? ",\n  \"identityPolicy\": {}" : "";
        return """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "s2-03-v1-source",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "order_paid": "order_paid" },
                  "fieldMapping": {
                    "order_id": "order_id", "user_id": "user_id", "payment_id": "payment_id",
                    "amount": "amount", "paid_at": "paid_at"
                  },
                  "enumSemantics": {},
                  "timePolicy": { "field": "event_time", "formats": ["ISO_OFFSET_DATE_TIME"] },
                  "amountPolicy": { "bySourceField": { "amount": "YUAN" } }%s
                }
                """.formatted(identityPolicy);
    }

    // ---------------------------------------------------------------- 样本

    /** 一条画像声明过的原始行（源侧字段名与 canonical 完全不同），可完整接受。 */
    static String rawLine() {
        return rawLine("evt-1", "\"ord\":\"o-1\",\"buyer\":\"u-1\",\"pay\":\"p-1\","
                + "\"paid_fen\":\"12345\",\"paidAt\":\"2026-09-21T09:30:01+08:00\"");
    }

    /** 同一条原始行但缺一个必填 payload 字段 ⇒ 产生违例（可接受行数变 0）。 */
    static String rawLineMissingRequired() {
        return rawLine("evt-2", "\"buyer\":\"u-1\",\"pay\":\"p-1\","
                + "\"paid_fen\":\"12345\",\"paidAt\":\"2026-09-21T09:30:01+08:00\"");
    }

    /** v1 同名身份模式下的一条 canonical 形状输入行（与 S2-01B 的 HTTP 用例同形）。 */
    static String canonicalLine() {
        return "{\"event_id\":\"s2-03-v1-0001\",\"event_type\":\"order_paid\","
                + "\"event_time\":\"2026-09-20T15:00:47+08:00\",\"ingest_time\":\"2026-09-20T15:00:48+08:00\","
                + "\"source_system\":\"s2-03-v1-source\",\"schema_version\":\"1.0\",\"trace_id\":\"s2-03-trc\","
                + "\"payload\":{\"order_id\":\"1001\",\"user_id\":\"1\",\"payment_id\":\"P-1001\","
                + "\"amount\":\"199.00\",\"paid_at\":\"2026-09-20T15:00:47+08:00\"}}";
    }

    private static String rawLine(String id, String payloadFields) {
        return "{\"id\":\"" + id + "\",\"kind\":\"paid_se\","
                + "\"at\":\"2026-09-21T09:30:00+08:00\",\"sys\":\"" + SOURCE_CODE + "\",\"rev\":\"1.0\","
                + "\"tr\":\"trace-" + id + "\",\"data\":{" + payloadFields + "}}";
    }

    // ---------------------------------------------------------------- 文件与视图

    static Path writeProfile(Path root, String repoRelative, String text) {
        try {
            Path file = root.resolve(repoRelative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void writeSample(Path sampleRoot, String ref, String... lines) {
        try {
            Path file = sampleRoot.resolve(ref);
            Files.createDirectories(file.getParent() == null ? sampleRoot : file.getParent());
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static SourceRegistryView view(long id, String profilePath) {
        return new SourceRegistryView(id, SOURCE_CODE, "S2-03 激活探针源", "LOCAL_FILE", profilePath,
                "Asia/Shanghai", "CNY", "ACTIVE", "2.0", true, null, null, "s2_03_raw_a");
    }

    // ---------------------------------------------------------------- 指针存储替身

    /** 进程内指针替身（测试作用域，见 {@link TestActiveMappingPointerStore} 的边界说明）。 */
    static TestActiveMappingPointerStore store() {
        return new TestActiveMappingPointerStore();
    }

    static ActiveMappingPointer pointer(long sourceId, String profileChecksum, String contractChecksum) {
        return new ActiveMappingPointer(sourceId, SOURCE_CODE, PROFILE_PATH, "2.0", profileChecksum,
                "1.0", contractChecksum, "dr-seed-0001", LocalDateTime.parse("2026-01-01T00:00:00"), "seed");
    }
}
