package com.graduation.analytics.controller;

import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.MySqlMetricStore;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import com.graduation.analytics.common.TraceContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 指标服务接口（§24.3 子集）：看板固定查询走 MetricStore；快照列表供管理页。
 *
 * R7-1：快照列表不再走 MyBatis mapper（analytics_metric 无 mapper 扫描），
 * 改由 {@link MySqlMetricStore#listSnapshots(int)} 通过 metric_read 只读源查询，返回结构不变。
 */
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricController {

    private final MySqlMetricStore metricStore;
    private final com.graduation.analytics.pipeline.mapper.DataQualityResultMapper qualityMapper;

    /** 经营指标（默认最新 ACTIVE 快照）；返回 metric_code→value+unit */
    @GetMapping("/overview")
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
    public ApiResponse<List<MetricSnapshot>> snapshots(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(metricStore.listSnapshots(limit), TraceContext.create().traceId());
    }

    @GetMapping("/health")
    public ApiResponse<MetricStore.HealthResult> health() {
        return ApiResponse.ok(metricStore.healthCheck(), TraceContext.create().traceId());
    }

    /** 数据质量规则结果（§23.1 质量门；管理员运维页） */
    @GetMapping("/quality")
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
}