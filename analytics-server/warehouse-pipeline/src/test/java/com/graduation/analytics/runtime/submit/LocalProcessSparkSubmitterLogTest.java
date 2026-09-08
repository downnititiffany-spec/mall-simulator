package com.graduation.analytics.runtime.submit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6-7a：LocalProcessSparkSubmitter 日志名一致性修复验证。
 * 真实子进程（无 Spark）提交后，logs(externalJobId) 必须能读到 stdout（§13.3 日志可溯源）——
 * 早期 bug：写入 {logPrefix}-{jobId}.log、按 {externalJobId}.log 查找 → 永远读不到。
 */
class LocalProcessSparkSubmitterLogTest {

    @TempDir
    Path tempDir;

    @Test
    void submitThenLogsReadBackSameFileByExternalJobId() throws Exception {
        LocalProcessSparkSubmitter submitter =
                new LocalProcessSparkSubmitter("cmd", tempDir.toString());

        JobSubmitter.SubmitResult sr = submitter.submit(
                List.of("cmd", "/c", "echo", "MARKER-OK-123"), "pipeline-9-LOAD_ODS");

        String externalJobId = sr.externalJobId();
        assertThat(externalJobId).startsWith("lp-").isNotBlank();

        // 等真实子进程写完日志（轮询，最长 5s）
        String logs = "";
        for (int i = 0; i < 50; i++) {
            logs = submitter.logs(externalJobId);
            if (logs.contains("MARKER-OK-123")) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(logs).contains("MARKER-OK-123"); // 修复后：externalJobId 即日志文件名
    }

    @Test
    void logFileNamedAfterExternalJobId() throws Exception {
        LocalProcessSparkSubmitter submitter =
                new LocalProcessSparkSubmitter("cmd", tempDir.toString());
        JobSubmitter.SubmitResult sr = submitter.submit(
                List.of("cmd", "/c", "echo", "x"), "prefix-" + System.currentTimeMillis());

        // 日志由异步守护线程写完（进程先退出再落盘），轮询等待文件出现
        Path expected = tempDir.resolve(sr.externalJobId() + ".log");
        for (int i = 0; i < 50 && !Files.exists(expected); i++) {
            Thread.sleep(100);
        }
        assertThat(Files.exists(expected)).isTrue();
        // 不再产生以 logPrefix 命名的文件
        try (var list = Files.list(tempDir)) {
            assertThat(list.map(p -> p.getFileName().toString())
                    .filter(n -> n.contains("prefix-")).count()).isZero();
        }
    }

    @Test
    void logsMissingFileReturnsReadableMessage() {
        LocalProcessSparkSubmitter submitter =
                new LocalProcessSparkSubmitter("cmd", tempDir.toString());
        String logs = submitter.logs("lp-999999-nonexistent");
        assertThat(logs).contains("日志文件不存在");
    }
}