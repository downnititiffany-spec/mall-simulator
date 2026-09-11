package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.pipeline.PipelineRecoveryService;
import com.graduation.analytics.pipeline.PipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * R6-14（§23.1）流水线恢复管理接口：resume / mark-failed / retry-from-stage + 启动对账报告。
 * 每个动作都必须携带 operator 与 reason（审计）；R8-3 起细粒度权限由
 * {@link RequiresPermission} 声明、AuthInterceptor 查矩阵执行（恢复动作 = pipeline:run）。
 */
@RestController
@RequestMapping("/api/v1/admin/pipeline-runs")
@RequiredArgsConstructor
public class PipelineAdminController {

    private final PipelineService pipelineService;
    private final PipelineRecoveryService recoveryService;

    @PostMapping("/{id}/resume")
    @RequiresPermission(PermissionCode.PIPELINE_RUN)
    public ApiResponse<PipelineService.RunResult> resume(@PathVariable Long id,
                                                        @RequestParam String operator,
                                                        @RequestParam String reason) {
        TraceContext trace = TraceContext.create();
        return ApiResponse.ok(pipelineService.resume(id, operator, reason, trace.traceId()), trace.traceId());
    }

    @PostMapping("/{id}/mark-failed")
    @RequiresPermission(PermissionCode.PIPELINE_RUN)
    public ApiResponse<PipelineService.RunResult> markFailed(@PathVariable Long id,
                                                            @RequestParam String operator,
                                                            @RequestParam String reason) {
        TraceContext trace = TraceContext.create();
        return ApiResponse.ok(pipelineService.markFailed(id, operator, reason), trace.traceId());
    }

    @PostMapping("/{id}/retry-from-stage")
    @RequiresPermission(PermissionCode.PIPELINE_RUN)
    public ApiResponse<PipelineService.RunResult> retryFromStage(@PathVariable Long id,
                                                                 @RequestParam String stage,
                                                                 @RequestParam String operator,
                                                                 @RequestParam String reason) {
        TraceContext trace = TraceContext.create();
        return ApiResponse.ok(pipelineService.retryFromStage(id, stage, operator, reason, trace.traceId()),
                trace.traceId());
    }

    /** 启动对账报告（本次启动处理了哪些 run） */
    @GetMapping("/recovery-report")
    @RequiresPermission(PermissionCode.OPS_LOG_VIEW)
    public ApiResponse<PipelineRecoveryService.Report> recoveryReport() {
        return ApiResponse.ok(recoveryService.lastReport(), TraceContext.create().traceId());
    }
}
