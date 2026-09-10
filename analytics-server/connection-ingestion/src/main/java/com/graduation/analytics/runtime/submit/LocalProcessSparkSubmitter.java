package com.graduation.analytics.runtime.submit;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 本地进程提交器（§8.2/LOCAL）：用 ProcessBuilder 执行 spark-submit（本机
 * D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd 或配置路径），stdout 捕获
 * 一行 JobResult JSON（spark-jobs 契约，§24.5），日志归档到 landing/logs 供 status/logs。
 * R6：日志文件名固定为 {externalJobId}.log（修复早期 {logPrefix}-{jobId}.log 写 /
 * {externalJobId}.log 读不一致，§13.3 日志可溯源）。
 */
@Slf4j
public class LocalProcessSparkSubmitter implements JobSubmitter {

    private final String sparkSubmitPath;
    private final String logRoot;

    public LocalProcessSparkSubmitter(String sparkSubmitPath, String logRoot) {
        this.sparkSubmitPath = sparkSubmitPath;
        this.logRoot = logRoot;
    }

    @Override
    public String type() {
        return "local-process";
    }

    @Override
    public SubmitResult submit(List<String> command, String logPrefix) {
        try {
            Path logDir = Paths.get(logRoot).toAbsolutePath();
            Files.createDirectories(logDir);
            String jobId = "lp-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
            // §13.3：日志文件与 externalJobId 同名（logs(externalJobId) 可直接溯源）。
            // R6-12（V2.0 §15.3）：文件名前置 runId/stage/jobCode/attempt，便于运维按运行检索；
            // 仍在文件名中保留完整 externalJobId，logs() 用 *__{jobId}.log 兜底匹配。
            Path logFile = logDir.resolve(logFileName(logPrefix, jobId));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // 异步落日志（避免管道死锁）
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append(System.lineSeparator());
                    }
                    Files.writeString(logFile, sb.toString(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("capture spark-submit log failed: {}", e.getMessage());
                }
            }, "spark-log-" + jobId);
            thread.setDaemon(true);
            thread.start();

            log.info("spark-submit {} -> pid={} log={}", jobId, proc.pid(), logFile);
            return new SubmitResult(jobId, "local process pid=" + proc.pid());
        } catch (IOException e) {
            throw new IllegalStateException("启动 spark-submit 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String status(String externalJobId) {
        // 本地进程为一次性提交；状态由流水线根据进程退出码 + JobResult JSON 判定
        return "SUBMITTED";
    }

    @Override
    public String logs(String externalJobId) {
        try {
            Path logDir = Paths.get(logRoot).toAbsolutePath();
            Path logFile = logDir.resolve(externalJobId + ".log");
            if (!Files.exists(logFile)) {
                // R6-12：带前缀命名（{runId}-{stage}-{jobCode}-a{attempt}__{jobId}.log）时按后缀匹配
                logFile = findByJobId(logDir, externalJobId);
            }
            if (logFile != null && Files.exists(logFile)) {
                String content = Files.readString(logFile, StandardCharsets.UTF_8);
                return content.length() > 20000 ? content.substring(content.length() - 20000) : content;
            }
            return "(日志文件不存在: " + logFile + ")";
        } catch (IOException e) {
            return "(读取日志失败: " + e.getMessage() + ")";
        }
    }

    /** R6-12：日志文件绝对路径（落 spark_job_run.log_uri，可溯源） */
    @Override
    public String logUri(String externalJobId) {
        try {
            Path logDir = Paths.get(logRoot).toAbsolutePath();
            Path exact = logDir.resolve(externalJobId + ".log");
            Path found = Files.exists(exact) ? exact : findByJobId(logDir, externalJobId);
            return found == null ? logDir.resolve(logFileName(null, externalJobId)).toString()
                    : found.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /** R6-12：日志文件名前缀（{runId}-{stage}-{jobCode}-a{attempt}），非法字符替换为 '-' */
    static String logFileName(String logPrefix, String jobId) {
        if (logPrefix == null || logPrefix.isBlank()) {
            return jobId + ".log";
        }
        String safe = logPrefix.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        return safe + "__" + jobId + ".log";
    }

    /** 按 externalJobId 后缀查找日志文件（前缀命名兼容；找不到返回 null） */
    private static Path findByJobId(Path logDir, String externalJobId) throws IOException {
        if (!Files.isDirectory(logDir)) {
            return null;
        }
        try (java.util.stream.Stream<Path> list = Files.list(logDir)) {
            return list.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith("__" + externalJobId + ".log");
            }).findFirst().orElse(null);
        }
    }

    @Override
    public void cancel(String externalJobId) {
        log.warn("本地进程任务不支持远程取消: {}", externalJobId);
    }

    @Override
    public HealthResult healthCheck() {
        Path sp = Paths.get(sparkSubmitPath);
        if (!Files.exists(sp)) {
            return new HealthResult(false, "spark-submit 不存在: " + sparkSubmitPath);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(sparkSubmitPath, "--version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean exited = proc.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                proc.destroyForcibly();
                return new HealthResult(false, "spark-submit --version 超时");
            }
            return new HealthResult(proc.exitValue() == 0,
                    "spark-submit 可执行 (exit=" + proc.exitValue() + ") @ " + sparkSubmitPath);
        } catch (Exception e) {
            return new HealthResult(false, "spark-submit --version 失败: " + e.getMessage());
        }
    }
}