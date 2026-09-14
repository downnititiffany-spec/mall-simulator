package com.graduation.generator.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;

/**
 * 规范事件信封（契约 {@code canonical-event.v1.schema.json} 顶层 8 个字段，全部必填）。
 *
 * <p>字段顺序由 {@link JsonPropertyOrder} 固定：JSONL 必须是**逐字节可复现**的，否则
 * "相同 plan_version + seed + time_window 可复现"（V2.1 §4.2）就无法用 checksum 对账。</p>
 *
 * <p>信封刻意不含 {@code synthetic} 标记：契约的信封只有这 8 个字段，标记落在制品清单上
 * （见 {@link ArtifactManifest} 与 {@code ContractFormat.SOURCE_SYSTEM} 的未冻结项说明）。</p>
 *
 * @param eventId       事件 ID（契约未规定生成规则；生成器侧由调用方提供）
 * @param eventType     {@link EventTypes} 的 12 类之一
 * @param eventTime     业务时间（见 {@link ContractFormat#time(java.time.Instant)}）
 * @param ingestTime    采集时间（文件模式下即产出时间；链路延迟 = ingest_time − event_time）
 * @param sourceSystem  契约 const（见 {@code ContractFormat.SOURCE_SYSTEM}）
 * @param schemaVersion 契约版本（当前 1.0）
 * @param traceId       链路追踪 ID
 * @param payload       按事件类型的载荷（键集由契约的 payload 定义约束）
 */
@JsonPropertyOrder({"event_id", "event_type", "event_time", "ingest_time",
        "source_system", "schema_version", "trace_id", "payload"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanonicalEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("event_time") String eventTime,
        @JsonProperty("ingest_time") String ingestTime,
        @JsonProperty("source_system") String sourceSystem,
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("payload") Map<String, Object> payload) {

    public CanonicalEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("event_id 必填（契约信封 required）");
        }
        if (eventType == null || !EventTypes.ALL.contains(eventType)) {
            throw new IllegalArgumentException("event_type 必须是契约 12 类之一，实际=" + eventType);
        }
        ContractFormat.requireTime(eventTime);
        ContractFormat.requireTime(ingestTime);
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("trace_id 必填（契约信封 required）");
        }
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("payload 必填且非空（契约信封 required）");
        }
    }
}
