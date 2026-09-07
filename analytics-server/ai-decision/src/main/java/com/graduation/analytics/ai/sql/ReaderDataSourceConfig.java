package com.graduation.analytics.ai.sql;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/**
 * AI 执行器专属只读数据源（§8.6 第四层防线）：
 * mall.reader.* 配置存在时创建独立 Hikari 池（mall_reader 账号，仅 SELECT 权限），
 * 供 SqlExecutor 优先使用；应用数据源由 Spring Boot 自动配置保持不变
 * （该 Bean 不设 @Primary，不改变 MyBatis/JdbcTemplate 默认注入）。
 * 未配置 mall.reader.url 时不创建（SqlExecutor 回退应用数据源，降级友好）。
 */
@Configuration
public class ReaderDataSourceConfig {

    public static final String READER_BEAN = "readerDataSource";

    @Bean(READER_BEAN)
    @ConditionalOnProperty(prefix = "mall.reader", name = "url")
    public DataSource readerDataSource(Environment env) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(env.getProperty("mall.reader.url"))
                .username(env.getProperty("mall.reader.username"))
                .password(env.getProperty("mall.reader.password"))
                .driverClassName(env.getProperty("mall.reader.driver-class-name", "com.mysql.cj.jdbc.Driver"))
                .build();
        ds.setPoolName("reader-ds");
        ds.setMaximumPoolSize(4);
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(5000);
        // 懒校验：bean 初始化不连接 DB（避免与 Flyway V6 建账号的时序竞争），
        // 首次真正执行 SQL 时才连接——此时迁移必然已完成
        ds.setInitializationFailTimeout(-1);
        return ds;
    }
}