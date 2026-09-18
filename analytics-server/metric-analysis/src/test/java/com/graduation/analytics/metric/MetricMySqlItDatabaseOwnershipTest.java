package com.graduation.analytics.metric;

import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 7 characterization guard：两个历史写入型 MySQL IT 必须遵守 V3 的物理双库所有权。
 *
 * <p>{@code runtime_profile} 属 {@code analytics_meta}；{@code metric_snapshot}/{@code metric_value}/ADS
 * 属 {@code analytics_metric}。旧 IT 曾把两者都写进 {@code metricDb}，这在合库夹具里可能跑绿，
 * 但无法证明 V3 的真实双库链。本守卫只钉测试基础设施，不改变任何生产代码。</p>
 */
class MetricMySqlItDatabaseOwnershipTest {

    private static final Path METRIC_ADS_IT = RepoRoot.path(
            "analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/MetricAdsMySqlIT.java");
    private static final Path METRIC_PUBLISHER_IT = RepoRoot.path(
            "analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/publish/MetricPublisherMySqlIT.java");

    @Test
    void bothWriteItsUseRegisteredMetaAndMetricDatabasesSeparately() throws IOException {
        for (Path file : new Path[]{METRIC_ADS_IT, METRIC_PUBLISHER_IT}) {
            String source = Files.readString(file);
            assertThat(source)
                    .as(file + " 必须从 context.metaDb() 建 meta 连接")
                    .contains("dataSource(context.metaDb(), \"meta.username\", \"meta.password\")")
                    .contains("verifyBeforeWrite(context, metaDs, context.metaDb())")
                    .contains("new JdbcTemplate(metaDs)")
                    .as(file + " 必须从 context.metricDb() 建 publish/read 连接")
                    .contains("dataSource(context.metricDb(), \"metric.publish.username\", \"metric.publish.password\")")
                    .contains("dataSource(context.metricDb(), \"metric.read.username\", \"metric.read.password\")");
        }
    }

    @Test
    void runtimeProfileLifecycleBelongsOnlyToMetaConnection() throws IOException {
        for (Path file : new Path[]{METRIC_ADS_IT, METRIC_PUBLISHER_IT}) {
            String source = Files.readString(file);
            assertThat(source)
                    .contains("meta.update(\"INSERT INTO runtime_profile")
                    .contains("meta.queryForObject(\"SELECT id FROM runtime_profile")
                    .contains("meta.update(\"DELETE FROM runtime_profile")
                    .doesNotContain("publish.update(\"INSERT INTO runtime_profile")
                    .doesNotContain("publish.queryForObject(\"SELECT id FROM runtime_profile")
                    .doesNotContain("publish.update(\"DELETE FROM runtime_profile");
        }
    }

    @Test
    void guardHasTeethAgainstAccidentalCombinedDatabaseRegression() throws IOException {
        String original = Files.readString(METRIC_ADS_IT);
        String mutant = original.replace(
                "dataSource(context.metaDb(), \"meta.username\", \"meta.password\")",
                "dataSource(context.metricDb(), \"meta.username\", \"meta.password\")");
        assertThat(mutant).isNotEqualTo(original);
        assertThat(mutant).doesNotContain(
                "dataSource(context.metaDb(), \"meta.username\", \"meta.password\")");
    }
}
