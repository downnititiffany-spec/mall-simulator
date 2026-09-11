package com.graduation.analytics.runtime.submit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1-11（D-019）+ DEF-06 回归测试（L0：只起本地短命令，不启动 Spark）。
 *
 * <p>M1-11 背景：status() 原实现永远返回 SUBMITTED，进程崩溃但日志无 JobResult 行时平台只能空等到阶段超时
 * （run 33 实测 900s）；cancel() 原实现只打一条 warn，不终止进程。本测试验证：
 * ① 存活进程 → RUNNING；② 退出码 0/非 0 → SUCCESS/FAILED（平台据此快速判失败）；
 * ③ cancel() 真终止进程并给出 CANCELLED；④ 无句柄历史作业仍保持 SUBMITTED 旧语义。
 *
 * <p>DEF-06 背景：Windows 子 JVM 默认按本地代码页（GBK）写中文，平台按 UTF-8 读 → 落库 detail 乱码。
 */
class LocalProcessSparkSubmitterProcessTest {

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static LocalProcessSparkSubmitter submitter(Path logDir) {
        return new LocalProcessSparkSubmitter("spark-submit-noop", logDir.toString());
    }

    /** cmd.exe /c exit <code>：真实子进程，退出码确定 */
    private static List<String> exitCmd(int code) {
        return List.of("cmd", "/c", "exit", String.valueOf(code));
    }

    private static String waitStatus(LocalProcessSparkSubmitter s, String jobId, long timeoutMs, String... wanted) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String st = null;
        while (System.currentTimeMillis() < deadline) {
            st = s.status(jobId);
            for (String w : wanted) {
                if (w.equals(st)) {
                    return st;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return st;
    }

    @Test
    @DisplayName("DEF-06：为子进程注入 UTF-8 编码选项；已有 JAVA_TOOL_OPTIONS 时追加而非覆盖")
    void injectsUtf8OptionsForChildProcess() {
        ProcessBuilder pb = new ProcessBuilder("java", "-version");
        LocalProcessSparkSubmitter.applyChildEncoding(pb);
        assertThat(pb.environment().get("JAVA_TOOL_OPTIONS"))
                .contains("-Dfile.encoding=UTF-8")
                .contains("-Dstdout.encoding=UTF-8")
                .contains("-Dstderr.encoding=UTF-8");

        ProcessBuilder withExisting = new ProcessBuilder("java", "-version");
        withExisting.environment().put("JAVA_TOOL_OPTIONS", "-Xmx1g");
        LocalProcessSparkSubmitter.applyChildEncoding(withExisting);
        assertThat(withExisting.environment().get("JAVA_TOOL_OPTIONS"))
                .startsWith("-Xmx1g")
                .contains("-Dfile.encoding=UTF-8");
    }

    @Test
    @DisplayName("M1-11：存活进程 status()=RUNNING；cancel() 后为 CANCELLED（真实终止进程树）")
    void reportsRunningThenCancelled(@TempDir Path logDir) {
        Assumptions.assumeTrue(windows(), "本地进程语义按 Windows cmd.exe 验证");
        LocalProcessSparkSubmitter s = submitter(logDir);
        // ping -n 6 127.0.0.1 ≈ 5s 存活窗口：足够观测 RUNNING 并执行 cancel
        String jobId = s.submit(List.of("cmd", "/c", "ping", "-n", "6", "127.0.0.1"), "m1-11-cancel")
                .externalJobId();

        assertThat(s.status(jobId)).isEqualTo("RUNNING");
        s.cancel(jobId);
        assertThat(s.status(jobId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("M1-11：退出码 0 → SUCCESS；退出码非 0 → FAILED（平台据此快速判失败，不再空等超时）")
    void reportsExitCodeAsTerminalStatus(@TempDir Path logDir) {
        Assumptions.assumeTrue(windows(), "本地进程语义按 Windows cmd.exe 验证");
        LocalProcessSparkSubmitter s = submitter(logDir);

        String ok = s.submit(exitCmd(0), "m1-11-ok").externalJobId();
        assertThat(waitStatus(s, ok, 15_000, "SUCCESS", "FAILED")).isEqualTo("SUCCESS");

        String bad = s.submit(exitCmd(3), "m1-11-bad").externalJobId();
        assertThat(waitStatus(s, bad, 15_000, "SUCCESS", "FAILED")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("M1-11：无句柄的历史作业仍返回 SUBMITTED（保持由日志 JobResult 行判定的既有语义）")
    void keepsLegacySemanticsForUnknownJob(@TempDir Path logDir) {
        assertThat(submitter(logDir).status("lp-unknown-job")).isEqualTo("SUBMITTED");
    }
}
