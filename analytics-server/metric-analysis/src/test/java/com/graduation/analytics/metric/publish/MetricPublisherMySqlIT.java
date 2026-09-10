package com.graduation.analytics.metric.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.metric.MetricAdsCatalog;
import com.graduation.analytics.metric.MetricAdsWriter;
import com.graduation.analytics.metric.MySqlMetricStore;
import com.graduation.analytics.metric.publish.MetricPublisherPort.DefinitionRef;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishReport;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R7-3 真库集成测试（默认跳过：需同时满足 {@code -Dmetric.it=true} 与 surefire 显式指定本类）。
 *
 * <p>证明发布器最关键的两条安全性质（§17.5）：</p>
 * <ol>
 *   <li><b>成功</b>：ADS 宽表 + metric_value 落库，快照由 VERIFYING 切到 ACTIVE，旧 ACTIVE 归档；</li>
 *   <li><b>失败（写库后对账不通过）</b>：快照置 FAILED、本次写入的 ADS 行被补偿清理、
 *       <b>旧 ACTIVE 指针原样保留</b>——即"发不出去也不影响线上看板"。</li>
 * </ol>
 *
 * <p>只使用合成命名空间（snapshot_id 前缀 {@code it-r7-3-}、runtime_profile_id 999002），
 * 结束前清理自己写入的 ADS 行、指标值与快照行，不触碰真实快照数据。</p>
 */
@EnabledIfSystemProperty(named = "metric.it", matches = "true")
class MetricPublisherMySqlIT {

    private static final long PROFILE_ID = 999002L;
    private static final String DT = "20260901";
    private static final String SID_OK = "it-r7-3-ok-" + System.currentTimeMillis();
    private static final String SID_BAD = "it-r7-3-bad-" + System.currentTimeMillis();

    private static JdbcTemplate publish;
    private static JdbcTemplate read;
    private static MetricAdsWriter writer;
    private static MetricPublisher publisher;
    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new ObjectMapper();
        publish = new JdbcTemplate(dataSource("metric_pub", "metric_pub_pw_2026"));
        read = new JdbcTemplate(dataSource("metric_read", "metric_read_pw_2026"));
        writer = new MetricAdsWriter(publish);
        MetricPublishRepository repository = new MetricPublishRepository(publish, read);
        MySqlMetricStore store = new MySqlMetricStore(publish, read);
        publisher = new MetricPublisher(repository, writer, new AdsExportReader(), store,
                new MetricPublishValidator(), mapper);
        cleanup();
    }

    @AfterAll
    static void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("成功发布 → ACTIVE 切换；写库后对账失败 → FAILED + 补偿清理 + 旧 ACTIVE 保留")
    void publishActivationAndFailureCompensation(@TempDir Path dir) throws Exception {
        // ── 阶段 1：正常发布 → ACTIVE ──
        Path okDir = Files.createDirectories(dir.resolve(SID_OK));
        writeFixture(okDir, SID_OK, allTables());
        PublishReport ok = publisher.publish(request(SID_OK, okDir, fullDictionary()));

        assertThat(ok.ok()).as("失败码=%s 说明=%s checks=%s", ok.errorCode(), ok.message(),
                MetricPublishValidator.failedRules(ok.checks())).isTrue();
        assertThat(ok.adsRows()).isEqualTo(expectedAdsRows());
        assertThat(ok.metricValues()).isEqualTo(10);
        assertThat(activeSnapshot()).isEqualTo(SID_OK);
        assertThat(status(SID_OK)).isEqualTo("ACTIVE");
        assertThat(metricValueCount(SID_OK)).isEqualTo(10);
        assertThat(overviewPv(SID_OK)).isEqualTo(7L);
        assertThat(metricValue(SID_OK, "refund_rate")).isEqualByComparingTo(new BigDecimal("0.6000"));

        // ── 阶段 2：清单与 ADS 都正常，但字典缺 gmv → 写库后对账 BLOCKING 失败 ──
        Path badDir = Files.createDirectories(dir.resolve(SID_BAD));
        Map<String, DefinitionRef> partialDict = new LinkedHashMap<>(fullDictionary());
        partialDict.remove("gmv");
        writeFixture(badDir, SID_BAD, allTables());
        PublishReport bad = publisher.publish(request(SID_BAD, badDir, partialDict));

        assertThat(bad.ok()).isFalse();
        assertThat(bad.errorCode()).isEqualTo("MP_VERIFY_FAILED");
        assertThat(MetricPublishValidator.failedRules(bad.checks())).contains("MP_METRIC_DICT_VERSION");
        assertThat(status(SID_BAD)).isEqualTo("FAILED");
        assertThat(activeSnapshot()).as("失败不得改变 ACTIVE 指针").isEqualTo(SID_OK);
        assertThat(metricValueCount(SID_BAD)).as("失败快照不得留下指标值").isZero();
        assertThat(adsRowCount(SID_BAD)).as("失败补偿必须清掉本次写入的 ADS 行").isZero();
        assertThat(metricValueCount(SID_OK)).as("旧快照数据不受影响").isEqualTo(10);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private PublishRequest request(String sid, Path dir, Map<String, DefinitionRef> dict) {
        return new PublishRequest(PROFILE_ID, 7, sid, DT, "2026-09-01T00:00", 99001L, dir, dict);
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
        dict.put("buy_rate", new DefinitionRef("v1", ""));
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
        rows.put("ads_operation_overview_m", List.of(overview));

        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("order_count", 5L);
        trend.put("buyer_count", 3L);
        trend.put("sale_amount", new BigDecimal("2042.00"));
        trend.put("avg_order_value", new BigDecimal("408.40"));
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
        rows.put("ads_user_profile_m", List.of(profile));

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("rule_code", "EVENT_ID_UNIQUE");
        quality.put("check_count", 49L);
        quality.put("error_count", 0L);
        quality.put("error_rate", new BigDecimal("0.0000"));
        quality.put("passed", 1);
        quality.put("threshold", "error_count=0");
        rows.put("ads_data_quality_m", List.of(quality));
        return rows;
    }

    private static int expectedAdsRows() {
        return allTables().values().stream().mapToInt(List::size).sum();
    }

    /** 按 MetricAdsCatalog 逐表写 JSONL + `_export.json` 清单（路径用正斜杠，与 Spark 侧一致） */
    private void writeFixture(Path dir, String sid, Map<String, List<Map<String, Object>>> rows) throws Exception {
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
            table.put("hivePath", "file:/D:/Develop_code/GraduationProject/spark-warehouse/dw_ads.db/"
                    + spec.name() + "/snapshot_id=" + sid + "/dt=" + DT);
            table.put("exportFile", file.toAbsolutePath().toString().replace('\\', '/'));
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
                String.class, PROFILE_ID);
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

    private static void cleanup() {
        for (String sid : List.of(SID_OK, SID_BAD)) {
            writer.deleteSnapshot(sid);
            publish.update("DELETE FROM metric_value WHERE snapshot_id=?", sid);
            publish.update("DELETE FROM metric_snapshot WHERE snapshot_id=?", sid);
        }
        publish.update("DELETE FROM metric_snapshot WHERE runtime_profile_id=?", PROFILE_ID);
    }

    private static javax.sql.DataSource dataSource(String user, String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/analytics_metric?useSSL=false&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        ds.setUsername(user);
        ds.setPassword(password);
        return ds;
    }
}
