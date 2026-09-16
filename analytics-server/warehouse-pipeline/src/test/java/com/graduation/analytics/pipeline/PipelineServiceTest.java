package com.graduation.analytics.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.metric.QualityRuleCatalog;
import com.graduation.analytics.metric.RuleSeverity;
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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.atLeastOnce;
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

    /** S2-04：本类 fixture 的源（profile.source_id）与清单 sourceId 必须一致 */
    private static final long SOURCE_ID = 7L;
    /** S2-04：另一个源（用于验证"他源清单不被选中"） */
    private static final long OTHER_SOURCE_ID = 9L;

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

    /**
     * F-88：data_quality_result 的内存替身（预置行 + insert 累积）。
     * 发布前质量门断言读的就是这张表，所以测试必须能"预置一行未通过的阻断级规则"。
     */
    private final List<DataQualityResult> qualityRows = new ArrayList<>();

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
        // S2-04：运行的源身份是 ODS 库名/--sourceSystem 的来源，也是清单归属判据。
        // 真实环境里它由连接侧绑定（SOURCE_NOT_BOUND fail-closed），此处照实设成 SOURCE_ID。
        profile.setSourceId(SOURCE_ID);
        profile.setLandingUri(landing.toAbsolutePath().toString());
        profile.setSparkSubmitPath("D:\\spark\\bin\\spark-submit.cmd");
        profile.setSparkJobJarUri("spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar");
        when(runtimeProfileService.get(1L)).thenReturn(profile);

        // R6-11：真实 Spark 阶段由执行器工厂产出；L1 注入 Fake 执行器（不启动 Spark），
        // 默认每个阶段全部作业 SUCCESS，计数取 jobCode 对应的固定 JobResult 值。
        when(stageExecutorFactory.create(any())).thenReturn(stageExecutor);
        when(stageExecutor.executeStage(any(), anyLong(), anyString(), anyString(), anyInt(), any(), any()))
                .thenAnswer(inv -> successExecution(inv.getArgument(2)));

        // 质量检查默认通过（个别测试覆盖为失败）。
        // V25-Q01 起生产路径走**四参**重载（多一个 FrozenRules：该 run 冻结的规则集）。
        // 若此处仍 stub 三参重载，Mockito 会对未 stub 的四参调用返回 null，
        // QUALITY_CHECK 随即抛 STAGE_INTERNAL —— 实测 10 个用例因此集体翻红。
        when(qualityChecker.check(any(), anyLong(), any(), any()))
                .thenReturn(new QualityChecker.QualitySummary(List.of(), true));

        // F-88：质量结果表内存替身（默认空 → 发布前门断言通过；个别测试预置失败行）
        when(qualityMapper.insert(any(DataQualityResult.class))).thenAnswer(inv -> {
            qualityRows.add(inv.getArgument(0));
            return 1;
        });
        when(qualityMapper.selectList(any())).thenAnswer(inv -> List.copyOf(qualityRows));

        stubPublisherSuccess();

        service = new PipelineService(runMapper, stageMapper, qualityMapper, qualityChecker,
                eventClock, objectMapper, runtimeProfileService, stageExecutorFactory, executor,
                publisherPort, metricDefinitionMapper(), new DataQualityGate(qualityMapper),
                new LandingManifestSelector(objectMapper));
    }

    /**
     * R7-3：L1 用假发布端口（不连指标库）——本类只验证编排与状态机；
     * ADS→MySQL 的真实写入/对账/ACTIVE 切换由 metric-analysis 的单测 + 真实小链（run 21）覆盖。
     *
     * <p>F-88：改为 Mockito mock + 固定成功报告，便于断言「阻断时**从不调用**发布」
     * （不调用 = 不产生新快照、不切 ACTIVE 指针 = 旧 ACTIVE 保持可读）。</p>
     */
    private final com.graduation.analytics.metric.publish.MetricPublisherPort publisherPort =
            org.mockito.Mockito.mock(com.graduation.analytics.metric.publish.MetricPublisherPort.class);

    private void stubPublisherSuccess() {
        when(publisherPort.publish(any())).thenAnswer(inv -> {
            com.graduation.analytics.metric.publish.MetricPublisherPort.PublishRequest request = inv.getArgument(0);
            return new com.graduation.analytics.metric.publish.MetricPublisherPort.PublishReport(
                    true, null, "L1 stub", request.snapshotId(), 22L, 10, List.of(),
                    Map.of("stub", true));
        });
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

    // ── S3-32：INIT_SCHEMA 的「提交次序」与「自举证据」守卫 ───────────────────

    /**
     * S3-32：§14.1 要求**先自举四层库表**再装载 —— 自举若晚于装载，装载就打在尚未
     * 存在的库表上。既有断言只证「提交过 / 状态 SUCCESS」，对**成功路径**的先后**无断言**：
     * 把两段调用对调（变异探针 `s332_probeA1`）⇒ 本守卫红，另有 1 条既有**失败路径**用例
     * （`stageFailureMarksRunFailed`）因「自举根本没被提交」而**间接**变红 —— 但没有任何
     * 用例**直接**断言两者的先后，本守卫补的就是这条直接不变量。
     */
    @Test
    void initSchemaIsSubmittedBeforeLoadOds() throws Exception {
        List<String> submitted = new ArrayList<>();
        when(stageExecutor.executeStage(any(), anyLong(), anyString(), anyString(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    submitted.add(inv.getArgument(2));
                    return successExecution(inv.getArgument(2));
                });
        writeLanding("accepted/2026-09-01",
                event("e1", "behavior", "2026-09-01T10:00:00", "{\"user_id\":\"u1\",\"product_id\":\"p1\"}"));

        service.run(1L, "ODS_TO_ADS", LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-order", "trace-1");
        executor.drain();

        assertThat(submitted).as("自举与装载都必须真的被提交（否则下面的次序断言是空转）")
                .contains("INIT_SCHEMA", "LOAD_ODS");
        assertThat(submitted.indexOf("INIT_SCHEMA"))
                .as("INIT_SCHEMA 必须排在 LOAD_ODS 之前；实测提交次序=" + submitted)
                .isLessThan(submitted.indexOf("LOAD_ODS"));
        assertThat(submitted.get(0)).as("首个被提交的阶段必须是自举").isEqualTo("INIT_SCHEMA");
    }

    /**
     * S3-32：自举成功后 `PipelineService` 会把「幂等 CREATE IF NOT EXISTS，可重复执行」
     * 写进 INIT_SCHEMA 的阶段证据（`contracted` 键）。此前**无任何断言** ⇒ 删掉该写入
     * 测试仍全绿（变异探针 `s332_probeB1`），本守卫把这条对外可见的事实钉住。
     */
    @Test
    void initSchemaEvidenceCarriesSelfBootstrapContract() throws Exception {
        writeLanding("accepted/2026-09-01",
                event("e1", "behavior", "2026-09-01T10:00:00", "{\"user_id\":\"u1\",\"product_id\":\"p1\"}"));

        service.run(1L, "ODS_TO_ADS", LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-evidence", "trace-1");
        executor.drain();

        PipelineStageRun init = stageOf("INIT_SCHEMA");
        assertThat(init).isNotNull();
        assertThat(stageStatus("INIT_SCHEMA")).isEqualTo(PipelineStageRun.STATUS_SUCCESS);
        assertThat(init.getEvidence()).as("自举阶段的证据不得为空").isNotBlank();
        Map<String, Object> evidence = objectMapper.readValue(init.getEvidence(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        assertThat(evidence).containsKey("contracted");
        assertThat(String.valueOf(evidence.get("contracted")))
                .as("证据须写明自举是幂等的 CREATE … IF NOT EXISTS（可重复执行）")
                .contains("CREATE DATABASE/TABLE IF NOT EXISTS");
    }

    // ── S2-04：Landing 输入清单必须按源归属（fail-closed） ────────────────

    @Test
    void foreignSourceManifestIsNeverUsedAsInput() throws Exception {
        // landing 根下只有**他源**的 READY 批次（A 源这次 run 没有自己的批次）
        writeManifest(2, OTHER_SOURCE_ID, "accepted/2026-09-01-b", true);
        writeEvents("accepted/2026-09-01-b",
                event("e9", "order_created", "2026-09-01T10:00:00",
                        "{\"order_id\":\"o9\",\"total_amount\":\"999\"}"));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-foreign", "trace-1");
        executor.drain();

        PipelineService.RunResult failed = service.get(r.runId());
        assertThat(failed.status()).isEqualTo(PipelineRun.STATUS_FAILED);
        assertThat(failed.errorCode())
                .as("没有可归属的批次 ⇒ 如实报无输入，而不是拿他源字节充数（否则 B 的数据会进 A 的 ODS 库，"
                        + "且 source_system 是注入的常量，事后无法察觉）")
                .isEqualTo("RUN_EMPTY_LANDING");
        assertThat(stageStatus("WAIT_LANDING")).isEqualTo(PipelineStageRun.STATUS_FAILED);
        // 未通过 WAIT_LANDING 就不该有任何真正作业被提交（尤其不能把 B 的 accepted 当输入）
        assertThat(stageOf("INIT_SCHEMA")).isNull();
        assertThat(stageOf("LOAD_ODS")).isNull();
        verify(stageExecutor, never()).executeStage(any(), anyLong(), anyString(), anyString(),
                anyInt(), any(), any());
    }

    @Test
    void newerForeignManifestDoesNotShadowOwnSourceBatch() throws Exception {
        // A 源自己有批次 1；B 源后来写了更大的批次 2（共用同一 landing 根的常见实验场景）
        writeManifest(1, SOURCE_ID, "accepted/2026-09-01-a", true);
        writeEvents("accepted/2026-09-01-a",
                event("e1", "order_created", "2026-09-01T10:00:00",
                        "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));
        writeManifest(2, OTHER_SOURCE_ID, "accepted/2026-09-01-b", true);
        writeEvents("accepted/2026-09-01-b",
                event("e9", "order_created", "2026-09-01T10:00:00",
                        "{\"order_id\":\"o9\",\"total_amount\":\"999\"}"));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-shadow", "trace-1");
        executor.drain();

        assertThat(service.get(r.runId()).status()).isEqualTo(PipelineRun.STATUS_SUCCESS);
        PipelineStageRun landingStage = stageOf("WAIT_LANDING");
        assertThat(landingStage.getEvidence())
                .as("只能选本源的批次 1（batchId 更大的是他源）")
                .contains("\"batchId\":1")
                .contains("\"acceptedUri\":\"accepted/2026-09-01-a\"")
                .contains("\"sourceId\":" + SOURCE_ID);
        assertThat(landingStage.getEvidence()).doesNotContain("accepted/2026-09-01-b");
    }

    @Test
    void manifestWithoutSourceIdIsNotAttributableInPipeline() throws Exception {
        // P1-05 之前的清单（无 sourceId）：无法证明它属于本源的库 → 不猜
        writeManifest(3, SOURCE_ID, "accepted/2026-09-01", false);
        writeEvents("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00",
                        "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-nosource", "trace-1");
        executor.drain();

        PipelineService.RunResult failed = service.get(r.runId());
        assertThat(failed.status()).isEqualTo(PipelineRun.STATUS_FAILED);
        assertThat(failed.errorCode()).isEqualTo("RUN_EMPTY_LANDING");
    }

    // ── S3-36：批次级溯源 —— WAIT_LANDING 钉住的输入批次落库（pipeline_run.input_batch_id） ──
    // 背景（S3-34 登记行）：V7 迁移建了该列、实体也有字段，但生产代码**零写入**，
    // 于是"这个 run 吃的是哪个采集批次"只能靠 pipeline_stage_run.evidence 的 JSON 正则反查。
    // 这两例把"该写"和"不该写"都钉住：有归属批次 → 写；无可归属批次 → 留 NULL，不猜。

    @Test
    void waitLandingPersistsInputBatchIdOfPinnedBatch() throws Exception {
        // writeLanding 写的是 manifests/b1.json（batchId=1）→ 本 run 的输入批次就是 1
        writeLanding("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-batchid", "trace-1");
        executor.drain();

        assertThat(service.get(r.runId()).status()).isEqualTo(PipelineRun.STATUS_SUCCESS);
        // ①真的落库：至少有一次 updateById 带着 input_batch_id=1（不是只改了内存对象）
        ArgumentCaptor<PipelineRun> captor = ArgumentCaptor.forClass(PipelineRun.class);
        verify(runMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(x -> Long.valueOf(1L).equals(x.getInputBatchId()));
        // ②落库值与阶段证据同源（同一次 manifest 解析）
        assertThat(insertedRun.get().getInputBatchId()).isEqualTo(1L);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"batchId\"\\s*:\\s*(\\d+)")
                .matcher(stageOf("WAIT_LANDING").getEvidence());
        assertThat(m.find()).isTrue();
        assertThat(Long.parseLong(m.group(1))).isEqualTo(insertedRun.get().getInputBatchId());
    }

    @Test
    void manifestWithoutUsableBatchIdDoesNotGuessInputBatch() throws Exception {
        // 老/坏清单：READY、可归属、有数据，但**没有 batchId** —— 选择器仍会选中它
        // （首个合格候选即 best），此时 input_batch_id 必须留 NULL：ingestion_batch 里
        // 不存在 0 号批次，写 0 就是编造。
        Files.createDirectories(landing.resolve("manifests"));
        Files.writeString(landing.resolve("manifests/b-nobatch.json"),
                "{\"status\":\"READY\",\"acceptedRecords\":3,\"acceptedUri\":\"accepted/2026-09-01\","
                        + "\"quarantinedRecords\":0,\"sourceId\":" + SOURCE_ID + ",\"sourceCode\":\"mall-a\","
                        + "\"schemaVersions\":[\"1.0\"]}");
        writeEvents("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-nobatchid", "trace-1");
        executor.drain();

        assertThat(service.get(r.runId()).status()).isEqualTo(PipelineRun.STATUS_SUCCESS);
        ArgumentCaptor<PipelineRun> captor = ArgumentCaptor.forClass(PipelineRun.class);
        verify(runMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).allMatch(x -> x.getInputBatchId() == null);
    }

    @Test
    void nonNumericBatchIdIsNotWrittenAsZero() throws Exception {
        // 更坏的老清单：batchId 存在但**不是数字** —— 选择器按 longOf 宽松读得 0，
        // 且首个合格候选即 best，故它仍会被选中（WAIT_LANDING 通过）。
        // 此时若把 0 写进 input_batch_id，就等于宣称"吃了 0 号批次"（ingestion_batch 无此行）⇒
        // 必须留 NULL。本用例专门钉住写入侧的 `batchId > 0` fail-closed 守卫。
        Files.createDirectories(landing.resolve("manifests"));
        Files.writeString(landing.resolve("manifests/b-bad.json"),
                "{\"batchId\":\"unknown\",\"status\":\"READY\",\"acceptedRecords\":3,"
                        + "\"acceptedUri\":\"accepted/2026-09-01\",\"quarantinedRecords\":0,"
                        + "\"sourceId\":" + SOURCE_ID + ",\"sourceCode\":\"mall-a\","
                        + "\"schemaVersions\":[\"1.0\"]}");
        writeEvents("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-badbatch", "trace-1");
        executor.drain();

        assertThat(service.get(r.runId()).status()).isEqualTo(PipelineRun.STATUS_SUCCESS);
        ArgumentCaptor<PipelineRun> captor = ArgumentCaptor.forClass(PipelineRun.class);
        verify(runMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).allMatch(x -> x.getInputBatchId() == null);
    }

    @Test
    void unattributableManifestLeavesInputBatchIdNull() throws Exception {
        // 清单缺 sourceId（P1-05 之前的老清单）⇒ 不可归属 ⇒ 本 run 没有输入批次
        writeManifest(3, SOURCE_ID, "accepted/2026-09-01", false);
        writeEvents("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00",
                        "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-nobatch", "trace-1");
        executor.drain();

        assertThat(service.get(r.runId()).status()).isEqualTo(PipelineRun.STATUS_FAILED);
        // 无法证明吃了哪个批次时，该列必须保持 NULL（宁可空，不可猜）
        ArgumentCaptor<PipelineRun> captor = ArgumentCaptor.forClass(PipelineRun.class);
        verify(runMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).allMatch(x -> x.getInputBatchId() == null);
    }

    @Test
    void profileWithoutSourceFailsClosedWithStableCodeBeforeBuildingExecutor() throws Exception {
        // 未绑定源的运行环境：库名与 --sourceSystem 都不可知，绝不能猜一个源跑下去
        RuntimeProfile unbound = new RuntimeProfile();
        unbound.setId(1L);
        unbound.setVersion(7);
        unbound.setType(RuntimeProfile.TYPE_LOCAL);
        unbound.setLandingUri(landing.toAbsolutePath().toString());
        when(runtimeProfileService.get(1L)).thenReturn(unbound);

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-unbound", "trace-1");
        executor.drain();

        PipelineService.RunResult failed = service.get(r.runId());
        assertThat(failed.status()).isEqualTo(PipelineRun.STATUS_FAILED);
        assertThat(failed.errorCode())
                .as("装配路径下执行器工厂抛的是 PlatformBizException，会被通用 catch 吞成 RUN_INTERNAL；"
                        + "故本判定必须早于执行器构造，让运维看到的始终是稳定码 SOURCE_NOT_BOUND")
                .isEqualTo("SOURCE_NOT_BOUND");
        // 判定早于执行器构造：一个作业都不该被准备
        verify(stageExecutorFactory, never()).create(any());
        assertThat(stages).isEmpty();
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
        // S3-36 追加断言：重试仍复用原批次（manifestForRun 钉住 b1.json），
        // 输入批次不随重试漂移（第一轮 LOAD_ODS 失败时也已落库）
        assertThat(insertedRun.get().getInputBatchId()).isEqualTo(1L);
    }

    // ── ⑥质量失败不得进发布（§5.4.1 阻断） ────────────────────────────────

    @Test
    void qualityFailureBlocksPublish() throws Exception {
        writeLanding("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"),
                event("e2", "order_paid", "2026-09-01T10:05:00", "{\"order_id\":\"o1\",\"amount\":\"99\"}"));
        when(qualityChecker.check(any(), anyLong(), any(), any()))
                .thenReturn(new QualityChecker.QualitySummary(List.of(amountRuleFailed()), false));

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
                    // F-88：固定档位码两列同值（声明 BLOCKING、生效 BLOCKING）
                    assertThat(q.getSeverity()).isEqualTo("BLOCKING");
                    assertThat(q.getEffectiveSeverity()).isEqualTo("BLOCKING");
                    assertThat(q.getLayer()).isEqualTo("ADS_STAGING");
                    assertThat(q.getPassed()).isZero();
                    assertThat(q.getSnapshotId()).startsWith("S20260901_");
                });
    }

    /**
     * F-88（V20）：**第二个写点** {@code PipelineService.persistChecks}（Spark 作业回传的 checks）
     * 也必须落齐版本化信息，并与 {@code QualityChecker.rule} 用同一契约。
     *
     * <p>为什么单独钉这个写点：两个写点走的是不同代码路径（内联规则结果 vs 作业回传 checks），
     * 旧实现两处都是 {@code r.setSeverity(resolve(...).effectiveSeverity())} —— 只改一处的话，
     * 另一处产出的行仍会丢掉声明档位，而这类"半套版本信息"的行在库里看不出异常。</p>
     *
     * <p>夹具 {@code PUB_DQ_EVENT_ID_UNIQUE} 声明 WARN 且是条件观察项，作业回传字面 severity 写
     * {@code ERROR}、{@code passed=false}（超阈值）：因此正确结果是
     * {@code severity=WARN}（目录声明档位，**不是**作业回传的 ERROR）+
     * {@code effectiveSeverity=BLOCKING}（超阈值升档）。</p>
     */
    @Test
    void sparkCheckResultsCarryDeclaredAndEffectiveSeverityPlusVersionInfo() throws Exception {
        writeLanding("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));
        when(stageExecutor.executeStage(any(), anyLong(), org.mockito.ArgumentMatchers.eq("QUALITY_CHECK"),
                anyString(), anyInt(), any(), any()))
                .thenAnswer(inv -> qualityBlockedExecution("QUALITY_CHECK"));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-persist-checks", "trace-1");
        executor.drain();
        assertThat(service.get(r.runId()).status()).isEqualTo(PipelineRun.STATUS_FAILED);

        QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze(null);
        assertThat(qualityRows)
                .as("作业回传的 checks 必须写库（INFO 审计项除外）")
                .anySatisfy(q -> {
                    assertThat(q.getRuleCode()).isEqualTo("PUB_DQ_EVENT_ID_UNIQUE");
                    assertThat(q.getSeverity())
                            .as("声明档位列取目录声明值 WARN，不照抄作业回传字面 ERROR")
                            .isEqualTo(RuleSeverity.WARN);
                    assertThat(q.getEffectiveSeverity())
                            .as("超阈值 ⇒ 生效档位升为 BLOCKING")
                            .isEqualTo(RuleSeverity.BLOCKING);
                    assertThat(q.getRuleVersion())
                            .isEqualTo(rules.find("PUB_DQ_EVENT_ID_UNIQUE").orElseThrow().version());
                    assertThat(q.getCompatPolicyVersion()).isEqualTo(rules.compatPolicyVersion());
                    assertThat(q.getRuleFingerprint()).isEqualTo(rules.fingerprint());
                });
        // INFO 审计项不落规则表（原有纪律不因本次改动松动）
        assertThat(qualityRows).noneSatisfy(q ->
                assertThat(q.getRuleCode()).isEqualTo("PUB_STAGING_PRUNE"));
    }

    // ── ⑥c F-88（D-142 §1）：发布前门断言 —— 阻断级未过 ⇒ 不发布新快照 ────────

    @Test
    void errorSeverityFailureBlocksPublishAndLeavesActiveUntouched() throws Exception {
        writeLanding("accepted/2026-09-01",
                event("e1", "order_created", "2026-09-01T10:00:00", "{\"order_id\":\"o1\",\"total_amount\":\"100\"}"));
        // F-94：规则码才是判定输入。这里必须用**已登记的阻断级规则码**——
        // 早期夹具写的是 PUB_DQ_EVENT_ID_UNIQUE，而该码在目录里是 WARN（下游确定性去重），
        // 于是"ERROR 失败 ⇒ 不发布"这条用例实际测的是"WARN 失败 ⇒ 仍发布"，断言由 FAILED 变 SUCCESS。
        // 字面 severity 仍写 ERROR，用来证明目录覆盖了字面标签。
        qualityRows.add(qualityRow("ADS_STAGING_PRESENT", "ERROR", 0));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-err-pre", "trace-1");
        executor.drain();

        PipelineService.RunResult failed = service.get(r.runId());
        assertThat(failed.status()).isEqualTo(PipelineRun.STATUS_FAILED);
        assertThat(failed.errorCode()).isEqualTo("PIPELINE_QUALITY_FAILED");
        // 发布前门断言在 PUBLISH_METRIC 内触发：阶段已创建但 FAILED（不是"未创建"）
        assertThat(stageStatus("PUBLISH_METRIC")).isEqualTo(PipelineStageRun.STATUS_FAILED);
        // 关键负向断言：从不调用发布端口 ⇒ 不产生新快照、不切 ACTIVE（旧 ACTIVE 保持可读）
        verify(publisherPort, never()).publish(any());
        // 证据留痕：失败原因与规则码写进阶段证据（运维可见）
        assertThat(stageOf("PUBLISH_METRIC").getEvidence())
                .contains("prePublishGate").contains("ADS_STAGING_PRESENT");
    }

    @Test
    void duplicateRateWithinThresholdIsObservationAndStillPublishes() throws Exception {
        // 单个事件、无重复 ⇒ 重复率 0.000000 <= 0.0005 ⇒ EVENT_ID_UNIQUE passed=1 ⇒ 观察项，不阻断。
        // （本用例原为「重复率 0.5 仍发布成功」；§7.3.1 line 522 明确「超过阈值阻断」且
        //  「测试不得为通过把高重复率直接放行」，故 0.5 的情形已移到下面的阻断用例。）
        writeLanding("accepted/2026-09-01",
                event("e1", "behavior", "2026-09-01T10:00:00", "{\"user_id\":\"u1\",\"product_id\":\"p1\",\"behavior_type\":\"view\"}"));
        when(qualityChecker.check(any(), anyLong(), any(), any()))
                .thenAnswer(inv -> new QualityChecker().check(inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2), inv.getArgument(3)));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-obs", "trace-1");
        executor.drain();

        PipelineService.RunResult done = service.get(r.runId());
        assertThat(done.status()).isEqualTo(PipelineRun.STATUS_SUCCESS);
        assertThat(stageStatus("PUBLISH_METRIC")).isEqualTo(PipelineStageRun.STATUS_SUCCESS);
        // 观察项未过也要写库留痕（运维页可见），但不阻断发布
        assertThat(qualityRows).anySatisfy(q -> {
            assertThat(q.getRuleCode()).isEqualTo("EVENT_ID_UNIQUE");
            assertThat(q.getPassed()).isEqualTo(1);
        });
        verify(publisherPort).publish(any());
    }

    @Test
    void duplicateRateBeyondThresholdBlocksPublish() throws Exception {
        // 两个事件互为重复 ⇒ 重复率 0.5 >> 0.0005 ⇒ EVENT_ID_UNIQUE passed=0
        // ⇒ 有效严重度由 WARN 升为 BLOCKING ⇒ 必须阻断发布（§7.3.1 line 522）。
        writeLanding("accepted/2026-09-01",
                event("dup", "behavior", "2026-09-01T10:00:00", "{\"user_id\":\"u1\",\"product_id\":\"p1\",\"behavior_type\":\"view\"}"),
                event("dup", "behavior", "2026-09-01T10:01:00", "{\"user_id\":\"u1\",\"product_id\":\"p1\",\"behavior_type\":\"view\"}"));
        when(qualityChecker.check(any(), anyLong(), any(), any()))
                .thenAnswer(inv -> new QualityChecker().check(inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2), inv.getArgument(3)));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-dup", "trace-1");
        executor.drain();

        PipelineService.RunResult done = service.get(r.runId());
        assertThat(done.status()).isEqualTo(PipelineRun.STATUS_FAILED);
        // 原始档位仍是 WARN、但有效严重度升为 BLOCKING：两个字段都要留痕（§7.3.1 line 520）
        assertThat(qualityRows).anySatisfy(q -> {
            assertThat(q.getRuleCode()).isEqualTo("EVENT_ID_UNIQUE");
            assertThat(q.getPassed()).isZero();
            // F-88（V20）新契约：severity 落**声明**档位 WARN，effectiveSeverity 落**生效**档位 BLOCKING。
            // 两列必须不同 —— 这正是「声明 WARN 但超阈值生效阻断」与「本来就是阻断」可区分的依据；
            // 旧实现只写一列（severity=生效档位），声明档位 WARN 在库里永久丢失。
            assertThat(q.getSeverity()).isEqualTo(RuleSeverity.WARN);
            assertThat(q.getEffectiveSeverity()).isEqualTo(RuleSeverity.BLOCKING);
            // 版本化三要素同行落库，且与本次 run 冻结集一致（期望值从冻结集算，不硬编码指纹）
            QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze(null);
            assertThat(q.getRuleVersion()).isEqualTo(rules.find("EVENT_ID_UNIQUE").orElseThrow().version());
            assertThat(q.getCompatPolicyVersion()).isEqualTo(rules.compatPolicyVersion());
            assertThat(q.getRuleFingerprint()).isEqualTo(rules.fingerprint());
        });
        // 关键负向断言：不发布 ⇒ 不产生新 ACTIVE
        verify(publisherPort, never()).publish(any());
    }

    /**
     * 预置一条质量结果行（模拟库里已存在的未过规则）。
     *
     * <p>checkCount/errorCount 必须非空：QUALITY_CHECK 证据里的 landingRules 用 {@code Map.of}
     * 构造，null 会直接抛 NPE 并把阶段错误码变成 STAGE_INTERNAL（实测踩过 —— 断言只看到
     * "expected PIPELINE_QUALITY_FAILED but was STAGE_INTERNAL"）。</p>
     */
    private static DataQualityResult qualityRow(String ruleCode, String severity, Integer passed) {
        DataQualityResult row = new DataQualityResult();
        row.setRunId(1L);
        row.setRuleCode(ruleCode);
        row.setLayer("PUBLISH");
        row.setSeverity(severity);
        row.setPassed(passed);
        row.setCheckCount(1L);
        row.setErrorCount(Integer.valueOf(0).equals(passed) ? 1L : 0L);
        row.setThreshold("0.01");
        row.setDetail("F-88 测试夹具");
        return row;
    }

    /** AMOUNT_RECONCILE（BLOCKING）未过的内联规则结果。 */
    private static DataQualityResult amountRuleFailed() {
        DataQualityResult row = qualityRow("AMOUNT_RECONCILE", RuleSeverity.BLOCKING, 0);
        row.setLayer("LANDING");
        return row;
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
        writeManifest(1, SOURCE_ID, acceptedUriDir, true);
        writeEvents(acceptedUriDir, eventLines);
    }

    /**
     * 写一条 READY 清单（S2-04：清单的源身份是选择判据，故必须可定制）。
     *
     * @param includeSourceId false = 模拟 P1-05 之前的清单（缺源身份 → 不可归属）
     */
    private void writeManifest(int batchId, long sourceId, String acceptedUri, boolean includeSourceId)
            throws IOException {
        Files.createDirectories(landing.resolve("manifests"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", batchId);
        m.put("status", "READY");
        // acceptedRecords 声明为 3（>0 保证 manifest 不被当作空批次跳过；
        // 目录无文件时 LOAD_ODS 抛 RUN_EMPTY_DATA，而非 RUN_EMPTY_LANDING）
        m.put("acceptedRecords", 3);
        m.put("acceptedUri", acceptedUri);
        m.put("quarantinedRecords", 0);
        m.put("checksum", "test-checksum");
        m.put("schemaVersions", List.of("1.0"));
        if (includeSourceId) {
            m.put("sourceId", sourceId);
            m.put("sourceCode", sourceId == SOURCE_ID ? "mall-a" : "mall-b");
        }
        Files.writeString(landing.resolve("manifests/b" + batchId + ".json"),
                objectMapper.writeValueAsString(m));
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