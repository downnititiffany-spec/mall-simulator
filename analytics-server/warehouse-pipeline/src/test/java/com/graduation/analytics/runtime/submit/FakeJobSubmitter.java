package com.graduation.analytics.runtime.submit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * R6 快速测试替身：可编程 JobSubmitter（不启动 Spark/SSH）。
 * 可预置 status/logs/healthCheck 行为；submit 返回递增 externalJobId 并记录命令，
 * 供 PipelineStageExecutor 类测试断言外部任务号/日志/状态（§13.3、§8.2 契约）。
 * R6-11：支持按 jobCode 分别编程（验证阶段内 fail-fast：前序作业成功、后续失败时不再提交）。
 */
public class FakeJobSubmitter implements JobSubmitter {

    private final AtomicInteger seq = new AtomicInteger(0);
    private final List<String> submittedCommands = new ArrayList<>();
    private volatile String programStatus = "RUNNING";
    private volatile String programLogs = "";
    private volatile HealthResult programHealth = new HealthResult(true, "fake-ok");
    private volatile String lastExternalJobId = null;
    /** externalJobId → jobCode（submit 时从命令解析） */
    private final Map<String, String> jobCodeById = new HashMap<>();
    /** jobCode → [status, logs]（覆盖全局 program） */
    private final Map<String, String[]> perJob = new HashMap<>();

    /** 预置状态机：RUNNING/SUCCESS/FAILED */
    public FakeJobSubmitter program(String status, String logs) {
        this.programStatus = status;
        this.programLogs = logs == null ? "" : logs;
        return this;
    }

    /** R6-11：按 jobCode 单独编程（用于阶段内 fail-fast：前序成功、后续失败） */
    public FakeJobSubmitter programJob(String jobCode, String status, String logs) {
        perJob.put(jobCode, new String[]{status, logs == null ? "" : logs});
        return this;
    }

    public FakeJobSubmitter programHealth(boolean ok, String detail) {
        this.programHealth = new HealthResult(ok, detail);
        return this;
    }

    @Override
    public String type() {
        return "fake";
    }

    @Override
    public SubmitResult submit(List<String> command, String logPrefix) {
        String id = "fake-" + seq.incrementAndGet();
        this.lastExternalJobId = id;
        String joined = String.join(" ", command);
        this.submittedCommands.add(joined);
        jobCodeById.put(id, parseJobCode(joined));
        return new SubmitResult(id, "fake submit ok");
    }

    @Override
    public String status(String externalJobId) {
        String[] p = perJob.get(jobCodeById.get(externalJobId));
        return p == null ? programStatus : p[0];
    }

    @Override
    public String logs(String externalJobId) {
        String[] p = perJob.get(jobCodeById.get(externalJobId));
        return p == null ? programLogs : p[1];
    }

    private static String parseJobCode(String commandLine) {
        for (String part : commandLine.split(" ")) {
            if (part.startsWith("--jobCode=")) {
                return part.substring("--jobCode=".length());
            }
        }
        return null;
    }

    @Override
    public void cancel(String externalJobId) {
        programStatus = "CANCELLED";
    }

    @Override
    public HealthResult healthCheck() {
        return programHealth;
    }

    public String lastExternalJobId() {
        return lastExternalJobId;
    }

    public int submitCount() {
        return submittedCommands.size();
    }

    /** 最后一次提交的原始命令（空格拼接，供断言参数完整） */
    public String lastCommand() {
        return submittedCommands.isEmpty() ? null : submittedCommands.get(submittedCommands.size() - 1);
    }

    public List<String> submittedCommands() {
        return submittedCommands;
    }
}