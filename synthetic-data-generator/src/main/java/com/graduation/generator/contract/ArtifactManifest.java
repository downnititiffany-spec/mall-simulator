package com.graduation.generator.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * 制品清单（契约 {@code generation-artifact-manifest.v1.schema.json}，9 个字段全部必填、
 * {@code additionalProperties: false}）。
 *
 * <p>这是 {@code CANONICAL_EVENT_FILE} 模式"必须显示 {@code synthetic=true}"（V2.1 §3.3 B）的载体：
 * 事件信封只有 8 个固定字段，装不下这个标记；契约文件也把清单指定为落点（{@code x-synthetic-marker}）。</p>
 *
 * <p>{@code synthetic} 由工厂方法置 <b>true</b>；构造器对 {@code false} 直接抛异常——
 * 宁可构建失败，也不产出"没标合成数据"的清单。</p>
 *
 * <p><b>未冻结项</b>：契约 {@code additionalProperties:false} 与 V2.1 §4.3 L149"在 manifest 中记录期望隔离数"
 * 相互冲突（契约文件已登记）。因此期望隔离数不写在本清单里，而是写入运行级报告（后续切片）。</p>
 */
@JsonPropertyOrder({"run_id", "uri", "checksum", "bytes", "record_count",
        "min_event_time", "max_event_time", "schema_version", "synthetic"})
public record ArtifactManifest(
        @JsonProperty("run_id") String runId,
        @JsonProperty("uri") String uri,
        @JsonProperty("checksum") String checksum,
        @JsonProperty("bytes") long bytes,
        @JsonProperty("record_count") long recordCount,
        @JsonProperty("min_event_time") String minEventTime,
        @JsonProperty("max_event_time") String maxEventTime,
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("synthetic") boolean synthetic) {

    public ArtifactManifest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id 必填（契约清单 required）");
        }
        if (recordCount > 0) {
            ContractFormat.requireTime(minEventTime);
            ContractFormat.requireTime(maxEventTime);
        } else if (minEventTime != null || maxEventTime != null) {
            throw new IllegalArgumentException("空制品（record_count=0）不得带 min/max event_time："
                    + "否则无法分辨『真的没有事件』与『没算出来』");
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schema_version 必填（契约清单 required）");
        }
        if (bytes < 0 || recordCount < 0) {
            throw new IllegalArgumentException("bytes/record_count 不得为负（契约 minimum: 0）");
        }
        if (!synthetic) {
            throw new IllegalArgumentException(
                    "synthetic 必须为 true（V2.1 §3.3 B + 契约清单 const true）：文件模式不得产出未标注合成数据的清单");
        }
    }

    /** 唯一的构造入口：synthetic 恒为 true */
    public static ArtifactManifest of(String runId, String uri, String checksum, long bytes, long recordCount,
                                      String minEventTime, String maxEventTime, String schemaVersion) {
        return new ArtifactManifest(runId, uri, checksum, bytes, recordCount,
                minEventTime, maxEventTime, schemaVersion, true);
    }

    /** 写入 {@code <jsonl 同名>.manifest.json}，返回清单文件路径 */
    public Path write(Path target, ObjectMapper mapper) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), this);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("清单写入失败：" + target, e);
        }
    }
}
