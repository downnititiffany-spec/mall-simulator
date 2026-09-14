package com.graduation.generator.adapter;

import java.math.BigDecimal;

/**
 * 商城侧的退款单（§4.1 {@code refund} 的返回类型；指导书未定义字段，此处为最小设计）。
 *
 * @param refundId 商城侧退款单 ID
 * @param orderId  归属订单 ID
 * @param status   商城侧状态原文（参考商城为 {@code COMPLETED}/{@code PENDING} 等）
 * @param amount   退款金额（元）；商城未回传时为 null
 */
public record ExternalRefund(String refundId, String orderId, String status, BigDecimal amount) {

    public ExternalRefund {
        if (refundId == null || refundId.isBlank()) {
            throw new IllegalArgumentException("refundId 必填（商城侧唯一键）");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId 必填（退款必须能归到订单）");
        }
        if (status == null || status.isBlank()) {
            status = "UNKNOWN";
        }
    }

    public boolean completed() {
        return "COMPLETED".equals(status);
    }
}
