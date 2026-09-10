package com.graduation.analytics.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.contracts.EventContract;
import com.graduation.analytics.contracts.EventEnvelope;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.metric.dict.MetricDefinition;
import com.graduation.analytics.metric.dict.MetricDefinitionMapper;
import com.graduation.analytics.metric.publish.MetricPublisherPort;
import com.graduation.analytics.metric.publish.MetricPublisherPort.DefinitionRef;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishReport;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishRequest;
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
import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * R7-3（§17.4/§17.5）：指标快照发布端口。流水线只依赖 platform-common 里的契约，
     * 实现在 metric-analysis（避免 warehouse-pipeline → metric-analysis 的模块环）。
     */
    private final MetricPublisherPort metricPublisher;

    /** R7-3：发布前读指标字典做「指标码 + 口径版本」对账（字典属 analytics_meta，§17.2） */
    private final MetricDefinitionMapper metricDefinitionMapper;

    /** R7-3：Spark `mxp` 导出目录根（清单 + 各表 JSONL），可配置便于运维定位 */
    @org.springframework.beans.factory.annotation.Value("${platform.metric.publish.export-dir:metric-staging}")
    private String metricExportRoot = "metric-staging";

    public record RunResult(Long runId, String idempotencyKey, String status, String currentStage,
                            String errorCode, int attemptNo, String targetSnapshotId,
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
            return assemble(existing.getId());
        }

        // 并发同键：内存锁 + 二次检查（DB 唯一键 uk_idempotency 最终兜底，§13.4）
        synchronized (idempotencyLocks.computeIfAbsent(key, k -> new Object())) {
            existing = selectByKey(key);
            if (existing != null) {
                return assemble(existing.getId());
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
                    return assemble(winner.getId());
                }
                throw e;
            }
            final Long runId = run.getId();
            // §13.1：立即返回 PENDING taskId，计算链异步执行
            pipelineExecutor.execute(() -> executeInBackground(runId));
            return assemble(runId);
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
            return assemble(runId);
        }
        run.setStatus(PipelineRun.STATUS_PENDING);
        run.setCurrentStage("PENDING");
        run.setErrorCode(null);
        run.setErrorMessage(null);
        run.setAttemptNo(run.getAttemptNo() + 1);
        run.setTraceId(traceId);
        run.setUpdatedAt(eventClock.nowLdt());
        runMapper.updateById(run);
        clearRunError(run);
        pipelineExecutor.execute(() -> executeInBackground(runId));
        return assemble(runId);
    }

    /** 只读查询（含阶段明细） */
    public RunResult get(Long runId) {
        return assemble(runId);
    }

    // ── R6-14（§23.1）管理员恢复动作：resume / mark-failed / retry-from-stage ──────
    // 三者都必须记录操作者与原因（审计），且都复用同一 snapshotId，保证暂存与发布幂等。

    /** 阶段权威顺序（retry-from-stage 需要知道"其后"包含哪些阶段） */
    public static final List<String> STAGE_ORDER = List.of(
            "WAIT_LANDING", "INIT_SCHEMA", "LOAD_ODS", "BUILD_DWD", "BUILD_DWS",
            "BUILD_ADS", "QUALITY_CHECK", "PUBLISH_METRIC");

    /**
     * 恢复执行：把被中断/待执行/失败的 run 重新排队（§23.1）。
     * SUCCESS 阶段按阶段记录跳过，不重复计算；snapshotId 复用，暂存写入与发布保持幂等。
     * RUNNING 状态**拒绝**（无法证明执行线程已死，直接 resume 会双跑）——由启动对账或 mark-failed 先判定中断。
     */
    public RunResult resume(Long runId, String operator, String reason, String traceId) {
        PipelineRun run = requireRun(runId);
        if (PipelineRun.STATUS_SUCCESS.equals(run.getStatus())) {
            return assemble(runId);
        }
        if (PipelineRun.STATUS_RUNNING.equals(run.getStatus())) {
            throw new IllegalArgumentException("run " + runId
                    + " 仍为 RUNNING：先由启动对账或 mark-failed 判定中断，再 resume（避免双跑）");
        }
        audit(runId, "RESUME", operator, reason);
        run.setStatus(PipelineRun.STATUS_PENDING);
        run.setCurrentStage("PENDING");
        run.setAttemptNo(run.getAttemptNo() + 1);
        run.setTraceId(traceId);
        run.setFinishedAt(null);
        run.setUpdatedAt(eventClock.nowLdt());
        runMapper.updateById(run);
        // finished_at 与错误字段一样受 NOT_NULL 策略影响，需显式清空
        runMapper.update(null, new UpdateWrapper<PipelineRun>()
                .eq("id", runId)
                .set("finished_at", null));
        clearRunError(run);
        pipelineExecutor.execute(() -> executeInBackground(runId));
        log.info("pipeline {} resumed by {} ({}) attempt={}", runId, operator, reason, run.getAttemptNo());
        return assemble(runId);
    }

    /** 管理员判定失败（§23.1）：写入稳定错误码与操作者/原因，供运维页与审计追溯 */
    public RunResult markFailed(Long runId, String operator, String reason) {
        PipelineRun run = requireRun(runId);
        if (PipelineRun.STATUS_SUCCESS.equals(run.getStatus())) {
            throw new IllegalArgumentException("run " + runId + " 已 SUCCESS，不可标记失败");
        }
        audit(runId, "MARK_FAILED", operator, reason);
        run.setStatus(PipelineRun.STATUS_FAILED);
        run.setCurrentStage("FAILED");
        run.setErrorCode("ADMIN_MARKED_FAILED");
        run.setErrorMessage("管理员标记失败 operator=" + operator + " reason=" + reason);
        run.setFinishedAt(eventClock.nowLdt());
        run.setUpdatedAt(eventClock.nowLdt());
        runMapper.updateById(run);
        log.warn("pipeline {} marked FAILED by {} ({})", runId, operator, reason);
        return assemble(runId);
    }

    /**
     * 从指定阶段起重跑（§23.1）：删除该阶段及其之后的阶段记录，使 execute() 重新执行它们，
     * 再按 resume 排队。用于"失败阶段之前的数据可信、只需尾段重算"的场景。
     */
    public RunResult retryFromStage(Long runId, String stageCode, String operator, String reason,
                                    String traceId) {
        requireRun(runId);
        int idx = STAGE_ORDER.indexOf(stageCode);
        if (idx < 0) {
            throw new IllegalArgumentException("未知阶段: " + stageCode + "，可选 " + STAGE_ORDER);
        }
        List<String> from = STAGE_ORDER.subList(idx, STAGE_ORDER.size());
        stageMapper.delete(new LambdaQueryWrapper<PipelineStageRun>()
                .eq(PipelineStageRun::getRunId, runId)
                .in(PipelineStageRun::getStageCode, from));
        audit(runId, "RETRY_FROM_STAGE:" + stageCode, operator, reason);
        PipelineRun run = requireRun(runId);
        run.setStatus(PipelineRun.STATUS_FAILED);
        run.setErrorCode("ADMIN_RETRY_FROM_STAGE");
        run.setErrorMessage("管理员从 " + stageCode + " 起重跑 operator=" + operator + " reason=" + reason);
        runMapper.updateById(run);
        log.warn("pipeline {} retry-from-stage {} by {} ({})", runId, stageCode, operator, reason);
        return resume(runId, operator, "retry-from-stage " + stageCode, traceId);
    }

    /**
     * 审计：把恢复动作写入 run **最早**一条阶段记录（WAIT_LANDING）的证据。
     * R6-13 修正：原实现写"最近一条"，而 retry-from-stage 会删除失败阶段及其之后的记录
     * （实测 run 18：mark-failed 的审计行随 QUALITY_CHECK 记录被删而丢失），
     * §23.1 要求管理员动作的操作者+原因必须可追溯 → 改写耐久的最早阶段记录。
     * 无阶段记录（例如从 WAIT_LANDING 起重跑）时忽略。
     */
    private void audit(Long runId, String action, String operator, String reason) {
        PipelineStageRun latest = stageMapper.selectOne(new LambdaQueryWrapper<PipelineStageRun>()
                .eq(PipelineStageRun::getRunId, runId)
                .orderByAsc(PipelineStageRun::getId).last("LIMIT 1"));
        if (latest == null) {
            return;
        }
        String note = "[RECOVERY] action=" + action + " operator=" + operator
                + " reason=" + reason + " at=" + eventClock.nowLdt();
        String merged = latest.getEvidence() == null || latest.getEvidence().isBlank()
                ? note : cap(latest.getEvidence() + " | " + note, 4000);
        stageMapper.update(null, new UpdateWrapper<PipelineStageRun>()
                .eq("id", latest.getId())
                .set("evidence", merged));
    }

    private PipelineRun requireRun(Long runId) {
        PipelineRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("run 不存在: " + runId);
        }
        return run;
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

            // §14.4 快照号：一次 run 一个 snapshotId，**重试复用**（保证暂存分区与发布幂等，
            // 重试不会产生第二份数据，也不会把上一次的暂存结果误当本次）。生成即落库可追溯。
            String snapshotId = run.getTargetSnapshotId();
            if (snapshotId == null || snapshotId.isBlank()) {
                snapshotId = "S" + businessDate + "_" + run.getId();
                run.setTargetSnapshotId(snapshotId);
                runMapper.updateById(run);
            }

            // ── 数据准备（幂等读：即使重试跳过成功阶段，后续阶段仍有上下文） ──
            // §9.3：只认 manifests/ 下状态为 READY 的批次清单，不再看 source/events 目录
            // §13.4/§23.1：重试/恢复必须钉住**本 run 原有批次**（见 manifestForRun）
            Map<String, Object> manifest = manifestForRun(landingRoot, run.getId());
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

            // 每次提交都带显式 warehouse/metastore 配置（LOCAL 嵌入式 Hive 不能按 spark-submit CWD 漂移）
            final Map<String, String> confs = stageExecutorFactory.confsFor(snapshot);

            // ── INIT_SCHEMA（§14.1）：先自举四层库表（sci 幂等 CREATE IF NOT EXISTS）──
            StageOutcome initOutcome = runSparkStage(run, executor, snapshot, "INIT_SCHEMA",
                    businessDate, completedStages, Map.of(), null, confs);
            if (initOutcome != null && !completedStages.contains("INIT_SCHEMA")) {
                Map<String, Object> evidence = new LinkedHashMap<>(initOutcome.evidence());
                evidence.put("contracted", "sci: CREATE DATABASE/TABLE IF NOT EXISTS（四层库表自举，可重复执行）");
                updateStageEvidence(run.getId(), "INIT_SCHEMA", evidence);
            }

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
                    }, confs);
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
                    businessDate, completedStages, Map.of(), null, confs);
            if (dwdOutcome != null && !completedStages.contains("BUILD_DWD")) {
                Map<String, Object> evidence = new LinkedHashMap<>(dwdOutcome.evidence());
                evidence.put("contracted", "bdw: event_id 重复→reject 表；dim: 维度最新快照；tdw: 订单/退款合并明细");
                updateStageEvidence(run.getId(), "BUILD_DWD", evidence);
            }

            // ── BUILD_DWS：真实 spark-jobs usw（7 张 DWS，观察期=业务日） ──
            StageOutcome dwsOutcome = runSparkStage(run, executor, snapshot, "BUILD_DWS",
                    businessDate, completedStages,
                    Map.of("periodStart", businessDate, "periodEnd", businessDate), null, confs);
            if (dwsOutcome != null && !completedStages.contains("BUILD_DWS")) {
                Map<String, Object> evidence = new LinkedHashMap<>(dwsOutcome.evidence());
                evidence.put("contracted", "usw: 7 张 DWS（行为/漏斗/商品/交易/区域/用户周期）");
                updateStageEvidence(run.getId(), "BUILD_DWS", evidence);
            }

            // ── BUILD_ADS：真实 spark-jobs fna（8 张 ADS，R6-13 只写**暂存分区**） ──
            // §14.4：ADS 先写 {table}__staging/snapshot_id=S/dt=D，正式分区由 PUBLISH_METRIC 发布。
            StageOutcome adsOutcome = runSparkStage(run, executor, snapshot, "BUILD_ADS",
                    businessDate, completedStages,
                    Map.of("periodStart", businessDate, "periodEnd", businessDate, "topN", "50",
                            "outputSnapshotId", snapshotId), null, confs);
            if (adsOutcome != null && !completedStages.contains("BUILD_ADS")) {
                Map<String, Object> evidence = new LinkedHashMap<>(adsOutcome.evidence());
                evidence.put("contracted", "fna: 8 张 ADS 写入暂存分区（snapshot_id=" + snapshotId + "）");
                evidence.put("stagingSnapshotId", snapshotId);
                updateStageEvidence(run.getId(), "BUILD_ADS", evidence);
            }

            // ── QUALITY_CHECK：① Landing/DWD 层内联规则（Java，快速失败）② ADS 暂存层质量门（真实作业 dqc） ──
            final String snapshotIdRef = snapshotId;
            stage(run.getId(), "QUALITY_CHECK", completedStages, () -> {
                Map<String, Object> evidence = new LinkedHashMap<>();
                // ① 内联规则（§5.4.1）：金额对账阻断，空值率/枚举白名单/event_id 唯一为记录项
                QualityChecker.QualitySummary quality = qualityChecker.check(events, run.getId());
                persistQuality(run.getId(), snapshotIdRef, "LANDING", quality.results());
                evidence.put("landingRules", quality.results().stream().map(r -> Map.of(
                        "ruleCode", String.valueOf(r.getRuleCode()),
                        "checkCount", r.getCheckCount(),
                        "errorCount", r.getErrorCount(),
                        "passed", r.getPassed())).toList());
                evidence.put("landingCorePassed", quality.corePassed());
                evidence.put("blocking", "AMOUNT_RECONCILE（支付金额 vs 订单总额）");
                if (!quality.corePassed()) {
                    // 失败证据必须先落库，再抛出阻断（否则失败原因丢失）
                    evidence.put("published", false);
                    updateStageEvidence(run.getId(), "QUALITY_CHECK", evidence);
                    throw new PipelineStageException("PIPELINE_QUALITY_FAILED",
                            "金额对账未通过，正式分区未发布");
                }
                // ② ADS 暂存层质量门：读 staging 结果 + DWS 对账，任一 BLOCKING 未过 → 阶段失败、不发布
                SparkStageExecutor.StageExecution ex = executor.executeStage(snapshot, run.getId(),
                        "QUALITY_CHECK", businessDate, run.getAttemptNo(),
                        Map.of("outputSnapshotId", snapshotIdRef), confs);
                evidence.putAll(jobEvidence(ex));
                evidence.put("adsChecksPersisted", persistChecks(run.getId(), snapshotIdRef, ex.checks()));
                evidence.put("adsChecks", ex.checks().stream().map(c -> Map.of(
                        "ruleCode", c.ruleCode(), "layer", c.layer(), "severity", c.severity(),
                        "checkCount", c.checkCount(), "errorCount", c.errorCount(),
                        "passed", c.passed())).toList());
                evidence.put("published", false);
                if (ex.failed()) {
                    updateStageEvidence(run.getId(), "QUALITY_CHECK", evidence);
                    throw new PipelineStageException("PIPELINE_QUALITY_FAILED",
                            "ADS 暂存质量门未通过，正式分区未发布: " + ex.errorMessage());
                }
                evidence.put("published", true);
                return new StageOutcome(ex.totalOutputRecords(), evidence);
            });

            // ── PUBLISH_METRIC：真实作业 pub 发布正式分区（Hive 元数据指针）+ mxp 导出 → 指标库发布 ──
            if (!completedStages.contains("PUBLISH_METRIC")) {
                Map<String, Object> adsEvidence = adsOutcome != null
                        ? adsOutcome.evidence()
                        : stageEvidence(run.getId(), "BUILD_ADS");
                stage(run.getId(), "PUBLISH_METRIC", completedStages, () -> {
                    if (adsEvidence.isEmpty()) {
                        throw new PipelineStageException("RUN_PUBLISH_NO_ADS",
                                "缺少 BUILD_ADS 真实作业证据，拒绝发布");
                    }
                    Path exportDir = Paths.get(metricExportRoot).toAbsolutePath().resolve(snapshotIdRef);
                    Map<String, String> publishArgs = new LinkedHashMap<>();
                    publishArgs.put("outputSnapshotId", snapshotIdRef);
                    publishArgs.put("exportDir", exportDir.toString());
                    SparkStageExecutor.StageExecution ex = executor.executeStage(snapshot, run.getId(),
                            "PUBLISH_METRIC", businessDate, run.getAttemptNo(),
                            publishArgs, confs);
                    Map<String, Object> evidence = jobEvidence(ex);
                    evidence.put("adsSnapshotId", snapshotIdRef);
                    evidence.put("adsJobs", adsEvidence.get("jobs"));
                    evidence.put("hiveAds", "正式分区以 Hive 元数据指针指向本次暂存路径（§14.4）");
                    evidence.put("adsChecksPersisted", persistChecks(run.getId(), snapshotIdRef, ex.checks()));
                    evidence.put("adsChecks", ex.checks().stream().map(c -> Map.of(
                            "ruleCode", c.ruleCode(), "severity", c.severity(),
                            "passed", c.passed(), "detail", c.detail())).toList());
                    if (ex.failed()) {
                        return new StageOutcome(ex.totalOutputRecords(), evidence,
                                new PipelineStageException("RUN_PUBLISH_FAILED",
                                        "正式分区发布失败: " + ex.errorMessage()));
                    }

                    // ── R7-3：Hive 正式分区已发布 → 指标库 ADS→MySQL 写入 + 快照 ACTIVE 原子切换 ──
                    PublishReport report = metricPublisher.publish(new PublishRequest(
                            snapshot.id(), snapshot.version(), snapshotIdRef, businessDate,
                            run.getBusinessTime().toString(), run.getId(), exportDir, metricDefinitions()));
                    evidence.put("metricPublish", Map.of(
                            "ok", report.ok(),
                            "errorCode", String.valueOf(report.errorCode()),
                            "message", String.valueOf(report.message()),
                            "snapshotId", String.valueOf(report.snapshotId()),
                            "adsRows", report.adsRows(),
                            "metricValues", report.metricValues()));
                    evidence.put("metricPublishEvidence", report.evidence());
                    evidence.put("metricPublishChecks", report.checks().stream().map(c -> Map.of(
                            "ruleCode", c.ruleCode(), "severity", c.severity(),
                            "passed", c.passed(), "detail", c.detail())).toList());
                    if (!report.ok()) {
                        throw new PipelineStageException("RUN_METRIC_PUBLISH_FAILED",
                                "指标库发布失败[" + report.errorCode() + "]: " + report.message());
                    }
                    evidence.put("metricExportDir", exportDir.toString());
                    return new StageOutcome(ex.totalOutputRecords(), evidence);
                });
            }

            run.setStatus(PipelineRun.STATUS_SUCCESS);
            run.setCurrentStage("SUCCESS");
            run.setFinishedAt(eventClock.nowLdt());
            runMapper.updateById(run);
            // 重试成功的 run 不能残留上一次失败的错误信息（SUCCESS 与 error 并存会误导运维页）
            clearRunError(run);
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
     * @param confs    Spark --conf（warehouse/metastore，LOCAL 必须显式指定，见 SparkStageExecutorFactory）
     */
    private StageOutcome runSparkStage(PipelineRun run, SparkStageExecutor executor,
                                      RuntimeProfileSnapshot snapshot, String stageCode,
                                      String businessDate, Set<String> completedStages,
                                      Map<String, String> extraArgs, Precheck precheck,
                                      Map<String, String> confs) {
        if (completedStages.contains(stageCode)) {
            return null; // 重试跳过：成功阶段不重复执行（§13.4）
        }
        return stage(run.getId(), stageCode, completedStages, () -> {
            if (precheck != null) {
                precheck.verify();
            }
            SparkStageExecutor.StageExecution ex = executor.executeStage(snapshot, run.getId(),
                    stageCode, businessDate, run.getAttemptNo(), extraArgs, confs);
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

    /**
     * 显式清空 run 级错误字段。MyBatis-Plus updateById 默认跳过 null 字段（FieldStrategy.NOT_NULL），
     * 只 setErrorCode(null) 无法把库里已有错误清掉 —— 必须用 UpdateWrapper 显式 set null。
     * 实测触发：run 16 attempt1 失败 → attempt2 成功后 error_code 仍残留 STAGE_INTERNAL。
     */
    /**
     * R7-3：读指标字典（analytics_meta.metric_definition）→ metric_code 到「口径版本 + 单位」的映射。
     *
     * <p>发布器只允许写字典内的指标码，并逐码核对 definition_version（§17.5 版本对账）；
     * 字典为空/缺码时发布校验会以 BLOCKING 拦下整个发布，不会"少写几个指标也算成功"。</p>
     */
    private Map<String, DefinitionRef> metricDefinitions() {
        Map<String, DefinitionRef> refs = new LinkedHashMap<>();
        List<MetricDefinition> definitions = metricDefinitionMapper.selectList(null);
        for (MetricDefinition d : definitions) {
            refs.put(d.getMetricCode(), new DefinitionRef(
                    d.getDefinitionVersion() == null ? "" : d.getDefinitionVersion(),
                    d.getUnit() == null ? "" : d.getUnit()));
        }
        return refs;
    }

    private void clearRunError(PipelineRun run) {
        runMapper.update(null, new UpdateWrapper<PipelineRun>()
                .eq("id", run.getId())
                .set("error_code", null)
                .set("error_message", null));
        run.setErrorCode(null);
        run.setErrorMessage(null);
    }

    /**
     * R6-13：落 Landing 层内联质量规则结果（§16.1 layer=LANDING，§16.5 运维页字段）。
     * 严重度按规则语义标注：AMOUNT_RECONCILE 为阻断项，其余为记录项（与 QualityChecker.corePassed 一致）。
     */
    private void persistQuality(Long runId, String snapshotId, String layer,
                               List<DataQualityResult> results) {
        for (DataQualityResult r : results) {
            r.setRunId(runId);
            r.setLayer(cap(layer, 32));
            r.setSeverity("AMOUNT_RECONCILE".equals(String.valueOf(r.getRuleCode())) ? "BLOCKING" : "ERROR");
            r.setTargetTable(cap("landing/events", 500));
            r.setSnapshotId(cap(snapshotId, 64));
            r.setDetail(cap(r.getDetail(), 2000));
            if (r.getErrorRate() == null) {
                r.setErrorRate(r.getCheckCount() != null && r.getCheckCount() > 0
                        ? BigDecimal.valueOf(r.getErrorCount())
                            .divide(BigDecimal.valueOf(r.getCheckCount()), 6, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO);
            }
            qualityMapper.insert(r);
        }
    }

    /**
     * R6-13：落 Spark 作业回传的质量检查结果（dqc 的 ADS_STAGING/PUBLISH 层规则、pub 的发布校验）。
     * severity=INFO 的是发布操作审计项（切换/清理计数），只进阶段证据，不冒充质量规则写库。
     *
     * @return 实际写库的规则条数
     */
    private int persistChecks(Long runId, String snapshotId, List<JobResultParser.CheckInfo> checks) {
        int inserted = 0;
        for (JobResultParser.CheckInfo c : checks) {
            if ("INFO".equalsIgnoreCase(c.severity())) {
                continue;
            }
            DataQualityResult r = new DataQualityResult();
            r.setRunId(runId);
            r.setRuleCode(cap(c.ruleCode(), 64));
            r.setLayer(cap(c.layer(), 32));
            r.setSeverity(cap(c.severity(), 16));
            r.setTargetTable(cap(c.targetTable(), 500));
            r.setSnapshotId(cap(snapshotId, 64));
            r.setCheckCount(c.checkCount());
            r.setErrorCount(c.errorCount());
            r.setThreshold(cap(c.threshold(), 64));
            r.setPassed(c.passed() ? 1 : 0);
            // error_rate 为 NOT NULL：checkCount=0 时记 0（不能留 null，否则插入被 DB 拒绝）
            r.setErrorRate(c.checkCount() > 0
                    ? BigDecimal.valueOf(c.errorCount())
                        .divide(BigDecimal.valueOf(c.checkCount()), 6, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            r.setDetail(cap(c.detail(), 2000));
            r.setCreatedAt(eventClock.nowLdt());
            qualityMapper.insert(r);
            inserted++;
        }
        return inserted;
    }

    /** 列宽保护：超长即截断（规则明细的完整内容仍在阶段证据 JSON 中，不丢证据） */
    private static String cap(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 3) + "...";
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
            // R6-12：真实输出分区证据（表/dt/snapshotId/行数/路径），空则不写（不造数）
            if (j.outputPartitions() != null && !j.outputPartitions().isEmpty()) {
                item.put("outputPartitions", j.outputPartitions());
                item.put("outputPartitionCount", j.outputPartitions().size());
                item.put("outputPartitionRows", j.outputPartitions().stream()
                        .mapToLong(JobResultParser.OutputPartitionInfo::rowCount).sum());
            }
            // R6-13：质量检查结果逐作业入证据（含 severity=INFO 的发布操作审计项）
            if (j.checks() != null && !j.checks().isEmpty()) {
                item.put("checks", j.checks().stream().map(c -> {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("ruleCode", c.ruleCode());
                    cm.put("layer", c.layer());
                    cm.put("severity", c.severity());
                    cm.put("targetTable", c.targetTable());
                    cm.put("checkCount", c.checkCount());
                    cm.put("errorCount", c.errorCount());
                    cm.put("threshold", c.threshold());
                    cm.put("passed", c.passed());
                    cm.put("detail", c.detail());
                    return cm;
                }).toList());
                item.put("blockingFailed", j.checks().stream()
                        .filter(c -> c.blocking() && !c.passed()).map(JobResultParser.CheckInfo::ruleCode).toList());
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
        // §16.5 运维页/轮询要能读到真实进度：旧实现只在 execute() 开头写一次 current_stage，
        // 之后永不前进（轮询永远看到 WAIT_LANDING）。这里在每个阶段真正开始时推进一次。
        runMapper.update(null, new UpdateWrapper<PipelineRun>()
                .eq("id", runId)
                .set("current_stage", stageCode)
                .set("updated_at", eventClock.nowLdt()));
        StageOutcome outcome = null;
        PipelineStageException failure = null;
        try {
            outcome = action.execute();
            s.setRecords(outcome.records());
            if (outcome.evidence() != null) {
                s.setEvidence(evidenceJson(outcome.evidence()));
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
            s.setEvidence(evidenceJson(evidence));
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
     * 本 run 的输入批次：重试/恢复时**钉住**原批次（WAIT_LANDING 证据里的 batchId），
     * 只有首跑（尚无 WAIT_LANDING 记录）才取"最新 READY 批次"。
     * R6-13 修正：原实现每次都取最新 READY，重试时会换输入（实测 run 18 retry：篡改批次 19
     * 被后来的干净批次 20 顶掉，同一 run 的 Landing 对账门从 FAILED 变 passed → 判定不可复现）。
     */
    private Map<String, Object> manifestForRun(Path landingRoot, Long runId) {
        PipelineStageRun landing = stageMapper.selectOne(new LambdaQueryWrapper<PipelineStageRun>()
                .eq(PipelineStageRun::getRunId, runId)
                .eq(PipelineStageRun::getStageCode, "WAIT_LANDING")
                .orderByAsc(PipelineStageRun::getId).last("LIMIT 1"));
        if (landing != null && landing.getEvidence() != null) {
            Matcher m = Pattern.compile("\"batchId\"\\s*:\\s*(\\d+)").matcher(landing.getEvidence());
            if (m.find()) {
                Map<String, Object> pinned = readManifest(
                        landingRoot.resolve("manifests").resolve(m.group(1) + ".json"));
                if (pinned != null) {
                    log.info("pipeline {}: 复用本 run 原批次 batchId={}（重试/恢复不切换输入）",
                            runId, m.group(1));
                    return pinned;
                }
            }
        }
        return findReadyManifest(landingRoot);
    }

    /** 读取单个 manifest JSON（失败返回 null，不抛异常） */
    private Map<String, Object> readManifest(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("manifest 解析失败 {}: {}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * 扫描 landing/manifests/*.json，返回状态为 READY 且含数据（accepted+quarantined>0）
     * 的最新批次清单（§9.3；空批次视为无新数据，不做 ODS 输入）。
     * 按清单内 batchId 取最大者视为最新；无可用清单返回 null。
     */
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
                    Map<String, Object> manifest = readManifest(m);
                    if (manifest == null || !"READY".equals(manifest.get("status"))) {
                        continue;
                    }
                    // R6-13 修正：老批次清单里计数是字符串（实测 2.json/4.json/5.json 报
                    // "class java.lang.String cannot be cast to class java.lang.Number"），
                    // 按数字/字符串双兼容解析，避免整条清单被当作损坏而跳过。
                    long accepted = longOf(manifest.get("acceptedRecords"));
                    long quarantined = longOf(manifest.get("quarantinedRecords"));
                    if (accepted + quarantined <= 0) {
                        continue; // 空批次：无新数据，不阻塞也不作为输入（§9.3）
                    }
                    long batchId = longOf(manifest.get("batchId"));
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

    /** 宽松取长整型：Number 直接用，字符串/空值按解析（老清单兼容） */
    private static long longOf(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    /**
     * 阶段证据 JSON（R6-12）：列宽有限（V9 后 VARCHAR(4000)），超长时保留头部并标注截断，
     * 避免证据过大把阶段写成 FAILED（实测 run 12：evidence 超 500 字节导致落库失败）。
     */
    private String evidenceJson(Map<String, Object> evidence) {
        String json = toJson(evidence);
        int max = 4000;
        if (json.length() <= max) {
            return json;
        }
        String note = "…(截断,原长度" + json.length() + ")";
        return json.substring(0, max - note.length()) + note;
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

    private RunResult assemble(Long runId) {
        PipelineRun run = runMapper.selectById(runId);
        List<PipelineStageRun> stages = stageMapper.selectList(new LambdaQueryWrapper<PipelineStageRun>()
                .eq(PipelineStageRun::getRunId, runId)
                .orderByAsc(PipelineStageRun::getId));
        return new RunResult(runId, run.getIdempotencyKey(), run.getStatus(), run.getCurrentStage(),
                run.getErrorCode(), run.getAttemptNo(), run.getTargetSnapshotId(), stages);
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


