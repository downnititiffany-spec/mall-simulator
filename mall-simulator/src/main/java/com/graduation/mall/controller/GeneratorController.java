package com.graduation.mall.controller;

import com.graduation.mall.common.ApiResponse;
import com.graduation.mall.generator.GeneratorConfig;
import com.graduation.mall.generator.GeneratorDtos.GeneratorRunReq;
import com.graduation.mall.generator.GeneratorDtos.ScenarioInfo;
import com.graduation.mall.generator.GeneratorRunService;
import com.graduation.mall.generator.GenerationResult;
import com.graduation.mall.generator.scenario.ScenarioRegistry;
import com.graduation.mall.outbox.TraceContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 演示控制台生成接口（§3.5.2）：配置经营场景/规模/种子 → 生成并输出摘要。
 * 生成器内部全部走商城业务 Service（§5.2.2 禁止直接写订单表）。
 */
@RestController
@RequestMapping("/api/v1/generator")
@RequiredArgsConstructor
public class GeneratorController {

    private final GeneratorRunService generatorRunService;

    @PostMapping("/runs")
    public ApiResponse<GenerationResult> run(@Valid @RequestBody GeneratorRunReq req) {
        GeneratorConfig config = new GeneratorConfig(
                req.userCount(), req.productCount(), req.eventsPerSecond(), req.baseConversionRate(),
                req.startTime(), req.endTime(), req.randomSeed(), req.dirtyDataRate(), req.scenario());
        GenerationResult result = generatorRunService.run(config);
        return ApiResponse.ok(result, TraceContext.create().traceId());
    }

    @GetMapping("/scenarios")
    public ApiResponse<List<ScenarioInfo>> scenarios() {
        List<ScenarioInfo> list = ScenarioRegistry.all().values().stream()
                .map(s -> new ScenarioInfo(s.code().name(), s.code().label(),
                        s.expectedEffect().directions()))
                .toList();
        return ApiResponse.ok(list, TraceContext.create().traceId());
    }
}