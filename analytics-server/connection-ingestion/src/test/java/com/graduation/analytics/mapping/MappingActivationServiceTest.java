package com.graduation.analytics.mapping;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.mapping.activation.ActiveMappingPointer;
import com.graduation.analytics.mapping.activation.MappingActivationOutcome;
import com.graduation.analytics.mapping.activation.MappingActivationService;
import com.graduation.analytics.mapping.activation.TestActiveMappingPointerStore;
import com.graduation.analytics.mapping.activation.UnavailableActiveMappingPointerStore;
import com.graduation.analytics.mapping.dryrun.InMemoryMappingDryRunReportRepository;
import com.graduation.analytics.mapping.dryrun.MappingDryRunReport;
import com.graduation.analytics.mapping.dryrun.MappingDryRunService;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.graduation.analytics.mapping.MappingActivationTestSupport.CLOCK;
import static com.graduation.analytics.mapping.MappingActivationTestSupport.CONTRACT_REPO_PATH;
import static com.graduation.analytics.mapping.MappingActivationTestSupport.PROFILE_PATH;
import static com.graduation.analytics.mapping.MappingActivationTestSupport.SOURCE_ID;
import static com.graduation.analytics.mapping.MappingActivationTestSupport.SOURCE_TOKEN;
import static com.graduation.analytics.mapping.MappingActivationTestSupport.V1_PROFILE_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S2-03：映射激活生命周期（设计 §7.4「dry-run → 激活指针」）的 fail-closed 矩阵与幂等口径。
 *
 * <p><b>取证口径</b>：除"受控故障执行器"一例外，所有报告都来自**真实 dry-run**（真实契约、真实装载器、
 * 真实执行器、真实样本文件），激活服务也一律用真实实现；只有"指针持久化"用进程内替身
 * （{@link TestActiveMappingPointerStore}）——S2-03.1 起正式存储是表 {@code source_mapping_active}
 * （V21 迁移），其真库往返与锁行为由 MySqlIT 取证，不在本类的证明范围（见替身类注释）。
 * 也就是说：这里量的是"激活判据是否按事实拒绝"，不是"打桩之后流程能不能跑通"。</p>
 *
 * <p>核心不变式：**预览的内容必须就是激活的内容**——判据是字节哈希相等，不是"再跑一遍差不多一样"。</p>
 */
class MappingActivationServiceTest {

    @TempDir
    Path profileRoot;

    @TempDir
    Path sampleRoot;

    private InMemoryMappingDryRunReportRepository reports;
    private MappingDryRunService dryRun;
    private SourceRegistryService sources;
    private TestActiveMappingPointerStore store;

    @BeforeEach
    void setUp() {
        reports = new InMemoryMappingDryRunReportRepository();
        dryRun = new MappingDryRunService(reports, CLOCK,
                MappingTestSupport.repoFile(CONTRACT_REPO_PATH).toString(), sampleRoot.toString());
        sources = mock(SourceRegistryService.class);
        store = MappingActivationTestSupport.store();
    }

    // ---------------------------------------------------------------- ① 正常闭环

    @Test
    @DisplayName("① dry-run → activate：真实报告 + 真实画像字节 ⇒ 指针落库，成功条件十一项全过")
    void dryRunThenActivateClosesTheLoop() throws IOException {
        String profile = MappingActivationTestSupport.v2Profile();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl", MappingActivationTestSupport.rawLine());
        givenSource();

        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profile, "s2-03.jsonl", 100);
        assertThat(report.activationEligible())
                .as("前置：报告必须可激活，否则本用例量不到正常路径：%s", report.activationIneligibleReasons())
                .isTrue();

        MappingActivationOutcome outcome = service().activate(SOURCE_TOKEN, report.reportId(),
                report.profileChecksum(), "admin");

        assertThat(outcome.sourceId()).isEqualTo(SOURCE_ID);
        assertThat(outcome.sourceCode()).isEqualTo(MappingActivationTestSupport.SOURCE_CODE);
        assertThat(outcome.profilePath()).isEqualTo(PROFILE_PATH);
        assertThat(outcome.profileVersion()).isEqualTo("2.0");
        assertThat(outcome.profileChecksum()).isEqualTo(MappingHash.sha256Hex(profile));
        assertThat(outcome.contractVersion()).isEqualTo(report.contractVersion());
        assertThat(outcome.contractChecksum()).isEqualTo(report.contractChecksum());
        assertThat(outcome.reportId()).isEqualTo(report.reportId());
        assertThat(outcome.activatedAt()).isEqualTo(LocalDateTime.parse("2026-09-21T10:20:30"));
        assertThat(outcome.activatedBy()).isEqualTo("admin");
        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.previousProfileChecksum()).isNull();

        // 激活状态必须跨"普通 service 调用"保持：重新构造服务仍读得到同一事实
        ActiveMappingPointer active = store.current(SOURCE_ID).orElseThrow();
        assertThat(active.profileChecksum()).isEqualTo(report.profileChecksum());
        assertThat(service().activate(SOURCE_TOKEN, report.reportId(), report.profileChecksum(), "admin")
                .changed()).isFalse();
    }

    // ---------------------------------------------------------------- ② 资源不存在：404

    @Test
    @DisplayName("② 报告不存在 ⇒ 404 DRY_RUN_REPORT_NOT_FOUND，且不写指针")
    void unknownReportIsNotFound() throws IOException {
        givenSource();

        assertThatThrownBy(() -> service().activate(SOURCE_TOKEN, "dr-20260921102030-deadbeef",
                MappingHash.sha256Hex("whatever"), "admin"))
                .isInstanceOfSatisfying(PlatformBizException.class, e ->
                        assertThat(e.getCode()).isEqualTo(PlatformBizException.DRY_RUN_REPORT_NOT_FOUND));
        assertThat(store.writes()).isEmpty();
    }

    @Test
    @DisplayName("③ 报告归属别的 sourceId ⇒ 一律 404（不泄露'那个源跑没跑过预览'），且不写指针")
    void reportOfAnotherSourceIsNotFound() throws IOException {
        String profile = MappingActivationTestSupport.v2Profile();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl", MappingActivationTestSupport.rawLine());
        givenSource();
        // 报告归属 token "9"（dry-run 不查登记表，token 仅作归属标识）
        MappingDryRunReport report = dryRun.run("9", profile, "s2-03.jsonl", 100);

        assertThatThrownBy(() -> service().activate(SOURCE_TOKEN, report.reportId(), report.profileChecksum(), "admin"))
                .isInstanceOfSatisfying(PlatformBizException.class, e ->
                        assertThat(e.getCode()).isEqualTo(PlatformBizException.DRY_RUN_REPORT_NOT_FOUND));
        assertThat(store.writes()).isEmpty();
    }

    // ---------------------------------------------------------------- ③ 不可激活：409

    @Test
    @DisplayName("④ 报告 profileAccepted=false ⇒ 409 MAPPING_ACTIVATION_INELIGIBLE（不重跑预览，只看报告事实）")
    void rejectedProfileReportIsIneligible() throws IOException {
        String profile = MappingActivationTestSupport.rejectedV2Profile();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl", MappingActivationTestSupport.rawLine());
        givenSource();
        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profile, "s2-03.jsonl", 100);
        assertThat(report.profileAccepted()).isFalse();

        assertConflict(report, PlatformBizException.MAPPING_ACTIVATION_INELIGIBLE);
    }

    @Test
    @DisplayName("⑤ activationEligible=false（零可执行行）⇒ 409：空事实不能当'无违例'用")
    void notEligibleReportIsRefused() throws IOException {
        String profile = MappingActivationTestSupport.v2Profile();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "empty.jsonl", "");
        givenSource();
        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profile, "empty.jsonl", 100);
        assertThat(report.profileAccepted()).isTrue();
        assertThat(report.violations()).isEmpty();
        assertThat(report.activationBlocks()).isEmpty();
        assertThat(report.capabilityGaps()).isEmpty();
        assertThat(report.activationEligible()).isFalse();

        assertConflict(report, PlatformBizException.MAPPING_ACTIVATION_INELIGIBLE);
    }

    @Test
    @DisplayName("⑥ 有违例 ⇒ 409：一条缺必填字段的样本行就足以阻断激活")
    void violationBlocksActivation() throws IOException {
        String profile = MappingActivationTestSupport.v2Profile();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl",
                MappingActivationTestSupport.rawLine(), MappingActivationTestSupport.rawLineMissingRequired());
        givenSource();
        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profile, "s2-03.jsonl", 100);
        assertThat(report.violations()).isNotEmpty();

        assertConflict(report, PlatformBizException.MAPPING_ACTIVATION_INELIGIBLE);
    }

    @Test
    @DisplayName("⑦b 漏映射必填信封字段 ⇒ 409（S2-03.1：此前 Loader 漏读根级 required，这一份会被错误激活）")
    void unmappedEnvelopeTargetBlocksActivation() throws IOException {
        String profile = MappingActivationTestSupport.v2ProfileMissingEnvelopeTarget();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl",
                MappingActivationTestSupport.rawLine(), MappingActivationTestSupport.rawLine());
        givenSource();
        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profile, "s2-03.jsonl", 100);

        // 前置自证：装载成功、无系统异常，阻断同时出现在**两层**（这是修好信封必填解析的直接后果）：
        //   ① 装载层：activationBlocks 点名缺的信封目标；
        //   ② 逐行层：必填信封字段现在真的"必填"了 ⇒ 每行都因取不到 source_system 记 EMPTY_FIELD 违例。
        assertThat(report.profileAccepted()).isTrue();
        assertThat(report.systemErrors()).isEmpty();
        assertThat(report.activationBlocks()).contains("unmappedEnvelope:source_system");
        assertThat(report.violations())
                .as("修复前这里必为空集：Loader 漏读根级 required 时 source_system 根本不是必填")
                .isNotEmpty()
                .allSatisfy(v -> assertThat(v.path()).contains("source_system"));
        assertThat(report.activationEligible()).isFalse();

        assertConflict(report, PlatformBizException.MAPPING_ACTIVATION_INELIGIBLE);
    }

    @Test
    @DisplayName("⑦ 能力缺口阻断 ⇒ 409：v1 兼容画像声明 identityPolicy，只进 capabilityGaps（无激活阻断）也拒绝")
    void capabilityGapBlocksActivation() throws IOException {
        String profile = MappingActivationTestSupport.v1Profile(true);
        MappingActivationTestSupport.writeProfile(profileRoot, V1_PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "v1.jsonl", MappingActivationTestSupport.canonicalLine());
        givenSource(MappingActivationTestSupport.view(SOURCE_ID, V1_PROFILE_PATH));
        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profile, "v1.jsonl", 100);
        assertThat(report.profileAccepted()).isTrue();
        assertThat(report.violations()).isEmpty();
        assertThat(report.activationBlocks())
                .as("本用例要单独量能力缺口：activationBlocks 必须为空，否则量到的是另一条判据")
                .isEmpty();
        assertThat(report.capabilityGaps()).isNotEmpty();

        assertConflict(report, PlatformBizException.MAPPING_ACTIVATION_INELIGIBLE);
    }

    @Test
    @DisplayName("⑧ 系统异常阻断 ⇒ 409：平台侧异常未查清前不得激活（规则 13）")
    void systemErrorBlocksActivation() throws IOException {
        String profile = MappingActivationTestSupport.v2Profile();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl",
                MappingActivationTestSupport.rawLine(), MappingActivationTestSupport.rawLine());
        givenSource();
        MappingDryRunReport report = new ControlledFailureDryRun(reports, sampleRoot)
                .run(SOURCE_TOKEN, profile, "s2-03.jsonl", 100);
        assertThat(report.systemErrors()).hasSize(1);
        assertThat(report.profileAccepted()).isTrue();
        assertThat(report.violations()).isEmpty();

        assertConflict(report, PlatformBizException.MAPPING_ACTIVATION_INELIGIBLE);
    }

    // ---------------------------------------------------------------- ④ 三处哈希对不上：409

    @Test
    @DisplayName("⑨ expectedProfileChecksum ≠ 报告里的 profileChecksum ⇒ 409（调用方手上的画像不是被预览的那一份）")
    void expectedChecksumMismatchIsConflict() throws IOException {
        String profile = MappingActivationTestSupport.v2Profile();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl", MappingActivationTestSupport.rawLine());
        givenSource();
        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profile, "s2-03.jsonl", 100);

        assertThatThrownBy(() -> service().activate(SOURCE_TOKEN, report.reportId(),
                MappingHash.sha256Hex("another-profile"), "admin"))
                .isInstanceOfSatisfying(PlatformBizException.class, e ->
                        assertThat(e.getCode()).isEqualTo(PlatformBizException.MAPPING_ACTIVATION_CHECKSUM_MISMATCH));
        assertThat(store.writes()).isEmpty();
    }

    @Test
    @DisplayName("⑩ 预览之后契约文件被改过（checksum 漂移）⇒ 409 MAPPING_CONTRACT_DRIFT")
    void contractDriftIsConflict() throws IOException {
        String profile = MappingActivationTestSupport.v2Profile();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl", MappingActivationTestSupport.rawLine());
        givenSource();
        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profile, "s2-03.jsonl", 100);

        // 契约内容变了但版本号没变：装载器只校验版本号，因此只有字节哈希能发现漂移。
        // 篡改取"语义等价、字节不同"（末尾多一个空行）：证明判据是字节哈希，而不是"再装载一次看着一样"。
        Path tamperedContract = sampleRoot.resolve("canonical-event.v1.schema.json");
        String contractText = Files.readString(MappingTestSupport.repoFile(CONTRACT_REPO_PATH), StandardCharsets.UTF_8);
        Files.writeString(tamperedContract, contractText + "\n", StandardCharsets.UTF_8);
        MappingActivationService drifted = new MappingActivationService(reports, sources, store, CLOCK,
                profileRoot.toString(), tamperedContract.toString());

        assertThatThrownBy(() -> drifted.activate(SOURCE_TOKEN, report.reportId(), report.profileChecksum(), "admin"))
                .isInstanceOfSatisfying(PlatformBizException.class, e ->
                        assertThat(e.getCode()).isEqualTo(PlatformBizException.MAPPING_CONTRACT_DRIFT));
        assertThat(store.writes()).isEmpty();
    }

    @Test
    @DisplayName("⑪ 预览之后画像文件被改过（字节 sha256 变化）⇒ 409 MAPPING_PROFILE_CHANGED")
    void profileChangedAfterPreviewIsConflict() throws IOException {
        String profile = MappingActivationTestSupport.v2Profile();
        Path file = MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl", MappingActivationTestSupport.rawLine());
        givenSource();
        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profile, "s2-03.jsonl", 100);

        // 换成一个仍然合法、仍然可执行的画像：装载不会失败，只有字节哈希能发现"预览的不是这一份"
        Files.writeString(file, MappingActivationTestSupport.v2ProfileMissingRequiredPayload(),
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service().activate(SOURCE_TOKEN, report.reportId(), report.profileChecksum(), "admin"))
                .isInstanceOfSatisfying(PlatformBizException.class, e ->
                        assertThat(e.getCode()).isEqualTo(PlatformBizException.MAPPING_PROFILE_CHANGED));
        assertThat(store.writes()).isEmpty();
    }

    // ---------------------------------------------------------------- ⑤ 幂等与替换

    @Test
    @DisplayName("⑫ 同 sourceId + 同 profileChecksum 重复激活 ⇒ 幂等成功，不产生第二个指针、不覆盖激活时间")
    void repeatedActivationIsIdempotent() throws IOException {
        String profile = MappingActivationTestSupport.v2Profile();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl", MappingActivationTestSupport.rawLine());
        givenSource();
        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profile, "s2-03.jsonl", 100);

        MappingActivationOutcome first = service().activate(SOURCE_TOKEN, report.reportId(),
                report.profileChecksum(), "admin");
        // 第二张报告（同画像同契约，不同 reportId）也要幂等：判据是内容哈希，不是 reportId
        MappingDryRunReport second = dryRun.run(SOURCE_TOKEN, profile, "s2-03.jsonl", 100);
        MappingActivationOutcome repeat = service().activate(SOURCE_TOKEN, second.reportId(),
                second.profileChecksum(), "dev1");

        assertThat(first.changed()).isTrue();
        assertThat(repeat.changed()).isFalse();
        assertThat(repeat.activatedAt()).isEqualTo(first.activatedAt());
        assertThat(repeat.activatedBy()).isEqualTo("admin");
        assertThat(repeat.reportId()).isEqualTo(report.reportId());
        assertThat(store.writes()).hasSize(1);
        assertThat(store.current(SOURCE_ID)).get()
                .extracting(ActiveMappingPointer::profileChecksum)
                .isEqualTo(first.profileChecksum());
    }

    @Test
    @DisplayName("⑬ 不同 checksum ⇒ 显式替换，并在结果里留下'上一个 active 是哪个 checksum'")
    void differentChecksumReplacesExplicitly() throws IOException {
        String first = MappingActivationTestSupport.v2Profile();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, first);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl", MappingActivationTestSupport.rawLine());
        givenSource();
        MappingDryRunReport firstReport = dryRun.run(SOURCE_TOKEN, first, "s2-03.jsonl", 100);
        MappingActivationOutcome activated = service().activate(SOURCE_TOKEN, firstReport.reportId(),
                firstReport.profileChecksum(), "admin");

        // 换成另一份**合法且可执行**的画像（缺必填映射会不可激活，所以改一处非必填差异：来源单位声明）
        String second = first.replace("\"paid_fen\": \"FEN\"", "\"paid_fen\": \"FEN\", \"extra_fen\": \"FEN\"");
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, second);
        MappingDryRunReport secondReport = dryRun.run(SOURCE_TOKEN, second, "s2-03.jsonl", 100);
        assertThat(secondReport.activationEligible())
                .as("前置：第二份画像也要可激活：%s", secondReport.activationIneligibleReasons())
                .isTrue();

        MappingActivationOutcome replaced = service().activate(SOURCE_TOKEN, secondReport.reportId(),
                secondReport.profileChecksum(), "dev1");

        assertThat(replaced.changed()).isTrue();
        assertThat(replaced.profileChecksum()).isEqualTo(MappingHash.sha256Hex(second));
        assertThat(replaced.previousProfileChecksum()).isEqualTo(activated.profileChecksum());
        assertThat(store.writes()).hasSize(2);
        assertThat(store.current(SOURCE_ID)).get()
                .extracting(ActiveMappingPointer::profileChecksum, ActiveMappingPointer::activatedBy)
                .containsExactly(MappingHash.sha256Hex(second), "dev1");
    }

    // ---------------------------------------------------------------- ⑥ 指针持久化缺口

    @Test
    @DisplayName("⑭ 正式持久化未落地 ⇒ 501 MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE，绝不假装激活成功")
    void unavailablePersistenceFailsClosed() throws IOException {
        String profile = MappingActivationTestSupport.v2Profile();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl", MappingActivationTestSupport.rawLine());
        givenSource();
        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profile, "s2-03.jsonl", 100);

        MappingActivationService productionShaped = new MappingActivationService(reports, sources,
                new UnavailableActiveMappingPointerStore(), CLOCK, profileRoot.toString(),
                MappingTestSupport.repoFile(CONTRACT_REPO_PATH).toString());

        assertThatThrownBy(() -> productionShaped.activate(SOURCE_TOKEN, report.reportId(),
                report.profileChecksum(), "admin"))
                .isInstanceOfSatisfying(PlatformBizException.class, e -> assertThat(e.getCode())
                        .isEqualTo(PlatformBizException.MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE));
    }

    // ---------------------------------------------------------------- ⑦ 激活不碰采集侧状态

    @Test
    @DisplayName("⑮ 激活只写指针：不碰 canonical/quarantine/checkpoint，且服务依赖里没有任何采集侧写入者")
    void activationTouchesNoCollectionState() throws IOException {
        String profile = MappingActivationTestSupport.v2Profile();
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profile);
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl", MappingActivationTestSupport.rawLine());
        givenSource();
        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profile, "s2-03.jsonl", 100);
        List<String> before = tree(profileRoot);

        service().activate(SOURCE_TOKEN, report.reportId(), report.profileChecksum(), "admin");

        assertThat(tree(profileRoot)).as("激活不得改动画像文件树").isEqualTo(before);
        assertThat(reports.find(report.reportId())).as("激活不得产生/改动 dry-run 报告").isPresent();
        assertThat(store.writes()).hasSize(1);

        // 结构性证据（可机械复核，且能防未来回归）：激活服务的依赖集合里没有采集侧写入者
        List<String> deps = Stream.of(MappingActivationService.class.getDeclaredConstructors())
                .flatMap(c -> Stream.of(c.getParameterTypes()))
                .map(Class::getSimpleName)
                .collect(Collectors.toList());
        assertThat(deps).noneMatch(name -> name.matches(".*(Ingestion|Batch|Checkpoint|Quarantine|Canonical|Landing).*"));
    }

    // ---------------------------------------------------------------- helpers

    private MappingActivationService service() {
        return new MappingActivationService(reports, sources, store, CLOCK, profileRoot.toString(),
                MappingTestSupport.repoFile(CONTRACT_REPO_PATH).toString());
    }

    private void givenSource() {
        givenSource(MappingActivationTestSupport.view(SOURCE_ID, PROFILE_PATH));
    }

    private void givenSource(SourceRegistryView view) {
        when(sources.get(SOURCE_ID)).thenReturn(view);
    }

    private void assertConflict(MappingDryRunReport report, String code) {
        assertThatThrownBy(() -> service().activate(SOURCE_TOKEN, report.reportId(), report.profileChecksum(), "admin"))
                .isInstanceOfSatisfying(PlatformBizException.class, e -> assertThat(e.getCode()).isEqualTo(code));
        assertThat(store.writes()).as("被拒绝的激活不得写指针").isEmpty();
        assertThat(store.current(SOURCE_ID)).isEmpty();
    }

    private static List<String> tree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            List<String> entries = new ArrayList<>();
            walk.forEach(path -> entries.add(root.relativize(path).toString().replace('\\', '/')));
            return entries;
        }
    }

    /**
     * 受控故障 dry-run：只把"第 2 行执行抛异常"注入进来（与 S2-01B 的
     * {@code MappingDryRunServiceTest.ControlledFailureService} 同一手法）。
     * 公开 API 下没有任何样本内容能让冻结执行器抛异常，所以规则 13 的分支必须有一个受控故障点。
     */
    private static final class ControlledFailureDryRun extends MappingDryRunService {

        private final List<String> seen = new ArrayList<>();

        ControlledFailureDryRun(InMemoryMappingDryRunReportRepository reports, Path sampleRoot) {
            super(reports, CLOCK, MappingTestSupport.repoFile(CONTRACT_REPO_PATH).toString(),
                    sampleRoot.toString());
        }

        @Override
        protected MappingExecutor executor(CanonicalContract contract, com.fasterxml.jackson.databind.ObjectMapper mapper) {
            MappingExecutor real = new MappingExecutor(contract, mapper);
            MappingExecutor controlled = mock(MappingExecutor.class);
            when(controlled.execute(org.mockito.ArgumentMatchers.any(MappingProfile.class),
                    org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
                seen.add(invocation.getArgument(1));
                if (seen.size() == 2) {
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
