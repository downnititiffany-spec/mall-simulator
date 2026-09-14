package com.graduation.analytics.pipeline.spark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * R6：从 spark-submit 日志文本中解析最终 JobResult JSON（契约见
 * spark-jobs JobRunner.toJson：一行 {"jobCode","inputRecords","outputRecords",
 * "rejectedRecords","snapshotId"?,"attemptNo","status","message","elapsedMs",
 * "outputPartitions":[{"table","dt","snapshotId"?,"rowCount","path"?}]}）。
 * 只认包含 jobCode+status 的 JSON 行，且取最后一行为准；前面的普通 JSON 日志不会误判。
 * 纯函数、无 Spark/IO 依赖，供 L0 快速测试与真实流水线共用。
 */
public final class JobResultParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 输出分区证据（R6-12，V2.0 §15.3）：作业真实写出的表/dt/快照/行数/物理路径。
     * 缺失 outputPartitions 字段的旧作业结果解析为空表（不冒充、不造数）。
     */
    public record OutputPartitionInfo(String table, String dt, String snapshotId,
                                      long rowCount, String path) {
    }

    /**
     * 质量检查结果（R6-13，V2.0 §16.1/§16.5）：层次 + 目标表 + 检查数/错误数/阈值/严重度。
     *
     * <p><b>职责边界（F-88）</b>：本 record 是**作业回传 JSON 的忠实适配器**，severity 原样透传，
     * 并按 spark-jobs 自身契约（{@code JobResult.scala:17-18}）把 {@code BLOCKING} 解释为
     * 「作业未过必须 FAILED」。**平台侧的口径**（{@code BLOCKING}/{@code ERROR} 都阻断发布，
     * 见 D-142 §1）由 {@code RuleSeverity} 拥有，并在 {@code PipelineService.persistChecks}
     * 落库时归一化 —— 两个口径不混在同一个类里，避免"谁说了算"再次分叉。</p>
     */
    public record CheckInfo(String ruleCode, String layer, String targetTable,
                            long checkCount, long errorCount, String threshold,
                            String severity, boolean passed, String detail) {
        /** 作业契约里的阻断标签（只 BLOCKING；ERROR 在作业侧只是记录项）。 */
        public boolean blocking() {
            return "BLOCKING".equalsIgnoreCase(severity);
        }
    }

    /** 作业结果（snapshotId 可能缺失；outputPartitions/checks 可能为空）。 */
    public record JobResultInfo(String jobCode, long inputRecords, long outputRecords,
                                long rejectedRecords, String snapshotId, int attemptNo,
                                String status, String message, long elapsedMs,
                                List<OutputPartitionInfo> outputPartitions,
                                List<CheckInfo> checks) {
        public boolean success() {
            return "SUCCESS".equals(status);
        }

        /**
         * 作业返回 severity=BLOCKING 且未通过的规则（作业失败时用于定位"作业为什么 FAILED"）。
         *
         * <p>注意：这是**作业侧**判据，不等于平台发布门（平台门见
         * {@code RuleSeverity.blocks} 与 {@code DataQualityGate}）。</p>
         */
        public List<CheckInfo> blockingFailures() {
            return checks.stream().filter(c -> c.blocking() && !c.passed()).toList();
        }
    }

    private JobResultParser() {
    }

    /**
     * 从日志文本提取最后一行为 JobResult JSON 的结果；无结果行返回 empty。
     */
    public static Optional<JobResultInfo> parseLog(String logText) {
        if (logText == null || logText.isBlank()) {
            return Optional.empty();
        }
        String lastResultLine = null;
        for (String line : logText.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(trimmed);
                if (node.has("jobCode") && node.has("status")) {
                    lastResultLine = trimmed; // 取最后一个匹配（最终结果在日志尾部）
                }
            } catch (Exception ignored) {
                // 非 JobResult 的普通 JSON（如 INFO 日志）直接跳过
            }
        }
        return lastResultLine == null ? Optional.empty() : Optional.of(toInfo(lastResultLine));
    }

    /**
     * 结合进程退出码判定最终状态：退出码非 0 → 判失败（进程异常终止，即使日志声称 SUCCESS）；
     * 退出码 0 但无结果行 → 判失败（契约缺失，不冒充成功）。
     */
    public static JobResultInfo resolve(int exitCode, Optional<JobResultInfo> parsed) {
        if (parsed.isPresent() && exitCode == 0) {
            return parsed.get();
        }
        if (parsed.isPresent()) {
            JobResultInfo r = parsed.get();
            return new JobResultInfo(r.jobCode(), r.inputRecords(), r.outputRecords(),
                    r.rejectedRecords(), r.snapshotId(), r.attemptNo(), "FAILED",
                    "进程退出码非0(exit=" + exitCode + "): " + r.message(), r.elapsedMs(),
                    r.outputPartitions(), r.checks());
        }
        return new JobResultInfo("", 0, 0, 0, null, 0, "FAILED",
                "未找到 JobResult 结果行", 0, List.of(), List.of());
    }

    private static JobResultInfo toInfo(String line) {
        try {
            JsonNode n = MAPPER.readTree(line);
            return new JobResultInfo(
                    n.path("jobCode").asText(""),
                    n.path("inputRecords").asLong(0),
                    n.path("outputRecords").asLong(0),
                    n.path("rejectedRecords").asLong(0),
                    n.hasNonNull("snapshotId") ? n.get("snapshotId").asText() : null,
                    n.path("attemptNo").asInt(0),
                    n.path("status").asText("FAILED"),
                    n.path("message").asText(""),
                    n.path("elapsedMs").asLong(0),
                    toPartitions(n.path("outputPartitions")),
                    toChecks(n.path("checks")));
        } catch (Exception e) {
            // 理论上不会到这里（parseLog 已用同一 parser 校验过），防御性兜底
            return new JobResultInfo("", 0, 0, 0, null, 0, "FAILED",
                    "结果行解析失败: " + e.getMessage(), 0, List.of(), List.of());
        }
    }

    /** 输出分区数组 → 强类型列表（元素缺 table/rowCount 时按原值透传，不臆造） */
    private static List<OutputPartitionInfo> toPartitions(JsonNode array) {
        List<OutputPartitionInfo> partitions = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return partitions;
        }
        for (JsonNode p : array) {
            partitions.add(new OutputPartitionInfo(
                    p.path("table").asText(""),
                    p.path("dt").asText(""),
                    p.hasNonNull("snapshotId") ? p.get("snapshotId").asText() : null,
                    p.path("rowCount").asLong(0),
                    p.hasNonNull("path") ? p.get("path").asText() : null));
        }
        return partitions;
    }

    /** 质量检查数组 → 强类型列表（缺 severity 视为 BLOCKING：契约缺失不得当作放行） */
    private static List<CheckInfo> toChecks(JsonNode array) {
        List<CheckInfo> checks = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return checks;
        }
        for (JsonNode c : array) {
            checks.add(new CheckInfo(
                    c.path("ruleCode").asText(""),
                    c.path("layer").asText(""),
                    c.path("targetTable").asText(""),
                    c.path("checkCount").asLong(0),
                    c.path("errorCount").asLong(0),
                    c.path("threshold").asText(""),
                    c.path("severity").asText("BLOCKING"),
                    c.path("passed").asBoolean(false),
                    c.path("detail").asText("")));
        }
        return checks;
    }
}