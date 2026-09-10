package com.graduation.analytics.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.contracts.EventContract;
import com.graduation.analytics.contracts.EventEnvelope;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.pipeline.entity.PipelineRun;
import com.graduation.analytics.pipeline.entity.PipelineStageRun;
import com.graduation.analytics.pipeline.mapper.DataQualityResultMapper;
import com.graduation.analytics.pipeline.mapper.PipelineRunMapper;
import com.graduation.analytics.pipeline.mapper.PipelineStageRunMapper;
import com.graduation.analytics.pipeline.spark.SparkStageExecutor;
import com.graduation.analytics.pipeline.spark.SparkStageExecutorFactory;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 流水线编排（§13.1：POST 立即返回 taskId+PENDING，线程池异步执行计算链）：
 * WAIT_LANDING→LOAD_ODS→BUILD_DWD→BUILD_DWS→BUILD_ADS→QUALITY_CHECK→PUBLISH_METRIC→SUCCESS。
 * 幂等键 = runtimeProfileId+pipelineCode+businessTime+sourceDataVersion（DB 唯一键
 * uk_idempotency 兜底 + 应用层按 key 加锁，§13.4）；同键返回原任务；失败后重试递增
 * attempt_no 且已成功阶段不重复执行（§13.4 恢复）；质量检查失败阻断发布（§5.4.1）。
 *
 * R6-11（V2.0 §15.3）：本服务只做**状态机与阶段编排**，不再解析 Landing JSON 计算指标。
 * 四个计算阶段（LOAD_ODS/BUILD_DWD/BUILD_DWS/BUILD_ADS）全部由 {@link SparkStageExecutor}
 * 提交真实 spark-jobs，输入/输出/隔离计数一律取 JobResult（§15.3 R6-12：禁止常量或输入数冒充输出），
 * 每个作业的 external_job_id / log_uri / arguments_json 落 spark_job_run（可溯源）。
 * 阶段失败立即把 run 置 FAILED，后续依赖阶段不再执行（§15.2 fail-fast）。
 * 本地 Java 只保留两处非计算职责：WAIT_LANDING 的 READY manifest 门（§9.3）与
 * QUALITY_CHECK 的质量门（§5.4.1，R6-13 升级为读 staging 结果）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** 幂等键 → 内存锁（防并发同键重复 insert；DB 唯一键兜底） */
    private final ConcurrentHashMap<String, Object> idempotencyLocks = new ConcurrentHashMap<>();

    private final PipelineRunMapper runMapper;
    private final PipelineStageRunMapper stageMapper;
    private final DataQualityResultMapper qualityMapper;
    private final QualityChecker qualityChecker;
    private final EventClock eventClock;
    private final ObjectMapper objectMapper;
    private final RuntimeProfileService runtimeProfileService;

    /** R6-11：真实 Spark 阶段执行器工厂（生产注入点；L1 测试注入 Fake） */
    private final SparkStageExecutorFactory stageExecutorFactory;

    /** R6（§13.1）：后台执行线程池（测试注入直接执行器验证状态机） */
    @org.springframework.beans.factory.annotation.Qualifier("pipelineExecutor")
    private final Executor pipelineExecutor;

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
        PipelineRun existing = selectByKey(key);
        if (existing != null) {
            return assemble(existing.getId(), null);
        }

        // 并发同键：内存锁 + 二次检查（DB 唯一键 uk_idempotency 最终兜底，§13.4）
        synchronized (idempotencyLocks.computeIfAbsent(key, k -> new Object())) {
            existing = selectByKey(key);
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
            run.setStatus(PipelineRun.STATUS_PENDING);
            run.setCurrentStage("PENDING");
            run.setTraceId(traceId);
            run.setCreatedAt(eventClock.nowLdt());
            run.setUpdatedAt(eventClock.nowLdt());
            try {
                runMapper.insert(run);
            } catch (DuplicateKeyException e) {
                // 并发下唯一键兜底命中：返回已存在的任务
                PipelineRun winner = selectByKey(key);
                if (winner != null) {
                    return assemble(winner.getId(), null);
                }
                throw e;
            }
            final Long runId = run.getId();
            // §13.1：立即返回 PENDING taskId，计算链异步执行
            pipelineExecutor.execute(() -> executeInBackground(runId));
            return assemble(runId, null);
        }
    }

    /**
     * 重试：仅失败状态允许，attempt_no 递增后重新执行（§23.1 恢复）。
     * 已成功阶段不重复——execute() 内按 SUCCESS 阶段记录跳过（§13.4）。
     */
    public RunResult retry(Long runId, String traceId) {
        PipelineRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("run 不存在: " + runId);
        }
        if (!PipelineRun.STATUS_FAILED.equals(run.getStatus())) {
            return assemble(runId, null);
        }
        run.setStatus(PipelineRun.STATUS_PENDING);
        run.setCurrentStage("PENDING");
        run.setErrorCode(null);
        run.setErrorMessage(null);
        run.setAttemptNo(run.getAttemptNo() + 1);
        run.setTraceId(traceId);
        run.setUpdatedAt(eventClock.nowLdt());
        runMapper.updateById(run);
        pipelineExecutor.execute(() -> executeInBackground(runId));
        return assemble(runId, null);
    }

    /** 只读查询（含阶段明细） */
    public RunResult get(Long runId) {
        return assemble(runId, null);
    }

    // ── 执行链（幂等检查之外，run()/retry() 共用的异步入口） ──────────────

    /** 后台异步执行：读取 run → 顺序执行七阶段 → 更新状态（§13.1 异步契约） */
    private void executeInBackground(Long runId) {
        PipelineRun run = runMapper.selectById(runId);
        if (run == null) {
            log.warn("pipeline async: run {} 不存在，跳过执行", runId);
            return;
        }
        execute(run);
    }

    private void execute(PipelineRun run) {
        try {
            String businessDate = run.getBusinessTime().toLocalDate().format(KEY_DATE);
            // §8.1/§15.3 R6-10：一次 run 冻结一份环境快照（提交器与命令均取自该快照）
            RuntimeProfile profile = runtimeProfileService.get(run.getRuntimeProfileId());
            RuntimeProfileSnapshot snapshot = RuntimeProfileSnapshot.from(profile);
            SparkStageExecutor executor = stageExecutorFactory.create(snapshot);
            Path landingRoot = parseLandingRoot(profile.getLandingUri());

            // §13.4 恢复：重试时已成功阶段不重复执行（阶段记录不再重复写入）
            Set<String> completedStages = stageMapper.selectList(new LambdaQueryWrapper<PipelineStageRun>()
                            .eq(PipelineStageRun::getRunId, run.getId())
                            .eq(PipelineStageRun::getStatus, PipelineStageRun.STATUS_SUCCESS))
                    .stream().map(PipelineStageRun::getStageCode).collect(Collectors.toSet());

            run.setStatus(PipelineRun.STATUS_RUNNING);
            run.setCurrentStage("WAIT_LANDING");
            if (run.getStartedAt() == null) {
                run.setStartedAt(eventClock.nowLdt());
            }
            run.setUpdatedAt(eventClock.nowLdt());
            runMapper.updateById(run);

            // ── 数据准备（幂等读：即使重试跳过成功阶段，后续阶段仍有上下文） ──
            // §9.3：只认 manifests/ 下状态为 READY 的批次清单，不再看 source/events 目录
            Map<String, Object> manifest = findReadyManifest(landingRoot);
            // §9.1：ODS 只能读取 accepted（好的批次数据）；§5.3.3 只装载业务日事件
            Path acceptedDir = manifest == null ? null
                    : landingRoot.resolve(String.valueOf(manifest.get("acceptedUri")));
            String datePrefix = businessDate.substring(0, 4) + "-" + businessDate.substring(4, 6)
                    + "-" + businessDate.substring(6, 8);
            List<EventEnvelope> events = new ArrayList<>();
            if (acceptedDir != null && Files.isDirectory(acceptedDir)) {
                readAcceptedEvents(acceptedDir, datePrefix, events);
            }
            // R6-11：本地 Java **不再**计算任何 ADS 指标（删除 MetricCalculator / local-calculator 路径）。
            // events 仅服务于两处非计算职责：LOAD_ODS 空数据预检（快速失败，避免白跑 Spark）
            // 与 QUALITY_CHECK 质量门（§5.4.1；R6-13 改为读 staging 结果）。

            // ── WAIT_LANDING（§9.3）：只认 READY manifest ─────────────────
            stage(run.getId(), "WAIT_LANDING", completedStages, () -> {
                if (manifest == null) {
                    throw new PipelineStageException("RUN_EMPTY_LANDING",
                            "landing/manifests 无 READY 批次清单（先执行采集并生成 manifest）");
                }
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("batchId", manifest.get("batchId"));
                evidence.put("acceptedUri", manifest.get("acceptedUri"));
                evidence.put("checksum", manifest.get("checksum"));
                evidence.put("acceptedRecords", manifest.get("acceptedRecords"));
                evidence.put("schemaVersions", manifest.get("schemaVersions"));
                return new StageOutcome(((Number) manifest.get("acceptedRecords")).longValue(), evidence);
            });

            // 业务日预检在 LOAD_ODS 阶段内执行：accepted 目录与业务日事件必须存在，
            // 否则给出稳定错误码（RUN_EMPTY_DATA），且失败必须留在阶段记录上（§23.2/§15.3 失败留痕）
            final Map<String, Object> manifestRef = manifest;
            final Path acceptedDirRef = acceptedDir;
            final List<EventEnvelope> eventsRef = events;

            // ── LOAD_ODS（§9.1）：真实 spark-jobs odl 装载四主题 ──────────
            Map<String, String> odsExtra = new LinkedHashMap<>();
            odsExtra.put("landingDir", acceptedDir == null ? "" : acceptedDir.toUri().toString());
            if (manifest != null && manifest.get("batchId") != null) {
                odsExtra.put("batchId", String.valueOf(manifest.get("batchId")));
            }
            StageOutcome odsOutcome = runSparkStage(run, executor, snapshot, "LOAD_ODS",
                    businessDate, completedStages, odsExtra, () -> {
                        if (manifestRef == null) {
                            throw new PipelineStageException("RUN_EMPTY_LANDING", "无 READY manifest");
                        }
                        if (acceptedDirRef == null || !Files.isDirectory(acceptedDirRef)) {
                            throw new PipelineStageException("RUN_LOAD_FAILED",
                                    "accepted 目录不存在: " + acceptedDirRef);
                        }
                        if (eventsRef.isEmpty()) {
                            throw new PipelineStageException("RUN_EMPTY_DATA", "accepted 无归属业务日事件");
                        }
                    });
            if (odsOutcome != null && !completedStages.contains("LOAD_ODS")) {
                // §10.3：ODS 输入/输出/隔离数 = JobResult 真实计数（R6-12：非 Java 侧估算）
                Map<String, Object> evidence = new LinkedHashMap<>(odsOutcome.evidence());
                evidence.put("contracted", "odl: input=Landing 行数, output=四主题写入行数, rejected=版本/主键非法");
                Number q = manifest == null ? null : (Number) manifest.get("quarantinedRecords");
                if (q != null) {
                    evidence.put("odsQuarantinedRecords", q.longValue());
                }
                updateStageEvidence(run.getId(), "LOAD_ODS", evidence);
            }

            // ── BUILD_DWD：真实 spark-jobs bdw（行为明细/拒绝）+ dim（维度）+ tdw（订单明细）──
            StageOutcome dwdOutcome = runSparkStage(run, executor, snapshot, "BUILD_DWD",
                    businessDate, completedStages, Map.of(), null);
            if (dwdOutcome != null && !completedStages.contains("BUILD_DWD")) {
                Map<String, Object> evidence = new LinkedHashMap<>(dwdOutcome.evidence());
                evidence.put("contracted", "bdw: event_id 重复→reject 表；dim: 维度最新快照；tdw: 订单/退款合并明细");
                updateStageEvidence(run.getId(), "BUILD_DWD", evidence);
            }

            // ── BUILD_DWS：真实 spark-jobs usw（7 张 DWS，观察期=业务日） ──
            StageOutcome dwsOutcome = runSparkStage(run, executor, snapshot, "BUILD_DWS",
                    businessDate, completedStages,
                    Map.of("periodStart", businessDate, "periodEnd", businessDate), null);
            if (dwsOutcome != null && !completedStages.contains("BUILD_DWS")) {
                Map<String, Object> evidence = new LinkedHashMap<>(dwsOutcome.evidence());
                evidence.put("contracted", "usw: 7 张 DWS（行为/漏斗/商品/交易/区域/用户周期）");
                updateStageEvidence(run.getId(), "BUILD_DWS", evidence);
            }

            // ── BUILD_ADS：真实 spark-jobs fna（8 张 ADS，观察期=业务日） ──
            StageOutcome adsOutcome = runSparkStage(run, executor, snapshot, "BUILD_ADS",
                    businessDate, completedStages,
                    Map.of("periodStart", businessDate, "periodEnd", businessDate, "topN", "50"), null);
            if (adsOutcome != null && !completedStages.contains("BUILD_ADS")) {
                Map<String, Object> evidence = new LinkedHashMap<>(adsOutcome.evidence());
                evidence.put("contracted", "fna: 8 张 ADS（大盘/趋势/漏斗/热度/转化/画像/质量）");
                updateStageEvidence(run.getId(), "BUILD_ADS", evidence);
            }

            // ── QUALITY_CHECK：质量门（核心规则失败 → 阻断发布，§5.4.1） ──
            stage(run.getId(), "QUALITY_CHECK", completedStages, () -> {
                QualityChecker.QualitySummary quality = qualityChecker.check(events, run.getId());
                quality.results().forEach(qualityMapper::insert);
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("rules", quality.results().stream().map(r -> Map.of(
                        "ruleCode", String.valueOf(r.getRuleCode()),
                        "checkCount", r.getCheckCount(),
                        "errorCount", r.getErrorCount(),
                        "passed", r.getPassed())).toList());
                evidence.put("corePassed", quality.corePassed());
                evidence.put("blocking", "AMOUNT_RECONCILE（支付金额 vs 订单总额）");
                if (!quality.corePassed()) {
                    // 失败证据必须先落库，再抛出阻断（否则失败原因丢失）
                    updateStageEvidence(run.getId(), "QUALITY_CHECK", evidence);
                    throw new PipelineStageException("PIPELINE_QUALITY_FAILED",
                            "金额对账未通过，新指标未发布");
                }
                return new StageOutcome(quality.results().size(), evidence);
            });

            // ── PUBLISH_METRIC：登记本次 ADS 快照（真实分区已由 fna 写出） ──
            // R6 阶段边界：Hive ADS 已由 BUILD_ADS 真实写入；Hive→MySQL staging/原子切换属 R7，
            // 此处**不发布 MySQL、不写本地计算指标**（§15.4：生产服务不得引用 MetricCalculator）。
            if (!completedStages.contains("PUBLISH_METRIC")) {
                String snapshotId = "S" + businessDate + "_" + run.getId();
                run.setTargetSnapshotId(snapshotId);
                runMapper.updateById(run);
                // 本次运行已产出证据则直接用；重试跳过 BUILD_ADS 时从阶段记录回读（§13.4 恢复）
                Map<String, Object> adsEvidence = adsOutcome != null
                        ? adsOutcome.evidence()
                        : stageEvidence(run.getId(), "BUILD_ADS");
                stage(run.getId(), "PUBLISH_METRIC", completedStages, () -> {
                    if (adsEvidence.isEmpty()) {
                        throw new PipelineStageException("RUN_PUBLISH_NO_ADS",
                                "缺少 BUILD_ADS 真实作业证据，拒绝登记快照");
                    }
                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("adsSnapshotId", snapshotId);
                    evidence.put("adsJobs", adsEvidence.get("jobs"));
                    evidence.put("adsOutputRecords", adsEvidence.get("totalOutputRecords"));
                    evidence.put("hiveAds", "已写入 dw_ads.*（BUILD_ADS JobResult 计数）");
                    evidence.put("mysqlPublish", "deferred-to-R7（Hive→MySQL staging→ACTIVE 原子切换）");
                    Object total = adsEvidence.get("totalOutputRecords");
                    return new StageOutcome(total instanceof Number n ? n.longValue() : 0L, evidence);
                });
            }

            run.setStatus(PipelineRun.STATUS_SUCCESS);
            run.setCurrentStage("SUCCESS");
            run.setFinishedAt(eventClock.nowLdt());
            runMapper.updateById(run);
        } catch (PipelineStageException e) {
            run.setStatus(PipelineRun.STATUS_FAILED);
            run.setErrorCode(e.code());
            run.setErrorMessage(e.getMessage());
            run.setCurrentStage("FAILED");
            run.setFinishedAt(eventClock.nowLdt());
            runMapper.updateById(run);
            log.warn("pipeline {} failed: {}", run.getId(), e.getMessage());
        } catch (Exception e) {
            run.setStatus(PipelineRun.STATUS_FAILED);
            run.setErrorCode("RUN_INTERNAL");
            run.setErrorMessage(e.getMessage());
            run.setCurrentStage("FAILED");
            run.setFinishedAt(eventClock.nowLdt());
            runMapper.updateById(run);
            log.error("pipeline {} internal error", run.getId(), e);
        }
    }

    // ── 真实 Spark 阶段 ────────────────────────────────────────────────────

    /**
     * 提交并等待一个由真实 Spark 作业承载的阶段（§15.3 R6-11）。
     * 失败即抛 {@link PipelineStageException}（stage 记录置 FAILED，run 置 FAILED，后续阶段不执行）。
     * 重试时若该阶段已成功，返回 null（调用方据 completedStages 跳过证据覆盖）。
     *
     * @param precheck 阶段内预检（如 LOAD_ODS 的 accepted/业务日事件检查）；null 表示无
     */
    private StageOutcome runSparkStage(PipelineRun run, SparkStageExecutor executor,
                                      RuntimeProfileSnapshot snapshot, String stageCode,
                                      String businessDate, Set<String> completedStages,
                                      Map<String, String> extraArgs, Precheck precheck) {
        if (completedStages.contains(stageCode)) {
            return null; // 重试跳过：成功阶段不重复执行（§13.4）
        }
        return stage(run.getId(), stageCode, completedStages, () -> {
            if (precheck != null) {
                precheck.verify();
            }
            SparkStageExecutor.StageExecution ex = executor.executeStage(snapshot, run.getId(),
                    stageCode, businessDate, run.getAttemptNo(), extraArgs, null);
            Map<String, Object> evidence = jobEvidence(ex);
            if (ex.failed()) {
                // 失败证据先落库（stage() 的 finally 写记录），再阻断：后续依赖阶段保持未执行
                return new StageOutcome(ex.totalOutputRecords(), evidence,
                        new PipelineStageException("RUN_JOB_FAILED",
                                stageCode + " 作业失败: " + ex.errorMessage()));
            }
            // 输出计数只取 JobResult（R6-12）
            return new StageOutcome(ex.totalOutputRecords(), evidence);
        });
    }

    /** 阶段内预检（在阶段记录内执行，失败同样留痕） */
    private interface Precheck {
        void verify() throws PipelineStageException;
    }

    /** 阶段作业证据：逐作业 externalJobId/计数/日志位置（§15.3 R6-12） */
    private Map<String, Object> jobEvidence(SparkStageExecutor.StageExecution ex) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (SparkStageExecutor.JobExecution j : ex.jobs()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("jobCode", j.jobCode());
            item.put("externalJobId", j.externalJobId());
            item.put("status", j.status());
            item.put("inputRecords", j.inputRecords());
            item.put("outputRecords", j.outputRecords());
            item.put("rejectedRecords", j.rejectedRecords());
            item.put("logUri", j.logUri());
            if (j.errorMessage() != null) {
                item.put("errorMessage", j.errorMessage());
            }
            jobs.add(item);
        }
        evidence.put("stageCode", ex.stageCode());
        evidence.put("jobs", jobs);
        evidence.put("totalInputRecords", ex.totalInputRecords());
        evidence.put("totalOutputRecords", ex.totalOutputRecords());
        evidence.put("totalRejectedRecords", ex.totalRejectedRecords());
        evidence.put("failed", ex.failed());
        return evidence;
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    /** 阶段产出：记录数 + 证据；可选延迟抛出（失败证据需先落库再阻断） */
    private record StageOutcome(long records, Map<String, Object> evidence,
                                PipelineStageException deferredFailure) {
        StageOutcome(long records, Map<String, Object> evidence) {
            this(records, evidence, null);
        }
    }

    private interface StageAction {
        StageOutcome execute() throws PipelineStageException, IOException;
    }

    /**
     * 阶段执行：已成功阶段在重试时跳过（不重复写记录，§13.4 恢复：成功且输出校验
     * 有效的阶段不重复执行；数据准备在 execute() 中幂等重读，不影响跳过后继阶段）。
     * 失败时先落 FAILED + 证据，再抛出（§15.3：失败留痕不得丢失）。
     */
    private StageOutcome stage(Long runId, String stageCode, Set<String> completedStages, StageAction action) {
        if (completedStages.contains(stageCode)) {
            log.info("pipeline {}: stage {} 已成功，重试跳过（不重复执行）", runId, stageCode);
            return null;
        }
        PipelineStageRun s = new PipelineStageRun();
        s.setRunId(runId);
        s.setStageCode(stageCode);
        s.setStatus(PipelineStageRun.STATUS_RUNNING);
        s.setStartedAt(eventClock.nowLdt());
        stageMapper.insert(s);
        StageOutcome outcome = null;
        PipelineStageException failure = null;
        try {
            outcome = action.execute();
            s.setRecords(outcome.records());
            if (outcome.evidence() != null) {
                s.setEvidence(toJson(outcome.evidence()));
            }
            if (outcome.deferredFailure() != null) {
                // 作业失败：证据已随记录落库，状态置 FAILED 并延后抛出
                failure = outcome.deferredFailure();
                s.setStatus(PipelineStageRun.STATUS_FAILED);
                s.setErrorCode(failure.code());
            } else {
                s.setStatus(PipelineStageRun.STATUS_SUCCESS);
            }
        } catch (PipelineStageException e) {
            failure = e;
            s.setStatus(PipelineStageRun.STATUS_FAILED);
            s.setErrorCode(e.code());
        } catch (Exception e) {
            failure = new PipelineStageException("STAGE_INTERNAL",
                    "阶段 " + stageCode + " 内部错误: " + e.getMessage());
            s.setStatus(PipelineStageRun.STATUS_FAILED);
            s.setErrorCode("STAGE_INTERNAL");
        } finally {
            s.setFinishedAt(eventClock.nowLdt());
            stageMapper.updateById(s);
        }
        if (failure != null) {
            throw failure;
        }
        return outcome;
    }

    /** 覆盖式更新阶段证据（阶段已 SUCCESS 后追加契约说明等） */
    private void updateStageEvidence(Long runId, String stageCode, Map<String, Object> evidence) {
        PipelineStageRun s = latestStage(runId, stageCode);
        if (s != null) {
            s.setEvidence(toJson(evidence));
            stageMapper.updateById(s);
        }
    }

    /** 读取阶段证据 JSON（重试路径下从库中恢复 BUILD_ADS 证据） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> stageEvidence(Long runId, String stageCode) {
        PipelineStageRun s = latestStage(runId, stageCode);
        if (s == null || s.getEvidence() == null || s.getEvidence().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(s.getEvidence(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("阶段证据解析失败 run={} stage={}: {}", runId, stageCode, e.getMessage());
            return Map.of();
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

    /** 幂等键查询（§13.4：同键返回原任务） */
    private PipelineRun selectByKey(String key) {
        return runMapper.selectOne(new LambdaQueryWrapper<PipelineRun>()
                .eq(PipelineRun::getIdempotencyKey, key));
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

    /** 阶段最近一条记录（证据更新用；重试跳过后取旧记录保持不变） */
    private PipelineStageRun latestStage(Long runId, String stageCode) {
        return stageMapper.selectOne(new LambdaQueryWrapper<PipelineStageRun>()
                .eq(PipelineStageRun::getRunId, runId)
                .eq(PipelineStageRun::getStageCode, stageCode)
                .orderByDesc(PipelineStageRun::getId).last("LIMIT 1"));
    }

    /** 读取 accepted 目录下补给业务日（datePrefix）的 jsonl 事件（§9.1 只读 accepted） */
    private void readAcceptedEvents(Path acceptedDir, String datePrefix, List<EventEnvelope> out) {
        try (Stream<Path> list = Files.list(acceptedDir)) {
            for (Path f : list.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        EventEnvelope envelope = EventEnvelope.fromJson(line, objectMapper);
                        if (envelope.eventTime() != null && envelope.eventTime().startsWith(datePrefix)) {
                            out.add(envelope);
                        }
                    } catch (Exception e) {
                        log.warn("pipeline skip bad line in {}: {}", f.getFileName(), e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("accepted 读取失败 {}: {}", acceptedDir, e.getMessage());
        }
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
