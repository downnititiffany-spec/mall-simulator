package com.graduation.mall.decision;

import com.graduation.mall.decision.DecisionStateMachine.IllegalDecisionStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策状态机单元测试（§22.6 全生命周期）。
 */
class DecisionStateMachineTest {

    @Test
    @DisplayName("主链路全流转合法")
    void fullLifecycleAllowed() {
        assertDoesNotThrow(() -> DecisionStateMachine.validate(
                DecisionStateMachine.DRAFT, DecisionStateMachine.PENDING_REVIEW));
        assertDoesNotThrow(() -> DecisionStateMachine.validate(
                DecisionStateMachine.PENDING_REVIEW, DecisionStateMachine.APPROVED));
        assertDoesNotThrow(() -> DecisionStateMachine.validate(
                DecisionStateMachine.APPROVED, DecisionStateMachine.IN_PROGRESS));
        assertDoesNotThrow(() -> DecisionStateMachine.validate(
                DecisionStateMachine.IN_PROGRESS, DecisionStateMachine.COMPLETED));
        assertDoesNotThrow(() -> DecisionStateMachine.validate(
                DecisionStateMachine.COMPLETED, DecisionStateMachine.EVALUATING));
        for (String terminal : new String[]{DecisionStateMachine.EFFECTIVE,
                DecisionStateMachine.PARTIAL, DecisionStateMachine.INEFFECTIVE,
                DecisionStateMachine.INSUFFICIENT_DATA}) {
            assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.EVALUATING, terminal));
        }
    }

    @Test
    @DisplayName("旁路：审核前可驳回，执行中可取消")
    void sidePathsAllowed() {
        assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.DRAFT, DecisionStateMachine.REJECTED));
        assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.PENDING_REVIEW, DecisionStateMachine.REJECTED));
        assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.IN_PROGRESS, DecisionStateMachine.CANCELLED));
    }

    @Test
    @DisplayName("非法流转与终态全部拒绝")
    void illegalTransitionsRejected() {
        assertThrows(IllegalDecisionStateException.class, () ->
                DecisionStateMachine.validate(DecisionStateMachine.DRAFT, DecisionStateMachine.APPROVED));
        assertThrows(IllegalDecisionStateException.class, () ->
                DecisionStateMachine.validate(DecisionStateMachine.APPROVED, DecisionStateMachine.COMPLETED));
        assertThrows(IllegalDecisionStateException.class, () ->
                DecisionStateMachine.validate(DecisionStateMachine.COMPLETED, DecisionStateMachine.IN_PROGRESS));
        assertThrows(IllegalDecisionStateException.class, () ->
                DecisionStateMachine.validate(DecisionStateMachine.INEFFECTIVE, DecisionStateMachine.IN_PROGRESS));
        assertThrows(IllegalDecisionStateException.class, () ->
                DecisionStateMachine.validate(DecisionStateMachine.CANCELLED, DecisionStateMachine.COMPLETED));
        assertFalse(DecisionStateMachine.allowed(DecisionStateMachine.EFFECTIVE, DecisionStateMachine.EVALUATING));
    }
}