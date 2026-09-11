package com.graduation.analytics.controller;

import com.graduation.analytics.ai.ExplanationService;
import com.graduation.analytics.ai.TextToSqlService;
import com.graduation.analytics.ai.evidence.EvidenceBuilder;
import com.graduation.analytics.ai.evidence.EvidenceService;
import com.graduation.analytics.ai.llm.LlmProvider;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.mapper.AiQueryHistoryMapper;
import com.graduation.analytics.ai.sql.SqlExecutor;
import com.graduation.analytics.auth.AuthService;
import com.graduation.analytics.auth.AuthInterceptor;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.UserAdminController;
import com.graduation.analytics.common.MallBizException;
import com.graduation.analytics.decision.DecisionService;
import com.graduation.analytics.decision.OperationAuditService;
import com.graduation.analytics.decision.entity.DecisionTask;
import com.graduation.analytics.metric.MySqlMetricStore;
import com.graduation.analytics.pipeline.mapper.DataQualityResultMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 实测响应（R8-3 §3.1/§3.2 + R8-2 §1）：用**真实** {@link AuthInterceptor} + **真实**
 * {@link PlatformExceptionAdvice} + 真实控制器跑 MockMvc，逐条量出「无身份 → 401」
 * 「有身份无权限 → 403」的真实状态码与响应体，并写到 {@code target/r8-3-auth-responses.txt}
 * 供交付报告逐条引用（不是推断，是实测产出）。
 *
 * <p>为什么不是真机 HTTP：8091 进程已停，且本轮的边界是「不做数据库变更」——
 * MetaFlywayInitializer 在启动时必然执行 analytics_meta 迁移（V14），起进程就越界了。
 * MockMvc 走的是同一个拦截器、同一个 advice、同一批控制器，差别仅在「没有 TCP 套接字」；
 * 真机报文由主会话统一重启后补测。证据包取数（EvidenceBuilder）在 ④ 里是桩，
 * 六段叙述/字段形状/providerUsed 判定都是真实代码产出。</p>
 */
class AuthResponseMeasurementTest {

    private static final Map<String, CurrentUser> TOKENS = Map.of(
            "admin-token", new CurrentUser(1L, "admin", "admin"),
            "analyst-token", new CurrentUser(3L, "analyst1", "analyst"),
            "operator-token", new CurrentUser(4L, "operator1", "operator"),
            "dev-token", new CurrentUser(5L, "dev1", "data_dev"));

    private MockMvc mockMvc;
    private final List<String> measurements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AuthService authService = mock(AuthService.class);
        for (Map.Entry<String, CurrentUser> entry : TOKENS.entrySet()) {
            when(authService.validate(entry.getKey())).thenReturn(entry.getValue());
        }

        DecisionService decisionService = mock(DecisionService.class);
        when(decisionService.list(anyInt())).thenReturn(List.of(new DecisionTask()));
        when(decisionService.createDraft(any(), any(), anyString()))
                .thenThrow(new MallBizException(MallBizException.PARAM_INVALID, "标题必填"));

        TextToSqlService textToSqlService = mock(TextToSqlService.class);
        EvidenceBuilder builder = mock(EvidenceBuilder.class);
        when(builder.build(any())).thenReturn(EvidenceTestFixtures.packageOf(EvidenceTestFixtures.SNAPSHOT));
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.healthCheck()).thenReturn(false);
        ExplanationService explanationService = new ExplanationService(llm,
                mock(AiCallLogMapper.class), mock(SqlExecutor.class));
        EvidenceService evidenceService = new EvidenceService(builder, explanationService);

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DecisionController(decisionService, mock(OperationAuditService.class)),
                        new AiController(textToSqlService, explanationService, evidenceService,
                                mock(AiQueryHistoryMapper.class), mock(AiCallLogMapper.class),
                                mock(OperationAuditService.class)),
                        new UserAdminController(mock(AuthService.class), mock(OperationAuditService.class)),
                        new MetricController(mock(MySqlMetricStore.class), mock(DataQualityResultMapper.class)))
                .addInterceptors(new AuthInterceptor(authService, new ObjectMapper()))
                .setControllerAdvice(new PlatformExceptionAdvice())
                .build();
    }

    @Test
    @DisplayName("实测①：无身份访问 /decisions、/ai/queries、/ai/explanations → 401 UNAUTHORIZED")
    void unauthenticatedIs401() throws Exception {
        measure("① GET /api/v1/decisions（无 token）", get("/api/v1/decisions"), 401, "UNAUTHORIZED");
        measure("① POST /api/v1/decisions（无 token）",
                post("/api/v1/decisions").contentType("application/json").content("{}"), 401, "UNAUTHORIZED");
        measure("① GET /api/v1/ai/audit/history（无 token）", get("/api/v1/ai/audit/history"), 401, "UNAUTHORIZED");
        measure("① POST /api/v1/ai/queries（无 token）",
                post("/api/v1/ai/queries").contentType("application/json").content("{\"question\":\"x\"}"),
                401, "UNAUTHORIZED");
        measure("① POST /api/v1/ai/explanations（无 token）",
                explanations(null), 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("实测②：analyst 越权 /ai/audit/* → 403 FORBIDDEN_PERMISSION（附所需权限码）")
    void analystAuditIs403() throws Exception {
        String body = measure("② GET /api/v1/ai/audit/history（analyst）",
                get("/api/v1/ai/audit/history").header("Authorization", "Bearer analyst-token"),
                403, "FORBIDDEN_PERMISSION");
        assertTrue(body.contains("ai:audit:view"), "403 报文必须说明所需权限码: " + body);
        measure("② GET /api/v1/ai/audit/calls（analyst）",
                get("/api/v1/ai/audit/calls").header("Authorization", "Bearer analyst-token"),
                403, "FORBIDDEN_PERMISSION");
    }

    @Test
    @DisplayName("实测③：operator/data_dev 越权用户管理 → 403；admin 可用")
    void operatorUserAdminIs403() throws Exception {
        measure("③ GET /api/v1/admin/users（operator）",
                get("/api/v1/admin/users").header("Authorization", "Bearer operator-token"),
                403, "FORBIDDEN_PERMISSION");
        measure("③ GET /api/v1/admin/users（data_dev）",
                get("/api/v1/admin/users").header("Authorization", "Bearer dev-token"),
                403, "FORBIDDEN_PERMISSION");
        measure("③ GET /api/v1/ai/audit/history（admin）",
                get("/api/v1/ai/audit/history").header("Authorization", "Bearer admin-token"), 200, "OK");
    }

    @Test
    @DisplayName("实测④：admin 调 /ai/explanations → 200，六段叙述 + facts 非空 + 快照号真实")
    void adminExplanationIsComplete() throws Exception {
        String body = measure("④ POST /api/v1/ai/explanations（admin，body=快照+时间范围+问题）",
                explanations("admin-token"), 200, "OK");
        assertEquals(EvidenceTestFixtures.SNAPSHOT, JsonPath.read(body, "$.data.evidence.snapshotId"));
        assertEquals(EvidenceTestFixtures.EVIDENCE_ID, JsonPath.read(body, "$.data.evidence.evidenceId"));
        assertEquals(6, (int) JsonPath.read(body, "$.data.narrative.sections.length()"),
                "固定模板必须是六段: " + body);
        assertEquals("template", JsonPath.read(body, "$.data.narrative.providerUsed"));
        assertEquals("evidence_v1", JsonPath.read(body, "$.data.narrative.templateVersion"));
        List<?> facts = JsonPath.read(body, "$.data.evidence.facts");
        assertFalse(facts.isEmpty(), "evidence.facts 不得为空");
        assertEquals("发生了什么", JsonPath.read(body, "$.data.narrative.sections[0].title"));
        assertEquals("限制", JsonPath.read(body, "$.data.narrative.sections[5].title"));
    }

    @Test
    @DisplayName("实测⑤：伪造 X-User-Id 头不生效（无 token 仍 401，不按头部取数）")
    void forgedHeaderIsRejected() throws Exception {
        measure("⑤ GET /api/v1/decisions（X-User-Id: 999，无 token）",
                get("/api/v1/decisions").header("X-User-Id", "999"), 401, "UNAUTHORIZED");
        measure("⑤ POST /api/v1/ai/explanations（X-User-Id: 999，无 token）",
                explanations(null).header("X-User-Id", "999"), 401, "UNAUTHORIZED");
        // 有会话时按会话身份取数：响应 200，且不因伪造头改变归属（归属断言见各控制器单测）
        measure("⑤ GET /api/v1/decisions（X-User-Id: 999 + analyst 会话）",
                get("/api/v1/decisions").header("X-User-Id", "999")
                        .header("Authorization", "Bearer analyst-token"), 200, "OK");
    }

    @Test
    @DisplayName("把实测结果落盘，供交付报告逐条引用")
    void dumpMeasurements() throws Exception {
        unauthenticatedIs401();
        analystAuditIs403();
        operatorUserAdminIs403();
        adminExplanationIsComplete();
        forgedHeaderIsRejected();

        Path output = Path.of(System.getProperty("user.dir"), "target", "r8-3-auth-responses.txt");
        Files.createDirectories(output.getParent());
        Files.write(output, measurements, StandardCharsets.UTF_8);
        assertTrue(Files.size(output) > 0, "实测结果文件不应为空");
    }

    /** 证据解释请求：body 与主会话给的验收样例一致 */
    private static MockHttpServletRequestBuilder explanations(String token) {
        MockHttpServletRequestBuilder builder = post("/api/v1/ai/explanations")
                .contentType("application/json")
                .content("{\"snapshotId\":\"" + EvidenceTestFixtures.SNAPSHOT
                        + "\",\"timeRange\":\"2026-09-01\",\"question\":\"为什么退款率偏高\"}");
        return token == null ? builder : builder.header("Authorization", "Bearer " + token);
    }

    /** 发一个真实请求，断言状态码与业务 code，把「状态码 + 响应体」记进清单并返回响应体 */
    private String measure(String label, MockHttpServletRequestBuilder builder,
                           int expectedStatus, String expectedCode) throws Exception {
        MvcResult result = mockMvc.perform(builder).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        measurements.add(label + " → HTTP " + status + " " + body);
        assertEquals(expectedStatus, status, label + " 状态码不符，响应体: " + body);
        if (!"OK".equals(expectedCode)) {
            assertTrue(body.contains("\"code\":\"" + expectedCode + "\""),
                    label + " 响应 code 不符: " + body);
        } else {
            assertTrue(body.contains("\"code\":\"OK\""), label + " 应成功: " + body);
        }
        return body;
    }

    /** 供阅读的字段说明（避免 IDE 提示未使用） */
    static Map<String, String> contractFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("code", "稳定错误码（UNAUTHORIZED / FORBIDDEN_PERMISSION / OK）");
        fields.put("message", "面向调用方的中文原因");
        fields.put("traceId", "本次请求链路 id（与审计行一致）");
        return fields;
    }
}
