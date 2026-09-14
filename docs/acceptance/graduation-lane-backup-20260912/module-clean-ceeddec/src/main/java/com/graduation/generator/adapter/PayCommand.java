package com.graduation.generator.adapter;

/**
 * 支付指令（§4.1 {@code pay(PayCommand)} 的入参；指导书未定义字段，此处为最小设计）。
 *
 * <p>参考商城的 {@code POST /api/v1/mall/orders/{orderId}/pay} 需要 {@code userId} 与订单同属一人
 * （实测），故显式带上而不是从订单里猜。</p>
 */
public record PayCommand(String orderId, String userId) {

    public PayCommand {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId 必填");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 必填（参考商城的支付接口要校验订单归属）");
        }
    }
}
