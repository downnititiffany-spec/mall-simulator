package com.graduation.mall.generator;

import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 生成确定性测试（§20.6 验收 1）：相同 randomSeed + 配置 → 相同摘要。
 */
class GeneratorDeterminismTest extends MallTestSupport {

    @Autowired
    private GeneratorRunService generatorRunService;

    private GeneratorConfig config(long seed) {
        return new GeneratorConfig(30, 0, 2, 0.05,
                LocalDateTime.of(2026, 9, 1, 8, 0), LocalDateTime.of(2026, 9, 1, 9, 0),
                seed, 0.0, "normal");
    }

    @Test
    @DisplayName("同种子两次运行：事件/订单/金额/摘要完全一致")
    void sameSeedSameResult() {
        GeneratorConfig c = config(20260901L);
        GenerationResult first = generatorRunService.run(c);
        GenerationResult second = generatorRunService.run(c);

        assertEquals(first.configKey(), second.configKey());
        assertEquals(first.usersCreated(), second.usersCreated());
        assertEquals(first.productsCreated(), second.productsCreated());
        assertEquals(first.totalEvents(), second.totalEvents());
        assertEquals(first.behaviorsByType(), second.behaviorsByType());
        assertEquals(first.ordersCreated(), second.ordersCreated());
        assertEquals(first.ordersPaid(), second.ordersPaid());
        assertEquals(first.ordersCancelled(), second.ordersCancelled());
        assertEquals(first.ordersCompleted(), second.ordersCompleted());
        assertEquals(first.refundsApplied(), second.refundsApplied());
        assertEquals(first.refundsCompleted(), second.refundsCompleted());
        assertEquals(0, first.gmv().compareTo(second.gmv()), "GMV 必须一致");
        assertEquals(0, first.netSale().compareTo(second.netSale()), "净销售额必须一致");
        assertEquals(0, first.avgOrderValue().compareTo(second.avgOrderValue()));
        assertEquals(first.stockShortageHits(), second.stockShortageHits());
    }

    @Test
    @DisplayName("不同种子结果不同（存在随机性）")
    void differentSeedDifferentResult() {
        GenerationResult a = generatorRunService.run(config(1L));
        GenerationResult b = generatorRunService.run(config(2L));
        // 几乎必然不同：比较行为分布/订单数/GMV
        boolean differ = !a.behaviorsByType().equals(b.behaviorsByType())
                || a.ordersPaid() != b.ordersPaid()
                || a.gmv().compareTo(b.gmv()) != 0;
        org.junit.jupiter.api.Assertions.assertTrue(differ,
                "不同种子应当产生不同结果（除非分布恰好重合）");
    }
}