package com.graduation.analytics.controller;

import com.graduation.analytics.auth.AuthenticationRequiredException;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.decision.AuditActor;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 调用者上下文（R8-3 §3.1/§3.3）：控制层取「当前登录用户」的唯一入口。
 *
 * <p>R8-3 之前各控制器用 {@code @RequestHeader("X-User-Id")} + {@code "demo"} 兜底，
 * 等于允许任意调用方自报身份；现在只认 {@link CurrentUserHolder}（由 AuthInterceptor 在校验
 * Bearer token 后写入），拿不到就抛 {@link AuthenticationRequiredException} → 401，
 * **绝不回退请求头或 demo 用户**（§21.2）。</p>
 */
public final class CallerContext {

    private CallerContext() {
    }

    /** 当前登录用户；无登录态直接失败（fail-closed） */
    public static CurrentUser requireUser() {
        CurrentUser current = CurrentUserHolder.get();
        if (current == null || current.username() == null || current.username().isBlank()) {
            throw new AuthenticationRequiredException(
                    "缺少可信当前用户（未登录或会话已过期），拒绝以匿名身份执行（§21.2）");
        }
        return current;
    }

    /** 当前登录用户名（落库 created_by/approved_by/evaluated_by、审计 user_id 用） */
    public static String requireUserId() {
        return requireUser().username();
    }

    /** 组装审计/业务操作者：身份取自登录会话，ip 取自请求（traceId 用响应信封同一个） */
    public static AuditActor actor(HttpServletRequest request, String traceId) {
        CurrentUser current = requireUser();
        return new AuditActor(traceId, current.username(), current.role(), clientIp(request));
    }

    /** 客户端 ip：优先 X-Forwarded-For 首段，其次 remoteAddr */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
