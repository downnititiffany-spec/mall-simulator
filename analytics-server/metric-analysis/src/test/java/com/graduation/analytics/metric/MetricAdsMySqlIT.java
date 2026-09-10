package com.graduation.analytics.metric;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R7-2 真库集成测试（默认跳过：需同时满足 {@code -Dmetric.it=true} 与 surefire 显式指定本类）。
 *
 * <p>用途：证明 ADS DAO 与本仓库 Flyway 建出的真实表结构一致（列名/类型/主键），
 * 并验证「同一 dt 在不同 snapshot_id 下共存」——即 R7 修复的 dt 单列主键互相覆盖问题。</p>
 *
 * <p>只使用合成命名空间（snapshot_id 前缀 {@code it-r7-2-}、runtime_profile_id 999001），
 * 结束前清理自己插入的全部行（ADS 行 + 快照行），不触碰任何真实快照数据。</p>
 */
@EnabledIfSystemProperty(named = "metric.it", matches = "true")
class MetricAdsMySqlIT {

    private static final long PROFILE_ID = 999001L;
    private static final String SNAPSHOT_ACTIVE = "it-r7-2-active-" + System.currentTimeMillis();
    private static final String SNAPSHOT_B = "it-r7-2-b-" + System.currentTimeMillis();
    private static final String SNAPSHOT_C = "it-r7-2-c-" + System.currentTimeMillis();
    private static final String DT = "2026-09-01";

    private static JdbcTemplate publish;
    private static MetricAdsWriter writer;
    private static MetricAdsReader reader;

    @BeforeAll
    static void setUp() {
        publish = new JdbcTemplate(dataSource("metric_pub", "metric_pub_pw_2026"));
        writer = new MetricAdsWriter(publish);
        reader = new MetricAdsReader(new JdbcTemplate(dataSource("metric_read", "metric_read_pw_2026")));
        cleanup();
        insertSnapshot(SNAPSHOT_ACTIVE, "ACTIVE", 1);
        insertSnapshot(SNAPSHOT_B, "ARCHIVED", null);
        insertSnapshot(SNAPSHOT_C, "ARCHIVED", null);
    }

    @AfterAll
    static void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("真库往返：写入 ADS 行 → 按快照读回 → 计数 → deleteSnapshot 清理")
    void roundTripAgainstRealSchema() {
        assertThat(writer.insertRows("ads_operation_overview_m", SNAPSHOT_ACTIVE, DT,
                List.of(overviewRow(100L, new BigDecimal("1234.5600"))))).isEqualTo(1);
        assertThat(writer.insertRows("ads_hot_product_m", SNAPSHOT_ACTIVE, DT,
                List.of(hotRow(1, 11L), hotRow(2, 22L), hotRow(3, 33L)))).isEqualTo(3);

        assertThat(reader.activeSnapshotId()).isEqualTo(SNAPSHOT_ACTIVE);
        assertThat(reader.activeSnapshotId(PROFILE_ID)).isEqualTo(SNAPSHOT_ACTIVE);
        assertThat(reader.activeSnapshotId(999999L)).isNull();
        assertThat(reader.countRows("ads_hot_product_m", SNAPSHOT_ACTIVE)).isEqualTo(3);

        Map<String, Object> overview = reader.selectOneBySnapshot("ads_operation_overview_m", SNAPSHOT_ACTIVE, DT);
        assertThat(overview).isNotNull();
        assertThat(((Number) overview.get("pv")).longValue()).isEqualTo(100L);
        assertThat(new BigDecimal(String.valueOf(overview.get("sale_amount"))))
                .isEqualByComparingTo(new BigDecimal("1234.56"));

        Map<String, Object> top = reader.selectOneActive("ads_hot_product_m", DT);
        assertThat(top).isNotNull();
        assertThat(((Number) top.get("rank_no")).intValue()).isEqualTo(1); // ORDER BY rank_no + LIMIT 1
        assertThat(reader.selectActive("ads_hot_product_m", DT)).hasSize(3);

        assertThat(writer.deleteSnapshot(SNAPSHOT_ACTIVE)).isEqualTo(4);
        assertThat(reader.countRows("ads_hot_product_m", SNAPSHOT_ACTIVE)).isZero();
        assertThat(reader.selectOneBySnapshot("ads_operation_overview_m", SNAPSHOT_ACTIVE, DT)).isNull();
    }

    @Test
    @DisplayName("同一个 dt 在两个快照下共存：主键不是 dt 单列（R7 核心修复）")
    void sameDtSurvivesTwoSnapshots() {
        writer.insertRows("ads_operation_overview_m", SNAPSHOT_B, DT, List.of(overviewRow(1L, BigDecimal.ONE)));
        writer.insertRows("ads_operation_overview_m", SNAPSHOT_C, DT, List.of(overviewRow(2L, BigDecimal.TEN)));

        assertThat(reader.countRows("ads_operation_overview_m", SNAPSHOT_B)).isEqualTo(1);
        assertThat(reader.countRows("ads_operation_overview_m", SNAPSHOT_C)).isEqualTo(1);
        assertThat(((Number) reader.selectOneBySnapshot("ads_operation_overview_m", SNAPSHOT_C, DT).get("pv"))
                .longValue()).isEqualTo(2L);
    }

    private static Map<String, Object> overviewRow(long pv, BigDecimal saleAmount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pv", pv);
        row.put("uv", pv);
        row.put("dau", pv);
        row.put("order_count", 5L);
        row.put("sale_amount", saleAmount);
        row.put("net_sale_amount", saleAmount);
        row.put("avg_order_value", new BigDecimal("246.9100"));
        row.put("refund_rate", new BigDecimal("0.012500"));
        row.put("full_refund_rate", new BigDecimal("0.005000"));
        return row;
    }

    private static Map<String, Object> hotRow(int rankNo, long productId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rank_no", rankNo);
        row.put("product_id", productId);
        row.put("product_name", "IT 商品 " + productId);
        row.put("heat_score", new BigDecimal("9.5"));
        row.put("pv", 100L);
        row.put("buy", 3L);
        return row;
    }

    private static void insertSnapshot(String snapshotId, String status, Integer activeFlag) {
        LocalDateTime now = LocalDateTime.now();
        publish.update("INSERT INTO metric_snapshot (snapshot_id, runtime_profile_id, business_time, status, "
                        + "version, definition_version, data_updated_at, published_at, source, active_flag) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                snapshotId, PROFILE_ID, Timestamp.valueOf(now), status, 1, "v1",
                Timestamp.valueOf(now), Timestamp.valueOf(now), "spark-ads", activeFlag);
    }

    private static void cleanup() {
        for (String snapshotId : List.of(SNAPSHOT_ACTIVE, SNAPSHOT_B, SNAPSHOT_C)) {
            writer.deleteSnapshot(snapshotId);
        }
        publish.update("DELETE FROM metric_snapshot WHERE snapshot_id IN (?,?,?)",
                SNAPSHOT_ACTIVE, SNAPSHOT_B, SNAPSHOT_C);
        publish.update("DELETE FROM metric_snapshot WHERE runtime_profile_id = ?", PROFILE_ID);
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
