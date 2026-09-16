package com.graduation.analytics.metric.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.metric.MetricAdsCatalog;
import com.graduation.analytics.metric.MetricAdsWriter;
import com.graduation.analytics.metric.MySqlMetricStore;
import com.graduation.analytics.metric.publish.MetricPublisherPort.DefinitionRef;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishReport;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishRequest;
import com.graduation.analytics.testsupport.IsolationProfileCondition;
import com.graduation.analytics.testsupport.TestIsolationGuard;
import com.graduation.analytics.testsupport.TestIsolationGuard.DeletionTarget;
import com.graduation.analytics.testsupport.TestIsolationGuard.IsolationViolationException;
import com.graduation.analytics.testsupport.TestIsolationGuard.LiveFacts;
import com.graduation.analytics.testsupport.TestIsolationGuard.TestRunContext;
import com.graduation.analytics.testsupport.TestIsolationGuard.WorkScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R7-3 真库集成测试（**默认关闭**：没有登记测试隔离档案时整个类不执行，见
 * {@link IsolationProfileCondition} 与指导书 V2.5 §9.4）。
 *
 * <p>证明发布器最关键的两条安全性质（§17.5）：</p>
 * <ol>
 *   <li><b>成功</b>：ADS 宽表 + metric_value 落库，快照由 VERIFYING 切到 ACTIVE，旧 ACTIVE 归档；</li>
 *   <li><b>失败（写库后对账不通过）</b>：快照置 FAILED、本次写入的 ADS 行被补偿清理、
 *       <b>旧 ACTIVE 指针原样保留</b>——即"发不出去也不影响线上看板"。</li>
 * </ol>
 *
 * <p><b>V25-S01 整改</b>：过去本类写死 {@code analytics_metric} ＋ 正式写账号 {@code metric_pub}，
 * cleanup 还按固定 {@code runtime_profile_id = 999002} 批量 DELETE。现在：目标库/账号来自本次
 * {@link TestRunContext}；写前用实际连接核对库名＋服务实例指纹＋账号权限；快照号带 testRunId 前缀、
 * 档案 id 是本次自己登记的行；cleanup 先出清单再按「快照号 + 本次档案 id」删除；
 * {@link #productionDatabaseIsRejectedBeforeAnyWrite()} 是**负向测试**：给正式库配置必须**在任何写入之前**失败，
 * 且**不**在正式库试写再回滚。</p>
 *
 * <p><b>为什么隔离上下文放在 {@code @BeforeAll} 而不是 static 字段</b>：static 初始化在<b>类加载</b>时求值，
 * 那时 JUnit 尚未评估 {@link IsolationProfileCondition}，配置缺失会抛 {@code ExceptionInInitializerError}
 * 把整个测试套件打红，而不是按 §9.4「默认关闭」。放进 {@code @BeforeAll} 后顺序为
 * 「先判定启用 → 再加载配置 → 启用但配置非法仍硬失败」。</p>
 */
@ExtendWith(IsolationProfileCondition.class)
class MetricPublisherMySqlIT {

    private static final String DT = "20260901";

    private static TestRunContext context;
    private static String sidOk;
    private static String sidBad;

    private static final List<String> OWNED_ADS_TABLES = new ArrayList<>();

    static {
        for (MetricAdsCatalog spec : MetricAdsCatalog.ALL) {
            OWNED_ADS_TABLES.add(spec.name());
        }
    }

    private static JdbcTemplate publish;
    private static JdbcTemplate read;
    private static MetricAdsWriter writer;
    private static MetricPublisher publisher;
    private static ObjectMapper mapper;
    private static WorkScope scope;
    private static long profileId;

    @BeforeAll
    static void setUp() {
        // 到这里说明类已被判定为「启用」，因此配置缺失/非法必须硬失败，不能静默跳过
        context = TestIsolationGuard.loadContext();
        String run = context.testRunId();
        sidOk = run + "-pub-ok";
        sidBad = run + "-pub-bad";
        System.out.println("[MetricPublisherMySqlIT] 隔离上下文：" + context.redactedSummary());
        mapper = new ObjectMapper();
        // 键名不带 v25.it. 前缀：TestIsolationGuard.requiredProperty 内部会先查系统属性
        // -Dv25.it.<key>，再查隔离档案文件，两处都没有才拒绝（无正式目标兜底）。
        DataSource publishDs = dataSource("metric.publish.username", "metric.publish.password");
        DataSource readDs = dataSource("metric.read.username", "metric.read.password");

        LiveFacts publishFacts = TestIsolationGuard.verifyBeforeWrite(context, publishDs, context.metricDb());
        LiveFacts readFacts = TestIsolationGuard.verifyBeforeWrite(context, readDs, context.metricDb());
        System.out.println("[MetricPublisherMySqlIT] publish 连接事实：" + publishFacts.redactedSummary());
        System.out.println("[MetricPublisherMySqlIT] read 连接事实：" + readFacts.redactedSummary());

        publish = new JdbcTemplate(publishDs);
        read = new JdbcTemplate(readDs);
        writer = new MetricAdsWriter(publish);
        MetricPublishRepository repository = new MetricPublishRepository(publish, read);
        MySqlMetricStore store = new MySqlMetricStore(publish, read);
        publisher = new MetricPublisher(repository, writer, new AdsExportReader(), store,
                new MetricPublishValidator(), mapper);

        profileId = registerOwnProfile();
        scope = new WorkScope(context.testRunId(), profileId, new LinkedHashSet<>(List.of(sidOk, sidBad)),
                new LinkedHashSet<>(OWNED_ADS_TABLES));
        System.out.println("[MetricPublisherMySqlIT] 本次拥有范围：profileId=" + profileId
                + " snapshots=" + List.of(sidOk, sidBad));
        cleanup();
    }

    @AfterAll
    static void tearDown() {
        if (scope != null) {
            cleanup();
        }
    }

    @Test
    @DisplayName("成功发布 → ACTIVE 切换；写库后对账失败 → FAILED + 补偿清理 + 旧 ACTIVE 保留")
    void publishActivationAndFailureCompensation(@TempDir Path dir) throws Exception {
        // ── 阶段 1：正常发布 → ACTIVE ──
        Path okDir = Files.createDirectories(dir.resolve(sidOk));
        writeFixture(okDir, sidOk, allTables());
        PublishReport ok = publisher.publish(request(sidOk, okDir, fullDictionary()));

        assertThat(ok.ok()).as("失败码=%s 说明=%s checks=%s", ok.errorCode(), ok.message(),
                MetricPublishValidator.failedRules(ok.checks())).isTrue();
        assertThat(ok.adsRows()).isEqualTo(expectedAdsRows());
        assertThat(ok.metricValues()).isEqualTo(13);
        assertThat(activeSnapshot()).isEqualTo(sidOk);
        assertThat(status(sidOk)).isEqualTo("ACTIVE");
        assertThat(metricValueCount(sidOk)).isEqualTo(13);
        assertThat(overviewPv(sidOk)).isEqualTo(7L);
        assertThat(metricValue(sidOk, "refund_rate")).isEqualByComparingTo(new BigDecimal("0.6000"));
        assertThat(metricValue(sidOk, "repeat_rate")).isEqualByComparingTo(new BigDecimal("0.3333"));

        // ── 阶段 2：清单与 ADS 都正常，但字典缺 gmv → 写库后对账 BLOCKING 失败 ──
        Path badDir = Files.createDirectories(dir.resolve(sidBad));
        Map<String, DefinitionRef> partialDict = new LinkedHashMap<>(fullDictionary());
        partialDict.remove("gmv");
        writeFixture(badDir, sidBad, allTables());
        PublishReport bad = publisher.publish(request(sidBad, badDir, partialDict));

        assertThat(bad.ok()).isFalse();
        assertThat(bad.errorCode()).isEqualTo("MP_VERIFY_FAILED");
        assertThat(MetricPublishValidator.failedRules(bad.checks())).contains("MP_METRIC_DICT_VERSION");
        assertThat(status(sidBad)).isEqualTo("FAILED");
        assertThat(activeSnapshot()).as("失败不得改变 ACTIVE 指针").isEqualTo(sidOk);
        assertThat(metricValueCount(sidBad)).as("失败快照不得留下指标值").isZero();
        assertThat(adsRowCount(sidBad)).as("失败补偿必须清掉本次写入的 ADS 行").isZero();
        assertThat(metricValueCount(sidOk)).as("旧快照数据不受影响").isEqualTo(13);
    }

    /**
     * §9.4 负向测试：把目标库换成正式库 {@code analytics_metric} 时，必须在**任何写入之前**失败。
     *
     * <p>做法是 mock 写调用/无权限账号这一类"不产生副作用"的方式：这里用
     * {@link RejectingDataSource} —— 它连 {@code getConnection()} 都不会被调用；
     * 一旦 guard 的判断顺序退化（先连库再判断），本用例立刻变红。
     * <b>绝不在正式库试写再回滚。</b></p>
     */
    @Test
    @DisplayName("负向：正式库配置在任何写入之前被拒（不进正式库、不试写、不回滚）")
    void productionDatabaseIsRejectedBeforeAnyWrite() {
        RejectingDataSource probe = new RejectingDataSource();

        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(context, probe, "analytics_metric"))
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("analytics_metric")
                .hasMessageContaining("禁止");
        assertThat(probe.connectionAttempts()).as("正式库上不得建立任何连接").isZero();
        assertThat(probe.statementAttempts()).as("正式库上不得执行任何语句").isZero();
    }

    /** 负向：把库名换成正式库之外的其他库（只改 JDBC URL）同样被拒，证明「单改 URL 不算隔离」。 */
    @Test
    @DisplayName("负向：声明库不在登记隔离范围 → 写前被拒")
    void databaseOutsideRegisteredScopeIsRejected() {
        RejectingDataSource probe = new RejectingDataSource();
        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(context, probe, "analytics_metric_p103"))
                .isInstanceOf(IsolationViolationException.class);
        assertThat(probe.connectionAttempts()).isZero();
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private PublishRequest request(String sid, Path dir, Map<String, DefinitionRef> dict) {
        scope.requireOwnedSnapshot(sid);
        return new PublishRequest(profileId, 7, sid, DT, "2026-09-01T00:00", 99001L, dir, dict);
    }

    private static Map<String, DefinitionRef> fullDictionary() {
        Map<String, DefinitionRef> dict = new LinkedHashMap<>();
        dict.put("pv", new DefinitionRef("v1", "次"));
        dict.put("uv", new DefinitionRef("v1", "人"));
        dict.put("dau", new DefinitionRef("v1", "人"));
        dict.put("paid_order_cnt", new DefinitionRef("v1", "单"));
        dict.put("gmv", new DefinitionRef("v1", "元"));
        dict.put("net_sale", new DefinitionRef("v1", "元"));
        dict.put("avg_order_value", new DefinitionRef("v1", "元"));
        dict.put("refund_rate", new DefinitionRef("v2", ""));
        dict.put("full_refund_rate", new DefinitionRef("v1", ""));
        dict.put("repeat_rate", new DefinitionRef("v1", ""));
        dict.put("buy_rate", new DefinitionRef("v1", ""));
        // S3-08：收藏/加购次数两枚码（字典 metric-dictionary.md:21/:22，源表 dwd_user_behavior_detail）
        dict.put("fav_cnt", new DefinitionRef("v1", "次"));
        dict.put("cart_add_cnt", new DefinitionRef("v1", "次"));
        return dict;
    }

    /** 每张表一行（列集合严格等于 MetricAdsCatalog 白名单），行数 8，够验证落库/对账/补偿 */
    private static Map<String, List<Map<String, Object>>> allTables() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("pv", 7L);
        overview.put("uv", 3L);
        overview.put("dau", 3L);
        overview.put("order_count", 5L);
        overview.put("sale_amount", new BigDecimal("2042.00"));
        overview.put("net_sale_amount", new BigDecimal("1493.00"));
        overview.put("avg_order_value", new BigDecimal("408.40"));
        overview.put("refund_rate", new BigDecimal("0.6000"));
        overview.put("full_refund_rate", new BigDecimal("0.2000"));
        // S3-03 加列：复购率 + 观察期声明（period 应落成 window:2026-08-31..2026-09-01）
        overview.put("repeat_rate", new BigDecimal("0.3333"));
        overview.put("repeat_period_start", "2026-08-31");
        overview.put("repeat_period_end", "2026-09-01");
        // S3-08 加列：收藏/加购次数（与人数口径刻意不同：次数 > 人数，防止「拿人数冒次数」也能过）
        overview.put("fav_cnt", 4L);
        overview.put("cart_add_cnt", 6L);
        rows.put("ads_operation_overview_m", List.of(overview));

        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("order_count", 5L);
        trend.put("buyer_count", 3L);
        trend.put("sale_amount", new BigDecimal("2042.00"));
        trend.put("avg_order_value", new BigDecimal("408.40"));
        trend.put("net_sale_amount", new BigDecimal("1493.00")); // S3-02 加列：与大盘净额同比
        rows.put("ads_sale_trend_m", List.of(trend));

        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("stage", "pay");
        funnel.put("user_count", 3L);
        funnel.put("conversion_rate", new BigDecimal("1.0000"));
        funnel.put("overall_buy_rate", new BigDecimal("1.0000"));
        rows.put("ads_behavior_funnel_m", List.of(funnel));

        Map<String, Object> active = new LinkedHashMap<>();
        active.put("dau", 3L);
        active.put("behavior_count", 22L);
        rows.put("ads_active_trend_m", List.of(active));

        Map<String, Object> hot = new LinkedHashMap<>();
        hot.put("product_id", 11L);
        hot.put("product_name", "IT 商品");
        hot.put("heat_score", new BigDecimal("9.50"));
        hot.put("pv", 100L);
        hot.put("fav", 2L);
        hot.put("cart", 1L);
        hot.put("buy", 1L);
        hot.put("rank_no", 1);
        // S3-07：ADS 行必须携带热度权重定义版本（夹具与该列保持一致，列集合不一致会让本 IT 假红）
        hot.put("rule_version", "v1");
        rows.put("ads_hot_product_m", List.of(hot));

        Map<String, Object> conversion = new LinkedHashMap<>();
        conversion.put("product_id", 11L);
        conversion.put("pv_users", 3L);
        conversion.put("buy_users", 1L);
        conversion.put("conversion_rate", new BigDecimal("0.3333"));
        rows.put("ads_product_conversion_m", List.of(conversion));

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("user_id", 1001L);
        profile.put("r", 3);
        profile.put("f", 2);
        profile.put("m", new BigDecimal("500.00"));
        profile.put("value_group", "中价值");
        profile.put("active_level", "活跃");
        profile.put("favorite_category", 1);
        profile.put("last_active_date", "2026-09-01");
        profile.put("last_buy_date", "2026-09-01");
        profile.put("lifecycle_state", "活跃期");
        profile.put("rule_version", "v1");
        profile.put("calc_date", "2026-09-01");
        // S3-01：画像表补 R/F/M 原值与观察窗口（§11.4 L447）；发布侧 INSERT 按 MetricAdsSpec 列集合逐列绑定，
        // 夹具缺列会让本 IT 在不该失败的地方失败（本机无 3306 写权限，只能靠列集合与其保持一致）。
        profile.put("r_days", 0);
        profile.put("f_count", 2L);
        profile.put("m_amount", new BigDecimal("500.00"));
        profile.put("period_start", "2026-09-01");
        profile.put("period_end", "2026-09-01");
        rows.put("ads_user_profile_m", List.of(profile));

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("rule_code", "EVENT_ID_UNIQUE");
        quality.put("check_count", 49L);
        quality.put("error_count", 0L);
        quality.put("error_rate", new BigDecimal("0.0000"));
        quality.put("passed", 1);
        quality.put("threshold", "error_count=0");
        // S3-05：质量大盘补规则定义版本列；夹具与该列保持一致（同 profile 的注释：列集合不对齐会让本 IT 无辜变红）
        quality.put("rule_version", 1);
        rows.put("ads_data_quality_m", List.of(quality));
        return rows;
    }

    private static int expectedAdsRows() {
        return allTables().values().stream().mapToInt(List::size).sum();
    }

    /** 按 MetricAdsCatalog 逐表写 JSONL + `_export.json` 清单（路径用正斜杠，与 Spark 侧一致） */
    private void writeFixture(Path dir, String sid, Map<String, List<Map<String, Object>>> rows) throws Exception {
        scope.requireOwnedSnapshot(sid);
        List<Map<String, Object>> tables = new ArrayList<>();
        int total = 0;
        for (MetricAdsCatalog spec : MetricAdsCatalog.ALL) {
            List<Map<String, Object>> tableRows = rows.getOrDefault(spec.name(), List.of());
            total += tableRows.size();
            Path file = dir.resolve(spec.name() + ".jsonl");
            StringBuilder jsonl = new StringBuilder();
            for (Map<String, Object> row : tableRows) {
                Map<String, Object> ordered = new LinkedHashMap<>();
                for (String column : spec.columns()) {
                    ordered.put(column, row.get(column));
                }
                jsonl.append(mapper.writeValueAsString(ordered)).append('\n');
            }
            Files.writeString(file, jsonl.toString(), StandardCharsets.UTF_8);

            Map<String, Object> table = new LinkedHashMap<>();
            table.put("hiveTable", "dw_ads." + spec.name().replace("_m", ""));
            table.put("mysqlTable", spec.name());
            table.put("rowCount", tableRows.size());
            table.put("columns", spec.columns());
            table.put("hivePath", context.hdfsRoot() + "/warehouse/dw_ads.db/"
                    + spec.name() + "/snapshot_id=" + sid + "/dt=" + DT);
            table.put("exportFile", file.toAbsolutePath().toString().replace('\\', '/'));
            // S3-06：清单必须带内容摘要（MetricExportManifest.read 缺该字段即拒绝整份清单）
            table.put("checksum", MetricExportManifest.crc32(file));
            tables.add(table);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("snapshotId", sid);
        manifest.put("businessDate", DT);
        manifest.put("dt", DT);
        manifest.put("source", "spark-ads");
        manifest.put("generatedAt", "2026-09-10T00:00");
        manifest.put("totalRows", total);
        manifest.put("tables", tables);
        Files.writeString(dir.resolve("_export.json"), mapper.writeValueAsString(manifest),
                StandardCharsets.UTF_8);
    }

    // ── 真库探针 ────────────────────────────────────────────────────────────

    private String activeSnapshot() {
        List<String> ids = read.queryForList(
                "SELECT snapshot_id FROM metric_snapshot WHERE runtime_profile_id=? AND active_flag=1",
                String.class, profileId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String status(String sid) {
        return read.queryForObject("SELECT status FROM metric_snapshot WHERE snapshot_id=?", String.class, sid);
    }

    private int metricValueCount(String sid) {
        Integer n = read.queryForObject("SELECT COUNT(*) FROM metric_value WHERE snapshot_id=?", Integer.class, sid);
        return n == null ? 0 : n;
    }

    private BigDecimal metricValue(String sid, String code) {
        return read.queryForObject("SELECT metric_value FROM metric_value WHERE snapshot_id=? AND metric_code=?",
                BigDecimal.class, sid, code);
    }

    private long overviewPv(String sid) {
        Long pv = read.queryForObject("SELECT pv FROM ads_operation_overview_m WHERE snapshot_id=?",
                Long.class, sid);
        return pv == null ? -1L : pv;
    }

    private int adsRowCount(String sid) {
        int total = 0;
        for (MetricAdsCatalog spec : MetricAdsCatalog.ALL) {
            Integer n = read.queryForObject("SELECT COUNT(*) FROM " + spec.name() + " WHERE snapshot_id=?",
                    Integer.class, sid);
            total += n == null ? 0 : n;
        }
        return total;
    }

    // ── 自有档案登记与 cleanup ──────────────────────────────────────────────

    /** 登记本 run 自己的 runtime_profile 行（不使用可复用的固定 id）。 */
    private static long registerOwnProfile() {
        String profileCode = "v25it-" + context.testRunId();
        publish.update("INSERT INTO runtime_profile (profile_code, profile_name, type, status, landing_uri, "
                        + "spark_master, metric_store_type, timezone, version, credential_ref) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE profile_name = VALUES(profile_name), updated_at = CURRENT_TIMESTAMP(3)",
                profileCode, "V25-S01 隔离测试档案 " + context.testRunId(), "LOCAL", "ACTIVE", context.hdfsRoot() + "/landing",
                "local[1]", "MYSQL", "Asia/Shanghai", 1, context.credentialsRef());
        Long id = publish.queryForObject("SELECT id FROM runtime_profile WHERE profile_code = ?", Long.class, profileCode);
        assertThat(id).as("本次运行必须有自己的 runtime_profile 行").isNotNull();
        System.out.println("[MetricPublisherMySqlIT] 本次登记档案 id=" + id + " profile_code=" + profileCode);
        return id;
    }

    /** cleanup：先查目标清单并验范围，再按「快照号 + 本次档案 id」删除。 */
    private static void cleanup() {
        for (String sid : List.of(sidOk, sidBad)) {
            safeDeleteSnapshot(sid);
        }
        int profiles = publish.update("DELETE FROM runtime_profile WHERE id = ? AND profile_code = ?",
                profileId, "v25it-" + context.testRunId());
        System.out.println("[MetricPublisherMySqlIT] cleanup runtime_profile 行数=" + profiles + " id=" + profileId);
    }

    private static void safeDeleteSnapshot(String sid) {
        List<DeletionTarget> targets = read.query(
                "SELECT snapshot_id, runtime_profile_id, status, active_flag FROM metric_snapshot WHERE snapshot_id = ?",
                (rs, rowNum) -> new DeletionTarget(rs.getString("snapshot_id"), rs.getLong("runtime_profile_id"),
                        rs.getString("status"), (Integer) rs.getObject("active_flag")),
                sid);
        TestIsolationGuard.assertDeletionTargets(scope, targets);
        if (targets.isEmpty()) {
            return;
        }
        for (MetricAdsCatalog spec : MetricAdsCatalog.ALL) {
            scope.requireOwnedTable(spec.name());
            int ads = publish.update("DELETE FROM " + spec.name() + " WHERE snapshot_id = ?", sid);
            if (ads > 0) {
                System.out.println("[MetricPublisherMySqlIT] cleanup " + spec.name() + " rows=" + ads);
            }
        }
        int values = publish.update("DELETE FROM metric_value WHERE snapshot_id = ?", sid);
        int snapshots = publish.update("DELETE FROM metric_snapshot WHERE snapshot_id = ? AND runtime_profile_id = ?",
                sid, profileId);
        System.out.println("[MetricPublisherMySqlIT] cleanup snapshot_id=" + sid
                + " metric_value_rows=" + values + " metric_snapshot_rows=" + snapshots);
    }

    private static DataSource dataSource(String userProperty, String passwordProperty) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + requiredProperty("mysql.host") + "/" + context.metricDb()
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        ds.setUsername(requiredProperty(userProperty));
        ds.setPassword(requiredProperty(passwordProperty));
        return ds;
    }

    private static String requiredProperty(String key) {
        return TestIsolationGuard.requiredProperty(key);
    }

    /**
     * 只用于负向测试的探针数据源：任何 {@code getConnection()} 都计数并失败。
     * 它不是数据库，也不会执行任何写入 —— 用来证明「拒绝发生在建立连接之前」。
     */
    private static final class RejectingDataSource implements DataSource {

        private final java.util.concurrent.atomic.AtomicInteger connections =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger statements =
                new java.util.concurrent.atomic.AtomicInteger();

        int connectionAttempts() {
            return connections.get();
        }

        int statementAttempts() {
            return statements.get();
        }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            connections.incrementAndGet();
            statements.incrementAndGet();
            throw new java.sql.SQLException("负向测试探针：不允许连接（正式库上不得有任何连接/语句）");
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
            return getConnection();
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
            // 探针不需要日志
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // 探针不需要超时
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("rejecting-datasource");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
            throw new java.sql.SQLException("不是真实数据源");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
