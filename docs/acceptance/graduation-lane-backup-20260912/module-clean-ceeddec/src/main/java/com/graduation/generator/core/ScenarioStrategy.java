package com.graduation.generator.core;

import java.util.Map;

/**
 * 场景策略（§20.4）：只调整明确参数（factors），不直接伪造最终指标。
 */
public interface ScenarioStrategy {

    ScenarioCode code();

    GenerationFactors factors(GeneratorConfig config);

    ExpectedEffect expectedEffect();
}
