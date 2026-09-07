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
 * analytics_metric 由后续 R7 发布链路接入（metric_pub/metric_read，二阶段接线）。
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
}