package com.graduation.analytics.pipeline.spark;

import com.graduation.analytics.pipeline.entity.SparkJobRun;
import com.graduation.analytics.pipeline.mapper.SparkJobRunMapper;
import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.runtime.submit.FakeJobSubmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R6-6：阶段作业执行器快速测试（L1，不启动 Spark；FakeJobSubmitter 驱动）。
 * 覆盖 §13.2/§13.3：阶段→作业映射、externalJobId 非空即提交成功、JobResult 解析、
 * 退出码非 0 覆盖日志成功、无结果行判失败、argumentsJson 溯源、spark_job_run 落库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SparkStageExecutorTest {

    @Mock
    private SparkJobRunMapper runMapper;

    private FakeJobSubmitter submitter;
    private SparkStageExecutor executor;
    private RuntimeProfileSnapshot profile;
    private java.util.concurrent.atomic.AtomicLong idSeq;

    @BeforeEach
    void setUp() {
        submitter = new FakeJobSubmitter();
        executor = new SparkStageExecutor(submitter, runMapper, 2_000L, 5L);
        idSeq = new java.util.concurrent.atomic.AtomicLong(100L);
        com.graduation.analytics.runtime.entity.RuntimeProfile entity =
                new com.graduation.analytics.runtime.entity.RuntimeProfile();
        entity.setId(7L);
        entity.setVersion(3);
        entity.setType(com.graduation.analytics.runtime.entity.RuntimeProfile.TYPE_LOCAL);
        entity.setSparkSubmitPath("D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd");
        entity.setSparkJobJarUri("spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar");
        profile = RuntimeProfileSnapshot.from(entity);
        when(runMapper.insert(any(SparkJobRun.class)))
                .thenAnswer(inv -> {
                    SparkJobRun run = inv.getArgument(0);
                    run.setId(idSeq.getAndIncrement());
                    return 1;
                });
    }

    /** R6-11：阶段执行返回 StageExecution，测试统一取作业明细 */
    private List<SparkStageExecutor.JobExecution> runStage(long runId, String stageCode) {
        return executor.executeStage(profile, runId, stageCode, "20260901", 1, Map.of()).jobs();
    }

    private String successLog(String jobCode, long in, long out, long rej) {
        return "INFO some spark log\n{\"jobCode\":\"" + jobCode + "\",\"inputRecords\":" + in
                + ",\"outputRecords\":" + out + ",\"rejectedRecords\":" + rej
                + ",\"attemptNo\":1,\"status\":\"SUCCESS\",\"message\":\"ok\",\"elapsedMs\":1200}";
    }

    @Test
    void stageMappingCoversAllCalculationStages() {
        assertThat(SparkStageExecutor.stageJobs("INIT_SCHEMA")).containsExactly("sci");
        assertThat(SparkStageExecutor.stageJobs("LOAD_ODS")).containsExactly("odl");
        assertThat(SparkStageExecutor.stageJobs("BUILD_DWD")).containsExactly("bdw", "dim", "tdw");
        assertThat(SparkStageExecutor.stageJobs("BUILD_DWS")).containsExactly("usw");
        assertThat(SparkStageExecutor.stageJobs("BUILD_ADS")).containsExactly("fna");
        // R6-13：质量门与发布由真实作业承载（dqc 读暂存分区，pub 用元数据指针发布正式分区）
        assertThat(SparkStageExecutor.stageJobs("QUALITY_CHECK")).containsExactly("dqc");
        // R7-3：发布后 mxp 把已发布的正式 ADS 导出给指标库发布器（Hive→MySQL 快照发布）
        assertThat(SparkStageExecutor.stageJobs("PUBLISH_METRIC")).containsExactly("pub", "mxp");
    }

    @Test
    void loadOdsSubmitsOdlJobAndPersistsSparkJobRun() {
        submitter.program("SUBMITTED", successLog("odl", 36, 35, 1));

        List<SparkStageExecutor.JobExecution> results = runStage(11L, "LOAD_ODS");

        assertThat(results).hasSize(1);
        SparkStageExecutor.JobExecution r = results.get(0);
        assertThat(r.externalJobId()).isNotBlank(); // §13.3：非空即提交成功
        assertThat(r.jobCode()).isEqualTo("odl");
        assertThat(r.status()).isEqualTo("SUCCESS");
        assertThat(r.inputRecords()).isEqualTo(36);
        assertThat(r.outputRecords()).isEqualTo(35);
        assertThat(r.rejectedRecords()).isEqualTo(1);

        ArgumentCaptor<SparkJobRun> captor = ArgumentCaptor.forClass(SparkJobRun.class);
        verify(runMapper, times(1)).insert(captor.capture());
        SparkJobRun saved = captor.getValue();
        assertThat(saved.getExternalJobId()).isEqualTo(r.externalJobId());
        assertThat(saved.getJobCode()).isEqualTo("odl");
        assertThat(saved.getStageCode()).isEqualTo("LOAD_ODS");
        assertThat(saved.getPipelineRunId()).isEqualTo(11L);
        assertThat(saved.getStatus()).isEqualTo(SparkJobRun.STATUS_SUCCESS);
        assertThat(saved.getInputRecords()).isEqualTo(36);
        assertThat(saved.getOutputRecords()).isEqualTo(35);
        assertThat(saved.getRejectedRecords()).isEqualTo(1L);
        assertThat(saved.getArgumentsJson()).contains("--jobCode=odl")
                .contains("--businessDate=20260901").contains("--attemptNo=1");
        verify(runMapper).updateById(saved);
    }

    @Test
    void buildDwdSubmitsAllThreeJobs() {
        submitter.program("SUBMITTED", successLog("bdw", 10, 9, 1));

        List<SparkStageExecutor.JobExecution> results = runStage(12L, "BUILD_DWD");

        assertThat(results).hasSize(3);
        assertThat(submitter.submitCount()).isEqualTo(3);
        assertThat(submitter.submittedCommands().get(0)).contains("--jobCode=bdw");
        assertThat(submitter.submittedCommands().get(1)).contains("--jobCode=dim");
        assertThat(submitter.submittedCommands().get(2)).contains("--jobCode=tdw");
    }

    @Test
    void jobFailureMarksStageFailedWithError() {
        submitter.program("SUBMITTED",
                "{\"jobCode\":\"odl\",\"inputRecords\":10,\"outputRecords\":0,\"rejectedRecords\":0,"
                        + "\"attemptNo\":1,\"status\":\"FAILED\",\"message\":\"schema mismatch\",\"elapsedMs\":500}");

        List<SparkStageExecutor.JobExecution> results = runStage(13L, "LOAD_ODS");

        assertThat(results.get(0).status()).isEqualTo("FAILED");
        assertThat(results.get(0).errorMessage()).contains("schema mismatch");
    }

    @Test
    void exitCodeNonZeroOverridesLogSuccess() {
        // Fake 模拟真实进程：status()=FAILED（退出码非 0）但日志尾行却声称 SUCCESS
        submitter.program("FAILED", successLog("odl", 36, 35, 1));

        List<SparkStageExecutor.JobExecution> results = runStage(14L, "LOAD_ODS");

        SparkStageExecutor.JobExecution r = results.get(0);
        assertThat(r.status()).isEqualTo("FAILED"); // 进程异常终止不冒充成功（§13.2）
        assertThat(r.errorMessage()).contains("进程退出码非0");
    }

    @Test
    void noResultLineTimesOutAndFails() {
        submitter.program("SUBMITTED", "INFO spark running...");

        List<SparkStageExecutor.JobExecution> results = runStage(15L, "LOAD_ODS");

        assertThat(results.get(0).status()).isEqualTo("FAILED");
        assertThat(results.get(0).errorMessage()).contains("未找到 JobResult 结果行");
    }

    @Test
    void cancelledJobFails() {
        submitter.program("CANCELLED", "");

        List<SparkStageExecutor.JobExecution> results = runStage(16L, "LOAD_ODS");

        assertThat(results.get(0).status()).isEqualTo("FAILED");
        assertThat(results.get(0).errorMessage()).contains("取消");
    }

    @Test
    void recordsPersistedWithInputOutputRejected() {
        submitter.program("SUBMITTED", successLog("usw", 200, 8, 0));

        runStage(17L, "BUILD_DWS");

        ArgumentCaptor<SparkJobRun> captor = ArgumentCaptor.forClass(SparkJobRun.class);
        verify(runMapper).insert(captor.capture());
        SparkJobRun saved = captor.getValue();
        assertThat(saved.getOutputRecords()).isEqualTo(8);
        assertThat(saved.getInputRecords()).isEqualTo(200);
        assertThat(saved.getStatus()).isEqualTo(SparkJobRun.STATUS_SUCCESS);
    }

    // ── R6-11 fail-fast：阶段内某作业失败后不再提交剩余作业 ────────────────

    @Test
    void stageFailFastStopsRemainingJobsAfterFirstFailure() {
        // BUILD_DWD = [bdw, dim, tdw]；bdw 成功、dim 失败 → tdw 不得提交
        submitter.programJob("bdw", "SUBMITTED", successLog("bdw", 10, 9, 1));
        submitter.programJob("dim", "SUBMITTED",
                "{\"jobCode\":\"dim\",\"inputRecords\":4,\"outputRecords\":0,\"rejectedRecords\":4,"
                        + "\"attemptNo\":1,\"status\":\"FAILED\",\"message\":\"维度构建失败\",\"elapsedMs\":300}");

        SparkStageExecutor.StageExecution stage =
                executor.executeStage(profile, 21L, "BUILD_DWD", "20260901", 1, Map.of());

        assertThat(stage.failed()).isTrue();
        assertThat(stage.errorMessage()).contains("dim").contains("维度构建失败");
        // 只提交了 bdw + dim，tdw 未提交（fail-fast）
        assertThat(submitter.submitCount()).isEqualTo(2);
        assertThat(submitter.submittedCommands().get(0)).contains("--jobCode=bdw");
        assertThat(submitter.submittedCommands().get(1)).contains("--jobCode=dim");
        assertThat(submitter.submittedCommands()).noneMatch(c -> c.contains("--jobCode=tdw"));
        // 已提交的两个作业都留痕（失败作业也有记录）
        assertThat(stage.jobs()).hasSize(2);
        assertThat(stage.jobs().get(1).success()).isFalse();
    }

    @Test
    void stageExecutionAggregatesRealJobResultCounts() {
        submitter.program("SUBMITTED", successLog("odl", 55, 52, 3));

        SparkStageExecutor.StageExecution stage =
                executor.executeStage(profile, 42L, "LOAD_ODS", "20260901", 3, Map.of());

        // R6-12：阶段计数只能来自 JobResult（真实 input/output/rejected）
        assertThat(stage.failed()).isFalse();
        assertThat(stage.jobs()).hasSize(1);
        assertThat(stage.jobs().get(0).jobCode()).isEqualTo("odl");
        assertThat(stage.totalInputRecords()).isEqualTo(55);
        assertThat(stage.totalOutputRecords()).isEqualTo(52);
        assertThat(stage.totalRejectedRecords()).isEqualTo(3);
        // 真实日志位置与作业号落库（可溯源）
        ArgumentCaptor<SparkJobRun> captor = ArgumentCaptor.forClass(SparkJobRun.class);
        verify(runMapper).updateById(captor.capture());
        assertThat(captor.getValue().getExternalJobId()).startsWith("fake-");
    }
}