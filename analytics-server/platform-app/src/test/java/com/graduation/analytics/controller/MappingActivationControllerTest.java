package com.graduation.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.graduation.analytics.auth.AuthInterceptor;
import com.graduation.analytics.auth.AuthService;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.common.GlobalExceptionHandler;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.decision.AuditActor;
import com.graduation.analytics.decision.OperationAuditService;
import com.graduation.analytics.mapping.MappingHash;
import com.graduation.analytics.mapping.activation.ActiveMappingPointer;
import com.graduation.analytics.mapping.activation.ActiveMappingPointerStore;
import com.graduation.analytics.mapping.activation.MappingActivationService;
import com.graduation.analytics.mapping.activation.UnavailableActiveMappingPointerStore;
import com.graduation.analytics.mapping.dryrun.InMemoryMappingDryRunReportRepository;
import com.graduation.analytics.mapping.dryrun.MappingDryRunService;
import com.graduation.analytics.source.SourceAuditActions;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import com.graduation.analytics.testsupport.RepoRoot;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S2-03 HTTP 层实测：真实控制器 + 真实激活服务 + 真实 dry-run 服务 + 真实拦截器 + 真实异常映射。
 *
 * <p>为什么这一层必须用真服务而不是桩掉：本层唯一要量清楚的是"打桩会掩盖的东西"——
 * ①激活结果（10 字段 record + {@code LocalDateTime}）经 Spring 自己的 mapper 序列化的真实形状；
 * ②九个错误码到 HTTP 状态码的真实落点（404/409/501/400，**不得用 500 表达业务冲突**）；
 * ③认证/授权（401/403）发生在进入控制器之前，因此**不产生审计行**；
 * ④审计规则与 P1-03 同口径：真实变更 1 行 SUCCESS、幂等重复 0 行、失败 1 行 FAILED。</p>
 *
 * <p><b>指针存储为什么在本类里另写一个替身</b>：{@code TestActiveMappingPointerStore} 在
 * {@code connection-ingestion} 的 test 作用域，而 {@code platform-app} 只依赖该模块的 main 与
 * {@code platform-common} 的 test-jar（见 pom），因此本类自带一个最小替身。
 * 它**不是**正式 active pointer 的属主，只在 HTTP 层用作"落库能力已就绪"的对照组；
 * 落库能力未就绪时（生产装配的真实状态）另有 {@link UnavailableActiveMappingPointerStore} 的用例 → 501。</p>
 */
class MappingActivationControllerTest {

    private static final String CONTRACT = "contract-specs/schemas/canonical-event.v1.schema.json";
    private static final long SOURCE_ID = 7L;
    private static final String SOURCE_CODE = "s2-03-http";
    private static final String PROFILE_PATH = "profiles/raw-a.v2.json";
    private static final String SAMPLE_REF = "s2-03-http.jsonl";

    private static final String ORDER_PAID_FULL = "{\"ord\":\"order_id\",\"buyer\":\"user_id\","
            + "\"pay\":\"payment_id\",\"paid_fen\":\"amount\",\"paidAt\":\"paid_at\"}";

    private static final String ENVELOPE_FULL = "{\"id\":\"event_id\",\"kind\":\"event_type\","
            + "\"at\":\"event_time\",\"sys\":\"source_system\",\"rev\":\"schema_version\","
            + "\"tr\":\"trace_id\",\"data\":\"payload\"}";

    /** 必填 payload 字段 {@code payment_id} 没有映射 ⇒ 能力缺口 + 激活阻断（两者同时出现，是 loader 的既有事实）。 */
    private static final String ORDER_PAID_MISSING_PAYMENT_ID = "{\"ord\":\"order_id\",\"buyer\":\"user_id\","
            + "\"paid_fen\":\"amount\",\"paidAt\":\"paid_at\"}";

    private static final Map<String, CurrentUser> TOKENS = Map.of(
            "admin-token", new CurrentUser(1L, "admin", "admin"),
            "dev-token", new CurrentUser(5L, "dev1", "data_dev"),
            "analyst-token", new CurrentUser(3L, "analyst1", "analyst"));

    @TempDir
    Path profileRoot;

    @TempDir
    Path sampleRoot;

    private InMemoryMappingDryRunReportRepository reports;
    private SourceRegistryService sources;
    private EventClock clock;
    private AuthService authService;
    private OperationAuditService audit;
    private RecordingPointerStore store;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(profileRoot.resolve("profiles"));
        Files.writeString(profileRoot.resolve(PROFILE_PATH), v2Profile(), StandardCharsets.UTF_8);
        Files.writeString(sampleRoot.resolve(SAMPLE_REF), rawLine("evt-1") + "\n", StandardCharsets.UTF_8);

        reports = new InMemoryMappingDryRunReportRepository();
        sources = mock(SourceRegistryService.class);
        when(sources.get(SOURCE_ID)).thenReturn(view());
        when(sources.get(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            if (id == SOURCE_ID) {
                return view();
            }
            throw new PlatformBizException(PlatformBizException.SOURCE_NOT_FOUND, "源不存在");
        });

        clock = new EventClock(Clock.fixed(Instant.parse("2026-09-21T02:20:30Z"), ZoneId.of("Asia/Shanghai")));
        authService = mock(AuthService.class);
        TOKENS.forEach((token, user) -> when(authService.validate(token)).thenReturn(user));
        audit = mock(OperationAuditService.class);
        store = new RecordingPointerStore();
    }

    // ---------------------------------------------------------------- ⑯ 正常闭环

    @Test
    @DisplayName("⑯ dry-run → activate（data_dev）→ 200：指针已写、审计 1 行 SUCCESS、字段形状钉住")
    void fullLoopActivatesAndAudits() throws Exception {
        String reportId = dryRunProfile("dev-token", v2Profile(), 200);
        String checksum = MappingHash.sha256Hex(v2Profile());

        MvcResult result = activate("dev-token", String.valueOf(SOURCE_ID), reportId, checksum);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("OK");
        assertThat(((Number) JsonPath.read(body, "$.data.sourceId")).longValue()).isEqualTo(SOURCE_ID);
        assertThat((String) JsonPath.read(body, "$.data.sourceCode")).isEqualTo(SOURCE_CODE);
        assertThat((String) JsonPath.read(body, "$.data.profilePath")).isEqualTo(PROFILE_PATH);
        assertThat((String) JsonPath.read(body, "$.data.profileVersion")).isEqualTo("2.0");
        assertThat((String) JsonPath.read(body, "$.data.profileChecksum")).isEqualTo(checksum);
        assertThat((String) JsonPath.read(body, "$.data.contractVersion")).isEqualTo("1.0");
        assertThat((String) JsonPath.read(body, "$.data.reportId")).isEqualTo(reportId);
        assertThat((String) JsonPath.read(body, "$.data.activatedAt")).isEqualTo("2026-09-21T10:20:30");
        assertThat((String) JsonPath.read(body, "$.data.activatedBy")).isEqualTo("dev1");
        assertThat((boolean) JsonPath.read(body, "$.data.changed")).isTrue();
        assertThat((Object) JsonPath.read(body, "$.data.previousProfileChecksum")).isNull();

        // 指针真的写了（且写的正是报告钉住的那一份字节）
        assertThat(store.find(SOURCE_ID)).get()
                .satisfies(p -> {
                    assertThat(p.profileChecksum()).isEqualTo(checksum);
                    assertThat(p.profilePath()).isEqualTo(PROFILE_PATH);
                    assertThat(p.activatedBy()).isEqualTo("dev1");
                });

        // 审计：1 行 SUCCESS，资源 id = 源 id，after 摘要含新指针全部事实，before 为空（首次激活）
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).success(any(AuditActor.class), eq(SourceAuditActions.ACTION_MAPPING_ACTIVATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_MAPPING), eq(String.valueOf(SOURCE_ID)),
                isNull(), after.capture(), reason.capture());
        assertThat(after.getValue()).contains("profileChecksum=" + checksum)
                .contains("profilePath=" + PROFILE_PATH).contains("reportId=" + reportId)
                .contains("activatedBy=dev1");
        assertThat(reason.getValue()).contains("首次激活");
    }

    // ---------------------------------------------------------------- ⑭⑮ 权限

    @Test
    @DisplayName("⑭⑮ 权限实测：无会话 401 / analyst 403（报文带 runtime:manage）且都不留审计行")
    void permissionIsEnforcedBeforeControllerAndAuditedNowhere() throws Exception {
        String reportId = dryRunProfile("dev-token", v2Profile(), 200);
        String checksum = MappingHash.sha256Hex(v2Profile());

        MvcResult anonymous = activate(null, String.valueOf(SOURCE_ID), reportId, checksum);
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
        assertThat(anonymous.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"UNAUTHORIZED\"");

        MvcResult analyst = activate("analyst-token", String.valueOf(SOURCE_ID), reportId, checksum);
        assertThat(analyst.getResponse().getStatus()).isEqualTo(403);
        assertThat(analyst.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"FORBIDDEN_PERMISSION\"").contains("runtime:manage");

        // 两者都在进入控制器之前被拒绝：没有可信身份可记，也没有业务变更，因此不写审计行
        verifyNoInteractions(audit);
        assertThat(store.find(SOURCE_ID)).isEmpty();

        assertThat(activate("admin-token", String.valueOf(SOURCE_ID), reportId, checksum)
                .getResponse().getStatus()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- ⑫⑬ 幂等与替换

    @Test
    @DisplayName("⑫⑬ 同 checksum 重复 activate 幂等（0 行审计、激活时间不变）；换 checksum 才替换并留痕")
    void repeatIsIdempotentAndSwitchingReplacesWithAudit() throws Exception {
        String profileA = v2Profile();
        String checksumA = MappingHash.sha256Hex(profileA);
        String reportA = dryRunProfile("dev-token", profileA, 200);

        assertThat(activate("dev-token", String.valueOf(SOURCE_ID), reportA, checksumA)
                .getResponse().getStatus()).isEqualTo(200);
        LocalDateTime firstAt = store.find(SOURCE_ID).orElseThrow().activatedAt();

        MvcResult again = activate("dev-token", String.valueOf(SOURCE_ID), reportA, checksumA);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        String againBody = again.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((boolean) JsonPath.read(againBody, "$.data.changed")).isFalse();
        assertThat((String) JsonPath.read(againBody, "$.data.activatedAt")).isEqualTo("2026-09-21T10:20:30");
        assertThat(store.find(SOURCE_ID)).get()
                .satisfies(p -> assertThat(p.activatedAt()).isEqualTo(firstAt));
        // 幂等重复不写第二行审计：否则刷新一次页面就多一行"激活"
        verify(audit, times(1)).success(any(), any(), any(), any(), any(), any(), any());

        // 换成另一份字节不同的画像（多一条未被使用的金额单位声明，语义不变、哈希不同）
        String profileB = profileA.replace("\"paid_fen\": \"FEN\"", "\"paid_fen\": \"FEN\", \"extra_fen\": \"FEN\"");
        String checksumB = MappingHash.sha256Hex(profileB);
        assertThat(checksumB).isNotEqualTo(checksumA);
        Files.writeString(profileRoot.resolve(PROFILE_PATH), profileB, StandardCharsets.UTF_8);
        String reportB = dryRunProfile("dev-token", profileB, 200);

        MvcResult replaced = activate("dev-token", String.valueOf(SOURCE_ID), reportB, checksumB);
        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
        String replacedBody = replaced.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((boolean) JsonPath.read(replacedBody, "$.data.changed")).isTrue();
        assertThat((String) JsonPath.read(replacedBody, "$.data.previousProfileChecksum")).isEqualTo(checksumA);
        assertThat(store.find(SOURCE_ID)).get()
                .satisfies(p -> assertThat(p.profileChecksum()).isEqualTo(checksumB));

        ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit, times(2)).success(any(AuditActor.class), eq(SourceAuditActions.ACTION_MAPPING_ACTIVATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_MAPPING), eq(String.valueOf(SOURCE_ID)),
                before.capture(), any(), reason.capture());
        assertThat(before.getAllValues().get(0)).as("首次激活没有 before").isNull();
        assertThat(before.getAllValues().get(1)).contains("previousProfileChecksum=" + checksumA);
        assertThat(reason.getAllValues().get(1)).contains("替换");
    }

    // ---------------------------------------------------------------- 404

    @Test
    @DisplayName("404：源不存在 / 报告不存在 / 报告属于别的源（归属不泄漏）——都不是 400/500")
    void notFoundIs404() throws Exception {
        String checksum = MappingHash.sha256Hex(v2Profile());

        MvcResult unknownSource = activate("dev-token", "8", "dr-20260921102030-deadbeef", checksum);
        assertThat(unknownSource.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknownSource.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"SOURCE_NOT_FOUND\"");

        String reportId = dryRunProfile("dev-token", v2Profile(), 200);
        MvcResult unknownReport = activate("dev-token", String.valueOf(SOURCE_ID),
                "dr-20260921102030-00000000", checksum);
        assertThat(unknownReport.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknownReport.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"DRY_RUN_REPORT_NOT_FOUND\"");
        assertThat(reportId).startsWith("dr-");

        // 同一个报告 id，但路径上写的是另一个源 ⇒ 与"报告不存在"同一个码，不泄露它属于谁
        MvcResult otherSource = activate("dev-token", String.valueOf(SOURCE_ID),
                dryRunProfileWithToken("9"), checksum);
        assertThat(otherSource.getResponse().getStatus()).isEqualTo(404);
        assertThat(otherSource.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"DRY_RUN_REPORT_NOT_FOUND\"");

        // 失败一律留 1 行 FAILED（reason 以稳定错误码开头），且没有指针被写入
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit, times(3)).failure(any(AuditActor.class), eq(SourceAuditActions.ACTION_MAPPING_ACTIVATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_MAPPING), any(), isNull(), isNull(), reason.capture());
        assertThat(reason.getAllValues().get(0)).startsWith("SOURCE_NOT_FOUND");
        assertThat(reason.getAllValues().get(1)).startsWith("DRY_RUN_REPORT_NOT_FOUND");
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
        assertThat(store.find(SOURCE_ID)).isEmpty();
    }

    // ---------------------------------------------------------------- 409（业务冲突不得是 500）

    @Test
    @DisplayName("409：报告不可激活 / checksum 不一致 / 契约漂移 / 画像字节变化——四条都是 409 且各留 1 行 FAILED")
    void businessConflictsAre409() throws Exception {
        // ① 可装载但不可激活：必填 payload 字段没有映射 ⇒ 能力缺口 + 激活阻断
        //    前置自证（不假设夹具的阻断性）：同一次 HTTP 预览里就把"可装载 / 不可激活 / 阻断原因"读出来
        String blocked = v2ProfileMissingRequiredPayload();
        String blockedBody = postDryRun("dev-token", blocked, 200)
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((boolean) JsonPath.read(blockedBody, "$.data.profileAccepted"))
                .as("这一份必须是'装载成功但不可激活'，否则量到的不是这条判据: " + blockedBody).isTrue();
        assertThat((List<String>) JsonPath.read(blockedBody, "$.data.activationBlocks"))
                .as(blockedBody).isNotEmpty();
        assertThat((boolean) JsonPath.read(blockedBody, "$.data.activationEligible")).isFalse();
        assertConflict(activate("dev-token", String.valueOf(SOURCE_ID),
                        JsonPath.read(blockedBody, "$.data.reportId"), MappingHash.sha256Hex(blocked)),
                "MAPPING_ACTIVATION_INELIGIBLE");

        // ② 调用方手上的 checksum 与报告不一致（预览之后换了画像）
        String good = v2Profile();
        String goodReport = dryRunProfile("dev-token", good, 200);
        assertConflict(activate("dev-token", String.valueOf(SOURCE_ID), goodReport,
                MappingHash.sha256Hex(blocked)), "MAPPING_ACTIVATION_CHECKSUM_MISMATCH");

        // ③ 契约在预览之后被动过：字节追加一个换行（语义相同、字节不同 ⇒ 必须判漂移）
        Path contractCopy = profileRoot.resolve("contract-copy.json");
        Files.copy(RepoRoot.path(CONTRACT), contractCopy);
        MappingActivationService driftService = activationService(contractCopy, store);
        String driftReport = dryRunProfile(driftService, "dev-token", good, 200);
        Files.writeString(contractCopy, Files.readString(contractCopy) + "\n", StandardCharsets.UTF_8);
        assertConflict(activate(driftService, "dev-token", String.valueOf(SOURCE_ID), driftReport,
                MappingHash.sha256Hex(good)), "MAPPING_CONTRACT_DRIFT");

        // ④ 磁盘上的画像字节在预览之后被改（激活的内容必须就是预览的内容）
        String changedReport = dryRunProfile("dev-token", good, 200);
        Files.writeString(profileRoot.resolve(PROFILE_PATH), good.replace("\"paid_se\"", "\"paid_se \""),
                StandardCharsets.UTF_8);
        assertConflict(activate("dev-token", String.valueOf(SOURCE_ID), changedReport,
                MappingHash.sha256Hex(good)), "MAPPING_PROFILE_CHANGED");

        verify(audit, times(4)).failure(any(AuditActor.class), eq(SourceAuditActions.ACTION_MAPPING_ACTIVATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_MAPPING), any(), isNull(), isNull(), any());
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
        assertThat(store.find(SOURCE_ID)).as("四条冲突都必须在写入之前失败").isEmpty();
    }

    // ---------------------------------------------------------------- 400

    @Test
    @DisplayName("400：入参形状非法（reportId 空白 / checksum 非 64 位 hex / body 非 JSON），不是 409")
    void invalidInputIs400() throws Exception {
        String checksum = MappingHash.sha256Hex(v2Profile());
        // 这两条被 bean validation（@NotBlank）在控制器之前拦下 ⇒ 400 且不留审计行
        assertBadRequest(activate("dev-token", String.valueOf(SOURCE_ID), "  ", checksum));
        assertBadRequest(activate("dev-token", String.valueOf(SOURCE_ID), "dr-1", "  "));
        // 这两条形状非空，进到服务里才被 requireChecksum 拒绝 ⇒ 400 + 1 行 FAILED(PARAM_INVALID)
        assertBadRequest(activate("dev-token", String.valueOf(SOURCE_ID), "dr-1", "not-a-checksum"));
        assertBadRequest(activate("dev-token", String.valueOf(SOURCE_ID), "dr-1", checksum.substring(0, 63)));
        // 报文都读不出来 ⇒ 反序列化失败发生在控制器之前，同样不留审计行
        assertBadRequest(mockMvc(store).perform(post(activatePath(String.valueOf(SOURCE_ID)))
                .header("Authorization", "Bearer dev-token")
                .contentType("application/json").content("{not json")).andReturn());

        // 精确计数：服务里被拒的两次各留 1 行 FAILED（与 P1-03 同口径：被拒绝的尝试也要留痕），
        // 控制器之前被拒的三次不留 —— 少一行或多一行都会让本断言失败
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit, times(2)).failure(any(AuditActor.class), eq(SourceAuditActions.ACTION_MAPPING_ACTIVATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_MAPPING), any(), isNull(), isNull(), reason.capture());
        assertThat(reason.getAllValues()).allSatisfy(r -> assertThat(r).startsWith("PARAM_INVALID"));
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- 501（能力缺口）

    @Test
    @DisplayName("501：生产装配（落库能力未就绪）→ MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE，且审计写失败不掩盖它")
    void persistenceGapIs501AndNotMasked() throws Exception {
        UnavailableActiveMappingPointerStore unavailable = new UnavailableActiveMappingPointerStore();
        MockMvc mvc = mockMvc(unavailable);
        String profile = v2Profile();
        String reportId = dryRunProfile(mvc, "dev-token", profile, 200);
        String checksum = MappingHash.sha256Hex(profile);

        MvcResult result = mvc.perform(post(activatePath(String.valueOf(SOURCE_ID)))
                .header("Authorization", "Bearer dev-token").contentType("application/json")
                .content(activateBody(reportId, checksum))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(501);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE\"");

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).failure(any(AuditActor.class), eq(SourceAuditActions.ACTION_MAPPING_ACTIVATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_MAPPING), eq(String.valueOf(SOURCE_ID)),
                isNull(), isNull(), reason.capture());
        assertThat(reason.getValue()).startsWith("MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE");

        // 审计自己写失败也不能把 501 变成 500（真问题被掩盖）
        doThrow(new RuntimeException("audit db down")).when(audit)
                .failure(any(), any(), any(), any(), any(), any(), any());
        assertThat(mvc.perform(post(activatePath(String.valueOf(SOURCE_ID)))
                .header("Authorization", "Bearer dev-token").contentType("application/json")
                .content(activateBody(reportId, checksum))).andReturn().getResponse().getStatus())
                .isEqualTo(501);
    }

    // ---------------------------------------------------------------- helpers

    private MockMvc mockMvc(ActiveMappingPointerStore pointers) {
        return mockMvc(activationService(RepoRoot.path(CONTRACT), pointers));
    }

    private MockMvc mockMvc(MappingActivationService activationService) {
        return MockMvcBuilders.standaloneSetup(
                        new MappingDryRunController(dryRunService(RepoRoot.path(CONTRACT))),
                        new MappingActivationController(activationService, audit))
                .addInterceptors(new AuthInterceptor(authService, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler(), new PlatformExceptionAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(productionShapedMapper()))
                .build();
    }

    private MappingDryRunService dryRunService(Path contractPath) {
        return new MappingDryRunService(reports, clock, contractPath.toString(), sampleRoot.toString());
    }

    private MappingActivationService activationService(Path contractPath, ActiveMappingPointerStore pointers) {
        return new MappingActivationService(reports, sources, pointers, clock,
                profileRoot.toString(), contractPath.toString());
    }

    private static ObjectMapper productionShapedMapper() {
        return Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /** 跑一次 dry-run 并返回报告 id（断言 200 + 报告可激活，见 {@link #dryRunProfile(MockMvc, ...)}）。 */
    private String dryRunProfile(String token, String profileText, int expectedStatus) throws Exception {
        return dryRunProfile(mockMvc(store), token, profileText, expectedStatus);
    }

    private String dryRunProfile(MappingActivationService service, String token, String profileText, int status)
            throws Exception {
        return dryRunProfile(mockMvc(service), token, profileText, status);
    }

    private String dryRunProfile(MockMvc mvc, String token, String profileText, int expectedStatus)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sources/" + SOURCE_ID + "/mappings/dry-run")
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .content("{\"profileText\":" + quote(profileText)
                                + ",\"sampleRef\":" + quote(SAMPLE_REF) + ",\"limit\":100}"))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(expectedStatus);
        assertThat((boolean) JsonPath.read(body, "$.data.activationEligible"))
                .as("前置：这份画像必须是可激活的（否则量到的是别的判据）: " + body).isTrue();
        return JsonPath.read(body, "$.data.reportId");
    }

    /**
     * 原始 dry-run：只断言 HTTP 状态，不对"可激活"作任何假设。
     * 用于"报告本身不可激活"这类前置自证 —— 阻断性由调用方从响应里读出来。
     */
    private MvcResult postDryRun(String token, String profileText, int expectedStatus) throws Exception {
        MvcResult result = mockMvc(store).perform(post("/api/v1/sources/" + SOURCE_ID + "/mappings/dry-run")
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .content("{\"profileText\":" + quote(profileText)
                                + ",\"sampleRef\":" + quote(SAMPLE_REF) + ",\"limit\":100}"))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(expectedStatus);
        return result;
    }

    /** 用路径 token {@code 9} 跑一次 dry-run（登记表里没有 9，dry-run 不查库），返回报告 id。 */
    private String dryRunProfileWithToken(String sourceToken) throws Exception {
        String profile = v2Profile();
        MvcResult result = mockMvc(store).perform(post("/api/v1/sources/" + sourceToken + "/mappings/dry-run")
                        .header("Authorization", "Bearer dev-token").contentType("application/json")
                        .content("{\"profileText\":" + quote(profile)
                                + ",\"sampleRef\":" + quote(SAMPLE_REF) + ",\"limit\":100}"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.reportId");
    }

    private MvcResult activate(String token, String sourceToken, String reportId, String checksum)
            throws Exception {
        return activateService(mockMvc(store), token, sourceToken, reportId, checksum);
    }

    private MvcResult activate(MappingActivationService service, String token, String sourceToken,
                               String reportId, String checksum) throws Exception {
        return activateService(mockMvc(service), token, sourceToken, reportId, checksum);
    }

    private static MvcResult activateService(MockMvc mvc, String token, String sourceToken,
                                             String reportId, String checksum) throws Exception {
        var builder = post(activatePath(sourceToken)).contentType("application/json")
                .content(activateBody(reportId, checksum));
        return mvc.perform(token == null ? builder : builder.header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private static String activatePath(String sourceToken) {
        return "/api/v1/sources/" + sourceToken + "/mappings/activate";
    }

    private static String activateBody(String reportId, String checksum) {
        return "{\"reportId\":" + quote(reportId) + ",\"expectedProfileChecksum\":" + quote(checksum) + "}";
    }

    private static void assertConflict(MvcResult result, String code) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(409);
        assertThat(body).contains("\"code\":\"" + code + "\"");
    }

    private static void assertBadRequest(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
        assertThat(body).contains("\"code\":\"PARAM_INVALID\"");
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static SourceRegistryView view() {
        return new SourceRegistryView(SOURCE_ID, SOURCE_CODE, "S2-03 激活 HTTP 探针", "LOCAL_FILE",
                PROFILE_PATH, "Asia/Shanghai", "CNY", "ACTIVE", "2.0", true, null, null,
                "s2_03_http");
    }

    private static String rawLine(String eventId) {
        return "{\"id\":\"" + eventId + "\",\"kind\":\"paid_se\","
                + "\"at\":\"2026-09-21T09:30:00+08:00\",\"sys\":\"" + SOURCE_CODE + "\",\"rev\":\"1.0\","
                + "\"tr\":\"trace-" + eventId + "\",\"data\":{\"ord\":\"o-1\",\"buyer\":\"u-1\",\"pay\":\"p-1\","
                + "\"paid_fen\":\"12345\",\"paidAt\":\"2026-09-21T09:30:00+08:00\"}}";
    }

    private static String v2Profile() {
        return v2Profile(ORDER_PAID_FULL);
    }

    /** 必填 payload 缺映射的那一份（可装载，但 capabilityGaps/activationBlocks 非空 ⇒ 不可激活）。 */
    private static String v2ProfileMissingRequiredPayload() {
        return v2Profile(ORDER_PAID_MISSING_PAYMENT_ID);
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
                """.formatted(SOURCE_CODE, ENVELOPE_FULL, orderPaidPayload);
    }

    /**
     * HTTP 层最小指针替身（按 sourceId 保持"一个源最多一个激活指针"）。
     *
     * <p>**它不是正式激活指针的属主**：正式属主是 {@link ActiveMappingPointerStore} 的落库实现
     * （当前未就绪，见 {@link UnavailableActiveMappingPointerStore}）。这里只用于"落库能力已就绪"时
     * HTTP 层行为（200 / 幂等 / 替换 / 审计）的实测对照。</p>
     */
    private static final class RecordingPointerStore implements ActiveMappingPointerStore {

        private final Map<Long, ActiveMappingPointer> pointers = new LinkedHashMap<>();

        @Override
        public Optional<ActiveMappingPointer> find(long sourceId) {
            return Optional.ofNullable(pointers.get(sourceId));
        }

        @Override
        public void save(ActiveMappingPointer pointer) {
            pointers.put(pointer.sourceId(), pointer);
        }
    }
}
