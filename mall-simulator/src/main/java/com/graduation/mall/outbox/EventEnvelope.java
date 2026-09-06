package com.graduation.mall.outbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一事件信封（§5.2.4）：event_id/event_type/event_time/ingest_time/
 * source_system/schema_version/trace_id/payload。
 * toJson 固定字段顺序，保证落盘 JSON 列序稳定；金额一律字符串。
 * JSON 属性名与 Java 字段名的 snake_case ↦ camelCase 映射显式声明，
 * 保证读写两侧字段绑定一致（黄金数据对账依赖）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("event_time") String eventTime,
        @JsonProperty("ingest_time") String ingestTime,
        @JsonProperty("source_system") String sourceSystem,
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("trace_id") String traceId,
        Map<String, Object> payload) {

    /**
     * 序列化为固定字段序的 JSON 单行。
     *
     * @throws IllegalStateException 序列化失败（payload 含不可序列化对象）
     */
    public String toJson(ObjectMapper mapper) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("event_id", eventId);
        ordered.put("event_type", eventType);
        ordered.put("event_time", eventTime);
        ordered.put("ingest_time", ingestTime);
        ordered.put("source_system", sourceSystem);
        ordered.put("schema_version", schemaVersion);
        ordered.put("trace_id", traceId);
        ordered.put("payload", payload);
        try {
            return mapper.writeValueAsString(ordered);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件信封序列化失败: " + e.getMessage(), e);
        }
    }

    public static EventEnvelope fromJson(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("事件信封解析失败: " + e.getMessage(), e);
        }
    }
}