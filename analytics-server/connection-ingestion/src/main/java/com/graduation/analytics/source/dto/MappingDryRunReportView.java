package com.graduation.analytics.source.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graduation.analytics.mapper.DryRunReport;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 映射 Dry-Run 报告视图 DTO（WP-2A-2 / Design V3.0 §7.4）。
 *
 * <p>包含 DryRunReport 的全部度量、覆盖率、错误统计与脱敏预览，
 * 以及报告标识 {@code reportId} 与源标识 {@code sourceId}。</p>
 */
public record MappingDryRunReportView(
        String reportId,
        Long sourceId,
        String profileVersion,
        String profileContentHash,
        long totalRows,
        long canonicalRows,
        long quarantinedRows,
        Double requiredCoverage,
        Double enumCoverage,
        Map<String, Long> reasonCounts,
        Map<String, Long> unresolvedEnumCounts,
        long validatorAccepted,
        long strictContractAccepted,
        long itemsPassthroughFlagged,
        List<ObjectNode> preview,
        String previewSensitiveNote,
        LocalDateTime createdAt
) {

    public static MappingDryRunReportView from(String reportId, Long sourceId, DryRunReport report, LocalDateTime createdAt) {
        return new MappingDryRunReportView(
                reportId,
                sourceId,
                report.profileVersion(),
                report.profileContentHash(),
                report.totalRows(),
                report.canonicalRows(),
                report.quarantinedRows(),
                report.requiredCoverage(),
                report.enumCoverage(),
                report.reasonCounts(),
                report.unresolvedEnumCounts(),
                report.validatorAccepted(),
                report.strictContractAccepted(),
                report.itemsPassthroughFlagged(),
                report.preview(),
                report.previewSensitiveNote(),
                createdAt
        );
    }
}
