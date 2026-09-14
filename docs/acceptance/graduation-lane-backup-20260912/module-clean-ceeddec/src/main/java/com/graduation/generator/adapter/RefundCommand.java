package com.graduation.generator.adapter;

import java.math.BigDecimal;

/**
 * 退款指令（§4.1 {@code refund(RefundCommand)} 的入参；指导书未定义字段，此处为最小设计）。
 *
 * <p>{@code amount} 用 {@link BigDecimal}：参考商城要求 {@code amount} 且校验"不得超过订单可退金额"，
 * 用 double 会在边界（等额整退）上偶发失败，属于自找的脏数据。</p>
 */
public record RefundCommand(String orderId, String userId, BigDecimal amount, String reason) {

    public RefundCommand {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId 必填");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 必填");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount 必须为正金额（0 元退款没有业务含义）：" + amount);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 必填（退款原因会落商城退款表）");
        }
        reason = reason.trim();
    }
}
