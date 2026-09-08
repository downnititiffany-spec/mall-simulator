package com.graduation.analytics.pipeline.spark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.pipeline.entity.SparkJobRun;
import com.graduation.analytics.pipeline.mapper.SparkJobRunMapper;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.submit.JobSubmitter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * R6-6：阶段作业执行器（§13.2/§13.3）。
 * 把计算阶段映射为 spark-jobs 作业序列（JobRegistry 依赖：odl → bdw/dim/tdw → usw → fna），
 * 经 JobSubmitter 提交（externalJobId 非空即提交成功）、轮询日志（JobResultParser 解析
 * 最终 JobResult JSON，取最后一行）、落库 spark_job_run（argumentsJson 可溯源 §21.10）。
 * 判定规则：CANCELLED → 失败；日志有 JobResult 行 → 按行内 status 判定；超时无结果行 →
 * 失败（契约缺失不冒充成功）；进程退出码非 0 覆盖日志声称的 SUCCESS（§13.2 阶段不能自证）。
 */
@Slf4j
public class SparkStageExecutor {

    /** 阶段 → 作业序列（与 spark-jobs JobRegistry 依赖对齐）：QUALITY_CHECK/PUBLISH_METRIC 无外部作业 */
    private static final Map<String, List<String>> STAGE_JOBS = Map.of(
            "LOAD_ODS", List.of("odl"),
            "BUILD_DWD", List.of("bdw", "dim", "tdw"),
            "BUILD_DWS", List.of("usw"),
            "BUILD_ADS", List.of("fna"));

    private final JobSubmitter submitter;
    private final SparkJobRunMapper runMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long maxWaitMs;
    private final long pollIntervalMs;

    public SparkStageExecutor(JobSubmitter submitter, SparkJobRunMapper runMapper,
                              long maxWaitMs, long pollIntervalMs) {
        this.submitter = submitter;
        this.runMapper = runMapper;
        this.maxWaitMs = maxWaitMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    /** 阶段 → 作业序列（未知阶段返回空表：无外部作业，阶段由编排方本地判定） */
    public static List<String> stageJobs(String stageCode) {
        return STAGE_JOBS.getOrDefault(stageCode, List.of());
    }

    /** 一次作业执行结果（§13.3：externalJobId/记录数/状态/错误）。 */
    public record JobExecution(String externalJobId, String jobCode, String status,
                               long inputRecords, long outputRecords, long rejectedRecords,
                               String errorMessage) {
    }

    /**
     * 执行一个阶段的所有外部作业（顺序提交等待，§13.2）。任一作业失败不熔断后续（由
     * 编排方按阶段结果判定）；本方法只保证每个作业提交成功并如实记录状态。
     */
    public List<JobExecution> executeStage(RuntimeProfile profile, Long pipelineRunId,
                                           String stageCode, String businessDate, int attemptNo,
                                           Map<String, String> extraArgs) {
        return executeStage(profile, pipelineRunId, stageCode, businessDate, attemptNo,
                extraArgs, null);
    }

    /** 完整版：显式 --conf（如 spark.sql.warehouse.dir 分区隔离），null 等价便捷版。 */
    public List<JobExecution> executeStage(RuntimeProfile profile, Long pipelineRunId,
                                           String stageCode, String businessDate, int attemptNo,
                                           Map<String, String> extraArgs,
                                           Map<String, String> confs) {
        List<JobExecution> results = new ArrayList<>();
        for (String jobCode : stageJobs(stageCode)) {
            results.add(executeJob(profile, pipelineRunId, stageCode, jobCode,
                    businessDate, attemptNo, extraArgs, confs));
        }
        return results;
    }

    /** 提交单个作业并等待结果（§13.3：externalJobId 落库非空、argumentsJson 快照溯源） */
    private JobExecution executeJob(RuntimeProfile profile, Long pipelineRunId, String stageCode,
                                    String jobCode, String businessDate, int attemptNo,
                                    Map<String, String> extraArgs, Map<String, String> confs) {
        List<String> command = JobCommandBuilder.build(profile, jobCode, businessDate,
                profile.getId(), attemptNo, extraArgs, confs);

        JobSubmitter.SubmitResult sr = submitter.submit(command, "pipeline-" + pipelineRunId + "-" + stageCode);

        SparkJobRun run = new SparkJobRun();
        run.setRuntimeProfileId(profile.getId());
        run.setRuntimeProfileVersion(profile.getVersion());
        run.setPipelineRunId(pipelineRunId);
        run.setStageCode(stageCode);
        run.setJobCode(jobCode);
        run.setExternalJobId(sr.externalJobId()); // 非空即提交成功（§13.3）
        run.setSubmitterType(submitter.type());
        run.setArgumentsJson(toJson(command));
        run.setStatus(SparkJobRun.STATUS_SUBMITTED);
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        // 轮询：CANCELLED/FAILED 进程状态、或日志出现 JobResult 行 → 终结；否则直到超时
        String st = submitter.status(sr.externalJobId());
        Optional<JobResultParser.JobResultInfo> parsed = Optional.empty();
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            st = submitter.status(sr.externalJobId());
            parsed = JobResultParser.parseLog(submitter.logs(sr.externalJobId()));
            if ("CANCELLED".equals(st) || "FAILED".equals(st) || "SUCCESS".equals(st)) {
                break;
            }
            if (parsed.isPresent()
                    && ("SUCCESS".equals(parsed.get().status()) || "FAILED".equals(parsed.get().status()))) {
                break;
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        JobResultParser.JobResultInfo info = resolve(st, parsed, jobCode);
        run.setInputRecords(info.inputRecords());
        run.setOutputRecords(info.outputRecords());
        run.setRejectedRecords(info.rejectedRecords());
        run.setStatus(info.success() ? SparkJobRun.STATUS_SUCCESS : SparkJobRun.STATUS_FAILED);
        run.setFinishedAt(LocalDateTime.now());
        if (!info.success()) {
            run.setErrorCode("JOB_EXECUTION_FAILED");
            run.setErrorMessage(info.message());
        }
        runMapper.updateById(run);

        return new JobExecution(run.getExternalJobId(), jobCode, run.getStatus(),
                run.getInputRecords(), run.getOutputRecords(), run.getRejectedRecords(),
                info.success() ? null : info.message());
    }

    /** 按提交器状态 + 日志结果行综合判定（与 JobResultParser.resolve 语义一致） */
    private JobResultParser.JobResultInfo resolve(String st,
                                                  Optional<JobResultParser.JobResultInfo> parsed,
                                                  String jobCode) {
        if ("CANCELLED".equals(st)) {
            return new JobResultParser.JobResultInfo(jobCode, 0, 0, 0, null, 0,
                    "FAILED", "外部任务被取消", 0);
        }
        if ("FAILED".equals(st)) {
            // 进程退出码非 0：即使日志尾行声称 SUCCESS 也判失败（§13.2 不冒充成功）
            return JobResultParser.resolve(1, parsed);
        }
        if (parsed.isPresent()) {
            boolean success = "SUCCESS".equals(parsed.get().status());
            return JobResultParser.resolve(success ? 0 : 1, parsed);
        }
        // 无结果行：无论进程态如何都判失败（契约缺失）
        long exitCode = "SUCCESS".equals(st) ? 0 : 1;
        return JobResultParser.resolve((int) exitCode, parsed);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}