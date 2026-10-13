package com.graduation.analytics.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.report.ReportInstanceService;
import com.graduation.analytics.report.ReportInstanceService.CreateCommand;
import com.graduation.analytics.report.ReportInstanceService.Page;
import com.graduation.analytics.report.entity.ReportInstance;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportInstanceController {
    private final ReportInstanceService service;

    public record CreateReq(@NotBlank String templateCode, String templateVersion, String snapshotId,
                            LocalDate businessDate, Boolean includeAiNarrative,
                            LocalDate fromDate, LocalDate toDate, Long decisionId) {
    }

    public record ReportSummary(Long id, String templateCode, String templateVersion, Long sourceId,
                                String snapshotId, String definitionVersion, LocalDate fromDate,
                                LocalDate toDate, String promptVersion, String creatorId, String status,
                                String artifactChecksum, String stableErrorCode,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {
        static ReportSummary from(ReportInstance report) {
            return new ReportSummary(report.getId(), report.getTemplateCode(), report.getTemplateVersion(),
                    report.getSourceId(), report.getSnapshotId(), report.getDefinitionVersion(),
                    report.getFromDate(), report.getToDate(), report.getPromptVersion(), report.getCreatorId(),
                    report.getStatus(), report.getArtifactChecksum(),
                    report.getStableErrorCode(), report.getCreatedAt(), report.getUpdatedAt());
        }
    }

    public record ReportPage(List<ReportSummary> items, long page, int size, long total,
                             long totalPages, String sort) {
    }

    public record ReportDetail(ReportSummary report, JsonNode summary) {
    }

    @PostMapping
    @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
    public ApiResponse<ReportSummary> create(@RequestBody CreateReq req) {
        String owner = CallerContext.requireUserId();
        ReportInstance report = service.create(new CreateCommand(req == null ? null : req.templateCode(),
                req == null ? null : req.templateVersion(), req == null ? null : req.snapshotId(),
                req == null ? null : req.businessDate(), req == null ? null : req.includeAiNarrative(),
                req == null ? null : req.fromDate(), req == null ? null : req.toDate(),
                req == null ? null : req.decisionId()), owner);
        return ApiResponse.ok(ReportSummary.from(report), TraceContext.create().traceId());
    }

    @GetMapping("/page")
    @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
    public ApiResponse<ReportPage> page(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(defaultValue = "createdAt,desc") String sort) {
        SortOrder order = parseSort(sort);
        Page result = service.page(CallerContext.requireUserId(), page, size, order.field(), order.ascending());
        return ApiResponse.ok(new ReportPage(result.items().stream().map(ReportSummary::from).toList(),
                result.page(), result.size(), result.total(), result.totalPages(), result.sort()),
                TraceContext.create().traceId());
    }

    @GetMapping("/{id}")
    @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
    public ApiResponse<ReportDetail> detail(@PathVariable("id") long id) {
        ReportInstance report = ownReport(id);
        JsonNode summary = service.summary(report);
        return ApiResponse.ok(new ReportDetail(ReportSummary.from(report), summary),
                TraceContext.create().traceId());
    }

    @GetMapping("/{id}/artifact")
    @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
    public ApiResponse<JsonNode> artifact(@PathVariable("id") long id) {
        return ApiResponse.ok(service.artifact(ownReport(id)), TraceContext.create().traceId());
    }

    private ReportInstance ownReport(long id) {
        ReportInstance report = service.findOwn(id, CallerContext.requireUserId());
        if (report == null) {
            throw new com.graduation.analytics.common.PlatformBizException("REPORT_NOT_FOUND", "报告不存在");
        }
        return report;
    }

    private static SortOrder parseSort(String value) {
        String[] parts = value == null ? new String[0] : value.trim().split(",", -1);
        if (parts.length < 1 || parts.length > 2) {
            throw new com.graduation.analytics.common.PlatformBizException(
                    com.graduation.analytics.common.PlatformBizException.PARAM_INVALID, "sort 格式应为 字段[,asc|desc]");
        }
        String field = parts[0].trim();
        String direction = parts.length == 1 ? "asc" : parts[1].trim().toLowerCase(Locale.ROOT);
        if (!List.of("id", "createdAt", "templateCode", "status").contains(field)
                || !("asc".equals(direction) || "desc".equals(direction))) {
            throw new com.graduation.analytics.common.PlatformBizException(
                    com.graduation.analytics.common.PlatformBizException.PARAM_INVALID, "sort 字段或方向不受支持");
        }
        return new SortOrder(field, "asc".equals(direction));
    }

    private record SortOrder(String field, boolean ascending) {
    }
}
