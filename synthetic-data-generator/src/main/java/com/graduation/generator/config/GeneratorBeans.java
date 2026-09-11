package com.graduation.generator.config;

import com.graduation.generator.engine.FileModeGenerationEngine;
import com.graduation.generator.engine.GenerationEngine;
import com.graduation.generator.meta.GeneratorMetaStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 生成器装配。
 *
 * <p>{@link GeneratorMetaStore} 与 {@link GenerationEngine} 刻意<b>不打</b> {@code @Component} 注解：
 * 元数据仓是纯 JDBC 适配器、引擎是纯算法实现，两处都不依赖 Spring，这样它们能在不起容器的情况下被单元测试
 * （S2/S3a 的 39 个测试正是这么跑的）。装配集中在这里，谁需要什么一眼可见。</p>
 */
@Configuration
public class GeneratorBeans {

    @Bean
    public GeneratorMetaStore generatorMetaStore(JdbcTemplate jdbcTemplate) {
        return new GeneratorMetaStore(jdbcTemplate);
    }

    /**
     * 当前只有文件模式引擎（§3.3 B）。
     *
     * <p>{@code MALL_API} 的 HTTP 适配属于 S4，届时按模式选择实现由运行服务决定；
     * 在那之前对 {@code MALL_API} 计划显式返回 501（见 {@code GenerationRunService.start}），
     * 绝不用文件模式顶替。</p>
     */
    @Bean
    public GenerationEngine generationEngine() {
        return new FileModeGenerationEngine();
    }
}
