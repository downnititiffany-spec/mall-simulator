package com.graduation.analytics.isolation;

import com.graduation.analytics.testsupport.TestIsolationGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 7 analytics 双库 schema 准备入口。
 *
 * <p>本类只在 {@code -Pisolated-analytics-schema} 下被 Surefire 选中。它刻意不使用
 * {@code IsolationProfileCondition}：既然调用方显式选择了 schema 准备，缺完整 V25_IT_* 时必须
 * <b>失败</b>，不能 disabled 后让 Maven 假绿。</p>
 *
 * <p>执行顺序严格是：加载完整隔离上下文 → 分别建立 meta/metric 最小权限连接 → 两边各自
 * {@link TestIsolationGuard#verifyBeforeWrite} → Flyway migrate。不会碰 3306、不会合库、不会用
 * 一个跨库写账号。无论本次是 fresh 初始化还是对已初始化隔离库的重复验证，第二次 migrate 都必须
 * 0 个脚本，并且 Flyway 必须能报告当前版本、关键表必须实际存在。</p>
 */
@Tag("analytics-schema-it")
class AnalyticsIsolationFlywayIT {

    @Test
    void migratesMetaAndMetricSchemasIndependentlyAndIdempotently() {
        TestIsolationGuard.TestRunContext context = TestIsolationGuard.loadContext();
        assertThat(context.metaDb()).isNotEqualTo(context.metricDb());

        DataSource metaDs = dataSource(context.metaDb(), "meta.username", "meta.password");
        DataSource metricDs = dataSource(context.metricDb(), "metric.publish.username", "metric.publish.password");

        TestIsolationGuard.LiveFacts metaFacts =
                TestIsolationGuard.verifyBeforeWrite(context, metaDs, context.metaDb());
        TestIsolationGuard.LiveFacts metricFacts =
                TestIsolationGuard.verifyBeforeWrite(context, metricDs, context.metricDb());
        System.out.println("[AnalyticsIsolationFlywayIT] meta facts: " + metaFacts.redactedSummary());
        System.out.println("[AnalyticsIsolationFlywayIT] metric facts: " + metricFacts.redactedSummary());

        Flyway metaFlyway = Flyway.configure().dataSource(metaDs).locations("classpath:db/meta").load();
        Flyway metricFlyway = Flyway.configure().dataSource(metricDs).locations("classpath:db/metric").load();

        var metaFirst = metaFlyway.migrate();
        var metricFirst = metricFlyway.migrate();
        var metaSecond = metaFlyway.migrate();
        var metricSecond = metricFlyway.migrate();

        // MigrateResult.targetSchemaVersion 在“schema 已是最新，本次执行 0 条迁移”时允许为 null，
        // 因此不能把它当作 schema 已初始化的判据。重复使用同一隔离 RunId 时，正确行为正是首轮 0 条。
        assertThat(metaFlyway.info().current()).as("meta Flyway 必须已有当前版本").isNotNull();
        assertThat(metricFlyway.info().current()).as("metric Flyway 必须已有当前版本").isNotNull();
        assertThat(metaSecond.migrationsExecuted).as("meta 第二次启动必须空跑").isZero();
        assertThat(metricSecond.migrationsExecuted).as("metric 第二次启动必须空跑").isZero();

        JdbcTemplate meta = new JdbcTemplate(metaDs);
        JdbcTemplate metric = new JdbcTemplate(metricDs);
        assertThat(tableCount(meta, "runtime_profile"))
                .as("db/meta 迁移必须在 metaDb 创建 runtime_profile")
                .isEqualTo(1);
        assertThat(tableCount(metric, "metric_snapshot"))
                .as("db/metric 迁移必须在 metricDb 创建 metric_snapshot")
                .isEqualTo(1);

        System.out.println("[AnalyticsIsolationFlywayIT] migrated meta=" + context.metaDb()
                + " first=" + metaFirst.migrationsExecuted + " second=" + metaSecond.migrationsExecuted
                + "; metric=" + context.metricDb()
                + " first=" + metricFirst.migrationsExecuted + " second=" + metricSecond.migrationsExecuted);
    }

    private static int tableCount(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table);
        return count == null ? 0 : count;
    }

    private static DataSource dataSource(String database, String userKey, String passwordKey) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + TestIsolationGuard.requiredProperty("mysql.host") + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        ds.setUsername(TestIsolationGuard.requiredProperty(userKey));
        ds.setPassword(TestIsolationGuard.requiredProperty(passwordKey));
        return ds;
    }
}
