package com.graduation.analytics.controller;

import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.auth.RolePermissions;
import com.graduation.analytics.common.PlatformBizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Dry-Run 显式权限检查组件（WP-2A-2 交付项 4 / Design V3.0 §7.4）。
 *
 * <p>权限语义与缺口对齐（§7.4 明确要求 403 路径）：
 * <ul>
 *   <li>当不存在可信登录身份（{@link CurrentUserHolder#get()} 为空）时，显式拒绝并产生 403 {@code FORBIDDEN}；</li>
 *   <li>当已登录但角色在 {@link RolePermissions} 矩阵中未分配该权限时，显式拒绝并产生 403 {@code FORBIDDEN}；</li>
 *   <li>【缺口标记】：平台现有通用拦截器 {@code AuthInterceptor} 在无 Bearer token 时返回 401 UNAUTHORIZED，
 *       仅在角色越权时返回 403 FORBIDDEN_PERMISSION；而 §7.4 映射目标表格要求无权限/未授权路径统一定位为 403。
 *       本组件弥合该层差异，保证无凭据/凭据不足时均触发 403。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class MappingDryRunPermissionChecker {

    /**
     * 校验当前调用者是否具有指定权限码。
     *
     * @param requiredPermission 期望的权限码（如 {@code PermissionCode.RUNTIME_MANAGE}）
     * @throws PlatformBizException 身份缺失或权限不足时抛出 {@code FORBIDDEN}（映射为 403）
     */
    public void checkPermission(String requiredPermission) {
        CurrentUser current = CurrentUserHolder.get();
        if (current == null || current.username() == null || current.username().isBlank()) {
            log.warn("拒绝无身份执行 mapping dry-run（§7.4 要求 403）");
            throw new PlatformBizException(PlatformBizException.FORBIDDEN,
                    "缺少可信当前用户（未登录或会话已过期），拒绝执行（§7.4 权限不足 403）");
        }

        if (!RolePermissions.knownRole(current.role()) || !RolePermissions.has(current.role(), requiredPermission)) {
            log.warn("拒绝越权执行 mapping dry-run: user={} role={} required={}",
                    current.username(), current.role(), requiredPermission);
            throw new PlatformBizException(PlatformBizException.FORBIDDEN,
                    "当前账号角色 (" + current.role() + ") 缺少所需权限: " + requiredPermission + "（§7.4 权限不足 403）");
        }
    }
}
