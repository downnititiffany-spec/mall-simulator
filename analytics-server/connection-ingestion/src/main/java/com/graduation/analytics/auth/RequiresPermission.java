package com.graduation.analytics.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明访问所需的权限码（R8-3 契约 §3.2）。方法级优先于类级。
 *
 * <p>语义：调用方登录角色必须命中 {@link RolePermissions} 矩阵中的该权限码，否则
 * {@link AuthInterceptor} 直接响应 403 {@code FORBIDDEN_PERMISSION}（消息附所需权限码）。
 * 无注解的接口默认**拒绝**（fail-closed，避免新增接口忘记加权限就对外开放）；
 * 仅登录态即可访问的接口（登出、当前用户）在 AuthInterceptor 的 SESSION_ONLY_PATHS 中显式列出。</p>
 *
 * <p>用法：{@code @RequiresPermission(PermissionCode.DASHBOARD_VIEW)}</p>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** 权限码，取值来自 {@link PermissionCode} */
    String value();
}
