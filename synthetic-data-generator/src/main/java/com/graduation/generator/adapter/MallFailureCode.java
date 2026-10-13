package com.graduation.generator.adapter;

import java.util.Locale;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;

/**
 * 生成器侧稳定错误词汇。商城适配器把少量已知协议细节映射到这些类别；未知商城错误码
 * 仍按 HTTP/业务拒绝类别处理，而不要求平台复制每个商城的完整错误码表。
 */
public enum MallFailureCode {
    MALL_OPERATION_FAILED,
    MALL_UNSUPPORTED_OPERATION,
    MALL_CREDENTIAL_UNAVAILABLE,
    MALL_TRANSPORT_ERROR,
    MALL_PROTOCOL_ERROR,
    MALL_AUTH_REJECTED,
    MALL_ROUTE_NOT_FOUND,
    MALL_CONFLICT,
    MALL_RATE_LIMITED,
    MALL_STOCK_UNAVAILABLE,
    MALL_ITEM_UNAVAILABLE,
    MALL_REQUEST_REJECTED,
    MALL_REMOTE_FAILURE,
    MALL_OUTCOME_UNKNOWN,
    MALL_BUSINESS_REJECTED;

    /** 把两种已接入协议的已知库存/商品码归一化，未知码永不直接成为平台错误码。 */
    public static MallFailureCode fromResponse(int httpStatus, String externalCode) {
        if (httpStatus == 401 || httpStatus == 403) {
            return MALL_AUTH_REJECTED;
        }
        if (httpStatus == 404) {
            return MALL_ROUTE_NOT_FOUND;
        }
        if (httpStatus == 409) {
            return MALL_CONFLICT;
        }
        if (httpStatus == 429) {
            return MALL_RATE_LIMITED;
        }
        if (httpStatus >= 500) {
            return MALL_REMOTE_FAILURE;
        }
        String code = externalCode == null ? "" : externalCode.trim().toUpperCase(Locale.ROOT);
        if (code.equals("INSUFFICIENT_STOCK") || code.equals("E_NO_STOCK")) {
            return MALL_STOCK_UNAVAILABLE;
        }
        if (code.equals("E_ITEM_OFF_SHELF")) {
            return MALL_ITEM_UNAVAILABLE;
        }
        if (httpStatus >= 400) {
            return MALL_REQUEST_REJECTED;
        }
        return MALL_BUSINESS_REJECTED;
    }

    /**
     * 按 HTTP 方法及失败阶段判断写请求是否可能已经产生副作用。
     * 建连超时/拒绝连接表示尚未建立连接；普通请求超时、其它 I/O 中断不能证明服务端没有处理。
     */
    public static MallFailureCode forTransport(String method, IOException failure) {
        if (isReadOnly(method)) {
            return MALL_TRANSPORT_ERROR;
        }
        if (hasCause(failure, HttpConnectTimeoutException.class) || hasCause(failure, ConnectException.class)) {
            return MALL_TRANSPORT_ERROR;
        }
        return MALL_OUTCOME_UNKNOWN;
    }

    /** 在正在发送写请求的同步调用被中断时，不能推断远端是否已执行。 */
    public static MallFailureCode forInterruptedRequest(String method) {
        return isReadOnly(method) ? MALL_TRANSPORT_ERROR : MALL_OUTCOME_UNKNOWN;
    }

    /**
     * 写请求收到 5xx 或成功状态却没有成功信封时，远端可能已经提交副作用；
     * 不得把它作为可安全重放的普通远端失败。
     */
    public static MallFailureCode forResponse(String method, int httpStatus, String externalCode) {
        if (!isReadOnly(method) && httpStatus >= 500) {
            return MALL_OUTCOME_UNKNOWN;
        }
        return fromResponse(httpStatus, externalCode);
    }

    public static MallFailureCode forInvalidResponse(String method, int httpStatus) {
        if (!isReadOnly(method) && httpStatus >= 200 && httpStatus < 300) {
            return MALL_OUTCOME_UNKNOWN;
        }
        return MALL_PROTOCOL_ERROR;
    }

    private static boolean isReadOnly(String method) {
        return method == null || method.equalsIgnoreCase("GET")
                || method.equalsIgnoreCase("HEAD") || method.equalsIgnoreCase("OPTIONS");
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
