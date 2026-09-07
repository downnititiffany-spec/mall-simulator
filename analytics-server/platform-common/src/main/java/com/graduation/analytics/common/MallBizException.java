package com.graduation.analytics.common;

/**
 * 商城业务异常：code 为稳定错误码（REST 返回），message 面向调用方。
 */
public class MallBizException extends RuntimeException {

    private final String code;

    public MallBizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    // 常用错误码（§23.2 错误分类在平台层做完整映射，商城先保证稳定）
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";
    public static final String PRODUCT_OFF_SALE = "PRODUCT_OFF_SALE";
    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    public static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    public static final String ORDER_OWNER_MISMATCH = "ORDER_OWNER_MISMATCH";
    public static final String ORDER_STATE_ILLEGAL = "ORDER_STATE_ILLEGAL";
    public static final String REFUND_EXCEEDS_PAID = "REFUND_EXCEEDS_PAID";
    public static final String REFUND_NOT_FOUND = "REFUND_NOT_FOUND";
    public static final String PARAM_INVALID = "PARAM_INVALID";
    public static final String INTERNAL = "INTERNAL";
}