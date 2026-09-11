package com.graduation.analytics.controller;

import com.graduation.analytics.auth.AuthenticationRequiredException;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.decision.AuditActor;
import com.graduation.analytics.decision.DecisionService;
import com.graduation.analytics.decision.OperationAuditService;
import com.graduation.analytics.decision.entity.DecisionTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 决策控制器身份（R8-3 §3.1/§21.2）：无登录态一律 401 且不触达业务层；X-User-Id 头不再有任何作用。
 */
class DecisionControllerIdentityTest {

    private DecisionService decisionService;
    private OperationAuditService audit;
    private DecisionController controller;

    @BeforeEach
    void setUp() {
        decisionService = mock(DecisionService.class);
        audit = mock(OperationAuditService.class);
        controller = new DecisionController(decisionService, audit);
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
    }

    @Test
    @DisplayName("未登录：八个写动作全部 401 且不触达 DecisionService / 审计")
    void allActionsRequireLogin() {
        DecisionService.CreateDraftReq create = new DecisionService.CreateDraftReq(
                "t", "a", "order_paid_amount", "UP", null, null, null, null);
        DecisionService.ApproveReq approve = new DecisionService.ApproveReq(
                "alice", java.time.LocalDate.now(), null, 3, null);
        DecisionService.ReasonReq reason = new DecisionService.ReasonReq("理由");
        DecisionService.ExecuteReq execute = new DecisionService.ExecuteReq("备注");
        MockHttpServletRequest request = request();

        List<Runnable> actions = List.of(
                () -> controller.createDraft(create, request),
                () -> controller.submit(1L, null, request),
                () -> controller.approve(1L, approve, request),
                () -> controller.reject(1L, reason, request),
                () -> controller.start(1L, request),
                () -> controller.complete(1L, execute, request),
                () -> controller.cancel(1L, reason, request),
                () -> controller.evaluate(1L, request));

        for (Runnable action : actions) {
            assertThrows(AuthenticationRequiredException.class, action::run);
        }
        verifyNoInteractions(decisionService);
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("带 X-User-Id: admin 但没有会话 → 依然 401（头部伪造已退役）")
    void forgedHeaderStillUnauthorized() {
        MockHttpServletRequest request = request();
        request.addHeader("X-User-Id", "admin");
        request.addHeader("X-Username", "admin");

        assertThrows(AuthenticationRequiredException.class,
                () -> controller.createDraft(new DecisionService.CreateDraftReq(
                        "t", "a", "order_paid_amount", "UP", null, null, null, null), request));
        verifyNoInteractions(decisionService);
    }

    @Test
    @DisplayName("登录后创建者取会话身份（analyst1），请求头伪造的 admin 被忽略")
    void createdByComesFromSession() {
        CurrentUserHolder.set(new CurrentUser(3L, "analyst1", "analyst"));
        MockHttpServletRequest request = request();
        request.addHeader("X-User-Id", "admin");
        DecisionTask task = new DecisionTask();
        task.setId(9L);
        when(decisionService.createDraft(any(), any(), any())).thenReturn(task);

        controller.createDraft(new DecisionService.CreateDraftReq(
                "提高支付转化", "调整详情页", "order_paid_amount", "UP", "S1", "LOW", null, null), request);

        ArgumentCaptor<AuditActor> actorCaptor = ArgumentCaptor.forClass(AuditActor.class);
        verify(decisionService).createDraft(any(), actorCaptor.capture(), eq("ai"));
        assertEquals("analyst1", actorCaptor.getValue().userId());
        assertEquals("analyst", actorCaptor.getValue().role());
        assertNotNull(actorCaptor.getValue().traceId());
    }

    @Test
    @DisplayName("审批人/评价人都取会话身份，不由调用方传入")
    void approveAndEvaluateUseSessionIdentity() {
        CurrentUserHolder.set(new CurrentUser(4L, "operator1", "operator"));
        MockHttpServletRequest request = request();
        when(decisionService.approve(any(), any(), any())).thenReturn(new DecisionTask());
        when(decisionService.evaluate(any(), any())).thenReturn(new com.graduation.analytics.decision.entity.DecisionEvaluation());

        controller.approve(1L, new DecisionService.ApproveReq("alice", java.time.LocalDate.now(), null, 3, null), request);
        controller.evaluate(1L, request);

        ArgumentCaptor<AuditActor> captor = ArgumentCaptor.forClass(AuditActor.class);
        verify(decisionService).approve(eq(1L), any(), captor.capture());
        verify(decisionService).evaluate(eq(1L), captor.capture());
        assertEquals("operator1", captor.getValue().userId());
    }

    @Test
    @DisplayName("读接口（列表/评价历史）不需要登录态判断，直接走服务（权限由拦截器把关）")
    void readEndpointsDelegate() {
        when(decisionService.list(20)).thenReturn(List.of());
        when(decisionService.evaluations(1L)).thenReturn(List.of());

        assertEquals("OK", controller.list(20).code());
        assertEquals("OK", controller.evaluations(1L).code());
        verify(decisionService).list(20);
        verify(decisionService).evaluations(1L);
        verify(audit, never()).failure(any(), any(), any(), any(), any(), any(), any());
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/decisions");
    }
}
