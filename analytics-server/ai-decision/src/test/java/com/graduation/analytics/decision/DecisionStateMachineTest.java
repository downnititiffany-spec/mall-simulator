package com.graduation.analytics.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策状态机（§20.3 / R8-3 §3.3）：只测真实规则，不测实现细节。
 */
class DecisionStateMachineTest {

    @Test
    @DisplayName("主链路合法：DRAFT→PENDING_REVIEW→APPROVED→IN_PROGRESS→COMPLETED→EVALUATING→结论")
    void mainPathAllowed() {
        assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.DRAFT, DecisionStateMachine.PENDING_REVIEW));
        assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.PENDING_REVIEW, DecisionStateMachine.APPROVED));
        assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.APPROVED, DecisionStateMachine.IN_PROGRESS));
        assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.IN_PROGRESS, DecisionStateMachine.COMPLETED));
        assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.COMPLETED, DecisionStateMachine.EVALUATING));
        for (String result : new String[]{DecisionStateMachine.EFFECTIVE, DecisionStateMachine.PARTIAL,
                DecisionStateMachine.INEFFECTIVE, DecisionStateMachine.INSUFFICIENT_DATA}) {
            assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.EVALUATING, result),
                    "EVALUATING → " + result);
        }
    }

    @Test
    @DisplayName("审计前可驳回、执行中可取消")
    void rejectAndCancelAllowed() {
        assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.DRAFT, DecisionStateMachine.REJECTED));
        assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.PENDING_REVIEW, DecisionStateMachine.REJECTED));
        assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.IN_PROGRESS, DecisionStateMachine.CANCELLED));
    }

    @Test
    @DisplayName("数据不足不是终态：补齐窗口后可再评一次（§20.3 不得归类为无效）")
    void insufficientDataCanBeReEvaluated() {
        assertTrue(DecisionStateMachine.allowed(DecisionStateMachine.INSUFFICIENT_DATA, DecisionStateMachine.EVALUATING));
    }

    @Test
    @DisplayName("AI 不能跳过人工审核：DRAFT 不能直达 APPROVED / IN_PROGRESS / EVALUATING")
    void aiCannotSkipHumanReview() {
        assertFalse(DecisionStateMachine.allowed(DecisionStateMachine.DRAFT, DecisionStateMachine.APPROVED));
        assertFalse(DecisionStateMachine.allowed(DecisionStateMachine.DRAFT, DecisionStateMachine.IN_PROGRESS));
        assertFalse(DecisionStateMachine.allowed(DecisionStateMachine.DRAFT, DecisionStateMachine.EVALUATING));
        assertFalse(DecisionStateMachine.allowed(DecisionStateMachine.PENDING_REVIEW, DecisionStateMachine.IN_PROGRESS));
        assertFalse(DecisionStateMachine.allowed(DecisionStateMachine.APPROVED, DecisionStateMachine.COMPLETED));
    }

    @Test
    @DisplayName("终态不可再流转（EFFECTIVE/PARTIAL/INEFFECTIVE/REJECTED/CANCELLED）")
    void terminalStatesAreTerminal() {
        for (String terminal : new String[]{DecisionStateMachine.EFFECTIVE, DecisionStateMachine.PARTIAL,
                DecisionStateMachine.INEFFECTIVE, DecisionStateMachine.REJECTED, DecisionStateMachine.CANCELLED}) {
            for (String target : new String[]{DecisionStateMachine.DRAFT, DecisionStateMachine.PENDING_REVIEW,
                    DecisionStateMachine.APPROVED, DecisionStateMachine.IN_PROGRESS, DecisionStateMachine.COMPLETED,
                    DecisionStateMachine.EVALUATING, DecisionStateMachine.EFFECTIVE}) {
                assertFalse(DecisionStateMachine.allowed(terminal, target), terminal + " → " + target);
            }
        }
    }

    @Test
    @DisplayName("非法流转抛 IllegalDecisionStateException 并带上起止状态")
    void validateThrowsWithStates() {
        DecisionStateMachine.IllegalDecisionStateException e = assertThrows(
                DecisionStateMachine.IllegalDecisionStateException.class,
                () -> DecisionStateMachine.validate(DecisionStateMachine.DRAFT, DecisionStateMachine.COMPLETED));
        assertEquals(DecisionStateMachine.DRAFT, e.getFrom());
        assertEquals(DecisionStateMachine.COMPLETED, e.getTo());
        assertTrue(e.getMessage().contains("DRAFT"));
    }

    @Test
    @DisplayName("未知状态一律拒绝（fail-closed）")
    void unknownStateRejected() {
        assertFalse(DecisionStateMachine.allowed("WHATEVER", DecisionStateMachine.APPROVED));
        assertFalse(DecisionStateMachine.allowed(DecisionStateMachine.DRAFT, "WHATEVER"));
    }
}
