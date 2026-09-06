package com.graduation.mall.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.mall.common.ApiResponse;
import com.graduation.mall.outbox.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 登录鉴权 + 基于角色的 API 鉴权（§3.2 / §21.5）：
 * 解析 Authorization: Bearer &lt;token&gt; → AuthService.validate →
 * 未通过直接写 401 JSON；通过后按 admin 专属路径前缀做角色校验（非 admin → 403 JSON）。
 * 白名单路径在 AuthConfig 注册时排除，此处再兜底短路一次。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 白名单：跳过一切校验 */
    private static final List<String> WHITELIST_PATHS = List.of(
            "/api/v1/auth/login",
            "/api/v1/metrics/health");

    /** admin 专属路径前缀：非 admin 拒绝（403） */
    private static final List<String> ADMIN_ONLY_PREFIXES = List.of(
            "/api/v1/generator",
            "/api/v1/ingestion",
            "/api/v1/pipeline-runs",
            "/api/v1/mall/outbox",
            "/api/v1/metrics/quality",
            "/api/v1/ai/audit");

    private static final String ROLE_ADMIN = "admin";

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI().substring(request.getContextPath().length());

        // 白名单：跳过一切校验
        if (WHITELIST_PATHS.contains(uri)) {
            return true;
        }

        // 解析并校验 Bearer token
        String token = resolveToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        CurrentUser current = authService.validate(token);
        if (current == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "未登录或会话已过期");
            return false;
        }

        // 角色规则：admin 专属路径前缀
        boolean adminOnly = ADMIN_ONLY_PREFIXES.stream().anyMatch(uri::startsWith);
        if (adminOnly && !ROLE_ADMIN.equals(current.role())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "无权访问该资源，需要管理员权限");
            return false;
        }

        CurrentUserHolder.set(current);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CurrentUserHolder.clear();
    }

    private String resolveToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message, TraceContext.create().traceId()));
    }
}