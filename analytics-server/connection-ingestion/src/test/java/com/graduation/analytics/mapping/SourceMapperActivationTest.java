package com.graduation.analytics.mapping;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.mapping.activation.MappingActivationOutcome;
import com.graduation.analytics.mapping.activation.MappingActivationService;
import com.graduation.analytics.mapping.activation.TestActiveMappingPointerStore;
import com.graduation.analytics.mapping.dryrun.InMemoryMappingDryRunReportRepository;
import com.graduation.analytics.mapping.dryrun.MappingDryRunReport;
import com.graduation.analytics.mapping.dryrun.MappingDryRunService;
import com.graduation.analytics.mapping.ingest.SourceMapper;
import com.graduation.analytics.mapping.ingest.SourceMapping;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static com.graduation.analytics.mapping.MappingActivationTestSupport.CLOCK;
import static com.graduation.analytics.mapping.MappingActivationTestSupport.CONTRACT_REPO_PATH;
import static com.graduation.analytics.mapping.MappingActivationTestSupport.PROFILE_PATH;
import static com.graduation.analytics.mapping.MappingActivationTestSupport.SOURCE_CODE;
import static com.graduation.analytics.mapping.MappingActivationTestSupport.SOURCE_ID;
import static com.graduation.analytics.mapping.MappingActivationTestSupport.SOURCE_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S2-03 采集侧接线：正式采集**只使用当前已激活**的那一份画像。
 *
 * <p>量的是采集链路的取用判据，不是重测映射语义：</p>
 * <ul>
 *   <li>磁盘上存在一份能装载、未被阻断的 v2 画像 ≠ 它已激活 ⇒ {@code MAPPING_NOT_ACTIVE}(409)；</li>
 *   <li>已激活画像被换掉（写新文件/改内容）⇒ 不会自动改用新的（禁止 latest-wins）⇒
 *       {@code MAPPING_ACTIVE_PROFILE_DRIFT}(409)；</li>
 *   <li>显式激活另一份画像之后 ⇒ 采集立刻按新的激活内容走（哈希随之切换）；</li>
 *   <li>v1 只读兼容直通不受激活门影响（种子源 mock-mall 行为不变）。</li>
 * </ul>
 *
 * <p>画像 A/B 的差异只用"多一条未被使用的金额单位声明"：由 {@code MappingActivationServiceTest}
 * 第⑬例实测证明这类差异**不产生能力缺口、不影响 activationEligible**，因此两份都是真实可激活的画像，
 * 差异只体现在字节哈希上——正是本用例要观测的量。</p>
 */
class SourceMapperActivationTest {

    private static final String PROFILE_PATH_B = "profiles/raw-a.v2.b.json";

    @TempDir
    Path profileRoot;

    @TempDir
    Path sampleRoot;

    private TestActiveMappingPointerStore store;

    @BeforeEach
    void setUp() {
        store = MappingActivationTestSupport.store();
    }

    // ---------------------------------------------------------------- ⑰ 没有激活记录就不映射

    @Test
    @DisplayName("⑰ 磁盘上有可装载的 v2 画像但从未激活 ⇒ MAPPING_NOT_ACTIVE；采集只读指针、绝不写指针")
    void neverActivatedProfileIsRefused() throws IOException {
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profileA());

        assertThatThrownBy(() -> mapper(store).prepare(view(PROFILE_PATH)))
                .isInstanceOfSatisfying(PlatformBizException.class, e ->
                        assertThat(e.getCode()).isEqualTo(PlatformBizException.MAPPING_NOT_ACTIVE))
                .hasMessageContaining("从未激活过");
        assertThat(store.writes()).as("采集链路不得顺手写激活指针（激活只有显式 activate 一条路）").isEmpty();
    }

    // ---------------------------------------------------------------- ⑱ 禁止 latest-wins

    @Test
    @DisplayName("⑱ 磁盘出现更新的画像但不激活 ⇒ 不采用新的（MAPPING_ACTIVE_PROFILE_DRIFT），指针保持原样")
    void newerProfileOnDiskIsNotAdopted() throws IOException {
        store.seed(MappingActivationTestSupport.pointer(SOURCE_ID, MappingHash.sha256Hex(profileA()),
                MappingHash.sha256Hex(contractText())));
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profileB());

        assertThatThrownBy(() -> mapper(store).prepare(view(PROFILE_PATH)))
                .isInstanceOfSatisfying(PlatformBizException.class, e -> assertThat(e.getCode())
                        .isEqualTo(PlatformBizException.MAPPING_ACTIVE_PROFILE_DRIFT))
                .hasMessageContaining("已激活");
        assertThat(store.current(SOURCE_ID)).get()
                .as("拒绝之后激活指针不得被自动改写（否则就是隐式 latest-wins）")
                .extracting(p -> p.profileChecksum())
                .isEqualTo(MappingHash.sha256Hex(profileA()));
        assertThat(store.writes()).isEmpty();
    }

    @Test
    @DisplayName("⑱b 登记表被指到另一个画像文件（那一份没被激活过）⇒ MAPPING_NOT_ACTIVE，不静默改用它")
    void registryPointingAtUnactivatedFileIsRefused() throws IOException {
        store.seed(MappingActivationTestSupport.pointer(SOURCE_ID, MappingHash.sha256Hex(profileA()),
                MappingHash.sha256Hex(contractText())));
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profileA());
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH_B, profileB());

        assertThatThrownBy(() -> mapper(store).prepare(view(PROFILE_PATH_B)))
                .isInstanceOfSatisfying(PlatformBizException.class, e -> assertThat(e.getCode())
                        .isEqualTo(PlatformBizException.MAPPING_NOT_ACTIVE))
                .hasMessageContaining("profile_path 与已激活记录不一致");
        assertThat(store.current(SOURCE_ID)).get()
                .extracting(p -> p.profilePath())
                .isEqualTo(PROFILE_PATH);
    }

    // ---------------------------------------------------------------- ⑳ 激活切换后读新 checksum

    @Test
    @DisplayName("⑳ 显式激活另一份画像后 ⇒ 采集按新的激活内容走，SourceMapping 的 checksum 随之切换")
    void activationSwitchChangesWhatIngestionUses() throws IOException {
        // 先激活 A，采集读的就是 A
        store.seed(MappingActivationTestSupport.pointer(SOURCE_ID, MappingHash.sha256Hex(profileA()),
                MappingHash.sha256Hex(contractText())));
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profileA());
        SourceMapping before = mapper(store).prepare(view(PROFILE_PATH));
        assertThat(before.profileChecksum()).isEqualTo(MappingHash.sha256Hex(profileA()));

        // 走真实闭环：写 B（同一路径）→ dry-run → activate
        MappingActivationTestSupport.writeProfile(profileRoot, PROFILE_PATH, profileB());
        MappingActivationTestSupport.writeSample(sampleRoot, "s2-03.jsonl", MappingActivationTestSupport.rawLine());
        InMemoryMappingDryRunReportRepository reports = new InMemoryMappingDryRunReportRepository();
        MappingDryRunService dryRun = new MappingDryRunService(reports, CLOCK,
                MappingTestSupport.repoFile(CONTRACT_REPO_PATH).toString(), sampleRoot.toString());
        SourceRegistryService sources = mock(SourceRegistryService.class);
        when(sources.get(SOURCE_ID)).thenReturn(view(PROFILE_PATH));
        MappingDryRunReport report = dryRun.run(SOURCE_TOKEN, profileB(), "s2-03.jsonl", 100);
        assertThat(report.activationEligible())
                .as("前置：B 必须是可激活画像（否则量到的是另一条判据）：%s", report.activationIneligibleReasons())
                .isTrue();
        MappingActivationService activation = new MappingActivationService(reports, sources, store, CLOCK,
                profileRoot.toString(), MappingTestSupport.repoFile(CONTRACT_REPO_PATH).toString());

        MappingActivationOutcome outcome = activation.activate(SOURCE_TOKEN, report.reportId(),
                report.profileChecksum(), "admin");
        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.previousProfileChecksum()).isEqualTo(MappingHash.sha256Hex(profileA()));

        // 采集侧立刻按新激活的 checksum 走（同一次调用链里没有缓存到旧画像）
        SourceMapping after = mapper(store).prepare(view(PROFILE_PATH));
        assertThat(after.profileChecksum()).isEqualTo(MappingHash.sha256Hex(profileB()));
        assertThat(after.profileVersion()).isEqualTo("2.0");
    }

    // ---------------------------------------------------------------- v1 直通不受影响

    @Test
    @DisplayName("v1 只读兼容画像（种子源现状）⇒ 仍然直通：激活门不改变既有采集行为")
    void v1LegacyPassthroughIsUnaffected() {
        SourceRegistryView seed = new SourceRegistryView(1L, "mock-mall", "种子源", "LOCAL_FILE",
                "analytics-server/source-profiles/mock-mall.v1.json", "Asia/Shanghai", "CNY",
                "ACTIVE", "1.0", true, null, null, "mock_mall");

        SourceMapping mapping = mapperAtRepoRoot(store).prepare(seed);

        assertThat(mapping.applied()).as("v1 在激活门之前就返回 legacy，不需要任何激活记录").isFalse();
        assertThat(store.writes()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private SourceMapper mapper(TestActiveMappingPointerStore pointers) {
        return new SourceMapper(profileRoot.toString(),
                MappingTestSupport.repoFile(CONTRACT_REPO_PATH).toString(), CLOCK,
                MappingJson.mapper(), pointers);
    }

    private SourceMapper mapperAtRepoRoot(TestActiveMappingPointerStore pointers) {
        return new SourceMapper(MappingTestSupport.repoFile(".").toString(),
                MappingTestSupport.repoFile(CONTRACT_REPO_PATH).toString(), CLOCK,
                MappingJson.mapper(), pointers);
    }

    private static SourceRegistryView view(String profilePath) {
        return MappingActivationTestSupport.view(SOURCE_ID, profilePath);
    }

    private static String profileA() {
        return MappingActivationTestSupport.v2Profile();
    }

    /** 与 A 语义等价、字节不同（多一条未被使用的金额单位声明，实测不影响 activationEligible）。 */
    private static String profileB() {
        return profileA().replace("\"paid_fen\": \"FEN\"", "\"paid_fen\": \"FEN\", \"extra_fen\": \"FEN\"");
    }

    private static String contractText() {
        try {
            return java.nio.file.Files.readString(MappingTestSupport.repoFile(CONTRACT_REPO_PATH));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
