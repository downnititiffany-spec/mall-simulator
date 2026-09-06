package com.graduation.mall.controller;

import com.graduation.mall.common.ApiResponse;
import com.graduation.mall.metric.MetricStore;
import com.graduation.mall.metric.entity.MetricSnapshot;
import com.graduation.mall.metric.entity.MetricValue;
import com.graduation.mall.metric.mapper.MetricSnapshotMapper;
import com.graduation.mall.outbox.TraceContext;
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
 */
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricController {

    private final MetricStore metricStore;
    private final MetricSnapshotMapper snapshotMapper;

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
        return ApiResponse.ok(snapshotMapper.selectList(new LambdaQueryWrapper<MetricSnapshot>()
                .orderByDesc(MetricSnapshot::getId)
                .last("LIMIT " + Math.max(1, Math.min(100, limit)))), TraceContext.create().traceId());
    }

    @GetMapping("/health")
    public ApiResponse<MetricStore.HealthResult> health() {
        return ApiResponse.ok(metricStore.healthCheck(), TraceContext.create().traceId());
    }
}