package com.graduation.analytics.controller;

import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.decision.AuditActor;
import com.graduation.analytics.decision.DecisionService;
import com.graduation.analytics.decision.DecisionStateMachine;
import com.graduation.analytics.decision.OperationAuditService;
import com.graduation.analytics.decision.entity.DecisionEvaluation;
import com.graduation.analytics.decision.entity.DecisionTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 决策审计（§21.4）：被拒绝的动作也要留痕（FAILED 行），且审计失败不得掩盖原始异常。
 */
class DecisionControllerAuditTest {

    private DecisionService decisionService;
    private OperationAuditService audit;
    private DecisionController controller;

    @BeforeEach
    void setUp() {
        decisionService = mock(DecisionService.class);
        audit = mock(OperationAuditService.class);
        controller = new DecisionController(decisionService, audit);
        CurrentUserHolder.set(new CurrentUser(3L, "analyst1", "analyst"));
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
    }

    @Test
    @DisplayName("非法状态流转：异常向上抛（交给 advice 映射 400）并写 FAILED 审计行")
    void illegalTransitionIsAudited() {
        DecisionStateMachine.IllegalDecisionStateException boom =
                new DecisionStateMachine.IllegalDecisionStateException("DRAFT", "COMPLETED");
        when(decisionService.submit(eq(1L), any(), any())).thenThrow(boom);

        DecisionStateMachine.IllegalDecisionStateException thrown = assertThrows(
                DecisionStateMachine.IllegalDecisionStateException.class,
                () -> controller.submit(1L, null, request()));

        assertSame(boom, thrown);
        ArgumentCaptor<AuditActor> actorCaptor = ArgumentCaptor.forClass(AuditActor.class);
        verify(audit).failure(actorCaptor.capture(), eq(OperationAuditService.ACTION_DECISION_SUBMIT),
                eq(OperationAuditService.RESOURCE_DECISION_TASK), eq("1"), eq(null), eq(null), any());
        assertEquals("analyst1", actorCaptor.getValue().userId());
    }

    @Test
    @DisplayName("参数不齐（PARAM_INVALID）：同样记 FAILED 且原因带上原始 message")
    void paramInvalidIsAudited() {
        when(decisionService.approve(eq(2L), any(), any()))
                .thenThrow(new PlatformBizException(PlatformBizException.PARAM_INVALID, "缺少 dueDate"));

        assertThrows(PlatformBizException.class, () -> controller.approve(2L,
                new DecisionService.ApproveReq("alice", null, null, 3, null), request()));

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(audit).failure(any(), eq(OperationAuditService.ACTION_DECISION_APPROVE),
                eq(OperationAuditService.RESOURCE_DECISION_TASK), eq("2"), eq(null), eq(null), reasonCaptor.capture());
        assertEquals("缺少 dueDate", reasonCaptor.getValue());
    }

    @Test
    @DisplayName("审计写入自身失败：只记日志，原始业务异常照常抛出（不掩盖）")
    void auditFailureDoesNotMaskBusinessError() {
        PlatformBizException business = new PlatformBizException(PlatformBizException.PARAM_INVALID, "驳回原因必填");
        when(decisionService.reject(eq(3L), any(), any())).thenThrow(business);
        doThrow(new RuntimeException("audit db down")).when(audit)
                .failure(any(), any(), any(), any(), any(), any(), any());

        PlatformBizException thrown = assertThrows(PlatformBizException.class,
                () -> controller.reject(3L, new DecisionService.ReasonReq(null), request()));
        assertSame(business, thrown);
    }

    @Test
    @DisplayName("成功路径：响应信封 traceId 与审计 traceId 同一个，且不写 FAILED 行")
    void successSharesTraceIdAndWritesNoFailure() {
        DecisionTask task = new DecisionTask();
        task.setId(7L);
        when(decisionService.start(eq(7L), any())).thenReturn(task);

        ApiResponse<DecisionTask> response = controller.start(7L, request());

        assertEquals("OK", response.code());
        assertSame(task, response.data());
        assertNotNull(response.traceId());
        assertFalse(response.traceId().isBlank());
        verify(audit, never()).failure(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("创建草稿失败也留痕（resourceId 为 null，因为还没有 id）")
    void createFailureAuditedWithoutResourceId() {
        when(decisionService.createDraft(any(), any(), any()))
                .thenThrow(new PlatformBizException(PlatformBizException.PARAM_INVALID, "标题必填"));

        assertThrows(PlatformBizException.class, () -> controller.createDraft(
                new DecisionService.CreateDraftReq(" ", "a", "m", "UP", null, null, null, null), request()));

        verify(audit).failure(any(), eq(OperationAuditService.ACTION_DECISION_CREATE),
                eq(OperationAuditService.RESOURCE_DECISION_TASK), eq(null), eq(null), eq(null), any());
    }

    @Test
    @DisplayName("评价动作：审计 action 为 DECISION_EVALUATE，成功结果原样返回")
    void evaluateAuditAction() {
        DecisionEvaluation evaluation = new DecisionEvaluation();
        evaluation.setResult(DecisionStateMachine.INSUFFICIENT_DATA);
        when(decisionService.evaluate(eq(5L), any())).thenReturn(evaluation);

        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, controller.evaluate(5L, request()).data().getResult());
        verify(audit, never()).failure(any(), any(), any(), any(), any(), any(), any());
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/decisions");
    }
}
