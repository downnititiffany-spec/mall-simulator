package com.graduation.mall.controller;

import com.graduation.mall.analysis.AnalysisService;
import com.graduation.mall.analysis.AnalysisService.ActiveDay;
import com.graduation.mall.analysis.AnalysisService.FunnelStage;
import com.graduation.mall.analysis.AnalysisService.Overview;
import com.graduation.mall.analysis.AnalysisService.ProductRankItem;
import com.graduation.mall.analysis.AnalysisService.SalesDay;
import com.graduation.mall.common.ApiResponse;
import com.graduation.mall.outbox.TraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 专题分析接口（§24.3）：销售/商品/漏斗/用户 + 大盘。
 * 固定看板数据源：不调用大模型，普通员工直接可用。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;

    @GetMapping("/dashboards/overview")
    public ApiResponse<Overview> overview() {
        return ApiResponse.ok(analysisService.overview(), TraceContext.create().traceId());
    }

    @GetMapping("/analysis/sales")
    public ApiResponse<List<SalesDay>> sales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(analysisService.salesTrend(from, to), TraceContext.create().traceId());
    }

    @GetMapping("/analysis/products")
    public ApiResponse<List<ProductRankItem>> products(
            @RequestParam(defaultValue = "10") int topN,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(analysisService.productRank(topN, from, to), TraceContext.create().traceId());
    }

    @GetMapping("/analysis/funnel")
    public ApiResponse<List<FunnelStage>> funnel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(analysisService.funnelDay(date), TraceContext.create().traceId());
    }

    @GetMapping("/analysis/users")
    public ApiResponse<List<ActiveDay>> users(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(analysisService.userActiveTrend(from, to), TraceContext.create().traceId());
    }
}