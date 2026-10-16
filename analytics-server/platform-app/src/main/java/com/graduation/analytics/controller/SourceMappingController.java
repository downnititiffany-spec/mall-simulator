package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.source.SourceMappingDryRunService;
import com.graduation.analytics.source.dto.MappingDryRunReportView;
import com.graduation.analytics.source.dto.MappingDryRunReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 来源字段映射 Dry-Run 试运行端点（WP-2A-2 / Design V3.0 §7.4）。
 *
 * <p>端点规格：
 * <ul>
 *   <li>{@code POST /api/v1/sources/{sourceId}/mappings/dry-run}：针对受控样本试运行字段映射，输出转换报告与预览，零正式写入；</li>
 *   <li>{@code GET /api/v1/sources/{sourceId}/mappings/dry-runs/{reportId}}：查询已存储的 Dry-Run 试运行报告。</li>
 * </ul>
 * </p>
 *
 * <p>权限与响应模式完全遵循 {@link SourceRegistryController}：
 * 使用 {@link RequiresPermission}（{@link PermissionCode#RUNTIME_MANAGE}），响应统一采用 {@link ApiResponse} 信封包装，
 * 错误由 {@link com.graduation.analytics.common.GlobalExceptionHandler} 统一分状态码收口（400/403/404/409）。
 * 激活端点 {@code mappings/{version}/activate} 不在本包交付范围内。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sources/{sourceId}/mappings")
@RequiresPermission(PermissionCode.RUNTIME_MANAGE)
public class SourceMappingController {

    private final SourceMappingDryRunService dryRunService;
    private final MappingDryRunPermissionChecker permissionChecker;

    public SourceMappingController(SourceMappingDryRunService dryRunService,
                                   MappingDryRunPermissionChecker permissionChecker) {
        this.dryRunService = dryRunService;
        this.permissionChecker = permissionChecker;
    }

    /**
     * POST /api/v1/sources/{sourceId}/mappings/dry-run
     *
     * <p>加载指定源画像与受控脱敏样本，执行 Dry-Run 试运行，保存报告至内存存储并返回。</p>
     */
    @PostMapping("/dry-run")
    public ApiResponse<MappingDryRunReportView> dryRun(
            @PathVariable("sourceId") Long sourceId,
            @RequestBody(required = false) MappingDryRunReq req) {
        permissionChecker.checkPermission(PermissionCode.RUNTIME_MANAGE);
        MappingDryRunReportView view = dryRunService.runDryRun(sourceId, req);
        return ApiResponse.ok(view, TraceContext.create().traceId());
    }

    /**
     * GET /api/v1/sources/{sourceId}/mappings/dry-runs/{reportId}
     *
     * <p>查询内存存储中的 Dry-Run 报告；未知 reportId 或跨源查询返回 404。</p>
     */
    @GetMapping("/dry-runs/{reportId}")
    public ApiResponse<MappingDryRunReportView> getDryRunReport(
            @PathVariable("sourceId") Long sourceId,
            @PathVariable("reportId") String reportId) {
        permissionChecker.checkPermission(PermissionCode.RUNTIME_MANAGE);
        MappingDryRunReportView view = dryRunService.getDryRunReport(sourceId, reportId);
        return ApiResponse.ok(view, TraceContext.create().traceId());
    }
}
