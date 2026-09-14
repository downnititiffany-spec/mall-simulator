package com.graduation.generator.web;

import com.graduation.generator.service.GenerationRunService.RunNotFoundException;
import com.graduation.generator.service.GenerationRunService.UnsupportedModeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误映射。
 *
 * <p>契约对错误响应体<b>没有</b>建模（每个端点都写"错误体形状未冻结，本契约不造错误结构"），
 * 所以这里的 {@code {error, detail, timestamp}} 是本实现的临时形状，已登记 D-015；
 * 等契约冻结错误结构后必须替换，而不是把这份形状当成契约。</p>
 *
 * <p>状态码同样未冻结，这里取最小必要区分：400 参数/输入不合法、404 资源不存在、501 能力未实现、
 * 500 其它。501 的存在是为了让"模式未实现"显式失败，而不是静默降级成另一种模式。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RunNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(RunNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(UnsupportedModeException.class)
    public ResponseEntity<Map<String, Object>> notImplemented(UnsupportedModeException e) {
        log.warn("请求了尚未实现的能力：{}", e.getMessage());
        return body(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage());
    }

    /**
     * 唯一键冲突（实测撞到：`generator_target.uk_target_name` 不允许重名目标）。
     *
     * <p>不映射就落进兜底的 500，调用方会以为是服务端坏了；实际是请求内容与现有数据冲突，属 409。
     * 这里只报约束名，不回显整行数据。</p>
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> duplicate(DuplicateKeyException e) {
        log.warn("唯一键冲突：{}", e.getMostSpecificCause().getMessage());
        return body(HttpStatus.CONFLICT, "DUPLICATE_KEY",
                "与现有数据唯一键冲突（如目标名称重名）：" + e.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> serverError(RuntimeException e) {
        log.error("未预期的服务端异常", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("detail", detail);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
