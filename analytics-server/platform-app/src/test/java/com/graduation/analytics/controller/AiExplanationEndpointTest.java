package com.graduation.analytics.controller;

import com.graduation.analytics.ai.ExplanationService;
import com.graduation.analytics.ai.TextToSqlService;
import com.graduation.analytics.ai.evidence.EvidenceBuilder;
import com.graduation.analytics.ai.evidence.EvidenceRequest;
import com.graduation.analytics.ai.evidence.EvidenceService;
import com.graduation.analytics.ai.llm.LlmProvider;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.mapper.AiQueryHistoryMapper;
import com.graduation.analytics.ai.sql.SqlExecutor;
import com.graduation.analytics.auth.AuthenticationRequiredException;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.decision.OperationAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 证据解释端点 {@code POST /api/v1/ai/explanations}（R8-2 §1）。
 *
 * <p>用**真实**的 {@link EvidenceService} + {@link ExplanationService} + 固定模板，只把取数
 * （{@link EvidenceBuilder}）换成桩：段落结构、段落顺序、providerUsed 判定都是真实代码产出的，
 * 不是测试里另写一套。核心断言 = §19.3「模型不可用也必须有完整结论」。</p>
 */
class AiExplanationEndpointTest {

    private EvidenceBuilder builder;
    private LlmProvider llm;
    private OperationAuditService audit;
    private AiController controller;

    @BeforeEach
    void setUp() {
        builder = mock(EvidenceBuilder.class);
        llm = mock(LlmProvider.class);
        audit = mock(OperationAuditService.class);
        when(llm.healthCheck()).thenReturn(false); // 默认：模型不可用
        ExplanationService explanationService = new ExplanationService(llm,
                mock(AiCallLogMapper.class), mock(SqlExecutor.class));
        EvidenceService evidenceService = new EvidenceService(builder, explanationService);
        controller = new AiController(mock(TextToSqlService.class), explanationService, evidenceService,
                mock(AiQueryHistoryMapper.class), mock(AiCallLogMapper.class), audit);
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
    }

    @Test
    @DisplayName("未登录调证据解释 → 401，且不建包、不写审计（§21.2）")
    void explainRequiresLogin() {
        AuthenticationRequiredException ex = assertThrows(AuthenticationRequiredException.class,
                () -> controller.explain(new AiController.ExplainReq(
                        EvidenceTestFixtures.SNAPSHOT, "2026-09-01", "为什么退款率偏高"), request()));
        assertEquals("UNAUTHORIZED", AuthenticationRequiredException.CODE);
        assertTrue(ex.getMessage().contains("§21.2"), ex.getMessage());
        verifyNoInteractions(builder, audit);
    }

    @Test
    @DisplayName("伪造 X-User-Id 头无效：请求人永远是会话用户")
    void forgedHeaderIsIgnored() {
        CurrentUserHolder.set(new CurrentUser(1L, "admin", "admin"));
        when(builder.build(any())).thenReturn(EvidenceTestFixtures.packageOf(EvidenceTestFixtures.SNAPSHOT));
        MockHttpServletRequest request = request();
        request.addHeader("X-User-Id", "999");

        controller.explain(new AiController.ExplainReq(null, null, "退款率"), request);

        ArgumentCaptor<EvidenceRequest> captor = ArgumentCaptor.forClass(EvidenceRequest.class);
        verify(builder).build(captor.capture());
        assertEquals("admin", captor.getValue().requestedBy(), "请求人必须来自登录会话");
    }

    @Test
    @DisplayName("模型不可用：六段模板照出，providerUsed=template，facts 非空，快照号真实")
    void templateNarrativeWhenModelUnavailable() {
        CurrentUserHolder.set(new CurrentUser(1L, "admin", "admin"));
        when(builder.build(any())).thenReturn(EvidenceTestFixtures.packageOf(EvidenceTestFixtures.SNAPSHOT));

        AiController.EvidenceExplanationResp data = controller.explain(new AiController.ExplainReq(
                EvidenceTestFixtures.SNAPSHOT, "2026-09-01", "为什么退款率偏高"), request()).data();

        assertEquals(EvidenceTestFixtures.SNAPSHOT, data.evidence().snapshotId());
        assertEquals(2, data.evidence().facts().size(), "证据包 facts 必须原样带出");

        AiController.ExplanationNarrative narrative = data.narrative();
        assertEquals(6, narrative.sections().size(), "固定模板必须是六段");
        assertEquals(List.of("发生了什么", "与上期相比", "维度贡献", "数据质量可信度", "核查行动", "限制"),
                narrative.sections().stream().map(AiController.ExplanationSection::title).toList());
        assertFalse(narrative.sections().get(0).lines().isEmpty(), "第一段必须有事实行");
        assertEquals("template", narrative.providerUsed(), "模型不可用时 providerUsed=template");
        assertEquals("evidence_v1", narrative.templateVersion());
        assertTrue(narrative.summary().contains("快照 " + EvidenceTestFixtures.SNAPSHOT), narrative.summary());
        assertTrue(narrative.limitations().stream().anyMatch(l -> l.contains("未调用大模型")),
                "必须显式说明结论来自固定模板: " + narrative.limitations());
    }

    @Test
    @DisplayName("question 只用于解释措辞：不进建包请求（更不能被当 SQL 执行）")
    void questionNeverReachesEvidenceBuild() {
        CurrentUserHolder.set(new CurrentUser(2L, "dev1", "data_dev"));
        when(builder.build(any())).thenReturn(EvidenceTestFixtures.packageOf(EvidenceTestFixtures.SNAPSHOT));

        controller.explain(new AiController.ExplainReq(null, "2026-09-01", "为什么退款率偏高"), request());

        ArgumentCaptor<EvidenceRequest> captor = ArgumentCaptor.forClass(EvidenceRequest.class);
        verify(builder).build(captor.capture());
        EvidenceRequest sent = captor.getValue();
        assertNull(sent.snapshotId(), "未指定快照 → 由证据包取 ACTIVE");
        assertNull(sent.question(), "question 不进建包/取数");
        assertEquals("2026-09-01", sent.timeRange());
        assertEquals("dev1", sent.requestedBy());
    }

    @Test
    @DisplayName("模型可用且改写通过数值守卫 → providerUsed=llm")
    void providerUsedIsLlmWhenRewriteAccepted() {
        CurrentUserHolder.set(new CurrentUser(1L, "admin", "admin"));
        when(builder.build(any())).thenReturn(EvidenceTestFixtures.packageOf(EvidenceTestFixtures.SNAPSHOT));
        when(llm.healthCheck()).thenReturn(true);
        when(llm.complete(any())).thenReturn(new LlmProvider.AiResponse(
                "本期经营平稳，未命中候选异常。", 12, 6, "mock"));

        AiController.ExplanationNarrative narrative = controller.explain(
                new AiController.ExplainReq(null, null, "整体情况如何"), request()).data().narrative();

        assertEquals("llm", narrative.providerUsed());
        assertEquals("本期经营平稳，未命中候选异常。", narrative.summary());
        assertEquals(6, narrative.sections().size(), "段落仍来自固定模板");
    }

    @Test
    @DisplayName("证据解释记一行 AI_QUERY 审计：摘要只落哈希，不落问题原文")
    void explanationIsAudited() {
        CurrentUserHolder.set(new CurrentUser(1L, "admin", "admin"));
        when(builder.build(any())).thenReturn(EvidenceTestFixtures.packageOf(EvidenceTestFixtures.SNAPSHOT));

        controller.explain(new AiController.ExplainReq(EvidenceTestFixtures.SNAPSHOT, null,
                "为什么退款率偏高"), request());

        ArgumentCaptor<String> digestCaptor = ArgumentCaptor.forClass(String.class);
        verify(audit).success(any(), eq(OperationAuditService.ACTION_AI_QUERY),
                eq(OperationAuditService.RESOURCE_AI_QUERY), eq(EvidenceTestFixtures.EVIDENCE_ID),
                eq(null), digestCaptor.capture(), anyString());
        String digest = digestCaptor.getValue();
        assertTrue(digest.contains("snapshot=" + EvidenceTestFixtures.SNAPSHOT), digest);
        assertTrue(digest.contains("qualityGate=PASS"), digest);
        assertFalse(digest.contains("为什么退款率偏高"), "审计摘要不得含问题原文");
    }

    @Test
    @DisplayName("审计表不可用不拖垮只读解释（V14 未执行时不返回 5xx）")
    void auditFailureDoesNotBreakExplanation() {
        CurrentUserHolder.set(new CurrentUser(1L, "admin", "admin"));
        when(builder.build(any())).thenReturn(EvidenceTestFixtures.packageOf(EvidenceTestFixtures.SNAPSHOT));
        doThrow(new RuntimeException("Table 'analytics_meta.operation_audit_log' doesn't exist"))
                .when(audit).success(any(), anyString(), anyString(), any(), any(), anyString(), anyString());

        AiController.ExplanationNarrative narrative = controller.explain(
                new AiController.ExplainReq(null, null, null), request()).data().narrative();

        assertEquals(6, narrative.sections().size());
        assertEquals("template", narrative.providerUsed());
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/ai/explanations");
    }
}
