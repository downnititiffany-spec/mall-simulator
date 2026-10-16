package com.graduation.analytics.mapping.dryrun;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 转换预览的一条：样本行号 + 该行产出的 canonical（设计 §7.4「输出转换预览」）。
 *
 * <p>只放进**通过**的行（隔离行没有 canonical）；条数由
 * {@link MappingDryRunService#PREVIEW_MAX} 封顶，避免报告体积随样本线性增长——
 * 报告同时给出全量 {@code acceptedCanonicalChecksums} 与 {@code processedCount}，
 * 需要全量内容时按 checksum 对账，而不是靠预览条数推断。</p>
 */
public record MappingDryRunPreview(int lineNo, JsonNode canonical) {

    public MappingDryRunPreview {
        if (lineNo < 1) {
            throw new IllegalArgumentException("lineNo 从 1 起: " + lineNo);
        }
        Objects.requireNonNull(canonical, "canonical");
    }
}
