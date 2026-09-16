package com.graduation.analytics.analysis;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.metric.MetricAdsReader;
import com.graduation.analytics.metric.MetricQualityGate;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.MySqlMetricStore;
import com.graduation.analytics.metric.dict.MetricDefinition;
import com.graduation.analytics.metric.dict.MetricDefinitionMapper;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 专题分析服务（R7-4 契约 docs/contracts/analysis-viewmodel-r7-4.md，指导书 §18.1/§18.2/§24.6）。
 *
 * <p>数据来源唯一：**指标库只读源**。数值指标取 {@code metric_value}（§3.1/§3.2 明确"不得重算"），
 * 图表语义数据取 MetricAdsCatalog 白名单内的 ADS 宽表。R7-4 之前的旧原型（按落地区事件 JSON 实时聚合）
 * 已整体删除：分析包内不读 JSON 文件、不查商城业务表（订单/商品等明细表）、不在本层重算口径（§18.1）。</p>
 *
 * <p>快照一致性：一次请求只解析一个 snapshotId（请求指定优先，否则取 ACTIVE），
 * 之后所有查询都带着它，并在响应信封里回显（契约 §1.2，§17.5 第 8 步）。</p>
 *
 * <p>{@code from/to}、{@code topN} 只作为筛选回显：ADS 宽表本身就是"快照 + dt"粒度的物化结果，
 * 服务端不按事件时间重算，因此它们不参与计算（契约 §1.3 不允许回退到明细层）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    // ── 指标库 ADS 表名（只允许 MetricAdsCatalog 白名单内的表） ────────────────
    private static final String T_SALE_TREND = "ads_sale_trend_m";
    private static final String T_FUNNEL = "ads_behavior_funnel_m";
    private static final String T_ACTIVE_TREND = "ads_active_trend_m";
    private static final String T_HOT_PRODUCT = "ads_hot_product_m";
    private static final String T_PRODUCT_CONVERSION = "ads_product_conversion_m";
    private static final String T_DATA_QUALITY = "ads_data_quality_m";

    // ── 销售分析取值的指标码（一律取 metric_value，不重算，契约 §3.2） ──────────
    private static final String METRIC_GMV = "gmv";
    private static final String METRIC_NET_SALE = "net_sale";
    private static final String METRIC_REFUND_RATE = "refund_rate";
    private static final String METRIC_FULL_REFUND_RATE = "full_refund_rate";

    /** 漏斗阶段规范顺序与中文标签（页面必须显示阶段语义，§18.2 用户行为） */
    private static final List<String> FUNNEL_ORDER = List.of("view", "intent", "order", "pay");
    private static final Map<String, String> FUNNEL_LABELS = Map.of(
            "view", "浏览", "intent", "意向", "order", "下单", "pay", "支付");

    private static final int DEFAULT_TOP_N = 10;
    private static final int MAX_TOP_N = 100;

    /**
     * v1.3（S3-18）：分页窗口上限。与 {@link #MAX_TOP_N} **同值且刻意共用**——旧参数 `topN` 已退化为
     * 「窗口大小」，两个上限若各写一个字面量迟早漂移；这里只留一个所有者。
     */
    private static final int MAX_PAGE_SIZE = MAX_TOP_N;

    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final MetricAdsReader adsReader;
    private final MySqlMetricStore metricStore;
    private final MetricQualityGate qualityGate;
    private final MetricDefinitionMapper definitionMapper;
    private final RfmService rfmService;

    // ── 端点数据结构（图表语义数据，不是 ECharts option） ──────────────────────

    public record MetricItem(String metricCode, String metricName, BigDecimal value, String unit,
                             String period, String definitionVersion) {
    }

    public record DictionaryItem(String metricCode, String metricName, String formula, String unit) {
    }

    /** 质量卡：规则条数 / 通过条数 / 未通过规则码（营业页质量状态的可展开明细） */
    public record QualitySummary(int ruleCount, int passedCount, List<String> failedRules) {
    }

    /**
     * 销售趋势点（契约 §3.1/§3.2）。
     *
     * <p>v1.4（S3-20）加性补 {@code netSaleAmount}：设计 §9.3 **L333**「历史已发布，**net_sale 等字段需补**」
     * （该条原文以 Hive 侧表名开头；本包源码策略测试禁止白名单外的 ADS 裸表名，故此处不引该名）
     * ＋ §11.2 **L428**「净销售 = 同口径支付金额 − 成功退款金额；真实收入方向，
     * 退款归属期需冻结」。值 = ADS 列 `net_sale_amount` **原样透传**（口径所有者仍是 DWS/ADS，
     * 服务层不重算），与 {@code saleAmount} 同一行、同一 dt。
     *
     * <p><b>口径边界（不得越界表述）</b>：① 跨业务日到账的退款**不会**回改历史业务日的净额
     * （L428「退款归属期需冻结」尚未裁决，见 `PROJECT_STATUS` backlog），故本值**不等于**"最终到账净收入"；
     * ② 历史快照该列由加性迁移的 `NOT NULL DEFAULT 0` 回填 ⇒ **0 可能是"未计算"占位**，
     * 与"当日零净额"在当前数据上**不可区分**（不得据此判断"无退款"）；列整体缺失/畸形值 ⇒ {@code null}
     * （不臆造 0）。
     */
    public record SalesTrendPoint(String date, long orderCount, BigDecimal saleAmount, long buyerCount,
                                  BigDecimal avgOrderValue, BigDecimal netSaleAmount) {
    }

    public record ActiveDay(String date, long dau, long behaviorCount) {
    }

    public record OverviewData(List<MetricItem> metrics, List<SalesTrendPoint> salesTrend,
                               List<ActiveDay> activeTrend, QualitySummary quality,
                               List<DictionaryItem> metricDictionary) {
    }

    public record SalesData(List<SalesTrendPoint> trend, BigDecimal gmv, BigDecimal netSale,
                            BigDecimal refundRate, BigDecimal fullRefundRate, QualitySummary quality,
                            List<Map<String, Object>> byCategory, List<Map<String, Object>> byRegion) {
    }

    public record HotProduct(long productId, String productName, BigDecimal heat, long pv, long fav,
                             long cart, long buy, int rank) {
    }

    public record ProductConversion(long productId, long pvUsers, long buyUsers, BigDecimal conversionRate) {
    }

    /**
     * 商品分析 data（契约 §3.3）。
     *
     * <p>v1.3（S3-18）加性补 4 个分页字段：`page`/`size` 为**生效值**，
     * `total` = 该快照排行可用总行数，`hasMore = page*size &lt; total`。
     * {@code topN} 语义由「前 N 名」收紧为「**本次窗口上限**」——`page` 缺省即 1，
     * 旧前端行为逐字段不变（契约 v1.3）。</p>
     */
    public record ProductsData(List<HotProduct> hot, List<ProductConversion> conversion, int topN,
                               int page, int size, long total, boolean hasMore) {
    }

    public record FunnelStage(String stage, String label, long users, BigDecimal rate) {
    }

    public record FunnelData(List<FunnelStage> stages, BigDecimal overallBuyRate, String windowNote) {
    }

    /**
     * 用户分群专题数据（§3.5）。
     *
     * <p>S3-16 加性补充：{@code periodStart}/{@code periodEnd} 是本次评分**实际使用的观察窗口**
     * （来自 ads_user_profile_m 的 period_start/period_end）；不可用时为 null 并由
     * {@code warnings} 声明（设计 V3.0 §15 L676「R/F/M 原值与 score、8 群体、观察期、复购」）。
     * 分组行 {@code RfmSegment} 自 S3-16 起带 M 原值（{@code amount}）与 F 原值（{@code orders}）。</p>
     */
    public record UsersData(List<RfmService.RfmSegment> rfmSegments, List<RfmService.LifecycleState> lifecycle,
                            List<RfmService.CategoryPreference> preference, String ruleVersion,
                            String periodStart, String periodEnd) {
    }

    /** RFM 专题数据（§3.6）：字段语义与 {@link UsersData} 一致，另带八类全量矩阵 rfmMatrix */
    public record RfmData(List<RfmService.RfmSegment> rfmSegments, List<RfmService.RfmSegment> rfmMatrix,
                          String ruleVersion, String periodStart, String periodEnd) {
    }

    // ── 运营总览（§3.1） ──────────────────────────────────────────────────────

    public AnalysisViewModel<OverviewData> overview(String snapshotId, LocalDate from, LocalDate to) {
        Map<String, Object> filters = new LinkedHashMap<>();
        echoDateRange(filters, from, to);
        echoRequestedSnapshot(filters, snapshotId);
        Pinned pinned = pin(snapshotId);
        if (pinned.snapshotId() == null) {
            return AnalysisViewModel.empty(filters, pinned.warnings());
        }
        String sid = pinned.snapshotId();
        filters.put("snapshotId", sid);

        List<DictionaryItem> dictionary = dictionary();
        Map<String, String> names = new HashMap<>();
        dictionary.forEach(item -> names.put(item.metricCode(), item.metricName()));
        OverviewData data = new OverviewData(metrics(sid, names), salesTrend(sid), activeTrend(sid),
                quality(sid), dictionary);
        return view(pinned, filters, data, List.of());
    }

    // ── 销售分析（§3.2） ──────────────────────────────────────────────────────

    public AnalysisViewModel<SalesData> sales(String snapshotId, LocalDate from, LocalDate to) {
        Map<String, Object> filters = new LinkedHashMap<>();
        echoDateRange(filters, from, to);
        echoRequestedSnapshot(filters, snapshotId);
        Pinned pinned = pin(snapshotId);
        if (pinned.snapshotId() == null) {
            return AnalysisViewModel.empty(filters, pinned.warnings());
        }
        String sid = pinned.snapshotId();
        filters.put("snapshotId", sid);

        Map<String, BigDecimal> values = metricValueMap(sid);
        // 分类/地区结构：本期没有对应 Hive ADS 与 MySQL 服务表，返回空数组并显式给出降级事实（契约 §3.2）
        SalesData data = new SalesData(salesTrend(sid),
                values.get(METRIC_GMV), values.get(METRIC_NET_SALE),
                values.get(METRIC_REFUND_RATE), values.get(METRIC_FULL_REFUND_RATE),
                quality(sid), List.of(), List.of());
        return view(pinned, filters, data, List.of(AnalysisViewModel.WARN_UNKNOWN_DIMENSION_TABLE));
    }

    // ── 商品分析（§3.3） ──────────────────────────────────────────────────────

    /**
     * 商品分析（契约 §3.3）。v1.3 起热度榜**真分页**（指导书 V3.0 §7 阶段4 L158「分页」、
     * 设计 V3.0 L693「分页 page/size/sort」/ L675「稳定排行、分页」）；
     * v1.5（S3-21）补齐 L693 三件套里的 `sort`。
     *
     * <p><b>`sort` 只重排热度榜，不改口径</b>：`total`/`hasMore` 仍是全量榜的行数与窗口计算，
     * `conversion` 列表与排序无关（始终按 `product_id` 升序），`topN` 旧口径不动。</p>
     *
     * @param page 1 起始页码；{@code null} ⇒ 1
     * @param size 窗口大小；{@code null} ⇒ 由旧参数 {@code topN} 决定（缺省 10，上限 100）
     * @param sort `字段` 或 `字段,asc|desc`；{@code null}/空白 ⇒ {@code rank,asc}（与 v1.4 前逐行同序）；
     *             未登记字段或非法方向 ⇒ {@code PARAM_INVALID}
     * @param topN 旧参数：仍原样回显进 {@code filters}；`size` 未显式给出时充当窗口大小
     */
    public AnalysisViewModel<ProductsData> products(String snapshotId, Integer page, Integer size, String sort,
                                                    Integer topN, LocalDate from, LocalDate to) {
        Map<String, Object> filters = new LinkedHashMap<>();
        echoDateRange(filters, from, to);
        echoRequestedSnapshot(filters, snapshotId);
        // v1.2 兼容：旧参数原样回显（缺省回显 10，与旧 @RequestParam defaultValue="10" 的观测结果一致）
        filters.put("topN", topN == null ? DEFAULT_TOP_N : topN);

        // 显式传入的非法值 fail-fast：静默钳制会造成「回显值 ≠ 实际生效值」
        int effectivePage = resolvePage(page);
        int effectiveSize = resolveSize(size, topN);
        HotSort effectiveSort = resolveSort(sort);
        filters.put("page", effectivePage);
        filters.put("size", effectiveSize);
        filters.put("sort", effectiveSort.echo());

        Pinned pinned = pin(snapshotId);
        if (pinned.snapshotId() == null) {
            return AnalysisViewModel.empty(filters, pinned.warnings());
        }
        String sid = pinned.snapshotId();
        filters.put("snapshotId", sid);

        List<Map<String, Object>> ranked = rankedHotRows(sid, effectiveSort);
        long total = ranked.size();
        List<HotProduct> hot = ranked.stream()
                .skip((long) (effectivePage - 1) * effectiveSize)
                .limit(effectiveSize)
                .map(AnalysisService::toHotProduct)
                .toList();
        boolean hasMore = (long) effectivePage * effectiveSize < total;
        ProductsData data = new ProductsData(hot, productConversion(sid), effectiveSize,
                effectivePage, effectiveSize, total, hasMore);
        return view(pinned, filters, data, List.of());
    }

    /** v1.3：缺省 = 第 1 页；显式非法值抛 {@code PARAM_INVALID}（错误码所有者仍是 GlobalExceptionHandler）。 */
    private static int resolvePage(Integer page) {
        if (page == null) {
            return 1;
        }
        if (page < 1) {
            throw new PlatformBizException("PARAM_INVALID", "page 必须 >= 1，实际 " + page);
        }
        return page;
    }

    /** v1.3：显式 {@code size} 优先；未给出时沿用 v1.2 的旧参数口径（{@code topN<=0} 取缺省 10，不转错误）。 */
    private static int resolveSize(Integer size, Integer legacyTopN) {
        if (size != null) {
            if (size < 1) {
                throw new PlatformBizException("PARAM_INVALID", "size 必须 >= 1，实际 " + size);
            }
            return Math.min(size, MAX_PAGE_SIZE);
        }
        return legacyTopN == null || legacyTopN <= 0 ? DEFAULT_TOP_N : Math.min(legacyTopN, MAX_TOP_N);
    }

    /**
     * v1.5（S3-21）：解析后生效的排序（字段名 + 方向）。{@code echo} 是回显进 {@code filters} 的规范形式
     * （`字段,asc|desc`），因此「回显值 = 实际生效值」可被测试与前端直接比对。
     */
    private record HotSort(String field, boolean descending) {

        String echo() {
            return field + (descending ? ",desc" : ",asc");
        }
    }

    /**
     * v1.5：`sort` 字段白名单 → ADS 列名。**只登记热度榜真实存在的列**；
     * 未登记字段一律拒绝（设计 L693 只规定参数名，未规定字段集，故这里把字段集当成本服务的显式契约）。
     */
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "rank", "rank_no",
            "heat", "heat_score",
            "pv", "pv",
            "fav", "fav",
            "cart", "cart",
            "buy", "buy");

    /** v1.5：字段缺省方向（true = 降序）。`rank` 是「越小越热」的名次列；其余指标越大越靠前。 */
    private static final Set<String> SORT_DESC_FIELDS = Set.of("heat", "pv", "fav", "cart", "buy");

    /** v1.5：缺省排序——与 v1.4 前逐行同序（`rank_no` 升序 + `product_id` 升序打破平局）。 */
    private static final HotSort DEFAULT_HOT_SORT = new HotSort("rank", false);

    /**
     * v1.5（S3-21）：解析 `sort`（设计 L693「分页 page/size/sort」、L675「稳定排行」）。
     *
     * <p>形式：`字段` 或 `字段,asc|desc`；去首尾空白、大小写不敏感；{@code null}/空白 ⇒ {@code rank,asc}。
     * 未登记字段、非法方向、段数 > 2、给了逗号却不给方向 ⇒ {@code PARAM_INVALID} fail-fast：
     * **不静默降级成缺省序**——那会让客户端以为拿到了排序结果，而 {@code filters.sort} 与实际顺序都对不上。</p>
     */
    private static HotSort resolveSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return DEFAULT_HOT_SORT;
        }
        String[] parts = sort.trim().toLowerCase(Locale.ROOT).split(",", -1);
        if (parts.length > 2) {
            throw new PlatformBizException("PARAM_INVALID", "sort 段数过多（应为 字段[,asc|desc]）：" + sort);
        }
        String field = parts[0].trim();
        if (!SORT_FIELDS.containsKey(field)) {
            throw new PlatformBizException("PARAM_INVALID",
                    "sort 字段未登记：" + parts[0] + "，可用 " + String.join("/", SORT_FIELDS.keySet()));
        }
        boolean descending = SORT_DESC_FIELDS.contains(field);
        if (parts.length == 2) {
            String direction = parts[1].trim();
            if ("asc".equals(direction)) {
                descending = false;
            } else if ("desc".equals(direction)) {
                descending = true;
            } else {
                throw new PlatformBizException("PARAM_INVALID",
                        "sort 方向只支持 asc/desc，实际 " + parts[1]);
            }
        }
        return new HotSort(field, descending);
    }

    // ── 行为漏斗（§3.4） ──────────────────────────────────────────────────────

    /**
     * @param date 兼容旧前端传参：只回显进 filters，不参与计算（漏斗是快照 + businessDate 粒度）
     */
    public AnalysisViewModel<FunnelData> funnel(String snapshotId, LocalDate date) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (date != null) {
            filters.put("date", date.toString());
        }
        echoRequestedSnapshot(filters, snapshotId);
        Pinned pinned = pin(snapshotId);
        if (pinned.snapshotId() == null) {
            return AnalysisViewModel.empty(filters, pinned.warnings());
        }
        String sid = pinned.snapshotId();
        filters.put("snapshotId", sid);

        Map<String, Map<String, Object>> byStage = new LinkedHashMap<>();
        for (Map<String, Object> row : AdsRows.latestPartition(adsReader, T_FUNNEL, sid)) {
            byStage.put(AdsRows.asString(row.get("stage")), row);
        }
        List<String> orderedStages = new ArrayList<>(byStage.keySet());
        orderedStages.sort(Comparator
                .comparingInt((String stage) -> AdsRows.orderIndex(FUNNEL_ORDER, stage))
                .thenComparing(Comparator.naturalOrder()));

        List<FunnelStage> stages = new ArrayList<>();
        BigDecimal overallBuyRate = null;
        for (String stage : orderedStages) {
            Map<String, Object> row = byStage.get(stage);
            // rate 取 ADS 的 conversion_rate 原值：首阶段（view）库里为 NULL，不擅自补 1.0
            stages.add(new FunnelStage(stage, FUNNEL_LABELS.getOrDefault(stage, stage),
                    AdsRows.asLong(row.get("user_count")), AdsRows.asDecimal(row.get("conversion_rate"))));
            BigDecimal stageOverall = AdsRows.asDecimal(row.get("overall_buy_rate"));
            if (stageOverall != null) {
                overallBuyRate = stageOverall; // 规范顺序里最后一个非空值 = 全链路累计购买率
            }
        }
        // 漏斗口径与观察窗口必须随响应返回（§18.2 用户行为）；口径版本用快照的 definitionVersion，不写死
        FunnelData data = new FunnelData(stages, overallBuyRate,
                "同一 businessDate 内的行为窗口（口径版本 " + blankToEmpty(pinned.meta().getDefinitionVersion()) + "）");
        return view(pinned, filters, data, List.of());
    }

    // ── 用户分群（§3.5）与 RFM（§3.6） ────────────────────────────────────────

    public AnalysisViewModel<UsersData> users(String snapshotId, LocalDate from, LocalDate to) {
        Map<String, Object> filters = new LinkedHashMap<>();
        echoDateRange(filters, from, to);
        echoRequestedSnapshot(filters, snapshotId);
        Pinned pinned = pin(snapshotId);
        if (pinned.snapshotId() == null) {
            return AnalysisViewModel.empty(filters, pinned.warnings());
        }
        String sid = pinned.snapshotId();
        filters.put("snapshotId", sid);

        RfmService.RfmProfile profile = rfmService.profile(sid);
        UsersData data = new UsersData(profile.segments(), profile.lifecycle(), profile.preference(),
                profile.ruleVersion(), profile.periodStart(), profile.periodEnd());
        return view(pinned, filters, data, profile.warnings());
    }

    /**
     * @param limit 兼容旧前端 {@code ?limit=50}：只回显，不再用于截断——
     *              R7-4 起本端点只返回聚合分层，不返回 user_id 明细（§18.2 不展示真实个人敏感数据）
     */
    public AnalysisViewModel<RfmData> rfm(String snapshotId, Integer limit) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (limit != null) {
            filters.put("limit", limit);
        }
        echoRequestedSnapshot(filters, snapshotId);
        Pinned pinned = pin(snapshotId);
        if (pinned.snapshotId() == null) {
            return AnalysisViewModel.empty(filters, pinned.warnings());
        }
        String sid = pinned.snapshotId();
        filters.put("snapshotId", sid);

        RfmService.RfmProfile profile = rfmService.profile(sid);
        RfmData data = new RfmData(profile.segments(), profile.matrix(), profile.ruleVersion(),
                profile.periodStart(), profile.periodEnd());
        return view(pinned, filters, data, profile.warnings());
    }

    // ── 快照解析与信封组装 ────────────────────────────────────────────────────

    /** 本次请求固定使用的快照；snapshotId=null 表示无可用快照（契约 §1.3 → 空 data + warning） */
    private record Pinned(String snapshotId, MetricSnapshot meta, List<String> warnings) {
    }

    /**
     * 解析快照号：请求指定优先，否则取 ACTIVE；随后**只按该快照号**读元数据与 ADS。
     * 解析不到就返回带警告的空信封，绝不回退到落地区事件明细、也不编数值。
     */
    private Pinned pin(String requestedSnapshotId) {
        String requested = requestedSnapshotId == null ? null : requestedSnapshotId.trim();
        boolean explicit = requested != null && !requested.isEmpty();
        String snapshotId = explicit ? requested : adsReader.activeSnapshotId();
        if (snapshotId == null) {
            return new Pinned(null, null, List.of(AnalysisViewModel.WARN_NO_ACTIVE_SNAPSHOT));
        }
        MetricSnapshot meta = metricStore.findSnapshot(snapshotId);
        if (meta == null) {
            // 显式指定的快照号库里没有（不能假装读过它）；ACTIVE 号取到却没有快照行同样属于"取不到"
            return new Pinned(null, null, List.of(explicit
                    ? AnalysisViewModel.WARN_UNKNOWN_SNAPSHOT
                    : AnalysisViewModel.WARN_NO_ACTIVE_SNAPSHOT));
        }
        return new Pinned(snapshotId, meta, List.of());
    }

    /** 统一信封：元数据来自快照行，qualityStatus 来自质量门，data 为各端点结构 */
    private <T> AnalysisViewModel<T> view(Pinned pinned, Map<String, Object> filters, T data,
                                          List<String> warnings) {
        List<String> allWarnings = new ArrayList<>(warnings);
        MetricSnapshot meta = pinned.meta();
        // source 是快照行的发布方（§17.6 只接受 spark-ads），原样回显；空串保持空串，不臆造值
        return AnalysisViewModel.of(pinned.snapshotId(), blankToEmpty(meta.getSource()),
                isoSeconds(meta.getBusinessTime()),
                dataUpdatedAt(meta), blankToEmpty(meta.getDefinitionVersion()),
                qualityStatus(meta, allWarnings), filters, data, allWarnings);
    }

    /**
     * 质量门结论：BLOCKING 未通过 → FAIL，有结果且无阻断失败 → PASS，取不到 → UNKNOWN。
     * 查询本身失败（meta 库不可用）也归为 UNKNOWN 并留下警告，不把异常伪造成 PASS。
     */
    private String qualityStatus(MetricSnapshot meta, List<String> warnings) {
        try {
            return qualityGate.statusForRun(meta.getPipelineRunId());
        } catch (RuntimeException e) {
            log.warn("quality gate unavailable for run {}: {}", meta.getPipelineRunId(), e.getMessage());
            warnings.add(AnalysisViewModel.WARN_QUALITY_STATUS_UNAVAILABLE);
            return MetricQualityGate.UNKNOWN;
        }
    }

    /** dataUpdatedAt 取快照的 data_updated_at；缺失时退回发布时间、再退回 LOAD（行创建）时间 */
    private static String dataUpdatedAt(MetricSnapshot meta) {
        LocalDateTime value = meta.getDataUpdatedAt() != null ? meta.getDataUpdatedAt()
                : meta.getPublishedAt() != null ? meta.getPublishedAt() : meta.getCreatedAt();
        return isoSeconds(value);
    }

    // ── 指标值与字典（metric_value / metric_definition） ──────────────────────

    private List<MetricItem> metrics(String snapshotId, Map<String, String> names) {
        List<MetricItem> items = new ArrayList<>();
        for (MetricValue value : metricValues(snapshotId)) {
            items.add(new MetricItem(value.getMetricCode(),
                    names.getOrDefault(value.getMetricCode(), ""), // 字典缺该码就留空，不臆造名称
                    value.getMetricValue(), blankToEmpty(value.getUnit()),
                    blankToEmpty(value.getPeriod()), blankToEmpty(value.getDefinitionVersion())));
        }
        items.sort(Comparator.comparing(MetricItem::metricCode)); // 按 metricCode 稳定排序（契约 §3.1）
        return items;
    }

    private Map<String, BigDecimal> metricValueMap(String snapshotId) {
        Map<String, BigDecimal> values = new HashMap<>();
        for (MetricValue value : metricValues(snapshotId)) {
            values.put(value.getMetricCode(), value.getMetricValue());
        }
        return values;
    }

    private List<MetricValue> metricValues(String snapshotId) {
        List<MetricValue> values = metricStore.query(new MetricStore.MetricQuery(snapshotId, false));
        return values == null ? List.of() : values;
    }

    /** 指标字典（analytics_meta.metric_definition）：页面"查看指标口径"用 */
    private List<DictionaryItem> dictionary() {
        List<MetricDefinition> definitions = definitionMapper.selectList(null);
        if (definitions == null) {
            return List.of();
        }
        List<DictionaryItem> items = new ArrayList<>();
        for (MetricDefinition definition : definitions) {
            if (definition.getMetricCode() == null) {
                continue;
            }
            items.add(new DictionaryItem(definition.getMetricCode(), blankToEmpty(definition.getMetricName()),
                    blankToEmpty(definition.getFormula()), blankToEmpty(definition.getUnit())));
        }
        items.sort(Comparator.comparing(DictionaryItem::metricCode));
        return items;
    }

    // ── ADS 宽表映射 ──────────────────────────────────────────────────────────

    /**
     * 销售趋势（ads_sale_trend_m，按 dt 升序）。
     *
     * <p>v1.4（S3-20）：末列补 ADS `net_sale_amount`（净销售额）**原样透传**；缺列/畸形 ⇒ {@code null}。
     */
    private List<SalesTrendPoint> salesTrend(String snapshotId) {
        return adsReader.selectBySnapshot(T_SALE_TREND, snapshotId, null).stream()
                .sorted(Comparator.comparing((Map<String, Object> row) -> AdsRows.isoDate(AdsRows.asString(row.get("dt")))))
                .map(row -> new SalesTrendPoint(AdsRows.isoDate(AdsRows.asString(row.get("dt"))),
                        AdsRows.asLong(row.get("order_count")), AdsRows.asDecimal(row.get("sale_amount")),
                        AdsRows.asLong(row.get("buyer_count")), AdsRows.asDecimal(row.get("avg_order_value")),
                        AdsRows.asDecimal(row.get("net_sale_amount"))))
                .toList();
    }

    /** 活跃趋势（ads_active_trend_m，按 dt 升序） */
    private List<ActiveDay> activeTrend(String snapshotId) {
        return adsReader.selectBySnapshot(T_ACTIVE_TREND, snapshotId, null).stream()
                .sorted(Comparator.comparing((Map<String, Object> row) -> AdsRows.isoDate(AdsRows.asString(row.get("dt")))))
                .map(row -> new ActiveDay(AdsRows.isoDate(AdsRows.asString(row.get("dt"))),
                        AdsRows.asLong(row.get("dau")), AdsRows.asLong(row.get("behavior_count"))))
                .toList();
    }

    /** 质量卡（ads_data_quality_m）：按规则码去重统计，未通过规则码升序列出 */
    private QualitySummary quality(String snapshotId) {
        Map<String, Integer> passedByRule = new java.util.TreeMap<>();
        for (Map<String, Object> row : AdsRows.latestPartition(adsReader, T_DATA_QUALITY, snapshotId)) {
            passedByRule.put(AdsRows.asString(row.get("rule_code")), AdsRows.asInt(row.get("passed")));
        }
        int passed = (int) passedByRule.values().stream().filter(value -> value == 1).count();
        List<String> failedRules = passedByRule.entrySet().stream()
                .filter(entry -> entry.getValue() != 1)
                .map(Map.Entry::getKey)
                .toList();
        return new QualitySummary(passedByRule.size(), passed, failedRules);
    }

    /**
     * 热门商品**全量排行**（ads_hot_product_m）：缺省 `rank_no` 升序，**末级一律以 `product_id` 升序打破平局**
     * （设计 L675「稳定排行」：不让同值行随读取顺序漂移）。分页切片与 `total` 由调用方负责。
     *
     * <p>v1.5（S3-21）：`sort` 只换**首级**排序键；`product_id` 升序仍是固定的末级，因此
     * 「同值集合内顺序稳定」在任意 `sort` 下都成立。</p>
     */
    private List<Map<String, Object>> rankedHotRows(String snapshotId, HotSort sort) {
        return AdsRows.latestPartition(adsReader, T_HOT_PRODUCT, snapshotId).stream()
                .sorted(hotKeyComparator(sort)
                        .thenComparingLong(row -> AdsRows.asLong(row.get("product_id"))))
                .toList();
    }

    /**
     * v1.5：`sort` 首级比较器。取不到 `heat_score` 的行**无论方向都排最后**
     * （`AdsRows.asDecimal` 取不到返回 null；把 null 当 0 会让缺值行在降序里冒充末位真值、
     * 在升序里直接霸榜——两种都会让「热度榜」结论失真）。
     */
    private static Comparator<Map<String, Object>> hotKeyComparator(HotSort sort) {
        if ("heat".equals(sort.field())) {
            Comparator<BigDecimal> order = sort.descending() ? Comparator.reverseOrder() : Comparator.naturalOrder();
            return Comparator.comparing((Map<String, Object> row) -> AdsRows.asDecimal(row.get("heat_score")),
                    Comparator.nullsLast(order));
        }
        Comparator<Map<String, Object>> numeric =
                Comparator.comparingLong(row -> AdsRows.asLong(row.get(SORT_FIELDS.get(sort.field()))));
        return sort.descending() ? numeric.reversed() : numeric;
    }

    private static HotProduct toHotProduct(Map<String, Object> row) {
        return new HotProduct(AdsRows.asLong(row.get("product_id")),
                AdsRows.asString(row.get("product_name")), AdsRows.asDecimal(row.get("heat_score")),
                AdsRows.asLong(row.get("pv")), AdsRows.asLong(row.get("fav")),
                AdsRows.asLong(row.get("cart")), AdsRows.asLong(row.get("buy")),
                AdsRows.asInt(row.get("rank_no")));
    }

    /** 商品转化（ads_product_conversion_m，按 product_id 升序；不做 topN 截断，避免漏商品） */
    private List<ProductConversion> productConversion(String snapshotId) {
        return AdsRows.latestPartition(adsReader, T_PRODUCT_CONVERSION, snapshotId).stream()
                .sorted(Comparator.comparingLong((Map<String, Object> row) -> AdsRows.asLong(row.get("product_id"))))
                .map(row -> new ProductConversion(AdsRows.asLong(row.get("product_id")),
                        AdsRows.asLong(row.get("pv_users")), AdsRows.asLong(row.get("buy_users")),
                        AdsRows.asDecimal(row.get("conversion_rate"))))
                .toList();
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private static void echoDateRange(Map<String, Object> filters, LocalDate from, LocalDate to) {
        if (from != null) {
            filters.put("from", from.toString());
        }
        if (to != null) {
            filters.put("to", to.toString());
        }
    }

    private static void echoRequestedSnapshot(Map<String, Object> filters, String requestedSnapshotId) {
        String requested = requestedSnapshotId == null ? null : requestedSnapshotId.trim();
        if (requested != null && !requested.isEmpty()) {
            filters.put("snapshotId", requested);
        }
    }

    private static String isoSeconds(LocalDateTime value) {
        return value == null ? null : value.format(ISO_SECONDS);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
