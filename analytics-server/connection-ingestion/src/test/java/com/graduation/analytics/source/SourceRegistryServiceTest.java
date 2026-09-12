package com.graduation.analytics.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.source.dto.SourceChangeOutcome;
import com.graduation.analytics.source.dto.SourceCheckResult;
import com.graduation.analytics.source.dto.SourceRegistryCreateReq;
import com.graduation.analytics.source.dto.SourceRegistryUpdateReq;
import com.graduation.analytics.source.dto.SourceRegistryView;
import com.graduation.analytics.source.entity.SourceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-03 源登记服务语义（L0，不连库；E2）。用例逐条对应 D-035 的裁决与任务书 §5 的必测清单。
 *
 * <p>数据库语义（行锁、事务回滚、真并发）不在此断言——由 E3 真 HTTP + 真 MySQL 取证。</p>
 */
class SourceRegistryServiceTest {

    private static final String SEED_CODE = "mock-mall";
    private static final String SEED_PATH = "analytics-server/source-profiles/mock-mall.v1.json";
    private static final String PROBE_CODE = "p1-03-probe-1";
    private static final String PROBE_PATH = "analytics-server/source-profiles/p1-03-probe-1.v1.json";

    private final SourceRegistryTestSupport.InMemoryStore store = new SourceRegistryTestSupport.InMemoryStore();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path profileRoot;

    private SourceRegistryService service() {
        return new SourceRegistryServiceImpl(
                SourceRegistryTestSupport.mapper(store),
                SourceRegistryTestSupport.bindingMapper(store),
                new SourceProfileValidator(profileRoot.toString(), objectMapper));
    }

    /** 造一行与真库 V16 种子行**同形**的实体（字段逐字对齐 V16 的 INSERT），但不落库 */
    private SourceRegistry rowOf(String sourceCode, String status) {
        SourceRegistry seed = new SourceRegistry();
        seed.setSourceCode(sourceCode);
        seed.setDisplayName("参考商城（源 A）");
        seed.setIngestMode("FILE");
        seed.setProfilePath(SEED_PATH);
        seed.setTimezone("Asia/Shanghai");
        seed.setCurrency("CNY");
        seed.setStatus(status);
        seed.setProfileVersion("1.0");
        // 与真库 V16 种子行同形：V18 回填后种子行的 warehouse_prefix = 'dw'
        // （P2-07 / D-072：源 A 的库名逐字不变，故零数据迁移）
        seed.setWarehousePrefix("dw");
        return seed;
    }

    /** 种入与真库 V16 种子行**同形**的行（id 由内存表分配） */
    private SourceRegistry seedRow() {
        return store.insert(rowOf(SEED_CODE, SourceRegistry.STATUS_ACTIVE));
    }

    /** 与真库 `runtime_profile` id=1 ACTIVE 等价的内存状态：绑定到种子源 */
    private void bindSeedAsCurrent(Long seedId) {
        store.bind(1L, seedId);
    }

    /** 写一份「设计 §4.2 顶层必备键齐全」的最小合法画像（不占用 P3-01 的 mock-mall.v1.json） */
    private void writeValidProfile(String relativePath, String sourceCode, String profileVersion) {
        Path target = profileRoot.resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, """
                    {
                      "profileVersion": "%s",
                      "sourceCode": "%s",
                      "canonical": { "schemaVersion": "1.0" },
                      "eventTypeMapping": { "product_viewed": "view" },
                      "fieldMapping": { "buyer_id": "user_id" },
                      "enumSemantics": { "behavior": { "browse": "view" } },
                      "identityPolicy": { "user": { "rawField": "buyer_id", "shape": "UUID", "surrogate": "HASH64" } },
                      "timePolicy": { "field": "created_at", "formats": ["ISO_OFFSET_DATE_TIME"] },
                      "quarantinePolicy": { "unknownEventType": "QUARANTINE", "unknownField": "KEEP_IN_PAYLOAD" }
                    }
                    """.formatted(profileVersion, sourceCode), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private SourceRegistryCreateReq createReq(String code, String path, String status) {
        return new SourceRegistryCreateReq(code, "探针源 " + code, "FILE", path,
                "Asia/Shanghai", "CNY", status, "1.0", "probe");
    }

    private static String codeOf(Throwable e) {
        return ((PlatformBizException) e).getCode();
    }

    // ---------------- 读路径 ----------------

    @Test
    @DisplayName("list：种子源正常返回，current 派生自 ACTIVE runtime_profile.source_id（不加表列）")
    void listDerivesCurrentFromActiveProfileBinding() {
        SourceRegistry seed = seedRow();
        bindSeedAsCurrent(seed.getId());

        List<SourceRegistryView> rows = service().list();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).sourceCode()).isEqualTo(SEED_CODE);
        assertThat(rows.get(0).current()).isTrue();
        assertThat(rows.get(0).profilePath()).isEqualTo(SEED_PATH);
    }

    @Test
    @DisplayName("list：读取层接受 DRAFT/DISABLED（D-035 裁决 1：并集不删减，读到不得报错）")
    void listAcceptsDraftAndDisabledStatuses() {
        // 三行：一行 ACTIVE（同真库种子）+ 一行 DRAFT + 一行 DISABLED，共 3 行
        seedRow();
        store.insert(rowOf("draft-src", SourceRegistry.STATUS_DRAFT));
        store.insert(rowOf("retired-src", SourceRegistry.STATUS_DISABLED));

        List<SourceRegistryView> rows = service().list();

        assertThat(rows).extracting(SourceRegistryView::status)
                .containsExactlyInAnyOrder("DRAFT", "ACTIVE", "DISABLED");
    }

    @Test
    @DisplayName("get 不存在 id → SOURCE_NOT_FOUND（不是 PARAM_INVALID）")
    void getMissingIdThrowsSourceNotFound() {
        assertThatThrownBy(() -> service().get(999999L))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_NOT_FOUND));
    }

    // ---------------- create ----------------

    @Test
    @DisplayName("create 缺省 status → DRAFT（ACTIVE 只能经 activate 得到）")
    void createDefaultsToDraft() {
        SourceChangeOutcome outcome = service()
                .create(createReq(PROBE_CODE, PROBE_PATH, null));

        assertThat(outcome.source().status()).isEqualTo(SourceRegistry.STATUS_DRAFT);
        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.source().current()).isFalse();
    }

    @Test
    @DisplayName("create 显式 PAUSED 允许；显式 ACTIVE 被拒（唯一带画像校验的入口是 activate）")
    void createRejectsActiveButAcceptsPaused() {
        assertThat(service().create(createReq(PROBE_CODE, PROBE_PATH, "PAUSED"))
                .source().status()).isEqualTo(SourceRegistry.STATUS_PAUSED);

        assertThatThrownBy(() -> service().create(createReq("probe-x", PROBE_PATH, "ACTIVE")))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID));
        assertThatThrownBy(() -> service().create(createReq("probe-y", PROBE_PATH, "DISABLED")))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID));
        assertThat(store.sourceCodes()).containsExactly(PROBE_CODE);
    }

    @Test
    @DisplayName("create 重复 source_code → PARAM_INVALID（唯一键语义在服务层先拦）")
    void createRejectsDuplicateSourceCode() {
        seedRow();
        assertThatThrownBy(() -> service().create(createReq(SEED_CODE, SEED_PATH, null)))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID));
    }

    @Test
    @DisplayName("create 拒绝绝对路径与 .. 逃逸（profile_path 必须是仓库相对路径）")
    void createRejectsIllegalProfilePaths() {
        assertThatThrownBy(() -> service().create(
                createReq("probe-abs", "C:/Develop_code/GraduationProject/x.json", null)))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID));
        assertThatThrownBy(() -> service().create(
                createReq("probe-up", "../../etc/passwd", null)))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID));
        assertThatThrownBy(() -> service().create(
                createReq("probe-back", "analytics-server\\source-profiles\\x.json", null)))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID));
        assertThat(store.sourceCodes()).isEmpty();
    }

    @Test
    @DisplayName("create 拒绝非 FILE 接入方式：JDBC/HTTP 是预留能力，登记层 fail-closed 不假装支持")
    void createRejectsUnimplementedIngestMode() {
        SourceRegistryCreateReq req = new SourceRegistryCreateReq(
                "probe-jdbc", "JDBC 探针", "JDBC", PROBE_PATH, "Asia/Shanghai", "CNY", null, "1.0", "probe");
        assertThatThrownBy(() -> service().create(req))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID));
    }

    // ---------------- update ----------------

    @Test
    @DisplayName("update 改 source_code → SOURCE_CODE_IMMUTABLE，且落库值不变")
    void updateSourceCodeIsImmutable() {
        SourceRegistry seed = seedRow();
        SourceRegistryUpdateReq req = new SourceRegistryUpdateReq(
                "renamed-mall", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service().update(seed.getId(), req))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_CODE_IMMUTABLE));
        assertThat(store.selectById(seed.getId()).getSourceCode()).isEqualTo(SEED_CODE);
    }

    @Test
    @DisplayName("update 目标不存在 → SOURCE_NOT_FOUND")
    void updateMissingIdThrowsSourceNotFound() {
        SourceRegistryUpdateReq req = new SourceRegistryUpdateReq(
                null, "新名字", null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service().update(404L, req))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("update 正常字段落库（display_name/profile_path/timezone/currency/profile_version）")
    void updateAppliesNormalFields() {
        SourceRegistry seed = seedRow();
        writeValidProfile(PROBE_PATH, PROBE_CODE, "1.0");
        SourceRegistryUpdateReq req = new SourceRegistryUpdateReq(
                null, "参考商城（改名后）", null, PROBE_PATH, "UTC", "USD", "1.0", null, null);

        SourceChangeOutcome outcome = service().update(seed.getId(), req);

        assertThat(outcome.source().displayName()).isEqualTo("参考商城（改名后）");
        assertThat(outcome.source().profilePath()).isEqualTo(PROBE_PATH);
        assertThat(outcome.source().timezone()).isEqualTo("UTC");
        assertThat(outcome.source().currency()).isEqualTo("USD");
        SourceRegistry stored = store.selectById(seed.getId());
        assertThat(stored.getDisplayName()).isEqualTo("参考商城（改名后）");
        assertThat(stored.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("update 不接受 status：状态只能经 activate/pause 变更（ACTIVE 必已过画像校验的全局不变量）")
    void updateRejectsStatusChange() {
        SourceRegistry seed = seedRow();
        SourceRegistryUpdateReq req = new SourceRegistryUpdateReq(
                null, null, null, null, null, null, null, "PAUSED", null);

        assertThatThrownBy(() -> service().update(seed.getId(), req))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID));
        assertThat(store.selectById(seed.getId()).getStatus()).isEqualTo(SourceRegistry.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("update 同样拒绝绝对路径 / .. 逃逸")
    void updateRejectsIllegalProfilePath() {
        SourceRegistry seed = seedRow();
        SourceRegistryUpdateReq absolute = new SourceRegistryUpdateReq(
                null, null, null, "/tmp/x.json", null, null, null, null, null);
        SourceRegistryUpdateReq escape = new SourceRegistryUpdateReq(
                null, null, null, "a/../../b.json", null, null, null, null, null);

        assertThatThrownBy(() -> service().update(seed.getId(), absolute))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID));
        assertThatThrownBy(() -> service().update(seed.getId(), escape))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID));
    }

    @Test
    @DisplayName("拒绝绝对路径时**不回显该值**（真机验收发现：原消息把 D:\\... 原样带回响应与审计）")
    void rejectedAbsolutePathIsNotEchoedBack() {
        SourceRegistry seed = seedRow();
        String absolute = "D:\\Develop_code\\GraduationProject\\analytics-server\\source-profiles\\x.v1.json";
        SourceRegistryUpdateReq req = new SourceRegistryUpdateReq(
                null, null, null, absolute, null, null, null, null, null);

        assertThatThrownBy(() -> service().update(seed.getId(), req))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> {
                    assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID);
                    assertThat(e.getMessage()).doesNotContain("D:\\Develop_code", "GraduationProject", "x.v1.json");
                });
        // create 走同一条策略实现，也必须不回显
        assertThatThrownBy(() -> service().create(new SourceRegistryCreateReq(
                "probe-abs", "绝对路径探针", "FILE", absolute, "Asia/Shanghai", "CNY", null, "1.0", "probe")))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("D:\\Develop_code", "x.v1.json"));
    }

    // ---------------- test（只读） ----------------

    @Test
    @DisplayName("test 种子源：画像文件未落盘 → 明细如实报缺，ok=false，且**不写状态**")
    void testSeedSourceReportsMissingProfileAndWritesNothing() {
        SourceRegistry seed = seedRow();
        bindSeedAsCurrent(seed.getId());
        LocalDateTime updatedBefore = store.selectById(seed.getId()).getUpdatedAt();

        SourceCheckResult result = service().test(seed.getId());

        assertThat(result.ok()).isFalse();
        assertThat(result.items())
                .anySatisfy(item -> {
                    assertThat(item.name()).isEqualTo("profile_file_exists");
                    assertThat(item.passed()).isFalse();
                    assertThat(item.detail()).contains(SEED_PATH);
                });
        assertThat(result.items())
                .filteredOn(item -> item.name().equals("profile_json_object"))
                .allSatisfy(item -> assertThat(item.applicable()).isFalse());
        // 不变量：没评估的项不许报"通过"（否则 response 里会出现"没读文件却 passed=true"的假绿）
        assertThat(result.items()).filteredOn(item -> !item.applicable())
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item.passed()).isFalse());
        assertThat(store.selectById(seed.getId()).getStatus()).isEqualTo(SourceRegistry.STATUS_ACTIVE);
        assertThat(store.selectById(seed.getId()).getUpdatedAt()).isEqualTo(updatedBefore);
        assertThat(store.activeSourceId()).isEqualTo(seed.getId());
    }

    @Test
    @DisplayName("test 合法画像：逐项通过（含 §4.2 顶层必备键齐全 + 状态允许变更）")
    void testValidProfilePassesAllItems() throws IOException {
        writeValidProfile(PROBE_PATH, PROBE_CODE, "1.0");
        SourceRegistry probe = store.insert(probeRow(PROBE_PATH, "1.0"));

        SourceCheckResult result = service().test(probe.getId());

        assertThat(result.items()).extracting(SourceCheckResult.CheckItem::name)
                .containsExactly("profile_path_policy", "profile_file_exists", "profile_json_object",
                        "profile_source_code_matches", "profile_profile_version_matches",
                        "profile_required_top_level_keys", "status_transition_allowed");
        assertThat(result.items()).allSatisfy(item ->
                assertThat(item.passed()).as("检查项 %s 应通过：%s", item.name(), item.detail()).isTrue());
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("test 不存在的源 → SOURCE_NOT_FOUND")
    void testMissingIdThrowsSourceNotFound() {
        assertThatThrownBy(() -> service().test(77L))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("test 的 ok 与 activate 同源：DISABLED 源 status_transition_allowed=false 且 ok=false")
    void testDisabledSourceReportsStatusNotMutable() {
        // 画像合法 ⇒ 前 6 项全过；唯一不通过的是状态项。若这里返回 ok=true，
        // 就会出现「校验说行、激活报 PARAM_INVALID」的口径分裂（本用例就是这条不变量的看门人）。
        SourceRegistry seed = seedRow();
        bindSeedAsCurrent(seed.getId());
        writeValidProfile(PROBE_PATH, PROBE_CODE, "1.0");
        SourceRegistry probe = store.insert(probeRow(PROBE_PATH, "1.0"));
        // 内存替身的 selectById 返回**副本**（与真库一致：改副本不等于改库），
        // 所以置为 DISABLED 必须走一次真正的更新，否则本用例会假绿。
        SourceRegistry disabled = store.selectById(probe.getId());
        disabled.setStatus(SourceRegistry.STATUS_DISABLED);
        store.updateById(disabled);

        SourceCheckResult result = service().test(probe.getId());

        assertThat(result.items()).filteredOn(item -> item.name().equals("status_transition_allowed"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.passed()).isFalse();
                    assertThat(item.applicable()).isTrue();
                    assertThat(item.detail()).contains(SourceRegistry.STATUS_DISABLED);
                });
        assertThat(result.items()).filteredOn(item -> item.name().startsWith("profile_"))
                .allSatisfy(item -> assertThat(item.passed()).isTrue());
        assertThat(result.ok()).isFalse();

        // 同一个源：activate 必须同样拒绝，且拒绝理由必须是**状态**而不是别的 PARAM_INVALID 分支
        // （库里已有 ACTIVE 绑定，所以"没有 ACTIVE 运行环境"那条分支不可能被走到）
        assertThatThrownBy(() -> service().activate(probe.getId()))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> {
                    assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID);
                    assertThat(e.getMessage()).contains("DISABLED");
                });
        assertThat(store.selectById(probe.getId()).getStatus()).isEqualTo(SourceRegistry.STATUS_DISABLED);
    }

    @Test
    @DisplayName("test 的每项 detail 只讲自己：版本不一致时 sourceCode 项不得自称『不一致』")
    void testItemDetailsDescribeTheirOwnCondition() throws IOException {
        // 真机验收发现：原先 5 个 profile_* 项共用 check.detail()，于是
        // profile_profile_version_matches=true 却带着 detail「画像 sourceCode 与登记不一致」——
        // 输出自相矛盾，运维照它排查会找错方向。
        writeValidProfile(PROBE_PATH, PROBE_CODE, "9.9");
        SourceRegistry probe = store.insert(probeRow(PROBE_PATH, "1.0"));

        SourceCheckResult result = service().test(probe.getId());

        assertThat(result.items()).filteredOn(item -> item.name().equals("profile_source_code_matches"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.passed()).isTrue();
                    assertThat(item.detail()).contains("一致").doesNotContain("不一致", "profileVersion");
                });
        assertThat(result.items()).filteredOn(item -> item.name().equals("profile_profile_version_matches"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.passed()).isFalse();
                    assertThat(item.detail()).contains("profileVersion 与登记不一致", "登记=1.0", "文件=9.9");
                });
        assertThat(result.items()).filteredOn(item -> item.name().equals("profile_required_top_level_keys"))
                .singleElement()
                .satisfies(item -> assertThat(item.detail()).contains("9 个顶层必备键齐全"));
        assertThat(result.ok()).isFalse();
    }

    @Test
    @DisplayName("test 缺键时缺键项逐字列出缺失键，而不是复用别的项的说明")
    void testMissingKeysItemListsMissingKeys() throws IOException {
        Path target = profileRoot.resolve(PROBE_PATH);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "{\"profileVersion\":\"1.0\",\"sourceCode\":\"" + PROBE_CODE + "\"}",
                StandardCharsets.UTF_8);
        SourceRegistry probe = store.insert(probeRow(PROBE_PATH, "1.0"));

        SourceCheckResult result = service().test(probe.getId());

        assertThat(result.items()).filteredOn(item -> item.name().equals("profile_required_top_level_keys"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.passed()).isFalse();
                    assertThat(item.applicable()).isTrue();
                    assertThat(item.detail()).contains("canonical", "eventTypeMapping", "quarantinePolicy");
                });
        // sourceCode / profileVersion 本身是齐的 ⇒ 这两项仍应通过（不因别的项失败而被连坐）
        assertThat(result.items())
                .filteredOn(item -> item.name().equals("profile_source_code_matches")
                        || item.name().equals("profile_profile_version_matches"))
                .allSatisfy(item -> assertThat(item.passed()).isTrue());
    }

    @Test
    @DisplayName("create 的状态集合提示文本稳定（Set 迭代顺序不得泄漏进响应）")
    void createStatusHintIsDeterministic() {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
            SourceRegistryCreateReq req = new SourceRegistryCreateReq(
                    "probe-status-" + i, "状态提示探针", "FILE", PROBE_PATH, "Asia/Shanghai", "CNY", "ACTIVE", "1.0",
                    "probe");
            assertThatThrownBy(() -> service().create(req))
                    .isInstanceOf(PlatformBizException.class)
                    .satisfies(e -> seen.add(e.getMessage()));
        }
        assertThat(seen).hasSize(1);
        assertThat(seen.iterator().next()).contains("[DRAFT, PAUSED]");
    }

    // ---------------- activate ----------------

    @Test
    @DisplayName("activate 种子源：画像未落盘 → SOURCE_PROFILE_INVALID（D-035 §11 预期行为，非缺陷）")
    void activateSeedSourceFailsProfileValidation() {
        SourceRegistry seed = seedRow();
        bindSeedAsCurrent(seed.getId());

        assertThatThrownBy(() -> service().activate(seed.getId()))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_PROFILE_INVALID));
        assertThat(store.selectById(seed.getId()).getStatus()).isEqualTo(SourceRegistry.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("activate 合法探针源：status=ACTIVE 且当前源绑定到它；ACTIVE 环境仍唯一")
    void activateBindsCurrentSource() {
        SourceRegistry seed = seedRow();
        bindSeedAsCurrent(seed.getId());
        writeValidProfile(PROBE_PATH, PROBE_CODE, "1.0");
        SourceRegistry probe = store.insert(probeRow(PROBE_PATH, "1.0"));

        SourceChangeOutcome outcome = service().activate(probe.getId());

        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.source().status()).isEqualTo(SourceRegistry.STATUS_ACTIVE);
        assertThat(outcome.source().current()).isTrue();
        assertThat(store.activeSourceId()).isEqualTo(probe.getId());
        assertThat(store.activeRuntimeProfileCount()).isEqualTo(1);
        assertThat(store.selectById(seed.getId()).getStatus()).isEqualTo(SourceRegistry.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("activate 幂等：已是当前源 → 成功且 changed=false（调用方据此不重复写绑定审计）")
    void activateIsIdempotentWhenAlreadyCurrent() {
        writeValidProfile(PROBE_PATH, PROBE_CODE, "1.0");
        SourceRegistry probe = store.insert(probeRow(PROBE_PATH, "1.0"));
        SourceRegistryService service = service();

        SourceChangeOutcome first = service.activate(probe.getId());
        LocalDateTime afterFirst = store.selectById(probe.getId()).getUpdatedAt();
        SourceChangeOutcome second = service.activate(probe.getId());

        assertThat(first.changed()).isTrue();
        assertThat(second.changed()).isFalse();
        assertThat(second.source().current()).isTrue();
        assertThat(store.selectById(probe.getId()).getUpdatedAt()).isEqualTo(afterFirst);
        assertThat(store.activeSourceId()).isEqualTo(probe.getId());
    }

    @Test
    @DisplayName("activate 尚无 ACTIVE runtime_profile → 明确拒绝，不静默只改源状态")
    void activateWithoutActiveRuntimeProfileIsRejected() {
        writeValidProfile(PROBE_PATH, PROBE_CODE, "1.0");
        SourceRegistry probe = store.insert(probeRow(PROBE_PATH, "1.0"));
        store.clearActiveProfile();

        assertThatThrownBy(() -> service().activate(probe.getId()))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.PARAM_INVALID));
        assertThat(store.selectById(probe.getId()).getStatus()).isEqualTo(SourceRegistry.STATUS_DRAFT);
    }

    @Test
    @DisplayName("activate 不存在的源 → SOURCE_NOT_FOUND")
    void activateMissingIdThrowsSourceNotFound() {
        assertThatThrownBy(() -> service().activate(55L))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("activate 画像 sourceCode/profileVersion 与登记不一致 → SOURCE_PROFILE_INVALID")
    void activateRejectsProfileIdentityMismatch() {
        writeValidProfile(PROBE_PATH, "someone-else", "1.0");
        SourceRegistry probe = store.insert(probeRow(PROBE_PATH, "1.0"));

        assertThatThrownBy(() -> service().activate(probe.getId()))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_PROFILE_INVALID));

        writeValidProfile(PROBE_PATH, PROBE_CODE, "9.9");
        assertThatThrownBy(() -> service().activate(probe.getId()))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_PROFILE_INVALID));
    }

    @Test
    @DisplayName("activate 画像缺少 §4.2 顶层必备键 → SOURCE_PROFILE_INVALID")
    void activateRejectsProfileMissingRequiredKeys() throws IOException {
        Path target = profileRoot.resolve(PROBE_PATH);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "{\"profileVersion\":\"1.0\",\"sourceCode\":\"" + PROBE_CODE + "\"}",
                StandardCharsets.UTF_8);
        SourceRegistry probe = store.insert(probeRow(PROBE_PATH, "1.0"));

        assertThatThrownBy(() -> service().activate(probe.getId()))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_PROFILE_INVALID));
    }

    // ---------------- pause ----------------

    @Test
    @DisplayName("pause 当前绑定源 → SOURCE_IN_USE（先切当前源；不写任何状态）")
    void pauseCurrentSourceIsRejected() {
        SourceRegistry seed = seedRow();
        bindSeedAsCurrent(seed.getId());

        assertThatThrownBy(() -> service().pause(seed.getId()))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_IN_USE));
        assertThat(store.selectById(seed.getId()).getStatus()).isEqualTo(SourceRegistry.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("pause 非当前源 → 成功置 PAUSED")
    void pauseNonCurrentSourceSucceeds() {
        SourceRegistry seed = seedRow();
        bindSeedAsCurrent(seed.getId());
        SourceRegistry probe = store.insert(probeRow(PROBE_PATH, "1.0"));

        SourceChangeOutcome outcome = service().pause(probe.getId());

        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.source().status()).isEqualTo(SourceRegistry.STATUS_PAUSED);
        assertThat(store.selectById(probe.getId()).getStatus()).isEqualTo(SourceRegistry.STATUS_PAUSED);
        assertThat(store.activeSourceId()).isEqualTo(seed.getId());
    }

    @Test
    @DisplayName("pause 不存在的源 → SOURCE_NOT_FOUND")
    void pauseMissingIdThrowsSourceNotFound() {
        assertThatThrownBy(() -> service().pause(88L))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(PlatformBizException.SOURCE_NOT_FOUND));
    }

    // ---------------- DTO 边界 ----------------

    @Test
    @DisplayName("DTO 不含凭据字段，也不含绝对本机路径（值是仓库相对路径 / 关键词）")
    void viewExposesNoCredentialAndNoAbsolutePath() {
        SourceRegistry seed = seedRow();
        bindSeedAsCurrent(seed.getId());

        SourceRegistryView view = service().get(seed.getId());

        assertThat(SourceRegistryView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("credentialRef", "credential", "password", "secret", "token", "absolutePath");
        assertThat(view.profilePath()).isEqualTo(SEED_PATH).doesNotContain(":\\").doesNotStartWith("/");
    }

    private SourceRegistry probeRow(String path, String version) {
        SourceRegistry probe = new SourceRegistry();
        probe.setSourceCode(PROBE_CODE);
        probe.setDisplayName("P1-03 探针源 1（非 mock-mall）");
        probe.setIngestMode("FILE");
        probe.setProfilePath(path);
        probe.setTimezone("Asia/Shanghai");
        probe.setCurrency("CNY");
        probe.setStatus(SourceRegistry.STATUS_DRAFT);
        probe.setProfileVersion(version);
        // P2-07：探针源是"第二个源"，给一个与种子源 'dw' 不撞名的合法前缀。
        // 这里必须给值而不是留空：V18 之后该列 NOT NULL，且 activate 会校验库中的值（D-074 三处挂点）。
        probe.setWarehousePrefix("dw_probe");
        return probe;
    }
}
