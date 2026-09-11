package com.graduation.analytics.analysis;

import com.graduation.analytics.analysis.AnalysisService.FunnelData;
import com.graduation.analytics.analysis.AnalysisService.FunnelStage;
import com.graduation.analytics.analysis.AnalysisService.MetricItem;
import com.graduation.analytics.analysis.AnalysisService.OverviewData;
import com.graduation.analytics.analysis.AnalysisService.ProductConversion;
import com.graduation.analytics.analysis.AnalysisService.ProductsData;
import com.graduation.analytics.analysis.AnalysisService.RfmData;
import com.graduation.analytics.analysis.AnalysisService.SalesData;
import com.graduation.analytics.analysis.AnalysisService.UsersData;
import com.graduation.analytics.metric.MetricAdsReader;
import com.graduation.analytics.metric.MetricQualityGate;
import com.graduation.analytics.metric.MySqlMetricStore;
import com.graduation.analytics.metric.dict.MetricDefinition;
import com.graduation.analytics.metric.dict.MetricDefinitionMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R7-4 真库集成测试（默认跳过：需 {@code -Dmetric.it=true} 且 surefire 显式指定本类）。
 *
 * <p>用途：用**线上真实 ACTIVE 快照 {@value #SID}** 证明看板切换后的六个端点拿到的是指标库里的真值，
 * 而不是 JSON 明细或服务端重算值。黄金值来自指导书 §5 / 契约 §5：GMV 2042.00、订单 5、PV 7、退款率 0.6000。</p>
 *
 * <p>只读：只用 metric_read / meta_app 两个只读账号 SELECT，不写任何库、不清理任何数据
 * （与写入型 IT 的合成命名空间策略不同，本类不产生副作用）。</p>
 */
@EnabledIfSystemProperty(named = "metric.it", matches = "true")
class AnalysisGoldenMySqlIT {

    /** 真库当前 ACTIVE 快照（analytics_metric.metric_snapshot） */
    private static final String SID = "S20260901_24";

    private static JdbcTemplate metricRead;
    private static JdbcTemplate meta;
    private static AnalysisService service;

    @BeforeAll
    static void setUp() {
        metricRead = new JdbcTemplate(dataSource("analytics_metric", "metric_read", "metric_read_pw_2026"));
        meta = new JdbcTemplate(dataSource("analytics_meta", "meta_app", "meta_app_pw_2026"));

        MySqlMetricStore store = new MySqlMetricStore(metricRead, metricRead); // 写源在本类用不到
        MetricAdsReader reader = new MetricAdsReader(metricRead);
        MetricDefinitionMapper dictionary = mock(MetricDefinitionMapper.class);
        when(dictionary.selectList(any())).thenReturn(dictionaryFromMeta());

        service = new AnalysisService(reader, store, new MetaQualityGate(meta), dictionary, new RfmService(reader));
    }

    @Test
    @DisplayName("运营总览：信封元数据来自快照行，指标含黄金值 GMV/订单/PV/退款率")
    void overviewMatchesGoldenValues() {
        AnalysisViewModel<OverviewData> model = service.overview(null, null, null);

        assertThat(model.snapshotId()).isEqualTo(SID);
        assertThat(model.businessTime()).isEqualTo("2026-09-01T00:00:00");
        assertThat(model.dataUpdatedAt()).isEqualTo("2026-09-01T00:00:00");
        assertThat(model.definitionVersion()).isEqualTo("v2");
        assertThat(model.qualityStatus()).isEqualTo(MetricQualityGate.PASS);
        assertThat(model.warnings()).isEmpty();

        OverviewData data = model.data();
        assertThat(metric(data, "gmv")).isEqualByComparingTo("2042.00");
        assertThat(metric(data, "paid_order_cnt")).isEqualByComparingTo("5");
        assertThat(metric(data, "pv")).isEqualByComparingTo("7");
        assertThat(metric(data, "refund_rate")).isEqualByComparingTo("0.6000");
        assertThat(metric(data, "net_sale")).isEqualByComparingTo("1493.00");
        assertThat(metric(data, "full_refund_rate")).isEqualByComparingTo("0.2000");
        assertThat(metric(data, "avg_order_value")).isEqualByComparingTo("408.40");
        assertThat(metric(data, "uv")).isEqualByComparingTo("3");

        // 趋势按 dt 升序且 dt 归一化为 ISO（真库 ADS 的 dt 实际落库为 yyyyMMdd）
        assertThat(data.salesTrend()).isNotEmpty();
        assertThat(data.salesTrend().get(0).date()).isEqualTo("2026-09-01");
        assertThat(data.salesTrend().get(0).saleAmount()).isEqualByComparingTo("2042.00");
        assertThat(data.salesTrend().get(0).orderCount()).isEqualTo(5L);
        assertThat(data.salesTrend().get(0).buyerCount()).isEqualTo(3L);
        assertThat(data.activeTrend()).isNotEmpty();
        assertThat(data.activeTrend().get(0).dau()).isEqualTo(3L);
        assertThat(data.activeTrend().get(0).behaviorCount()).isEqualTo(14L);

        // 质量卡是 ADS 规则明细；真库有 1 条规则未通过（与契约示例的 4/4/[] 不同，属真实数据）
        assertThat(data.quality().ruleCount()).isEqualTo(4);
        assertThat(data.quality().passedCount()).isEqualTo(3);
        assertThat(data.quality().failedRules()).containsExactly("EVENT_ID_UNIQUE");
        assertThat(data.metricDictionary()).isNotEmpty();
        assertThat(data.metricDictionary()).extracting(AnalysisService.DictionaryItem::metricCode).contains("gmv");
    }

    @Test
    @DisplayName("销售分析：四项取 metric_value 原值（不重算），维度表缺失给降级警告")
    void salesMatchesGoldenValues() {
        AnalysisViewModel<SalesData> model = service.sales(null, null, null);

        assertThat(model.snapshotId()).isEqualTo(SID);
        assertThat(model.data().gmv()).isEqualByComparingTo("2042.00");
        assertThat(model.data().netSale()).isEqualByComparingTo("1493.00");
        assertThat(model.data().refundRate()).isEqualByComparingTo("0.6000");
        assertThat(model.data().fullRefundRate()).isEqualByComparingTo("0.2000");
        assertThat(model.data().trend()).isNotEmpty();
        assertThat(model.data().byCategory()).isEmpty();
        assertThat(model.data().byRegion()).isEmpty();
        assertThat(model.warnings()).containsExactly(AnalysisViewModel.WARN_UNKNOWN_DIMENSION_TABLE);
    }

    @Test
    @DisplayName("商品分析：热度榜与转化取自 ADS 排行表")
    void productsComeFromAdsRankTables() {
        AnalysisViewModel<ProductsData> model = service.products(null, 10, null, null);

        assertThat(model.data().hot()).hasSize(4);
        assertThat(model.data().hot().get(0).rank()).isEqualTo(1);
        assertThat(model.data().hot().get(0).productName()).isEqualTo("保温杯");
        assertThat(model.data().hot().get(0).heat()).isEqualByComparingTo("11.0904");
        assertThat(model.data().hot().get(0).pv()).isEqualTo(1L);
        assertThat(model.data().hot().get(0).buy()).isEqualTo(3L);

        assertThat(model.data().conversion()).hasSize(4);
        ProductConversion keyboard = model.data().conversion().stream()
                .filter(item -> item.productId() == 2L).findFirst().orElseThrow();
        assertThat(keyboard.pvUsers()).isEqualTo(3L);
        assertThat(keyboard.buyUsers()).isEqualTo(1L);
        assertThat(keyboard.conversionRate()).isEqualByComparingTo("0.3333");
    }

    @Test
    @DisplayName("行为漏斗：四阶段规范顺序，首阶段 rate 真库为 NULL 就返回 null")
    void funnelComesFromAdsFunnelTable() {
        AnalysisViewModel<FunnelData> model = service.funnel(null, null);

        assertThat(model.data().stages()).extracting(FunnelStage::stage)
                .containsExactly("view", "intent", "order", "pay");
        assertThat(model.data().stages()).extracting(FunnelStage::users).containsExactly(3L, 2L, 3L, 3L);
        assertThat(model.data().stages().get(0).rate()).isNull();
        assertThat(model.data().stages().get(1).rate()).isEqualByComparingTo("0.6667");
        assertThat(model.data().overallBuyRate()).isEqualByComparingTo("1.0000");
        assertThat(model.data().windowNote()).contains("v2");
    }

    @Test
    @DisplayName("用户分群/RFM：聚合来自 ads_user_profile_m，八类矩阵补齐，金额列缺失显式降级")
    void rfmComesFromProfileAggregation() {
        AnalysisViewModel<UsersData> users = service.users(null, null, null);

        assertThat(users.data().rfmSegments()).extracting(RfmService.RfmSegment::valueGroup)
                .containsExactly("一般发展", "一般挽留");
        assertThat(users.data().rfmSegments().get(0).users()).isEqualTo(2L);
        assertThat(users.data().rfmSegments().get(0).share()).isEqualByComparingTo("0.6667");
        assertThat(users.data().rfmSegments().get(0).avgRecencyDays()).isEqualByComparingTo("0");
        assertThat(users.data().rfmSegments()).allSatisfy(segment -> assertThat(segment.amount()).isNull());
        assertThat(users.data().ruleVersion()).isEqualTo("rfm-v1");
        assertThat(users.data().lifecycle()).extracting(RfmService.LifecycleState::state).containsExactly("活跃");
        assertThat(users.data().lifecycle().get(0).users()).isEqualTo(3L);
        assertThat(users.data().preference()).extracting(RfmService.CategoryPreference::categoryId)
                .containsExactly(11L, 21L);
        assertThat(users.warnings()).containsExactly(AnalysisViewModel.WARN_RFM_AMOUNT_UNAVAILABLE);

        AnalysisViewModel<RfmData> rfm = service.rfm(null, 50);
        assertThat(rfm.filters()).containsEntry("limit", 50).containsEntry("snapshotId", SID);
        assertThat(rfm.data().rfmMatrix()).hasSize(8);
        assertThat(rfm.data().rfmMatrix()).extracting(RfmService.RfmSegment::valueGroup)
                .containsExactlyElementsOf(RfmService.VALUE_GROUPS);
        assertThat(rfm.data().ruleVersion()).isEqualTo("rfm-v1");
        assertThat(rfm.warnings()).containsExactly(AnalysisViewModel.WARN_RFM_AMOUNT_UNAVAILABLE);
    }

    @Test
    @DisplayName("显式指定不存在的快照：返回 UNKNOWN_SNAPSHOT 空信封，不回退 ACTIVE")
    void unknownSnapshotDoesNotFallBackToActive() {
        AnalysisViewModel<SalesData> model = service.sales("S-NOT-EXIST-IN-METRIC", null, null);

        assertThat(model.snapshotId()).isNull();
        assertThat(model.warnings()).containsExactly(AnalysisViewModel.WARN_UNKNOWN_SNAPSHOT);
        assertThat((Object) model.data()).isEqualTo(Map.of());
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────────

    private static BigDecimal metric(OverviewData data, String metricCode) {
        return data.metrics().stream().filter(item -> metricCode.equals(item.metricCode()))
                .map(MetricItem::value).findFirst().orElse(null);
    }

    private static List<MetricDefinition> dictionaryFromMeta() {
        return meta.query("SELECT metric_code, metric_name, formula, unit FROM metric_definition",
                (rs, rowNum) -> {
                    MetricDefinition definition = new MetricDefinition();
                    definition.setMetricCode(rs.getString("metric_code"));
                    definition.setMetricName(rs.getString("metric_name"));
                    definition.setFormula(rs.getString("formula"));
                    definition.setUnit(rs.getString("unit"));
                    return definition;
                });
    }

    /**
     * 测试用的质量门（与 warehouse-pipeline 的 DataQualityGate 同口径）。
     *
     * <p>为什么在测试里重写一份：metric-analysis 不依赖 warehouse-pipeline（会形成模块环），
     * 生产实现由 platform-app 装配；本 IT 只验证"分析侧读到的结论"，
     * DataQualityGate 本身的判定由 warehouse-pipeline 的 L0 单测覆盖。</p>
     */
    private static final class MetaQualityGate implements MetricQualityGate {

        private final JdbcTemplate metaJdbc;

        private MetaQualityGate(JdbcTemplate metaJdbc) {
            this.metaJdbc = metaJdbc;
        }

        @Override
        public String statusForRun(Long pipelineRunId) {
            if (pipelineRunId == null) {
                return UNKNOWN;
            }
            List<Map<String, Object>> rows = metaJdbc.queryForList(
                    "SELECT severity, passed FROM data_quality_result WHERE run_id = ?", pipelineRunId);
            if (rows.isEmpty()) {
                return UNKNOWN;
            }
            boolean blockingFailed = rows.stream().anyMatch(row ->
                    BLOCKING.equalsIgnoreCase(String.valueOf(row.get("severity")))
                            && !(row.get("passed") instanceof Number number && number.intValue() == 1));
            return blockingFailed ? FAIL : PASS;
        }
    }

    private static DataSource dataSource(String database, String user, String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/" + database + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        ds.setUsername(user);
        ds.setPassword(password);
        return ds;
    }
}
