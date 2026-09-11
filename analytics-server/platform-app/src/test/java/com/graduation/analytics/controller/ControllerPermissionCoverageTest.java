package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 权限覆盖扫描（R8-3 §3.2）：反射遍历所有 {@code @RestController}，逐个端点要求「类级或方法级
 * {@code @RequiresPermission}」，并把前端冻结调用的端点与期望权限码逐条钉死 —— 防止新接口
 * 忘记声明权限码（fail-closed 会 403，但更该在构建期发现），也防止权限码被悄悄改宽/改窄。
 *
 * <p>豁免清单是**冻结集合**：只有登录、会话自身、健康检查可无权限码；一旦有人新增豁免路径，
 * 本测试会失败并要求显式评审。</p>
 */
class ControllerPermissionCoverageTest {

    /** 无需权限码的端点（拦截器白名单 / 仅要求登录态）——冻结，改动必须显式评审 */
    private static final Set<String> EXEMPT = Set.of(
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/logout",
            "GET /api/v1/auth/me",
            "GET /api/v1/metrics/health",
            "GET /api/v1/health");

    /** 前端 api.js 实际调用的端点 → 期望权限码（路径占位符统一写成 {id}） */
    private static final Map<String, String> FROZEN_FRONTEND_EXPECTATIONS = new LinkedHashMap<>();

    static {
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/dashboards/overview", PermissionCode.DASHBOARD_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/analysis/sales", PermissionCode.DASHBOARD_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/analysis/products", PermissionCode.DASHBOARD_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/analysis/funnel", PermissionCode.DASHBOARD_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/analysis/users", PermissionCode.DASHBOARD_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/analysis/rfm", PermissionCode.DASHBOARD_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/metrics/overview", PermissionCode.DASHBOARD_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/metrics/snapshots", PermissionCode.DASHBOARD_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/metrics/quality", PermissionCode.OPS_LOG_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/ai/queries", PermissionCode.AI_QUERY);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/ai/analyses", PermissionCode.AI_QUERY);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/ai/explanations", PermissionCode.AI_QUERY);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/ai/history/my", PermissionCode.AI_QUERY);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/ai/audit/history", PermissionCode.AI_AUDIT_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/ai/audit/calls", PermissionCode.AI_AUDIT_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/decisions", PermissionCode.DASHBOARD_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/decisions", PermissionCode.DECISION_CREATE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/decisions/{id}/submit", PermissionCode.DECISION_CREATE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/decisions/{id}/approve", PermissionCode.DECISION_APPROVE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/decisions/{id}/reject", PermissionCode.DECISION_APPROVE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/decisions/{id}/start", PermissionCode.DECISION_CREATE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/decisions/{id}/complete", PermissionCode.DECISION_CREATE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/decisions/{id}/cancel", PermissionCode.DECISION_CREATE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/decisions/{id}/evaluate", PermissionCode.DECISION_CREATE);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/decisions/{id}/evaluations", PermissionCode.DASHBOARD_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/admin/users", PermissionCode.USER_MANAGE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/admin/users", PermissionCode.USER_MANAGE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/admin/users/{id}/toggle", PermissionCode.USER_MANAGE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/admin/users/{id}/reset-password", PermissionCode.USER_MANAGE);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/ingestion/status", PermissionCode.OPS_LOG_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/ingestion/batches", PermissionCode.OPS_LOG_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/ingestion/runs", PermissionCode.PIPELINE_RUN);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/pipeline-runs", PermissionCode.OPS_LOG_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/pipeline-runs", PermissionCode.PIPELINE_RUN);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/pipeline-runs/{id}", PermissionCode.OPS_LOG_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/pipeline-runs/{id}/retry", PermissionCode.PIPELINE_RUN);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/admin/pipeline-runs/{id}/resume", PermissionCode.PIPELINE_RUN);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/admin/pipeline-runs/{id}/mark-failed", PermissionCode.PIPELINE_RUN);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/admin/pipeline-runs/{id}/retry-from-stage",
                PermissionCode.PIPELINE_RUN);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/admin/pipeline-runs/recovery-report", PermissionCode.OPS_LOG_VIEW);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/runtime-profiles", PermissionCode.RUNTIME_MANAGE);
        FROZEN_FRONTEND_EXPECTATIONS.put("PUT /api/v1/runtime-profiles/{id}", PermissionCode.RUNTIME_MANAGE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/runtime-profiles/{id}/test", PermissionCode.RUNTIME_MANAGE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/runtime-profiles/{id}/activate", PermissionCode.RUNTIME_MANAGE);
        FROZEN_FRONTEND_EXPECTATIONS.put("POST /api/v1/runtime-profiles/{id}/disable", PermissionCode.RUNTIME_MANAGE);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/runtime-profiles/active", PermissionCode.RUNTIME_MANAGE);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/runtime-profiles/{id}", PermissionCode.RUNTIME_MANAGE);
        FROZEN_FRONTEND_EXPECTATIONS.put("GET /api/v1/runtime-profiles", PermissionCode.RUNTIME_MANAGE);
    }

    @Test
    @DisplayName("每个 /api/v1 端点都有权限码（豁免清单冻结）")
    void everyEndpointDeclaresPermission() {
        Map<String, String> endpoints = scanEndpoints();
        assertTrue(endpoints.size() > 40, "扫描到的端点数异常（" + endpoints.size() + "），可能没扫到控制器");

        Set<String> exemptFound = new TreeSet<>();
        Map<String, String> missing = new TreeMap<>();
        for (Map.Entry<String, String> entry : endpoints.entrySet()) {
            if (EXEMPT.contains(entry.getKey())) {
                exemptFound.add(entry.getKey());
                continue;
            }
            if (entry.getValue() == null) {
                missing.put(entry.getKey(), "未声明 @RequiresPermission（类级或方法级）");
            }
        }
        if (!missing.isEmpty()) {
            fail("以下端点没有任何权限码（fail-closed 会 403，请在控制器上补 @RequiresPermission）: " + missing);
        }
        assertEquals(new TreeSet<>(EXEMPT), exemptFound,
                "豁免端点集合发生变化：新增豁免必须显式评审（拦截器白名单/SESSION_ONLY_PATHS 同步）");
    }

    @Test
    @DisplayName("前端冻结调用的端点权限码逐条一致（防越权与误 403）")
    void frozenFrontendExpectations() {
        Map<String, String> endpoints = scanEndpoints();
        Map<String, String> mismatch = new TreeMap<>();
        Set<String> notFound = new TreeSet<>();

        for (Map.Entry<String, String> expected : FROZEN_FRONTEND_EXPECTATIONS.entrySet()) {
            String actual = endpoints.get(expected.getKey());
            if (actual == null) {
                notFound.add(expected.getKey() + "（实际: " + actual + "）");
            } else if (!expected.getValue().equals(actual)) {
                mismatch.put(expected.getKey(), "期望 " + expected.getValue() + "，实际 " + actual);
            }
        }
        assertTrue(notFound.isEmpty(), "这些端点不存在或路径变了（前端 api.js 会 404）: " + notFound);
        assertTrue(mismatch.isEmpty(), "权限码与契约不一致: " + mismatch);
    }

    @Test
    @DisplayName("权限码取值只能是权限矩阵里登记的 9 个（不出现拼错的字符串）")
    void permissionCodesComeFromMatrix() {
        for (Map.Entry<String, String> entry : scanEndpoints().entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            assertTrue(PermissionCode.ALL.contains(entry.getValue()),
                    entry.getKey() + " 使用了未登记的权限码: " + entry.getValue());
        }
    }

    @Test
    @DisplayName("runtime-profiles 全组都要求 runtime:manage（响应含连接配置，读也要管权限）")
    void runtimeProfilesFullyGuarded() {
        for (Map.Entry<String, String> entry : scanEndpoints().entrySet()) {
            if (entry.getKey().contains("/api/v1/runtime-profiles")) {
                assertEquals(PermissionCode.RUNTIME_MANAGE, entry.getValue(), entry.getKey());
            }
        }
    }

    // ── 扫描实现 ────────────────────────────────────────────────────────

    /**
     * 扫描所有 {@code @RestController} 的请求映射。
     *
     * @return key = "HTTP方法 归一化路径"，value = 权限码（未声明为 null）
     */
    private Map<String, String> scanEndpoints() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Map<String, String> result = new TreeMap<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.graduation.analytics")) {
            Class<?> controller;
            try {
                controller = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("无法加载控制器类: " + definition.getBeanClassName(), e);
            }
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String classPath = classMapping == null || classMapping.path().length == 0 ? "" : classMapping.path()[0];
            RequiresPermission classPermission =
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequiresPermission.class);

            for (Method method : controller.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (methodMapping == null) {
                    continue;
                }
                String[] methodPaths = methodMapping.path().length == 0 ? new String[]{""} : methodMapping.path();
                for (String httpMethod : requestMethods(methodMapping)) {
                    for (String methodPath : methodPaths) {
                        String path = normalize(classPath + methodPath);
                        if (!path.startsWith("/api/v1")) {
                            continue; // 非 API 路径（SPA 转发等）不在权限码范围
                        }
                        RequiresPermission methodPermission =
                                AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
                        String code = methodPermission != null ? methodPermission.value()
                                : classPermission == null ? null : classPermission.value();
                        String key = httpMethod + " " + path;
                        String previous = result.put(key, code);
                        if (previous != null && !previous.equals(code)) {
                            fail("同一端点被声明了两次且权限码不同: " + key);
                        }
                    }
                }
            }
        }
        return result;
    }

    private Set<String> requestMethods(RequestMapping mapping) {
        if (mapping.method().length == 0) {
            return Set.of("GET", "POST", "PUT", "DELETE", "PATCH");
        }
        Set<String> methods = new TreeSet<>();
        for (org.springframework.web.bind.annotation.RequestMethod method : mapping.method()) {
            methods.add(method.name());
        }
        return methods;
    }

    /** 路径占位符统一为 {id}，去掉重复斜杠 */
    private String normalize(String path) {
        String normalized = path.replaceAll("\\{[^}]+}", "{id}").replaceAll("//+", "/");
        return normalized.isEmpty() ? "/" : normalized;
    }

    @Test
    @DisplayName("归一化与 HTTP 方法展开逻辑自检（防止扫描器本身失真）")
    void scannerSelfCheck() {
        assertEquals("/api/v1/admin/users/{id}/toggle", normalize("/api/v1/admin/users/{userId}/toggle"));
        assertEquals("/api/v1/decisions", normalize("/api/v1/decisions"));
        assertFalse(scanEndpoints().isEmpty());
    }
}
