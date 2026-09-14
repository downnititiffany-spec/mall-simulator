package com.graduation.analytics.pipeline.spark;

import com.graduation.analytics.pipeline.entity.SparkJobRun;
import com.graduation.analytics.pipeline.mapper.SparkJobRunMapper;
import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
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
 * golden 55 行（R6-8b 扩充：52 接受 / 3 rejected：坏 JSON、schema_version=2.0、缺 event_id）。
 * 验证 §四 边界：提交器真实启动 spark-submit、参数被 JobRunner
 * 正确解析、externalJobId/记录数/状态落库（spark_job_run）、Spark 确实写出目标分区。
 *
 * <h3>V25-S03 R-5 整改：加门禁 + 只删本次 runId 拥有的目录</h3>
 *
 * <p><b>整改前</b>：工作根是仓库内<b>固定常量</b>
 * {@code <repo>/tests/r6-smoke-warehouse}，入口处直接
 * {@code Files.walk(SMOKE_ROOT).sorted(reverseOrder()).forEach(Files::deleteIfExists)}
 * ——无门禁、无预览、无目标校验。只要有人把常量改成 {@code tests} 或 {@code .}，
 * 这段递归删除就会清掉整个目录树；而且直接 {@code mvn test} 就会启动真实
 * {@code spark-submit} 外部进程。</p>
 *
 * <p><b>整改后</b>：</p>
 * <ul>
 *   <li>默认关闭：必须 {@code -Dv25.spark.it=true}；未开启则**显式失败**（不是 skip）；</li>
 *   <li>允许根白名单：默认 {@code <repo>/target/v25-spark-it}，且拒绝仓库根、
 *       仓库根一级子目录、集群 {@code /graduation/**}；</li>
 *   <li>工作目录 {@code <允许根>/<testRunId>/}，删除**只**作用于该目录；</li>
 *   <li>删除前打印待删清单（相对路径 + 数量），再执行删除。</li>
 * </ul>
 *
 * <p><b>类名由 {@code SparkStageExecutorSmokeTest} 改为 {@code SparkStageExecutorSmokeIT}
 * （总控 2026-09-14 裁决）</b>：surefire 默认 includes 只含 {@code *Test}/{@code Test*}/{@code *Tests}/
 * {@code *TestCase}，因此改名后本类**不再进入 {@code mvn test} 默认套件**——这既是 R-5「默认关闭」的
 * 落地方式，也与仓库既有 {@code *MySqlIT} 一族同一约定。**不得**为了让它默认跑而改回 {@code *Test}：
 * 那会让每次默认构建都启动真实 {@code spark-submit} 外部进程（历史上还因内存不足崩过，见 DEF-15），
 * 并让默认反应堆因「未开开关」而永久 1 error。</p>
 *
 * <p>运行（**必须显式给出开关与 runId**）：{@code mvn -pl warehouse-pipeline
 * -Dtest=SparkStageExecutorSmokeIT -Dv25.spark.it=true -Dv25.it.testRunId=<runId> test}
 * 前置：spark-jobs jar 已构建；本机 {@code D:\Develop\spark-3.5.1-bin-hadoop3}。
 * 未给开关时本类会**显式报错拒绝**（{@code MissingConfigurationException}），不是 skip。</p>
 *
 * <p>凡触库/触 HDFS 的部分等 W03 交付隔离实例；本轮只把门禁与"拒跑"做实。</p>
 */
class SparkStageExecutorSmokeIT {

    // V25-S03 R-5：工作根不再用仓库内固定常量，改由 SparkItGuard 按允许根 + testRunId 解析。
    private static Path smokeRoot() {
        return com.graduation.analytics.testsupport.SparkItGuard.runRoot();
    }

    @Test
    void realSparkOdlLoadsGoldenDataset() throws Exception {
        // 门禁：默认关闭。未显式开启就红，不提供 skip 形态的通过。
        com.graduation.analytics.testsupport.SparkItGuard.requireEnabled(
                "SparkStageExecutorSmokeIT.realSparkOdlLoadsGoldenDataset");
        // 清理：先列预览、只删本次 runId 拥有的目录
        com.graduation.analytics.testsupport.SparkItGuard.deleteOwnedRunDir();

        Path SMOKE_ROOT = smokeRoot();
        Path warehouse = SMOKE_ROOT.resolve("warehouse");
        Path logRoot = SMOKE_ROOT.resolve("logs");
        Files.createDirectories(warehouse);
        Files.createDirectories(logRoot);

        // 档案：LOCAL + 真实 spark-submit（与 runtime_profile 表 LOCAL 档案同构）
        RuntimeProfile profileEntity = new RuntimeProfile();
        profileEntity.setId(7L);
        profileEntity.setSourceId(1L); // P2-07：库名按本次运行的源解析（与真库 seed 源同 id）
        profileEntity.setVersion(3);
        profileEntity.setType(RuntimeProfile.TYPE_LOCAL);
        profileEntity.setSparkMaster("local[2]");
        profileEntity.setSparkSubmitPath("D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd");
        profileEntity.setSparkJobJarUri("file:///D:/Develop_code/GraduationProject/spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar");

        RuntimeProfileSnapshot profile = RuntimeProfileSnapshot.from(profileEntity);

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
                profile.sparkSubmitPath(), logRoot.toString());

        // P2-07/A12：源身份由源解析（真库 seed 源 source_code='mock-mall'、warehouse_prefix='dw'，
        // 见 V16 种子行 + V18 回填），此处显式构造同值。
        com.graduation.analytics.warehouse.RunSourceIdentity source =
                new com.graduation.analytics.warehouse.RunSourceIdentity("mock-mall",
                        com.graduation.analytics.warehouse.WarehouseNamespace.of("dw"));

        // 1) 自举：sci 建表（不在阶段映射中，直接裸提交验证真实启动 + JobResult 解析）
        JobSubmitter.SubmitResult sci = submitter.submit(
                JobCommandBuilder.build(profile, source, "sci", "20260901", 7L, 1,
                        Map.of(), confs),
                "smoke-sci");
        assertThat(sci.externalJobId()).startsWith("lp-");
        JobResultParser.JobResultInfo sciInfo = awaitJobResult(submitter, sci.externalJobId());
        assertThat(sciInfo.success()).as("sci 应 SUCCESS: %s", sciInfo.message()).isTrue();
        // Derby 嵌入式元数据库锁：等 sci 进程完全退出后再启动 odl
        Thread.sleep(2000);

        // 2) LOAD_ODS 阶段经 SparkStageExecutor 真跑 odl（golden 55 行：52 接受 / 3 拒绝）
        SparkJobRunMapper mapper = mock(SparkJobRunMapper.class);
        AtomicLong idSeq = new AtomicLong(100L);
        when(mapper.insert(any(SparkJobRun.class))).thenAnswer(inv -> {
            SparkJobRun r = inv.getArgument(0);
            r.setId(idSeq.getAndIncrement());
            return 1;
        });
        when(mapper.updateById(any(SparkJobRun.class))).thenReturn(1);
        SparkStageExecutor executor = new SparkStageExecutor(submitter, mapper, 240_000L, 500L, source);

        List<SparkStageExecutor.JobExecution> results = executor.executeStage(profile, 1L,
                "LOAD_ODS", "20260901", 1,
                Map.of("landingDir", "file:///D:/Develop_code/GraduationProject/tests/golden-dataset/events"),
                confs).jobs();

        // 3) 断言阶段结果：odl 唯一作业 SUCCESS
        assertThat(results).hasSize(1);
        SparkStageExecutor.JobExecution odl = results.get(0);
        assertThat(odl.jobCode()).isEqualTo("odl");
        assertThat(odl.externalJobId()).startsWith("lp-");
        assertThat(odl.status()).isEqualTo(SparkJobRun.STATUS_SUCCESS);
        assertThat(odl.inputRecords()).isEqualTo(55L);
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
        assertThat(updated.getInputRecords()).isEqualTo(55L);
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