package com.graduation.analytics.mapper;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Pure Mapper 输出：Canonical（成功）或 Quarantined（一组违规，Rule-8）。
 *
 * <p>原始完整 JSON 另存于 rawJson，不管哪种结果都保留（Rule-3/Rule-8）。</p>
 */
public sealed interface MapperOutcome permits MapperOutcome.Canonical, MapperOutcome.Quarantined {

    /**
     * 转换成功；canonicalRecord 是映射后的规范化事件（不含敏感值）。
     * originalJson 是原始输入（Rule-8：raw记录全部输入）。
     * unresolvedEnumWarnings 是 ENUM_UNRESOLVED 警告（不阻断，只计数报告，Rule-12）。
     */
    record Canonical(
            ObjectNode canonicalRecord,
            String originalJson,
            List<MapperViolation> unresolvedEnumWarnings
    ) implements MapperOutcome {
        public Canonical(ObjectNode canonicalRecord, String originalJson) {
            this(canonicalRecord, originalJson, List.of());
        }
    }

    /**
     * 隔离；violations 包含至少一个非警告性违规。
     * originalJson 是原始输入。
     */
    record Quarantined(
            List<MapperViolation> violations,
            String originalJson
    ) implements MapperOutcome {
    }
}
