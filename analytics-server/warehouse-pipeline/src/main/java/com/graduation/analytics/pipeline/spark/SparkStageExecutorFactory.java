package com.graduation.analytics.pipeline.spark;

import com.graduation.analytics.pipeline.mapper.SparkJobRunMapper;
import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.runtime.submit.JobSubmitter;
import com.graduation.analytics.runtime.submit.JobSubmitterFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * R6-11（V2.0 §15.3）：阶段执行器工厂——生产 {@code PipelineService} 的唯一注入点。
 *
 * 职责：把"不可变环境快照"翻译成"可执行的真实 Spark 阶段执行器"：
 *   快照 → {@link JobSubmitterFactory} 选提交器（LOCAL 进程 / SSH）→ {@link SparkStageExecutor}
 *
 * 这样 PipelineService 只依赖本工厂（可注入 Fake 做 L1 编排测试），既不关心提交器选择，
 * 也不关心轮询/超时细节；同时保证同一次 run 用同一份快照（R6-10 不可变输入）。
 */
@Slf4j
public class SparkStageExecutorFactory {

    private final JobSubmitterFactory submitterFactory;
    private final SparkJobRunMapper jobRunMapper;
    private final long maxWaitMs;
    private final long pollIntervalMs;

    public SparkStageExecutorFactory(JobSubmitterFactory submitterFactory,
                                     SparkJobRunMapper jobRunMapper,
                                     long maxWaitMs, long pollIntervalMs) {
        this.submitterFactory = submitterFactory;
        this.jobRunMapper = jobRunMapper;
        this.maxWaitMs = maxWaitMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    /** 按环境快照构造执行器；每次调用新实例（无跨 run 状态） */
    public SparkStageExecutor create(RuntimeProfileSnapshot profile) {
        JobSubmitter submitter = submitterFactory.create(profile);
        log.debug("SparkStageExecutorFactory: profile={} v{} submitter={} maxWaitMs={} pollIntervalMs={}",
                profile.id(), profile.version(), submitter.type(), maxWaitMs, pollIntervalMs);
        return new SparkStageExecutor(submitter, jobRunMapper, maxWaitMs, pollIntervalMs);
    }

    public long maxWaitMs() {
        return maxWaitMs;
    }

    public long pollIntervalMs() {
        return pollIntervalMs;
    }
}
