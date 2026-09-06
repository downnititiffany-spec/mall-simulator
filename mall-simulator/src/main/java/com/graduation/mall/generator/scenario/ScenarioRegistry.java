package com.graduation.mall.generator.scenario;

import com.graduation.mall.generator.ExpectedEffect;
import com.graduation.mall.generator.GenerationFactors;
import com.graduation.mall.generator.GeneratorConfig;
import com.graduation.mall.generator.ScenarioCode;

import java.util.Map;

/**
 * 场景注册表（§20.4 场景清单）。
 * 纯因子型场景用 ShiftScenario 定义；需要引擎参与选择（爆款/缺货/分类）的用专用实现。
 */
public final class ScenarioRegistry {

    private static final Map<String, ScenarioStrategy> BY_CODE = Map.ofEntries(
            Map.entry("normal", new ShiftScenario(ScenarioCode.normal, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, Map.of())),
            Map.entry("promotion", new ShiftScenario(ScenarioCode.promotion, 1.6, 1.0, 0.9, 1.5, 1.0, 1.0, Map.of(
                    "pv", "UP", "gmv", "UP", "avg_order_value", "DOWN"))),
            Map.entry("weekend_growth", new ShiftScenario(ScenarioCode.weekend_growth, 1.1, 1.0, 1.0, 1.2, 1.0, 1.6, Map.of(
                    "gmv", "UP"))),
            Map.entry("sales_decline", new ShiftScenario(ScenarioCode.sales_decline, 0.7, 1.0, 1.0, 0.6, 1.0, 1.0, Map.of(
                    "pv", "DOWN", "gmv", "DOWN"))),
            Map.entry("refund_rise", new ShiftScenario(ScenarioCode.refund_rise, 0.95, 4.0, 1.0, 1.0, 1.0, 1.0, Map.of(
                    "refund_rate", "UP", "net_sale", "DOWN"))),
            Map.entry("price_increase", new PriceIncreaseScenario()),
            Map.entry("hot_product", new HotProductScenario()),
            Map.entry("stock_shortage", new StockShortageScenario()),
            Map.entry("new_product_cold_start", new NewProductColdStartScenario()),
            Map.entry("old_user_churn", new OldUserChurnScenario()),
            Map.entry("new_user_growth", new ShiftScenario(ScenarioCode.new_user_growth, 1.1, 1.0, 1.0, 1.4, 1.0, 1.0, Map.of(
                    "uv", "UP", "gmv", "UP")))
    );

    private ScenarioRegistry() {
    }

    public static ScenarioStrategy get(String code) {
        ScenarioStrategy s = BY_CODE.get(code);
        if (s == null) {
            throw new IllegalArgumentException("未知场景: " + code);
        }
        return s;
    }

    public static Map<String, ScenarioStrategy> all() {
        return BY_CODE;
    }

    /** 纯因子型场景：由乘数定义 */
    static final class ShiftScenario implements ScenarioStrategy {
        private final ScenarioCode code;
        private final double conversionMultiplier;
        private final double refundMultiplier;
        private final double priceMultiplier;
        private final double trafficMultiplier;
        private final double oldUserWeight;
        private final double weekendBoost;
        private final Map<String, String> directions;

        ShiftScenario(ScenarioCode code, double conversionMultiplier, double refundMultiplier,
                      double priceMultiplier, double trafficMultiplier, double oldUserWeight,
                      double weekendBoost, Map<String, String> directions) {
            this.code = code;
            this.conversionMultiplier = conversionMultiplier;
            this.refundMultiplier = refundMultiplier;
            this.priceMultiplier = priceMultiplier;
            this.trafficMultiplier = trafficMultiplier;
            this.oldUserWeight = oldUserWeight;
            this.weekendBoost = weekendBoost;
            this.directions = directions;
        }

        @Override
        public ScenarioCode code() {
            return code;
        }

        @Override
        public GenerationFactors factors(GeneratorConfig config) {
            return new GenerationFactors(conversionMultiplier, refundMultiplier, priceMultiplier,
                    trafficMultiplier, oldUserWeight, weekendBoost, false, false, false, false,
                    java.util.List.of(), java.util.List.of(), java.util.List.of());
        }

        @Override
        public ExpectedEffect expectedEffect() {
            return ExpectedEffect.of(code.name(), code.label(), directions);
        }
    }

    /** 价格上涨：选择一个分类提价，客单价升、转化降 */
    static final class PriceIncreaseScenario implements ScenarioStrategy {
        @Override
        public ScenarioCode code() {
            return ScenarioCode.price_increase;
        }

        @Override
        public GenerationFactors factors(GeneratorConfig config) {
            return new GenerationFactors(0.85, 1.0, 1.3, 1.0, 1.0, 1.0,
                    false, false, true, false, java.util.List.of(), java.util.List.of(), java.util.List.of());
        }

        @Override
        public ExpectedEffect expectedEffect() {
            return ExpectedEffect.of(code().name(), code().label(),
                    Map.of("avg_order_value", "UP", "buy_rate", "DOWN"));
        }
    }

    /** 单品爆款：头部商品曝光与转化加权 */
    static final class HotProductScenario implements ScenarioStrategy {
        @Override
        public ScenarioCode code() {
            return ScenarioCode.hot_product;
        }

        @Override
        public GenerationFactors factors(GeneratorConfig config) {
            return new GenerationFactors(1.15, 1.0, 1.0, 1.0, 1.0, 1.0,
                    true, false, false, false, java.util.List.of(), java.util.List.of(), java.util.List.of());
        }

        @Override
        public ExpectedEffect expectedEffect() {
            return ExpectedEffect.of(code().name(), code().label(), Map.of("top_product_share", "UP"));
        }
    }

    /** 库存不足：头部商品可售库存压低，缺货率升、支付转化降 */
    static final class StockShortageScenario implements ScenarioStrategy {
        @Override
        public ScenarioCode code() {
            return ScenarioCode.stock_shortage;
        }

        @Override
        public GenerationFactors factors(GeneratorConfig config) {
            return new GenerationFactors(0.7, 1.2, 1.0, 1.0, 1.0, 1.0,
                    false, true, false, false, java.util.List.of(), java.util.List.of(), java.util.List.of());
        }

        @Override
        public ExpectedEffect expectedEffect() {
            return ExpectedEffect.of(code().name(), code().label(),
                    Map.of("stock_shortage", "UP", "buy_rate", "DOWN"));
        }
    }

    /** 新品冷启动：引擎补建商品并加权曝光，新商品贡献占比升 */
    static final class NewProductColdStartScenario implements ScenarioStrategy {
        @Override
        public ScenarioCode code() {
            return ScenarioCode.new_product_cold_start;
        }

        @Override
        public GenerationFactors factors(GeneratorConfig config) {
            return new GenerationFactors(1.05, 1.0, 1.0, 1.2, 1.0, 1.0,
                    false, false, false, true, java.util.List.of(), java.util.List.of(), java.util.List.of());
        }

        @Override
        public ExpectedEffect expectedEffect() {
            return ExpectedEffect.of(code().name(), code().label(), Map.of("new_product_share", "UP"));
        }
    }

    /** 老用户流失：老用户会话权重降低，活跃与复购下降 */
    static final class OldUserChurnScenario implements ScenarioStrategy {
        @Override
        public ScenarioCode code() {
            return ScenarioCode.old_user_churn;
        }

        @Override
        public GenerationFactors factors(GeneratorConfig config) {
            return new GenerationFactors(1.0, 1.0, 1.0, 1.0, 0.45, 1.0,
                    false, false, false, false, java.util.List.of(), java.util.List.of(), java.util.List.of());
        }

        @Override
        public ExpectedEffect expectedEffect() {
            return ExpectedEffect.of(code().name(), code().label(),
                    Map.of("repeat_rate", "DOWN", "dau", "DOWN"));
        }
    }
}