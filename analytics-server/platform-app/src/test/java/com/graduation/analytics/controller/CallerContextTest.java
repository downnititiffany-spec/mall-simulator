package com.graduation.analytics.controller;

import com.graduation.analytics.auth.AuthenticationRequiredException;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.decision.AuditActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调用者上下文（R8-3 §3.1/§21.2）：身份只来自登录会话，请求头不可自报。
 */
class CallerContextTest {

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
    }

    @Test
    @DisplayName("无登录态 → AuthenticationRequiredException（UNAUTHORIZED），不回退 demo/admin")
    void noSessionFailsClosed() {
        AuthenticationRequiredException e = assertThrows(AuthenticationRequiredException.class,
                CallerContext::requireUser);
        assertEquals("UNAUTHORIZED", AuthenticationRequiredException.CODE);
        assertTrue(e.getMessage().contains("§21.2") || e.getMessage().contains("匿名"),
                "异常信息应说明禁止匿名身份: " + e.getMessage());
        assertThrows(AuthenticationRequiredException.class, CallerContext::requireUserId);
    }

    @Test
    @DisplayName("即使带 X-User-Id / X-Username 头，没有会话依然 401")
    void headersCannotForgeIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/decisions");
        request.addHeader("X-User-Id", "admin");
        request.addHeader("X-Username", "admin");
        request.addHeader("X-Role", "admin");

        assertThrows(AuthenticationRequiredException.class, () -> CallerContext.actor(request, "trace-1"));
        assertThrows(AuthenticationRequiredException.class, () -> CallerContext.requireUserId());
    }

    @Test
    @DisplayName("登录后身份取自会话：userId/role 来自 CurrentUser，ip 取 X-Forwarded-For 首段")
    void identityFromSession() {
        CurrentUserHolder.set(new CurrentUser(3L, "analyst1", "analyst"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/decisions");
        request.addHeader("X-Forwarded-For", "10.1.2.3, 10.9.9.9");
        request.addHeader("X-User-Id", "admin"); // 伪造头必须被忽略

        AuditActor actor = CallerContext.actor(request, "trace-abc");

        assertEquals("trace-abc", actor.traceId());
        assertEquals("analyst1", actor.userId());
        assertEquals("analyst", actor.role());
        assertEquals("10.1.2.3", actor.ip());
        assertEquals("analyst1", CallerContext.requireUserId());
    }

    @Test
    @DisplayName("无 X-Forwarded-For 时用 remoteAddr；空串头按缺省处理")
    void clientIpFallback() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/decisions");
        request.setRemoteAddr("127.0.0.1");
        assertEquals("127.0.0.1", CallerContext.clientIp(request));

        request.addHeader("X-Forwarded-For", "   ");
        assertEquals("127.0.0.1", CallerContext.clientIp(request));

        assertNull(CallerContext.clientIp(null));
    }

    @Test
    @DisplayName("会话用户名为空（脏数据）等同未登录")
    void blankUsernameTreatedAsAnonymous() {
        CurrentUserHolder.set(new CurrentUser(1L, "   ", "admin"));
        assertThrows(AuthenticationRequiredException.class, CallerContext::requireUser);
    }

    @Test
    @DisplayName("清理登录态后立即回到 401（ThreadLocal 不跨请求保留）")
    void clearedHolderDenies() {
        CurrentUserHolder.set(new CurrentUser(1L, "admin", "admin"));
        assertEquals("admin", CallerContext.requireUserId());
        CurrentUserHolder.clear();
        assertThrows(AuthenticationRequiredException.class, CallerContext::requireUserId);
    }
}
