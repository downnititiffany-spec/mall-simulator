package com.graduation.mall.domain.enums;

/**
 * 订单状态（§20.1）：CREATED→PAID→COMPLETED，旁路 CANCELLED、REFUNDING→REFUNDED。
 * 合法流转由 {@link com.graduation.mall.domain.state.OrderStateMachine} 统一校验。
 */
public enum OrderStatus {
    CREATED, PAID, COMPLETED, CANCELLED, REFUNDING, REFUNDED
}