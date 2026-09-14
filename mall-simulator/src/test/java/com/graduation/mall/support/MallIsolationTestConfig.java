package com.graduation.mall.support;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * 把 {@link MallIsolationGuard} 接到 Spring 测试上下文里（V25-S03 R-1）。
 *
 * <p>两道接线：</p>
 * <ol>
 *   <li>{@link FlywayConfigurationCustomizer}：把 {@code BEFORE_MIGRATE} 门禁回调注册进 Flyway。
 *       这是关键的一道——Flyway 迁移是**建表 DDL**，必须在第一步就被拦。</li>
 *   <li>{@link BeanPostProcessor}：任何 {@link DataSource} bean 一创建就做 URL 预检
 *       （不建立连接、不写任何东西）。这样「配置指向宿主 3306 / root / createDatabaseIfNotExist」
 *       在**连接池被使用之前**就报错，错误信息直接指出是哪一条判据。</li>
 * </ol>
 *
 * <p>注意：本类放在 <b>test</b> 源码树，生产运行不会加载它。</p>
 */
@TestConfiguration
public class MallIsolationTestConfig {

    /** Flyway 回调注册（BEFORE_MIGRATE 写前校验）。 */
    @Bean
    FlywayConfigurationCustomizer mallIsolationFlywayCustomizer() {
        return configuration -> configuration.callbacks(new MallIsolationGuard.BeforeMigrateGuard());
    }

    /** DataSource URL 预检：不建立连接，纯文本判据。 */
    @Bean
    static BeanPostProcessor mallIsolationDataSourcePrecheck() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds) {
                    String url = readUrl(ds);
                    if (url != null && !url.isBlank()) {
                        MallIsolationGuard.assertUrlAllowed(url, "spring-datasource:" + beanName);
                    }
                }
                return bean;
            }

            private String readUrl(DataSource ds) {
                for (String accessor : new String[] {"getUrl", "getURL", "getJdbcUrl"}) {
                    try {
                        Object value = ds.getClass().getMethod(accessor).invoke(ds);
                        if (value instanceof String s && !s.isBlank()) {
                            return s;
                        }
                    } catch (ReflectiveOperationException ignored) {
                        // Hikari 等包装类型读不到 URL：留给 Flyway 回调 + 写前实连校验兜底
                    }
                }
                return null;
            }
        };
    }
}
