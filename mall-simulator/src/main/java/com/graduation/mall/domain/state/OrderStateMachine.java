package com.graduation.mall.domain.state;

import com.graduation.mall.domain.enums.OrderStatus;

/**
 * 订单状态机 —— 纯 Java 逻辑，无 DB 依赖，可独立单元测试（§20.1）。
 * 合法边：
 *   CREATED   → PAID | CANCELLED
 *   PAID      → COMPLETED | REFUNDING
 *   COMPLETED → REFUNDING
 *   REFUNDING → REFUNDED
 */
public final class OrderStateMachine {

    private OrderStateMachine() {
    }

    public static void validateTransition(OrderStatus from, OrderStatus to) {
        boolean allowed = switch (from) {
            case CREATED -> to == OrderStatus.PAID || to == OrderStatus.CANCELLED;
            case PAID -> to == OrderStatus.COMPLETED || to == OrderStatus.REFUNDING;
            case COMPLETED -> to == OrderStatus.REFUNDING;
            case REFUNDING -> to == OrderStatus.REFUNDED;
            case CANCELLED, REFUNDED -> false;
        };
        if (!allowed) {
            throw new IllegalOrderStateException(from, to);
        }
    }

    public static class IllegalOrderStateException extends RuntimeException {
        private final OrderStatus from;
        private final OrderStatus to;

        public IllegalOrderStateException(OrderStatus from, OrderStatus to) {
            super("非法订单状态流转: " + from + " -> " + to);
            this.from = from;
            this.to = to;
        }

        public OrderStatus getFrom() {
            return from;
        }

        public OrderStatus getTo() {
            return to;
        }
    }
}