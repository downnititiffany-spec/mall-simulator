package com.graduation.generator.adapter;

import java.math.BigDecimal;

/**
 * 商城侧的订单（§4.1 {@code createOrder/pay/cancel} 的返回类型；指导书未定义字段，此处为最小设计）。
 *
 * <p>{@code status} 用<b>商城回传的原文</b>（如 {@code PAID}/{@code CANCELLED}），不由生成器翻译成
 * 自己的枚举：订单状态机归商城所有，生成器只是调用方，翻译一次就多一份要对齐的口径。</p>
 *
 * @param orderId     商城侧订单 ID
 * @param userId      商城侧用户 ID
 * @param status      商城侧状态原文；无法读取时为 {@code "UNKNOWN"}，且不得据此推断成功
 * @param totalAmount 订单总额（元）；商城未回传时为 null
 * @param itemCount   明细条数；商城未回传时为 -1
 */
public record ExternalOrder(String orderId, String userId, String status,
                            BigDecimal totalAmount, int itemCount) {

    /** 下单应答只有 {@code {"orderId": "..."}} 时的最小构造（实测：POST /orders 的应答只有 orderId） */
    public static ExternalOrder created(String orderId, String userId) {
        return new ExternalOrder(orderId, userId, "CREATED", null, -1);
    }

    public ExternalOrder {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId 必填（商城侧唯一键）");
        }
        if (status == null || status.isBlank()) {
            status = "UNKNOWN";
        }
    }

    public boolean cancelled() {
        return "CANCELLED".equals(status);
    }

    public boolean paid() {
        return "PAID".equals(status);
    }
}
