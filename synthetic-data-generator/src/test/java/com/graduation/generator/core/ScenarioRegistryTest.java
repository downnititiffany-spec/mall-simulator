package com.graduation.generator.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景注册表测试（§20.4）：11 场景齐全、code 唯一、normal 为中性因子。
 */
class ScenarioRegistryTest {

    private static GeneratorConfig config(String scenario) {
        return new GeneratorConfig(10, 0, 1, 0.02,
                LocalDateTime.of(2026, 9, 1, 8, 0), LocalDateTime.of(2026, 9, 1, 9, 0),
                42, 0, scenario);
    }

    @Test
    void 全部11个场景已注册且code唯一() {
        assertEquals(11, ScenarioRegistry.all().size());
        Set<String> codes = new HashSet<>();
        for (ScenarioStrategy s : ScenarioRegistry.all().values()) {
            assertTrue(codes.add(s.code().name()), "重复场景码: " + s.code());
        }
        for (ScenarioCode code : ScenarioCode.values()) {
            assertEquals(code, ScenarioRegistry.get(code.name()).code());
        }
    }

    @Test
    void normal场景因子全部中性() {
        GenerationFactors f = ScenarioRegistry.get("normal").factors(config("normal"));
        assertEquals(1.0, f.conversionMultiplier());
        assertEquals(1.0, f.refundMultiplier());
        assertEquals(1.0, f.priceMultiplier());
        assertEquals(1.0, f.trafficMultiplier());
        assertEquals(1.0, f.oldUserWeight());
        assertEquals(1.0, f.weekendBoost());
    }
}
