package com.graduation.analytics.auth;

import java.util.Map;
import java.util.Set;

/**
 * 角色 → 权限码矩阵（R8-3 契约 §3.2 / V2.0 §21.1，逐行对齐）。
 *
 * <pre>
 * permissionCode     admin data_dev operator analyst
 * user:manage          ✓
 * runtime:manage       ✓     ✓
 * pipeline:run         ✓     ✓       ✓(只运行)
 * ops:log:view         ✓     ✓       ✓(只读摘要)
 * dashboard:view       ✓     ✓       ✓        ✓
 * ai:query             ✓     ✓       ✓        ✓
 * decision:create      ✓     ✓       ✓        ✓
 * decision:approve     ✓             ✓(按授权)
 * ai:audit:view        ✓
 * </pre>
 *
 * <p>纯静态查表，无 Spring 依赖 → 可被拦截器与单元测试直接调用。
 * 未知角色（矩阵外的新角色）一律视为**无任何权限**（fail-closed），
 * 不能因为「没写进表」而放行。</p>
 */
public final class RolePermissions {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_DATA_DEV = "data_dev";
    public static final String ROLE_OPERATOR = "operator";
    public static final String ROLE_ANALYST = "analyst";

    /** 契约 §3.2 矩阵；Set.copyOf 保证只读 */
    private static final Map<String, Set<String>> MATRIX = Map.of(
            ROLE_ADMIN, Set.of(
                    PermissionCode.USER_MANAGE,
                    PermissionCode.RUNTIME_MANAGE,
                    PermissionCode.PIPELINE_RUN,
                    PermissionCode.OPS_LOG_VIEW,
                    PermissionCode.DASHBOARD_VIEW,
                    PermissionCode.AI_QUERY,
                    PermissionCode.DECISION_CREATE,
                    PermissionCode.DECISION_APPROVE,
                    PermissionCode.AI_AUDIT_VIEW),
            ROLE_DATA_DEV, Set.of(
                    PermissionCode.RUNTIME_MANAGE,
                    PermissionCode.PIPELINE_RUN,
                    PermissionCode.OPS_LOG_VIEW,
                    PermissionCode.DASHBOARD_VIEW,
                    PermissionCode.AI_QUERY,
                    PermissionCode.DECISION_CREATE),
            ROLE_OPERATOR, Set.of(
                    PermissionCode.PIPELINE_RUN,
                    PermissionCode.OPS_LOG_VIEW,
                    PermissionCode.DASHBOARD_VIEW,
                    PermissionCode.AI_QUERY,
                    PermissionCode.DECISION_CREATE,
                    PermissionCode.DECISION_APPROVE),
            ROLE_ANALYST, Set.of(
                    PermissionCode.DASHBOARD_VIEW,
                    PermissionCode.AI_QUERY,
                    PermissionCode.DECISION_CREATE));

    /** 平台可分配的角色（AuthService.createUser 白名单与矩阵键必须一致，测试守护） */
    public static final Set<String> ROLES = MATRIX.keySet();

    private RolePermissions() {
    }

    /** 角色是否拥有某权限码；角色或权限码为空 → false（fail-closed） */
    public static boolean has(String role, String permissionCode) {
        if (role == null || permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        return MATRIX.getOrDefault(role, Set.of()).contains(permissionCode);
    }

    /** 角色权限码集合（未知角色 → 空集） */
    public static Set<String> of(String role) {
        return role == null ? Set.of() : MATRIX.getOrDefault(role, Set.of());
    }

    /** 角色是否在矩阵内（AuthService 角色白名单校验用） */
    public static boolean knownRole(String role) {
        return role != null && MATRIX.containsKey(role);
    }
}
