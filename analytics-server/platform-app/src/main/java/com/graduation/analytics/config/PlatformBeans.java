package com.graduation.analytics.config;

import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.pipeline.mapper.SparkJobRunMapper;
import com.graduation.analytics.pipeline.spark.SparkStageExecutorFactory;
import com.graduation.analytics.runtime.credential.CredentialService;
import com.graduation.analytics.runtime.submit.JobSubmitterFactory;
import com.graduation.analytics.warehouse.WarehouseNamespaceProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.Executor;

/**
 * 平台通用 Bean（R1）：EventClock 模拟时钟（§20.3，业务时间与系统时间分离）。
 * 商城侧在 mall-simulator AppConfig 注册；平台独立进程自行注册（整改书 §5.2 迁移语义）。
 * R6-11（V2.0 §15.3）：新增提交器工厂与阶段执行器工厂——生产 PipelineService 的真实 Spark 注入点。
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

    /**
     * R6-10（V2.0 §15.3）：提交器工厂。LOCAL/SINGLE_NODE → 本地进程；REMOTE_CLUSTER → SSH。
     * 日志根与 landing 根同源（landing/logs），运维可在 landing 目录内按 runId 检索作业日志。
     */
    @Bean
    public JobSubmitterFactory jobSubmitterFactory(
            CredentialService credentialService,
            @Value("${platform.landing.local-root:./landing}") String landingRoot) {
        String logRoot = landingRoot.endsWith("/") || landingRoot.endsWith("\\")
                ? landingRoot + "logs" : landingRoot + "/logs";
        return new JobSubmitterFactory(credentialService, logRoot);
    }

    /**
     * R6-11（V2.0 §15.3）：阶段执行器工厂。超时/轮询可配置；默认 15 分钟等待、2 秒轮询
     * （小样本黄金链秒级完成，集群模式留足余量）。
     * warehouse/metastore 显式配置：LOCAL 的嵌入式 Hive 不能按 spark-submit 的 CWD 漂移
     * （否则 sci 建表与 odl 装载会看到不同 warehouse，历史 R6-7 已踩坑）。
     *
     * <p>P2-07：另注入 {@link WarehouseNamespaceProvider}——库名前缀按本次运行的源解析，
     * 在工厂里解析一次并随执行器冻结整轮 run。</p>
     */
    @Bean
    public SparkStageExecutorFactory sparkStageExecutorFactory(
            JobSubmitterFactory jobSubmitterFactory,
            SparkJobRunMapper sparkJobRunMapper,
            WarehouseNamespaceProvider warehouseNamespaceProvider,
            @Value("${platform.spark.job-timeout-ms:900000}") long maxWaitMs,
            @Value("${platform.spark.poll-interval-ms:2000}") long pollIntervalMs,
            @Value("${platform.spark.warehouse-dir:./spark-warehouse}") String warehouseDir,
            @Value("${platform.spark.metastore-dir:./derby-metastore}") String metastoreDir) {
        return new SparkStageExecutorFactory(jobSubmitterFactory, sparkJobRunMapper,
                warehouseNamespaceProvider, maxWaitMs, pollIntervalMs, warehouseDir, metastoreDir);
    }
}
