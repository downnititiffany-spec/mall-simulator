package com.graduation.analytics.controller;

import com.graduation.analytics.analysis.AnalysisService;
import com.graduation.analytics.analysis.AnalysisService.FunnelData;
import com.graduation.analytics.analysis.AnalysisService.OverviewData;
import com.graduation.analytics.analysis.AnalysisService.ProductsData;
import com.graduation.analytics.analysis.AnalysisService.RfmData;
import com.graduation.analytics.analysis.AnalysisService.SalesData;
import com.graduation.analytics.analysis.AnalysisService.UsersData;
import com.graduation.analytics.analysis.AnalysisViewModel;
import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 专题分析接口（§24.3）：销售/商品/漏斗/用户 + 大盘。
 * 固定看板数据源：不调用大模型，普通员工直接可用。
 *
 * <p>R7-4 看板切换：URL 与查询参数**保持不变**（前端 web/src/api.js 不用改），
 * 变化只在 {@code ApiResponse.data} 里 —— 由裸 DTO 换成统一信封 {@link AnalysisViewModel}
 * （契约 docs/contracts/analysis-viewmodel-r7-4.md §2）。控制器不做任何聚合与兜底，
 * 只做参数解析和透传（§18.1 数据分析边界）。</p>
 *
 * <p>参数兼容说明：{@code from/to/topN/date/limit} 在本层只用于回显（服务端按 ADS 物化粒度取值，
 * 不按事件时间重算），因此旧前端的传参仍然被接受、被原样回显，但不再影响数据；
 * 新增可选的 {@code snapshotId}，不传则服务端取 ACTIVE 快照并在响应中回传。</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
// R8-3 §3.2：专题分析与大盘全部归 dashboard:view（admin/data_dev/operator/analyst 四种角色都有）
@RequiresPermission(PermissionCode.DASHBOARD_VIEW)
public class AnalysisController {

    private final AnalysisService analysisService;

    /** 运营总览（§3.1） */
    @GetMapping("/dashboards/overview")
    public ApiResponse<AnalysisViewModel<OverviewData>> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String snapshotId) {
        return ApiResponse.ok(analysisService.overview(snapshotId, from, to), TraceContext.create().traceId());
    }

    /** 销售分析（§3.2） */
    @GetMapping("/analysis/sales")
    public ApiResponse<AnalysisViewModel<SalesData>> sales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String snapshotId) {
        return ApiResponse.ok(analysisService.sales(snapshotId, from, to), TraceContext.create().traceId());
    }

    /**
     * 商品分析：热度榜 + 转化（§3.3）。v1.3 起热度榜真分页（契约 §3.3 v1.3 / 设计 L693、L675）：
     * `page`/`size` 为新增分页参数，`topN` 保持旧参数兼容（`size` 未给定时充当窗口大小）。
     * v1.5（S3-21）补 `sort`（设计 L693「分页 page/size/sort」）：形如 `字段[,asc|desc]`，
     * 字段须在服务层白名单内；缺省 = `rank,asc`（与 v1.4 前逐行同序）。
     */
    @GetMapping("/analysis/products")
    public ApiResponse<AnalysisViewModel<ProductsData>> products(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer topN,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String snapshotId) {
        return ApiResponse.ok(analysisService.products(snapshotId, page, size, sort, topN, from, to),
                TraceContext.create().traceId());
    }

    /** 行为漏斗（§3.4）；date 兼容旧前端传参，只回显不参与计算 */
    @GetMapping("/analysis/funnel")
    public ApiResponse<AnalysisViewModel<FunnelData>> funnel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String snapshotId) {
        return ApiResponse.ok(analysisService.funnel(snapshotId, date), TraceContext.create().traceId());
    }

    /** 用户分群（§3.5）：RFM 分层 + 生命周期 + 偏好（只返回聚合，不含个人明细） */
    @GetMapping("/analysis/users")
    public ApiResponse<AnalysisViewModel<UsersData>> users(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String snapshotId) {
        return ApiResponse.ok(analysisService.users(snapshotId, from, to), TraceContext.create().traceId());
    }

    /** RFM 用户分层（§21.6、契约 §3.6）：八类分布矩阵 + 口径版本；limit 兼容旧前端传参，只回显 */
    @GetMapping("/analysis/rfm")
    public ApiResponse<AnalysisViewModel<RfmData>> rfm(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String snapshotId) {
        return ApiResponse.ok(analysisService.rfm(snapshotId, limit), TraceContext.create().traceId());
    }
}
