package com.graduation.analytics.runtime.submit;

import java.util.List;

/**
 * 作业提交器（整改书 §8.2）：submit/status/logs/cancel/healthCheck。
 * 首版实现：LocalProcessSparkSubmitter、SshSparkSubmitter；第二阶段 LivyJobSubmitter。
 * submit 必须返回非空 externalJobId（§13.3：非空即提交成功），并使用 runtimeProfile 的
 * 配置（sparkSubmitPath/jar/参数）——不再出现脚本里写死的绝对路径（§8.4）。
 */
public interface JobSubmitter {

    /** local-process / ssh / livy */
    String type();

    /**
     * 提交 Spark 作业（参数为完整命令行数组：spark-submit ... --key=value）
     *
     * @return 非空外部任务 ID
     */
    SubmitResult submit(List<String> command, String logPrefix);

    /** 查询任务状态 */
    String status(String externalJobId);

    /** 拉取任务日志（截断到合理长度） */
    String logs(String externalJobId);

    /**
     * 日志位置（R6-12，V2.0 §15.3）：可访问的日志 URI/路径，落 spark_job_run.log_uri，
     * 供运维与验收溯源。默认无（远程实现可返回远端路径）。
     */
    default String logUri(String externalJobId) {
        return null;
    }

    /** 取消任务 */
    void cancel(String externalJobId);

    /** 连通性检查：本地 spark-submit 可执行 / SSH 可达且 spark-submit 存在 */
    HealthResult healthCheck();

    record SubmitResult(String externalJobId, String detail) {
    }

    record HealthResult(boolean ok, String detail) {
    }
}