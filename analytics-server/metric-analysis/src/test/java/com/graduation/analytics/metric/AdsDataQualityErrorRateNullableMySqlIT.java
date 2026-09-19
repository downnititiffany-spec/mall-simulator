package com.graduation.analytics.metric;

import com.graduation.analytics.testsupport.IsolationProfileCondition;
import com.graduation.analytics.testsupport.TestIsolationGuard;
import com.graduation.analytics.testsupport.TestIsolationGuard.DeletionTarget;
import com.graduation.analytics.testsupport.TestIsolationGuard.LiveFacts;
import com.graduation.analytics.testsupport.TestIsolationGuard.TestRunContext;
import com.graduation.analytics.testsupport.TestIsolationGuard.WorkScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-021 真库集成测试（**默认关闭**：没有登记测试隔离档案时整个类不执行，
 * 见 {@link IsolationProfileCondition}）：证明 V11 在真实 MySQL 上把
 * {@code ads_data_quality_m.error_rate} 放开为可空，且 D-019 的合法空态质量行
 * （0/0/passed=1/error_rate=NULL）从此能真实写入 —— 这正是 T-R2 attempt-4 在
 * 真链路上被 V3 NOT NULL 拒绝（MP_ADS_WRITE，42 行回滚）的同一场景。
 *
 * <p>文本/结构层守卫见 {@link AdsDataQualityErrorRateNullableMigrationScriptTest}
 * （Migration：文本/结构测试 + 3307 真 MySQL 双层证据，治理 §4）。本类沿用
 * {@link MetricAdsMySqlIT} 的隔离纪律：目标库/账号只来自 {@link TestRunContext}、
 * 写前 {@link TestIsolationGuard#verifyBeforeWrite}、快照/表范围属于本次
 * {@code testRunId}、cleanup 前先 {@link TestIsolationGuard#assertDeletionTargets}。
 * 隔离上下文放在 {@code @BeforeAll}（启用判定先于配置加载）的缘由同该类。</p>
 */
@Tag("it")
@ExtendWith(IsolationProfileCondition.class)
class AdsDataQualityErrorRateNullableMySqlIT {

    private static final String DT = "2026-09-19";

    private static final String TABLE = "ads_data_quality_m";

    private static TestRunContext context;
    private static String snapshot;

    private static JdbcTemplate meta;
    private static JdbcTemplate publish;
    private static JdbcTemplate read;
    private static MetricAdsWriter writer;
    private static MetricAdsReader reader;
    private static WorkScope scope;
    private static long profileId;

    @BeforeAll
    static void setUp() {
        context = TestIsolationGuard.loadContext();
        snapshot = context.testRunId() + "-d021-null-rate";
        System.out.println("[AdsDataQualityErrorRateNullableMySqlIT] 隔离上下文：" + context.redactedSummary());

        DataSource metaDs = dataSource(context.metaDb(), "meta.username", "meta.password");
        DataSource publishDs = dataSource(context.metricDb(), "metric.publish.username", "metric.publish.password");
        DataSource readDs = dataSource(context.metricDb(), "metric.read.username", "metric.read.password");
        LiveFacts metaFacts = TestIsolationGuard.verifyBeforeWrite(context, metaDs, context.metaDb());
        LiveFacts publishFacts = TestIsolationGuard.verifyBeforeWrite(context, publishDs, context.metricDb());
        LiveFacts readFacts = TestIsolationGuard.verifyBeforeWrite(context, readDs, context.metricDb());
        System.out.println("[AdsDataQualityErrorRateNullableMySqlIT] meta 事实：" + metaFacts.redactedSummary());
        System.out.println("[AdsDataQualityErrorRateNullableMySqlIT] publish 事实：" + publishFacts.redactedSummary());
        System.out.println("[AdsDataQualityErrorRateNullableMySqlIT] read 事实：" + readFacts.redactedSummary());

        meta = new JdbcTemplate(metaDs);
        publish = new JdbcTemplate(publishDs);
        read = new JdbcTemplate(readDs);
        writer = new MetricAdsWriter(publish);
        reader = new MetricAdsReader(read);

        profileId = registerOwnProfile();
        scope = new WorkScope(context.testRunId(), profileId,
                new LinkedHashSet<>(List.of(snapshot)),
                new LinkedHashSet<>(List.of(TABLE)));
        System.out.println("[AdsDataQualityErrorRateNullableMySqlIT] 本次拥有范围：profileId=" + profileId
                + " snapshot=" + snapshot + " table=" + TABLE);

        cleanup();
        insertSnapshot(snapshot);
    }

    @AfterAll
    static void tearDown() {
        if (scope != null) {
            cleanup();
        }
    }

    @Test
    @DisplayName("V11 应用后：information_schema 显示 error_rate 可空且无默认值，V3 历史仍在")
    void errorRateIsNullableAfterFlyway() {
        Map<String, Object> column = publish.queryForMap(
                "SELECT IS_NULLABLE, COLUMN_DEFAULT, COLUMN_TYPE FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                TABLE, "error_rate");
        assertThat(column.get("IS_NULLABLE"))
                .as("V11 必须把 error_rate 放开为可空（D-021）")
                .isEqualTo("YES");
        assertThat(String.valueOf(column.get("COLUMN_TYPE")))
                .as("类型与精度必须保持 DECIMAL(12,6) 不变")
                .isEqualTo("decimal(12,6)");
        assertThat(column.get("COLUMN_DEFAULT"))
                .as("默认值必须是 NULL（不再有 0 默认值伪装）")
                .isNull();

        Integer v11 = publish.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '11' AND success = 1", Integer.class);
        assertThat(v11).as("V11 必须已在隔离 metric 库真实应用").isEqualTo(1);
        Integer v3 = publish.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = 1", Integer.class);
        assertThat(v3).as("append-only：V3 的应用历史必须原样保留").isEqualTo(1);
    }

    @Test
    @DisplayName("D-019 合法空态行（0/0/passed=1/error_rate=NULL）真实写入成功，非 NULL 对照行不回归")
    void d019LegalEmptyStateRowWritesWithNullErrorRate() {
        // T-R2 attempt-4 被拒的那一行（同一形态：无行为日的质量规则结果）
        Map<String, Object> emptyState = new LinkedHashMap<>();
        emptyState.put("rule_code", "IT_RULE_D019_EMPTY");
        emptyState.put("check_count", 0L);
        emptyState.put("error_count", 0L);
        emptyState.put("error_rate", null);
        emptyState.put("passed", 1);
        emptyState.put("rule_version", "2");
        assertThat(writer.insertRows(TABLE, snapshot, DT, List.of(emptyState)))
                .as("error_rate=NULL 的空态行必须能写入（V3 NOT NULL 已被 V11 放开）")
                .isEqualTo(1);

        // 非 NULL 对照：正常比率值不受迁移影响
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("rule_code", "IT_RULE_D021_CONTROL");
        control.put("check_count", 3L);
        control.put("error_count", 1L);
        control.put("error_rate", new BigDecimal("0.333333"));
        control.put("passed", 0);
        control.put("rule_version", "2");
        assertThat(writer.insertRows(TABLE, snapshot, DT, List.of(control))).isEqualTo(1);

        Map<String, Object> emptyRow = read.queryForMap(
                "SELECT check_count, error_count, error_rate, passed, rule_version FROM " + TABLE
                        + " WHERE snapshot_id = ? AND rule_code = ?", snapshot, "IT_RULE_D019_EMPTY");
        assertThat(emptyRow.get("error_rate"))
                .as("读回的 error_rate 必须是真实 NULL，不是 0")
                .isNull();
        assertThat(((Number) emptyRow.get("check_count")).longValue()).isZero();
        assertThat(((Number) emptyRow.get("error_count")).longValue()).isZero();
        assertThat(((Number) emptyRow.get("passed")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(emptyRow.get("rule_version"))).isEqualTo("2");

        Map<String, Object> controlRow = read.queryForMap(
                "SELECT error_rate, passed FROM " + TABLE + " WHERE snapshot_id = ? AND rule_code = ?",
                snapshot, "IT_RULE_D021_CONTROL");
        assertThat(new BigDecimal(String.valueOf(controlRow.get("error_rate"))))
                .isEqualByComparingTo(new BigDecimal("0.333333"));
        assertThat(((Number) controlRow.get("passed")).intValue()).isZero();

        assertThat(reader.countRows(TABLE, snapshot)).isEqualTo(2);
    }

    // ── 自带档案登记与 cleanup（与 MetricAdsMySqlIT 同一套隔离纪律）──────────────

    private static long registerOwnProfile() {
        String profileCode = "v25it-" + context.testRunId();
        String profileName = "D-021 隔离测试档案 " + context.testRunId();
        meta.update("INSERT INTO runtime_profile (profile_code, profile_name, type, status, landing_uri, "
                        + "spark_master, metric_store_type, timezone, version, credential_ref) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE profile_name = VALUES(profile_name), updated_at = CURRENT_TIMESTAMP(3)",
                profileCode, profileName, "LOCAL", "ACTIVE", context.hdfsRoot() + "/landing",
                "local[1]", "MYSQL", "Asia/Shanghai", 1, context.credentialsRef());
        Long id = meta.queryForObject("SELECT id FROM runtime_profile WHERE profile_code = ?", Long.class, profileCode);
        assertThat(id).as("本次运行必须有自己的 runtime_profile 行").isNotNull();
        return id;
    }

    private static void insertSnapshot(String snapshotId) {
        scope.requireOwnedSnapshot(snapshotId);
        LocalDateTime now = LocalDateTime.now();
        publish.update("INSERT INTO metric_snapshot (snapshot_id, runtime_profile_id, business_time, status, "
                        + "version, definition_version, data_updated_at, published_at, source, active_flag) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                snapshotId, profileId, Timestamp.valueOf(now), "ARCHIVED", 1, "v1",
                Timestamp.valueOf(now), Timestamp.valueOf(now), "spark-ads", null);
    }

    private static void cleanup() {
        safeDeleteSnapshot(snapshot);
        int profiles = meta.update("DELETE FROM runtime_profile WHERE id = ? AND profile_code = ?",
                profileId, "v25it-" + context.testRunId());
        System.out.println("[AdsDataQualityErrorRateNullableMySqlIT] cleanup runtime_profile 行数=" + profiles);
    }

    private static void safeDeleteSnapshot(String snapshotId) {
        List<DeletionTarget> targets = read.query(
                "SELECT snapshot_id, runtime_profile_id, status, active_flag FROM metric_snapshot WHERE snapshot_id = ?",
                (rs, rowNum) -> new DeletionTarget(rs.getString("snapshot_id"), rs.getLong("runtime_profile_id"),
                        rs.getString("status"), (Integer) rs.getObject("active_flag")),
                snapshotId);
        TestIsolationGuard.assertDeletionTargets(scope, targets);
        if (targets.isEmpty()) {
            return;
        }
        scope.requireOwnedTable(TABLE);
        int adsRows = publish.update("DELETE FROM " + TABLE + " WHERE snapshot_id = ?", snapshotId);
        int values = publish.update("DELETE FROM metric_value WHERE snapshot_id = ?", snapshotId);
        int snapshots = publish.update("DELETE FROM metric_snapshot WHERE snapshot_id = ? AND runtime_profile_id = ?",
                snapshotId, profileId);
        System.out.println("[AdsDataQualityErrorRateNullableMySqlIT] cleanup snapshot_id=" + snapshotId
                + " ads_rows=" + adsRows + " metric_value_rows=" + values + " metric_snapshot_rows=" + snapshots);
    }

    /**
     * 数据源：库名只能来自 {@link TestRunContext}（无任何正式库默认值），
     * 与 {@link MetricAdsMySqlIT#dataSource} 同一纪律。
     */
    private static DataSource dataSource(String database, String userProperty, String passwordProperty) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + TestIsolationGuard.requiredProperty("mysql.host") + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        ds.setUsername(TestIsolationGuard.requiredProperty(userProperty));
        ds.setPassword(TestIsolationGuard.requiredProperty(passwordProperty));
        return ds;
    }
}
