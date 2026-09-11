package com.graduation.analytics.auth;

/**
 * 缺少可信身份时抛出的异常（R8-3 契约 §3.1/§3.2）。
 *
 * <p>正常请求由 {@link AuthInterceptor} 在进入控制器前就以 401 UNAUTHORIZED 短路，
 * 本异常是**兜底**：控制器/服务一旦发现 {@link CurrentUserHolder#get()} 为空，
 * 必须显式失败，绝不允许回退到请求头用户名或 demo 用户。
 * platform-app 的 {@code PlatformExceptionAdvice} 将其映射为 401 + {@link #CODE}。</p>
 */
public class AuthenticationRequiredException extends RuntimeException {

    /** 稳定错误码（前端 api.js 收到 code=UNAUTHORIZED 即清理登录态并跳登录页） */
    public static final String CODE = "UNAUTHORIZED";

    public AuthenticationRequiredException(String message) {
        super(message);
    }
}
