package com.graduation.analytics.pipeline.spark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.pipeline.entity.SparkJobRun;
import com.graduation.analytics.pipeline.mapper.SparkJobRunMapper;
import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.runtime.submit.JobSubmitter;
import com.graduation.analytics.warehouse.RunSourceIdentity;
import com.graduation.analytics.warehouse.WarehouseNamespace;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * R6-6/R6-11：阶段作业执行器（§13.2/§13.3，V2.0 §15.3）。
 * 把计算阶段映射为 spark-jobs 作业序列（JobRegistry 依赖：odl → bdw/dim → tdw → usw → fna），
 * 经 JobSubmitter 提交（externalJobId 非空即提交成功）、轮询日志（JobResultParser 解析
 * 最终 JobResult JSON，取最后一行）、落库 spark_job_run（argumentsJson 可溯源 §21.10）。
 *
 * 判定规则：CANCELLED → 失败；日志有 JobResult 行 → 按行内 status 判定；超时无结果行 →
 * 失败（契约缺失不冒充成功）；进程退出码非 0 覆盖日志声称的 SUCCESS（§13.2 阶段不能自证）。
 *
 * R6-11 变更：
 * ①入参为不可变 {@link RuntimeProfileSnapshot}（与 JobCommandBuilder 同源，§15.3 R6-10）；
 * ②**阶段内 fail-fast**：任一作业失败立即停止本阶段剩余作业（V2.0 §15.2 原"失败仍继续"
 *   会浪费算力并让依赖作业读到半成品），由编排方据 {@link StageExecution#failed()} 判定；
 * ③真实输出证据：externalJobId / input / output / rejected / logUri 逐作业落 spark_job_run
 *   （§15.3 R6-12：计数只能来自 JobResult，禁止用输入数或常量冒充输出数）。
 */
@Slf4j
public class SparkStageExecutor {

    /**
     * 阶段 → 作业序列（与 spark-jobs JobRegistry 依赖对齐）。
     * R6-13：QUALITY_CHECK 由真实作业 dqc 承载（读 ADS 暂存分区 + DWS 对账），
     * PUBLISH_METRIC 由 pub 承载（Hive 元数据指针把正式分区指向已过质量门的暂存路径）。
     * R7-3：PUBLISH_METRIC = pub（Hive 侧发布）→ mxp（把已发布正式 ADS 导出给指标库发布器）。
     */
    private static final Map<String, List<String>> STAGE_JOBS = Map.of(
            "INIT_SCHEMA", List.of("sci"),
            "LOAD_ODS", List.of("odl"),
            // 顺序 = 依赖顺序（`:134` 按列表顺序**串行**提交，不做拓扑排序）：
            // `bdw`（DwdSql.behaviorClean）LEFT JOIN `dim_user`/`dim_product` 且谓词带
            // `u.dt = '<业务日>'` ⇒ `dim` 必须先产出当日快照，否则 `city_level`/`category_id`/
            // `category_key` 在当日首次运行时静默退化为 NULL/-1（不报错、不失败）。
            // `tdw` 同样依赖 `dim`（其 SQL 亦 JOIN 两张维表）⇒ `dim` 排在最前同时服务两者。
            "BUILD_DWD", List.of("dim", "bdw", "tdw"),
            "BUILD_DWS", List.of("usw"),
            "BUILD_ADS", List.of("fna"),
            "QUALITY_CHECK", List.of("dqc"),
            "PUBLISH_METRIC", List.of("pub", "mxp"));

    private final JobSubmitter submitter;
    private final SparkJobRunMapper runMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long maxWaitMs;
    private final long pollIntervalMs;
    /** 本次运行的源身份（P2-07/A12：由工厂按快照的 sourceId 解析一次，全程冻结） */
    private final RunSourceIdentity source;

    public SparkStageExecutor(JobSubmitter submitter, SparkJobRunMapper runMapper,
                              long maxWaitMs, long pollIntervalMs,
                              RunSourceIdentity source) {
        this.submitter = submitter;
        this.runMapper = runMapper;
        this.maxWaitMs = maxWaitMs;
        this.pollIntervalMs = pollIntervalMs;
        this.source = source;
    }

    /** 阶段 → 作业序列（未知阶段返回空表：无外部作业，阶段由编排方本地判定） */
    public static List<String> stageJobs(String stageCode) {
        return STAGE_JOBS.getOrDefault(stageCode, List.of());
    }

    /** 该阶段是否由真实 Spark 作业承载（编排方据此决定是否调执行器） */
    public static boolean hasExternalJobs(String stageCode) {
        return !stageJobs(stageCode).isEmpty();
    }

    /**
     * 一次作业执行结果（§13.3：externalJobId/记录数/状态/错误；R6-12：输出分区证据）。
     */
    public record JobExecution(String externalJobId, String jobCode, String status,
                               long inputRecords, long outputRecords, long rejectedRecords,
                               String logUri, String errorMessage,
                               List<JobResultParser.OutputPartitionInfo> outputPartitions,
                               List<JobResultParser.CheckInfo> checks) {
        public boolean success() {
            return SparkJobRun.STATUS_SUCCESS.equals(status);
        }
    }

    /**
     * 阶段执行结果：作业明细 + 是否失败（fail-fast 后剩余作业未提交，jobs() 只含已提交项）。
     */
    public record StageExecution(String stageCode, List<JobExecution> jobs, boolean failed,
                                 String errorMessage) {
        public long totalInputRecords() {
            return jobs.stream().mapToLong(JobExecution::inputRecords).sum();
        }

        public long totalOutputRecords() {
            return jobs.stream().mapToLong(JobExecution::outputRecords).sum();
        }

        public long totalRejectedRecords() {
            return jobs.stream().mapToLong(JobExecution::rejectedRecords).sum();
        }

        /** 本阶段全部质量检查结果（含失败作业的 checks：§16.3 失败证据不得丢失） */
        public List<JobResultParser.CheckInfo> checks() {
            return jobs.stream().flatMap(j -> j.checks().stream()).toList();
        }
    }

    /**
     * 执行一个阶段的所有外部作业（顺序提交等待，§13.2）。
     * R6-11 fail-fast：某作业失败后**不再提交**本阶段剩余作业（依赖作业不得读半成品），
     * 失败原因通过 {@link StageExecution#errorMessage()} 上抛给编排方。
     */
    public StageExecution executeStage(RuntimeProfileSnapshot profile, Long pipelineRunId,
                                       String stageCode, String businessDate, int attemptNo,
                                       Map<String, String> extraArgs) {
        return executeStage(profile, pipelineRunId, stageCode, businessDate, attemptNo,
                extraArgs, null);
    }

    /** 完整版：显式 --conf（如 spark.sql.warehouse.dir 分区隔离），null 等价便捷版。 */
    public StageExecution executeStage(RuntimeProfileSnapshot profile, Long pipelineRunId,
                                       String stageCode, String businessDate, int attemptNo,
                                       Map<String, String> extraArgs,
                                       Map<String, String> confs) {
        List<JobExecution> results = new ArrayList<>();
        for (String jobCode : stageJobs(stageCode)) {
            JobExecution exec = executeJob(profile, pipelineRunId, stageCode, jobCode,
                    businessDate, attemptNo, extraArgs, confs);
            results.add(exec);
            if (!exec.success()) {
                log.warn("stage {} fail-fast: job {} 失败（{}），不再提交剩余作业 {}",
                        stageCode, jobCode, exec.errorMessage(),
                        stageJobs(stageCode).subList(results.size(), stageJobs(stageCode).size()));
                return new StageExecution(stageCode, results, true,
                        jobCode + ": " + exec.errorMessage());
            }
        }
        return new StageExecution(stageCode, results, false, null);
    }

    /** 提交单个作业并等待结果（§13.3：externalJobId 落库非空、argumentsJson 快照溯源） */
    private JobExecution executeJob(RuntimeProfileSnapshot profile, Long pipelineRunId, String stageCode,
                                    String jobCode, String businessDate, int attemptNo,
                                    Map<String, String> extraArgs, Map<String, String> confs) {
        List<String> command = JobCommandBuilder.build(profile, source, jobCode, businessDate,
                profile.id(), attemptNo, extraArgs, confs);

        // §15.3 R6-12：日志前缀携带 runId/stage/jobCode/attempt，运维可按运行检索
        String logPrefix = "pipeline-" + pipelineRunId + "-" + stageCode + "-" + jobCode + "-a" + attemptNo;
        JobSubmitter.SubmitResult sr = submitter.submit(command, logPrefix);

        SparkJobRun run = new SparkJobRun();
        run.setRuntimeProfileId(profile.id());
        run.setRuntimeProfileVersion(profile.version());
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
        // R6-12：真实输出分区证据（表/dt/snapshotId/行数/路径）逐作业落库，供审计与对账
        if (!info.outputPartitions().isEmpty()) {
            run.setOutputPartitionsJson(toJson(info.outputPartitions()));
        }
        run.setLogUri(submitter.logUri(sr.externalJobId())); // R6-12：真实可访问日志位置
        run.setStatus(info.success() ? SparkJobRun.STATUS_SUCCESS : SparkJobRun.STATUS_FAILED);
        run.setFinishedAt(LocalDateTime.now());
        if (!info.success()) {
            run.setErrorCode("JOB_EXECUTION_FAILED");
            run.setErrorMessage(info.message());
        }
        runMapper.updateById(run);

        return new JobExecution(run.getExternalJobId(), jobCode, run.getStatus(),
                run.getInputRecords(), run.getOutputRecords(), run.getRejectedRecords(),
                run.getLogUri(), info.success() ? null : info.message(), info.outputPartitions(),
                info.checks());
    }

    /** 按提交器状态 + 日志结果行综合判定（与 JobResultParser.resolve 语义一致） */
    private JobResultParser.JobResultInfo resolve(String st,
                                                  Optional<JobResultParser.JobResultInfo> parsed,
                                                  String jobCode) {
        if ("CANCELLED".equals(st)) {
            return new JobResultParser.JobResultInfo(jobCode, 0, 0, 0, null, 0,
                    "FAILED", "外部任务被取消", 0, List.of(), List.of());
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
