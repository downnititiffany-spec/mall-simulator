package com.graduation.generator.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景方向测试（§20.4/§20.6 验收 6）：同一时间窗口与种子下，场景只通过因子改变生成方向，方向可识别。
 *
 * <p><b>移植说明（不是逐行照搬）</b>：旧商城的同名测试继承 Spring 集成基类、注入运行服务，
 * 直接比对引擎产出（促销：支付订单数与 GMV 高于正常；退款上升：completed 退款单更多、净销售额低于 GMV）。
 * 本切片只移植纯场景/规划层，引擎属于后续切片，因此这里把断言下移到**因子与预期方向**：
 * promotion 相对 normal 流量/转化乘数更高、价格乘数更低；refund_rise 退款乘数放大且预期方向为
 * refund_rate UP / net_sale DOWN。ExpectedEffect 不参与生成，只用于核对方向。</p>
 *
 * <p><b>未覆盖</b>：引擎级方向（ordersPaid / gmv / refundsCompleted / netSale 的实际高低）
 * 必须由移植 SimulationEngine/GeneratorRunService 的后续切片重新补测，本文件不宣称验证过引擎行为。</p>
 */
class ScenarioEffectTest {

    private static GeneratorConfig config(String scenario) {
        return new GeneratorConfig(50, 0, 1, 0.10,
                LocalDateTime.of(2026, 9, 6, 9, 0), LocalDateTime.of(2026, 9, 6, 10, 0),
                42L, 0.0, scenario);
    }

    @Test
    @DisplayName("促销场景因子：流量与转化高于正常场景，价格低于正常场景")
    void promotionLiftsGmv() {
        GenerationFactors normal = ScenarioRegistry.get("normal").factors(config("normal"));
        GenerationFactors promo = ScenarioRegistry.get("promotion").factors(config("promotion"));

        assertTrue(promo.trafficMultiplier() > normal.trafficMultiplier(),
                "促销流量乘数应更高: normal=" + normal.trafficMultiplier()
                        + " promo=" + promo.trafficMultiplier());
        assertTrue(promo.conversionMultiplier() > normal.conversionMultiplier(),
                "促销转化乘数应更高: normal=" + normal.conversionMultiplier()
                        + " promo=" + promo.conversionMultiplier());
        assertTrue(promo.priceMultiplier() < normal.priceMultiplier(),
                "促销价格乘数应更低: normal=" + normal.priceMultiplier()
                        + " promo=" + promo.priceMultiplier());
        assertEquals("UP", ScenarioRegistry.get("promotion").expectedEffect().directions().get("gmv"));
    }

    @Test
    @DisplayName("退款上升场景因子：退款乘数放大，预期方向为退款率升、净销售额降")
    void refundRiseLiftsRefunds() {
        GenerationFactors normal = ScenarioRegistry.get("normal").factors(config("normal"));
        GenerationFactors refund = ScenarioRegistry.get("refund_rise").factors(config("refund_rise"));

        assertTrue(refund.refundMultiplier() > normal.refundMultiplier(),
                "退款上升场景退款乘数应更大: normal=" + normal.refundMultiplier()
                        + " refund=" + refund.refundMultiplier());

        ExpectedEffect effect = ScenarioRegistry.get("refund_rise").expectedEffect();
        assertEquals("UP", effect.directions().get("refund_rate"));
        assertEquals("DOWN", effect.directions().get("net_sale"));
    }
}
