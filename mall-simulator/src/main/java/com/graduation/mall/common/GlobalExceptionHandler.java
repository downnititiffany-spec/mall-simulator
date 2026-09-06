package com.graduation.mall.common;

import com.graduation.mall.outbox.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常映射：业务异常 → 400 + 业务码；参数校验 → 400 PARAM_INVALID；
 * 未知异常 → 500 INTERNAL（日志记录，不向调用方泄露堆栈/连接信息）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResource(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ApiResponse.error("NOT_FOUND", "资源不存在: " + e.getResourcePath(), TraceContext.create().traceId());
    }

    @ExceptionHandler(MallBizException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBiz(MallBizException e) {
        return ApiResponse.error(e.getCode(), e.getMessage(), TraceContext.create().traceId());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleParam(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ApiResponse.error(MallBizException.PARAM_INVALID, detail, TraceContext.create().traceId());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleOther(Exception e) {
        log.error("mall internal error", e);
        return ApiResponse.error(MallBizException.INTERNAL, "系统繁忙，请稍后重试", TraceContext.create().traceId());
    }
}