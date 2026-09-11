package com.graduation.analytics.auth;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.decision.AuditActor;
import com.graduation.analytics.decision.OperationAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户管理审计（§21.4 + §21.3 脱敏）：三个写动作都留痕，摘要里绝不能出现密码。
 */
class UserAdminControllerAuditTest {

    private static final String RAW_PASSWORD = "Sup3rSecret!";

    private AuthService authService;
    private OperationAuditService audit;
    private UserAdminController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        audit = mock(OperationAuditService.class);
        controller = new UserAdminController(authService, audit);
        CurrentUserHolder.set(new CurrentUser(1L, "admin", "admin"));
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
    }

    @Test
    @DisplayName("创建用户成功：审计摘要含 username/role，绝不含密码")
    void createAuditHidesPassword() {
        when(authService.createUser(anyString(), any(), anyString(), anyString()))
                .thenReturn(new AuthService.UserView(9L, "dev1", "张三", "data_dev"));

        controller.create(new UserAdminController.CreateUserReq("dev1", "张三", "data_dev", RAW_PASSWORD), request());

        ArgumentCaptor<AuditActor> actorCaptor = ArgumentCaptor.forClass(AuditActor.class);
        ArgumentCaptor<String> digestCaptor = ArgumentCaptor.forClass(String.class);
        verify(audit).success(actorCaptor.capture(), eq(OperationAuditService.ACTION_USER_CREATE),
                eq(OperationAuditService.RESOURCE_SYS_USER), eq("9"), eq(null), digestCaptor.capture(), anyString());

        assertEquals("admin", actorCaptor.getValue().userId());
        String digest = digestCaptor.getValue();
        assertTrue(digest.contains("username=dev1"), digest);
        assertTrue(digest.contains("role=data_dev"), digest);
        assertFalse(digest.contains(RAW_PASSWORD), "审计摘要禁止出现密码明文: " + digest);
    }

    @Test
    @DisplayName("创建用户失败（重复用户名）：写 FAILED 行且仍不含密码")
    void createFailureAuditHidesPassword() {
        when(authService.createUser(anyString(), any(), anyString(), anyString()))
                .thenThrow(new PlatformBizException("USER_EXISTS", "用户名已存在: dev1"));

        assertThrows(PlatformBizException.class, () -> controller.create(
                new UserAdminController.CreateUserReq("dev1", "张三", "data_dev", RAW_PASSWORD), request()));

        ArgumentCaptor<String> digestCaptor = ArgumentCaptor.forClass(String.class);
        verify(audit).failure(any(), eq(OperationAuditService.ACTION_USER_CREATE),
                eq(OperationAuditService.RESOURCE_SYS_USER), eq("dev1"), eq(null), digestCaptor.capture(), anyString());
        assertFalse(digestCaptor.getValue().contains(RAW_PASSWORD), digestCaptor.getValue());
        assertTrue(digestCaptor.getValue().contains("role=data_dev"), digestCaptor.getValue());
    }

    @Test
    @DisplayName("重置密码：审计只记 password=***")
    void resetPasswordAuditMasksSecret() {
        controller.resetPassword(5L,
                new UserAdminController.ResetPasswordReq(RAW_PASSWORD), request());

        ArgumentCaptor<String> digestCaptor = ArgumentCaptor.forClass(String.class);
        verify(audit).success(any(), eq(OperationAuditService.ACTION_USER_RESET_PASSWORD),
                eq(OperationAuditService.RESOURCE_SYS_USER), eq("5"), eq(null), digestCaptor.capture(), anyString());
        String digest = digestCaptor.getValue();
        assertTrue(digest.contains("password=***"), digest);
        assertFalse(digest.contains(RAW_PASSWORD), digest);
    }

    @Test
    @DisplayName("重置密码失败：digest 里密码位仍是 ***（失败路径最容易漏脱敏）")
    void resetPasswordFailureAuditMasksSecret() {
        doThrow(new PlatformBizException(PlatformBizException.PARAM_INVALID, "新密码至少 6 位"))
                .when(authService).resetPassword(anyLong(), anyString());

        assertThrows(PlatformBizException.class, () -> controller.resetPassword(5L,
                new UserAdminController.ResetPasswordReq(RAW_PASSWORD), request()));

        ArgumentCaptor<String> digestCaptor = ArgumentCaptor.forClass(String.class);
        verify(audit).failure(any(), eq(OperationAuditService.ACTION_USER_RESET_PASSWORD),
                eq(OperationAuditService.RESOURCE_SYS_USER), eq("5"), eq(null), digestCaptor.capture(), anyString());
        assertEquals("password=***", digestCaptor.getValue());
        assertFalse(digestCaptor.getValue().contains(RAW_PASSWORD));
    }

    @Test
    @DisplayName("启停账号：操作者取会话（不能停用自己），审计记 enable 与 userId")
    void toggleAuditUsesSessionActor() {
        controller.toggle(6L, new UserAdminController.ToggleReq(true), request());

        verify(authService).toggleUser(eq(6L), eq(true), any(CurrentUser.class));
        ArgumentCaptor<AuditActor> actorCaptor = ArgumentCaptor.forClass(AuditActor.class);
        ArgumentCaptor<String> digestCaptor = ArgumentCaptor.forClass(String.class);
        verify(audit).success(actorCaptor.capture(), eq(OperationAuditService.ACTION_USER_TOGGLE),
                eq(OperationAuditService.RESOURCE_SYS_USER), eq("6"), eq(null), digestCaptor.capture(), anyString());
        assertEquals("admin", actorCaptor.getValue().userId());
        assertTrue(digestCaptor.getValue().contains("enable=true"), digestCaptor.getValue());
    }

    @Test
    @DisplayName("启停失败（不能停用当前登录账号）：写 FAILED 行")
    void toggleFailureAudited() {
        doThrow(new PlatformBizException("FORBIDDEN_OPERATION", "不能停用当前登录账号"))
                .when(authService).toggleUser(anyLong(), anyBoolean(), any());

        assertThrows(PlatformBizException.class,
                () -> controller.toggle(1L, new UserAdminController.ToggleReq(false), request()));

        verify(audit).failure(any(), eq(OperationAuditService.ACTION_USER_TOGGLE),
                eq(OperationAuditService.RESOURCE_SYS_USER), eq("1"), eq(null), anyString(), anyString());
    }

    @Test
    @DisplayName("未登录时不得触达用户管理服务（身份缺失优先于参数校验）")
    void anonymousCannotManageUsers() {
        CurrentUserHolder.clear();
        assertThrows(AuthenticationRequiredException.class, () -> controller.create(
                new UserAdminController.CreateUserReq("dev1", "张三", "data_dev", RAW_PASSWORD), request()));
        verify(authService, never()).createUser(anyString(), any(), anyString(), anyString());
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/admin/users");
    }
}
