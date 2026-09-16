package com.graduation.analytics.analysis;

import com.graduation.analytics.analysis.AnalysisService.FunnelData;
import com.graduation.analytics.analysis.AnalysisService.FunnelStage;
import com.graduation.analytics.analysis.AnalysisService.MetricItem;
import com.graduation.analytics.analysis.AnalysisService.OverviewData;
import com.graduation.analytics.analysis.AnalysisService.ProductsData;
import com.graduation.analytics.analysis.AnalysisService.RfmData;
import com.graduation.analytics.analysis.AnalysisService.SalesData;
import com.graduation.analytics.analysis.AnalysisService.SalesTrendPoint;
import com.graduation.analytics.analysis.AnalysisService.UsersData;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.metric.MetricAdsReader;
import com.graduation.analytics.metric.MetricQualityGate;
import com.graduation.analytics.metric.MySqlMetricStore;
import com.graduation.analytics.metric.dict.MetricDefinition;
import com.graduation.analytics.metric.dict.MetricDefinitionMapper;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R7-4 分析服务 L0 单测（不连库，Mockito 替换指标库读取）。
 *
 * <p>覆盖点：① 无 ACTIVE 快照 → 空 data + NO_ACTIVE_SNAPSHOT，不回退、不抛异常；
 * ② 快照号在请求内固定并回显，ADS 查询与信封用的是同一个 snapshotId；
 * ③ 契约 §3 的结构映射与 §5 黄金值口径（取 metric_value 原值，不重算）；
 * ④ 降级事实以 warnings 暴露。</p>
 */
class AnalysisServiceTest {

    /** 真库当前 ACTIVE 快照（数值与 §5 黄金值一致） */
    private static final String SID = "S20260901_24";

    private MetricAdsReader adsReader;
    private MySqlMetricStore metricStore;
    private MetricDefinitionMapper definitionMapper;
    private MetricQualityGate qualityGate;
    private AnalysisService service;

    @BeforeEach
    void setUp() {
        adsReader = mock(MetricAdsReader.class);
        metricStore = mock(MySqlMetricStore.class);
        definitionMapper = mock(MetricDefinitionMapper.class);
        qualityGate = mock(MetricQualityGate.class);
        when(qualityGate.statusForRun(any())).thenReturn(MetricQualityGate.PASS);
        // RfmService 用同一个 mock reader，保证 users/rfm 端点确实走 ADS 聚合路径
        service = new AnalysisService(adsReader, metricStore, qualityGate, definitionMapper, new RfmService(adsReader));
    }

    @Test
    @DisplayName("无 ACTIVE 快照：6 个端点都返回空 data + NO_ACTIVE_SNAPSHOT，且不查任何 ADS/指标")
    void noActiveSnapshotReturnsEmptyEnvelopeForEveryEndpoint() {
        when(adsReader.activeSnapshotId()).thenReturn(null);

        List<AnalysisViewModel<?>> models = List.of(
                service.overview(null, null, null),
                service.sales(null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1)),
                service.products(null, null, null, 10, null, null),
                service.funnel(null, LocalDate.of(2026, 9, 1)),
                service.users(null, null, null),
                service.rfm(null, 50));

        for (AnalysisViewModel<?> model : models) {
            assertThat(model.snapshotId()).isNull();
            assertThat(model.source()).isNull();
            assertThat(model.businessTime()).isNull();
            assertThat(model.qualityStatus()).isEqualTo(MetricQualityGate.UNKNOWN);
            assertThat(model.warnings()).containsExactly(AnalysisViewModel.WARN_NO_ACTIVE_SNAPSHOT);
            assertThat(model.data()).isEqualTo(Map.of());
        }
        // 关键否定断言：没有 ACTIVE 就绝不读 ADS/指标值（R7-4 之前会回退落地区事件造数）
        verify(adsReader, never()).selectBySnapshot(anyString(), anyString(), any());
        verify(metricStore, never()).query(any());
        verify(metricStore, never()).findSnapshot(anyString());
    }

    @Test
    @DisplayName("运营总览：信封 9 字段齐备，指标/趋势/质量/字典按契约 §3.1 组装")
    void overviewFillsEnvelopeAndSections() {
        stubActiveSnapshot();

        AnalysisViewModel<OverviewData> model = service.overview(null, null, null);

        assertThat(model.snapshotId()).isEqualTo(SID);
        assertThat(model.source()).isEqualTo("spark-ads");
        assertThat(model.businessTime()).isEqualTo("2026-09-01T00:00:00");
        assertThat(model.dataUpdatedAt()).isEqualTo("2026-09-01T00:00:00");
        assertThat(model.definitionVersion()).isEqualTo("v2");
        assertThat(model.qualityStatus()).isEqualTo("PASS");
        assertThat(model.warnings()).isEmpty();

        OverviewData data = model.data();
        assertThat(data.metrics()).extracting(MetricItem::metricCode)
                .containsExactly("avg_order_value", "full_refund_rate", "gmv", "net_sale", "paid_order_cnt", "pv",
                        "refund_rate");
        MetricItem gmv = data.metrics().stream().filter(item -> "gmv".equals(item.metricCode())).findFirst()
                .orElseThrow();
        assertThat(gmv.metricName()).isEqualTo("销售额(GMV)"); // 名称来自 metric_definition 字典
        assertThat(gmv.value()).isEqualByComparingTo("2042.00");
        assertThat(gmv.unit()).isEqualTo("元");
        assertThat(gmv.period()).isEqualTo("day:2026-09-01");
        assertThat(gmv.definitionVersion()).isEqualTo("v1");

        // 趋势按 dt 升序（yyyyMMdd → ISO 归一化）
        assertThat(data.salesTrend()).hasSize(2);
        assertThat(data.salesTrend().get(0).date()).isEqualTo("2026-08-31");
        assertThat(data.salesTrend().get(1).date()).isEqualTo("2026-09-01");
        assertThat(data.salesTrend().get(1).saleAmount()).isEqualByComparingTo("2042.00");
        assertThat(data.salesTrend().get(1).netSaleAmount()).isEqualByComparingTo("1493.00");
        assertThat(data.salesTrend().get(0).netSaleAmount()).isEqualByComparingTo("500.00");
        assertThat(data.salesTrend().get(1).orderCount()).isEqualTo(5L);
        assertThat(data.salesTrend().get(1).buyerCount()).isEqualTo(3L);

        assertThat(data.activeTrend()).hasSize(1);
        assertThat(data.activeTrend().get(0).date()).isEqualTo("2026-09-01");
        assertThat(data.activeTrend().get(0).dau()).isEqualTo(3L);
        assertThat(data.activeTrend().get(0).behaviorCount()).isEqualTo(14L);

        assertThat(data.quality().ruleCount()).isEqualTo(4);
        assertThat(data.quality().passedCount()).isEqualTo(3);
        assertThat(data.quality().failedRules()).containsExactly("EVENT_ID_UNIQUE");
        assertThat(data.metricDictionary()).isNotEmpty();
    }

    @Test
    @DisplayName("信封 source 取快照行原值：空串原样回显，不臆造成 spark-ads（§17.6 只约束发布侧）")
    void blankSnapshotSourceIsEchoedAsEmptyString() {
        when(adsReader.activeSnapshotId()).thenReturn(SID);
        MetricSnapshot blank = snapshot(SID);
        blank.setSource("");
        when(metricStore.findSnapshot(SID)).thenReturn(blank);
        when(metricStore.query(any())).thenReturn(metricValues());
        when(definitionMapper.selectList(any())).thenReturn(definitions());
        stubAdsRows();

        AnalysisViewModel<OverviewData> model = service.overview(null, null, null);

        assertThat(model.snapshotId()).isEqualTo(SID);
        assertThat(model.source()).isEmpty();
        assertThat(model.warnings()).isEmpty(); // 空串不是降级事实：读到的就是"发布方未标注"
    }

    @Test
    @DisplayName("销售分析：gmv/净销售额/退款率一律取 metric_value 原值，不重算；缺维度表给出降级警告")
    void salesReadsMetricValueWithoutRecompute() {
        stubActiveSnapshot();

        AnalysisViewModel<SalesData> model =
                service.sales(null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

        assertThat(model.snapshotId()).isEqualTo(SID);
        assertThat(model.filters()).containsEntry("snapshotId", SID)
                .containsEntry("from", "2026-09-01").containsEntry("to", "2026-09-01");
        assertThat(model.data().gmv()).isEqualByComparingTo("2042.00");
        assertThat(model.data().netSale()).isEqualByComparingTo("1493.00");
        assertThat(model.data().refundRate()).isEqualByComparingTo("0.6000");
        assertThat(model.data().fullRefundRate()).isEqualByComparingTo("0.2000");
        assertThat(model.data().trend()).hasSize(2);
        assertThat(model.data().trend().get(1).netSaleAmount()).isEqualByComparingTo("1493.00");
        assertThat(model.data().byCategory()).isEmpty();
        assertThat(model.data().byRegion()).isEmpty();
        assertThat(model.warnings()).containsExactly(AnalysisViewModel.WARN_UNKNOWN_DIMENSION_TABLE);
        // 快照一致性：ADS 查询带着与信封相同的 snapshotId
        verify(adsReader).selectBySnapshot("ads_sale_trend_m", SID, null);
    }

    @Test
    @DisplayName("趋势净额：ADS 缺该列时返回 null，不臆造 0；汇总净额仍取 metric_value（两个来源不得互相顶替）")
    void trendNetSaleAmountIsNullWhenAdsColumnAbsent() {
        stubActiveSnapshot();
        stubTrendRows(List.of(row("dt", "20260901", "order_count", 5L, "buyer_count", 3L,
                "sale_amount", new BigDecimal("2042.00"), "avg_order_value", new BigDecimal("408.40"))));

        AnalysisViewModel<SalesData> model = service.sales(null, null, null);

        assertThat(model.data().trend()).hasSize(1);
        assertThat(model.data().trend().get(0).netSaleAmount()).isNull();
        assertThat(model.data().trend().get(0).saleAmount()).isEqualByComparingTo("2042.00");
        assertThat(model.data().netSale()).isEqualByComparingTo("1493.00");
    }

    @Test
    @DisplayName("趋势净额：字符串/数值两种驱动形态都吃，畸形值返回 null 且不抛异常（一律不猜 0）")
    void trendNetSaleAmountParsesDriverForms() {
        stubActiveSnapshot();
        stubTrendRows(List.of(
                row("dt", "20260831", "order_count", 2L, "buyer_count", 2L, "sale_amount", new BigDecimal("500.00"),
                        "avg_order_value", new BigDecimal("250.00"), "net_sale_amount", "500.00"),
                row("dt", "20260901", "order_count", 5L, "buyer_count", 3L, "sale_amount", new BigDecimal("2042.00"),
                        "avg_order_value", new BigDecimal("408.40"), "net_sale_amount", new BigDecimal("1493.00")),
                row("dt", "20260902", "order_count", 1L, "buyer_count", 1L, "sale_amount", new BigDecimal("100.00"),
                        "avg_order_value", new BigDecimal("100.00"), "net_sale_amount", "abc")));

        List<SalesTrendPoint> trend = service.sales(null, null, null).data().trend();

        assertThat(trend).extracting(SalesTrendPoint::date)
                .containsExactly("2026-08-31", "2026-09-01", "2026-09-02");
        assertThat(trend.get(0).netSaleAmount()).isEqualByComparingTo("500.00");
        assertThat(trend.get(1).netSaleAmount()).isEqualByComparingTo("1493.00");
        assertThat(trend.get(2).netSaleAmount()).isNull();
    }

    @Test
    @DisplayName("趋势净额：overview 与 sales 共用同一记录，两处都给净额（不得一处有一处没有）")
    void overviewTrendAlsoCarriesNetSaleAmount() {
        stubActiveSnapshot();

        List<SalesTrendPoint> overviewTrend = service.overview(null, null, null).data().salesTrend();

        assertThat(overviewTrend).isNotEmpty();
        assertThat(overviewTrend).allSatisfy(point -> assertThat(point.netSaleAmount()).isNotNull());
        assertThat(overviewTrend.get(overviewTrend.size() - 1).netSaleAmount()).isEqualByComparingTo("1493.00");
    }

    @Test
    @DisplayName("商品分析：topN 原样回显、生效值做上限保护；热度榜按 rank、转化按 product_id")
    void productsEchoTopNButClampEffectiveValue() {
        stubActiveSnapshot();

        AnalysisViewModel<ProductsData> model = service.products(null, null, null, 1000, null, null);

        assertThat(model.filters()).containsEntry("topN", 1000);
        assertThat(model.data().topN()).isEqualTo(100);
        assertThat(model.data().hot()).hasSize(4);
        assertThat(model.data().hot().get(0).rank()).isEqualTo(1);
        assertThat(model.data().hot().get(0).productName()).isEqualTo("保温杯");
        assertThat(model.data().hot().get(0).heat()).isEqualByComparingTo("11.0904");
        assertThat(model.data().conversion()).extracting(AnalysisService.ProductConversion::productId)
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(model.data().conversion().get(1).conversionRate()).isEqualByComparingTo("0.3333");
        // v1.3：size 未显式传 ⇒ 生效窗口 = min(topN, 100)；page 缺省 1 ⇒ 与 v1.2 逐字段相同
        assertThat(model.data().page()).isEqualTo(1);
        assertThat(model.data().size()).isEqualTo(100);
        assertThat(model.data().total()).isEqualTo(4);
        assertThat(model.data().hasMore()).isFalse();
    }

    @Test
    @DisplayName("商品分析 v1.3：只传旧参数 topN 时 page=1 语义与 v1.2 逐字段相同（兼容性）")
    void productsFirstPageKeepsV12ShapeWhenOnlyLegacyTopNIsProvided() {
        stubActiveSnapshot();

        AnalysisViewModel<ProductsData> model = service.products(null, null, null, 10, null, null);

        assertThat(model.filters()).containsEntry("topN", 10).containsEntry("page", 1).containsEntry("size", 10);
        assertThat(model.data().hot()).extracting(AnalysisService.HotProduct::rank).containsExactly(1, 2, 3, 4);
        assertThat(model.data().topN()).isEqualTo(10);
        assertThat(model.data().page()).isEqualTo(1);
        assertThat(model.data().size()).isEqualTo(10);
        assertThat(model.data().total()).isEqualTo(4);
        assertThat(model.data().hasMore()).isFalse();
    }

    @Test
    @DisplayName("商品分析 v1.3：page=2/size=5 返回第 6~10 名窗口，total/hasMore 如实，conversion 仍全量")
    void productsSecondPageReturnsRequestedRankWindow() {
        stubActiveSnapshot();
        stubHotRows(hotRows(15));

        AnalysisViewModel<ProductsData> model = service.products(null, 2, 5, null, null, null);

        assertThat(model.data().hot()).extracting(AnalysisService.HotProduct::rank)
                .containsExactly(6, 7, 8, 9, 10);
        assertThat(model.data().hot()).extracting(AnalysisService.HotProduct::productId)
                .containsExactly(60L, 70L, 80L, 90L, 100L);
        assertThat(model.data().page()).isEqualTo(2);
        assertThat(model.data().size()).isEqualTo(5);
        assertThat(model.data().total()).isEqualTo(15);
        assertThat(model.data().hasMore()).isTrue();
        // 旧参数 topN 缺省仍按 v1.2 回显 10（请求原值/缺省值），新参数回显生效值
        assertThat(model.filters()).containsEntry("page", 2).containsEntry("size", 5).containsEntry("topN", 10);
        assertThat(model.data().topN()).isEqualTo(5); // 窗口上限 = 生效 size
        // 分页只作用于热度榜：转化保持全量，不漏商品
        assertThat(model.data().conversion()).hasSize(4);
    }

    @Test
    @DisplayName("商品分析 v1.3：size 未传时旧参数 topN 退化为窗口大小（topN=3 ⇒ 前 3 名）")
    void productsLegacyTopNBecomesWindowSize() {
        stubActiveSnapshot();
        stubHotRows(hotRows(15));

        AnalysisViewModel<ProductsData> model = service.products(null, null, null, 3, null, null);

        assertThat(model.data().hot()).extracting(AnalysisService.HotProduct::rank).containsExactly(1, 2, 3);
        assertThat(model.data().page()).isEqualTo(1);
        assertThat(model.data().size()).isEqualTo(3);
        assertThat(model.data().total()).isEqualTo(15);
        assertThat(model.data().hasMore()).isTrue();
        assertThat(model.filters()).containsEntry("topN", 3);
    }

    @Test
    @DisplayName("商品分析 v1.3：page 超出 total 返回空 hot 而不是错误，total/hasMore 如实")
    void productsPageBeyondLastPageIsEmptyButNotAnError() {
        stubActiveSnapshot();
        stubHotRows(hotRows(15));

        AnalysisViewModel<ProductsData> model = service.products(null, 4, 5, null, null, null);

        assertThat(model.data().hot()).isEmpty();
        assertThat(model.data().page()).isEqualTo(4);
        assertThat(model.data().size()).isEqualTo(5);
        assertThat(model.data().total()).isEqualTo(15);
        assertThat(model.data().hasMore()).isFalse();
        assertThat(model.warnings()).isEmpty();
    }

    @Test
    @DisplayName("商品分析 v1.3：排行空 ⇒ total=0、hasMore=false、hot 空数组（不是 null）")
    void productsEmptyRankingYieldsZeroTotal() {
        stubActiveSnapshot();
        stubHotRows(List.of());

        AnalysisViewModel<ProductsData> model = service.products(null, 1, 10, null, null, null);

        assertThat(model.data().hot()).isEmpty();
        assertThat(model.data().total()).isZero();
        assertThat(model.data().hasMore()).isFalse();
    }

    @Test
    @DisplayName("商品分析 v1.3：稳定排行——同 rank_no 以 product_id 升序打破平局")
    void productsTiesAreBrokenByProductIdForStableRanking() {
        stubActiveSnapshot();
        stubHotRows(List.of(
                hotRow(1, 7L, "P7"), hotRow(1, 3L, "P3"), hotRow(1, 5L, "P5")));

        AnalysisViewModel<ProductsData> model = service.products(null, null, null, 10, null, null);

        assertThat(model.data().hot()).extracting(AnalysisService.HotProduct::productId)
                .containsExactly(3L, 5L, 7L);
        assertThat(model.data().total()).isEqualTo(3);
    }

    @Test
    @DisplayName("商品分析 v1.3：显式 page/size 非法 ⇒ PARAM_INVALID（不静默钳制）；旧参数 topN<=0 仍取缺省")
    void productsRejectNonPositivePageOrSizeButTolerateLegacyTopN() {
        stubActiveSnapshot();

        assertThatThrownBy(() -> service.products(null, 0, null, null, null, null))
                .isInstanceOfSatisfying(PlatformBizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PARAM_INVALID"));
        assertThatThrownBy(() -> service.products(null, -1, null, null, null, null))
                .isInstanceOfSatisfying(PlatformBizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PARAM_INVALID"));
        assertThatThrownBy(() -> service.products(null, null, 0, null, null, null))
                .isInstanceOfSatisfying(PlatformBizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PARAM_INVALID"));
        assertThatThrownBy(() -> service.products(null, null, -5, null, null, null))
                .isInstanceOfSatisfying(PlatformBizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PARAM_INVALID"));

        // 兼容保留：旧参数 topN<=0 沿用 v1.2 语义取缺省 10，不因 v1.3 转为错误（既有行为不变）
        AnalysisViewModel<ProductsData> model = service.products(null, null, null, 0, null, null);
        assertThat(model.filters()).containsEntry("topN", 0);
        assertThat(model.data().size()).isEqualTo(10);
        assertThat(model.data().page()).isEqualTo(1);
    }

    @Test
    @DisplayName("行为漏斗：阶段按规范顺序、首阶段 rate 为 null 不补 1.0、窗口说明带口径版本")
    void funnelKeepsStageOrderAndNullRate() {
        stubActiveSnapshot();

        AnalysisViewModel<FunnelData> model = service.funnel(null, LocalDate.of(2026, 9, 1));

        assertThat(model.filters()).containsEntry("date", "2026-09-01").containsEntry("snapshotId", SID);
        assertThat(model.data().stages()).extracting(FunnelStage::stage)
                .containsExactly("view", "intent", "order", "pay");
        assertThat(model.data().stages()).extracting(FunnelStage::label)
                .containsExactly("浏览", "意向", "下单", "支付");
        assertThat(model.data().stages()).extracting(FunnelStage::users).containsExactly(3L, 2L, 3L, 3L);
        assertThat(model.data().stages().get(0).rate()).isNull(); // 库里 view 的 conversion_rate 为 NULL
        assertThat(model.data().stages().get(1).rate()).isEqualByComparingTo("0.6667");
        assertThat(model.data().overallBuyRate()).isEqualByComparingTo("1.0000");
        assertThat(model.data().windowNote()).contains("口径版本 v2");
    }

    @Test
    @DisplayName("用户分群与 RFM：聚合层来自 ads_user_profile_m，矩阵补齐八类，金额缺失不冒充")
    void usersAndRfmComeFromProfileAggregation() {
        stubActiveSnapshot();

        AnalysisViewModel<UsersData> users = service.users(null, null, null);
        assertThat(users.data().rfmSegments()).extracting(RfmService.RfmSegment::valueGroup)
                .containsExactly("一般发展", "一般挽留");
        assertThat(users.data().rfmSegments().get(0).users()).isEqualTo(2L);
        assertThat(users.data().rfmSegments().get(0).share()).isEqualByComparingTo("0.6667");
        assertThat(users.data().rfmSegments().get(0).amount()).isNull();
        assertThat(users.data().ruleVersion()).isEqualTo("rfm-v1");
        assertThat(users.data().lifecycle()).extracting(RfmService.LifecycleState::state).containsExactly("活跃");
        assertThat(users.data().preference()).extracting(RfmService.CategoryPreference::categoryId)
                .containsExactly(11L, 21L);
        assertThat(users.warnings()).containsExactly(AnalysisViewModel.WARN_RFM_AMOUNT_UNAVAILABLE,
                AnalysisViewModel.WARN_RFM_RAW_VALUES_UNAVAILABLE,
                AnalysisViewModel.WARN_RFM_PERIOD_UNAVAILABLE);

        AnalysisViewModel<RfmData> rfm = service.rfm(null, 50);
        assertThat(rfm.filters()).containsEntry("limit", 50).containsEntry("snapshotId", SID);
        assertThat(rfm.data().rfmMatrix()).hasSize(8);
        assertThat(rfm.data().rfmMatrix()).extracting(RfmService.RfmSegment::valueGroup)
                .containsExactlyElementsOf(RfmService.VALUE_GROUPS);
        assertThat(rfm.data().rfmMatrix().get(0).users()).isZero(); // 重要价值 真库无该分层 → 补 0
        assertThat(rfm.data().ruleVersion()).isEqualTo("rfm-v1");
    }

    @Test
    @DisplayName("S3-16 原值列存在：M/F 原值与观察窗口透传到 users/rfm 的 data，且无降级警告")
    void usersAndRfmCarryRawValuesAndWindow() {
        stubActiveSnapshot();
        Map<String, List<Map<String, Object>>> ads = new LinkedHashMap<>(adsRows());
        ads.put("ads_user_profile_m", List.of(
                row("dt", "20260901", "user_id", 1L, "r", 5, "f", 2, "m", 3, "value_group", "一般发展",
                        "favorite_category", 11L, "lifecycle_state", "活跃", "rule_version", "rfm-v1",
                        "calc_date", "20260901", "last_buy_date", "2026-09-01",
                        "r_days", 7, "f_count", 2L, "m_amount", new BigDecimal("1496.00"),
                        "period_start", "2026-08-02", "period_end", "2026-09-01")));
        when(adsReader.selectBySnapshot(anyString(), anyString(), any()))
                .thenAnswer(invocation -> ads.getOrDefault(invocation.getArgument(0), List.of()));

        AnalysisViewModel<UsersData> users = service.users(null, null, null);
        assertThat(users.data().rfmSegments().get(0).amount()).isEqualByComparingTo("1496.00");
        assertThat(users.data().rfmSegments().get(0).orders()).isEqualTo(2L);
        assertThat(users.data().rfmSegments().get(0).avgRecencyDays()).isEqualByComparingTo("7.0");
        assertThat(users.data().periodStart()).isEqualTo("2026-08-02");
        assertThat(users.data().periodEnd()).isEqualTo("2026-09-01");
        assertThat(users.warnings()).isEmpty();

        AnalysisViewModel<RfmData> rfm = service.rfm(null, null);
        assertThat(rfm.data().periodStart()).isEqualTo("2026-08-02");
        assertThat(rfm.data().periodEnd()).isEqualTo("2026-09-01");
        assertThat(rfm.data().rfmMatrix()).filteredOn(segment -> "一般发展".equals(segment.valueGroup()))
                .singleElement().satisfies(segment -> {
                    assertThat(segment.amount()).isEqualByComparingTo("1496.00");
                    assertThat(segment.orders()).isEqualTo(2L);
                });
        assertThat(rfm.warnings()).isEmpty();
    }

    @Test
    @DisplayName("显式指定 snapshotId：按指定快照读，不解析 ACTIVE，并在 filters/信封回显")
    void explicitSnapshotIdWins() {
        when(metricStore.findSnapshot("S20260901_23")).thenReturn(snapshot("S20260901_23"));
        when(metricStore.query(any())).thenReturn(List.of());
        stubAdsRows();

        AnalysisViewModel<SalesData> model = service.sales("S20260901_23", null, null);

        assertThat(model.snapshotId()).isEqualTo("S20260901_23");
        assertThat(model.filters()).containsEntry("snapshotId", "S20260901_23");
        assertThat(model.data().gmv()).isNull(); // 该快照无 gmv 指标行 → null，不用 0 冒充
        verify(adsReader, never()).activeSnapshotId();
        verify(adsReader).selectBySnapshot("ads_sale_trend_m", "S20260901_23", null);
    }

    @Test
    @DisplayName("指定的快照号不存在：UNKNOWN_SNAPSHOT 空信封，不假装读过它")
    void unknownSnapshotIdReturnsEmptyEnvelope() {
        when(metricStore.findSnapshot("S-NOT-EXIST")).thenReturn(null);

        AnalysisViewModel<SalesData> model = service.sales("S-NOT-EXIST", null, null);

        assertThat(model.snapshotId()).isNull();
        assertThat(model.filters()).containsEntry("snapshotId", "S-NOT-EXIST"); // 回显请求值便于排障
        assertThat(model.warnings()).containsExactly(AnalysisViewModel.WARN_UNKNOWN_SNAPSHOT);
        assertThat(model.qualityStatus()).isEqualTo(MetricQualityGate.UNKNOWN);
        // data 是空对象；断言时先擦除成 Object，避免强转成具体 record（空信封里放的不是它）
        assertThat((Object) model.data()).isEqualTo(Map.of());
        verify(adsReader, never()).selectBySnapshot(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("质量门：BLOCKING 失败 → FAIL；质量查询异常 → UNKNOWN 且留警告（不伪造成 PASS）")
    void qualityStatusReflectsGateResult() {
        stubActiveSnapshot();

        when(qualityGate.statusForRun(any())).thenReturn(MetricQualityGate.FAIL);
        assertThat(service.overview(null, null, null).qualityStatus()).isEqualTo("FAIL");

        when(qualityGate.statusForRun(any())).thenThrow(new IllegalStateException("meta 库不可用"));
        AnalysisViewModel<OverviewData> degraded = service.overview(null, null, null);
        assertThat(degraded.qualityStatus()).isEqualTo(MetricQualityGate.UNKNOWN);
        assertThat(degraded.warnings()).containsExactly(AnalysisViewModel.WARN_QUALITY_STATUS_UNAVAILABLE);
    }

    // ── 夹具：数值与真库 S20260901_24 一致（§5 黄金值） ─────────────────────────

    private void stubActiveSnapshot() {
        when(adsReader.activeSnapshotId()).thenReturn(SID);
        when(metricStore.findSnapshot(SID)).thenReturn(snapshot(SID));
        when(metricStore.query(any())).thenReturn(metricValues());
        when(definitionMapper.selectList(any())).thenReturn(definitions());
        stubAdsRows();
    }

    private void stubAdsRows() {
        Map<String, List<Map<String, Object>>> ads = adsRows();
        when(adsReader.selectBySnapshot(anyString(), anyString(), any()))
                .thenAnswer(invocation -> ads.getOrDefault(invocation.getArgument(0), List.of()));
    }

    /** v1.3：只替换热度榜表，用于构造分页/平局场景（其余表沿用 {@link #adsRows()} 默认夹具）。 */
    private void stubHotRows(List<Map<String, Object>> rows) {
        when(adsReader.selectBySnapshot(eq("ads_hot_product_m"), anyString(), any())).thenReturn(rows);
    }

    /** v1.4：只替换销售趋势表，用于构造净额缺列/畸形值场景（其余表沿用 {@link #adsRows()} 默认夹具）。 */
    private void stubTrendRows(List<Map<String, Object>> rows) {
        when(adsReader.selectBySnapshot(eq("ads_sale_trend_m"), anyString(), any())).thenReturn(rows);
    }

    /** 生成 count 行热度榜，rank_no 1..count，**故意倒序放入**以证明排序不依赖读取顺序。 */
    private static List<Map<String, Object>> hotRows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int rank = count; rank >= 1; rank--) {
            rows.add(hotRow(rank, rank * 10L, "P" + rank));
        }
        return rows;
    }

    private static Map<String, Object> hotRow(int rank, long productId, String name) {
        return row("dt", "20260901", "product_id", productId, "product_name", name, "heat_score",
                new BigDecimal(rank + ".0000"), "pv", 1L, "fav", 0L, "cart", 0L, "buy", 0L, "rank_no", rank);
    }

    private static MetricSnapshot snapshot(String snapshotId) {
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setSnapshotId(snapshotId);
        snapshot.setBusinessTime(LocalDateTime.of(2026, 9, 1, 0, 0, 0));
        snapshot.setDataUpdatedAt(LocalDateTime.of(2026, 9, 1, 0, 0, 0));
        snapshot.setDefinitionVersion("v2");
        snapshot.setPipelineRunId(24L);
        snapshot.setSource("spark-ads"); // §17.6：成功快照只接受 spark-ads（发布侧写入，读侧原样回显）
        snapshot.setStatus(MetricSnapshot.STATUS_ACTIVE);
        return snapshot;
    }

    private static List<MetricValue> metricValues() {
        return List.of(
                value("avg_order_value", "408.4000", "元", "v1"),
                value("full_refund_rate", "0.2000", "", "v1"),
                value("gmv", "2042.0000", "元", "v1"),
                value("net_sale", "1493.0000", "元", "v1"),
                value("paid_order_cnt", "5.0000", "单", "v1"),
                value("pv", "7.0000", "次", "v1"),
                value("refund_rate", "0.6000", "", "v2"));
    }

    private static MetricValue value(String code, String value, String unit, String version) {
        MetricValue metric = new MetricValue();
        metric.setSnapshotId(SID);
        metric.setMetricCode(code);
        metric.setMetricValue(new BigDecimal(value));
        metric.setUnit(unit);
        metric.setPeriod("day:2026-09-01");
        metric.setDefinitionVersion(version);
        return metric;
    }

    private static List<MetricDefinition> definitions() {
        MetricDefinition gmv = new MetricDefinition();
        gmv.setMetricCode("gmv");
        gmv.setMetricName("销售额(GMV)");
        gmv.setFormula("sum(paid_amount)");
        gmv.setUnit("元");
        MetricDefinition pv = new MetricDefinition();
        pv.setMetricCode("pv");
        pv.setMetricName("浏览量");
        pv.setUnit("次");
        return List.of(gmv, pv);
    }

    private static Map<String, List<Map<String, Object>>> adsRows() {
        Map<String, List<Map<String, Object>>> ads = new LinkedHashMap<>();
        ads.put("ads_sale_trend_m", List.of(
                row("dt", "20260831", "order_count", 2L, "buyer_count", 2L, "sale_amount", new BigDecimal("500.00"),
                        "avg_order_value", new BigDecimal("250.00"), "net_sale_amount", new BigDecimal("500.00")),
                row("dt", "20260901", "order_count", 5L, "buyer_count", 3L, "sale_amount", new BigDecimal("2042.00"),
                        "avg_order_value", new BigDecimal("408.40"), "net_sale_amount", new BigDecimal("1493.00"))));
        ads.put("ads_active_trend_m", List.of(
                row("dt", "20260901", "dau", 3L, "behavior_count", 14L)));
        ads.put("ads_behavior_funnel_m", List.of(
                row("dt", "20260901", "stage", "order", "user_count", 3L, "conversion_rate", new BigDecimal("1.5000"),
                        "overall_buy_rate", new BigDecimal("1.0000")),
                row("dt", "20260901", "stage", "view", "user_count", 3L, "conversion_rate", null,
                        "overall_buy_rate", new BigDecimal("1.0000")),
                row("dt", "20260901", "stage", "pay", "user_count", 3L, "conversion_rate", new BigDecimal("1.0000"),
                        "overall_buy_rate", new BigDecimal("1.0000")),
                row("dt", "20260901", "stage", "intent", "user_count", 2L, "conversion_rate", new BigDecimal("0.6667"),
                        "overall_buy_rate", new BigDecimal("1.0000"))));
        ads.put("ads_hot_product_m", List.of(
                row("dt", "20260901", "product_id", 3L, "product_name", "保温杯", "heat_score",
                        new BigDecimal("11.0904"), "pv", 1L, "fav", 1L, "cart", 1L, "buy", 3L, "rank_no", 1),
                row("dt", "20260901", "product_id", 2L, "product_name", "机械键盘", "heat_score",
                        new BigDecimal("10.3972"), "pv", 3L, "fav", 0L, "cart", 1L, "buy", 3L, "rank_no", 2),
                row("dt", "20260901", "product_id", 1L, "product_name", "无线耳机", "heat_score",
                        new BigDecimal("10.0574"), "pv", 2L, "fav", 1L, "cart", 1L, "buy", 2L, "rank_no", 3),
                row("dt", "20260901", "product_id", 4L, "product_name", "数据线", "heat_score",
                        new BigDecimal("0.6931"), "pv", 1L, "fav", 0L, "cart", 0L, "buy", 0L, "rank_no", 4)));
        ads.put("ads_product_conversion_m", List.of(
                row("dt", "20260901", "product_id", 4L, "pv_users", 1L, "buy_users", 0L, "conversion_rate",
                        new BigDecimal("0.0000")),
                row("dt", "20260901", "product_id", 1L, "pv_users", 2L, "buy_users", 2L, "conversion_rate",
                        new BigDecimal("1.0000")),
                row("dt", "20260901", "product_id", 3L, "pv_users", 1L, "buy_users", 1L, "conversion_rate",
                        new BigDecimal("1.0000")),
                row("dt", "20260901", "product_id", 2L, "pv_users", 3L, "buy_users", 1L, "conversion_rate",
                        new BigDecimal("0.3333"))));
        ads.put("ads_data_quality_m", List.of(
                row("dt", "20260901", "rule_code", "AMOUNT_RECONCILE", "passed", 1),
                row("dt", "20260901", "rule_code", "ENUM_WHITELIST", "passed", 1),
                row("dt", "20260901", "rule_code", "EVENT_ID_UNIQUE", "passed", 0),
                row("dt", "20260901", "rule_code", "REQUIRED_FIELD_NULL_RATE", "passed", 1)));
        ads.put("ads_user_profile_m", List.of(
                row("dt", "20260901", "user_id", 1L, "r", 5, "f", 2, "m", 3, "value_group", "一般发展",
                        "favorite_category", 11L, "lifecycle_state", "活跃", "rule_version", "rfm-v1",
                        "calc_date", "20260901", "last_buy_date", "2026-09-01"),
                row("dt", "20260901", "user_id", 2L, "r", 4, "f", 1, "m", 2, "value_group", "一般发展",
                        "favorite_category", 21L, "lifecycle_state", "活跃", "rule_version", "rfm-v1",
                        "calc_date", "20260901", "last_buy_date", "2026-09-01"),
                row("dt", "20260901", "user_id", 3L, "r", 3, "f", 3, "m", 1, "value_group", "一般挽留",
                        "favorite_category", 11L, "lifecycle_state", "活跃", "rule_version", "rfm-v1",
                        "calc_date", "20260901", "last_buy_date", "2026-09-01")));
        return ads;
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return row;
    }
}
