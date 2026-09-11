package com.graduation.analytics.controller;

import com.graduation.analytics.auth.AuthenticationRequiredException;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.decision.DecisionStateMachine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * R8-3 身份与状态异常映射（平台层补充 advice，不动共享的 GlobalExceptionHandler）。
 *
 * <p>共享处理器把 {@code PlatformBizException} 固定映射 400、其余异常映射 500，无法表达 401，
 * 也无法区分「业务参数错」与「状态机冲突」；这两个语义属本轮职责，因此在 platform-app 内
 * 用最高优先级的 advice 处理：</p>
 * <ul>
 *   <li>{@link AuthenticationRequiredException} → 401 {@code UNAUTHORIZED}
 *       （前端 api.js 收到该 code 会清理登录态回登录页）；</li>
 *   <li>{@link DecisionStateMachine.IllegalDecisionStateException} → 400
 *       {@code DECISION_STATE_ILLEGAL}（非法状态流转直接拒绝，不再报成「系统繁忙」500）。</li>
 * </ul>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class PlatformExceptionAdvice {

    /** 未登录/会话失效（与拦截器 401 同一错误码） */
    public static final String UNAUTHORIZED = AuthenticationRequiredException.CODE;

    /** 非法状态流转（沿用 §23.2 的 ORDER_STATE_ILLEGAL 命名风格，决策域前缀） */
    public static final String DECISION_STATE_ILLEGAL = "DECISION_STATE_ILLEGAL";

    @ExceptionHandler(AuthenticationRequiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleAuthenticationRequired(AuthenticationRequiredException e) {
        log.warn("拒绝无身份请求: {}", e.getMessage());
        return ApiResponse.error(UNAUTHORIZED, e.getMessage(), TraceContext.create().traceId());
    }

    @ExceptionHandler(DecisionStateMachine.IllegalDecisionStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalState(DecisionStateMachine.IllegalDecisionStateException e) {
        log.warn("拒绝非法决策状态流转: {}", e.getMessage());
        return ApiResponse.error(DECISION_STATE_ILLEGAL, e.getMessage(), TraceContext.create().traceId());
    }
}
