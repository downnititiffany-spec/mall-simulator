package com.graduation.mall.ai.sql;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/**
 * 主数据源显式 @Primary（spring.datasource.*）：
 * 系统存在多个 DataSource 时（本配置 + mall.reader），Flyway/MyBatis/JdbcTemplate 等
 * 按类型注入必须稳定拿到主库；reader 只被 SqlExecutor 按名取用（§8.6 第四层防线）。
 */
@Configuration
public class PrimaryDataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(Environment env) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(env.getProperty("spring.datasource.url"))
                .username(env.getProperty("spring.datasource.username"))
                .password(env.getProperty("spring.datasource.password", ""))
                .driverClassName(env.getProperty("spring.datasource.driver-class-name",
                        "com.mysql.cj.jdbc.Driver"))
                .build();
        ds.setPoolName("app-ds");
        return ds;
    }
}