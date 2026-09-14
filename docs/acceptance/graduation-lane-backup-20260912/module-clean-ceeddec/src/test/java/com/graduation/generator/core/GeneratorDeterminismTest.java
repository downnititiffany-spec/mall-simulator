package com.graduation.generator.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 生成确定性测试（§20.6 验收 1）：相同 randomSeed + 配置 → 相同结果。
 *
 * <p><b>移植说明（不是逐行照搬）</b>：旧商城的同名测试继承 Spring 集成基类、注入运行服务，
 * 跑一遍完整生成再比对摘要（事件数/订单数/GMV 等）。本切片只移植纯场景/规划层，
 * 运行引擎（事件流、订单状态机驱动、落库/落文件）属于后续切片，因此这里把断言下移到
 * 已移植的纯函数层：同种子 + 同配置 → 同可复现键、同场景因子、同抽样序列；
 * 不同种子 → 不同可复现键与抽样序列。</p>
 *
 * <p><b>未覆盖</b>：引擎级摘要一致性（configKey/usersCreated/ordersPaid/gmv... 两次运行完全相同）
 * 必须由移植 SimulationEngine/GeneratorRunService 的后续切片重新补测，本文件不宣称验证过引擎行为。</p>
 */
class GeneratorDeterminismTest {

    private static GeneratorConfig config(long seed) {
        return new GeneratorConfig(30, 0, 2, 0.05,
                LocalDateTime.of(2026, 9, 1, 8, 0), LocalDateTime.of(2026, 9, 1, 9, 0),
                seed, 0.0, "normal");
    }

    @Test
    @DisplayName("同种子同配置：可复现键、场景因子与抽样序列完全一致")
    void sameSeedSameResult() {
        GeneratorConfig first = config(20260901L);
        GeneratorConfig second = config(20260901L);

        assertEquals(first.reproducibilityKey(), second.reproducibilityKey());
        assertEquals(ScenarioRegistry.get(first.scenario()).factors(first),
                ScenarioRegistry.get(second.scenario()).factors(second));
        assertEquals(draw(20260901L), draw(20260901L), "同种子的抽样序列必须一致");
        assertEquals(ScenarioRegistry.get(first.scenario()).expectedEffect(),
                ScenarioRegistry.get(second.scenario()).expectedEffect());
    }

    @Test
    @DisplayName("不同种子结果不同（存在随机性）")
    void differentSeedDifferentResult() {
        assertNotEquals(config(1L).reproducibilityKey(), config(2L).reproducibilityKey(),
                "可复现键必须随种子变化");
        assertNotEquals(draw(1L), draw(2L), "不同种子应当产生不同抽样序列");
    }

    /** 同一 RNG 消费序列下的抽样指纹（20 次 nextInt(100)） */
    private static String draw(long seed) {
        DistributionKit kit = new DistributionKit(seed);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            out.append(kit.nextInt(100)).append(',');
        }
        return out.toString();
    }
}
