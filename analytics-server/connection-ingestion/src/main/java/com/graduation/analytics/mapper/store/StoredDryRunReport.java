package com.graduation.analytics.mapper.store;

import com.graduation.analytics.mapper.DryRunReport;

import java.time.LocalDateTime;

/**
 * 内存中持久化的 Dry-Run 报告实体（WP-2A-2）。
 */
public record StoredDryRunReport(
        String reportId,
        Long sourceId,
        DryRunReport report,
        LocalDateTime createdAt
) {
}
