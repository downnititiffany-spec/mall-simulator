package com.graduation.generator.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MallFailureCodeTest {

    @Test
    @DisplayName("两家商城不同库存错误码映射到同一稳定业务分类")
    void knownStockCodesNormalizeAcrossAdapters() {
        assertEquals(MallFailureCode.MALL_STOCK_UNAVAILABLE,
                MallFailureCode.fromResponse(400, "INSUFFICIENT_STOCK"));
        assertEquals(MallFailureCode.MALL_STOCK_UNAVAILABLE,
                MallFailureCode.fromResponse(400, "e_no_stock"));
    }

    @Test
    @DisplayName("第二商城下架错误码即使 HTTP 200 仍映射为商品不可用")
    void itemUnavailableCodeNormalizesEvenWhenHttpStatusLooksSuccessful() {
        assertEquals(MallFailureCode.MALL_ITEM_UNAVAILABLE,
                MallFailureCode.fromResponse(200, "E_ITEM_OFF_SHELF"));
    }

    @Test
    @DisplayName("未知商城错误码按 HTTP 类别归一，不暴露成生成器错误码")
    void unknownVendorCodesUseStableHttpCategories() {
        assertEquals(MallFailureCode.MALL_AUTH_REJECTED,
                MallFailureCode.fromResponse(403, "VENDOR_SPECIFIC_AUTH_17"));
        assertEquals(MallFailureCode.MALL_AUTH_REJECTED,
                MallFailureCode.fromResponse(401, "E_NO_STOCK"),
                "HTTP 认证失败优先于不可信或矛盾的业务错误码");
        assertEquals(MallFailureCode.MALL_CONFLICT,
                MallFailureCode.fromResponse(409, "VENDOR_SPECIFIC_DUPLICATE_902"));
        assertEquals(MallFailureCode.MALL_RATE_LIMITED,
                MallFailureCode.fromResponse(429, "VENDOR_SPECIFIC_THROTTLE"));
        assertEquals(MallFailureCode.MALL_REQUEST_REJECTED,
                MallFailureCode.fromResponse(422, "UNKNOWN"));
        assertEquals(MallFailureCode.MALL_REMOTE_FAILURE,
                MallFailureCode.fromResponse(503, "UNKNOWN"));
        assertEquals(MallFailureCode.MALL_BUSINESS_REJECTED,
                MallFailureCode.fromResponse(200, "UNKNOWN"));
    }

    @Test
    @DisplayName("写请求发出后的超时或中断映射为结果不明，建连失败与只读超时仍是传输失败")
    void uncertainWriteTransportIsNotReportedAsDefinitelyNotSent() {
        assertEquals(MallFailureCode.MALL_OUTCOME_UNKNOWN,
                MallFailureCode.forTransport("POST", new HttpTimeoutException("response timeout")));
        assertEquals(MallFailureCode.MALL_OUTCOME_UNKNOWN,
                MallFailureCode.forInterruptedRequest("POST"));
        assertEquals(MallFailureCode.MALL_TRANSPORT_ERROR,
                MallFailureCode.forTransport("POST", new HttpConnectTimeoutException("connect timeout")));
        assertEquals(MallFailureCode.MALL_TRANSPORT_ERROR,
                MallFailureCode.forTransport("POST", new ConnectException("refused")));
        assertEquals(MallFailureCode.MALL_TRANSPORT_ERROR,
                MallFailureCode.forTransport("POST", new IOException("connect wrapper",
                        new ConnectException("refused"))));
        assertEquals(MallFailureCode.MALL_TRANSPORT_ERROR,
                MallFailureCode.forTransport("GET", new HttpTimeoutException("read timeout")));
        assertEquals(MallFailureCode.MALL_OUTCOME_UNKNOWN,
                MallFailureCode.forTransport("POST", new IOException("connection reset")));
    }

    @Test
    @DisplayName("写请求 5xx 或 2xx 错误信封标记结果不明，不进入可重放的普通错误类")
    void writeResponsesWithUncertainCommitAreMarkedUnknown() {
        assertEquals(MallFailureCode.MALL_OUTCOME_UNKNOWN,
                MallFailureCode.forResponse("POST", 503, "REMOTE_FAILURE"));
        assertEquals(MallFailureCode.MALL_BUSINESS_REJECTED,
                MallFailureCode.forResponse("POST", 200, "E_INTERNAL"));
        assertEquals(MallFailureCode.MALL_OUTCOME_UNKNOWN,
                MallFailureCode.forInvalidResponse("POST", 200));
        assertEquals(MallFailureCode.MALL_REMOTE_FAILURE,
                MallFailureCode.forResponse("GET", 503, "REMOTE_FAILURE"));
        assertEquals(MallFailureCode.MALL_CONFLICT,
                MallFailureCode.forResponse("POST", 409, "DUPLICATE"));
        assertEquals(MallFailureCode.MALL_PROTOCOL_ERROR,
                MallFailureCode.forInvalidResponse("GET", 200));
    }
}
