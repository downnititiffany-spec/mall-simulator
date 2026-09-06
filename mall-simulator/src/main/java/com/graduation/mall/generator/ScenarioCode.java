package com.graduation.mall.generator;

/**
 * 内置经营场景（§20.4）：每个场景只调整生成因子，不直接伪造最终指标。
 */
public enum ScenarioCode {
    normal("正常经营"),
    weekend_growth("周末增长"),
    promotion("促销爆发"),
    new_product_cold_start("新品冷启动"),
    hot_product("单品爆款"),
    stock_shortage("库存不足"),
    price_increase("价格上涨"),
    sales_decline("整体销量下降"),
    refund_rise("退款率上升"),
    new_user_growth("新用户增长"),
    old_user_churn("老用户流失");

    private final String label;

    ScenarioCode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}