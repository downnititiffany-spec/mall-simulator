package com.graduation.analytics.config;

import com.graduation.analytics.common.QueryTimeoutPolicy;
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
     * 未配置时返回 null —— 消费方必须 fail-closed（R7-4 起 SqlExecutor 已取消回退主源，
     * 见 SqlExecutor#effectiveDataSource），绝不允许退回 analytics_meta 取数（§17.1）。
     */
    @Bean("metricReadDataSource")
    public DataSource metricReadDataSource(Environment env) {
        String url = env.getProperty("platform.metric.read.url");
        if (url == null || url.isBlank()) {
            // 返回 null 仅表示"本机没配只读源"；消费方据此拒绝执行，而不是改用元数据库
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
     *
     * <p>S3-19（指导书 V3.0 L158「超时统一」）：这里是**阶段4 分析只读链路**的超时下发点。
     * {@code MySqlMetricStore} 与 {@code MetricAdsReader} 注入的是同一个本 Bean，
     * 因此在这里设一次 {@code setQueryTimeout} 两条路径同时生效，不必在两个 DAO 里各写一遍
     * （设计 L572 只读链路「timeout/maxRows 同时生效」的同一条思路）。超时值取自
     * {@link QueryTimeoutPolicy}（唯一数值属主，默认 30 秒 = 设计 L569 先例），
     * 可用 {@code platform.query.read-timeout-seconds} 覆盖；0/负数/非法值一律回退默认值，
     * 绝不解释成"无超时"。</p>
     *
     * <p><b>刻意不扩展</b>：{@code metricPublishJdbcTemplate}（批量写入/发布）与 meta 模板
     * （读写共用）**不加**读超时——本机没有真库长事务证据，给未取证的写路径加语句超时会引入
     * 新的生产失败模式。{@code maxRows} 也不在此设：ADS 读取是整分区读取，
     * 静默截断会让"总数/排行"出错，行数上限只属于阶段6 的只读 SQL 路径（{@code SqlExecutor}）。</p>
     */
    @Bean("metricReadJdbcTemplate")
    public JdbcTemplate metricReadJdbcTemplate(
            @Qualifier("metricReadDataSource") DataSource dataSource,
            Environment env) {
        if (dataSource == null) {
            throw new IllegalStateException(
                    "platform.metric.read.url 未配置：指标库只读源缺失，禁止回退 analytics_meta（§17.1）");
        }
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(QueryTimeoutPolicy.readTimeoutSeconds(env));
        return template;
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
