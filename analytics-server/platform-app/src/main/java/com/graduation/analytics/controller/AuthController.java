package com.graduation.analytics.controller;

import com.graduation.analytics.auth.AuthService;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.common.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录认证接口（§3.2 权限模块）：
 * login（白名单）→ 创建 24h 会话；logout / me 需携带有效 Bearer token。
 * 登录失败由本控制器直接映射 401 BAD_CREDENTIALS / 403 USER_DISABLED
 * （全局异常处理器把 PlatformBizException 固定映射为 400，无法表达 401）。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 登录请求体 */
    public record LoginRequest(
            @NotBlank(message = "username 必填") String username,
            @NotBlank(message = "password 必填") String password) {
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Object>> login(@Valid @RequestBody LoginRequest request,
                                                     HttpServletRequest servletRequest) {
        TraceContext trace = TraceContext.create();
        try {
            AuthService.LoginResult result = authService.login(
                    request.username(), request.password(), clientIp(servletRequest));
            ApiResponse<Object> body = ApiResponse.ok(result, trace.traceId());
            return ResponseEntity.ok(body);
        } catch (PlatformBizException e) {
            if (AuthService.BAD_CREDENTIALS.equals(e.getCode())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error(e.getCode(), e.getMessage(), trace.traceId()));
            }
            if (AuthService.USER_DISABLED.equals(e.getCode())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(e.getCode(), e.getMessage(), trace.traceId()));
            }
            throw e;
        }
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest servletRequest) {
        TraceContext trace = TraceContext.create();
        authService.logout(resolveBearerToken(servletRequest.getHeader(HttpHeaders.AUTHORIZATION)));
        return ApiResponse.ok(null, trace.traceId());
    }

    @GetMapping("/me")
    public ApiResponse<Object> me() {
        TraceContext trace = TraceContext.create();
        CurrentUser current = CurrentUserHolder.get();
        AuthService.UserView user = authService.me(current);
        return ApiResponse.ok(Map.of("user", user), trace.traceId());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String resolveBearerToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}