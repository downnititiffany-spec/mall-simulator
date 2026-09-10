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
    void logFileEmbedsPrefixAndIsStillTraceableByExternalJobId() throws Exception {
        LocalProcessSparkSubmitter submitter =
                new LocalProcessSparkSubmitter("cmd", tempDir.toString());
        String prefix = "prefix-" + System.currentTimeMillis();
        JobSubmitter.SubmitResult sr = submitter.submit(
                List.of("cmd", "/c", "echo", "x"), prefix);

        // 日志由异步守护线程写完（进程先退出再落盘），轮询等待文件出现。
        // R6-12（V2.0 §15.3）：文件名 = {prefix}__{externalJobId}.log，运维可按运行检索，
        // 同时保留完整 externalJobId 供 logs() 溯源。
        Path expected = tempDir.resolve(prefix + "__" + sr.externalJobId() + ".log");
        for (int i = 0; i < 50 && !Files.exists(expected); i++) {
            Thread.sleep(100);
        }
        assertThat(Files.exists(expected)).as("前缀命名日志文件应存在: " + expected).isTrue();
        try (var list = Files.list(tempDir)) {
            assertThat(list.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("__" + sr.externalJobId() + ".log")).count())
                    .as("按 externalJobId 后缀应恰好命中一个日志文件").isEqualTo(1);
        }
        // 仍可按 externalJobId 读回（R6-7a 修复的语义不回退：不再出现"日志文件不存在"）
        assertThat(submitter.logs(sr.externalJobId())).doesNotContain("日志文件不存在");
    }

    /** R6-12：日志文件名必须包含 runId/stage/jobCode/attempt 与 externalJobId */
    @Test
    void logFileNameCarriesRunStageJobAndAttempt() {
        String name = LocalProcessSparkSubmitter.logFileName(
                "pipeline-42-BUILD_DWD-bdw-a3", "lp-1700000000-abcdef");
        assertThat(name).isEqualTo("pipeline-42-BUILD_DWD-bdw-a3__lp-1700000000-abcdef.log");
        // 非法字符（路径分隔符/空格）替换为 '-'，避免越出日志根目录
        String sanitized = LocalProcessSparkSubmitter.logFileName("a/b c", "lp-1-xxxxxx");
        assertThat(sanitized).isEqualTo("a-b-c__lp-1-xxxxxx.log");
        // 无前缀时退回 {externalJobId}.log（兼容旧调用）
        assertThat(LocalProcessSparkSubmitter.logFileName(null, "lp-1-xxxxxx"))
                .isEqualTo("lp-1-xxxxxx.log");
    }

    @Test
    void logsMissingFileReturnsReadableMessage() {
        LocalProcessSparkSubmitter submitter =
                new LocalProcessSparkSubmitter("cmd", tempDir.toString());
        String logs = submitter.logs("lp-999999-nonexistent");
        assertThat(logs).contains("日志文件不存在");
    }
}