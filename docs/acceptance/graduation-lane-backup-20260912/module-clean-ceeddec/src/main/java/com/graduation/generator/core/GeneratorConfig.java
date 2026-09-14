package com.graduation.generator.core;

import java.time.LocalDateTime;

/**
 * 生成器配置（§5.2.3 参数表）。
 * 相同 randomSeed + 配置版本 → 可复现数据（§20.6 验收 1）。
 */
public record GeneratorConfig(
        int userCount,
        int productCount,           // 0=使用种子商品池，>0 额外按分类模板补建商品
        int eventsPerSecond,        // 每秒行为事件数（批量模式按总时长折算事件预算）
        double baseConversionRate,  // 浏览到支付的基础概率（0~1）
        LocalDateTime startTime,
        LocalDateTime endTime,
        long randomSeed,
        double dirtyDataRate,       // 0~1，脏数据比例（相对正常事件数）
        String scenario            // ScenarioCode.name()
) {

    public GeneratorConfig {
        if (userCount <= 0) {
            throw new IllegalArgumentException("userCount 必须 > 0");
        }
        if (eventsPerSecond <= 0) {
            throw new IllegalArgumentException("eventsPerSecond 必须 > 0");
        }
        if (baseConversionRate < 0 || baseConversionRate > 1) {
            throw new IllegalArgumentException("baseConversionRate 必须在 [0,1]");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("endTime 必须晚于 startTime");
        }
        if (dirtyDataRate < 0 || dirtyDataRate > 1) {
            throw new IllegalArgumentException("dirtyDataRate 必须在 [0,1]");
        }
        if (scenario == null || scenario.isBlank()) {
            scenario = ScenarioCode.normal.name();
        }
    }

    /** 可复现性键：决定结果的全部输入（§20.6：相同种子+配置版本 → 相同摘要） */
    public String reproducibilityKey() {
        return "v1|seed=" + randomSeed
                + "|users=" + userCount
                + "|products=" + productCount
                + "|eps=" + eventsPerSecond
                + "|conv=" + baseConversionRate
                + "|window=" + startTime + "~" + endTime
                + "|dirty=" + dirtyDataRate
                + "|scenario=" + scenario;
    }
}
