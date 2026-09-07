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
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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

    /** ODS 接受的事件类型白名单（与 spark-jobs OdsLoadSql.eventTypeToTable 口径一致，§10.2） */
    private static final Set<String> ODS_ACCEPTED_TYPES = Set.of(
            "user_created", "user_updated",
            "product_created", "product_updated", "product_status_changed", "inventory_changed",
            "behavior",
            "order_created", "order_cancelled", "order_paid",
            "refund_requested", "refund_completed");

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
    private final ObjectMapper objectMapper;
    private final RuntimeProfileService runtimeProfileService;

    public record RunResult(Long runId, String idempotencyKey, String status,
                            String errorCode, int attemptNo, Long snapshotId,
                            List<PipelineStageRun> stages) {
    }

    public RunResult run(Long runtimeProfileId, String pipelineCode, LocalDateTime businessTime,
                         String sourceDataVersion, String idempotencyKey, String traceId) {
        // §8.1：每次运行必须保存实际 runtime_profile_id + profile_version
        RuntimeProfile profile = runtimeProfileService.get(runtimeProfileId);
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
        run.setRuntimeProfileVersion(profile.getVersion());
        run.setPipelineCode(pipelineCode);
        run.setBusinessTime(businessTime);
        run.setSourceDataVersion(sourceDataVersion);
        run.setAttemptNo(1);
        run.setStatus(PipelineRun.STATUS_RUNNING);
        run.setTraceId(traceId);
        run.setCreatedAt(eventClock.nowLdt());
        run.setUpdatedAt(eventClock.nowLdt());
        run.setStartedAt(eventClock.nowLdt());
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
        run.setErrorMessage(null);
        run.setAttemptNo(run.getAttemptNo() + 1);
        run.setTraceId(traceId);
        run.setStartedAt(eventClock.nowLdt());
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
            // 流水线归属的落地根：取运行环境 landingUri（替代硬编码 ./landing，§8.1/§9.1）
            RuntimeProfile profile = runtimeProfileService.get(run.getRuntimeProfileId());
            Path landingRoot = parseLandingRoot(profile.getLandingUri());

            // WAIT_LANDING（§9.3）：只认 manifests/ 下状态为 READY 的批次清单，
            // 不再看 source/events 目录；证据 batchId/URI/checksum/records 落 stage.evidence（§13.2）
            AtomicReference<Map<String, Object>> manifestRef = new AtomicReference<>();
            stage(run.getId(), "WAIT_LANDING", () -> {
                Map<String, Object> manifest = findReadyManifest(landingRoot);
                if (manifest == null) {
                    throw new PipelineStageException("RUN_EMPTY_LANDING",
                            "landing/manifests 无 READY 批次清单（先执行采集并生成 manifest）");
                }
                manifestRef.set(manifest);
                return ((Number) manifest.get("acceptedRecords")).longValue();
            });
            if (manifestRef.get() != null) {
                PipelineStageRun waitStage = stageMapper.selectOne(new LambdaQueryWrapper<PipelineStageRun>()
                        .eq(PipelineStageRun::getRunId, run.getId())
                        .eq(PipelineStageRun::getStageCode, "WAIT_LANDING")
                        .orderByDesc(PipelineStageRun::getId).last("LIMIT 1"));
                if (waitStage != null) {
                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("batchId", manifestRef.get().get("batchId"));
                    evidence.put("acceptedUri", manifestRef.get().get("acceptedUri"));
                    evidence.put("checksum", manifestRef.get().get("checksum"));
                    evidence.put("acceptedRecords", manifestRef.get().get("acceptedRecords"));
                    evidence.put("schemaVersions", manifestRef.get().get("schemaVersions"));
                    waitStage.setEvidence(toJson(evidence));
                    stageMapper.updateById(waitStage);
                }
            }

            stage(run.getId(), "LOAD_ODS", () -> {
                // §9.1：ODS 只能读取 accepted（好的批次数据），禁止直接读 source/events
                Map<String, Object> manifest = manifestRef.get();
                if (manifest == null) {
                    throw new PipelineStageException("RUN_EMPTY_LANDING", "无 READY manifest");
                }
                Path acceptedDir = landingRoot.resolve(String.valueOf(manifest.get("acceptedUri")));
                if (!Files.isDirectory(acceptedDir)) {
                    throw new PipelineStageException("RUN_LOAD_FAILED", "accepted 目录不存在: " + acceptedDir);
                }
                // 只装载 businessTime 归属日的事件（补数/重跑按日隔离，§5.3.3）
                String datePrefix = businessDate.substring(0, 4) + "-" + businessDate.substring(4, 6)
                        + "-" + businessDate.substring(6, 8);
                try (Stream<Path> list = Files.list(acceptedDir)) {
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
                    throw new PipelineStageException("RUN_EMPTY_DATA", "accepted 无归属业务日事件");
                }
                return (long) events.size();
            });
            // §10.3：流水线详情展示 ODS 输入/输出/隔离数（与 EventOdsLoadJob 契约口径一致：
            // 接受=schema_version=1.0 且 event_id/event_type/event_time 非空且类型在合法集合）
            PipelineStageRun loadStage = stageMapper.selectOne(new LambdaQueryWrapper<PipelineStageRun>()
                    .eq(PipelineStageRun::getRunId, run.getId())
                    .eq(PipelineStageRun::getStageCode, "LOAD_ODS")
                    .orderByDesc(PipelineStageRun::getId).last("LIMIT 1"));
            if (loadStage != null) {
                long accepted = events.stream().filter(e -> "1.0".equals(e.schemaVersion())
                        && !isBlank(e.eventId()) && !isBlank(e.eventType()) && !isBlank(e.eventTime())
                        && ODS_ACCEPTED_TYPES.contains(e.eventType())).count();
                long rejected = events.size() - accepted;
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("odsInputRecords", (long) events.size());
                evidence.put("odsAcceptedRecords", accepted);
                evidence.put("odsRejectedRecords", rejected);
                // 采集层隔离（schema_version 不符等）来自 manifest，§9.3 quarantine
                Number q = (Number) manifestRef.get().get("quarantinedRecords");
                if (q != null) {
                    evidence.put("odsQuarantinedRecords", q.longValue());
                }
                evidence.put("contracted", "odl: total=input, output=accepted, quarantine=rejected (Spark 侧同口径)");
                loadStage.setEvidence(toJson(evidence));
                stageMapper.updateById(loadStage);
            }

            // DWD 清洗（LOCAL：契约级校验 + 去重计数；集群：spark-jobs bdw）
            stage(run.getId(), "BUILD_DWD", () -> {
                long bad = events.stream().filter(e ->
                        EventContract.BEHAVIOR.equals(e.eventType())
                                && (isBlank(e.payload().get("user_id")) || isBlank(e.payload().get("product_id"))))
                        .count();
                long total = events.size();
                long behaviorEvents = events.stream()
                        .filter(e -> EventContract.BEHAVIOR.equals(e.eventType())).count();
                long uniqueBehavior = events.stream()
                        .filter(e -> EventContract.BEHAVIOR.equals(e.eventType()))
                        .map(EventEnvelope::eventId).distinct().count();
                long duplicateRejected = behaviorEvents - uniqueBehavior;
                return total - bad - duplicateRejected;
            });
            // §11.4 第 1 条：行为事件 event_id 重复 → 1 有效行，重复进拒绝表（Java 侧计数口径）
            PipelineStageRun dwdStage = stageMapper.selectOne(new LambdaQueryWrapper<PipelineStageRun>()
                    .eq(PipelineStageRun::getRunId, run.getId())
                    .eq(PipelineStageRun::getStageCode, "BUILD_DWD")
                    .orderByDesc(PipelineStageRun::getId).last("LIMIT 1"));
            if (dwdStage != null) {
                long behaviorEvents = events.stream()
                        .filter(e -> EventContract.BEHAVIOR.equals(e.eventType())).count();
                long uniqueBehavior = events.stream()
                        .filter(e -> EventContract.BEHAVIOR.equals(e.eventType()))
                        .map(EventEnvelope::eventId).distinct().count();
                long contractBad = events.stream().filter(e ->
                        EventContract.BEHAVIOR.equals(e.eventType())
                                && (isBlank(e.payload().get("user_id")) || isBlank(e.payload().get("product_id"))))
                        .count();
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("dwdInputRecords", (long) events.size());
                evidence.put("behaviorEvents", behaviorEvents);
                evidence.put("behaviorUnique", uniqueBehavior);
                evidence.put("duplicateRejected", behaviorEvents - uniqueBehavior);
                evidence.put("contractBad", contractBad);
                evidence.put("contracted", "bdw: event_id 重复→reject 表，每重复组 1 条 DUPLICATE_EVENT (Spark 侧已验证 10→1)");
                dwdStage.setEvidence(toJson(evidence));
                stageMapper.updateById(dwdStage);
            }

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
            snapshotId = createSnapshot(run, dataset);
            adsMaterializer.refreshActive(); // 刷新 AI 白名单物化 ADS（§8.1）
            stage(run.getId(), "PUBLISH_METRIC", () -> (long) dataset.metrics().size());

            run.setStatus(PipelineRun.STATUS_SUCCESS);
            run.setCurrentStage("SUCCESS");
            run.setFinishedAt(eventClock.nowLdt());
            runMapper.updateById(run);
        } catch (PipelineStageException e) {
            run.setStatus(PipelineRun.STATUS_FAILED);
            run.setErrorCode(e.code());
            run.setErrorMessage(e.getMessage());
            run.setFinishedAt(eventClock.nowLdt());
            runMapper.updateById(run);
            log.warn("pipeline {} failed: {}", run.getId(), e.getMessage());
        } catch (Exception e) {
            run.setStatus(PipelineRun.STATUS_FAILED);
            run.setErrorCode("RUN_INTERNAL");
            run.setErrorMessage(e.getMessage());
            run.setFinishedAt(eventClock.nowLdt());
            runMapper.updateById(run);
            log.error("pipeline {} internal error", run.getId(), e);
        }
        return assemble(run.getId(), snapshotId);
    }

    // ── 快照发布 ──────────────────────────────────────────────────────────

    private long createSnapshot(PipelineRun run, MetricDataset dataset) {
        String businessDate = run.getBusinessTime().toLocalDate().format(KEY_DATE);
        String snapshotId = "S" + businessDate + "_" + run.getId();
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setSnapshotId(snapshotId);
        // §8.1：必须保存实际 runtime_profile_id + profile_version（替代早期硬编码 1L）
        snapshot.setRuntimeProfileId(run.getRuntimeProfileId());
        snapshot.setRuntimeProfileVersion(run.getRuntimeProfileVersion());
        snapshot.setBusinessTime(run.getBusinessTime());
        snapshot.setPipelineRunId(run.getId());
        snapshot.setStatus(MetricSnapshot.STATUS_BUILDING);
        snapshot.setVersion(1);
        snapshot.setDataUpdatedAt(eventClock.nowLdt());
        snapshot.setSource("local-calculator");
        snapshot.setCreatedAt(eventClock.nowLdt());
        snapshotMapper.insert(snapshot);

        snapshot.setStatus(MetricSnapshot.STATUS_VERIFYING);
        snapshotMapper.updateById(snapshot);

        run.setTargetSnapshotId(snapshotId);
        runMapper.updateById(run);

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
        metricStore.publish(new MetricStore.SnapshotRef(snapshotId, run.getRuntimeProfileId(), "day:" + businessDate), values);
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

    /**
     * 扫描 landing/manifests/*.json，返回状态为 READY 且含数据（accepted+quarantined>0）
     * 的最新批次清单（§9.3；空批次视为无新数据，不做 ODS 输入）。
     * 按清单内 batchId 取最大者视为最新；无可用清单返回 null。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findReadyManifest(Path landingRoot) {
        Path manifestsDir = landingRoot.resolve("manifests");
        if (!Files.isDirectory(manifestsDir)) {
            return null;
        }
        AtomicReference<Long> maxBatchId = new AtomicReference<>(null);
        AtomicReference<Map<String, Object>> best = new AtomicReference<>(null);
        try (Stream<Path> list = Files.list(manifestsDir)) {
            for (Path m : list.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    Map<String, Object> manifest = objectMapper.readValue(Files.readString(m, StandardCharsets.UTF_8),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                            });
                    if (!"READY".equals(manifest.get("status"))) {
                        continue;
                    }
                    long accepted = ((Number) manifest.get("acceptedRecords")).longValue();
                    long quarantined = ((Number) manifest.get("quarantinedRecords")).longValue();
                    if (accepted + quarantined <= 0) {
                        continue; // 空批次：无新数据，不阻塞也不作为输入（§9.3）
                    }
                    long batchId = ((Number) manifest.get("batchId")).longValue();
                    if (maxBatchId.get() == null || batchId > maxBatchId.get()) {
                        maxBatchId.set(batchId);
                        best.set(manifest);
                    }
                } catch (Exception e) {
                    log.warn("manifest 解析失败 {}: {}", m.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("manifests 扫描失败: {}", e.getMessage());
        }
        return best.get();
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    /** 解析 profile.landingUri（file:///D:/... 或 file://./landing）→ 本地 Path */
    static Path parseLandingRoot(String landingUri) {
        String p = landingUri == null ? "./landing" : landingUri.trim();
        if (p.startsWith("file:///")) {
            p = p.substring("file://".length());
        } else if (p.startsWith("file://")) {
            p = p.substring("file://".length());
        }
        return Paths.get(p).toAbsolutePath();
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