package com.graduation.analytics.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.metric.AdsMaterializer;
import com.graduation.analytics.metric.MetricCalculator;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.mapper.MetricSnapshotMapper;
import com.graduation.analytics.metric.mapper.MetricValueMapper;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import com.graduation.analytics.pipeline.entity.PipelineRun;
import com.graduation.analytics.pipeline.entity.PipelineStageRun;
import com.graduation.analytics.pipeline.mapper.DataQualityResultMapper;
import com.graduation.analytics.pipeline.mapper.PipelineRunMapper;
import com.graduation.analytics.pipeline.mapper.PipelineStageRunMapper;
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
import static org.mockito.ArgumentMatchers.anyLong;
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
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineServiceTest {

    @TempDir
    Path landing;

    @Mock PipelineRunMapper runMapper;
    @Mock PipelineStageRunMapper stageMapper;
    @Mock DataQualityResultMapper qualityMapper;
    @Mock MetricSnapshotMapper snapshotMapper;
    @Mock MetricValueMapper valueMapper;
    @Mock AdsMaterializer adsMaterializer;
    @Mock MetricStore metricStore;
    @Mock QualityChecker qualityChecker;
    @Mock RuntimeProfileService runtimeProfileService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventClock eventClock = new EventClock(Clock.fixed(
            java.time.Instant.parse("2026-09-01T10:00:00Z"), ZoneId.of("Asia/Shanghai")));
    private final MetricCalculator calculator = new MetricCalculator();

    /** 收集型 Executor：run() 只投递不执行 → 手动 drain 触发异步链（验证"立即返回"） */
    private final CollectingExecutor executor = new CollectingExecutor();

    /** 内存映射 store：模拟单键幂等查询与单 run 阶段列表 */
    private final AtomicReference<PipelineRun> insertedRun = new AtomicReference<>();
    private final List<PipelineStageRun> stages = new ArrayList<>();
    private final AtomicLong stageId = new AtomicLong(1);
    private final AtomicLong runId = new AtomicLong(1);
    private final AtomicLong snapshotIdSeq = new AtomicLong(1);

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

        // 快照 insert 回填自增 id（createSnapshot 返回 snapshot.getId() 需要非空）
        doAnswer(inv -> {
            MetricSnapshot s = inv.getArgument(0);
            s.setId(900L + snapshotIdSeq.getAndIncrement());
            return 1;
        }).when(snapshotMapper).insert(any(MetricSnapshot.class));

        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setVersion(7);
        profile.setType(RuntimeProfile.TYPE_LOCAL);
        profile.setLandingUri(landing.toAbsolutePath().toString());
        when(runtimeProfileService.get(1L)).thenReturn(profile);

        // 质量检查默认通过（个别测试覆盖为失败）
        when(qualityChecker.check(any(), anyLong()))
                .thenReturn(new QualityChecker.QualitySummary(List.of(mock(DataQualityResult.class)), true));

        service = new PipelineService(runMapper, stageMapper, qualityMapper, snapshotMapper,
                valueMapper, adsMaterializer, calculator, metricStore, qualityChecker,
                eventClock, objectMapper, runtimeProfileService, executor);
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
        // 未发布
        verify(metricStore, never()).publish(any(), any());
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
        when(qualityChecker.check(any(), anyLong()))
                .thenReturn(new QualityChecker.QualitySummary(List.of(mock(DataQualityResult.class)), false));

        PipelineService.RunResult r = service.run(1L, "ODS_TO_ADS",
                LocalDateTime.of(2026, 9, 1, 10, 0), "v7", "k-quality", "trace-1");
        executor.drain();

        PipelineService.RunResult failed = service.get(r.runId());
        assertThat(failed.status()).isEqualTo(PipelineRun.STATUS_FAILED);
        assertThat(failed.errorCode()).isEqualTo("PIPELINE_QUALITY_FAILED");
        assertThat(stageStatus("QUALITY_CHECK")).isEqualTo(PipelineStageRun.STATUS_FAILED);
        // 不发布：无 PUBLISH_METRIC 阶段、快照与 ADS 物化均未触发
        assertThat(stageOf("PUBLISH_METRIC")).isNull();
        verify(snapshotMapper, never()).insert(any(MetricSnapshot.class));
        verify(adsMaterializer, never()).refreshActive();
        verify(metricStore, never()).publish(any(), any());
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