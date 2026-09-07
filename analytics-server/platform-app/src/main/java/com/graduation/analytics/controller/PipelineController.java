package com.graduation.analytics.controller;

import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.pipeline.PipelineService;
import com.graduation.analytics.pipeline.entity.PipelineRun;
import com.graduation.analytics.pipeline.mapper.PipelineRunMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 流水线接口（§24.3 子集）：创建（幂等键头）、查询、重试。
 */
@RestController
@RequestMapping("/api/v1/pipeline-runs")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;
    private final PipelineRunMapper runMapper;

    public record CreateRunReq(
            @NotNull Long runtimeProfileId,
            @NotBlank String pipelineCode,
            @NotNull LocalDateTime businessTime,
            String sourceDataVersion) {
    }

    @PostMapping
    public ApiResponse<PipelineService.RunResult> create(@RequestBody CreateRunReq req,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        TraceContext trace = TraceContext.create();
        PipelineService.RunResult result = pipelineService.run(req.runtimeProfileId(), req.pipelineCode(),
                req.businessTime(), req.sourceDataVersion(), idempotencyKey, trace.traceId());
        return ApiResponse.ok(result, trace.traceId());
    }

    @GetMapping("/{id}")
    public ApiResponse<PipelineService.RunResult> get(@PathVariable Long id) {
        return ApiResponse.ok(pipelineService.get(id), TraceContext.create().traceId());
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<PipelineService.RunResult> retry(@PathVariable Long id) {
        TraceContext trace = TraceContext.create();
        return ApiResponse.ok(pipelineService.retry(id, trace.traceId()), trace.traceId());
    }

    @GetMapping
    public ApiResponse<List<PipelineRun>> list(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(runMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PipelineRun>()
                .orderByDesc(PipelineRun::getId)
                .last("LIMIT " + Math.max(1, Math.min(100, limit)))), TraceContext.create().traceId());
    }
}