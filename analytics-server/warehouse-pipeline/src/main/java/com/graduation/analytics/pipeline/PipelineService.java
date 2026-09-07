package com.graduation.analytics.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.metric.MetricCalculator;
import com.graduation.analytics.metric.MetricCalculator.MetricDataset;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.AdsMaterializer;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import com.graduation.analytics.metric.mapper.MetricSnapshotMapper;
import com.graduation.analytics.metric.mapper.MetricValueMapper;
import com.graduation.analytics.contracts.EventContract;
import com.graduation.analytics.contracts.EventEnvelope;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import com.graduation.analytics.pipeline.entity.PipelineRun;
import com.graduation.analytics.pipeline.entity.PipelineStageRun;
import com.graduation.analytics.pipeline.mapper.DataQualityResultMapper;
import com.graduation.analytics.pipeline.mapper.PipelineRunMapper;
import com.graduation.analytics.pipeline.mapper.PipelineStageRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 首版流水线（§23.1）：WAIT_LANDING→LOAD_ODS→BUILD_DWD→BUILD_DWS→BUILD_ADS→
 * QUALITY_CHECK→PUBLISH_METRIC→SUCCESS。
 * 幂等键 = runtimeProfileId+pipelineCode+businessTime+sourceDataVersion；
 * 相同键返回原 run；失败后重试递增 attempt_no。集群模式各阶段由 spark-jobs
 * 执行（external_job_id），LOCAL 模式由本服务顺序执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final PipelineRunMapper runMapper;
    private final PipelineStageRunMapper stageMapper;
    private final DataQualityResultMapper qualityMapper;
    private final MetricSnapshotMapper snapshotMapper;
    private final MetricValueMapper valueMapper;
    private final AdsMaterializer adsMaterializer;
    private final MetricCalculator calculator;
    private final MetricStore metricStore;
    private final QualityChecker qualityChecker;
    private final EventClock eventClock;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    public record RunResult(Long runId, String idempotencyKey, String status,
                            String errorCode, int attemptNo, Long snapshotId,
                            List<PipelineStageRun> stages) {
    }

    public RunResult run(Long runtimeProfileId, String pipelineCode, LocalDateTime businessTime,
                         String sourceDataVersion, String idempotencyKey, String traceId) {
        String key = idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey
                : buildKey(runtimeProfileId, pipelineCode, businessTime, sourceDataVersion);

        // 幂等：同键已存在 → 返回原 run（不重复执行）
        PipelineRun existing = runMapper.selectOne(new LambdaQueryWrapper<PipelineRun>()
                .eq(PipelineRun::getIdempotencyKey, key));
        if (existing != null) {
            return assemble(existing.getId(), null);
        }

        PipelineRun run = new PipelineRun();
        run.setIdempotencyKey(key);
        run.setRuntimeProfileId(runtimeProfileId);
        run.setPipelineCode(pipelineCode);
        run.setBusinessTime(businessTime);
        run.setSourceDataVersion(sourceDataVersion);
        run.setAttemptNo(1);
        run.setStatus(PipelineRun.STATUS_RUNNING);
        run.setTraceId(traceId);
        run.setCreatedAt(eventClock.nowLdt());
        run.setUpdatedAt(eventClock.nowLdt());
        runMapper.insert(run);
        return execute(run);
    }

    /**
     * 重试：仅失败状态允许，attempt_no 递增后重新执行（§23.1 恢复：失败阶段重跑，
     * 已成功阶段不重复——首版顺序链从 WAIT_LANDING 起，集群版按阶段状态跳过）。
     */
    public RunResult retry(Long runId, String traceId) {
        PipelineRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("run 不存在: " + runId);
        }
        if (!PipelineRun.STATUS_FAILED.equals(run.getStatus())) {
            return assemble(runId, null);
        }
        run.setStatus(PipelineRun.STATUS_RUNNING);
        run.setErrorCode(null);
        run.setAttemptNo(run.getAttemptNo() + 1);
        run.setTraceId(traceId);
        runMapper.updateById(run);
        return execute(run);
    }

    /** 只读查询（含阶段明细） */
    public RunResult get(Long runId) {
        return assemble(runId, null);
    }

    // ── 执行链（幂等检查之外，run()/retry() 共用） ─────────────────────────

    private RunResult execute(PipelineRun run) {
        Long snapshotId = null;
        try {
            String businessDate = run.getBusinessTime().toLocalDate().format(KEY_DATE);
            List<EventEnvelope> events = new ArrayList<>();

            stage(run.getId(), "WAIT_LANDING", () -> {
                Path eventsDir = eventsDir();
                long files = 0;
                long bytes = 0;
                if (Files.isDirectory(eventsDir)) {
                    try (Stream<Path> list = Files.list(eventsDir)) {
                        for (Path f : list.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                            files++;
                            bytes += Files.size(f);
                        }
                    }
                }
                if (files == 0 || bytes == 0) {
                    throw new PipelineStageException("RUN_EMPTY_LANDING", "events 目录无数据");
                }
                return files;
            });

            stage(run.getId(), "LOAD_ODS", () -> {
                // 只装载 businessTime 归属日的事件（补数/重跑按日隔离，§5.3.3）
                String datePrefix = businessDate.substring(0, 4) + "-" + businessDate.substring(4, 6)
                        + "-" + businessDate.substring(6, 8);
                Path eventsDir = eventsDir();
                try (Stream<Path> list = Files.list(eventsDir)) {
                    for (Path f : list.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList()) {
                        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                            if (line.isBlank()) {
                                continue;
                            }
                            try {
                                EventEnvelope envelope = EventEnvelope.fromJson(line, objectMapper);
                                if (envelope.eventTime() != null && envelope.eventTime().startsWith(datePrefix)) {
                                    events.add(envelope);
                                }
                            } catch (Exception e) {
                                log.warn("pipeline skip bad line in {}: {}", f.getFileName(), e.getMessage());
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new PipelineStageException("RUN_LOAD_FAILED", e.getMessage());
                }
                if (events.isEmpty()) {
                    throw new PipelineStageException("RUN_EMPTY_DATA", "无可解析事件");
                }
                return (long) events.size();
            });

            // DWD 清洗（LOCAL：契约级校验 + 去重计数；集群：spark-jobs bdw）
            stage(run.getId(), "BUILD_DWD", () -> {
                long bad = events.stream().filter(e ->
                        EventContract.BEHAVIOR.equals(e.eventType())
                                && (isBlank(e.payload().get("user_id")) || isBlank(e.payload().get("product_id"))))
                        .count();
                return (long) events.size() - bad;
            });

            // DWS 主题聚合（LOCAL：透视计数占位，与计算器口径一致）
            stage(run.getId(), "BUILD_DWS", () ->
                    events.stream().filter(e -> EventContract.ORDER_PAID.equals(e.eventType()))
                            .map(e -> String.valueOf(e.payload().get("order_id"))).distinct().count());

            // ADS 指标计算（LOCAL：MetricCalculator；集群：spark-jobs usw/fna 产出）
            MetricDataset dataset = calculator.compute(events, businessDate);
            stage(run.getId(), "BUILD_ADS", () -> (long) dataset.metrics().size());

            // 质量门（核心规则失败 → 阻断发布，§5.4.1 对账性）
            QualityChecker.QualitySummary quality = qualityChecker.check(events, run.getId());
            quality.results().forEach(qualityMapper::insert);
            if (!quality.corePassed()) {
                throw new PipelineStageException("PIPELINE_QUALITY_FAILED", "金额对账未通过，新指标未发布");
            }
            stage(run.getId(), "QUALITY_CHECK", () -> (long) quality.results().size());

            // 快照发布（§21.11：BUILDING→VERIFYING→ACTIVE，旧 ACTIVE→ARCHIVED）
            snapshotId = createSnapshot(run.getId(), run.getBusinessTime(), businessDate, dataset);
            adsMaterializer.refreshActive(); // 刷新 AI 白名单物化 ADS（§8.1）
            stage(run.getId(), "PUBLISH_METRIC", () -> (long) dataset.metrics().size());

            run.setStatus(PipelineRun.STATUS_SUCCESS);
            runMapper.updateById(run);
        } catch (PipelineStageException e) {
            run.setStatus(PipelineRun.STATUS_FAILED);
            run.setErrorCode(e.code());
            runMapper.updateById(run);
            log.warn("pipeline {} failed: {}", run.getId(), e.getMessage());
        } catch (Exception e) {
            run.setStatus(PipelineRun.STATUS_FAILED);
            run.setErrorCode("RUN_INTERNAL");
            runMapper.updateById(run);
            log.error("pipeline {} internal error", run.getId(), e);
        }
        return assemble(run.getId(), snapshotId);
    }

    // ── 快照发布 ──────────────────────────────────────────────────────────

    private long createSnapshot(Long runId, LocalDateTime businessTime, String businessDate,
                                MetricDataset dataset) {
        String snapshotId = "S" + businessDate + "_" + runId;
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setSnapshotId(snapshotId);
        snapshot.setRuntimeProfileId(1L);
        snapshot.setBusinessTime(businessTime);
        snapshot.setPipelineRunId(runId);
        snapshot.setStatus(MetricSnapshot.STATUS_BUILDING);
        snapshot.setVersion(1);
        snapshot.setDataUpdatedAt(eventClock.nowLdt());
        snapshot.setSource("local-calculator");
        snapshot.setCreatedAt(eventClock.nowLdt());
        snapshotMapper.insert(snapshot);

        snapshot.setStatus(MetricSnapshot.STATUS_VERIFYING);
        snapshotMapper.updateById(snapshot);

        List<MetricValue> values = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> m : dataset.metrics().entrySet()) {
            MetricValue v = new MetricValue();
            v.setSnapshotId(snapshotId);
            v.setMetricCode(m.getKey());
            v.setMetricValue(m.getValue());
            v.setUnit(dataset.units().getOrDefault(m.getKey(), ""));
            v.setPeriod("day:" + businessDate);
            v.setDefinitionVersion("v1");
            values.add(v);
        }
        metricStore.publish(new MetricStore.SnapshotRef(snapshotId, 1L, "day:" + businessDate), values);
        return snapshot.getId();
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    private interface StageAction {
        long execute() throws PipelineStageException, IOException;
    }

    private void stage(Long runId, String stageCode, StageAction action) {
        PipelineStageRun s = new PipelineStageRun();
        s.setRunId(runId);
        s.setStageCode(stageCode);
        s.setStatus(PipelineStageRun.STATUS_RUNNING);
        s.setStartedAt(eventClock.nowLdt());
        stageMapper.insert(s);
        try {
            s.setRecords(action.execute());
            s.setStatus(PipelineStageRun.STATUS_SUCCESS);
        } catch (PipelineStageException e) {
            s.setStatus(PipelineStageRun.STATUS_FAILED);
            s.setErrorCode(e.code());
            throw e;
        } catch (Exception e) {
            s.setStatus(PipelineStageRun.STATUS_FAILED);
            s.setErrorCode("STAGE_INTERNAL");
            throw new PipelineStageException("STAGE_INTERNAL", e.getMessage());
        } finally {
            s.setFinishedAt(eventClock.nowLdt());
            stageMapper.updateById(s);
        }
    }

    private Path eventsDir() {
        return Path.of(environment.getProperty("mall.landing.path", "./landing")).resolve("events");
    }

    private static String buildKey(Long profileId, String code, LocalDateTime businessTime, String version) {
        return profileId + "|" + code + "|" + businessTime + "|" + (version == null ? "" : version);
    }

    private static boolean isBlank(Object v) {
        return v == null || String.valueOf(v).isBlank();
    }

    private RunResult assemble(Long runId, Long snapshotId) {
        PipelineRun run = runMapper.selectById(runId);
        List<PipelineStageRun> stages = stageMapper.selectList(new LambdaQueryWrapper<PipelineStageRun>()
                .eq(PipelineStageRun::getRunId, runId)
                .orderByAsc(PipelineStageRun::getId));
        return new RunResult(runId, run.getIdempotencyKey(), run.getStatus(),
                run.getErrorCode(), run.getAttemptNo(), snapshotId, stages);
    }

    /** 阶段失败（携带稳定错误码，§23.2） */
    public static class PipelineStageException extends RuntimeException {
        private final String code;

        public PipelineStageException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}