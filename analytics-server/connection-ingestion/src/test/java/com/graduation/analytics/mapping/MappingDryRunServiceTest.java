package com.graduation.analytics.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.mapping.dryrun.InMemoryMappingDryRunReportRepository;
import com.graduation.analytics.mapping.dryrun.MappingDryRunReport;
import com.graduation.analytics.mapping.dryrun.MappingDryRunService;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S2-01B 映射 dry-run 编排（设计 §7.3 规则 10/13、§7.4）。
 *
 * <p><b>为什么本类在 {@code com.graduation.analytics.mapping} 包而不是 {@code ...mapping.dryrun}</b>：
 * S2-01A 的测试支撑 {@link MappingTestSupport}（契约真相、仓根、逐行执行器）是包内可见的，
 * 复用它比在 dryrun 包再写一份"向上找仓根 + 装载契约"的第二实现更好（第二份实现已登记为待合并债）。
 * 被测类本身是 public，跨包使用没有任何放宽。</p>
 *
 * <p><b>取证口径</b>：凡"语义对不对"的问题都不由本类判定——本类只判定**编排**：
 * 逐行喂样本、聚合执行器给出的事实、生成/保存/取回报告、以及 fail-closed 判据。
 * 语义期望值一律来自直接调用冻结执行器算出的"预言机"，不另行复述规则。</p>
 */
class MappingDryRunServiceTest {

    private static final String CONTRACT_REPO_PATH = "contract-specs/schemas/canonical-event.v1.schema.json";
    private static final String MOCK_MALL_V1 = "analytics-server/source-profiles/mock-mall.v1.json";
    private static final String FIXTURE_B_V1 =
            "docs/acceptance/p5-heterogeneous-source-20260912/mapping/fixture-b.v1.json";
    private static final String B1A_JSONL =
            "docs/acceptance/p5-heterogeneous-source-20260912/fixtures/b1a-envelope-vocab.jsonl";
    private static final String B3A_JSONL =
            "docs/acceptance/p5-heterogeneous-source-20260912/fixtures/b3a-missing-required.jsonl";

    /** S2-01B 自有的 v1 画像（含一个「声明了但引用不到」的金额单位，用于未使用声明口径） */
    private static final String PROFILE_V1 = """
            {
              "profileVersion": "1.0",
              "sourceCode": "s2-01b-order-paid",
              "canonical": { "schemaVersion": "1.0" },
              "eventTypeMapping": { "order_paid": "order_paid" },
              "fieldMapping": {
                "order_id": "order_id", "user_id": "user_id", "payment_id": "payment_id",
                "amount": "amount", "paid_at": "paid_at"
              },
              "enumSemantics": {},
              "timePolicy": { "field": "event_time", "formats": ["ISO_OFFSET_DATE_TIME"] },
              "amountPolicy": { "bySourceField": { "amount": "YUAN", "legacy_fen": "FEN" } }
            }
            """;
    /** S2-01A 的 v2 画像（信封映射 + items 数组 + FEN 金额），复用以证明 v2 路径 */
    private static final String PROFILE_V2_ITEMS = "order-items-fen.v2.json";
    /** S2-01A 的 v2 画像（枚举语义含未裁定 null），复用以证明枚举覆盖率/告警 */
    private static final String PROFILE_V2_BEHAVIOR = "behavior-enum-keep.v2.json";

    private static final Path CONTRACT_FILE = MappingTestSupport.repoFile(CONTRACT_REPO_PATH);

    /** 固定时钟：报告里的 createdAt 与 reportId 前缀可预期；固定时刻也能反证"除这两个字段外确定" */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T02:20:30Z"), ZoneId.of("Asia/Shanghai"));

    /** 非空样本行数（空白行不计） */
    private static long countEventLines(String text) {
        return text.lines().filter(line -> !line.isBlank()).count();
    }

    @TempDir
    Path samples;

    private final InMemoryMappingDryRunReportRepository reports = new InMemoryMappingDryRunReportRepository();
    private final EventClock clock = new EventClock(FIXED_CLOCK);

    private MappingDryRunService service() {
        return service(samples);
    }

    private MappingDryRunService service(Path sampleRoot) {
        return new MappingDryRunService(reports, clock, CONTRACT_FILE.toString(), sampleRoot.toString());
    }

    private Path writeSample(String name, String content) throws IOException {
        Path file = samples.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String sha256(String text) {
        return MappingHash.sha256Hex(text);
    }

    /**
     * 报告序列化用的 mapper。
     *
     * <p>{@code MappingJson.mapper()} 是映射核心的解析器，**故意**不带 jsr310 模块（它只解析
     * canonical/画像 JSON，没有时间类型字段）。报告的 {@code createdAt} 是 {@code LocalDateTime}，
     * 由 HTTP 层的 Spring 自带 mapper（已注册 jsr310）序列化；本测试要独立验证报告字段集，
     * 因此这里显式注册 jsr310，而不是去改核心解析器。</p>
     */
    private static ObjectMapper reportMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    // ---------- 样本语料 ----------

    /** canonical 形状的完整 order_paid（可映射成功；与 S2-01A 的身份画像用例同形） */
    private static final String ORDER_PAID_OK =
            "{\"event_id\":\"s2-01b-0001\",\"event_type\":\"order_paid\","
                    + "\"event_time\":\"2026-09-20T15:00:47+08:00\",\"ingest_time\":\"2026-09-20T15:00:48+08:00\","
                    + "\"source_system\":\"fixture-b\",\"schema_version\":\"1.0\",\"trace_id\":\"s2-01b-trc-0001\","
                    + "\"payload\":{\"order_id\":\"1001\",\"user_id\":\"1\",\"payment_id\":\"P-1001\","
                    + "\"amount\":\"199.00\",\"paid_at\":\"2026-09-20T15:00:47+08:00\"}}";

    /** order_paid 但缺 payment_id ⇒ 必填缺失（P5 B3A 夹具第 1 行，真实数据） */
    private static String b3aLine(int oneBasedLine) {
        List<String> lines = MappingTestSupport.repoText(B3A_JSONL).lines()
                .filter(line -> !line.isBlank())
                .toList();
        assertThat(lines.size()).isGreaterThanOrEqualTo(oneBasedLine);
        return lines.get(oneBasedLine - 1);
    }

    private static final String BEHAVIOR_OK =
            "{\"msg_id\":\"e-1\",\"msg_kind\":\"browse\",\"occurred\":\"2026-09-20T11:00:00+08:00\","
                    + "\"origin\":\"s2-src\",\"rev\":\"1.0\",\"trc\":\"t-1\",\"body\":{\"buyer\":1,\"item\":\"7\","
                    + "\"visit\":\"s-1\",\"act\":\"BROWSE\",\"term\":\"APP\",\"ext_coupon\":\"C-9\"}}";

    /** 同一事件但枚举取值在画像里是未裁定 null（PURCHASE / MINI_PROG）⇒ 期望只出告警、不进违例 */
    private static final String BEHAVIOR_UNRESOLVED_ENUM =
            BEHAVIOR_OK.replace("\"act\":\"BROWSE\"", "\"act\":\"PURCHASE\"")
                    .replace("\"term\":\"APP\"", "\"term\":\"MINI_PROG\"")
                    .replace("\"msg_id\":\"e-1\"", "\"msg_id\":\"e-2\"");

    // ---------- 编排：聚合与预言机对账 ----------

    @Test
    @DisplayName("逐行聚合与「直接执行冻结执行器」算出的预言机逐项一致（含物理行号）")
    void aggregatesMatchDirectExecutorOracle() throws IOException {
        String profile = PROFILE_V1;
        String sampleText = ORDER_PAID_OK + "\n" + b3aLine(1) + "\n\n" + b3aLine(2) + "\n";
        writeSample("b3a-mix.jsonl", sampleText);

        MappingDryRunReport report = service().run("src-1", profile, "b3a-mix.jsonl", 100);

        MappingProfile loaded = MappingTestSupport.profileFromText(profile, PROFILE_V1);
        MappingExecutor executor = new MappingExecutor(MappingTestSupport.contract(), MappingTestSupport.mapper());
        int[] lineNos = {1, 2, 4};
        String[] raws = {ORDER_PAID_OK, b3aLine(1), b3aLine(2)};
        List<MappingIssue> violationOracle = new ArrayList<>();
        int acceptedOracle = 0;
        int quarantinedOracle = 0;
        int requiredTotal = 0;
        int requiredOk = 0;
        List<String> acceptedChecksums = new ArrayList<>();
        for (int i = 0; i < raws.length; i++) {
            MappingOutcome outcome = executor.execute(loaded, raws[i]);
            outcome.violations().forEach(issue -> violationOracle.add(issue));
            if (outcome.quarantined()) {
                quarantinedOracle++;
            } else {
                acceptedOracle++;
                acceptedChecksums.add(outcome.canonicalChecksum());
            }
            requiredTotal += outcome.stats().requiredPositionsTotal();
            requiredOk += outcome.stats().requiredPositionsOk();
        }

        assertThat(report.processedCount()).isEqualTo(3);
        assertThat(report.sampleLineCount()).isEqualTo(3);
        assertThat(report.requestedLimit()).isEqualTo(100);
        assertThat(report.acceptedCount()).isEqualTo(acceptedOracle).isEqualTo(1);
        assertThat(report.quarantinedCount()).isEqualTo(quarantinedOracle).isEqualTo(2);
        assertThat(report.systemErrors()).isEmpty();
        assertThat(report.acceptedCanonicalChecksums()).isEqualTo(acceptedChecksums);
        assertThat(report.acceptedCount() + report.quarantinedCount() + report.systemErrors().size())
                .isEqualTo(report.processedCount());
        // 违例条数与行号：第 3 行是空行，第 4 行的行号必须是 4（物理行号，不是"第 3 个事件"）
        assertThat(report.violations()).hasSize(violationOracle.size()).hasSize(2);
        assertThat(report.violations().stream().map(issue -> issue.lineNo())).containsExactly(2, 4);
        assertThat(report.violations().stream().map(issue -> issue.reason().name()))
                .containsExactlyElementsOf(MappingTestSupport.codes(violationOracle));
        assertThat(report.requiredPositionsTotal()).isEqualTo(requiredTotal);
        assertThat(report.requiredPositionsOk()).isEqualTo(requiredOk);
        assertThat(report.requiredCoverage()).isEqualTo((double) requiredOk / (double) requiredTotal);
        // 该画像无枚举字段 ⇒ 分母为 0 ⇒ 覆盖率是 null（不写 0.0 冒充"全错"）
        assertThat(report.enumPairsObserved()).isZero();
        assertThat(report.enumCoverage()).isNull();
        // 预览只放通过的行
        assertThat(report.preview()).hasSize(1);
        assertThat(report.preview().get(0).lineNo()).isEqualTo(1);
        assertThat(report.preview().get(0).canonical().has("ingest_time")).isFalse();
    }

    @Test
    @DisplayName("limit 只截断执行范围：sampleLineCount/requestedLimit 仍如实报告，且总行数不受影响")
    void limitTruncatesExecutionButNotFacts() throws IOException {
        writeSample("three.jsonl", ORDER_PAID_OK + "\n" + b3aLine(1) + "\n" + b3aLine(2) + "\n");

        MappingDryRunReport report = service().run("src-1", PROFILE_V1, "three.jsonl", 2);

        assertThat(report.requestedLimit()).isEqualTo(2);
        assertThat(report.sampleLineCount()).isEqualTo(3);
        assertThat(report.processedCount()).isEqualTo(2);
        assertThat(report.acceptedCount()).isEqualTo(1);
        assertThat(report.quarantinedCount()).isEqualTo(1);
        assertThat(report.violations().stream().map(issue -> issue.lineNo())).containsExactly(2);
    }

    @Test
    @DisplayName("空白行跳过但占行号；CRLF 被容忍（行号仍是物理行号）")
    void blankLinesAreSkippedAndCrLfTolerated() throws IOException {
        writeSample("crlf.jsonl", "\n   \n" + ORDER_PAID_OK + "\r\n" + b3aLine(1) + "\r\n\n");

        MappingDryRunReport report = service().run("src-1", PROFILE_V1, "crlf.jsonl", 100);

        assertThat(report.sampleLineCount()).isEqualTo(2);
        assertThat(report.processedCount()).isEqualTo(2);
        assertThat(report.acceptedCount()).isEqualTo(1);
        assertThat(report.quarantinedCount()).isEqualTo(1);
        assertThat(report.violations().stream().map(issue -> issue.lineNo())).containsExactly(4);
    }

    @Test
    @DisplayName("空样本：处理 0 行，且不得因「没有事实」而判可激活（fail-closed 加固）")
    void emptySampleIsNotEligible() throws IOException {
        writeSample("empty.jsonl", "\n  \n\n");

        MappingDryRunReport report = service().run("src-1", PROFILE_V1, "empty.jsonl", 100);

        assertThat(report.sampleLineCount()).isZero();
        assertThat(report.processedCount()).isZero();
        assertThat(report.requiredCoverage()).isNull();
        assertThat(report.enumCoverage()).isNull();
        assertThat(report.activationEligible()).isFalse();
        assertThat(report.activationIneligibleReasons())
                .anySatisfy(reason -> assertThat(reason).contains("processedCount=0"));
    }

    @Test
    @DisplayName("预览上限 5 条，但「通过行」的全量校验和一条不少")
    void previewIsCappedWhileChecksumsAreComplete() throws IOException {
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            sample.append(ORDER_PAID_OK.replace("\"order_id\":\"1001\"", "\"order_id\":\"100" + i + "\"")).append('\n');
        }
        writeSample("seven.jsonl", sample.toString());

        MappingDryRunReport report = service().run("src-1", PROFILE_V1, "seven.jsonl", 100);

        assertThat(report.acceptedCount()).isEqualTo(7);
        assertThat(report.acceptedCanonicalChecksums()).hasSize(7).doesNotContainNull();
        assertThat(report.preview()).hasSize(MappingDryRunService.PREVIEW_MAX);
        assertThat(report.preview().stream().map(p -> p.lineNo())).containsExactly(1, 2, 3, 4, 5);
    }

    // ---------- 输入校验与资源查找 ----------

    @Test
    @DisplayName("limit 必填且 1..100：null/0/101/负数一律 PARAM_INVALID（不静默取默认值）")
    void limitIsMandatoryAndBounded() throws IOException {
        writeSample("s.jsonl", ORDER_PAID_OK + "\n");
        MappingDryRunService service = service();

        for (Integer bad : new Integer[]{null, 0, -1, 101, 1000}) {
            assertThatThrownBy(() -> service.run("src-1", PROFILE_V1, "s.jsonl", bad))
                    .as("limit=%s", bad)
                    .isInstanceOf(PlatformBizException.class)
                    .extracting(e -> ((PlatformBizException) e).getCode())
                    .isEqualTo(PlatformBizException.PARAM_INVALID);
        }
        assertThat(service.run("src-1", PROFILE_V1, "s.jsonl", 1).processedCount()).isEqualTo(1);
        assertThat(service.run("src-1", PROFILE_V1, "s.jsonl", 100).processedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("profileText 为空 ⇒ PARAM_INVALID（画像不许缺省，缺省就等于替调用方猜一版）")
    void blankProfileIsParamInvalid() throws IOException {
        writeSample("s.jsonl", ORDER_PAID_OK + "\n");
        MappingDryRunService service = service();

        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service.run("src-1", blank, "s.jsonl", 10))
                    .isInstanceOf(PlatformBizException.class)
                    .extracting(e -> ((PlatformBizException) e).getCode())
                    .isEqualTo(PlatformBizException.PARAM_INVALID);
        }
    }

    @Test
    @DisplayName("sourceId/reportId 形状非法 ⇒ PARAM_INVALID（路径段安全，不回显原值）")
    void identifiersMustBeSafeTokens() throws IOException {
        writeSample("s.jsonl", ORDER_PAID_OK + "\n");
        MappingDryRunService service = service();

        for (String bad : new String[]{null, "", "  ", "a b", "a/b", "../x", "a".repeat(65)}) {
            assertThatThrownBy(() -> service.run(bad, PROFILE_V1, "s.jsonl", 10))
                    .isInstanceOf(PlatformBizException.class)
                    .extracting(e -> ((PlatformBizException) e).getCode())
                    .isEqualTo(PlatformBizException.PARAM_INVALID);
        }
        assertThatThrownBy(() -> service.report("src-1", "dr-x/../y"))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageNotContaining("dr-x/../y");
    }

    @Test
    @DisplayName("样本不存在/不是普通文件 ⇒ DRY_RUN_SAMPLE_NOT_FOUND（404 码），引用越界 ⇒ PARAM_INVALID（400 码）")
    void sampleLookupFailuresAreDistinguishedFromIllegalRefs() throws IOException {
        writeSample("dir/placeholder.txt", "x");
        MappingDryRunService service = service();

        assertThatThrownBy(() -> service.run("src-1", PROFILE_V1, "not-there.jsonl", 10))
                .isInstanceOf(PlatformBizException.class)
                .extracting(e -> ((PlatformBizException) e).getCode())
                .isEqualTo(PlatformBizException.DRY_RUN_SAMPLE_NOT_FOUND);
        assertThatThrownBy(() -> service.run("src-1", PROFILE_V1, "dir", 10))
                .isInstanceOf(PlatformBizException.class)
                .extracting(e -> ((PlatformBizException) e).getCode())
                .isEqualTo(PlatformBizException.DRY_RUN_SAMPLE_NOT_FOUND);
        for (String illegal : new String[]{"../outside.jsonl", "/etc/passwd", "file:///D:/x.jsonl"}) {
            assertThatThrownBy(() -> service.run("src-1", PROFILE_V1, illegal, 10))
                    .as("illegal=%s", illegal)
                    .isInstanceOf(PlatformBizException.class)
                    .extracting(e -> ((PlatformBizException) e).getCode())
                    .isEqualTo(PlatformBizException.PARAM_INVALID);
        }
    }

    // ---------- 画像装载失败：fail-closed 展示 ----------

    @Test
    @DisplayName("画像装载失败 ⇒ 报告 profileAccepted=false、不执行样本、0 行，但校验和与行数如实给出")
    void profileLoadFailureIsDisplayedFailClosed() throws IOException {
        String badProfile = "{\"profileVersion\":\"2.0\",\"contractVersion\":\"1.0\","
                + "\"eventTypeMappings\":{},\"fieldMappings\":{\"payload\":{}},\"amountPolicy\":{\"bySourceField\":{}}}";
        String sampleText = ORDER_PAID_OK + "\n" + b3aLine(1) + "\n";
        writeSample("s.jsonl", sampleText);

        MappingDryRunReport report = service().run("src-1", badProfile, "s.jsonl", 10);

        assertThat(report.profileAccepted()).isFalse();
        assertThat(report.profileIssues()).isNotEmpty();
        assertThat(report.profileIssues().stream().map(issue -> issue.reason().name()))
                .contains(MappingReason.PROFILE_INVALID.name());
        assertThat(report.profileVersion()).isNull();
        assertThat(report.processedCount()).isZero();
        assertThat(report.acceptedCount()).isZero();
        assertThat(report.quarantinedCount()).isZero();
        assertThat(report.violations()).isEmpty();
        assertThat(report.preview()).isEmpty();
        assertThat(report.requestedLimit()).isEqualTo(10);
        assertThat(report.sampleLineCount()).isEqualTo(2).isEqualTo(countEventLines(sampleText));
        assertThat(report.profileChecksum()).isEqualTo(sha256(badProfile));
        assertThat(report.sampleChecksum()).isEqualTo(MappingHash.sha256Hex(sampleText.getBytes(StandardCharsets.UTF_8)));
        assertThat(report.activationEligible()).isFalse();
        assertThat(report.activationIneligibleReasons()).anySatisfy(reason -> assertThat(reason).contains("profileAccepted=false"));
        // 画像级问题的行号约定为 0（不指向任何样本行）
        assertThat(report.profileIssues().stream().map(issue -> issue.lineNo())).containsOnly(0);
    }

    @Test
    @DisplayName("校验和冻结口径：v2 顶层 checksum ⇒ 拒（LEGACY_KEY_NOT_ALLOWED_IN_V2）；v1 同键 ⇒ 只登记兼容缺口")
    void topLevelChecksumIsFrozenForV2ButGapForV1() throws IOException {
        writeSample("s.jsonl", ORDER_PAID_OK + "\n");
        MappingDryRunService service = service();

        String v2WithChecksum = MappingTestSupport.resourceText(PROFILE_V2_ITEMS)
                .replace("\"profileVersion\": \"2.0\",", "\"profileVersion\": \"2.0\", \"checksum\": \"deadbeef\",");
        MappingDryRunReport v2 = service.run("src-1", v2WithChecksum, "s.jsonl", 1);
        assertThat(v2.profileAccepted()).isFalse();
        assertThat(v2.profileIssues().stream().map(issue -> issue.detail()))
                .anySatisfy(detail -> assertThat(detail).contains("LEGACY_KEY_NOT_ALLOWED_IN_V2"));

        String v1WithChecksum = PROFILE_V1
                .replace("\"profileVersion\": \"1.0\",", "\"profileVersion\": \"1.0\", \"checksum\": \"deadbeef\",");
        MappingDryRunReport v1 = service.run("src-1", v1WithChecksum, "s.jsonl", 1);
        assertThat(v1.profileAccepted()).isTrue();
        assertThat(v1.capabilityGaps()).anySatisfy(gap -> assertThat(gap).startsWith("checksum:"));
        // 权威哈希仍是原始字节的 sha256，绝不等于声明的 checksum
        assertThat(v1.profileChecksum()).isEqualTo(sha256(v1WithChecksum)).isNotEqualTo("deadbeef");
    }

    // ---------- 覆盖率、告警、未使用声明 ----------

    @Test
    @DisplayName("枚举覆盖率与重复次数无关（同一对枚举多来几行，比值不变；未裁定取值只出告警不出违例）")
    void enumCoverageIsInvariantUnderRepetition() throws IOException {
        String profile = MappingTestSupport.resourceText(PROFILE_V2_BEHAVIOR);
        writeSample("enum-1.jsonl", BEHAVIOR_OK + "\n");
        writeSample("enum-3.jsonl", BEHAVIOR_OK + "\n" + BEHAVIOR_OK + "\n" + BEHAVIOR_OK + "\n");
        writeSample("enum-mixed.jsonl", BEHAVIOR_OK + "\n" + BEHAVIOR_UNRESOLVED_ENUM + "\n");

        MappingDryRunService service = service();
        MappingDryRunReport once = service.run("s", profile, "enum-1.jsonl", 100);
        MappingDryRunReport thrice = service.run("s", profile, "enum-3.jsonl", 100);
        MappingDryRunReport mixed = service.run("s", profile, "enum-mixed.jsonl", 100);

        assertThat(once.enumPairsObserved()).isEqualTo(2);
        assertThat(once.enumCoverage()).isEqualTo(1.0);
        // 3 行重复：观察到 6 对、解决 6 对 ⇒ 与 1 行同为 1.0（合计比 ≡ 去重比）
        assertThat(thrice.enumPairsObserved()).isEqualTo(6);
        assertThat(thrice.enumCoverage()).isEqualTo(once.enumCoverage());

        assertThat(mixed.enumPairsObserved()).isEqualTo(4);
        assertThat(mixed.enumPairsResolved()).isEqualTo(2);
        assertThat(mixed.enumCoverage()).isEqualTo(0.5);
        // 未裁定的枚举只出告警：不计入违例，也不进 reasonCounts
        assertThat(mixed.warningCounts()).containsEntry(MappingReason.ENUM_UNRESOLVED, 2);
        assertThat(mixed.warnings().stream().map(issue -> issue.lineNo())).containsOnly(2, 2);
        assertThat(mixed.reasonCounts()).doesNotContainKey(MappingReason.ENUM_UNRESOLVED);
        assertThat(mixed.violations()).noneSatisfy(issue ->
                assertThat(issue.reason()).isEqualTo(MappingReason.ENUM_UNRESOLVED));
    }

    @Test
    @DisplayName("未使用声明：声明了单位却没有任何金额目标引用它 ⇒ 以提示列出（不拒绝、不新增原因码）")
    void unusedAmountDeclarationsAreReportedAsHintOnly() throws IOException {
        writeSample("s.jsonl", ORDER_PAID_OK + "\n");
        MappingDryRunService service = service();

        MappingDryRunReport v1 = service.run("s", PROFILE_V1, "s.jsonl", 1);
        assertThat(v1.profileAccepted()).isTrue();
        assertThat(v1.unusedDeclarations()).containsExactly("legacy_fen");

        // 对照：v2 画像里 5 个单位都被 items/金额目标引用 ⇒ 无未使用声明
        String v2Sample = "{\"id\":\"e-2\",\"kind\":\"order_new_se\",\"at\":\"2026-09-20T12:00:00+08:00\","
                + "\"sys\":\"src\",\"rev\":\"1.0\",\"tr\":\"t-2\",\"data\":{\"ord\":\"O-1\",\"buyer\":\"U-1\","
                + "\"lines\":[{\"sku\":\"P-1\",\"qty\":2,\"unit_fen\":1234,\"disc_fen\":0,\"amt_fen\":2468}],"
                + "\"total_fen\":2468,\"state\":\"CREATED\",\"placed\":\"2026-09-20T12:00:00+08:00\"}}";
        writeSample("v2.jsonl", v2Sample + "\n");
        MappingDryRunReport v2 = service.run("s", MappingTestSupport.resourceText(PROFILE_V2_ITEMS), "v2.jsonl", 1);
        assertThat(v2.profileAccepted()).isTrue();
        assertThat(v2.acceptedCount()).isEqualTo(1);
        assertThat(v2.unusedDeclarations()).isEmpty();
        assertThat(v2.amountRoundedCount()).isZero();
    }

    // ---------- 可激活判据 ----------

    @Test
    @DisplayName("可激活判据是不变量：eligible ⇔ 无违例 且 无系统异常 且 处理行数>0 且 blocks/gaps 皆空")
    void activationEligibilityIsTheDocumentedInvariant() throws IOException {
        writeSample("s.jsonl", ORDER_PAID_OK + "\n");
        MappingDryRunService service = service();

        MappingDryRunReport clean = service.run("s", PROFILE_V1, "s.jsonl", 1);
        assertThat(clean.activationEligible())
                .isEqualTo(clean.violations().isEmpty() && clean.systemErrors().isEmpty()
                        && clean.processedCount() > 0 && clean.activationBlocks().isEmpty()
                        && clean.capabilityGaps().isEmpty());
        assertThat(clean.activationIneligibleReasons()).isEmpty();

        // 真实 v1 画像（mock-mall）：有激活阻断 + 能力缺口 ⇒ 一律不可激活，并逐条列出原因
        MappingDryRunReport mock = service.run("s", MappingTestSupport.repoText(MOCK_MALL_V1), "s.jsonl", 1);
        assertThat(mock.profileAccepted()).isTrue();
        assertThat(mock.activationBlocks()).isNotEmpty();
        assertThat(mock.capabilityGaps()).isNotEmpty();
        assertThat(mock.activationEligible()).isFalse();
        assertThat(mock.activationIneligibleReasons())
                .anySatisfy(reason -> assertThat(reason).contains("activationBlocks"))
                .anySatisfy(reason -> assertThat(reason).contains("capabilityGaps"));
    }

    @Test
    @DisplayName("系统异常不冒充坏数据：单独计数、不让该行计入成败、并阻断可激活")
    void systemErrorsAreCountedSeparatelyAndBlockActivation() throws IOException {
        writeSample("s.jsonl", ORDER_PAID_OK + "\n" + b3aLine(1) + "\n" + b3aLine(2) + "\n");
        MappingDryRunService service = new ControlledFailureService(samples);

        MappingDryRunReport report = service.run("s", PROFILE_V1, "s.jsonl", 100);

        assertThat(report.processedCount()).isEqualTo(3);
        assertThat(report.systemErrors()).hasSize(1);
        assertThat(report.systemErrors().get(0)).contains("样本第 2 行执行异常").contains("IllegalStateException");
        assertThat(report.acceptedCount()).isEqualTo(1);
        assertThat(report.quarantinedCount()).isEqualTo(1);
        assertThat(report.acceptedCount() + report.quarantinedCount() + report.systemErrors().size())
                .isEqualTo(report.processedCount());
        assertThat(report.violations()).hasSize(1);
        assertThat(report.violations().get(0).lineNo()).isEqualTo(3);
        assertThat(report.reasonCounts()).doesNotContainKey(MappingReason.PROFILE_INVALID);
        assertThat(report.activationEligible()).isFalse();
        assertThat(report.activationIneligibleReasons()).anySatisfy(reason -> assertThat(reason).contains("systemErrors=1"));
    }

    @Test
    @DisplayName("报告存入进程内存储：同 sourceId 可取回，sourceId 不匹配或未知 reportId ⇒ DRY_RUN_REPORT_NOT_FOUND")
    void reportsAreStoredInProcessAndScopedToSource() throws IOException {
        writeSample("s.jsonl", ORDER_PAID_OK + "\n");
        MappingDryRunService service = service();

        MappingDryRunReport saved = service.run("src-1", PROFILE_V1, "s.jsonl", 1);

        assertThat(service.report("src-1", saved.reportId())).isEqualTo(saved);
        assertThat(saved.reportId()).matches("dr-\\d{14}-[0-9a-f]{8}");
        for (String[] probe : new String[][]{{"src-2", saved.reportId()}, {"src-1", "dr-20260921000000-deadbeef"}}) {
            assertThatThrownBy(() -> service.report(probe[0], probe[1]))
                    .isInstanceOf(PlatformBizException.class)
                    .extracting(e -> ((PlatformBizException) e).getCode())
                    .isEqualTo(PlatformBizException.DRY_RUN_REPORT_NOT_FOUND);
        }
    }

    // ---------- 确定性、校验和与序列化 ----------

    @Test
    @DisplayName("同一输入两次 ⇒ 除 reportId/createdAt 外全部事实逐字节一致（确定性）")
    void sameInputsYieldIdenticalFacts() throws IOException {
        writeSample("s.jsonl", ORDER_PAID_OK + "\n" + b3aLine(1) + "\n");
        MappingDryRunService service = service();
        String profile = PROFILE_V1;

        MappingDryRunReport first = service.run("src-1", profile, "s.jsonl", 100);
        MappingDryRunReport second = service.run("src-1", profile, "s.jsonl", 100);

        ObjectMapper mapper = reportMapper();
        ObjectNode a = (ObjectNode) mapper.valueToTree(first);
        ObjectNode b = (ObjectNode) mapper.valueToTree(second);
        // reportId 每次唯一（含 UUID 片段），createdAt 由注入时钟决定；其余字段必须逐字节一致
        assertThat(first.reportId()).isNotEqualTo(second.reportId());
        assertThat(first.createdAt()).isEqualTo(second.createdAt());
        a.remove("reportId");
        b.remove("reportId");
        a.remove("createdAt");
        b.remove("createdAt");
        assertThat(a).isEqualTo(b);
        assertThat(a.path("processedCount").asInt())
                .isEqualTo(2)
                .isEqualTo(b.path("processedCount").asInt());
    }

    @Test
    @DisplayName("校验和一律是「原始字节的 sha256」：画像=入参文本、契约=契约文件、样本=样本文件")
    void checksumsAreRawByteSha256() throws IOException {
        String profile = PROFILE_V1;
        Path sampleFile = writeSample("s.jsonl", ORDER_PAID_OK + "\n");

        MappingDryRunReport report = service().run("src-1", profile, "s.jsonl", 1);

        assertThat(report.profileChecksum())
                .isEqualTo(sha256(profile))
                .isEqualTo(MappingTestSupport.profileFromText(profile, PROFILE_V1).profileChecksum())
                .matches("[0-9a-f]{64}");
        assertThat(report.sampleChecksum())
                .isEqualTo(MappingHash.sha256Hex(Files.readAllBytes(sampleFile)))
                .matches("[0-9a-f]{64}");
        assertThat(report.contractChecksum())
                .isEqualTo(MappingHash.sha256Hex(Files.readAllBytes(CONTRACT_FILE)))
                .matches("[0-9a-f]{64}");
        assertThat(report.contractVersion()).isEqualTo(MappingTestSupport.contract().contractVersion());
        assertThat(report.profileVersion()).isEqualTo("1.0");
        assertThat(report.profileSyntax()).isEqualTo(MappingProfile.ProfileSyntax.V1_COMPATIBILITY);
    }

    @Test
    @DisplayName("报告序列化字段集冻结：33 个字段名逐个在 JSON 里出现（防改名/漏序列化）")
    void reportJsonContainsAllContractFields() throws IOException {
        writeSample("s.jsonl", ORDER_PAID_OK + "\n");
        MappingDryRunReport report = service().run("src-1", PROFILE_V1, "s.jsonl", 1);

        JsonNode json = reportMapper().valueToTree(report);
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "reportId", "sourceId", "createdAt",
                "profileVersion", "profileSyntax", "profileChecksum", "contractVersion", "contractChecksum",
                "profileAccepted", "profileIssues",
                "sampleRef", "sampleChecksum", "sampleLineCount", "requestedLimit",
                "processedCount", "acceptedCount", "quarantinedCount", "acceptedCanonicalChecksums",
                "requiredCoverage", "requiredPositionsTotal", "requiredPositionsOk",
                "enumCoverage", "enumPairsObserved", "enumPairsResolved", "amountRoundedCount",
                "reasonCounts", "warningCounts", "violations", "warnings", "systemErrors", "preview",
                "activationBlocks", "capabilityGaps", "unusedDeclarations", "activationIneligibleReasons",
                "activationEligible");
        assertThat(json.path("activationEligible").isBoolean()).isTrue();
        assertThat(json.path("reasonCounts").isObject()).isTrue();
    }

    // ---------- 真实夹具（只读） ----------

    @Test
    @DisplayName("真实 P5 夹具 + 真实画像：画像装载失败时按 fail-closed 展示（不执行、不写任何东西）")
    void realP5FixtureWithAmbiguousProfileFailsClosed() throws IOException {
        String profile = MappingTestSupport.repoText(FIXTURE_B_V1);
        String sampleText = MappingTestSupport.repoText(B1A_JSONL);
        writeSample("b1a.jsonl", sampleText);

        MappingDryRunReport report = service().run("src-b", profile, "b1a.jsonl", 100);

        assertThat(report.profileAccepted()).isFalse();
        assertThat(report.profileIssues().stream().map(issue -> issue.reason().name()))
                .contains(MappingReason.PROFILE_INVALID.name());
        assertThat(report.processedCount()).isZero();
        assertThat(report.preview()).isEmpty();
        assertThat(report.activationEligible()).isFalse();
        // 事实仍须如实：样本行数/字节哈希与独立重算一致
        assertThat(report.sampleLineCount()).isEqualTo((int) countEventLines(sampleText)).isEqualTo(30);
        assertThat(report.sampleChecksum())
                .isEqualTo(MappingHash.sha256Hex(Files.readAllBytes(MappingTestSupport.repoFile(B1A_JSONL))));
        assertThat(report.profileChecksum()).isEqualTo(sha256(profile));
    }

    // ---------- 受控故障执行器 ----------

    /**
     * 只把"第 2 行执行抛异常"注入进来，其余行仍走冻结执行器。
     * 目的是让规则 13 的"系统异常不冒充坏数据"分支可被独立验证——公开 API 下没有任何样本内容
     * 能让冻结执行器抛异常（它把所有坏数据都变成违例），所以这里必须有一个受控故障点。
     *
     * <p>{@link MappingExecutor} 是 S2-01A 的 {@code final} 冻结核心，不为了好测而拆掉 final，
     * 因此这里用 Mockito（inline mock maker，可代理 final 类）只覆写 {@code execute}，
     * 其余行为仍转发给真执行器。</p>
     */
    private final class ControlledFailureService extends MappingDryRunService {

        ControlledFailureService(Path sampleRoot) {
            super(reports, clock, CONTRACT_FILE.toString(), sampleRoot.toString());
        }

        @Override
        protected MappingExecutor executor(CanonicalContract contract, ObjectMapper mapper) {
            MappingExecutor real = new MappingExecutor(contract, mapper);
            AtomicInteger calls = new AtomicInteger();
            MappingExecutor controlled = mock(MappingExecutor.class);
            when(controlled.execute(any(MappingProfile.class), anyString())).thenAnswer(invocation -> {
                if (calls.incrementAndGet() == 2) {
                    throw new IllegalStateException("受控故障：模拟平台侧执行异常");
                }
                MappingProfile profile = invocation.getArgument(0);
                String rawJson = invocation.getArgument(1);
                return real.execute(profile, rawJson);
            });
            return controlled;
        }
    }
}
