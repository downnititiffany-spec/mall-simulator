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
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 本地进程提交器（§8.2/LOCAL）：用 ProcessBuilder 执行 spark-submit（本机
 * D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd 或配置路径），stdout 捕获
 * 一行 JobResult JSON（spark-jobs 契约，§24.5），日志归档到 landing/logs 供 status/logs。
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
            Path logFile = logDir.resolve(logPrefix + "-" + jobId + ".log");

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
            Path logFile = Paths.get(logRoot).toAbsolutePath()
                    .resolve(externalJobId + ".log");
            if (Files.exists(logFile)) {
                String content = Files.readString(logFile, StandardCharsets.UTF_8);
                return content.length() > 20000 ? content.substring(content.length() - 20000) : content;
            }
            return "(日志文件不存在: " + logFile + ")";
        } catch (IOException e) {
            return "(读取日志失败: " + e.getMessage() + ")";
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