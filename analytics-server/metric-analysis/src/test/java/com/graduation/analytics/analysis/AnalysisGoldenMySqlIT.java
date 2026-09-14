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
import com.graduation.analytics.metric.QualityRuleCatalog;
import com.graduation.analytics.metric.RuleSeverity;
import com.graduation.analytics.metric.dict.MetricDefinition;
import com.graduation.analytics.metric.dict.MetricDefinitionMapper;
import com.graduation.analytics.testsupport.TestIsolationGuard;
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
 * <p>用途：用**线上真实快照 {@value #SID}** 证明看板切换后的六个端点拿到的是指标库里的真值，
 * 而不是 JSON 明细或服务端重算值。黄金值来自指导书 §5 / 契约 §5：GMV 2042.00、订单 5、PV 7、退款率 0.6000。</p>
 *
 * <p><b>F-88 修正（实测）</b>：{@value #SID} 在 analytics_metric.metric_snapshot 里是
 * {@code status=ARCHIVED}（{@code pipeline_run_id=24}；2026-09-10 发布时它是线上 ACTIVE，
 * 其后指针推进到 {@code S20260901_47}）——原文案"线上真实快照"是过时描述，
 * 本类只把它当"历史黄金数据集"读。</p>
 *
 * <p><b>F-94 二次修正（实测，2026-09-12 独立验证）</b>：{@value #SID} 所属的 run 24 在
 * analytics_meta.data_quality_result 里有 3 条 <b>字面</b> {@code severity='ERROR', passed=0} 的行
 * （{@code EVENT_ID_UNIQUE} / {@code ADS_STAGING_SNAPSHOT_ISOLATION} / {@code PUB_DQ_EVENT_ID_UNIQUE}），
 * 但按读侧归一化口径这 3 条都不按库中字面 severity 判，因此 {@code qualityStatus} 当时读到 <b>PASS</b>。</p>
 *
 * <p><b>⚠ F-88 四次修正（2026-09-14，实测）：上面的 PASS 结论已被证伪，不得再引用。</b>
 * 只读 SELECT analytics_meta.data_quality_result 实测：run 24 的 {@code EVENT_ID_UNIQUE}
 * {@code error_rate=0.020408} 对 {@code threshold='<=0.0005'}（约 40 倍），
 * {@code PUB_DQ_EVENT_ID_UNIQUE} {@code error_rate=0.071429}（约 143 倍），两行 {@code passed=0}
 * 都是**真实超阈值**，不是误判。旧口径把这两个码**无条件**映射为 WARN，等于把超阈值的高重复率
 * 直接放行，违反 §7.3.1 line 522「测试不得为通过把高重复率直接放行」；现改为
 * {@code THRESHOLD_OBSERVATION}：未超阈值＝观察项，超阈值＝阻断。因此 run 24/47 在新口径下
 * **合法地翻为 FAIL**。</p>
 *
 * <p><b>读侧归一化的适用范围（必须写清，不得读成「全局永久降级」）</b>：
 * 归一化的依据是 {@code quality_rule_definition} 里**明确版本**的规则定义，
 * 由 {@link QualityRuleCatalog} 的冻结集按 {@code (source_scope, rule_code, version)} 解析；
 * 它**不是**「凡是字面 severity 都能被覆盖」的全局开关，也**不是**永久降级 ——
 * 档位变动必须发新 {@code version} 并留原始结果字段（§7.3.1 line 520/524）。
 * 本类用 {@code QualityRuleCatalog.DEFAULT.freeze(null)} 只是**过渡形态**：库表尚未落地
 * （实测 {@code quality_rule_definition} 0 命中），冻结集当前来自代码内快照，
 * 故「不同 run 之间随库中版本变化」这一层**尚未闭合**，不能声称版本化已闭合。</p>
 *
 * <p><b>F-88 三次修正（实测，2026-09-12）：信封断言与 ACTIVE 指针解耦。</b>
 * 上面 F-88 一段声称"断言本身不受 ACTIVE 指针变化影响"，但 6 处读调用都传 {@code null}，
 * 于是 {@code pin(null)} 走 {@code adsReader.activeSnapshotId()}（当前实测为 {@code S20260901_47}），
 * 断言反而**跟着指针漂移** —— 实测 3 个用例报 {@code expected: "S20260901_24" but was: "S20260901_47"}。
 * 现改为**所有读调用显式点名 {@value #SID}**（{@code pin(explicit)} 按快照号直查
 * {@code metricStore.findSnapshot}，不要求 ACTIVE），使断言真正与指针无关。
 * 这不是放宽断言：期望值一个没改，只是让调用命中被测的那个数据集。</p>
 *
 * <p>只读：只用 metric_read / meta_app 两个只读账号 SELECT，不写任何库、不清理任何数据
 * （与写入型 IT 的合成命名空间策略不同，本类不产生副作用）。</p>
 */
@EnabledIfSystemProperty(named = "metric.it", matches = "true")
class AnalysisGoldenMySqlIT {

    /**
     * 被测的历史黄金数据集快照号（analytics_metric.metric_snapshot）。
     * 读调用一律显式点名它，从而与"当前 ACTIVE 是谁"解耦（见类注释 F-88 三次修正）。
     */
    private static final String SID = "S20260901_24";

    /**
     * 被读的两个库名。**不是凭据**，故保留为常量（本类只读它们，且与线上正式库同名，
     * 库名本身不构成越权）；真正需要外部注入的是**账号与口令**，见 {@link #dataSource}。
     */
    private static final String METRIC_DB = "analytics_metric";
    private static final String META_DB = "analytics_meta";

    private static JdbcTemplate metricRead;
    private static JdbcTemplate meta;
    private static AnalysisService service;

    @BeforeAll
    static void setUp() {
        // 账号一律走 TestIsolationGuard（系统属性 v25.it.* 或隔离档案），**类内不再有明文口令**。
        // 缺配置时 requiredProperty 直接抛 MissingConfigurationException 拒绝运行，
        // 不存在「缺省用正式读写账号」这条兜底路径。
        metricRead = new JdbcTemplate(dataSource(METRIC_DB, "metric.read.user", "metric.read.password"));
        meta = new JdbcTemplate(dataSource(META_DB, "meta.app.user", "meta.app.password"));

        MySqlMetricStore store = new MySqlMetricStore(metricRead, metricRead); // 写源在本类用不到
        MetricAdsReader reader = new MetricAdsReader(metricRead);
        MetricDefinitionMapper dictionary = mock(MetricDefinitionMapper.class);
        when(dictionary.selectList(any())).thenReturn(dictionaryFromMeta());

        service = new AnalysisService(reader, store, new MetaQualityGate(meta), dictionary, new RfmService(reader));
    }

    @Test
    @DisplayName("运营总览：信封元数据来自快照行，指标含黄金值 GMV/订单/PV/退款率")
    void overviewMatchesGoldenValues() {
        AnalysisViewModel<OverviewData> model = service.overview(SID, null, null);

        assertThat(model.snapshotId()).isEqualTo(SID);
        assertThat(model.businessTime()).isEqualTo("2026-09-01T00:00:00");
        assertThat(model.dataUpdatedAt()).isEqualTo("2026-09-01T00:00:00");
        assertThat(model.definitionVersion()).isEqualTo("v2");
        // F-88 四次修正（**本条期望值已翻转，2026-09-14**）：
        // 旧注释写「这 3 条口径下都是 WARN（不阻断）⇒ 归一化后为 PASS」——**该结论已证伪、不得再引用**。
        // 实测 run 24 的 EVENT_ID_UNIQUE error_rate=0.020408 对 threshold='<=0.0005'（约 40 倍）、
        // PUB_DQ_EVENT_ID_UNIQUE error_rate=0.071429（约 143 倍），两行 passed=0 都是**真实超阈值**。
        // 这两个码现为 THRESHOLD_OBSERVATION：resolve() 见 passed 未通过即升为 BLOCKING
        // （§7.3.1 line 522「超过阈值阻断」），故归一化后为 **FAIL**。
        assertThat(model.qualityStatus()).isEqualTo(MetricQualityGate.FAIL);
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
        AnalysisViewModel<SalesData> model = service.sales(SID, null, null);

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
        AnalysisViewModel<ProductsData> model = service.products(SID, 10, null, null);

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
        AnalysisViewModel<FunnelData> model = service.funnel(SID, null);

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
        AnalysisViewModel<UsersData> users = service.users(SID, null, null);

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

        AnalysisViewModel<RfmData> rfm = service.rfm(SID, 50);
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
     * 测试用的质量门（与 warehouse-pipeline 的 DataQualityGate 同口径，D-142 §1 + F-94 读侧归一化）。
     *
     * <p>为什么在测试里重写一份：metric-analysis 不依赖 warehouse-pipeline（会形成模块环），
     * 生产实现由 platform-app 装配；本 IT 只验证"分析侧读到的结论"，
     * DataQualityGate 本身的判定由 warehouse-pipeline 的 L0 单测覆盖。</p>
     *
     * <p><b>F-88/F-94 教训（实测）</b>：本类曾按旧口径只判 {@code BLOCKING}；生产口径改成
     * 「{@code BLOCKING}/{@code ERROR} 都阻断」后，同一份 run 24 数据出现**两个结论**
     * （生产门 FAIL、本复刻体 PASS）—— 复刻体不会跟着生产改。但**照字面 severity 判同样是错的**：
     * 库里那 3 条 {@code ERROR} 的规则码在目录口径里是 {@code WARN}，照字面判会把用户可见的质量卡片
     * 错误染红（F-94）。因此这里与生产实现一致，统一走
     * {@link RuleSeverity#blocks(String, String, Integer)}：**先按规则码归一化**，
     * 未登记码才回退字面 severity。</p>
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
                    "SELECT rule_code, severity, passed FROM data_quality_result WHERE run_id = ?", pipelineRunId);
            if (rows.isEmpty()) {
                return UNKNOWN;
            }
            // 口径来源统一为唯一所有者 RuleSeverity（§7.3.1 line 520：按冻结规则集解析）。
            // 旧写法 blocks(字面 severity, code, passed) 会**静默丢弃**第一个实参，该重载已删除；
            // 新签名里没有 severity 字面这个入参 —— 此处仍把 severity 查出来只为「原始结果字段可读」。
            QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze(null);
            boolean blockingFailed = rows.stream().anyMatch(row -> RuleSeverity.blocks(
                    rules,
                    row.get("rule_code") == null ? null : String.valueOf(row.get("rule_code")),
                    row.get("passed") instanceof Number number ? number.intValue() : null));
            return blockingFailed ? FAIL : PASS;
        }
    }

    /**
     * 数据源：**账号与口令只能来自 {@link TestIsolationGuard}**（系统属性 {@code v25.it.*}
     * 或隔离档案），类内不再有任何明文口令，也不存在「缺省用正式账号」这条兜底路径 ——
     * 缺配置时 {@code requiredProperty} 抛 {@code MissingConfigurationException} 直接拒绝运行。
     *
     * <p>与写入型 IT（{@code MetricAdsMySqlIT} / {@code MetricPublisherMySqlIT}）的差别：
     * 那两个类还要经 {@code verifyBeforeWrite} 证明「不是正式库、账号不在禁止清单内」，
     * 因为它们**会写**；本类经 L1 核实**全类无任何写语句（纯只读）**，故只需参数化凭据，
     * 不引入写入门禁（引入反而会要求隔离库，与本类「读历史黄金数据集」的用途冲突）。</p>
     *
     * <p>库名仍为常量（{@link #METRIC_DB} / {@link #META_DB}）：库名不是凭据，且本类必须读
     * 正式库里的历史快照 {@value #SID} 才能验黄金值。主机与端口可由 {@code v25.it.mysql.host}
     * 覆盖，默认 {@code 127.0.0.1:3306}。</p>
     */
    private static DataSource dataSource(String database, String userProperty, String passwordProperty) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + requiredProperty("mysql.host") + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        ds.setUsername(requiredProperty(userProperty));
        ds.setPassword(requiredProperty(passwordProperty));
        return ds;
    }

    private static String requiredProperty(String key) {
        return TestIsolationGuard.requiredProperty(key);
    }
}
