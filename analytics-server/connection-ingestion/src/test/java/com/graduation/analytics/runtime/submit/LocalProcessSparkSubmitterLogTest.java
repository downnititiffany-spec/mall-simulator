package com.graduation.analytics.runtime.submit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEF-03 回归测试（L0，不启动 Spark）：日志回传窗口必须包含 JobResult 结果行。
 *
 * <p>实测背景：run 33 的 LOAD_ODS 作业真实 SUCCESS（input=1000/output=1000），
 * 但 Windows shutdown hook 的临时目录删除噪声把结果行挤出 20KB 窗口，
 * 平台因此判定"未找到 JobResult 结果行"并空等到阶段超时。
 */
class LocalProcessSparkSubmitterLogTest {

    private static final String RESULT_LINE =
            "{\"jobCode\":\"odl\",\"inputRecords\":1000,\"outputRecords\":1000,\"rejectedRecords\":0,"
                    + "\"attemptNo\":1,\"status\":\"SUCCESS\",\"message\":\"accepted=1000\"}";

    @Test
    @DisplayName("结果行在窗口内时按原样返回尾部窗口（行为不变）")
    void keepsPlainTailWhenResultInsideWindow() {
        String noise = "x".repeat(30000);
        String content = noise + "\n" + RESULT_LINE + "\n";

        String returned = LocalProcessSparkSubmitter.tailWithJobResult(content);

        assertThat(returned).hasSize(LocalProcessSparkSubmitter.LOG_TAIL_CHARS);
        assertThat(returned).contains(RESULT_LINE);
    }

    @Test
    @DisplayName("结果行被超长堆栈噪声挤出窗口时，仍必须回传整行结果（DEF-03）")
    void keepsResultLineWhenBuriedUnderShutdownNoise() {
        StringBuilder noise = new StringBuilder();
        noise.append("26/09/11 13:40:50 INFO SessionState: METASTORE_FILTER_HOOK will be ignored\n");
        noise.append(RESULT_LINE).append('\n');
        for (int i = 0; i < 4000; i++) {
            noise.append("\tat org.apache.spark.util.SparkShutdownHook.run(ShutdownHookManager.scala:214)\n");
        }
        String content = noise.toString();
        assertThat(content.length()).isGreaterThan(LocalProcessSparkSubmitter.LOG_TAIL_CHARS);
        assertThat(content.substring(content.length() - LocalProcessSparkSubmitter.LOG_TAIL_CHARS))
                .doesNotContain("{\"jobCode\"");

        String returned = LocalProcessSparkSubmitter.tailWithJobResult(content);

        assertThat(returned).contains(RESULT_LINE);
    }

    @Test
    @DisplayName("结果行本身超过窗口（实测 154KB）时必须整行保留，不得截断 JSON（DEF-03 真实场景）")
    void keepsWholeHugeResultLine() {
        StringBuilder partitions = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            partitions.append("{\"table\":\"ods_user_event\",\"dt\":\"20260930\",\"hour\":\"")
                    .append(i % 24).append("\",\"rowCount\":1,\"path\":\"file:/D:/warehouse/ods/dt=2026")
                    .append(String.format("%04d", i)).append("\"},");
        }
        String hugeResult = "{\"jobCode\":\"odl\",\"inputRecords\":1000,\"outputRecords\":1000,"
                + "\"status\":\"SUCCESS\",\"outputPartitions\":[" + partitions + "],\"checks\":[]}";
        assertThat(hugeResult.length()).isGreaterThan(150000);
        String content = "26/09/11 13:40:50 INFO SessionState: start\n" + hugeResult + "\n"
                + "26/09/11 13:42:08 ERROR ShutdownHookManager: Exception while deleting Spark temp dir\n"
                + "\tat org.apache.spark.util.SparkShutdownHook.run(ShutdownHookManager.scala:214)\n";

        String returned = LocalProcessSparkSubmitter.tailWithJobResult(content);

        assertThat(returned).startsWith("{\"jobCode\":\"odl\"");
        assertThat(returned).endsWith("\"checks\":[]}");
        assertThat(returned).hasSize(hugeResult.length());
    }

    @Test
    @DisplayName("真实日志文件路径：logs(jobId) 端到端取到完整结果行")
    void logsReadsResultLineFromFile(@TempDir Path logRoot) throws IOException {
        LocalProcessSparkSubmitter submitter =
                new LocalProcessSparkSubmitter("D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd",
                        logRoot.toString());
        String jobId = "lp-1789105242867-3a1dcc";
        StringBuilder sb = new StringBuilder(RESULT_LINE).append('\n');
        for (int i = 0; i < 4000; i++) {
            sb.append("\tat java.base/java.lang.Thread.run(Thread.java:842)\n");
        }
        Files.writeString(logRoot.resolve(jobId + ".log"), sb.toString(), StandardCharsets.UTF_8);

        String returned = submitter.logs(jobId);

        assertThat(returned).contains("\"jobCode\":\"odl\"");
        assertThat(returned).contains("\"outputRecords\":1000");
    }

    @Test
    @DisplayName("无结果行时不误报：仍返回尾部窗口")
    void fallsBackToTailWhenNoResultLine() {
        String content = "y".repeat(50000);

        String returned = LocalProcessSparkSubmitter.tailWithJobResult(content);

        assertThat(returned).hasSize(LocalProcessSparkSubmitter.LOG_TAIL_CHARS);
        assertThat(returned).doesNotContain("jobCode");
    }
}
