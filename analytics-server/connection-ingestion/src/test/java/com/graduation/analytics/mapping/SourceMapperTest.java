package com.graduation.analytics.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.mapping.ingest.MappedLine;
import com.graduation.analytics.mapping.ingest.SourceMapper;
import com.graduation.analytics.mapping.ingest.SourceMapping;
import com.graduation.analytics.source.dto.SourceRegistryView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S2-02：真实采集路径上的「画像装载 + 逐行映射」适配器（{@link SourceMapper}）。
 *
 * <p>钉住的是**谁决定这一轮要不要映射**（fail-closed 三分支）以及**平台生成的 ingest_time**
 * 落在哪里，而不是重测 S2-01A 的映射语义（那些在 {@code MappingExecutorTest} 里已冻结）。</p>
 */
class SourceMapperTest {

    /** 契约真相来自中立目录（只读），不复制内容。 */
    private static final String CONTRACT_PATH = "contract-specs/schemas/canonical-event.v1.schema.json";
    private static final String SOURCE_CODE = "s2-02-raw-a";
    private static final String PROFILE_PATH = "profiles/raw-a.v2.json";

    /** 固定业务时间：2026-09-21T02:20:30Z = Asia/Shanghai 10:20:30。 */
    private static final EventClock CLOCK = new EventClock(
            Clock.fixed(Instant.parse("2026-09-21T02:20:30Z"), ZoneId.of("Asia/Shanghai")));

    /** 一条 v2 画像声明过的原始行（源侧字段名与 canonical 完全不同）。 */
    private static final String RAW_LINE = "{\"id\":\"evt-1\",\"kind\":\"paid_se\","
            + "\"at\":\"2026-09-21T09:30:00+08:00\",\"sys\":\"" + SOURCE_CODE + "\",\"rev\":\"1.0\","
            + "\"tr\":\"trace-1\",\"data\":{\"ord\":\"o-1\",\"buyer\":\"u-1\",\"pay\":\"p-1\","
            + "\"paid_fen\":\"12345\",\"paidAt\":\"2026-09-21T09:30:01+08:00\"}}";

    private static final List<String> ENVELOPE_ORDER = List.of(
            "event_id", "event_type", "event_time", "ingest_time",
            "source_system", "schema_version", "trace_id", "payload");

    private SourceMapper mapper(Path profileRoot) {
        return new SourceMapper(profileRoot.toString(),
                MappingTestSupport.repoFile(CONTRACT_PATH).toString(), CLOCK, MappingJson.mapper());
    }

    private static SourceRegistryView view(String profilePath) {
        return new SourceRegistryView(7L, SOURCE_CODE, "S2-02 探针源", "LOCAL_FILE", profilePath,
                "Asia/Shanghai", "CNY", "ACTIVE", "2.0", true, null, null, "s2_02_raw_a");
    }

    private static void writeProfile(Path root, String repoRelative, String text) throws IOException {
        Path file = root.resolve(repoRelative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** 可执行的 v2 画像（信封 7 个目标齐全、order_paid 的必填 payload 齐全、金额声明单位）。 */
    private static String v2Profile() {
        return v2Profile("{\"ord\":\"order_id\",\"buyer\":\"user_id\",\"pay\":\"payment_id\","
                + "\"paid_fen\":\"amount\",\"paidAt\":\"paid_at\"}");
    }

    private static String v2Profile(String orderPaidPayload) {
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
                    "envelope": {
                      "id": "event_id", "kind": "event_type", "at": "event_time",
                      "sys": "source_system", "rev": "schema_version",
                      "tr": "trace_id", "data": "payload"
                    },
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
                """.formatted(SOURCE_CODE, orderPaidPayload);
    }

    // ---------------------------------------------------------------- ① 三分支：谁决定映射

    @Test
    @DisplayName("v1 兼容画像（种子源现状）⇒ 不映射：只读兼容模式不能激活，直通既有 canonical 校验")
    void v1ProfileKeepsLegacyPassthrough() {
        // 用仓内真实种子画像 + 仓根作为 profile-root：解析与登记行完全同路径（只读）
        SourceMapper mapper = new SourceMapper(MappingTestSupport.repoFile(".").toString(),
                MappingTestSupport.repoFile(CONTRACT_PATH).toString(), CLOCK, MappingJson.mapper());
        SourceRegistryView seed = new SourceRegistryView(1L, "mock-mall", "种子源", "LOCAL_FILE",
                "analytics-server/source-profiles/mock-mall.v1.json", "Asia/Shanghai", "CNY",
                "ACTIVE", "1.0", true, null, null, "mock_mall");

        SourceMapping mapping = mapper.prepare(seed);

        assertThat(mapping.applied())
                .as("v1 兼容画像缺金额单位（activationBlocks 非空），逐行映射会隔离所有金额事件；"
                        + "因此只能在「不映射」下保持既有采集行为")
                .isFalse();
        assertThat(mapping.profile()).isNull();
    }

    @Test
    @DisplayName("可执行 v2 画像 ⇒ 映射生效，版本与哈希来自画像原文（与 dry-run 的 profileChecksum 同一算法）")
    void executableV2ProfileIsApplied(@TempDir Path root) throws IOException {
        String text = v2Profile();
        writeProfile(root, PROFILE_PATH, text);

        SourceMapping mapping = mapper(root).prepare(view(PROFILE_PATH));

        assertThat(mapping.applied()).isTrue();
        assertThat(mapping.profileVersion()).isEqualTo("2.0");
        assertThat(mapping.profileChecksum())
                .as("画像哈希 = Mapper 对原始画像字节的 sha256（dry-run 报告同款算法）")
                .isEqualTo(MappingHash.sha256Hex(text));
    }

    @Test
    @DisplayName("v2 画像可装载但被激活门阻止 ⇒ 运行前 fail-closed（MAPPING_PROFILE_BLOCKED）")
    void blockedV2ProfileFailsClosed(@TempDir Path root) throws IOException {
        // 故意漏掉 order_paid 的必填 payload 目标 payment_id：装载成功，但禁止激活
        writeProfile(root, PROFILE_PATH,
                v2Profile("{\"ord\":\"order_id\",\"buyer\":\"user_id\",\"paid_fen\":\"amount\"}"));

        assertThatThrownBy(() -> mapper(root).prepare(view(PROFILE_PATH)))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(((PlatformBizException) e).getCode())
                        .isEqualTo(PlatformBizException.MAPPING_PROFILE_BLOCKED))
                .hasMessageContaining("unmapped:order_paid.payment_id");
    }

    @Test
    @DisplayName("画像装载失败（缺金额单位）⇒ 运行前 fail-closed（MAPPING_PROFILE_INVALID）")
    void unloadableV2ProfileFailsClosed(@TempDir Path root) throws IOException {
        writeProfile(root, PROFILE_PATH, v2Profile().replace(
                "\"amountPolicy\": { \"bySourceField\": { \"paid_fen\": \"FEN\" } }",
                "\"amountPolicy\": { \"bySourceField\": {} }"));

        assertThatThrownBy(() -> mapper(root).prepare(view(PROFILE_PATH)))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(((PlatformBizException) e).getCode())
                        .isEqualTo(PlatformBizException.MAPPING_PROFILE_INVALID));
    }

    @Test
    @DisplayName("画像文件不存在 ⇒ 运行前 fail-closed，绝不静默降级成「不映射」")
    void missingProfileFileFailsClosed(@TempDir Path root) {
        assertThatThrownBy(() -> mapper(root).prepare(view("profiles/absent.v2.json")))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(((PlatformBizException) e).getCode())
                        .isEqualTo(PlatformBizException.MAPPING_PROFILE_INVALID))
                .hasMessageContaining("profiles/absent.v2.json");
    }

    @Test
    @DisplayName("profile_path 逃逸仓库相对策略（..）⇒ 拒绝，且不回显该值")
    void escapingProfilePathIsRejected(@TempDir Path root) {
        assertThatThrownBy(() -> mapper(root).prepare(view("../outside.v2.json")))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(((PlatformBizException) e).getCode())
                        .isEqualTo(PlatformBizException.MAPPING_PROFILE_INVALID))
                .hasMessageNotContaining("outside");
    }

    @Test
    @DisplayName("画像 sourceCode 与登记不一致 ⇒ fail-closed（防止用 A 源画像映射 B 源字节）")
    void sourceCodeMismatchFailsClosed(@TempDir Path root) throws IOException {
        writeProfile(root, PROFILE_PATH, v2Profile().replace(SOURCE_CODE, "other-source"));

        assertThatThrownBy(() -> mapper(root).prepare(view(PROFILE_PATH)))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(((PlatformBizException) e).getCode())
                        .isEqualTo(PlatformBizException.MAPPING_PROFILE_INVALID))
                .hasMessageContaining("other-source");
    }

    // ---------------------------------------------------------------- ② 平台生成的 ingest_time

    @Test
    @DisplayName("映射成功的行由平台写入 ingest_time（固定业务时间、秒级、+08:00），并按契约信封顺序输出")
    void mappedLineCarriesPlatformIngestTime(@TempDir Path root) throws IOException {
        writeProfile(root, PROFILE_PATH, v2Profile());
        SourceMapping mapping = mapper(root).prepare(view(PROFILE_PATH));

        MappedLine line = mapper(root).map(mapping, RAW_LINE);

        assertThat(line.accepted()).isTrue();
        JsonNode canonical = MappingTestSupport.json(line.canonicalText());
        assertThat(canonical.path("ingest_time").asText())
                .as("ingest_time 由平台采集层生成（Mapper 只登记源侧候选、不写 canonical）")
                .isEqualTo("2026-09-21T10:20:30+08:00");
        List<String> names = new ArrayList<>();
        canonical.fieldNames().forEachRemaining(names::add);
        assertThat(names)
                .as("canonical 行按契约信封顺序输出（ingest_time 落回契约位置，而不是追加到末尾）")
                .containsExactlyElementsOf(ENVELOPE_ORDER);
        assertThat(canonical.path("event_type").asText()).isEqualTo("order_paid");
        assertThat(canonical.path("payload").path("amount").asText())
                .as("FEN → 元 的金额换算在真实采集路径上同样生效")
                .isEqualTo("123.45");
    }

    @Test
    @DisplayName("原始行自带的 ingest_time 不采信：平台采集时间覆盖它")
    void sourceSideIngestTimeIsNotTrusted(@TempDir Path root) throws IOException {
        writeProfile(root, PROFILE_PATH, v2Profile());
        SourceMapping mapping = mapper(root).prepare(view(PROFILE_PATH));
        String rawWithCandidate = RAW_LINE.replace("\"id\":\"evt-1\",",
                "\"id\":\"evt-1\",\"ingest_time\":\"2000-01-01T00:00:00+08:00\",");

        MappedLine line = mapper(root).map(mapping, rawWithCandidate);

        assertThat(MappingTestSupport.json(line.canonicalText()).path("ingest_time").asText())
                .isEqualTo("2026-09-21T10:20:30+08:00");
    }

    @Test
    @DisplayName("映射拒收的行 ⇒ 不带 canonical，只带 MAPPING: 前缀的隔离原因")
    void unmappableLineIsQuarantinedWithReason(@TempDir Path root) throws IOException {
        writeProfile(root, PROFILE_PATH, v2Profile());
        SourceMapping mapping = mapper(root).prepare(view(PROFILE_PATH));

        MappedLine line = mapper(root).map(mapping, RAW_LINE.replace("\"paid_se\"", "\"unknown_se\""));

        assertThat(line.accepted()).isFalse();
        assertThat(line.canonicalText()).isNull();
        assertThat(line.quarantineReason())
                .as("隔离原因必须与契约校验器给出的原因可区分（同一列 reason，来源不同）")
                .startsWith("MAPPING:")
                .contains("UNKNOWN_EVENT_TYPE");
    }

    @Test
    @DisplayName("隔离原因按 quarantine_record.reason VARCHAR(255) 截断并给出省略计数")
    void quarantineReasonIsCappedForColumnWidth() {
        List<MappingIssue> issues = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            issues.add(MappingIssue.of(MappingReason.BAD_ENUM, "payload.field_" + i,
                    "VALUE=" + "x".repeat(20)));
        }

        String reason = SourceMapper.formatViolations(issues);

        assertThat(reason).startsWith("MAPPING:").hasSizeLessThanOrEqualTo(255);
        assertThat(reason).as("被截断时必须告知还有多少条未列出").matches(".*;\\+\\d+$");
    }
}
