package com.graduation.generator.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 一次运行的运行报告（{@code <产物根>/<runId>/run-report.json}）。
 *
 * <p><b>为什么需要它</b>：V2.1 §4.3 要求"失败必须记入运行报告"、"在 manifest 中记录期望隔离数"。
 * 但制品清单的契约（{@code generation-artifact-manifest.v1.schema.json}）只有 9 个固定字段、
 * 没有承载"期望隔离数/说明"的位置，往清单里加键就是违约。因此把这些内容落在**同目录的运行报告**里，
 * 与清单并列、可人工对账，且不改契约。这一点已登记为分歧 D-016（待 B-06 裁决后决定是否升版契约）。</p>
 *
 * <p>本文件是"如实记录"的载体：`notes` 里放实现方自己声明的缺口（例如某维度未实现），
 * `expectedQuarantineCounts` 放注入的异常样本按类型的期望条数，供采集侧实际隔离数对账。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunReport(
        @JsonProperty("run_id") String runId,
        @JsonProperty("plan_id") String planId,
        @JsonProperty("plan_version") Integer planVersion,
        @JsonProperty("mode") String mode,
        @JsonProperty("scenario") String scenario,
        @JsonProperty("seed") Long seed,
        @JsonProperty("start_time") String startTime,
        @JsonProperty("end_time") String endTime,
        @JsonProperty("event_count") Long eventCount,
        @JsonProperty("status") String status,
        @JsonProperty("success_count") Long successCount,
        @JsonProperty("failed_count") Long failedCount,
        @JsonProperty("checksum") String checksum,
        @JsonProperty("expected_quarantine_counts") Map<String, Long> expectedQuarantineCounts,
        @JsonProperty("dirty_sample_types") List<String> dirtySampleTypes,
        @JsonProperty("event_stats") List<StatLine> eventStats,
        @JsonProperty("artifacts") List<ArtifactLine> artifacts,
        @JsonProperty("notes") List<String> notes,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage) {

    public static final String FILE_NAME = "run-report.json";

    /** 单个制品的对账行（比 API 多出内部 {@code kind}：契约的 GenerationArtifact 没有该字段，故只写进报告） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArtifactLine(
            @JsonProperty("uri") String uri,
            @JsonProperty("kind") String kind,
            @JsonProperty("checksum") String checksum,
            @JsonProperty("bytes") Long bytes,
            @JsonProperty("record_count") Long recordCount,
            @JsonProperty("min_event_time") String minEventTime,
            @JsonProperty("max_event_time") String maxEventTime) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StatLine(
            @JsonProperty("event_type") String eventType,
            @JsonProperty("count") Long count,
            @JsonProperty("amount") BigDecimal amount) {
    }

    /** 写入 {@code runDir/run-report.json}；返回落盘路径，便于日志与测试核实 */
    public Path write(Path runDir, ObjectMapper mapper) {
        Path file = runDir.resolve(FILE_NAME);
        try {
            Files.createDirectories(runDir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("运行报告写入失败：" + file, e);
        }
    }
}
