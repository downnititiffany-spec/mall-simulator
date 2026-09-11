package com.graduation.analytics.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拦截器（R8-3 §3.1/§3.2）：身份校验 + 权限码判定的真实行为，含 401/403 响应体与 ThreadLocal 纪律。
 */
class AuthInterceptorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AuthService authService;
    private AuthInterceptor interceptor;
    private FixtureController fixture;

    @BeforeEach
    void setUp() throws Exception {
        authService = mock(AuthService.class);
        interceptor = new AuthInterceptor(authService, objectMapper);
        fixture = new FixtureController();
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
    }

    @Test
    @DisplayName("无 token → 401 UNAUTHORIZED（不是 500/200），且不写身份")
    void noTokenIsUnauthorized() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request("/api/v1/decisions", null),
                response, handler("adminOnly"));

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
        assertEquals("UNAUTHORIZED", body(response).path("code").asText());
        assertNull(CurrentUserHolder.get(), "拒绝后不得残留身份");
    }

    @Test
    @DisplayName("token 无效/过期（validate 返回 null）→ 401")
    void invalidTokenIsUnauthorized() throws Exception {
        when(authService.validate("bad")).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request("/api/v1/decisions", "bad"), response, handler("anyRole")));
        assertEquals(401, response.getStatus());
        assertEquals("UNAUTHORIZED", body(response).path("code").asText());
    }

    @Test
    @DisplayName("analyst 访问 ai:audit:view 接口 → 403 FORBIDDEN_PERMISSION（契约要求的越权样例）")
    void analystCannotViewAiAudit() throws Exception {
        when(authService.validate("tok")).thenReturn(new CurrentUser(3L, "analyst1", "analyst"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request("/api/v1/ai/audit/history", "tok"), response,
                handler("aiAudit")));
        assertEquals(403, response.getStatus());
        JsonNode body = body(response);
        assertEquals("FORBIDDEN_PERMISSION", body.path("code").asText());
        assertTrue(body.path("message").asText().contains("ai:audit:view"), body.path("message").asText());
        assertNull(CurrentUserHolder.get());
    }

    @Test
    @DisplayName("admin 访问 ai:audit:view 接口 → 放行，并写入登录身份")
    void adminCanViewAiAudit() throws Exception {
        when(authService.validate("tok")).thenReturn(new CurrentUser(1L, "admin", "admin"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request("/api/v1/ai/audit/history", "tok"), response,
                handler("aiAudit")));
        assertEquals(200, response.getStatus());
        assertEquals("admin", CurrentUserHolder.get().username());
        assertEquals("admin", CurrentUserHolder.get().role());
    }

    @Test
    @DisplayName("operator 不能 user:manage（越权样例）；四种角色都能 dashboard:view / ai:query / decision:create")
    void roleMatrixEnforcedOnRealHandlers() throws Exception {
        for (String role : new String[]{"admin", "data_dev", "operator", "analyst"}) {
            setUp();
            when(authService.validate("tok")).thenReturn(new CurrentUser(9L, role + "-user", role));
            assertTrue(interceptor.preHandle(request("/api/v1/dashboards/overview", "tok"),
                    new MockHttpServletResponse(), handler("dashboard")), role + " 应能看看板");
            assertTrue(interceptor.preHandle(request("/api/v1/ai/queries", "tok"),
                    new MockHttpServletResponse(), handler("aiQuery")), role + " 应能问数");
            assertTrue(interceptor.preHandle(request("/api/v1/decisions", "tok"),
                    new MockHttpServletResponse(), handler("decisionCreate")), role + " 应能建决策");
        }

        // operator / analyst / data_dev 访问用户管理 → 403
        for (String role : new String[]{"data_dev", "operator", "analyst"}) {
            setUp();
            when(authService.validate("tok")).thenReturn(new CurrentUser(9L, role + "-user", role));
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request("/api/v1/admin/users", "tok"), response,
                    handler("userManage")), role + " 不应能管理用户");
            assertEquals(403, response.getStatus());
            assertEquals("FORBIDDEN_PERMISSION", body(response).path("code").asText());
        }
    }

    @Test
    @DisplayName("方法级权限码优先于类级（类级 dashboard:view 不会掩盖方法级 ai:audit:view）")
    void methodLevelWinsOverClassLevel() throws Exception {
        when(authService.validate("tok")).thenReturn(new CurrentUser(3L, "analyst1", "analyst"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request("/api/v1/ai/audit/calls", "tok"), response,
                handler("classLevelWithMethodOverride")));
        assertEquals(403, response.getStatus());
        assertTrue(body(response).path("message").asText().contains("ai:audit:view"));

        // 同类中未覆盖的方法走类级 dashboard:view → analyst 放行
        assertTrue(interceptor.preHandle(request("/api/v1/dashboards/overview", "tok"),
                new MockHttpServletResponse(), handler("classLevelOnly")));
    }

    @Test
    @DisplayName("未声明权限码的接口 → 403（fail-closed），提示漏加注解")
    void unannotatedEndpointIsDenied() throws Exception {
        when(authService.validate("tok")).thenReturn(new CurrentUser(1L, "admin", "admin"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request("/api/v1/whatever", "tok"), response, handler("unannotated")));
        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN_PERMISSION", body(response).path("code").asText());
        assertTrue(body(response).path("message").asText().contains("未声明权限码"));
    }

    @Test
    @DisplayName("非矩阵角色（脏数据/新角色）→ 403 FORBIDDEN，且不因「查表为空」放行")
    void unknownRoleForbidden() throws Exception {
        when(authService.validate("tok")).thenReturn(new CurrentUser(7L, "ghost", "guest"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request("/api/v1/dashboards/overview", "tok"), response,
                handler("dashboard")));
        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN", body(response).path("code").asText());
        assertNull(CurrentUserHolder.get());
    }

    @Test
    @DisplayName("白名单路径不做任何校验（登录/健康检查）")
    void whitelistBypassesAuth() throws Exception {
        for (String path : new String[]{"/api/v1/auth/login", "/api/v1/metrics/health", "/api/v1/health"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertTrue(interceptor.preHandle(request(path, null), response, handler("unannotated")),
                    path + " 应在白名单");
        }
        verify(authService, never()).validate(any());
    }

    @Test
    @DisplayName("会话自身接口（logout/me）只要求登录态，不要求权限码")
    void sessionOnlyPathsNeedLoginOnly() throws Exception {
        when(authService.validate("tok")).thenReturn(new CurrentUser(3L, "analyst1", "analyst"));
        assertTrue(interceptor.preHandle(request("/api/v1/auth/me", "tok"), new MockHttpServletResponse(),
                handler("unannotated")));
        assertTrue(interceptor.preHandle(request("/api/v1/auth/logout", "tok"), new MockHttpServletResponse(),
                handler("unannotated")));
        assertEquals("analyst1", CurrentUserHolder.get().username());
    }

    @Test
    @DisplayName("非 HandlerMethod（静态资源）也要权限码 → 403（fail-closed）")
    void nonHandlerMethodDenied() throws Exception {
        when(authService.validate("tok")).thenReturn(new CurrentUser(1L, "admin", "admin"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("/api/v1/whatever", "tok"), response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    @DisplayName("afterCompletion 清理身份（ThreadLocal 不跨请求泄漏）")
    void afterCompletionClearsIdentity() throws Exception {
        when(authService.validate("tok")).thenReturn(new CurrentUser(1L, "admin", "admin"));
        interceptor.preHandle(request("/api/v1/dashboards/overview", "tok"),
                new MockHttpServletResponse(), handler("dashboard"));
        assertEquals("admin", CurrentUserHolder.get().username());

        interceptor.afterCompletion(request("/api/v1/dashboards/overview", "tok"),
                new MockHttpServletResponse(), handler("dashboard"), null);
        assertNull(CurrentUserHolder.get());
    }

    @Test
    @DisplayName("X-User-Id 请求头不再影响身份（反熵：头部伪造已退役）")
    void headerUserIdIsIgnored() throws Exception {
        when(authService.validate("tok")).thenReturn(new CurrentUser(3L, "analyst1", "analyst"));
        MockHttpServletRequest request = request("/api/v1/admin/users", "tok");
        request.addHeader("X-User-Id", "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, handler("userManage")));
        assertEquals(403, response.getStatus(), "伪造 X-User-Id: admin 不得提权");
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    @RequiresPermission(PermissionCode.USER_MANAGE)
    public static class FixtureController {

        public void userManage() {
        }

        @RequiresPermission(PermissionCode.AI_AUDIT_VIEW)
        public void aiAudit() {
        }

        @RequiresPermission(PermissionCode.AI_QUERY)
        public void aiQuery() {
        }

        @RequiresPermission(PermissionCode.DECISION_CREATE)
        public void decisionCreate() {
        }

        @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
        public void dashboard() {
        }

        @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
        public void anyRole() {
        }

        @RequiresPermission(PermissionCode.DECISION_APPROVE)
        public void adminOnly() {
        }
    }

    /** 完全没有权限码声明的控制器（类级/方法级都没有） */
    public static class PlainFixture {

        public void unannotated() {
        }
    }

    /** 类级 dashboard:view + 某方法覆盖为 ai:audit:view */
    @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
    public static class OverrideFixture {

        @RequiresPermission(PermissionCode.AI_AUDIT_VIEW)
        public void audit() {
        }

        public void board() {
        }
    }

    private HandlerMethod handler(String methodName) throws Exception {
        if ("classLevelWithMethodOverride".equals(methodName)) {
            return new HandlerMethod(new OverrideFixture(), OverrideFixture.class.getMethod("audit"));
        }
        if ("classLevelOnly".equals(methodName)) {
            return new HandlerMethod(new OverrideFixture(), OverrideFixture.class.getMethod("board"));
        }
        // 未声明权限码的接口：必须用「类级也没有注解」的控制器，否则测的是类级继承而不是 fail-closed
        if ("unannotated".equals(methodName)) {
            return new HandlerMethod(new PlainFixture(), PlainFixture.class.getMethod("unannotated"));
        }
        Method method = FixtureController.class.getMethod(methodName);
        return new HandlerMethod(fixture, method);
    }

    private MockHttpServletRequest request(String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (token != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return request;
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString());
    }
}
