package com.graduation.analytics.config;

import com.graduation.analytics.contracts.EventClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.Executor;

/**
 * 平台通用 Bean（R1）：EventClock 模拟时钟（§20.3，业务时间与系统时间分离）。
 * 商城侧在 mall-simulator AppConfig 注册；平台独立进程自行注册（整改书 §5.2 迁移语义）。
 */
@Configuration
public class PlatformBeans {

    @Bean
    public EventClock eventClock() {
        return new EventClock(Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * R6（§13.1）：流水线后台执行线程池。POST 提交后立即返回 taskId+PENDING，
     * 计算链在异步线程中执行；同幂等键并发提交只产生一个任务（幂等键锁在 PipelineService）。
     */
    @Bean(name = "pipelineExecutor")
    public Executor pipelineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("pipeline-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}