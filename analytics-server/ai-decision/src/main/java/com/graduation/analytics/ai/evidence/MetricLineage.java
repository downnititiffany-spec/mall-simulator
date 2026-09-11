package com.graduation.analytics.ai.evidence;

import com.graduation.analytics.warehouse.WarehouseNamespace;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标血缘映射（§19.2 lineage 的取值来源，口径以 {@code docs/contracts/metric-lineage.md} 为准）。
 *
 * <p>反向熵：血缘关系**只在这里登记一次**，证据包按实际取到的指标码反查，
 * 不在业务代码里散落表名常量，也不允许出现「映射表里没有的表」。</p>
 *
 * <p>P1-04：本表只登记 **ADS 层内的表名**与 MySQL 镜像表名，**不含库名**——
 * 库名由唯一所有者 {@link WarehouseNamespace} 在 {@link #hiveTables} 处一次性拼出，
 * 因此换源（不同 {@code hive_database_prefix}）时血缘自动跟随，无需改表。</p>
 */
public final class MetricLineage {

    private MetricLineage() {
    }

    /** 单条血缘：Hive ADS 表（**层内表名**，库名由命名空间拼）→ MySQL ADS 表 */
    public record Edge(String hiveTableName, String mysqlTable) {

        /** 完整 Hive 限定名（{@code <ns.ads()>.<表名>}） */
        public String hiveTable(WarehouseNamespace namespace) {
            if (namespace == null) {
                throw new IllegalArgumentException("WarehouseNamespace 不能为空（血缘库名必须由唯一所有者派生）");
            }
            return namespace.ads() + "." + hiveTableName;
        }
    }

    private static final Edge OVERVIEW =
            new Edge("ads_operation_overview", "ads_operation_overview_m");
    private static final Edge SALE_TREND =
            new Edge("ads_sale_trend", "ads_sale_trend_m");
    private static final Edge BEHAVIOR_FUNNEL =
            new Edge("ads_behavior_funnel", "ads_behavior_funnel_m");
    private static final Edge ACTIVE_TREND =
            new Edge("ads_active_trend", "ads_active_trend_m");
    private static final Edge HOT_PRODUCT =
            new Edge("ads_hot_product", "ads_hot_product_m");
    private static final Edge PRODUCT_CONVERSION =
            new Edge("ads_product_conversion", "ads_product_conversion_m");
    private static final Edge USER_PROFILE =
            new Edge("ads_user_profile", "ads_user_profile_m");
    private static final Edge DATA_QUALITY =
            new Edge("ads_data_quality", "ads_data_quality_m");

    /** 指标码 → 血缘边（按 metric-lineage.md 的 #1–#12） */
    private static final Map<String, Edge> BY_METRIC_CODE = Map.ofEntries(
            Map.entry("pv", OVERVIEW),
            Map.entry("uv", OVERVIEW),
            Map.entry("dau", OVERVIEW),
            Map.entry("paid_order_cnt", OVERVIEW),
            Map.entry("gmv", OVERVIEW),
            Map.entry("net_sale", OVERVIEW),
            Map.entry("avg_order_value", OVERVIEW),
            Map.entry("refund_rate", OVERVIEW),
            Map.entry("full_refund_rate", OVERVIEW),
            Map.entry("buy_rate", BEHAVIOR_FUNNEL),
            Map.entry("product_heat", HOT_PRODUCT),
            Map.entry("user_value_level", USER_PROFILE));

    /** 指标码集合的 MySQL ADS 表（去重 + 保持稳定顺序）；未登记的码不收表名，避免编造血缘 */
    public static List<String> mysqlTables(Iterable<String> metricCodes) {
        Set<String> tables = new LinkedHashSet<>();
        if (metricCodes != null) {
            for (String code : metricCodes) {
                Edge edge = code == null ? null : BY_METRIC_CODE.get(code);
                if (edge != null) {
                    tables.add(edge.mysqlTable());
                }
            }
        }
        return List.copyOf(tables);
    }

    /** MySQL ADS 表 → Hive ADS 限定表名（同一批表名的镜像；未登记的表名原样不返回） */
    public static List<String> hiveTables(Iterable<String> mysqlTables, WarehouseNamespace namespace) {
        Set<String> tables = new LinkedHashSet<>();
        if (mysqlTables != null) {
            for (String mysql : mysqlTables) {
                BY_METRIC_CODE.values().stream()
                        .filter(edge -> edge.mysqlTable().equals(mysql))
                        .findFirst()
                        .ifPresent(edge -> tables.add(edge.hiveTable(namespace)));
            }
        }
        return List.copyOf(tables);
    }

    /** 质量卡血缘：ads_data_quality → ads_data_quality_m（无论指标码如何都算读到） */
    public static Edge dataQuality() {
        return DATA_QUALITY;
    }

    /** 销售趋势/商品转化血缘（维度贡献读取用） */
    public static Edge saleTrend() {
        return SALE_TREND;
    }

    public static Edge activeTrend() {
        return ACTIVE_TREND;
    }

    public static Edge hotProduct() {
        return HOT_PRODUCT;
    }

    public static Edge productConversion() {
        return PRODUCT_CONVERSION;
    }
}
