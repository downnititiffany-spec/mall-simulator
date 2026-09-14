package com.graduation.generator.core;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 生成器 REST DTO。
 */
public final class GeneratorDtos {

    private GeneratorDtos() {
    }

    public record GeneratorRunReq(
            @Min(value = 1, message = "userCount 至少 1") int userCount,
            @Min(value = 0, message = "productCount 不小于 0") int productCount,
            @Min(value = 1, message = "eventsPerSecond 至少 1") int eventsPerSecond,
            @DecimalMin(value = "0", message = "baseConversionRate 范围 [0,1]")
            @DecimalMax(value = "1", message = "baseConversionRate 范围 [0,1]") double baseConversionRate,
            @NotNull(message = "startTime 必填") LocalDateTime startTime,
            @NotNull(message = "endTime 必填") LocalDateTime endTime,
            long randomSeed,
            @DecimalMin(value = "0", message = "dirtyDataRate 范围 [0,1]")
            @DecimalMax(value = "1", message = "dirtyDataRate 范围 [0,1]") double dirtyDataRate,
            @NotBlank(message = "scenario 必填") String scenario) {
    }

    public record ScenarioInfo(String code, String label, java.util.Map<String, String> expectedDirections) {
    }
}
