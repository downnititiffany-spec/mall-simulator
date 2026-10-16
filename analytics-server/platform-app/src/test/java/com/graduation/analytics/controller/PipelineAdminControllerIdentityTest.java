package com.graduation.analytics.controller;

import com.graduation.analytics.auth.AuthenticationRequiredException;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.pipeline.PipelineRecoveryService;
import com.graduation.analytics.pipeline.PipelineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PipelineAdminControllerIdentityTest {

    private final PipelineService pipelineService = mock(PipelineService.class);
    private final PipelineAdminController controller = new PipelineAdminController(
            pipelineService, mock(PipelineRecoveryService.class));

    @AfterEach
    void clearSession() {
        CurrentUserHolder.clear();
    }

    @Test
    @DisplayName("恢复、失败标记、阶段重试和重算均使用登录会话身份")
    void allMutationActorsComeFromAuthenticatedSession() {
        CurrentUserHolder.set(new CurrentUser(12L, "trusted-operator", "data_dev"));

        controller.resume(21L, "resume reason");
        controller.markFailed(22L, "failure reason");
        controller.retryFromStage(23L, "BUILD_DWS", "retry reason");
        // 旧客户端可能仍发送 operator 字段；它必须被忽略。
        controller.recalculate(new PipelineAdminController.RecalculateReq(
                5L, 8L, "forged-operator", "recalculate reason"));

        verify(pipelineService).resume(eq(21L), eq("trusted-operator"), eq("resume reason"), anyString());
        verify(pipelineService).markFailed(22L, "trusted-operator", "failure reason");
        verify(pipelineService).retryFromStage(eq(23L), eq("BUILD_DWS"), eq("trusted-operator"),
                eq("retry reason"), anyString());
        verify(pipelineService).recalculate(eq(5L), eq(8L), eq("trusted-operator"),
                eq("recalculate reason"), anyString());
    }

    @Test
    @DisplayName("没有已认证会话时 fail-closed，不调用恢复服务")
    void missingSessionCannotMutateRuns() {
        assertThrows(AuthenticationRequiredException.class, () -> controller.resume(21L, "reason"));
        assertThrows(AuthenticationRequiredException.class, () -> controller.markFailed(22L, "reason"));
        assertThrows(AuthenticationRequiredException.class,
                () -> controller.retryFromStage(23L, "BUILD_DWS", "reason"));
        assertThrows(AuthenticationRequiredException.class, () -> controller.recalculate(
                new PipelineAdminController.RecalculateReq(5L, 8L, "forged-operator", "reason")));
        verifyNoInteractions(pipelineService);
    }
}
