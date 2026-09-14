package com.graduation.analytics.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import com.graduation.analytics.pipeline.entity.PipelineRun;
import com.graduation.analytics.pipeline.entity.PipelineStageRun;
import com.graduation.analytics.pipeline.mapper.DataQualityResultMapper;
import com.graduation.analytics.pipeline.mapper.PipelineRunMapper;
import com.graduation.analytics.pipeline.mapper.PipelineStageRunMapper;
import com.graduation.analytics.pipeline.spark.JobResultParser;
import com.graduation.analytics.pipeline.spark.SparkStageExecutor;
import com.graduation.analytics.pipeline.spark.SparkStageExecutorFactory;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R6 PipelineService 快速测试（不启动 Spark/MySQL，几十秒内完成）：
 * ①POST 立即返回 taskId+PENDING 不阻塞（§13.1）
 * ②同幂等键返回原任务（§13.4）  ③WAIT_LANDING 成功→LOAD_ODS（阶段顺序）
 * ④阶段失败→FAILED  ⑤重试跳过成功阶段（§13.4 恢复）
 * ⑥质量失败不得进发布（§5.4.1）  ⑦attemptNo 递增  ⑧并发同键只产生一个任务
 *
 * R6-11（V2.0 §15.3）：四个计算阶段改为真实 Spark 执行器（注入 Fake 工厂做 L1 编排验证）：
 * ⑨阶段失败 fail-fast（后续依赖阶段不执行）  ⑩阶段计数来自 JobResult（非 Java 估算）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineServiceTest {

    @TempDir
    Path landing;

    @Mock PipelineRunMapper runMapper;
    @Mock PipelineStageRunMapper stageMapper;
    @Mock DataQualityResultMapper qualityMapper;
    @Mock QualityChecker qualityChecker;
    @Mock RuntimeProfileService runtimeProfileService;
    @Mock SparkStageExecutorFactory stageExecutorFactory;
    @Mock SparkStageExecutor stageExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventClock eventClock = new EventClock(Clock.fixed(
            java.time.Instant.parse("2026-09-01T10:00:00Z"), ZoneId.of("Asia/Shanghai")));

    /** 收集型 Executor：run() 只投递不执行 → 手动 drain 触发异步链（验证"立即返回"） */
    private final CollectingExecutor executor = new CollectingExecutor();

    /** 内存映射 store：模拟单键幂等查询与单 run 阶段列表 */
    private final AtomicReference<PipelineRun> insertedRun = new AtomicReference<>();
    private final List<PipelineStageRun> stages = new ArrayList<>();
    private final AtomicLong stageId = new AtomicLong(1);
    private final AtomicLong runId = new AtomicLong(1);

    private PipelineService service;

    @BeforeEach
    void setUp() throws IOException {
        PipelineRun repoRun = new PipelineRun();
        doAnswer(inv -> {
            PipelineRun r = inv.getArgument(0);
            r.setId(runId.get());
            insertedRun.set(r);
            return 1;
        }).when(runMapper).insert(any(PipelineRun.class));
        when(runMapper.selectById(anyLong())).thenAnswer(inv -> insertedRun.get());
        when(runMapper.selectOne(any())).thenAnswer(inv -> insertedRun.get());
        when(runMapper.selectList(any())).thenAnswer(inv ->
                insertedRun.get() == null ? List.of() : List.of(insertedRun.get()));

        doAnswer(inv -> {
            PipelineStageRun s = inv.getArgument(0);
            s.setId(stageId.getAndIncrement());
            stages.add(s);
            return 1;
        }).when(stageMapper).insert(any(PipelineStageRun.class));
        // 模拟真实库：completedStages 查询只返回 SUCCESS 阶段（execute() 用 status 过滤）
        when(stageMapper.selectList(any())).thenAnswer(inv -> stages.stream()
                .filter(s -> PipelineStageRun.STATUS_SUCCESS.equals(s.getStatus())).collect(java.util.stream.Collectors.toList()));
        // latestStage：顺序执行时列表最后一条即当前阶段
        when(stageMapper.selectOne(any())).thenAnswer(inv ->
                stages.isEmpty() ? null : stages.get(stages.size() - 1));

        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setVersion(7);
        profile.setType(RuntimeProfile.TYPE_LOCAL);
        profile.setLandingUri(landing.toAbsolutePath().toString());
        profile.setSparkSubmitPath("D:\\spark\\bin\\spark-submit.cmd");
        profile.setSparkJobJarUri("spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar");
        when(runtimeProfileService.get(1L)).thenReturn(profile);

        // R6-11：真实 Spark 阶段由执行器工厂产出；L1 注入 Fake 执行器（不启动 Spark），
        // 默认每个阶段全部作业 SUCCESS，计数取 jobCode 对应的固定 JobResult 值。
        when(stageExecutorFactory.create(any())).thenReturn(stageExecutor);
        when(stageExecutor.executeStage(any(), anyLong(), anyString(), anyString(), anyInt(), any(), any()))
                .thenAnswer(inv -> successExecution(inv.getArgument(2)));

        // 质量检查默认通过（个别测试覆盖为失败）；DEF-04 后生产路径走三参重载（带整批订单总额索引）
        when(qualityChecker.check(any(), anyLong(), any()))
                .thenReturn(new QualityChecker.QualitySummary(List.of(mock(DataQualityResult.class)), true));

        service = new PipelineService(runMapper, stageMapper, qualityMapper, qualityChecker,
                eventClock, objectMapper, runtimeProfileService, stageExecutorFactory, executor,
                metricPublisherPort(), metricDefinitionMapper());
    }

    /**
     * R7-3：L1 用假发布端口（不连指标库）——本类只验证编排与状态机；
     * ADS→MySQL 的真实写入/对账/ACTIVE 切换由 metric-analysis 的单测 + 真实小链（run 21）覆盖。
     */
    private static com.graduation.analytics.metric.publish.MetricPublisherPort metricPublisherPort() {
        return request -> new com.graduation.analytics.metric.publish.MetricPublisherPort.PublishReport(
                true, null, "L1 stub", request.snapshotId(), 22L, 10, List.of(),
                Map.of("stub", true));
    }

    /** R7-3：字典 mapper 用 Mockito 假实现（返回空字典；L1 不校验指标码） */
    private static com.graduation.analytics.metric.dict.MetricDefinitionMapper metricDefinitionMapper() {
        return mock(com.graduation.analytics.metric.dict.MetricDefinitionMapper.class);
    }

    /** 构造"全作业 SUCCESS"的阶段执行结果（计数模拟 JobResult 真实输出） */
    private static SparkStageExecutor.StageExecution successExecution(String stageCode) {
        List<SparkStageExecutor.JobExecution> jobs = new ArrayList<>();
        for (String jobCode : SparkStageExecutor.stageJobs(stageCode)) {
            jobs.add(new SparkStageExecutor.JobExecution(
                    "lp-test-" + jobCode, jobCode, com.graduation.analytics.pipeline.entity.SparkJobRun.STATUS_SUCCESS,
                    10L, 8L, 1L, "landing/logs/test__lp-test-" + jobCode + ".log", null,
                    List.of(partition("dw_ads.ads_operation_overview", "dt=20260901")),
                    checksFor(stageCode)));
        }
        return new SparkStageExecutor.StageExecution(stageCode, jobs, false, null);
    }

    /** R6-13：dqc 通过时的质量检查结果（BLOCKING 全通过 + 1 条 INFO 审计项） */
    private static List<JobResultParser.CheckInfo> checksFor(String stageCode) {
        if (!"QUALITY_CHECK".equals(stageCode)) {
            return List.of();
        }
        return List.of(
                new JobResultParser.CheckInfo("ADS_STAGING_PRESENT", "ADS_STAGING",
                        "dw_ads.ads_operation_overview__staging", 8L, 0L, "每表行数>0",
                        "BLOCKING", true, "8 张暂存表行数均>0"),
                new JobResultParser.CheckInfo("PUB_STAGING_PRUNE", "PUBLISH", "staging", 0L, 0L,
                        "保留被引用快照", "INFO", true, "无待清理历史暂存分区"));
    }

    /** R6-13：dqc 阻断（BLOCKING 未通过）时的执行结果 */
    private static SparkStageExecutor.StageExecution qualityBlockedExecution(String stageCode) {
        List<JobResultParser.CheckInfo> checks = List.of(
                new JobResultParser.CheckInfo("ADS_STAGING_PRESENT", "ADS_STAGING",
                        "dw_ads.ads_hot_product__staging", 8L, 1L, "每表行数>0",
                        "BLOCKING", false, "空/缺失暂存表: dw_ads.ads_hot_product__staging"),
                new JobResultParser.CheckInfo("PUB_DQ_EVENT_ID_UNIQUE", "PUBLISH",
                        "dw_ads.ads_data_quality__staging", 51L, 3L, "0.0005", "ERROR", false,
                        "观察项"));
        List<SparkStageExecutor.JobExecution> jobs = List.of(new SparkStageExecutor.JobExecution(
                "lp-block-dqc", "dqc", com.graduation.analytics.pipeline.entity.SparkJobRun.STATUS_FAILED,
                40L, 0L, 1L, "landing/logs/test__lp-block-dqc.log",
                "质量门阻断发布: ADS_STAGING_PRESENT", List.of(), checks));
        return new SparkStageExecutor.StageExecution(stageCode, jobs, true,
                "dqc: 质量门阻断发布: ADS_STAGING_PRESENT");
    }

    /** R6-12：分区证据样例（表/dt/快照/行数/路径） */
    private static JobResultParser.OutputPartitionInfo partition(String table, String dt) {
        return new JobResultParser.OutputPartitionInfo(table, dt, "S20260901_1", 8L,
                "file:/tmp/warehouse/" + table.replace('.', '/') + "/" + dt);
    }

    /** 构造"某阶段首个作业 FAILED"的执行结果（fail-fast 场景） */
    private static SparkStageExecutor.StageExecution failedExecution(String stageCode, String jobCode) {
        List<SparkStageExecutor.JobExecution> jobs = List.of(new SparkStageExecutor.JobExecution(
                "lp-fail-" + jobCode, jobCode, com.graduation.analytics.pipeline.entity.SparkJobRun.STATUS_FAILED,
                5L, 0L, 5L, "landing/logs/test__lp-fail-" + jobCode + ".log", "作业失败",
                List.of(), List.of()));
        return new SparkStageExecutor.StageExecution(stageCode, jobs, true, jobCode + ": 作业失败");
    }

    // ── ①接口异步：POST 立即返回 taskId+PENDING，不阻塞（§13.1） ─────────

    @Test
    void submitReturnsPendingTaskIdImmediatelyWithoutBlocking() throws Exception {
        writeLanding("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-1", "trace-1");

        // POST 立即返回：PENDING + 有 taskId，且计算链尚未执行（仅投递）
        assertThat(r.runId()).isNotNull();
        assertThat(r.status()).isEqualTo(PipelineRun.STATUS_PENDING);
        assertThat(executor.pending()).isEqualTo(1);

        executor.drain();
        PipelineService.RunResult done = service.get(r.runId());
        assertThat(done.status()).isEqualTo(PipelineRun.STATUS_SUCCESS);
    }

    // ── ②幂等：同键返回原任务，不重复执行（§13.4） ───────────────────────

    @Test
    void sameIdempotencyKeyReturnsOriginalTask() throws Exception {
        writeLanding("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));

        PipelineService.RunResult first = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "same-key", "trace-1");
        executor.drain();
        int updatesBeforeSecond = updateByIdCount();

        PipelineService.RunResult second = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "same-key", "trace-2");

        assertThat(second.runId()).isEqualTo(first.runId());
        // 第二次命中未新增 updateById（第一次执行链的更新已完成，第二次未改状态）
        verify(runMapper, org.mockito.Mockito.times(updatesBeforeSecond)).updateById(any(PipelineRun.class));
        assertThat(second.status()).isEqualTo(PipelineRun.STATUS_SUCCESS);
    }

    // ── ③阶段顺序：WAIT_LANDING 成功 → LOAD_ODS（§13.2 七阶段链） ─────────

    @Test
    void waitLandingSuccessThenLoadOdsInStageOrder() throws Exception {
        writeLanding("accepted/2026-09-01",
                event("e1", "behavior", "2026-09-01T10:00:00", "{\"user_id\":\"u1\",\"product_id\":\"p1\"}"));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-order", "trace-1");
        executor.drain();

        List<PipelineStageRun> byCode = stageList();
        assertThat(stageStatus("WAIT_LANDING")).isEqualTo(PipelineStageRun.STATUS_SUCCESS);
        assertThat(stageStatus("LOAD_ODS")).isEqualTo(PipelineStageRun.STATUS_SUCCESS);
        assertThat(stageStatus("BUILD_DWD")).isEqualTo(PipelineStageRun.STATUS_SUCCESS);
        assertThat(stageStatus("BUILD_DWS")).isEqualTo(PipelineStageRun.STATUS_SUCCESS);
        assertThat(stageStatus("BUILD_ADS")).isEqualTo(PipelineStageRun.STATUS_SUCCESS);
        assertThat(stageStatus("QUALITY_CHECK")).isEqualTo(PipelineStageRun.STATUS_SUCCESS);
        assertThat(stageStatus("PUBLISH_METRIC")).isEqualTo(PipelineStageRun.STATUS_SUCCESS);
        // 顺序：WAIT_LANDING 在 LOAD_ODS 之前
        assertThat(byCode.indexOf(stageOf("WAIT_LANDING"))).isLessThan(byCode.indexOf(stageOf("LOAD_ODS")));
    }

    // ── ④阶段失败 → run FAILED（§13.2 失败留痕） ───────────────────────────

    @Test
    void stageFailureMarksRunFailed() throws Exception {
        // accepted 目录存在但无业务日事件 → LOAD_ODS 抛 RUN_EMPTY_DATA
        writeLanding("accepted/2026-09-01"); // 无事件文件

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-fail", "trace-1");
        executor.drain();

        PipelineService.RunResult failed = service.get(r.runId());
        assertThat(failed.status()).isEqualTo(PipelineRun.STATUS_FAILED);
        assertThat(failed.errorCode()).isEqualTo("RUN_EMPTY_DATA");
        assertThat(stageStatus("LOAD_ODS")).isEqualTo(PipelineStageRun.STATUS_FAILED);
        // 自举阶段（INIT_SCHEMA）已执行；LOAD_ODS 预检失败不提交 odl，后续依赖阶段不执行、未发布
        assertThat(stageStatus("INIT_SCHEMA")).isEqualTo(PipelineStageRun.STATUS_SUCCESS);
        verify(stageExecutor, org.mockito.Mockito.times(1))
                .executeStage(any(), anyLong(), org.mockito.ArgumentMatchers.eq("INIT_SCHEMA"),
                        anyString(), anyInt(), any(), any());
        assertThat(stageOf("BUILD_DWD")).isNull();
        assertThat(stageOf("PUBLISH_METRIC")).isNull();
    }

    // ── ⑤重试跳过成功阶段：成功阶段不重复执行，失败阶段重跑（§13.4 恢复） ─

    @Test
    void retryReexecutesOnlyFailedStage() throws Exception {
        // 第一轮：有 manifest，但 accepted 无事件 → LOAD_ODS 失败
        writeLanding("accepted/2026-09-01");
        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-retry", "trace-1");
        executor.drain();
        assertThat(service.get(r.runId()).status()).isEqualTo(PipelineRun.STATUS_FAILED);
        long waitLandingInsertsBefore = stages.stream()
                .filter(s -> "WAIT_LANDING".equals(s.getStageCode())).count();
        assertThat(waitLandingInsertsBefore).isEqualTo(1);

        // 补齐数据后重试
        writeEvents("accepted/2026-09-01",
                event("e2", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o2\",\"total_amount\":\"200\"}"));
        service.retry(r.runId(), "trace-2");
        executor.drain();

        PipelineService.RunResult done = service.get(r.runId());
        assertThat(done.status()).isEqualTo(PipelineRun.STATUS_SUCCESS);
        assertThat(done.attemptNo()).isEqualTo(2);
        // 成功阶段 WAIT_LANDING 仍只插入 1 次（重试跳过）；失败阶段 LOAD_ODS 重跑（2 次）
        long waitLandingInsertsAfter = stages.stream()
                .filter(s -> "WAIT_LANDING".equals(s.getStageCode())).count();
        long loadOdsInserts = stages.stream()
                .filter(s -> "LOAD_ODS".equals(s.getStageCode())).count();
        assertThat(waitLandingInsertsAfter).isEqualTo(1);
        assertThat(loadOdsInserts).isEqualTo(2);
    }

    // ── ⑥质量失败不得进发布（§5.4.1 阻断） ────────────────────────────────

    @Test
    void qualityFailureBlocksPublish() throws Exception {
        writeLanding("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"),
                event("e2", "order_paid", "2026-09-01T10:05:00", "{\"order_id\":\"o1\",\"amount\":\"99\"}"));
        when(qualityChecker.check(any(), anyLong(), any()))
                .thenReturn(new QualityChecker.QualitySummary(List.of(mock(DataQualityResult.class)), false));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-quality", "trace-1");
        executor.drain();

        PipelineService.RunResult failed = service.get(r.runId());
        assertThat(failed.status()).isEqualTo(PipelineRun.STATUS_FAILED);
        assertThat(failed.errorCode()).isEqualTo("PIPELINE_QUALITY_FAILED");
        assertThat(stageStatus("QUALITY_CHECK")).isEqualTo(PipelineStageRun.STATUS_FAILED);
        // 不发布：无 PUBLISH_METRIC 阶段（BUILD_ADS 已真实执行，但快照登记被质量门阻断）
        assertThat(stageOf("PUBLISH_METRIC")).isNull();
    }

    // ── ⑥b R6-13：ADS 暂存质量门阻断 → 不发布正式分区（§14.4/§16.3） ──────

    @Test
    void adsQualityGateFailureBlocksFormalPartitionPublish() throws Exception {
        writeLanding("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));
        // Landing 层内联规则通过，但 dqc（ADS 暂存层 BLOCKING 规则）未过
        when(stageExecutor.executeStage(any(), anyLong(), org.mockito.ArgumentMatchers.eq("QUALITY_CHECK"),
                anyString(), anyInt(), any(), any()))
                .thenAnswer(inv -> qualityBlockedExecution("QUALITY_CHECK"));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-gate", "trace-1");
        executor.drain();

        PipelineService.RunResult failed = service.get(r.runId());
        assertThat(failed.status()).isEqualTo(PipelineRun.STATUS_FAILED);
        assertThat(failed.errorCode()).isEqualTo("PIPELINE_QUALITY_FAILED");
        assertThat(stageStatus("QUALITY_CHECK")).isEqualTo(PipelineStageRun.STATUS_FAILED);
        // 不发布：PUBLISH_METRIC 阶段根本未创建（正式分区未被指针切换）
        assertThat(stageOf("PUBLISH_METRIC")).isNull();
        // 失败也要留下检查证据：BLOCKING 未过的规则写库（运维页可见失败原因）
        org.mockito.ArgumentCaptor<DataQualityResult> captor =
                org.mockito.ArgumentCaptor.forClass(DataQualityResult.class);
        verify(qualityMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        assertThat(captor.getAllValues())
                .anySatisfy(q -> {
                    assertThat(q.getRuleCode()).isEqualTo("ADS_STAGING_PRESENT");
                    assertThat(q.getSeverity()).isEqualTo("BLOCKING");
                    assertThat(q.getLayer()).isEqualTo("ADS_STAGING");
                    assertThat(q.getPassed()).isZero();
                    assertThat(q.getSnapshotId()).startsWith("S20260901_");
                });
    }

    // ── ⑦重试 attempt_no 递增（§23.1 恢复） ────────────────────────────────

    @Test
    void retryIncrementsAttemptNo() throws Exception {
        writeLanding("accepted/2026-09-01"); // 无事件 → 第一轮失败
        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-attempt", "trace-1");
        executor.drain();
        assertThat(service.get(r.runId()).attemptNo()).isEqualTo(1);

        writeEvents("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));
        service.retry(r.runId(), "trace-2");

        assertThat(insertedRun.get().getAttemptNo()).isEqualTo(2);
    }

    // ── ⑧并发同幂等键只产生一个任务（应用锁 + DB 唯一键兜底，§13.4） ─────

    @Test
    void concurrentSameKeyCreatesSingleRun() throws Exception {
        writeLanding("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Long> runIds = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> statuses = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable task = () -> {
            try {
                start.await();
                PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                        LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-concurrent", "trace-c");
                runIds.add(r.runId());
                statuses.add(r.status());
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        };
        new Thread(task).start();
        new Thread(task).start();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        // 两个调用返回同一 run（第一个插入，第二个幂等命中返回原任务）
        assertThat(runIds).hasSize(2);
        assertThat(runIds.get(0)).isEqualTo(runIds.get(1));
        assertThat(statuses).allSatisfy(s -> assertThat(s).isIn(
                PipelineRun.STATUS_PENDING, PipelineRun.STATUS_SUCCESS));
        verify(runMapper, org.mockito.Mockito.times(1)).insert(any(PipelineRun.class));
    }

    // ── ⑨R6-11 fail-fast：计算阶段作业失败 → run FAILED，后续依赖阶段不执行 ──

    @Test
    void sparkStageFailureFailsFastWithoutRunningDependentStages() throws Exception {
        writeLanding("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));
        // BUILD_DWD 的 bdw 作业失败 → BUILD_DWS/BUILD_ADS/PUBLISH 都不得执行
        when(stageExecutor.executeStage(any(), anyLong(), anyString(), anyString(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    String stage = inv.getArgument(2);
                    if ("BUILD_DWD".equals(stage)) {
                        return failedExecution(stage, "bdw");
                    }
                    return successExecution(stage);
                });

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-fail-fast", "trace-1");
        executor.drain();

        PipelineService.RunResult failed = service.get(r.runId());
        assertThat(failed.status()).isEqualTo(PipelineRun.STATUS_FAILED);
        assertThat(failed.errorCode()).isEqualTo("RUN_JOB_FAILED");
        assertThat(stageStatus("INIT_SCHEMA")).isEqualTo(PipelineStageRun.STATUS_SUCCESS);
        assertThat(stageStatus("BUILD_DWD")).isEqualTo(PipelineStageRun.STATUS_FAILED);
        // 依赖阶段保持未执行（无阶段记录），PUBLISH 更不得发生
        assertThat(stageOf("BUILD_DWS")).isNull();
        assertThat(stageOf("BUILD_ADS")).isNull();
        assertThat(stageOf("PUBLISH_METRIC")).isNull();
        // 只提交到 BUILD_DWD 为止（INIT_SCHEMA + LOAD_ODS + BUILD_DWD = 3 次）
        verify(stageExecutor, org.mockito.Mockito.times(3))
                .executeStage(any(), anyLong(), anyString(), anyString(), anyInt(), any(), any());
    }

    // ── ⑩R6-11/R6-12 计数取 JobResult：阶段 records = 作业输出行数合计 ──────

    @Test
    void stageRecordsComeFromJobResultNotJavaEstimate() throws Exception {
        writeLanding("accepted/2026-09-01",
                event("e1", "behavior", "2026-09-01T10:00:00", "{\"user_id\":\"u1\",\"product_id\":\"p1\"}"),
                event("e2", "behavior", "2026-09-01T10:01:00", "{\"user_id\":\"u2\",\"product_id\":\"p2\"}"));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-records", "trace-1");
        executor.drain();

        assertThat(service.get(r.runId()).status()).isEqualTo(PipelineRun.STATUS_SUCCESS);
        // 每个 Spark 阶段 records = 该阶段所有作业 outputRecords 之和（Fake: 每作业 8 行）
        PipelineStageRun dwd = stageOf("BUILD_DWD");
        assertThat(dwd.getRecords()).isEqualTo(8L * SparkStageExecutor.stageJobs("BUILD_DWD").size());
        assertThat(stageOf("BUILD_DWS").getRecords()).isEqualTo(8L);
        assertThat(stageOf("BUILD_ADS").getRecords()).isEqualTo(8L);
        // 证据含真实 externalJobId / logUri（§15.3 R6-12 可溯源）
        assertThat(dwd.getEvidence()).contains("lp-test-bdw").contains("landing/logs/test__lp-test-bdw.log");
    }

    /**
     * 2026-09-11 真机事故回归：BUILD_ADS 证据超长时**不得**截断成非法 JSON。
     *
     * <p>原实现按字符数 {@code substring} 截断，落库的是半截 JSON；重试/恢复路径
     * {@code stageEvidence()} 解析失败后静默退化成空 Map → PUBLISH_METRIC 报
     * 「缺少 BUILD_ADS 真实作业证据，拒绝发布」（实测 pipeline run 22）。</p>
     */
    @Test
    void 阶段证据超长时仍保持JSON合法() throws Exception {
        // 构造真实体量的 BUILD_ADS 证据：逐作业 × 逐输出分区
        Map<String, Object> evidence = new LinkedHashMap<>();
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (String jobCode : List.of("fna", "dqc", "pub", "mxp")) {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("jobCode", jobCode);
            job.put("externalJobId", "lp-1789043509617-310b7b");
            job.put("status", "SUCCESS");
            job.put("inputRecords", 55);
            job.put("outputRecords", 22);
            List<Map<String, Object>> partitions = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                partitions.add(Map.of("table", "dw_ads.ads_hot_product__staging" + i, "dt", "20260901",
                        "snapshotId", "S20260901_24", "rowCount", 8,
                        "path", "file:/D:/Develop_code/GraduationProject/spark-warehouse/dw_ads.db/ads_hot_product__staging"
                                + i + "/dt=20260901/snapshot_id=S20260901_24/part-00000-abc.json"));
            }
            job.put("outputPartitions", partitions);
            jobs.add(job);
        }
        evidence.put("jobs", jobs);
        evidence.put("adsSnapshotId", "S20260901_24");

        String json = service.evidenceJson(evidence);
        // ① 无论长短都必须是合法 JSON（可被 ObjectMapper 反序列化）
        Map<String, Object> parsed = objectMapper.readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        assertThat(parsed).isNotEmpty();
        // ② 本项目量级（约 6 万字符）不触发缩减：证据必须完整保留
        assertThat(json.length()).isLessThan(PipelineService.EVIDENCE_MAX_CHARS);
        assertThat(parsed).doesNotContainKey("_evidenceTruncated");
        assertThat((List<?>) parsed.get("jobs")).hasSize(4);

        // ③ 人为超限（把上限当作「列宽」模拟）时，缩减结果仍必须是合法 JSON + 有截断标注
        Map<String, Object> huge = new LinkedHashMap<>(evidence);
        huge.put("blob", "x".repeat(PipelineService.EVIDENCE_MAX_CHARS + 10));
        String reduced = service.evidenceJson(huge);
        Map<String, Object> reducedParsed = objectMapper.readValue(reduced,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        assertThat(reducedParsed).containsEntry("_evidenceTruncated", true);
        assertThat(reduced.length()).isLessThanOrEqualTo(PipelineService.EVIDENCE_MAX_CHARS);
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────
    private void writeLanding(String acceptedUriDir, String... eventLines) throws IOException {
        Files.createDirectories(landing.resolve("manifests"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", 1);
        m.put("status", "READY");
        // acceptedRecords 声明为 3（>0 保证 manifest 不被 findReadyManifest 当作空批次跳过；
        // 目录无文件时 LOAD_ODS 抛 RUN_EMPTY_DATA，而非 RUN_EMPTY_LANDING）
        m.put("acceptedRecords", 3);
        m.put("acceptedUri", acceptedUriDir);
        m.put("quarantinedRecords", 0);
        m.put("checksum", "test-checksum");
        m.put("schemaVersions", List.of("1.0"));
        Files.writeString(landing.resolve("manifests/b1.json"), objectMapper.writeValueAsString(m));
        writeEvents(acceptedUriDir, eventLines);
    }

    private void writeEvents(String acceptedUriDir, String... eventLines) throws IOException {
        Path dir = landing.resolve(acceptedUriDir);
        Files.createDirectories(dir);
        if (eventLines.length > 0) {
            Files.writeString(dir.resolve("events.jsonl"), String.join("\n", eventLines) + "\n");
        }
    }

    private String event(String id, String type, String time, String payloadJson) {
        return "{\"event_id\":\"" + id + "\",\"event_type\":\"" + type + "\",\"event_time\":\"" + time
                + "\",\"ingest_time\":\"" + time + "\",\"source_system\":\"mall\",\"schema_version\":\"1.0\","
                + "\"trace_id\":\"t\",\"payload\":" + payloadJson + "}";
    }

    private List<PipelineStageRun> stageList() {
        return new ArrayList<>(stages);
    }

    private PipelineStageRun stageOf(String code) {
        for (PipelineStageRun s : stages) {
            if (code.equals(s.getStageCode())) {
                return s;
            }
        }
        return null;
    }

    private String stageStatus(String code) {
        PipelineStageRun s = stageOf(code);
        return s == null ? null : s.getStatus();
    }

    private int updateByIdCount() {
        return (int) org.mockito.Mockito.mockingDetails(runMapper).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("updateById")).count();
    }

    /** 收集 Runnable 的 Executor：run() 投递后不立即执行，drain() 手动触发 */
    private static class CollectingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int pending() {
            return tasks.size();
        }

        void drain() {
            List<Runnable> copy = new ArrayList<>(tasks);
            tasks.clear();
            for (Runnable r : copy) {
                r.run();
            }
        }
    }
}