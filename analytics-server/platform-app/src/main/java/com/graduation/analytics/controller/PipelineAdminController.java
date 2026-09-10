package com.graduation.analytics.controller;

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
 * 每个动作都必须携带 operator 与 reason（审计），鉴权沿用全局拦截器（细粒度权限属 R8）。
 */
@RestController
@RequestMapping("/api/v1/admin/pipeline-runs")
@RequiredArgsConstructor
public class PipelineAdminController {

    private final PipelineService pipelineService;
    private final PipelineRecoveryService recoveryService;

    @PostMapping("/{id}/resume")
    public ApiResponse<PipelineService.RunResult> resume(@PathVariable Long id,
                                                        @RequestParam String operator,
                                                        @RequestParam String reason) {
        TraceContext trace = TraceContext.create();
        return ApiResponse.ok(pipelineService.resume(id, operator, reason, trace.traceId()), trace.traceId());
    }

    @PostMapping("/{id}/mark-failed")
    public ApiResponse<PipelineService.RunResult> markFailed(@PathVariable Long id,
                                                            @RequestParam String operator,
                                                            @RequestParam String reason) {
        TraceContext trace = TraceContext.create();
        return ApiResponse.ok(pipelineService.markFailed(id, operator, reason), trace.traceId());
    }

    @PostMapping("/{id}/retry-from-stage")
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
    public ApiResponse<PipelineRecoveryService.Report> recoveryReport() {
        return ApiResponse.ok(recoveryService.lastReport(), TraceContext.create().traceId());
    }
}
