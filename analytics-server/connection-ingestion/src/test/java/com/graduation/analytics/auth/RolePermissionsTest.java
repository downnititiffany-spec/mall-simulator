package com.graduation.analytics.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限矩阵（R8-3 契约 §3.2 / V2.0 §21.1）：逐格断言，防止「矩阵被悄悄放宽/收紧」。
 */
class RolePermissionsTest {

    @Test
    @DisplayName("admin：9 个权限码全有")
    void adminHasAll() {
        assertEquals(Set.copyOf(PermissionCode.ALL), RolePermissions.of(RolePermissions.ROLE_ADMIN));
    }

    @Test
    @DisplayName("data_dev：运行环境/流水线/运维日志/看板/问数/建决策，但没有 user:manage 与 ai:audit:view")
    void dataDevMatrix() {
        Set<String> codes = RolePermissions.of(RolePermissions.ROLE_DATA_DEV);
        assertTrue(codes.containsAll(Set.of(PermissionCode.RUNTIME_MANAGE, PermissionCode.PIPELINE_RUN,
                PermissionCode.OPS_LOG_VIEW, PermissionCode.DASHBOARD_VIEW, PermissionCode.AI_QUERY,
                PermissionCode.DECISION_CREATE)));
        assertFalse(codes.contains(PermissionCode.USER_MANAGE));
        assertFalse(codes.contains(PermissionCode.AI_AUDIT_VIEW));
        assertFalse(codes.contains(PermissionCode.DECISION_APPROVE));
    }

    @Test
    @DisplayName("operator：能跑流水线、能审批、能建决策，但没有 user:manage / runtime:manage / ai:audit:view")
    void operatorMatrix() {
        Set<String> codes = RolePermissions.of(RolePermissions.ROLE_OPERATOR);
        assertTrue(codes.containsAll(Set.of(PermissionCode.PIPELINE_RUN, PermissionCode.OPS_LOG_VIEW,
                PermissionCode.DASHBOARD_VIEW, PermissionCode.AI_QUERY, PermissionCode.DECISION_CREATE,
                PermissionCode.DECISION_APPROVE)));
        assertFalse(codes.contains(PermissionCode.USER_MANAGE));
        assertFalse(codes.contains(PermissionCode.RUNTIME_MANAGE));
        assertFalse(codes.contains(PermissionCode.AI_AUDIT_VIEW));
    }

    @Test
    @DisplayName("analyst：只有看板/问数/建决策（analyst 访问 /ai/audit/* 必须 403）")
    void analystMatrix() {
        Set<String> codes = RolePermissions.of(RolePermissions.ROLE_ANALYST);
        assertEquals(Set.of(PermissionCode.DASHBOARD_VIEW, PermissionCode.AI_QUERY, PermissionCode.DECISION_CREATE),
                codes);
        assertFalse(codes.contains(PermissionCode.AI_AUDIT_VIEW));
        assertFalse(codes.contains(PermissionCode.PIPELINE_RUN));
        assertFalse(codes.contains(PermissionCode.OPS_LOG_VIEW));
        assertFalse(codes.contains(PermissionCode.USER_MANAGE));
        assertFalse(codes.contains(PermissionCode.DECISION_APPROVE));
    }

    @Test
    @DisplayName("四种角色都有 dashboard:view / ai:query / decision:create（防止误 403 的主路径）")
    void commonPermissionsForAllRoles() {
        for (String role : RolePermissions.ROLES) {
            for (String code : new String[]{PermissionCode.DASHBOARD_VIEW, PermissionCode.AI_QUERY,
                    PermissionCode.DECISION_CREATE}) {
                assertTrue(RolePermissions.has(role, code), role + " 缺少 " + code);
            }
        }
    }

    @Test
    @DisplayName("未知/空角色 fail-closed：无任何权限")
    void unknownRoleHasNothing() {
        for (String role : new String[]{null, "", "guest", "ADMIN", "root"}) {
            assertFalse(RolePermissions.knownRole(role));
            assertTrue(RolePermissions.of(role).isEmpty());
            assertFalse(RolePermissions.has(role, PermissionCode.DASHBOARD_VIEW));
        }
    }

    @Test
    @DisplayName("user:manage 只有 admin；ai:audit:view 只有 admin")
    void adminOnlyPermissions() {
        for (String role : new String[]{RolePermissions.ROLE_DATA_DEV, RolePermissions.ROLE_OPERATOR,
                RolePermissions.ROLE_ANALYST}) {
            assertFalse(RolePermissions.has(role, PermissionCode.USER_MANAGE));
            assertFalse(RolePermissions.has(role, PermissionCode.AI_AUDIT_VIEW));
        }
        assertTrue(RolePermissions.has(RolePermissions.ROLE_ADMIN, PermissionCode.USER_MANAGE));
        assertTrue(RolePermissions.has(RolePermissions.ROLE_ADMIN, PermissionCode.AI_AUDIT_VIEW));
    }

    @Test
    @DisplayName("矩阵登记的角色 = 平台可分配角色（4 个），且 permissionCode 覆盖 9 个")
    void rolesAndCodesAreComplete() {
        assertEquals(Set.of(RolePermissions.ROLE_ADMIN, RolePermissions.ROLE_DATA_DEV,
                RolePermissions.ROLE_OPERATOR, RolePermissions.ROLE_ANALYST), RolePermissions.ROLES);
        assertEquals(Set.of("user:manage", "runtime:manage", "pipeline:run", "ops:log:view", "dashboard:view",
                "ai:query", "decision:create", "decision:approve", "ai:audit:view"), Set.copyOf(PermissionCode.ALL));
    }

    @Test
    @DisplayName("权限码常量就是契约冻结字符串（前端/文档对齐）")
    void permissionCodesAreFrozen() {
        assertEquals("user:manage", PermissionCode.USER_MANAGE);
        assertEquals("runtime:manage", PermissionCode.RUNTIME_MANAGE);
        assertEquals("pipeline:run", PermissionCode.PIPELINE_RUN);
        assertEquals("ops:log:view", PermissionCode.OPS_LOG_VIEW);
        assertEquals("dashboard:view", PermissionCode.DASHBOARD_VIEW);
        assertEquals("ai:query", PermissionCode.AI_QUERY);
        assertEquals("decision:create", PermissionCode.DECISION_CREATE);
        assertEquals("decision:approve", PermissionCode.DECISION_APPROVE);
        assertEquals("ai:audit:view", PermissionCode.AI_AUDIT_VIEW);
    }

    @Test
    @DisplayName("空/未知权限码一律 false（不因参数异常放行）")
    void nullPermissionDenied() {
        assertFalse(RolePermissions.has("admin", null));
        assertFalse(RolePermissions.has("admin", "  "));
        assertFalse(RolePermissions.has(null, PermissionCode.USER_MANAGE));
    }

    /** 保留 ObjectMapper 依赖引用，避免测试与运行时 JSON 序列化实现脱节（拦截器用它写错误体） */
    @Test
    @DisplayName("拦截器错误体序列化可用（code/message/traceId 字段齐全）")
    void errorEnvelopeShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(com.graduation.analytics.common.ApiResponse.error(
                "FORBIDDEN_PERMISSION", "无权访问该资源，需要权限: ai:audit:view", "trace-x"));
        assertTrue(json.contains("FORBIDDEN_PERMISSION"));
        assertTrue(json.contains("ai:audit:view"));
        assertTrue(json.contains("trace-x"));
    }
}
