package com.graduation.analytics.ai;

import com.graduation.analytics.ai.sql.AiScope;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 业务语义层（§19.4/§24.8）：AI 可查询的已发布 ADS 白名单表、字段语义、中文别名、指标口径与少样本。
 * Schema 选择 = 关键词 → 主题 → 1-3 张表（不引入向量库）。
 *
 * <p>反熵（R8-2 契约 §2.2）：**删除 {@code snapshotPin()} 的 {@code (SELECT MAX(snapshot_id) ...)} 子查询**。
 * 原实现让 SQL 自锁「最新快照」，与「日期与快照必须参数化注入」直接冲突：
 * ① 子查询本身违反第一阶段「禁子查询」；② {@code MAX(dt)} 会把归档快照的同名日期一起纳入范围。
 * 现在改为：{@link com.graduation.analytics.ai.sql.AiScopeResolver} 先取 ACTIVE 快照，
 * 再由 {@link #fewShots(List, AiScope)} 把 {@code snapshot_id} / {@code dt} 作为**字面量**写进少样本，
 * 校验器用同一份 scope 反向核对（{@link com.graduation.analytics.ai.sql.SqlSafetyValidator}）。</p>
 */
@Component
public class SemanticCatalog {

    /** 表 → 字段语义 */
    public static final Map<String, Map<String, String>> TABLES = new LinkedHashMap<>();

    /** 中文别名 → 字段（用于拼音/同义词匹配） */
    public static final Map<String, String> FIELD_ALIASES = new LinkedHashMap<>();

    /**
     * 少样本示例（每表 2-3 条），一律为**参数化字面量**形态：
     * 里面的 {@code 'S20260901_24'} / {@code '20260904'} 是模板占位值，
     * 真正的运行时值由 {@link #fewShots(List, AiScope)} 按 ACTIVE 快照替换后送进提示词。
     */
    public static final Map<String, List<String>> FEW_SHOTS = new LinkedHashMap<>();

    /** 日期语义列（值形如 yyyyMMdd 紧凑格式的 VARCHAR，见 AiScope.DT_FORMAT）：校验器据此区分日期字面量与普通字符串字面量 */
    public static final Set<String> DATE_COLUMNS = Set.of("dt", "last_active_date", "last_buy_date", "calc_date");

    /** 模板占位业务日：仅在 {@link #FEW_SHOTS} 静态文本里出现，运行时由 scope 覆盖 */
    static final String TEMPLATE_DATE = "20260904";

    /** 模板占位快照号：同上 */
    static final String TEMPLATE_SNAPSHOT = "S20260901_24";

    /** 契约 §2.3：WHERE 必须含 {@code snapshot_id = '<ACTIVE>'}（字面量钉住快照，不用子查询） */
    public static String snapshotPin(AiScope scope) {
        return "snapshot_id = '" + scope.snapshotId() + "'";
    }

    /** 契约 §2.3：WHERE 必须含 {@code dt >= '<min>' AND dt <= '<max>'} 字面量范围（紧凑 yyyyMMdd，见 AiScope.DT_FORMAT） */
    public static String dateRangePin(AiScope scope) {
        return "dt >= '" + scope.dtFrom() + "' AND dt <= '" + scope.dtTo() + "'";
    }

    static {
        // 字段集合必须与 platform-app/src/main/resources/db/metric/V*.sql 一致：
        // AiSqlDriftTest（R7-4）常驻校验，任何一方漂移即红。
        // snapshot_id 必须进 Prompt：ADS 服务表按快照保留历史（同 dt 同时存在归档 S..._23 与生效 S..._24），
        // AI 查询若不 pin 快照就会跨快照串数，但 pin 的方式是**字面量**而非子查询（R8-2）。
        Map<String, String> overview = new LinkedHashMap<>();
        overview.put("snapshot_id", "发布快照 ID：查询必须写成 snapshot_id = '<ACTIVE 快照号>' 字面量，禁止子查询");
        overview.put("dt", "业务日期 yyyyMMdd 紧凑格式（如 20260901）：必须写成 dt >= '<起始>' AND dt <= '<结束>' 字面量范围");
        overview.put("pv", "浏览量：当日 view 行为次数");
        overview.put("uv", "浏览用户数：当日去重浏览用户");
        overview.put("dau", "日活跃用户数：当日任一有效行为去重用户");
        overview.put("order_count", "支付订单数");
        overview.put("sale_amount", "销售额(GMV)：有效支付订单金额合计，单位元，可求和");
        overview.put("net_sale_amount", "净销售额：销售额减退款金额，单位元");
        overview.put("avg_order_value", "客单价：销售额 ÷ 支付订单数；不可直接对明细求平均");
        overview.put("refund_rate", "退款率：退款订单数 ÷ 支付订单数");
        overview.put("full_refund_rate", "全额退款率：全额退款订单数 ÷ 支付订单数");
        overview.put("repeat_rate", "有效复购率：观察期内有效购买≥2次用户数 ÷ 支付用户数（完全退款订单不算有效购买）；"
                + "与下面两列一起构成观察期声明，跨窗口不可直接比较；无支付用户时为 NULL");
        overview.put("repeat_period_start", "复购率的观察期起点（ISO yyyy-MM-dd，来自上游 DWS 行声明）");
        overview.put("repeat_period_end", "复购率的观察期终点（ISO yyyy-MM-dd，来自上游 DWS 行声明）");
        TABLES.put("ads_operation_overview_m", overview);

        Map<String, String> saleTrend = new LinkedHashMap<>();
        saleTrend.put("snapshot_id", "发布快照 ID：查询必须写成 snapshot_id = '<ACTIVE 快照号>' 字面量，禁止子查询");
        saleTrend.put("dt", "业务日期 yyyyMMdd 紧凑格式（如 20260901）：必须写成 dt >= '<起始>' AND dt <= '<结束>' 字面量范围");
        saleTrend.put("order_count", "支付订单数");
        saleTrend.put("buyer_count", "支付用户数（去重）");
        saleTrend.put("sale_amount", "销售额(GMV)，单位元");
        saleTrend.put("avg_order_value", "客单价");
        // S3-02（设计 §9.3 L333 / §11.2 L428）：趋势表补净销售额，与大盘表同义同口径，
        // 使「净销售额」字段别名在本表也可解析（此前只有大盘表有该列）。
        saleTrend.put("net_sale_amount", "净销售额：销售额减退款金额（只扣已支付订单的退款），单位元");
        TABLES.put("ads_sale_trend_m", saleTrend);

        Map<String, String> funnel = new LinkedHashMap<>();
        funnel.put("snapshot_id", "发布快照 ID：查询必须写成 snapshot_id = '<ACTIVE 快照号>' 字面量，禁止子查询");
        funnel.put("dt", "业务日期 yyyyMMdd 紧凑格式（如 20260901）：必须写成 dt >= '<起始>' AND dt <= '<结束>' 字面量范围");
        funnel.put("stage", "漏斗阶段：view/intent/order/pay");
        funnel.put("user_count", "该阶段去重用户数");
        funnel.put("conversion_rate", "阶段转化率（后一阶段/前一阶段，0-1）");
        funnel.put("overall_buy_rate", "整体购买转化率：pay 阶段 ÷ view 阶段");
        // S3-04（设计 §11.2 L432 / 字典 metric-dictionary.md:23）：整体加购率，与上面的整体购买率同型。
        // 加购不是漏斗阶段（§11.3 L441 恒为 view/intent/order/pay），故它是列而不是 stage 取值。
        funnel.put("overall_cart_rate", "整体加购率：cart_add 去重用户数 ÷ view 去重用户数（四行同值；浏览为 0 时为 NULL）");
        TABLES.put("ads_behavior_funnel_m", funnel);

        Map<String, String> hotProduct = new LinkedHashMap<>();
        hotProduct.put("snapshot_id", "发布快照 ID：查询必须写成 snapshot_id = '<ACTIVE 快照号>' 字面量，禁止子查询");
        hotProduct.put("dt", "业务日期 yyyyMMdd 紧凑格式（如 20260901）：必须写成 dt >= '<起始>' AND dt <= '<结束>' 字面量范围");
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
        productConversion.put("snapshot_id", "发布快照 ID：查询必须写成 snapshot_id = '<ACTIVE 快照号>' 字面量，禁止子查询");
        productConversion.put("dt", "业务日期 yyyyMMdd 紧凑格式（如 20260901）：必须写成 dt >= '<起始>' AND dt <= '<结束>' 字面量范围");
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

        // ── 少样本：快照与日期一律字面量（R8-2 契约 §2.2） ─────────────────────
        // 禁止出现 (SELECT MAX(...) ...)、DATE_SUB(CURDATE(),...)、FIELD()：前者是子查询，
        // 中者绕过「日期必须落在 scope 内」，后者不在函数白名单（§2.3）。
        String saleTrendSnap = "snapshot_id = '" + TEMPLATE_SNAPSHOT + "'";
        FEW_SHOTS.put("ads_sale_trend_m", List.of(
                "问：最近7天销售额趋势？答：SELECT dt, sale_amount, order_count FROM ads_sale_trend_m WHERE "
                        + saleTrendSnap + " AND dt >= '20260829' AND dt <= '" + TEMPLATE_DATE
                        + "' ORDER BY dt LIMIT 7",
                "问：昨日客单价是多少？答：SELECT dt, avg_order_value FROM ads_sale_trend_m WHERE "
                        + saleTrendSnap + " AND dt >= '" + TEMPLATE_DATE + "' AND dt <= '" + TEMPLATE_DATE + "' LIMIT 1",
                "问：9月4日销售额？答：SELECT dt, sale_amount FROM ads_sale_trend_m WHERE "
                        + saleTrendSnap + " AND dt >= '" + TEMPLATE_DATE + "' AND dt <= '" + TEMPLATE_DATE + "' LIMIT 1"));

        String overviewSnap = "snapshot_id = '" + TEMPLATE_SNAPSHOT + "'";
        FEW_SHOTS.put("ads_operation_overview_m", List.of(
                "问：最近一天的销售额和退款率？答：SELECT dt, sale_amount, net_sale_amount, refund_rate "
                        + "FROM ads_operation_overview_m WHERE " + overviewSnap + " AND dt >= '" + TEMPLATE_DATE
                        + "' AND dt <= '" + TEMPLATE_DATE + "' LIMIT 1",
                "问：每天的活跃用户数？答：SELECT dt, dau FROM ads_operation_overview_m WHERE " + overviewSnap
                        + " AND dt >= '20260829' AND dt <= '" + TEMPLATE_DATE + "' ORDER BY dt LIMIT 7",
                "问：9月4日大盘？答：SELECT dt, pv, uv, dau, sale_amount FROM ads_operation_overview_m WHERE "
                        + overviewSnap + " AND dt >= '" + TEMPLATE_DATE + "' AND dt <= '" + TEMPLATE_DATE + "' LIMIT 1"));

        String funnelSnap = "snapshot_id = '" + TEMPLATE_SNAPSHOT + "'";
        FEW_SHOTS.put("ads_behavior_funnel_m", List.of(
                "问：9月4日转化漏斗？答：SELECT dt, stage, user_count, conversion_rate, overall_buy_rate "
                        + "FROM ads_behavior_funnel_m WHERE " + funnelSnap + " AND dt >= '" + TEMPLATE_DATE
                        + "' AND dt <= '" + TEMPLATE_DATE + "' ORDER BY stage LIMIT 4",
                "问：9月4日支付人数？答：SELECT dt, stage, user_count FROM ads_behavior_funnel_m WHERE "
                        + funnelSnap + " AND dt >= '" + TEMPLATE_DATE + "' AND dt <= '" + TEMPLATE_DATE
                        + "' AND stage = 'pay' LIMIT 1"));

        String hotSnap = "snapshot_id = '" + TEMPLATE_SNAPSHOT + "'";
        FEW_SHOTS.put("ads_hot_product_m", List.of(
                "问：热度最高的商品有哪些？答：SELECT dt, product_id, product_name, heat_score, rank_no "
                        + "FROM ads_hot_product_m WHERE " + hotSnap + " AND dt >= '" + TEMPLATE_DATE
                        + "' AND dt <= '" + TEMPLATE_DATE + "' ORDER BY rank_no LIMIT 10",
                "问：最近一天商品排行？答：SELECT dt, product_name, pv, fav, cart, buy, heat_score, rank_no "
                        + "FROM ads_hot_product_m WHERE " + hotSnap + " AND dt >= '" + TEMPLATE_DATE
                        + "' AND dt <= '" + TEMPLATE_DATE + "' ORDER BY rank_no LIMIT 10"));

        String convSnap = "snapshot_id = '" + TEMPLATE_SNAPSHOT + "'";
        FEW_SHOTS.put("ads_product_conversion_m", List.of(
                "问：商品转化率是多少？答：SELECT dt, product_id, pv_users, buy_users, conversion_rate "
                        + "FROM ads_product_conversion_m WHERE " + convSnap + " AND dt >= '" + TEMPLATE_DATE
                        + "' AND dt <= '" + TEMPLATE_DATE + "' ORDER BY conversion_rate DESC LIMIT 10"));
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

    /** 主题识别：关键词命中 → 去重表列表 */
    public List<String> selectTables(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        Set<String> tables = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, List<String>> e : TOPIC_TABLES.entrySet()) {
            if (q.contains(e.getKey().toLowerCase(Locale.ROOT))) {
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

    /** 组装 Schema JSON（表+字段语义），用于 Prompt */
    public String schemaJson(List<String> tables) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String t : tables) {
            if (!TABLES.containsKey(t)) {
                continue;
            }
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

    /**
     * 少样本模板（占位字面量，不依赖数据库）：由 {@link #FEW_SHOTS} 拼装。
     * 供离线漂移守卫（AiSqlDriftTest）与无 scope 场景使用。
     */
    public String fewShots(List<String> tables) {
        StringBuilder sb = new StringBuilder();
        for (String t : tables) {
            for (String shot : FEW_SHOTS.getOrDefault(t, List.of())) {
                sb.append(shot).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 少样本（把 ACTIVE 快照/业务日作为**真实字面量**注入，R8-2 契约 §2.2）：
     * 先取允许日期范围 {@code [businessDate-89, businessDate]}，把模板里的占位快照号与占位日期整体替换，
     * 模型看到的就是「本环境当前生效」的快照与日期，不需要（也不允许）自己写 MAX 子查询。
     */
    public String fewShots(List<String> tables, AiScope scope) {
        String raw = fewShots(tables);
        if (scope == null || scope.snapshotId() == null || scope.businessDate() == null) {
            return raw;
        }
        return parameterize(raw, scope);
    }

    /**
     * 模板参数化：占位快照 → ACTIVE 快照号；占位日期 → 业务日，并把模板中的
     * 「业务日 - 6 天」一并平移（占位业务日固定为 {@link #TEMPLATE_DATE}，紧凑 yyyyMMdd）。
     */
    static String parameterize(String template, AiScope scope) {
        String shifted = AiScope.dt(LocalDate.parse(TEMPLATE_DATE, AiScope.DT_FORMAT).minusDays(6));
        return template
                .replace(TEMPLATE_SNAPSHOT, scope.snapshotId())
                .replace(shifted, AiScope.dt(scope.businessDate().minusDays(6)))
                .replace(TEMPLATE_DATE, scope.dtTo());
    }
}
