package com.graduation.analytics.ai;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务语义层（§8.2/§8.3）：AI 可查询的 ADS 白名单表、字段语义、中文别名、
 * 指标口径与少样本。Schema 选择 = 关键词 → 主题 → 1-3 张表（不引入向量库）。
 */
@Component
public class SemanticCatalog {

    /** 表 → 字段语义 */
    public static final Map<String, Map<String, String>> TABLES = new LinkedHashMap<>();

    /** 中文别名 → 字段（用于拼音/同义词匹配） */
    public static final Map<String, String> FIELD_ALIASES = new LinkedHashMap<>();

    /** 少样本示例（每表 2 条） */
    public static final Map<String, List<String>> FEW_SHOTS = new LinkedHashMap<>();

    /** 锁定"最新已发布快照"的 WHERE 片段（ADS 服务表按快照保留历史，同 dt 会有多快照行） */
    public static String snapshotPin(String table) {
        return "snapshot_id = (SELECT MAX(snapshot_id) FROM " + table + ")";
    }

    static {
        // 字段集合必须与 platform-app/src/main/resources/db/metric/V*.sql 一致：
        // AiSqlDriftTest（R7-4）常驻校验，任何一方漂移即红。
        // snapshot_id 必须进 Prompt：ADS 服务表按快照保留历史（同 dt 同时存在归档 S..._23 与生效 S..._24），
        // AI 查询若不 pin 快照就会跨快照串数（LIMIT 1 甚至可能返回归档快照的旧口径值）。
        Map<String, String> overview = new LinkedHashMap<>();
        overview.put("snapshot_id", "发布快照 ID：查询必须锁定最新已发布快照，避免跨快照串数");
        overview.put("dt", "业务日期 yyyy-MM-dd");
        overview.put("pv", "浏览量：当日 view 行为次数");
        overview.put("uv", "浏览用户数：当日去重浏览用户");
        overview.put("dau", "日活跃用户数：当日任一有效行为去重用户");
        overview.put("order_count", "支付订单数");
        overview.put("sale_amount", "销售额(GMV)：有效支付订单金额合计，单位元，可求和");
        overview.put("net_sale_amount", "净销售额：销售额减退款金额，单位元");
        overview.put("avg_order_value", "客单价：销售额 ÷ 支付订单数；不可直接对明细求平均");
        overview.put("refund_rate", "退款率：退款订单数 ÷ 支付订单数");
        overview.put("full_refund_rate", "全额退款率：全额退款订单数 ÷ 支付订单数");
        TABLES.put("ads_operation_overview_m", overview);

        Map<String, String> saleTrend = new LinkedHashMap<>();
        saleTrend.put("snapshot_id", "发布快照 ID：查询必须锁定最新已发布快照，避免跨快照串数");
        saleTrend.put("dt", "业务日期 yyyy-MM-dd");
        saleTrend.put("order_count", "支付订单数");
        saleTrend.put("buyer_count", "支付用户数（去重）");
        saleTrend.put("sale_amount", "销售额(GMV)，单位元");
        saleTrend.put("avg_order_value", "客单价");
        TABLES.put("ads_sale_trend_m", saleTrend);

        Map<String, String> funnel = new LinkedHashMap<>();
        funnel.put("snapshot_id", "发布快照 ID：查询必须锁定最新已发布快照，避免跨快照串数");
        funnel.put("dt", "业务日期 yyyy-MM-dd");
        funnel.put("stage", "漏斗阶段：view/intent/order/pay");
        funnel.put("user_count", "该阶段去重用户数");
        funnel.put("conversion_rate", "阶段转化率（后一阶段/前一阶段，0-1）");
        funnel.put("overall_buy_rate", "整体购买转化率：pay 阶段 ÷ view 阶段");
        TABLES.put("ads_behavior_funnel_m", funnel);

        Map<String, String> hotProduct = new LinkedHashMap<>();
        hotProduct.put("snapshot_id", "发布快照 ID：查询必须锁定最新已发布快照，避免跨快照串数");
        hotProduct.put("dt", "业务日期 yyyy-MM-dd");
        hotProduct.put("product_id", "商品编号");
        hotProduct.put("product_name", "商品名称");
        hotProduct.put("heat_score", "热度分：浏览/收藏/加购/购买加权得分，可排序");
        hotProduct.put("pv", "该商品浏览量");
        hotProduct.put("fav", "该商品收藏次数");
        hotProduct.put("cart", "该商品加购次数");
        hotProduct.put("buy", "该商品购买次数");
        hotProduct.put("rank_no", "热度排名，1 为最高");
        TABLES.put("ads_hot_product_m", hotProduct);

        Map<String, String> productConversion = new LinkedHashMap<>();
        productConversion.put("snapshot_id", "发布快照 ID：查询必须锁定最新已发布快照，避免跨快照串数");
        productConversion.put("dt", "业务日期 yyyy-MM-dd");
        productConversion.put("product_id", "商品编号");
        productConversion.put("pv_users", "浏览该商品的用户数");
        productConversion.put("buy_users", "购买该商品的用户数");
        productConversion.put("conversion_rate", "商品转化率：购买用户数 ÷ 浏览用户数");
        TABLES.put("ads_product_conversion_m", productConversion);

        // 中文/别名 → 标准字段
        FIELD_ALIASES.put("销售额", "sale_amount");
        FIELD_ALIASES.put("gmv", "sale_amount");
        FIELD_ALIASES.put("收入", "sale_amount");
        FIELD_ALIASES.put("净销售额", "net_sale_amount");
        FIELD_ALIASES.put("客单价", "avg_order_value");
        FIELD_ALIASES.put("订单数", "order_count");
        FIELD_ALIASES.put("支付订单数", "order_count");
        FIELD_ALIASES.put("买家数", "buyer_count");
        FIELD_ALIASES.put("活跃用户", "dau");
        FIELD_ALIASES.put("浏览量", "pv");
        FIELD_ALIASES.put("访问量", "pv");
        FIELD_ALIASES.put("退款率", "refund_rate");
        FIELD_ALIASES.put("转化率", "conversion_rate");
        FIELD_ALIASES.put("漏斗", "stage");
        FIELD_ALIASES.put("热度", "heat_score");
        FIELD_ALIASES.put("热度分", "heat_score");
        FIELD_ALIASES.put("热销商品", "heat_score");
        FIELD_ALIASES.put("商品排行", "rank_no");
        FIELD_ALIASES.put("排名", "rank_no");
        FIELD_ALIASES.put("趋势", "dt");
        FIELD_ALIASES.put("最近7天", "dt");
        FIELD_ALIASES.put("近7日", "dt");
        FIELD_ALIASES.put("本周", "dt");
        FIELD_ALIASES.put("昨日", "dt");

        // 少样本一律以 snapshotPin(TABLE) 开头，示范"先锁快照再筛日期"
        FEW_SHOTS.put("ads_sale_trend_m", List.of(
                "问：最近7天销售额趋势？答：SELECT dt, sale_amount, order_count FROM ads_sale_trend_m WHERE " + snapshotPin("ads_sale_trend_m") + " AND dt >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) ORDER BY dt DESC LIMIT 7",
                "问：昨日客单价是多少？答：SELECT dt, avg_order_value FROM ads_sale_trend_m WHERE " + snapshotPin("ads_sale_trend_m") + " AND dt = DATE_SUB(CURDATE(), INTERVAL 1 DAY) LIMIT 1",
                "问：9月4日销售额？答：SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + snapshotPin("ads_sale_trend_m") + " AND dt = '2026-09-04' LIMIT 1"));

        FEW_SHOTS.put("ads_operation_overview_m", List.of(
                "问：最近一天的销售额和退款率？答：SELECT dt, sale_amount, net_sale_amount, refund_rate FROM ads_operation_overview_m WHERE " + snapshotPin("ads_operation_overview_m") + " AND dt = (SELECT MAX(dt) FROM ads_operation_overview_m) LIMIT 1",
                "问：每天的活跃用户数？答：SELECT dt, dau FROM ads_operation_overview_m WHERE " + snapshotPin("ads_operation_overview_m") + " AND dt >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) ORDER BY dt DESC LIMIT 7",
                "问：9月4日大盘？答：SELECT dt, pv, uv, dau, sale_amount FROM ads_operation_overview_m WHERE " + snapshotPin("ads_operation_overview_m") + " AND dt = '2026-09-04' LIMIT 1"));

        FEW_SHOTS.put("ads_behavior_funnel_m", List.of(
                "问：9月4日转化漏斗？答：SELECT dt, stage, user_count, conversion_rate FROM ads_behavior_funnel_m WHERE " + snapshotPin("ads_behavior_funnel_m") + " AND dt = '2026-09-04' ORDER BY FIELD(stage,'view','intent','order','pay') LIMIT 4",
                "问：最近一天各漏斗阶段人数？答：SELECT stage, user_count FROM ads_behavior_funnel_m WHERE " + snapshotPin("ads_behavior_funnel_m") + " AND dt = '2026-09-04' LIMIT 4",
                "问：9月4日支付人数？答：SELECT user_count FROM ads_behavior_funnel_m WHERE " + snapshotPin("ads_behavior_funnel_m") + " AND dt = '2026-09-04' AND stage = 'pay' LIMIT 1"));

        FEW_SHOTS.put("ads_hot_product_m", List.of(
                "问：热度最高的商品有哪些？答：SELECT dt, product_id, product_name, heat_score, rank_no FROM ads_hot_product_m WHERE " + snapshotPin("ads_hot_product_m") + " AND dt = (SELECT MAX(dt) FROM ads_hot_product_m) ORDER BY rank_no LIMIT 10",
                "问：最近一天商品排行？答：SELECT product_name, pv, fav, cart, buy, heat_score FROM ads_hot_product_m WHERE " + snapshotPin("ads_hot_product_m") + " AND dt = (SELECT MAX(dt) FROM ads_hot_product_m) ORDER BY rank_no LIMIT 10"));

        FEW_SHOTS.put("ads_product_conversion_m", List.of(
                "问：商品转化率是多少？答：SELECT dt, product_id, pv_users, buy_users, conversion_rate FROM ads_product_conversion_m WHERE " + snapshotPin("ads_product_conversion_m") + " AND dt = (SELECT MAX(dt) FROM ads_product_conversion_m) ORDER BY conversion_rate DESC LIMIT 10"));
    }

    /** 主题关键词 → 表 */
    private static final Map<String, List<String>> TOPIC_TABLES = Map.ofEntries(
            Map.entry("销售", List.of("ads_sale_trend_m")),
            Map.entry("销售额", List.of("ads_sale_trend_m")),
            Map.entry("订单", List.of("ads_sale_trend_m")),
            Map.entry("客单价", List.of("ads_sale_trend_m")),
            Map.entry("收入", List.of("ads_sale_trend_m")),
            Map.entry("大盘", List.of("ads_operation_overview_m")),
            Map.entry("运营", List.of("ads_operation_overview_m")),
            Map.entry("gmv", List.of("ads_operation_overview_m", "ads_sale_trend_m")),
            Map.entry("活跃", List.of("ads_operation_overview_m")),
            Map.entry("浏览量", List.of("ads_operation_overview_m")),
            Map.entry("访问", List.of("ads_operation_overview_m")),
            Map.entry("退款", List.of("ads_operation_overview_m")),
            Map.entry("漏斗", List.of("ads_behavior_funnel_m")),
            Map.entry("转化", List.of("ads_behavior_funnel_m")),
            Map.entry("商品", List.of("ads_hot_product_m", "ads_product_conversion_m")),
            Map.entry("排行", List.of("ads_hot_product_m")),
            Map.entry("热度", List.of("ads_hot_product_m")),
            Map.entry("用户数", List.of("ads_behavior_funnel_m", "ads_operation_overview_m")));

    /** 主题识别：关键词命中 → 去重表列表（§8.3：1-3 张） */
    public List<String> selectTables(String question) {
        String q = question == null ? "" : question.toLowerCase();
        Set<String> tables = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, List<String>> e : TOPIC_TABLES.entrySet()) {
            if (q.contains(e.getKey().toLowerCase())) {
                tables.addAll(e.getValue());
            }
        }
        if (tables.isEmpty()) {
            tables.add("ads_sale_trend_m"); // 默认主题兜底
        }
        return tables.stream().limit(3).toList();
    }

    /** 字段是否存在（白名单：表 + 字段） */
    public boolean fieldExists(String table, String column) {
        return TABLES.getOrDefault(table, Map.of()).containsKey(column);
    }

    public boolean tableWhitelisted(String table) {
        return TABLES.containsKey(table);
    }

    /** 组装 Schema JSON（表+字段语义+少样本），用于 Prompt */
    public String schemaJson(List<String> tables) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String t : tables) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append('"').append(t).append("\":{");
            boolean f = true;
            for (Map.Entry<String, String> col : TABLES.get(t).entrySet()) {
                if (!f) {
                    sb.append(",");
                }
                f = false;
                sb.append('"').append(col.getKey()).append("\":\"").append(col.getValue()).append('"');
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    public String fewShots(List<String> tables) {
        StringBuilder sb = new StringBuilder();
        for (String t : tables) {
            for (String shot : FEW_SHOTS.getOrDefault(t, List.of())) {
                sb.append(shot).append("\n");
            }
        }
        return sb.toString();
    }
}