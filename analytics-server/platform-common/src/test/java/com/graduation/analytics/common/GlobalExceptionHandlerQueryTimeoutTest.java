package com.graduation.analytics.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-19（指导书 V3.0 §7 阶段4 L158「…分页、限流、**超时统一**」；设计 V3.0 L544 Store 能力含
 * {@code queryTimeout}、L569 只读 SQL 查询超时 30 秒、L572「setReadOnly + timeout/maxRows 同时生效」）
 * 的加性交付：**查询超时在传输层可辨识**，不再与"服务端故障"混在兜底 500 INTERNAL 里。
 *
 * <p><b>单一属主</b>：码由 {@link PlatformBizException#QUERY_TIMEOUT} 定（唯一常量表），
 * 状态由 {@link GlobalExceptionHandler#mapStatus} 定（唯一状态表），超时值由
 * {@link QueryTimeoutPolicy} 定（唯一数值属主）。本类不新增第二套映射。</p>
 *
 * <p><b>取证边界（未测）</b>：本类断言处理器返回的 {@code ResponseEntity}，是**纯内存**测试；
 * 真机 HTTP 504（真实例 + 真 MySQL 只读源上跑一条超时查询）**未测**。</p>
 */
class GlobalExceptionHandlerQueryTimeoutTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("查询超时 → 504 + QUERY_TIMEOUT（原先落兜底 500 INTERNAL）")
    void queryTimeoutMapsToGatewayTimeout() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleQueryTimeout(
                new QueryTimeoutException("Statement cancelled due to timeout or client request"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("QUERY_TIMEOUT");
        assertThat(response.getBody().message()).contains("超时");
        // 不回传驱动原文（与兜底处理器同样不向调用方泄露实现细节）
        assertThat(response.getBody().message()).doesNotContain("Statement cancelled");
        assertThat(response.getBody().traceId()).isNotBlank();
    }

    @Test
    @DisplayName("QUERY_TIMEOUT 在 mapStatus 登记为 504（状态仍由单一属主决定）")
    void queryTimeoutIsRegisteredInMapStatus() {
        assertThat(GlobalExceptionHandler.mapStatus(PlatformBizException.QUERY_TIMEOUT))
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    @DisplayName("超时与兜底 500 INTERNAL 可区分（调用方能据此缩小窗口/重试，而不是当系统故障）")
    void queryTimeoutIsDistinguishableFromInternal() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleQueryTimeout(new QueryTimeoutException("boom"));

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isNotEqualTo(PlatformBizException.INTERNAL);
        assertThat(PlatformBizException.QUERY_TIMEOUT).isEqualTo("QUERY_TIMEOUT");
    }
}
