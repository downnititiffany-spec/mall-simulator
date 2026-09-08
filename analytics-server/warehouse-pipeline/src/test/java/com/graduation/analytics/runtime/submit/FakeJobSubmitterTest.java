package com.graduation.analytics.runtime.submit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6 快速测试：FakeJobSubmitter 替身自身行为（可编程状态机 + 命令记录）。
 * 它是后续阶段提交易测的基座，先证明替身本身可靠（§8.2 提交契约）。
 */
class FakeJobSubmitterTest {

    @Test
    void submitReturnsIncrementingIdsAndRecordsCommand() {
        FakeJobSubmitter sub = new FakeJobSubmitter();
        JobSubmitter.SubmitResult r1 = sub.submit(List.of("spark-submit", "--class", "X"), "p" + 1);
        JobSubmitter.SubmitResult r2 = sub.submit(List.of("spark-submit", "--class", "Y"), "p" + 2);

        assertThat(r1.externalJobId()).isEqualTo("fake-1");
        assertThat(r2.externalJobId()).isEqualTo("fake-2");
        assertThat(r1.detail()).isNotBlank();
        assertThat(sub.submitCount()).isEqualTo(2);
        assertThat(sub.lastCommand()).contains("--class Y");
        assertThat(sub.lastExternalJobId()).isEqualTo("fake-2");
    }

    @Test
    void programmedStatusAndLogsAreReturned() {
        FakeJobSubmitter sub = new FakeJobSubmitter().program("FAILED", "JobResult ERROR");

        sub.submit(List.of("spark-submit"), "p");
        assertThat(sub.status("fake-1")).isEqualTo("FAILED");
        assertThat(sub.logs("fake-1")).isEqualTo("JobResult ERROR");
    }

    @Test
    void healthCheckCanBeProgrammed() {
        FakeJobSubmitter sub = new FakeJobSubmitter().programHealth(false, "no spark home");

        assertThat(sub.healthCheck().ok()).isFalse();
        assertThat(sub.healthCheck().detail()).isEqualTo("no spark home");
    }

    @Test
    void cancelMovesToCancelled() {
        FakeJobSubmitter sub = new FakeJobSubmitter();

        sub.submit(List.of("spark-submit"), "p");
        sub.cancel("fake-1");
        assertThat(sub.status("fake-1")).isEqualTo("CANCELLED");
    }

    @Test
    void typeIsFake() {
        assertThat(new FakeJobSubmitter().type()).isEqualTo("fake");
    }
}