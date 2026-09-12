package com.graduation.generator.config;

import com.graduation.generator.engine.FileModeGenerationEngine;
import com.graduation.generator.engine.GenerationEngine;
import com.graduation.generator.engine.MallApiGenerationEngine;
import com.graduation.generator.meta.GeneratorMetaStore;
import com.graduation.generator.adapter.FileModeTargetAdapter;
import com.graduation.generator.adapter.MallTargetAdapterRegistry;
import com.graduation.generator.adapter.ReferenceMallHttpAdapter;
import com.graduation.generator.adapter.SecondMallHttpAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

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
     * 宿主注入的引擎：<b>文件模式</b>（§3.3 B）。
     *
     * <p>{@code MALL_API} 不走这个 bean——它需要目标适配器，签名不同（见 {@link #mallApiGenerationEngine()}）。
     * 运行服务按计划的 {@code mode} 二选一，绝不互相顶替。</p>
     */
    @Bean
    public GenerationEngine generationEngine() {
        return new FileModeGenerationEngine();
    }

    /**
     * MALL_API 生成引擎（§3.3 A）：读同一份生成计划，把事件驱动成对目标商城公开接口的真实调用。
     *
     * <p>内部复用文件模式的场景与分布实现，只在落点上分流；商城公开接口不存在的动作在计划层被摘掉，
     * 不会"跳过不发还照记成功"。无参构造，便于单元测试直接 {@code new}。</p>
     */
    @Bean
    public MallApiGenerationEngine mallApiGenerationEngine() {
        return new MallApiGenerationEngine();
    }

    /**
     * 目标适配器登记处（S4a）：一个 {@code adapter_type} 一个所有者。
     *
     * <p>新增商城＝在这里加一个 {@code MallTargetAdapter} 实现，而不是去改探测服务的分支——
     * "分析系统不能被写死到某一个模拟商城上"这条固定指令，在生成器这一侧的落点就是本方法。</p>
     */
    @Bean
    public MallTargetAdapterRegistry mallTargetAdapterRegistry(
            @Value("${generator.output.root:./generator-output}") String outputRoot,
            @Value("${generator.target.probe-timeout-ms:3000}") long probeTimeoutMs) {
        return new MallTargetAdapterRegistry(List.of(
                new FileModeTargetAdapter(outputRoot),
                new ReferenceMallHttpAdapter(credentialResolver(), Duration.ofMillis(probeTimeoutMs)),
                new SecondMallHttpAdapter(credentialResolver(), Duration.ofMillis(probeTimeoutMs))));
    }

    /**
     * 凭据解析顺序：JVM 系统属性优先，其次环境变量（生产形态）。
     *
     * <p>为什么要留系统属性这一档：自动化测试里没法给<b>已经启动的</b> JVM 注入环境变量，
     * 于是"真实 HTTP 端到端"要么改成打桩、要么被迫依赖外部进程里已有的环境变量——两者都比这更糟。
     * 系统属性只是<b>同一台机器上的另一个取值来源</b>，不改变"凭据只按引用名取、绝不落库/落盘"的约束。</p>
     */
    @Bean
    public Function<String, String> generatorCredentialResolver() {
        return credentialResolver();
    }

    private static Function<String, String> credentialResolver() {
        return name -> {
            if (name == null || name.isBlank()) {
                return null;
            }
            String fromProperty = System.getProperty(name);
            return fromProperty != null && !fromProperty.isBlank() ? fromProperty : System.getenv(name);
        };
    }
}
