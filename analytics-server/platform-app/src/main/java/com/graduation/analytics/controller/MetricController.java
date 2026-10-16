package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.PaginationMath;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import com.graduation.analytics.common.TraceContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 指标服务接口（§24.3 子集）：看板固定查询走 MetricStore；快照列表供管理页。
 *
 * R7-1：快照列表不再走 MyBatis mapper（analytics_metric 无 mapper 扫描），
 * 通过 MetricStore 从只读指标源查询，保留旧数组接口结构。
 */
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricController {

    private static final int MAX_QUALITY_PAGE_SIZE = 100;

    public record QualityPage(List<com.graduation.analytics.pipeline.entity.DataQualityResult> items,
                              long page, int size, long total, long totalPages, String sort) {
    }

    private final MetricStore metricStore;
    private final com.graduation.analytics.pipeline.mapper.DataQualityResultMapper qualityMapper;

    /** 经营指标（默认最新 ACTIVE 快照）；返回 metric_code→value+unit */
    @GetMapping("/overview")
    @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
    public ApiResponse<Object> overview(@RequestParam(required = false) String snapshotId) {
        TraceContext trace = TraceContext.create();
        List<MetricValue> values = metricStore.query(
                new MetricStore.MetricQuery(snapshotId, true));
        var data = values.stream().map(v -> Map.of(
                "metricCode", v.getMetricCode(),
                "value", v.getMetricValue(),
                "unit", v.getUnit(),
                "period", v.getPeriod(),
                "snapshotId", v.getSnapshotId(),
                "definitionVersion", v.getDefinitionVersion())).toList();
        return ApiResponse.ok(data, trace.traceId());
    }

    @GetMapping("/snapshots")
    @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
    public ApiResponse<List<MetricSnapshot>> snapshots(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(metricStore.listSnapshots(limit), TraceContext.create().traceId());
    }

    /** Paged snapshot history; the legacy limit endpoint keeps its array response for compatibility. */
    @GetMapping("/snapshots/page")
    @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
    public ApiResponse<MetricStore.SnapshotPage> snapshotsPage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id,desc") String sort) {
        return ApiResponse.ok(metricStore.listSnapshotsPage(page, size, sort), TraceContext.create().traceId());
    }

    /** 连通性检查：AuthInterceptor 白名单路径（无需权限码，供探活使用） */
    @GetMapping("/health")
    public ApiResponse<MetricStore.HealthResult> health() {
        return ApiResponse.ok(metricStore.healthCheck(), TraceContext.create().traceId());
    }

    /** 查询当前指标 Store 能力；运行时健康状态在本次调用中即时探测。 */
    @GetMapping("/capabilities")
    @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
    public ApiResponse<MetricStore.Capabilities> capabilities() {
        return ApiResponse.ok(metricStore.capabilities(), TraceContext.create().traceId());
    }

    /** 数据质量规则结果（§23.1 质量门；管理员运维页 → ops:log:view） */
    @GetMapping("/quality")
    @RequiresPermission(PermissionCode.OPS_LOG_VIEW)
    public ApiResponse<List<com.graduation.analytics.pipeline.entity.DataQualityResult>> quality(
            @RequestParam(required = false) Long runId,
            @RequestParam(defaultValue = "20") int limit) {
        var wrapper = new LambdaQueryWrapper<com.graduation.analytics.pipeline.entity.DataQualityResult>()
                .orderByDesc(com.graduation.analytics.pipeline.entity.DataQualityResult::getId)
                .last("LIMIT " + Math.max(1, Math.min(200, limit)));
        if (runId != null) {
            wrapper.eq(com.graduation.analytics.pipeline.entity.DataQualityResult::getRunId, runId);
        }
        return ApiResponse.ok(qualityMapper.selectList(wrapper), TraceContext.create().traceId());
    }

    /** Paged quality history; the bounded legacy limit route remains available to existing callers. */
    @GetMapping("/quality/page")
    @RequiresPermission(PermissionCode.OPS_LOG_VIEW)
    @Transactional(transactionManager = "metaTransactionManager", readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public ApiResponse<QualityPage> qualityPage(
            @RequestParam(required = false) Long runId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id,desc") String sort) {
        if (page < 1 || size < 1 || size > MAX_QUALITY_PAGE_SIZE) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "page 必须大于等于 1，size 必须在 1 到 " + MAX_QUALITY_PAGE_SIZE + " 之间");
        }
        String[] parts = sort == null ? new String[0] : sort.trim().toLowerCase(Locale.ROOT).split(",", -1);
        if (parts.length < 1 || parts.length > 2 || parts[0].isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sort 格式应为 字段[,asc|desc]");
        }
        String field = parts[0];
        String direction = parts.length == 1 ? "asc" : parts[1];
        if (!List.of("id", "createdat", "runid", "rulecode", "layer", "passed")
                .contains(field) || !("asc".equals(direction) || "desc".equals(direction))) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sort 字段或方向不受支持");
        }

        var countQuery = new LambdaQueryWrapper<com.graduation.analytics.pipeline.entity.DataQualityResult>();
        var query = new LambdaQueryWrapper<com.graduation.analytics.pipeline.entity.DataQualityResult>();
        if (runId != null) {
            countQuery.eq(com.graduation.analytics.pipeline.entity.DataQualityResult::getRunId, runId);
            query.eq(com.graduation.analytics.pipeline.entity.DataQualityResult::getRunId, runId);
        }
        long total = qualityMapper.selectCount(countQuery);
        long totalPages = PaginationMath.totalPages(total, size);
        long normalizedPage = Math.min(page, totalPages);
        long offset = Math.multiplyExact(normalizedPage - 1, (long) size);
        boolean ascending = "asc".equals(direction);
        switch (field) {
            case "createdat" -> { if (ascending) query.orderByAsc(com.graduation.analytics.pipeline.entity.DataQualityResult::getCreatedAt); else query.orderByDesc(com.graduation.analytics.pipeline.entity.DataQualityResult::getCreatedAt); }
            case "runid" -> { if (ascending) query.orderByAsc(com.graduation.analytics.pipeline.entity.DataQualityResult::getRunId); else query.orderByDesc(com.graduation.analytics.pipeline.entity.DataQualityResult::getRunId); }
            case "rulecode" -> { if (ascending) query.orderByAsc(com.graduation.analytics.pipeline.entity.DataQualityResult::getRuleCode); else query.orderByDesc(com.graduation.analytics.pipeline.entity.DataQualityResult::getRuleCode); }
            case "layer" -> { if (ascending) query.orderByAsc(com.graduation.analytics.pipeline.entity.DataQualityResult::getLayer); else query.orderByDesc(com.graduation.analytics.pipeline.entity.DataQualityResult::getLayer); }
            case "passed" -> { if (ascending) query.orderByAsc(com.graduation.analytics.pipeline.entity.DataQualityResult::getPassed); else query.orderByDesc(com.graduation.analytics.pipeline.entity.DataQualityResult::getPassed); }
            default -> { if (ascending) query.orderByAsc(com.graduation.analytics.pipeline.entity.DataQualityResult::getId); else query.orderByDesc(com.graduation.analytics.pipeline.entity.DataQualityResult::getId); }
        }
        if (!"id".equals(field)) query.orderByDesc(com.graduation.analytics.pipeline.entity.DataQualityResult::getId);
        var items = qualityMapper.selectList(query.last("LIMIT " + size + " OFFSET " + offset));
        var result = new QualityPage(items, normalizedPage, size, total, totalPages, field + "," + direction);
        return ApiResponse.ok(result, TraceContext.create().traceId());
    }
}
