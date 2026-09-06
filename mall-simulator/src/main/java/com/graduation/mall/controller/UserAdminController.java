package com.graduation.mall.auth;

import com.graduation.mall.auth.AuthService.UserView;
import com.graduation.mall.common.ApiResponse;
import com.graduation.mall.outbox.TraceContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理（§3.2 权限模块·admin 专属；路径前缀 /api/v1/admin 由 AuthInterceptor 隔离）。
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final AuthService authService;

    public record CreateUserReq(
            @NotBlank String username,
            String realName,
            @NotBlank String role,        // admin | operator | analyst
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
    public ApiResponse<UserView> create(@RequestBody CreateUserReq req) {
        TraceContext trace = TraceContext.create();
        return ApiResponse.ok(authService.createUser(req.username(), req.realName(), req.role(), req.password()),
                trace.traceId());
    }

    @PostMapping("/{userId}/toggle")
    public ApiResponse<Void> toggle(@PathVariable Long userId, @RequestBody ToggleReq req) {
        TraceContext trace = TraceContext.create();
        authService.toggleUser(userId, req.enable(), CurrentUserHolder.get());
        return ApiResponse.ok(null, trace.traceId());
    }

    @PostMapping("/{userId}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long userId, @RequestBody ResetPasswordReq req) {
        TraceContext trace = TraceContext.create();
        authService.resetPassword(userId, req.password());
        return ApiResponse.ok(null, trace.traceId());
    }
}