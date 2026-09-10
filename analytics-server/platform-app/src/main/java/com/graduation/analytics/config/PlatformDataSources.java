package com.graduation.analytics.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionManager;

import javax.sql.DataSource;

/**
 * R1/R7 平台数据源与事务边界（整改书 §7.1、V2.0 §17.1 三类数据源职责）：
 *
 * <pre>
 * metaDataSource         读写 analytics_meta   pipeline/ingestion/profile/auth/AI 日志/decision
 * metricPublishDataSource 读写 analytics_metric 仅指标发布（MetricAdsWriter / MySqlMetricStore.publish）
 * metricReadDataSource   只读 analytics_metric 看板/AI SQL/决策取值（metric_read，DB 层仅 SELECT）
 * </pre>
 *
 * 事务管理器同步三分：{@code metaTransactionManager}(@Primary) / {@code metricPublishTransactionManager}，
 * 只读源**不提供**写事务管理器（§17.1：metricRead 不允许写事务）。
 *
 * 指标库一律走 JdbcTemplate（R7-1 决策）：analytics_metric 不使用 MyBatis-Plus mapper，
 * 因此 @MapperScan 不再包含 metric.mapper，指标库没有 mapper 扫描歧义；
 * 写路径用 metricPublishJdbcTemplate，读路径用 metricReadJdbcTemplate，**禁止回退 meta 源**。
 *
 * 说明：本项目此前依赖 Spring Boot 自动配置的「主数据源 JdbcTemplate / 事务管理器」。
 * 一旦声明第二个 DataSource 之外的 JdbcTemplate/TransactionManager Bean，自动配置会整体退避
 * （@ConditionalOnMissingBean），所以这里显式补回 meta 侧的那一份并保持 @Primary，
 * 保证 RuntimeProfileServiceImpl 等既有 @Transactional 仍绑定 analytics_meta。
 */
@Configuration
public class PlatformDataSources {

    /** analytics_meta（平台元数据：任务/批次/质量/用户/AI 审计/决策） */
    @Bean
    @Primary
    public DataSource metaDataSource(Environment env) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(env.getProperty("platform.meta.url"))
                .username(env.getProperty("platform.meta.username"))
                .password(env.getProperty("platform.meta.password", ""))
                .driverClassName(env.getProperty("platform.meta.driver", "com.mysql.cj.jdbc.Driver"))
                .build();
        ds.setPoolName("meta-ds");
        return ds;
    }

    /**
     * R7-1：analytics_metric 读写数据源（metric_pub 账号）。指标发布/暂存写入的唯一入口（§17.1）。
     * 与只读源不同，本 Bean **不允许为空**：写路径缺失必须启动即失败，不能静默落到 meta 源。
     */
    @Bean("metricPublishDataSource")
    public DataSource metricPublishDataSource(Environment env) {
        String url = env.getProperty("platform.metric.publish.url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "platform.metric.publish.url 未配置：指标库发布源缺失（§17.1 禁止写入 analytics_meta 副本表）");
        }
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(env.getProperty("platform.metric.publish.username"))
                .password(env.getProperty("platform.metric.publish.password", ""))
                .driverClassName(env.getProperty("platform.metric.publish.driver", "com.mysql.cj.jdbc.Driver"))
                .build();
        ds.setPoolName("metric-publish-ds");
        return ds;
    }

    /**
     * analytics_metric 只读数据源（R1 接线）：metric_read 账号，DB 层仅 SELECT 权限
     * （init-three-dbs.sql 已授权）。AI SqlExecutor 与看板只读链路使用；
     * 未配置时返回 null（SqlExecutor 以 required=false 注入，其降级策略见该类）。
     */
    @Bean("metricReadDataSource")
    public DataSource metricReadDataSource(Environment env) {
        String url = env.getProperty("platform.metric.read.url");
        if (url == null || url.isBlank()) {
            // 未配置 metric 只读源时返回 null：SqlExecutor 以 required=false 注入并回退主源
            return null;
        }
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(env.getProperty("platform.metric.read.username"))
                .password(env.getProperty("platform.metric.read.password", ""))
                .driverClassName(env.getProperty("platform.metric.read.driver", "com.mysql.cj.jdbc.Driver"))
                .build();
        ds.setPoolName("metric-read-ds");
        return ds;
    }

    // ── JdbcTemplate（每库一份，按 Bean 名 + @Qualifier 注入，避免多数据源歧义） ──────────

    /** meta 库 JdbcTemplate（@Primary：既有未限定的 JdbcTemplate 注入点保持原语义） */
    @Bean
    @Primary
    public JdbcTemplate metaJdbcTemplate(@Qualifier("metaDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 指标库写 JdbcTemplate（metricPublishDataSource） */
    @Bean("metricPublishJdbcTemplate")
    public JdbcTemplate metricPublishJdbcTemplate(
            @Qualifier("metricPublishDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 指标库读 JdbcTemplate（metricReadDataSource）。
     * §17.1：生产环境只读指标源缺失必须失败，禁止回退元数据库 → 这里启动即报错而不是降级。
     */
    @Bean("metricReadJdbcTemplate")
    public JdbcTemplate metricReadJdbcTemplate(
            @Qualifier("metricReadDataSource") DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalStateException(
                    "platform.metric.read.url 未配置：指标库只读源缺失，禁止回退 analytics_meta（§17.1）");
        }
        return new JdbcTemplate(dataSource);
    }

    // ── 事务管理器（§17.1：meta 默认，指标发布独立，只读源无写事务） ──────────────────────

    /** 平台元数据事务管理器（@Primary：既有 @Transactional 默认绑定 analytics_meta） */
    @Bean
    @Primary
    public TransactionManager metaTransactionManager(@Qualifier("metaDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /** 指标发布事务管理器（MySqlMetricStore.publish / MetricAdsWriter 显式绑定） */
    @Bean("metricPublishTransactionManager")
    public DataSourceTransactionManager metricPublishTransactionManager(
            @Qualifier("metricPublishDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
