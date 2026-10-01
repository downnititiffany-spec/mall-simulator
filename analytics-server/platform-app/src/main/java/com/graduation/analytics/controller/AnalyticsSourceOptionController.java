package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.metric.MySqlMetricStore;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 面向看板用户的安全数据源选项。
 *
 * <p>普通看板角色只能看到「已存在已发布指标快照」对应的来源标识、编码和展示名；
 * 运行画像路径、仓库前缀等运维信息仍只由 runtime:manage 接口提供。</p>
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiresPermission(PermissionCode.DASHBOARD_VIEW)
@RequiredArgsConstructor
public class AnalyticsSourceOptionController {

    private final SourceRegistryService sourceRegistryService;
    private final MySqlMetricStore metricStore;

    @GetMapping("/sources")
    public ApiResponse<List<AnalyticsSourceOption>> listSources() {
        Set<Long> publishedSourceIds = metricStore.listSnapshots(100).stream()
                .filter(AnalyticsSourceOptionController::isPublished)
                .map(MetricSnapshot::getSourceId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());

        List<AnalyticsSourceOption> options = sourceRegistryService.list().stream()
                .filter(source -> source.id() != null && publishedSourceIds.contains(source.id()))
                .map(AnalyticsSourceOptionController::toOption)
                .sorted(Comparator.comparing(AnalyticsSourceOption::displayName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(AnalyticsSourceOption::sourceId))
                .toList();
        return ApiResponse.ok(options, TraceContext.create().traceId());
    }

    private static boolean isPublished(MetricSnapshot snapshot) {
        return snapshot != null && (MetricSnapshot.STATUS_ACTIVE.equals(snapshot.getStatus())
                || MetricSnapshot.STATUS_ARCHIVED.equals(snapshot.getStatus()));
    }

    private static AnalyticsSourceOption toOption(SourceRegistryView source) {
        return new AnalyticsSourceOption(source.id(), source.sourceCode(), source.displayName());
    }

    /** 仅包含员工选源所需字段，不得在此增加画像路径或仓库配置字段。 */
    public record AnalyticsSourceOption(Long sourceId, String sourceCode, String displayName) {
    }
}
