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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 本地进程提交器（§8.2/LOCAL）：用 ProcessBuilder 执行 spark-submit（本机
 * D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd 或配置路径），stdout 捕获
 * 一行 JobResult JSON（spark-jobs 契约，§24.5），日志归档到 landing/logs 供 status/logs。
 * R6：日志文件名固定为 {externalJobId}.log（修复早期 {logPrefix}-{jobId}.log 写 /
 * {externalJobId}.log 读不一致，§13.3 日志可溯源）。
 *
 * <p>M1-11（D-019，2026-09-11）：status() 由"永远 SUBMITTED"改为**真实进程态**
 * （RUNNING / SUCCESS / FAILED / CANCELLED）——原先进程已崩但日志无 JobResult 行时，平台只能空等到
 * 阶段超时（实测 900s）；cancel() 由"不支持"改为真实终止进程树。
 *
 * <p>DEF-06（2026-09-11 实测）：Windows 上子 JVM 默认按本地代码页（GBK）写出中文，平台按 UTF-8 读入
 * → 落库 detail 出现乱码（`8 ���ݴ��������>0`）。修复：为子进程注入 UTF-8 编码选项。
 */
@Slf4j
public class LocalProcessSparkSubmitter implements JobSubmitter {

    /** DEF-06：子 JVM（spark-submit 会再起 driver/executor JVM，均可继承）显式使用 UTF-8 */
    static final String CHILD_ENCODING_OPTS =
            "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8";

    /** M1-11：存活进程句柄（jobId → Process），供 status()/cancel() 查询与终止真实进程 */
    private static final Map<String, Process> LIVE = new ConcurrentHashMap<>();

    /** M1-11：已结束作业的退出码（有界 LRU，避免平台长跑内存增长） */
    static final int EXIT_CODE_CAPACITY = 256;
    private static final Map<String, Integer> EXIT_CODES = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > EXIT_CODE_CAPACITY;
                }
            });

    /** M1-11：被 cancel() 主动终止的作业（status 返回 CANCELLED，不误报 FAILED） */
    private static final Set<String> CANCELLED = ConcurrentHashMap.newKeySet();

    private final String sparkSubmitPath;
    private final String logRoot;

    public LocalProcessSparkSubmitter(String sparkSubmitPath, String logRoot) {
        this.sparkSubmitPath = sparkSubmitPath;
        this.logRoot = logRoot;
    }

    /** DEF-06：为子进程注入 UTF-8 编码选项；已有 JAVA_TOOL_OPTIONS 时追加而不覆盖 */
    static void applyChildEncoding(ProcessBuilder pb) {
        pb.environment().merge("JAVA_TOOL_OPTIONS", CHILD_ENCODING_OPTS, (a, b) -> a + " " + b);
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
            applyChildEncoding(pb); // DEF-06：子 JVM 输出中文按 UTF-8，避免落库乱码
            Process proc = pb.start();
            // M1-11：登记存活句柄 + 退出码（status() 依据真实进程态判定，不再空等超时）
            LIVE.put(jobId, proc);
            proc.onExit().whenComplete((p, err) -> {
                LIVE.remove(jobId);
                try {
                    EXIT_CODES.put(jobId, p.exitValue());
                } catch (IllegalThreadStateException stillAlive) {
                    // 极端竞态：onExit 回调瞬间进程仍报存活 → 下一次 status() 轮询重新判定
                    LIVE.putIfAbsent(jobId, p);
                }
            });
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
        // M1-11（D-019）：返回真实进程态——RUNNING（存活）/ SUCCESS / FAILED（已退出，按退出码）/
        // CANCELLED（被 cancel() 终止）。契约由 SparkStageExecutor 轮询消费：非终结值继续轮询，
        // SUCCESS/FAILED/CANCELLED 触发终结判定（再由日志 JobResult 行与退出码综合判定，§13.2 不冒充成功）。
        if (CANCELLED.contains(externalJobId)) {
            return "CANCELLED";
        }
        Process live = LIVE.get(externalJobId);
        if (live != null) {
            // 已退出但退出码登记尚未完成 → 仍返回非终结值，下一轮重新判定
            return live.isAlive() ? "RUNNING" : "SUBMITTED";
        }
        Integer exitCode = EXIT_CODES.get(externalJobId);
        if (exitCode != null) {
            return exitCode == 0 ? "SUCCESS" : "FAILED";
        }
        // 无句柄（平台重启后查询历史作业、或非本进程提交）：保持"由日志 JobResult 行判定"的既有语义
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
                return tailWithJobResult(content);
            }
            return "(日志文件不存在: " + logFile + ")";
        } catch (IOException e) {
            return "(读取日志失败: " + e.getMessage() + ")";
        }
    }

    /** 回传日志窗口大小（字符） */
    static final int LOG_TAIL_CHARS = 20000;
    /** JobResult 结果行起始标记（spark-jobs 契约 §24.5：jobCode 为首字段） */
    static final String JOB_RESULT_MARKER = "{\"jobCode\"";

    /**
     * 返回日志文本，且**必须包含完整的 JobResult 结果行**。
     *
     * <p>DEF-03（2026-09-11 实测）：结果行可能极长——run 34 的 LOAD_ODS 结果行实测 **154 365 字符**
     * （`outputPartitions` 逐 dt/hour 分区列出 1000 行的落点）。因此：
     * ① 只取尾部 20KB 会把结果行整行挤掉；② 即使从结果行起点截取、再截断到 20KB，也会把 JSON 截断成
     * 非法 JSON（`JobResultParser` 的 `readTree` 抛错被忽略 → 解析为空）→ 平台误判"未找到 JobResult 结果行"
     * 并空等到阶段超时，作业本身其实已 SUCCESS。
     *
     * <p>规则：结果行整体在尾部窗口内时按原样返回尾部窗口（既有行为不变）；否则返回该结果行本身
     * （从行首到行尾，长度不设上限，完整性优先）。
     */
    static String tailWithJobResult(String content) {
        int windowStart = Math.max(0, content.length() - LOG_TAIL_CHARS);
        String tail = content.substring(windowStart);
        if (content.length() <= LOG_TAIL_CHARS) {
            return content;
        }
        int marker = content.lastIndexOf(JOB_RESULT_MARKER);
        if (marker < 0 || marker >= windowStart) {
            // 无结果行，或结果行整体已在窗口内（窗口延伸到文本末尾，行必然完整）
            return tail;
        }
        int lineEnd = content.indexOf('\n', marker);
        int resultEnd = lineEnd < 0 ? content.length() : lineEnd;
        return content.substring(marker, resultEnd);
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
        // M1-11：真实终止本地进程树（spark-submit.cmd → java driver → executor 子进程）
        CANCELLED.add(externalJobId);
        Process proc = LIVE.remove(externalJobId);
        if (proc == null) {
            log.warn("本地进程任务无存活句柄，无法终止（可能已结束或平台已重启）: {}", externalJobId);
            return;
        }
        List<ProcessHandle> children = proc.descendants().collect(Collectors.toList());
        children.forEach(ProcessHandle::destroy);
        proc.destroy();
        try {
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                children.forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
        }
        log.warn("本地进程任务已终止: {} pid={} 子进程={}", externalJobId, proc.pid(), children.size());
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