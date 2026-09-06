package com.graduation.mall.generator;

import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景方向测试（§20.4/§20.6 验收 6）：同一时间窗口与种子下，
 * 促销→订单与 GMV 上升；退款上升→退款单数上升（方向可识别）。
 * ExpectedEffect 不参与生成，只用于核对方向。
 */
class ScenarioEffectTest extends MallTestSupport {

    @Autowired
    private GeneratorRunService generatorRunService;

    private GeneratorConfig config(String scenario) {
        return new GeneratorConfig(50, 0, 1, 0.10,
                LocalDateTime.of(2026, 9, 6, 9, 0), LocalDateTime.of(2026, 9, 6, 10, 0),
                42L, 0.0, scenario);
    }

    @Test
    @DisplayName("促销场景：支付订单数与 GMV 高于正常场景")
    void promotionLiftsGmv() {
        GenerationResult normal = generatorRunService.run(config("normal"));
        GenerationResult promo = generatorRunService.run(config("promotion"));

        assertTrue(promo.ordersPaid() > normal.ordersPaid(),
                "促销支付订单应更多: normal=" + normal.ordersPaid() + " promo=" + promo.ordersPaid());
        assertTrue(promo.gmv().compareTo(normal.gmv()) > 0,
                "促销 GMV 应更高: normal=" + normal.gmv() + " promo=" + promo.gmv());
        assertTrue(normal.behaviorsByType().getOrDefault("view", 0L) <
                        promo.behaviorsByType().getOrDefault("view", 0L),
                "促销流量应更高（trafficMultiplier=1.5）");
    }

    @Test
    @DisplayName("退款上升场景：completed 退款单数高于正常场景且净销售额下降")
    void refundRiseLiftsRefunds() {
        GenerationResult normal = generatorRunService.run(config("normal"));
        GenerationResult refund = generatorRunService.run(config("refund_rise"));

        assertTrue(refund.refundsCompleted() > normal.refundsCompleted(),
                "退款上升场景退款单应更多: normal=" + normal.refundsCompleted()
                        + " refund=" + refund.refundsCompleted());
        assertTrue(refund.gmv().compareTo(refund.netSale()) > 0,
                "退款后净销售额应低于 GMV");
        assertTrue(refund.refundsCompleted() > 0, "退款场景至少发生一笔退款");
    }
}