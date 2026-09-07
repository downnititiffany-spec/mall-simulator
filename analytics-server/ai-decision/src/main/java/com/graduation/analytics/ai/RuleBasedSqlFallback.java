package com.graduation.analytics.ai;

import java.util.List;

/**
 * 规则回退（§3.5.5/§16 风险控制：AI 费用或网络不可用时的降级路径）：
 * 推荐问题 → 固定模板 SQL（安全且在白名单内）。
 */
public final class RuleBasedSqlFallback {

    public record Fallback(String sql, List<String> assumptions) {
    }

    private RuleBasedSqlFallback() {
    }

    public static Fallback resolve(String question, List<String> tables) {
        String q = question == null ? "" : question;
        if (q.contains("漏斗") || q.contains("转化")) {
            return new Fallback(
                    "SELECT dt, stage, user_count, conversion_rate FROM ads_behavior_funnel_m "
                            + "WHERE dt = (SELECT MAX(dt) FROM ads_sale_trend_m) ORDER BY FIELD(stage,'view','intent','order','pay') LIMIT 4",
                    List.of("默认取最新业务日四个阶段"));
        }
        if (q.contains("活跃") || q.contains("大盘") || q.contains("gmv") || q.contains("退款")) {
            return new Fallback(
                    "SELECT dt, pv, uv, dau, gmv, net_sale_amount, avg_order_value, refund_rate "
                            + "FROM ads_operation_overview_m WHERE dt = (SELECT MAX(dt) FROM ads_sale_trend_m) LIMIT 1",
                    List.of("默认取最新业务日快照"));
        }
        if (q.contains("商品") || q.contains("排行")) {
            return new Fallback(
                    "SELECT dt, order_count, sale_amount FROM ads_sale_trend_m "
                            + "WHERE dt >= (SELECT MAX(dt) FROM ads_sale_trend_m) LIMIT 1",
                    List.of("商品排行当前使用销售摘要替代展示"));
        }
        // 默认：销售趋势（近 7 天有效支付口径）
        return new Fallback(
                "SELECT dt, order_count, buyer_count, sale_amount, net_sale_amount, avg_order_value "
                        + "FROM ads_sale_trend_m WHERE dt >= (SELECT MAX(dt) FROM ads_sale_trend_m) LIMIT 7",
                List.of("默认取最近 7 天有效支付口径"));
    }
}