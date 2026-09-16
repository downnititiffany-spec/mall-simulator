package com.graduation.analytics.metric;

import java.util.List;
import java.util.Map;

/**
 * R7-2：analytics_metric 八张 ADS 物化宽表白名单（§17.3 / V2.0 §24.4）。
 *
 * <p>唯一用途是给 {@link MetricAdsWriter}/{@link MetricAdsReader} 做**表名与列名校验**：
 * SQL 里的表名/列名只允许来自本清单（反引号拼接），任何来自外部的表名/列名都会在拼接前被拒绝，
 * 杜绝 SQL 注入与「写错库/写错表」。</p>
 *
 * <p>本期只建 8 张：Hive 侧首期 ADS 只有 8 张，{@code ads_category_sale_m}/{@code ads_region_sale_m}
 * 尚无对应 ADS，禁止先建空表造假数据（§24.4 只对有 Hive 来源的表建服务表）。</p>
 *
 * @param name       物理表名
 * @param columns    业务列（不含 snapshot_id / dt，二者所有表都有）；**必须包含全部主键业务列**
 *                   （写入校验只允许 columns 内的列名，漏掉主键列会导致整表无法写入）
 * @param keyColumns 主键中 snapshot_id/dt 之外的列（批量写入必须逐行提供，缺失即拒绝）
 */
public record MetricAdsCatalog(String name, List<String> columns, List<String> keyColumns) {

    /** 全表清单（插入顺序 = 建表顺序） */
    public static final List<MetricAdsCatalog> ALL = List.of(
            new MetricAdsCatalog("ads_operation_overview_m",
                    List.of("pv", "uv", "dau", "order_count", "sale_amount", "net_sale_amount",
                            "avg_order_value", "refund_rate", "full_refund_rate", "repeat_rate",
                            "repeat_period_start", "repeat_period_end"),
                    List.of()),
            new MetricAdsCatalog("ads_sale_trend_m",
                    List.of("order_count", "buyer_count", "sale_amount", "avg_order_value", "net_sale_amount"),
                    List.of()),
            new MetricAdsCatalog("ads_behavior_funnel_m",
                    List.of("stage", "user_count", "conversion_rate", "overall_buy_rate",
                            "overall_cart_rate"),
                    List.of("stage")),
            new MetricAdsCatalog("ads_active_trend_m",
                    List.of("dau", "behavior_count"),
                    List.of()),
            new MetricAdsCatalog("ads_hot_product_m",
                    List.of("product_id", "product_name", "heat_score", "pv", "fav", "cart", "buy", "rank_no",
                            "rule_version"),
                    List.of("rank_no")),
            new MetricAdsCatalog("ads_product_conversion_m",
                    List.of("product_id", "pv_users", "buy_users", "conversion_rate"),
                    List.of("product_id")),
            new MetricAdsCatalog("ads_user_profile_m",
                    List.of("user_id", "r", "f", "m", "value_group", "active_level", "favorite_category",
                            "last_active_date", "last_buy_date", "lifecycle_state", "rule_version", "calc_date",
                            "r_days", "f_count", "m_amount", "period_start", "period_end"),
                    List.of("user_id")),
            new MetricAdsCatalog("ads_data_quality_m",
                    List.of("rule_code", "check_count", "error_count", "error_rate", "passed", "threshold",
                            "rule_version"),
                    List.of("rule_code")));

    private static final Map<String, MetricAdsCatalog> BY_NAME =
            ALL.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(MetricAdsCatalog::name, t -> t));

    /** 非 snapshot_id/dt 的主键列名（供 SELECT ... ORDER BY 稳定取一行） */
    public List<String> orderColumns() {
        return keyColumns;
    }

    /** 校验并返回表清单；表名不在白名单 → IllegalArgumentException（不拼接任何 SQL） */
    public static MetricAdsCatalog require(String table) {
        MetricAdsCatalog spec = table == null ? null : BY_NAME.get(table);
        if (spec == null) {
            throw new IllegalArgumentException("非法表名（不在 ADS 白名单）: " + table);
        }
        return spec;
    }
}
