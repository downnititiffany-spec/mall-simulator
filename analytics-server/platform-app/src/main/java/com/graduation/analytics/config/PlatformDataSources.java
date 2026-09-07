package com.graduation.analytics.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/**
 * R1 平台数据源（整改书 §7.1）：analytics_meta 为平台元数据主库（meta_app 账号），
 * analytics_metric 由 metricReadDataSource 提供只读访问（metric_read 账号，仅 SELECT
 * 已发布 ADS，§7.2/17.3；AI SqlExecutor §8.6 第三/四层优先使用，未配置时回退主源）。
 * 平台严禁连接 mall_business（§7.4：代码中搜索 mall_order 等必须为零）。
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
     * analytics_metric 只读数据源（R1 接线）：metric_read 账号，DB 层仅 SELECT 权限
     * （init-three-dbs.sql 已授权）。AI SqlExecutor 与看板只读链路使用；
     * 未配置时 SqlExecutor 回退主源（降级友好，生产必须配置只读账号）。
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
}