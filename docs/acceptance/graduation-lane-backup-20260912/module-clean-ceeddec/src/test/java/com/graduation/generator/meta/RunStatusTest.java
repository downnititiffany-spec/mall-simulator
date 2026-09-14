package com.graduation.generator.meta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行状态机单元测试（E2）：把 §4.2 的 {@code PENDING -> RUNNING -> SUCCESS/FAILED/CANCELLED} 钉住，
 * 并覆盖"终态不可再变"与"未知库值不得静默降级"两条负向断言。
 */
class RunStatusTest {

    @Test
    @DisplayName("主链路 PENDING -> RUNNING -> 三个终态均合法")
    void mainChainTransitionsAreLegal() {
        assertThat(RunStatus.PENDING.canTransitionTo(RunStatus.RUNNING)).isTrue();
        for (RunStatus terminal : new RunStatus[]{RunStatus.SUCCESS, RunStatus.FAILED, RunStatus.CANCELLED}) {
            assertThat(RunStatus.RUNNING.canTransitionTo(terminal)).as("RUNNING -> " + terminal).isTrue();
            assertThat(terminal.isTerminal()).as(terminal + " 是终态").isTrue();
            assertThat(terminal.allowedNext()).as(terminal + " 不得再迁移").isEmpty();
        }
    }

    @Test
    @DisplayName("受控扩展：入队未执行即取消（PENDING -> CANCELLED）允许")
    void pendingCanBeCancelledBeforeStart() {
        assertThat(RunStatus.PENDING.canTransitionTo(RunStatus.CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("负向：跳过 RUNNING、终态复活、非法跳转一律拒绝")
    void illegalTransitionsAreRejected() {
        assertThat(RunStatus.PENDING.canTransitionTo(RunStatus.SUCCESS)).as("未执行不得直接成功").isFalse();
        assertThat(RunStatus.PENDING.canTransitionTo(RunStatus.FAILED)).as("未执行不得直接失败").isFalse();
        assertThat(RunStatus.SUCCESS.canTransitionTo(RunStatus.FAILED)).as("成功不得改成失败").isFalse();
        assertThat(RunStatus.CANCELLED.canTransitionTo(RunStatus.RUNNING)).as("取消不得复活").isFalse();
        assertThat(RunStatus.RUNNING.canTransitionTo(RunStatus.PENDING)).as("不得回退").isFalse();
        assertThat(RunStatus.RUNNING.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName("库值解析：大小写宽容，未知值报错而不是当成 PENDING")
    void fromDbIsStrict() {
        assertThat(RunStatus.fromDb("running")).isEqualTo(RunStatus.RUNNING);
        assertThat(RunStatus.fromDb(" SUCCESS ")).isEqualTo(RunStatus.SUCCESS);
        assertThatThrownBy(() -> RunStatus.fromDb("QUEUED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知运行状态");
        assertThatThrownBy(() -> RunStatus.fromDb(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
