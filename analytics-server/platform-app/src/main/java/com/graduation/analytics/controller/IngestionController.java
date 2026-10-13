package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.ingestion.IngestionService;
import com.graduation.analytics.ingestion.IngestionBatchQueryService;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ratelimit.RateLimited;
import com.graduation.analytics.common.TraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 采集控制接口（§5.2.1）：状态查看、手动触发一轮采集、批次列表。
 *
 * <p>R8-3 §3.2：手动触发采集 = pipeline:run（admin/data_dev/operator）；状态与批次查看 = ops:log:view。</p>
 */
@RestController
@RequestMapping("/api/v1/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;
    private final IngestionBatchQueryService batchQueryService;

    @GetMapping("/status")
    @RequiresPermission(PermissionCode.OPS_LOG_VIEW)
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(ingestionService.status(), TraceContext.create().traceId());
    }

    @PostMapping("/runs")
    @RequiresPermission(PermissionCode.PIPELINE_RUN)
    @RateLimited(scope = "ingestion-trigger", requests = 20)
    public ApiResponse<IngestionService.RunResult> run() {
        TraceContext trace = TraceContext.create();
        return ApiResponse.ok(ingestionService.runOne(trace), trace.traceId());
    }

    @GetMapping("/batches")
    @RequiresPermission(PermissionCode.OPS_LOG_VIEW)
    public ApiResponse<List<IngestionBatch>> batches(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(ingestionService.recentBatches(limit), TraceContext.create().traceId());
    }

    /** 历史批次服务端分页；旧 /batches?limit= 接口继续返回最近数组，保持兼容。 */
    @GetMapping("/batches/page")
    @RequiresPermission(PermissionCode.OPS_LOG_VIEW)
    public ApiResponse<IngestionBatchQueryService.BatchPage> batchPage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id,desc") String sort) {
        return ApiResponse.ok(batchQueryService.listPage(page, size, sort), TraceContext.create().traceId());
    }
}
