package com.graduation.analytics.config;

import com.graduation.analytics.ai.sql.AiScopeResolver;
import com.graduation.analytics.common.QueryTimeoutPolicy;
import com.graduation.analytics.runtime.ActiveRuntimeProfileIdProvider;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * S3-19（指导书 V3.0 L158「超时统一」）：**阶段4 分析只读链路**的查询超时在装配点统一下发。
 *
 * <p>为什么装配点是正确的位置：阶段4 的两条读路径 {@code MySqlMetricStore} 与 {@code MetricAdsReader}
 * 都注入同一个 {@code metricReadJdbcTemplate}；在 JdbcTemplate 上设一次 queryTimeout，
 * 两条路径同时生效（设计 L572「timeout/maxRows 同时生效」的同一条思路），
 * 不必在两个 DAO 里各写一遍字面量。</p>
 *
 * <p><b>刻意排除（如实登记）</b>：{@code metricPublishJdbcTemplate} 与 meta 模板**不加**读超时——
 * 前者是批量写入/发布路径，本机没有任何真库长事务证据；给未取证的写路径加语句超时会引入
 * 新的生产失败模式。L544 的 per-Store 能力描述由 {@code MetricStore.Capabilities} 单独报告，
 * 不会因此给这些写路径增加读查询超时。</p>
 *
 * <p><b>取证边界（未测）</b>：本类是纯内存装配测试（DataSource 为 mock，从不建连接）：
 * 真实 MySQL 上 queryTimeout 到点后驱动是否抛 {@code QueryTimeoutException}
 * 由真库 IT 取证，本轮**未测**。</p>
 */
class PlatformDataSourcesQueryTimeoutTest {

    private final PlatformDataSources platformDataSources = new PlatformDataSources();
    private final DataSource dataSource = mock(DataSource.class);

    private int readTimeoutSeconds(String configuredValue) {
        MockEnvironment env = new MockEnvironment();
        if (configuredValue != null) {
            env.setProperty(QueryTimeoutPolicy.READ_TIMEOUT_PROPERTY, configuredValue);
        }
        JdbcTemplate template = platformDataSources.metricReadJdbcTemplate(dataSource, env);
        return template.getQueryTimeout();
    }

    @Test
    @DisplayName("未配置时：只读查询超时取统一属主默认值（设计 L569 先例 30 秒）")
    void metricReadTemplateAppliesUnifiedTimeoutByDefault() {
        assertThat(readTimeoutSeconds(null)).isEqualTo(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS);
        assertThat(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS).isEqualTo(30);
    }

    @Test
    @DisplayName("配置了正数：按配置下发（运维可调，不必改代码）")
    void metricReadTemplateHonoursConfiguredTimeout() {
        assertThat(readTimeoutSeconds("5")).isEqualTo(5);
        assertThat(readTimeoutSeconds(" 7 ")).isEqualTo(7);
    }

    @Test
    @DisplayName("AI 快照作用域解析器复用带统一超时的 metric_read JdbcTemplate")
    void aiScopeResolverReusesConfiguredMetricReadTemplate() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(QueryTimeoutPolicy.READ_TIMEOUT_PROPERTY, "9");
        JdbcTemplate template = platformDataSources.metricReadJdbcTemplate(dataSource, env);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("metricReadJdbcTemplate", JdbcTemplate.class, () -> template);
            context.registerBean("activeRuntimeProfileIdProvider", ActiveRuntimeProfileIdProvider.class,
                    () -> mock(ActiveRuntimeProfileIdProvider.class));
            context.register(AiScopeResolver.class);
            context.refresh();

            AiScopeResolver resolver = context.getBean(AiScopeResolver.class);
            JdbcTemplate injected = (JdbcTemplate) org.springframework.test.util.ReflectionTestUtils.getField(
                    resolver, "metricReadJdbcTemplate");
            assertThat(injected).isSameAs(template);
            assertThat(injected.getQueryTimeout()).isEqualTo(9);
        }
    }

    @Test
    @DisplayName("0/负数/非数字/空：一律回退统一默认值，绝不解释成「无超时」")
    void nonPositiveOrUnparsableTimeoutNeverMeansUnlimited() {
        // JDBC 语义里 setQueryTimeout(0) = 永不超时；把它当"关掉超时"的口子会让 L158 静默失效
        assertThat(readTimeoutSeconds("0")).isEqualTo(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS);
        assertThat(readTimeoutSeconds("-3")).isEqualTo(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS);
        assertThat(readTimeoutSeconds("abc")).isEqualTo(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS);
        assertThat(readTimeoutSeconds("")).isEqualTo(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS);
        assertThat(readTimeoutSeconds("2147483648")).isEqualTo(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("写入/发布模板刻意不带读超时（本机无真库长事务证据，不扩大作用面）")
    void publishTemplateDeliberatelyKeepsNoReadTimeout() {
        JdbcTemplate publish = platformDataSources.metricPublishJdbcTemplate(dataSource);
        assertThat(publish.getQueryTimeout()).isNotEqualTo(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("改造后仍保持 fail-closed：只读源缺失时启动即报错，不静默降级")
    void missingReadDataSourceStillFailsClosed() {
        assertThatThrownBy(() -> platformDataSources.metricReadJdbcTemplate(null, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("§17.1");
    }

    @Test
    @DisplayName("只读源已配置但缺少发布或读取账号时 fail-closed")
    void metricReadDataSourceRequiresBothAccountNames() {
        for (String missingProperty : new String[]{
                "platform.metric.publish.username", "platform.metric.read.username"}) {
            MockEnvironment env = metricReadEnvironment();
            env.setProperty(missingProperty, " ");

            assertThatThrownBy(() -> platformDataSources.metricReadDataSource(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("必须配置为不同账号");
        }
    }

    @Test
    @DisplayName("只读源不接受与发布源相同的账号（忽略大小写和首尾空格）")
    void metricReadDataSourceRejectsPublishAccountReuse() {
        MockEnvironment env = metricReadEnvironment();
        env.setProperty("platform.metric.publish.username", " Metric_Publish ");
        env.setProperty("platform.metric.read.username", "metric_publish");

        assertThatThrownBy(() -> platformDataSources.metricReadDataSource(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须配置为不同账号");
    }

    @Test
    @DisplayName("独立只读账号可装配数据源，且不需要建立数据库连接")
    void metricReadDataSourceAcceptsDistinctAccountWithoutConnecting() {
        MockEnvironment env = metricReadEnvironment();
        env.setProperty("platform.metric.publish.username", "metric_publish");
        env.setProperty("platform.metric.read.username", "metric_read");

        DataSource result = platformDataSources.metricReadDataSource(env);
        assertThat(result).isInstanceOf(HikariDataSource.class);
        HikariDataSource dataSource = (HikariDataSource) result;
        try {
            assertThat(dataSource.getUsername()).isEqualTo("metric_read");
            assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:mysql://127.0.0.1:3307/analytics_metric");
        } finally {
            dataSource.close();
        }
    }

    private MockEnvironment metricReadEnvironment() {
        return new MockEnvironment()
                .withProperty("platform.metric.read.url", "jdbc:mysql://127.0.0.1:3307/analytics_metric")
                .withProperty("platform.metric.read.driver", "com.mysql.cj.jdbc.Driver");
    }
}
