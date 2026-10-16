package com.graduation.analytics.mapping;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一次映射的完整结果（设计 §7.3 规则 8「原始/标准化隔离」）。
 *
 * <p>{@code raw} 保留**全部**输入（含未映射字段与 @keep 扩展），是隔离区落库与对账的依据；
 * {@code canonical} 与之分离，并有独立 checksum。</p>
 *
 * <p>{@code canonical} 为 {@code null} 即隔离（{@link #quarantined()}）：只要有 1 条违例（非告警）就不产出
 * canonical，防止「半成品」进入下游。{@code ingest_time} 恒定不写入 canonical，而是登记在
 * {@link #pendingPlatformFields()}（值是源侧候选，仅作证据，见 S2-02）。</p>
 */
public record MappingOutcome(
        JsonNode raw,
        JsonNode canonical,
        List<MappingIssue> violations,
        List<MappingIssue> warnings,
        MappingStats stats,
        List<String> pendingPlatformFields,
        String ingestTimeCandidate,
        String rawChecksum,
        String canonicalChecksum) {

    /** canonical 由平台 ingestion 层生成、Mapper 不写的字段。 */
    public static final String PLATFORM_INGEST_TIME = "ingest_time";

    public boolean quarantined() {
        return canonical == null;
    }
}
