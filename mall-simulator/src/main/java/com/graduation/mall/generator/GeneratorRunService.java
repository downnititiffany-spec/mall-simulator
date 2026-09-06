package com.graduation.mall.generator;

import com.graduation.mall.generator.scenario.ScenarioRegistry;
import com.graduation.mall.generator.scenario.ScenarioStrategy;
import com.graduation.mall.outbox.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 生成器运行服务（§5.2.3）：配置 → 场景因子 → 引擎 → 脏数据注入 → 摘要。
 * 所有 AI 标准答案（ExpectedEffect）只进入结果，不传给模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratorRunService {

    private final SimulationEngine engine;
    private final DirtyDataInjector dirtyInjector;

    /**
     * 同步执行一次生成（演示/测试规模；大规模转为异步任务属于平台阶段）。
     */
    public GenerationResult run(GeneratorConfig config) {
        ScenarioStrategy strategy = ScenarioRegistry.get(config.scenario());
        long start = System.currentTimeMillis();
        GenerationResult base = engine.run(config, strategy.factors(config), TraceContext.create());

        int dirtyCount = (int) Math.round(config.dirtyDataRate() * base.totalEvents());
        var dirty = dirtyCount > 0
                ? dirtyInjector.inject(config.dirtyDataRate(), (int) base.totalEvents(),
                        base.sampleEventIds(), new DistributionKit(config.randomSeed() + 7))
                : java.util.List.<DirtyDataInjector.DirtySample>of();

        log.info("generator run completed: scenario={} users={} events={} orders={} paid={} gmv={} dirty={} in {}ms",
                config.scenario(), base.usersCreated(), base.totalEvents(), base.ordersCreated(),
                base.ordersPaid(), base.gmv(), dirty.size(), System.currentTimeMillis() - start);

        return GenerationResult.builder()
                .configKey(base.configKey())
                .scenario(base.scenario())
                .expectedEffect(base.expectedEffect())
                .usersCreated(base.usersCreated())
                .productsCreated(base.productsCreated())
                .behaviorsByType(base.behaviorsByType())
                .ordersCreated(base.ordersCreated())
                .ordersPaid(base.ordersPaid())
                .ordersCancelled(base.ordersCancelled())
                .ordersCompleted(base.ordersCompleted())
                .refundsApplied(base.refundsApplied())
                .refundsCompleted(base.refundsCompleted())
                .gmv(base.gmv())
                .netSale(base.netSale())
                .avgOrderValue(base.avgOrderValue())
                .stockShortageHits(base.stockShortageHits())
                .totalEvents(base.totalEvents())
                .sampleEventIds(base.sampleEventIds())
                .dirtySamples(dirty)
                .build();
    }

    /** 便捷构造（Controller 与测试共用） */
    public GeneratorConfig config(int userCount, int eventsPerSecond, double baseConversionRate,
                                  LocalDateTime start, LocalDateTime end, long seed,
                                  double dirtyRate, String scenario) {
        return new GeneratorConfig(userCount, 0, eventsPerSecond, baseConversionRate, start, end,
                seed, dirtyRate, scenario);
    }
}