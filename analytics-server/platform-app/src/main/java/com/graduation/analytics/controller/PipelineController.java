package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.common.PlatformBizException;
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
import java.util.Locale;

/**
 * 流水线接口（§24.3 子集）：创建（幂等键头）、查询、重试。
 *
 * <p>R8-3 §3.2：启动/重试 = pipeline:run（admin/data_dev/operator），运行记录查看 = ops:log:view
 * （analyst 只能看看板，不能触发流水线）。</p>
 */
@RestController
@RequestMapping("/api/v1/pipeline-runs")
@RequiredArgsConstructor
public class PipelineController {

    private static final int MAX_PAGE_SIZE = 100;

    public record PipelineRunPage(List<PipelineRun> items, long page, int size, long total, long totalPages,
                                  String sort) {
    }

    private final PipelineService pipelineService;
    private final PipelineRunMapper runMapper;

    public record CreateRunReq(
            @NotNull Long runtimeProfileId,
            @NotBlank String pipelineCode,
            @NotNull LocalDateTime businessTime,
            String sourceDataVersion) {
    }

    @PostMapping
    @RequiresPermission(PermissionCode.PIPELINE_RUN)
    public ApiResponse<PipelineService.RunResult> create(@RequestBody CreateRunReq req,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        TraceContext trace = TraceContext.create();
        PipelineService.RunResult result = pipelineService.run(req.runtimeProfileId(), req.pipelineCode(),
                req.businessTime(), req.sourceDataVersion(), idempotencyKey, trace.traceId());
        return ApiResponse.ok(result, trace.traceId());
    }

    @GetMapping("/{id}")
    @RequiresPermission(PermissionCode.OPS_LOG_VIEW)
    public ApiResponse<PipelineService.RunResult> get(@PathVariable Long id) {
        return ApiResponse.ok(pipelineService.get(id), TraceContext.create().traceId());
    }

    @PostMapping("/{id}/retry")
    @RequiresPermission(PermissionCode.PIPELINE_RUN)
    public ApiResponse<PipelineService.RunResult> retry(@PathVariable Long id) {
        TraceContext trace = TraceContext.create();
        return ApiResponse.ok(pipelineService.retry(id, trace.traceId()), trace.traceId());
    }

    @GetMapping
    @RequiresPermission(PermissionCode.OPS_LOG_VIEW)
    public ApiResponse<List<PipelineRun>> list(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(runMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PipelineRun>()
                .orderByDesc(PipelineRun::getId)
                .last("LIMIT " + Math.max(1, Math.min(100, limit)))), TraceContext.create().traceId());
    }

    /** 新增分页列表；旧 GET /pipeline-runs?limit=... 保持原响应，供旧客户端兼容。 */
    @GetMapping("/page")
    @RequiresPermission(PermissionCode.OPS_LOG_VIEW)
    public ApiResponse<PipelineRunPage> page(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") long page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "id,desc") String sort) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "page 必须大于等于 1，size 必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
        String[] parts = sort == null ? new String[0] : sort.trim().toLowerCase(Locale.ROOT).split(",", -1);
        if (parts.length < 1 || parts.length > 2 || parts[0].isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sort 格式应为 字段[,asc|desc]");
        }
        String field = parts[0];
        String direction = parts.length == 1 ? "asc" : parts[1];
        if (!List.of("id", "createdat", "startedat", "finishedat", "businesstime", "status").contains(field)
                || !("asc".equals(direction) || "desc".equals(direction))) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sort 字段或方向不受支持");
        }
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PipelineRun>();
        switch (field) {
            case "createdat" -> { if ("asc".equals(direction)) wrapper.orderByAsc(PipelineRun::getCreatedAt); else wrapper.orderByDesc(PipelineRun::getCreatedAt); }
            case "startedat" -> { if ("asc".equals(direction)) wrapper.orderByAsc(PipelineRun::getStartedAt); else wrapper.orderByDesc(PipelineRun::getStartedAt); }
            case "finishedat" -> { if ("asc".equals(direction)) wrapper.orderByAsc(PipelineRun::getFinishedAt); else wrapper.orderByDesc(PipelineRun::getFinishedAt); }
            case "businesstime" -> { if ("asc".equals(direction)) wrapper.orderByAsc(PipelineRun::getBusinessTime); else wrapper.orderByDesc(PipelineRun::getBusinessTime); }
            case "status" -> { if ("asc".equals(direction)) wrapper.orderByAsc(PipelineRun::getStatus); else wrapper.orderByDesc(PipelineRun::getStatus); }
            default -> { if ("asc".equals(direction)) wrapper.orderByAsc(PipelineRun::getId); else wrapper.orderByDesc(PipelineRun::getId); }
        }
        // Tie-break equal sort values deterministically so adjacent pages do not reshuffle.
        wrapper.orderByDesc(PipelineRun::getId);
        long total = runMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>());
        long totalPages = Math.max(1, (total + size - 1) / size);
        if (page > totalPages) page = totalPages;
        long offset = Math.multiplyExact(page - 1, (long) size);
        List<PipelineRun> items = runMapper.selectList(wrapper.last("LIMIT " + size + " OFFSET " + offset));
        PipelineRunPage result = new PipelineRunPage(items, page, size, total, totalPages,
                field + "," + direction);
        return ApiResponse.ok(result, TraceContext.create().traceId());
    }
}
