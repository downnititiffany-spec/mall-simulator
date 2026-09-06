package com.graduation.mall.generator;

import java.util.List;

/**
 * 场景生成因子（§20.4）：场景只修改这些可解释参数。
 * 选择型场景（爆款/缺货/调价分类）由引擎用同一 RNG 从商品池确定后填入 id 列表。
 */
public record GenerationFactors(
        double conversionMultiplier,  // 浏览→支付概率乘数
        double refundMultiplier,      // 支付→退款概率乘数
        double priceMultiplier,       // 受影响分类价格乘数（>1 涨价，<1 促销）
        double trafficMultiplier,     // 流量乘数
        double oldUserWeight,         // 老用户会话权重（<1 表示老用户流失）
        double weekendBoost,          // 周末流量放大（1.0=正常）
        boolean selectHotProduct,     // 引擎选一个高权重商品做爆款
        boolean selectShortageProduct,// 引擎选一个头部商品压低库存
        boolean selectAffectedCategories, // 引擎选一个分类受价格影响
        boolean createNewProducts,    // 新品冷启动：补建商品并加权曝光
        List<Long> hotProductIds,
        List<Long> shortageProductIds,
        List<Long> affectedCategories) {

    public static GenerationFactors neutral() {
        return new GenerationFactors(1.0, 1.0, 1.0, 1.0, 1.0, 1.0,
                false, false, false, false, List.of(), List.of(), List.of());
    }

    public GenerationFactors withHot(List<Long> ids) {
        return new GenerationFactors(conversionMultiplier, refundMultiplier, priceMultiplier,
                trafficMultiplier, oldUserWeight, weekendBoost, selectHotProduct, selectShortageProduct,
                selectAffectedCategories, createNewProducts, ids, shortageProductIds, affectedCategories);
    }

    public GenerationFactors withShortage(List<Long> ids) {
        return new GenerationFactors(conversionMultiplier, refundMultiplier, priceMultiplier,
                trafficMultiplier, oldUserWeight, weekendBoost, selectHotProduct, selectShortageProduct,
                selectAffectedCategories, createNewProducts, hotProductIds, ids, affectedCategories);
    }

    public GenerationFactors withCategories(List<Long> cats) {
        return new GenerationFactors(conversionMultiplier, refundMultiplier, priceMultiplier,
                trafficMultiplier, oldUserWeight, weekendBoost, selectHotProduct, selectShortageProduct,
                selectAffectedCategories, createNewProducts, hotProductIds, shortageProductIds, cats);
    }
}