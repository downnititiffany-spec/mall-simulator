package com.graduation.mall.domain;

import com.graduation.mall.domain.enums.OrderStatus;
import com.graduation.mall.domain.state.OrderStateMachine;
import com.graduation.mall.domain.state.OrderStateMachine.IllegalOrderStateException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 订单状态机单元测试（§20.1 状态约束）。
 */
class OrderStateMachineTest {

    @Test
    void 合法流转全部通过() {
        assertDoesNotThrow(() -> OrderStateMachine.validateTransition(OrderStatus.CREATED, OrderStatus.PAID));
        assertDoesNotThrow(() -> OrderStateMachine.validateTransition(OrderStatus.CREATED, OrderStatus.CANCELLED));
        assertDoesNotThrow(() -> OrderStateMachine.validateTransition(OrderStatus.PAID, OrderStatus.COMPLETED));
        assertDoesNotThrow(() -> OrderStateMachine.validateTransition(OrderStatus.PAID, OrderStatus.REFUNDING));
        assertDoesNotThrow(() -> OrderStateMachine.validateTransition(OrderStatus.COMPLETED, OrderStatus.REFUNDING));
        assertDoesNotThrow(() -> OrderStateMachine.validateTransition(OrderStatus.REFUNDING, OrderStatus.REFUNDED));
    }

    @Test
    void 非法流转全部拒绝() {
        assertThrows(IllegalOrderStateException.class,
                () -> OrderStateMachine.validateTransition(OrderStatus.PAID, OrderStatus.CANCELLED));
        assertThrows(IllegalOrderStateException.class,
                () -> OrderStateMachine.validateTransition(OrderStatus.CREATED, OrderStatus.REFUNDING));
        assertThrows(IllegalOrderStateException.class,
                () -> OrderStateMachine.validateTransition(OrderStatus.CREATED, OrderStatus.COMPLETED));
        assertThrows(IllegalOrderStateException.class,
                () -> OrderStateMachine.validateTransition(OrderStatus.REFUNDED, OrderStatus.PAID));
        assertThrows(IllegalOrderStateException.class,
                () -> OrderStateMachine.validateTransition(OrderStatus.CANCELLED, OrderStatus.REFUNDING));
        assertThrows(IllegalOrderStateException.class,
                () -> OrderStateMachine.validateTransition(OrderStatus.REFUNDING, OrderStatus.PAID));
        assertThrows(IllegalOrderStateException.class,
                () -> OrderStateMachine.validateTransition(OrderStatus.COMPLETED, OrderStatus.CANCELLED));
    }
}