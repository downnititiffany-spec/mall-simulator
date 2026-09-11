package com.graduation.analytics.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 登录鉴权 + permissionCode 鉴权（R8-3 契约 §3.1/§3.2、V2.0 §21.1/§21.2/§24.9）。
 *
 * <p>执行顺序：白名单短路 → 校验 Bearer token（失败 401 {@code UNAUTHORIZED}）→
 * 仅登录态接口短路 → 读取 {@link RequiresPermission}（方法级优先，其次类级）→
 * 查 {@link RolePermissions} 矩阵（失败 403 {@code FORBIDDEN_PERMISSION}）→ 写入 {@link CurrentUserHolder}。</p>
 *
 * <p>与 R8 之前实现的区别（反熵）：**删除**「按 URL 前缀猜 admin」的规则。
 * 前缀只能表达「是不是 admin」，既管不住 data_dev/operator，也会随新增路径静默放宽；
 * 现在未声明权限码的接口一律拒绝（fail-closed），权限矩阵是唯一判据。</p>
 *
 * <p>ThreadLocal 纪律：只有校验通过才 {@link CurrentUserHolder#set}；
 * 拒绝路径显式 {@link CurrentUserHolder#clear()}（Spring 对 preHandle 返回 false 的拦截器
 * **不会**回调 afterCompletion，不清理就会把身份残留给同线程的下一个请求）。</p>
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 白名单：跳过一切校验（登录接口本身、健康检查） */
    private static final List<String> WHITELIST_PATHS = List.of(
            "/api/v1/auth/login", "/api/v1/metrics/health", "/api/v1/health");

    /**
     * 仅需登录态、无权限码语义的接口（§3.2 矩阵未列这些动作）：
     * 登出与「当前用户」是会话自身操作，任何已登录角色都必须可用。
     */
    private static final Set<String> SESSION_ONLY_PATHS = Set.of(
            "/api/v1/auth/logout", "/api/v1/auth/me");

    /** 无权限 */
    private static final String FORBIDDEN = "FORBIDDEN";
    /** 缺少所需权限码（R8-3 契约 §3.2 冻结码） */
    private static final String FORBIDDEN_PERMISSION = "FORBIDDEN_PERMISSION";

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
            return deny(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "未登录或会话已过期");
        }

        // 会话自身操作：登录态校验通过即可
        if (SESSION_ONLY_PATHS.contains(uri)) {
            CurrentUserHolder.set(current);
            return true;
        }

        // 角色必须在权限矩阵内：未知/空角色不能因为「查表查不到」而落到任何权限
        if (!RolePermissions.knownRole(current.role())) {
            return deny(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN,
                    "当前账号未分配有效角色，无法访问受保护资源");
        }

        // 权限码判定：注解 + 角色矩阵
        String required = requiredPermission(handler);
        if (required == null) {
            // 未声明权限码 → 拒绝（fail-closed）。出现该分支说明有接口漏加注解，
            // ControllerPermissionCoverageTest 会在构建期把这种情况拦住。
            return deny(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN_PERMISSION,
                    "接口未声明权限码，按最小权限拒绝访问: " + request.getMethod() + " " + uri);
        }
        if (!RolePermissions.has(current.role(), required)) {
            return deny(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN_PERMISSION,
                    "无权访问该资源，需要权限: " + required);
        }

        CurrentUserHolder.set(current);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CurrentUserHolder.clear();
    }

    /** 取方法级注解，其次类级；都没有返回 null */
    private String requiredPermission(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return null;
        }
        RequiresPermission onMethod = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequiresPermission.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        RequiresPermission onType = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getBeanType(), RequiresPermission.class);
        return onType == null ? null : onType.value();
    }

    private String resolveToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** 拒绝：清 ThreadLocal（返回 false 时 Spring 不会回调 afterCompletion）并写统一 JSON 错误体 */
    private boolean deny(HttpServletResponse response, int status, String code, String message) throws IOException {
        CurrentUserHolder.clear();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message, TraceContext.create().traceId()));
        return false;
    }
}
