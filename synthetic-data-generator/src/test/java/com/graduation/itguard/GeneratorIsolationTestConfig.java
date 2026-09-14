package com.graduation.itguard;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * 把 {@link IsolationGuard} 接到 Spring 测试上下文里。
 *
 * <p>用于 {@code synthetic-data-generator} 的 {@code @SpringBootTest} 用例：
 * 它们会用 {@code application.yml} 的<b>默认 profile</b> 起整个应用，
 * 也就是连 {code 127.0.0.1:3306/generator_meta} + {@code root}（见 V25-S03 R-4b 记录），
 * 然后通过 {@code GeneratorMetaStore} 真写库。所以门禁必须挂在<b>上下文启动期</b>，
 * 而不是等用例方法里再检查——那时候 Flyway 已经建完表、用例可能已经写了几行。</p>
 *
 * <p>两道接线：</p>
 * <ol>
 *   <li>{@link FlywayConfigurationCustomizer}：注册 {@code BEFORE_MIGRATE} 回调。
 *       Flyway 迁移是建表 DDL，必须在第一步被拦。</li>
 *   <li>{@link BeanPostProcessor}：任何 {@link DataSource} bean 一创建就做 URL 预检
 *       （不建立连接、不写任何东西），并做库名/账号判据。</li>
 * </ol>
 *
 * <p>本类在 <b>test</b> 源码树，生产运行不会加载它。</p>
 */
@TestConfiguration
public class GeneratorIsolationTestConfig {

    /** Flyway 回调：BEFORE_MIGRATE 写前核对。 */
    @Bean
    FlywayConfigurationCustomizer generatorIsolationFlywayCustomizer() {
        return configuration -> configuration.callbacks(new BeforeMigrateGuard());
    }

    /** DataSource URL 预检。 */
    @Bean
    static BeanPostProcessor generatorIsolationDataSourcePrecheck() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds) {
                    String url = readUrl(ds);
                    // 读不到 URL（Hikari 等包装）时留给 Flyway 回调 + 写前实连核对兜底，
                    // 但只要读到了，就绝不放过去。
                    if (url != null && !url.isBlank()) {
                        IsolationGuard.assertUrlAllowed(url, "spring-datasource:" + beanName);
                    }
                }
                return bean;
            }

            private String readUrl(DataSource ds) {
                for (String accessor : new String[] {"getUrl", "getURL", "getJdbcUrl"}) {
                    try {
                        Object v = ds.getClass().getMethod(accessor).invoke(ds);
                        if (v instanceof String s && !s.isBlank()) {
                            return s;
                        }
                    } catch (ReflectiveOperationException ignored) {
                        // 见上：包装类型读不到 URL 是预期情形
                    }
                }
                return null;
            }
        };
    }

    /**
     * Flyway {@code BEFORE_MIGRATE} 门禁。
     *
     * <p>未显式开启即拒——这样"没准备隔离环境"表现为<b>上下文启动失败</b>（用例红），
     * 而不是静默连上默认 profile 的正式实例。</p>
     */
    static final class BeforeMigrateGuard implements Callback {

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.BEFORE_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            requireEnabledOrRefuse();
            DataSource ds = context.getConnection() == null
                    ? null : context.getConfiguration().getDataSource();
            IsolationGuard.verifyBeforeWrite(ds, null, "flyway-before-migrate");
        }

        private void requireEnabledOrRefuse() {
            try {
                IsolationGuard.requireEnabled("flyway-before-migrate");
            } catch (IsolationGuard.GuardViolation e) {
                throw new IsolationGuard.GuardViolation(e.getMessage()
                        + "（整改前本模块的 @SpringBootTest 用默认 profile 连 127.0.0.1:3306/"
                        + "generator_meta + root 并真写库；V25-S03 R-4b 起在 Flyway 迁移前拒绝。）");
            }
        }

        @Override
        public String getCallbackName() {
            return "generator-isolation-guard";
        }
    }
}
