package com.graduation.generator.adapter;

/**
 * 取消指令（§4.1 {@code cancel(CancelCommand)} 的入参；指导书未定义字段，此处为最小设计）。
 *
 * <p>{@code reason} 用契约 {@code $defs} 之外的自由文本：参考商城把它原样存进
 * {@code cancel_reason}（实测取值示例 {@code user_cancel/payment_timeout/out_of_stock}），
 * 商城侧没有枚举约束，生成器不替它发明一套——只要求非空，避免出现"原因未知"的订单。</p>
 */
public record CancelCommand(String orderId, String userId, String reason) {

    public CancelCommand {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId 必填");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 必填");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 必填（取消原因会落商城订单表，不能为空）");
        }
        reason = reason.trim();
    }
}
