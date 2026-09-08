package com.graduation.analytics.pipeline.spark;

import com.graduation.analytics.pipeline.entity.SparkJobRun;
import com.graduation.analytics.pipeline.mapper.SparkJobRunMapper;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.submit.JobSubmitter;
import com.graduation.analytics.runtime.submit.LocalProcessSparkSubmitter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R6-7c 真实 Spark 冒烟（L2，单独运行，不进快速套件）：
 * SparkStageExecutor + 真实 LocalProcessSparkSubmitter → 真实 spark-submit → JobRunner，
 * golden 30 条 odl 装载。验证 §四 边界：提交器真实启动 spark-submit、参数被 JobRunner
 * 正确解析、externalJobId/记录数/状态落库（spark_job_run）、Spark 确实写出目标分区。
 *
 * 运行：mvn -pl warehouse-pipeline -Dtest=SparkStageExecutorSmokeTest test
 * 前置：spark-jobs jar 已构建；本机 D:\Develop\spark-3.5.1-bin-hadoop3。
 */
class SparkStageExecutorSmokeTest {

    // 用仓库内固定目录（非 @TempDir）：失败后分区证据可事后检查
    private static final Path SMOKE_ROOT =
            java.nio.file.Paths.get("tests", "r6-smoke-warehouse").toAbsolutePath();

    @Test
    void realSparkOdlLoadsGoldenDataset() throws Exception {
        Path warehouse = SMOKE_ROOT.resolve("warehouse");
        Path logRoot = SMOKE_ROOT.resolve("logs");
        if (Files.exists(SMOKE_ROOT)) {
            try (var walk = Files.walk(SMOKE_ROOT)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (java.io.IOException ignored) {
                    }
                });
            }
        }
        Files.createDirectories(warehouse);
        Files.createDirectories(logRoot);

        // 档案：LOCAL + 真实 spark-submit（与 runtime_profile 表 LOCAL 档案同构）
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(7L);
        profile.setVersion(3);
        profile.setType(RuntimeProfile.TYPE_LOCAL);
        profile.setSparkMaster("local[2]");
        profile.setSparkSubmitPath("D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd");
        profile.setSparkJobJarUri("file:///D:/Develop_code/GraduationProject/spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar");

        String warehouseUri = "file:///" + warehouse.toString().replace('\\', '/');
        // Derby 元数据库也必须隔离（否则绑定到首次 warehouse 位置、跨 run 残留）。
        // 每次 run 已整目录删除重建，此处路径固定即可保证一致性。
        String derbyUri = SMOKE_ROOT.resolve("derby-metastore").toAbsolutePath()
                .toString().replace('\\', '/');
        Map<String, String> confs = Map.of(
                "spark.sql.warehouse.dir", warehouseUri,
                "spark.sql.session.timeZone", "Asia/Shanghai",
                "spark.hadoop.javax.jdo.option.ConnectionURL",
                "jdbc:derby:" + derbyUri + ";create=true",
                "spark.hadoop.javax.jdo.option.ConnectionDriverName",
                "org.apache.derby.jdbc.EmbeddedDriver",
                "spark.sql.hive.metastore.jars", "builtin",
                "spark.hadoop.datanucleus.schema.autoCreateTables", "true");

        LocalProcessSparkSubmitter submitter = new LocalProcessSparkSubmitter(
                profile.getSparkSubmitPath(), logRoot.toString());

        // 1) 自举：sci 建表（不在阶段映射中，直接裸提交验证真实启动 + JobResult 解析）
        JobSubmitter.SubmitResult sci = submitter.submit(
                JobCommandBuilder.build(profile, "sci", "20260901", 7L, 1,
                        Map.of(), confs),
                "smoke-sci");
        assertThat(sci.externalJobId()).startsWith("lp-");
        JobResultParser.JobResultInfo sciInfo = awaitJobResult(submitter, sci.externalJobId());
        assertThat(sciInfo.success()).as("sci 应 SUCCESS: %s", sciInfo.message()).isTrue();
        // Derby 嵌入式元数据库锁：等 sci 进程完全退出后再启动 odl
        Thread.sleep(2000);

        // 2) LOAD_ODS 阶段经 SparkStageExecutor 真跑 odl（golden 30 条）
        SparkJobRunMapper mapper = mock(SparkJobRunMapper.class);
        AtomicLong idSeq = new AtomicLong(100L);
        when(mapper.insert(any(SparkJobRun.class))).thenAnswer(inv -> {
            SparkJobRun r = inv.getArgument(0);
            r.setId(idSeq.getAndIncrement());
            return 1;
        });
        when(mapper.updateById(any(SparkJobRun.class))).thenReturn(1);
        SparkStageExecutor executor = new SparkStageExecutor(submitter, mapper, 240_000L, 500L);

        List<SparkStageExecutor.JobExecution> results = executor.executeStage(profile, 1L,
                "LOAD_ODS", "20260901", 1,
                Map.of("landingDir", "file:///D:/Develop_code/GraduationProject/tests/golden-dataset/events"),
                confs);

        // 3) 断言阶段结果：odl 唯一作业 SUCCESS
        assertThat(results).hasSize(1);
        SparkStageExecutor.JobExecution odl = results.get(0);
        assertThat(odl.jobCode()).isEqualTo("odl");
        assertThat(odl.externalJobId()).startsWith("lp-");
        assertThat(odl.status()).isEqualTo(SparkJobRun.STATUS_SUCCESS);
        assertThat(odl.inputRecords()).isEqualTo(30L);
        assertThat(odl.outputRecords()).isGreaterThan(0);
        assertThat(odl.errorMessage()).isNull();

        // 4) spark_job_run 落库：insert(SUBMITTED) + updateById(SUCCESS)，记录数一致
        // 注意：executor 内 insert 后复用同一实体更新，captor 捕获到同一引用（终态 SUCCESS）；
        // insert 快照的 SUBMITTED 已由 R6-6 快速测试覆盖，这里验证同一实体两阶段落库 + 终态。
        ArgumentCaptor<SparkJobRun> captor = ArgumentCaptor.forClass(SparkJobRun.class);
        verify(mapper).insert(captor.capture());
        verify(mapper).updateById(captor.capture());
        List<SparkJobRun> captured = captor.getAllValues();
        assertThat(captured).hasSize(2);
        SparkJobRun updated = captured.get(1);
        assertThat(updated.getExternalJobId()).isEqualTo(odl.externalJobId());
        assertThat(updated.getJobCode()).isEqualTo("odl");
        assertThat(updated.getStageCode()).isEqualTo("LOAD_ODS");
        assertThat(updated.getPipelineRunId()).isEqualTo(1L);
        assertThat(updated.getRuntimeProfileId()).isEqualTo(7L);
        assertThat(updated.getStatus()).isEqualTo(SparkJobRun.STATUS_SUCCESS);
        assertThat(updated.getInputRecords()).isEqualTo(30L);
        assertThat(updated.getOutputRecords()).isEqualTo(odl.outputRecords());
        assertThat(updated.getRejectedRecords()).isEqualTo(odl.rejectedRecords());
        assertThat(updated.getArgumentsJson()).contains("--jobCode=odl").contains("--businessDate=20260901");
        assertThat(updated.getFinishedAt()).isNotNull();

        // 5) Spark 确实写出目标分区：warehouse 下出现 ODS 分区目录（dt=20260901）
        System.out.println("[smoke] warehouse=" + warehouse);
        assertThat(partitionExists(warehouse, "dw_ods", "ods_behavior_event", "20260901"))
                .as("ODS 行为表分区应写出").isTrue();
    }

    /** 轮询真实提交器日志直到 JobResult 行出现（与 SparkStageExecutor 一致语义） */
    private JobResultParser.JobResultInfo awaitJobResult(LocalProcessSparkSubmitter submitter,
                                                         String externalJobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 240_000L;
        JobResultParser.JobResultInfo info = null;
        while (System.currentTimeMillis() < deadline) {
            Optional<JobResultParser.JobResultInfo> parsed =
                    JobResultParser.parseLog(submitter.logs(externalJobId));
            if (parsed.isPresent()) {
                info = parsed.get();
                if ("SUCCESS".equals(info.status()) || "FAILED".equals(info.status())) {
                    return info;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("真实 spark 作业超时未产出 JobResult，externalJobId=" + externalJobId);
    }

    private boolean partitionExists(Path warehouse, String db, String table, String dt) {
        // 直接检查分区目录（dt=...），walker 异常场景更容易误判
        Path partition = warehouse.resolve(db + ".db").resolve(table).resolve("dt=" + dt);
        if (!Files.isDirectory(partition)) {
            System.out.println("[smoke] partition dir missing: " + partition);
            return false;
        }
        try (var walk = Files.walk(partition)) {
            return walk.anyMatch(p -> p.getFileName().toString().startsWith("part-"));
        } catch (Exception e) {
            System.out.println("[smoke] partition walk error: " + e.getMessage());
            return false;
        }
    }
}