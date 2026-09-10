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

    /** 作业结果（snapshotId 可能缺失；outputPartitions 可能为空）。 */
    public record JobResultInfo(String jobCode, long inputRecords, long outputRecords,
                                long rejectedRecords, String snapshotId, int attemptNo,
                                String status, String message, long elapsedMs,
                                List<OutputPartitionInfo> outputPartitions) {
        public boolean success() {
            return "SUCCESS".equals(status);
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
                    r.outputPartitions());
        }
        return new JobResultInfo("", 0, 0, 0, null, 0, "FAILED",
                "未找到 JobResult 结果行", 0, List.of());
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
                    toPartitions(n.path("outputPartitions")));
        } catch (Exception e) {
            // 理论上不会到这里（parseLog 已用同一 parser 校验过），防御性兜底
            return new JobResultInfo("", 0, 0, 0, null, 0, "FAILED",
                    "结果行解析失败: " + e.getMessage(), 0, List.of());
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
}