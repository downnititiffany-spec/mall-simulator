package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.pipeline.PipelineRecoveryService;
import com.graduation.analytics.pipeline.PipelineService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public record RecalculateReq(
            @NotNull Long runtimeProfileId,
            @NotNull Long batchId,
            @NotBlank String operator,
            @NotBlank String reason) {
    }

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

    /**
     * G31-11（D-049e）：显式重算入口——唯一能对**已消费**批次再次发布的通道，必须带理由。
     * 语义与普通调度/重试不同：它豁免 M3 no-op 门、从插入 run 起就钉住目标批次
     * （input_batch_id 预置），目标批次选不到时 fail-closed（RUN_RECALC_BATCH_UNAVAILABLE）。
     * 理由走 body 而非 query param：理由是审计文本（可含空格/换行），且 operator/reason
     * 与业务参数同属一个语义整体。缺理由/参数缺失/无既成发布 → PARAM_INVALID(400)。
     */
    @PostMapping("/recalculate")
    @RequiresPermission(PermissionCode.PIPELINE_RUN)
    public ApiResponse<PipelineService.RunResult> recalculate(@RequestBody RecalculateReq req) {
        TraceContext trace = TraceContext.create();
        return ApiResponse.ok(pipelineService.recalculate(req.runtimeProfileId(), req.batchId(),
                req.operator(), req.reason(), trace.traceId()), trace.traceId());
    }

    /** 启动对账报告（本次启动处理了哪些 run） */
    @GetMapping("/recovery-report")
    @RequiresPermission(PermissionCode.OPS_LOG_VIEW)
    public ApiResponse<PipelineRecoveryService.Report> recoveryReport() {
        return ApiResponse.ok(recoveryService.lastReport(), TraceContext.create().traceId());
    }
}
