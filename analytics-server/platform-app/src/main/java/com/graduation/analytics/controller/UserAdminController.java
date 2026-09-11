package com.graduation.analytics.auth;

import com.graduation.analytics.auth.AuthService.UserView;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.controller.CallerContext;
import com.graduation.analytics.decision.AuditActor;
import com.graduation.analytics.decision.OperationAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理（§3.2 权限模块；R8-3 起按权限码 {@code user:manage} 判定，仅 admin）。
 *
 * <p>R8-3 改动：①类级 {@link RequiresPermission}（admin 独有，operator/analyst/data_dev 一律 403）；
 * ②创建/启停/重置密码三个写动作落 operation_audit_log（§21.4 用户管理必须可审计），
 * 操作者取自登录会话（{@link CallerContext}），审计摘要**不含密码**；③角色白名单注释补 data_dev。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@RequiresPermission(PermissionCode.USER_MANAGE)
public class UserAdminController {

    private final AuthService authService;
    private final OperationAuditService audit;

    public record CreateUserReq(
            @NotBlank String username,
            String realName,
            @NotBlank String role,        // admin | data_dev | operator | analyst
            @NotBlank String password) {
    }

    public record ResetPasswordReq(@NotBlank String password) {
    }

    public record ToggleReq(@NotNull Boolean enable) {
    }

    @GetMapping
    public ApiResponse<List<UserView>> list() {
        return ApiResponse.ok(authService.listUsers(), TraceContext.create().traceId());
    }

    @PostMapping
    public ApiResponse<UserView> create(@Valid @RequestBody CreateUserReq req, HttpServletRequest request) {
        TraceContext trace = TraceContext.create();
        AuditActor actor = CallerContext.actor(request, trace.traceId());
        UserView created;
        try {
            created = authService.createUser(req.username(), req.realName(), req.role(), req.password());
        } catch (RuntimeException e) {
            auditFailure(actor, OperationAuditService.ACTION_USER_CREATE,
                    req == null ? null : req.username(), "用户名=" + (req == null ? null : req.username())
                            + ";role=" + (req == null ? null : req.role()), e);
            throw e;
        }
        audit.success(actor, OperationAuditService.ACTION_USER_CREATE, OperationAuditService.RESOURCE_SYS_USER,
                String.valueOf(created.id()), null,
                OperationAuditService.digest("username", created.username(), "role", created.role(), "enabled", "true"),
                "创建账号");
        return ApiResponse.ok(created, trace.traceId());
    }

    /**
     * 启用/禁用。入参缺失（如空 body）必须落到 400 PARAM_INVALID：
     * record 上已声明 @NotNull，但缺 @Valid 时约束不生效，req.enable() 自动拆箱会抛 NPE → 500，
     * 把「参数错误」误报成「系统繁忙」（2026-09-11 DOM 验收实测：空 body 返回 500 INTERNAL）。
     */
    @PostMapping("/{userId}/toggle")
    public ApiResponse<Void> toggle(@PathVariable Long userId, @Valid @RequestBody ToggleReq req,
                                    HttpServletRequest request) {
        TraceContext trace = TraceContext.create();
        AuditActor actor = CallerContext.actor(request, trace.traceId());
        try {
            authService.toggleUser(userId, req.enable(), CallerContext.requireUser());
        } catch (RuntimeException e) {
            auditFailure(actor, OperationAuditService.ACTION_USER_TOGGLE, String.valueOf(userId),
                    "enable=" + (req == null ? null : req.enable()), e);
            throw e;
        }
        audit.success(actor, OperationAuditService.ACTION_USER_TOGGLE, OperationAuditService.RESOURCE_SYS_USER,
                String.valueOf(userId), null,
                OperationAuditService.digest("userId", String.valueOf(userId), "enable", String.valueOf(req.enable())),
                req.enable() ? "启用账号" : "停用账号");
        return ApiResponse.ok(null, trace.traceId());
    }

    @PostMapping("/{userId}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long userId, @Valid @RequestBody ResetPasswordReq req,
                                          HttpServletRequest request) {
        TraceContext trace = TraceContext.create();
        AuditActor actor = CallerContext.actor(request, trace.traceId());
        try {
            authService.resetPassword(userId, req.password());
        } catch (RuntimeException e) {
            auditFailure(actor, OperationAuditService.ACTION_USER_RESET_PASSWORD, String.valueOf(userId),
                    "password=***", e);
            throw e;
        }
        // 摘要只记「已重置」，绝不落密码明文或哈希（§21.3 脱敏）
        audit.success(actor, OperationAuditService.ACTION_USER_RESET_PASSWORD, OperationAuditService.RESOURCE_SYS_USER,
                String.valueOf(userId), null,
                OperationAuditService.digest("userId", String.valueOf(userId), "password", "***"),
                "重置密码");
        return ApiResponse.ok(null, trace.traceId());
    }

    /** 失败路径审计：审计写入本身失败不能掩盖原始业务异常 */
    private void auditFailure(AuditActor actor, String action, String resourceId, String digest, RuntimeException cause) {
        try {
            audit.failure(actor, action, OperationAuditService.RESOURCE_SYS_USER, resourceId, null, digest,
                    cause.getMessage());
        } catch (RuntimeException auditError) {
            log.error("用户管理失败审计写入失败: action={}, resourceId={}", action, resourceId, auditError);
        }
    }
}
