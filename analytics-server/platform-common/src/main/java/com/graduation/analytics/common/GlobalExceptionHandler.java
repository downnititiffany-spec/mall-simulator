package com.graduation.analytics.common;

import com.graduation.analytics.common.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /**
     * 业务异常 → 稳定错误码 + 分状态 HTTP。
     *
     * <p>P1-03（2026-09-11）之前此方法用类级 {@code @ResponseStatus(BAD_REQUEST)} 把所有业务码压成 400，
     * 调用方无法区分「找不到资源」与「与当前状态冲突」。现在状态由 {@link #mapStatus} 单点决定：
     * 源登记域三个冲突码 → 409、{@code SOURCE_NOT_FOUND} → 404，其余一律 400（既有语义不变）。
     * P1-05（2026-09-12）加法新增 {@code SOURCE_NOT_BOUND} → 409（运行环境未绑定源，
     * 与 {@code SOURCE_IN_USE} 同属"源域状态冲突"，理由见 {@link PlatformBizException#SOURCE_NOT_BOUND}）。</p>
     *
     * <p>返回 {@code ResponseEntity} 而不是继续用 {@code @ResponseStatus}：分状态无法用类级注解表达，
     * 而并列两个处理器会造成"同一异常两个所有者"。</p>
     *
     * <p>S2-01B（映射 dry-run）加法新增两个"资源不存在"码 → 404，理由见
     * {@link PlatformBizException#DRY_RUN_SAMPLE_NOT_FOUND}；既有码状态不变。</p>
     *
     * <p>S2-02（真实采集映射）加法新增两个"状态不满足采集前提"码 → 409，理由见
     * {@link PlatformBizException#MAPPING_PROFILE_INVALID}；既有码状态不变。</p>
     */
    @ExceptionHandler(PlatformBizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(PlatformBizException e) {
        return ResponseEntity.status(mapStatus(e.getCode()))
                .body(ApiResponse.error(e.getCode(), e.getMessage(), TraceContext.create().traceId()));
    }

    /**
     * 业务码 → HTTP 状态（**唯一所有者**：新增错误码只在这里加一行，不得散落到控制器或第二个 advice）。
     * 未列出的码一律 400——保持 V1 以来的既有契约，改动只做加法。
     *
     * <p>S2-02B 加法：{@code INGEST_BATCH_INPUT_CONFLICT} → 409（同批次二次消费同一输入，
     * 与 {@code MAPPING_*} 同族"状态不满足采集前提"，理由见
     * {@link PlatformBizException#INGEST_BATCH_INPUT_CONFLICT}）；既有码状态不变。</p>
     */
    static HttpStatus mapStatus(String code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        return switch (code) {
            case PlatformBizException.SOURCE_NOT_FOUND,
                 PlatformBizException.DRY_RUN_SAMPLE_NOT_FOUND,
                 PlatformBizException.DRY_RUN_REPORT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PlatformBizException.SOURCE_CODE_IMMUTABLE,
                 PlatformBizException.SOURCE_PROFILE_INVALID,
                 PlatformBizException.SOURCE_IN_USE,
                 PlatformBizException.SOURCE_NOT_BOUND,
                 PlatformBizException.MAPPING_PROFILE_INVALID,
                 PlatformBizException.MAPPING_PROFILE_BLOCKED,
                 PlatformBizException.INGEST_BATCH_INPUT_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleParam(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ApiResponse.error(PlatformBizException.PARAM_INVALID, detail, TraceContext.create().traceId());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleOther(Exception e) {
        log.error("unhandled server error [{}] {}", describeCurrentRequest(), e.getMessage(), e);
        return ApiResponse.error(PlatformBizException.INTERNAL, "系统繁忙，请稍后重试", TraceContext.create().traceId());
    }

    /**
     * 请求体不是合法 JSON / 缺少必填请求体 → 400（客户端错误，不是 500）。
     * 真机验收踩到：{@code POST /api/v1/ai/explanations} 未带 {@code Content-Type: application/json}
     * 时落到兜底 500 INTERNAL，既误导调用方也污染「系统故障」指标。
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ApiResponse.error(PlatformBizException.PARAM_INVALID,
                "请求体缺失或不是合法 JSON（需 Content-Type: application/json）", TraceContext.create().traceId());
    }

    /** Content-Type 不受支持（如表单/纯文本 POST JSON 接口）→ 415 稳定错误码 */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ApiResponse<Void> handleMediaType(
            org.springframework.web.HttpMediaTypeNotSupportedException e) {
        return ApiResponse.error(PlatformBizException.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type 不受支持：" + e.getContentType() + "，请使用 application/json",
                TraceContext.create().traceId());
    }

    /** HTTP 方法用错（如对只读接口发 POST）→ 405 */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethod(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return ApiResponse.error(PlatformBizException.METHOD_NOT_ALLOWED,
                "HTTP 方法不支持：" + e.getMethod(), TraceContext.create().traceId());
    }

    /** 查询参数类型不匹配 / 缺少必填参数 → 400（原先落兜底 500） */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return ApiResponse.error(PlatformBizException.PARAM_INVALID,
                "参数类型不合法：" + e.getName(), TraceContext.create().traceId());
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        return ApiResponse.error(PlatformBizException.PARAM_INVALID,
                "缺少必填参数：" + e.getParameterName(), TraceContext.create().traceId());
    }

    /** 兜底日志用的请求描述：方法 + 路径（不含查询串，避免把敏感参数写进日志） */
    private static String describeCurrentRequest() {
        org.springframework.web.context.request.RequestAttributes attrs =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra)) {
            return "-";
        }
        jakarta.servlet.http.HttpServletRequest req = sra.getRequest();
        String uri = req.getRequestURI();
        int semi = uri.indexOf(';');
        return req.getMethod() + " " + (semi > 0 ? uri.substring(0, semi) : uri);
    }
}