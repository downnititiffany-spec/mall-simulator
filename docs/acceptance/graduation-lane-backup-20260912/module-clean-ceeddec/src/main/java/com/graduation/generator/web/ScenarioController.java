package com.graduation.generator.web;

import com.graduation.generator.core.ExpectedEffect;
import com.graduation.generator.core.ScenarioRegistry;
import com.graduation.generator.web.dto.GeneratorApiDtos.ScenarioView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * §4.4 L157：场景定义和输入约束。
 *
 * <p>契约对响应元素只给了空 schema（{@code Scenario}: 无 properties + {@code x-unspecified}），并明确要求实现方
 * **不得**把自己的输出字段当成契约字段回写。因此这里返回本实现自己的三个字段
 * （{@code code/label/expected_directions}），并在 D-015 登记为"实现约定、等待冻结"。</p>
 *
 * <p>场景清单来自 {@link ScenarioRegistry}（六个策略），是唯一所有者：这里不复制一份枚举，避免两处漂移。</p>
 */
@RestController
@RequestMapping(path = "/api/v1/scenarios", produces = MediaType.APPLICATION_JSON_VALUE)
public class ScenarioController {

    @GetMapping
    public List<ScenarioView> list() {
        return ScenarioRegistry.all().values().stream()
                .map(strategy -> strategy.expectedEffect())
                .sorted(java.util.Comparator.comparing(ExpectedEffect::code))
                .map(effect -> new ScenarioView(effect.code(), effect.label(), effect.directions()))
                .toList();
    }
}
