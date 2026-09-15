package com.graduation.analytics.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.LandingUri;
import com.graduation.analytics.contracts.EventContract;
import com.graduation.analytics.contracts.EventEnvelope;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.metric.QualityRuleCatalog;
import com.graduation.analytics.metric.RuleSeverity;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流水线编排（§13.1：POST 立即返回 taskId+PENDING，线程池异步执行计算链）：
 * WAIT_LANDING→INIT_SCHEMA→LOAD_ODS→BUILD_DWD→BUILD_DWS→BUILD_ADS→QUALITY_CHECK→PUBLISH_METRIC
 * 共 8 个阶段（§9.1）；SUCCESS 是**运行终态**而非阶段，不出现在 stage_code 中。
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

    /**
     * 阶段证据 JSON 的字符上限（V15 后列型 MEDIUMTEXT，16MB；本项目证据量级为 10^4 字符，
     * 该上限只在异常膨胀时兜底，且缩减结果必须仍是合法 JSON）。
     */
    static final int EVIDENCE_MAX_CHARS = 200_000;

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

    /**
     * F-88（D-142 §1 / 指导书 §7.3）：发布前的质量门断言端口。
     *
     * <p>QUALITY_CHECK 阶段失败会 fail-fast，但那是**阶段顺序**的保证，不是发布前的显式断言；
     * 重试、作业退出码与 severity 判定不一致时，可能出现「质量结果里存在未通过的阻断级规则，
     * 但流水线仍然走到发布」。因此在真正调用 {@code metricPublisher.publish} 之前再查一次
     * analytics_meta.data_quality_result，用与质量门**同一口径**（{@link RuleSeverity#blocks}）
     * 复判：有未通过的阻断级规则就抛 {@code PIPELINE_QUALITY_FAILED}，不发布新快照。</p>
     */
    private final DataQualityGate qualityGate;

    /**
     * S2-04：本轮输入清单（Landing manifest）的选择器——**按源归属**取批次。
     *
     * <p>规则本体在 {@link LandingManifestSelector}（唯一所有者）：本类只负责"本 run 归属哪个源"
     * 与"重试时钉住哪个批次"。之所以必须按源过滤：ODS 库名与 {@code --sourceSystem} 来自
     * {@code profile.source_id}，而 {@code source_system} 是作业按 source_code 注入的常量字面量，
     * 选中他源清单就会产出"标成本源、实际是他源"的 ODS 行且事后不可察觉（详见选择器 javadoc）。</p>
     */
    private final LandingManifestSelector manifestSelector;

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
        // 口径：恢复审计以「| 文本后缀」形式追加到最早阶段证据（WAIT_LANDING 证据只被
        // 正则读 batchId，从不按 JSON 解析，见 manifestForRun）；上限随列宽（V15 MEDIUMTEXT）
        // 取 EVIDENCE_MAX_CHARS，避免审计行被 4000 老上限切掉。
        String merged = latest.getEvidence() == null || latest.getEvidence().isBlank()
                ? note : cap(latest.getEvidence() + " | " + note, EVIDENCE_MAX_CHARS);
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

    /**
     * 某个 run 的冻结规则集（§7.3.1 line 520：一次 run 冻结完整规则版本与指纹）。
     *
     * <p><b>过渡形态（必须写清，否则不能声称"版本化已闭合"）</b>：{@code quality_rule_definition}
     * 表尚未落地（实测 0 命中），因此冻结集**不是从库里读的**，而是由进程内
     * {@link QualityRuleCatalog} 常量目录按 sourceScope 过滤得到。它能保证的只有
     * 「**同一次 run 内**判定只解析一次、fingerprint 恒定」；它**不能**保证
     * 「不同 run 之间口径随库中版本变化」—— 因为目录随代码发布而变。
     * 真正闭合需要：① 总控批准迁移号建 {@code quality_rule_definition}（DDL 草案见
     * {@code docs/acceptance/f88-dq-severity-20260912/raw/q01-quality-rule-definition-ddl-draft.sql}）
     * ② <b>已由 V20 + F-88 完成</b>：{@code data_quality_result} 增列
     * {@code rule_version/effective_severity/compat_policy_version/rule_fingerprint}，
     * 且写侧三个写点（本类 persistQuality/persistChecks 与 {@code QualityChecker.rule}）
     * 经 {@code QualityChecker.applyVersionedSeverity} 按同一契约填满。
     * 注意 ①「{@code rulesFor()} 改读库」仍属未做项，因此本方法依旧是进程内目录。</p>
     */
    private QualityRuleCatalog.FrozenRules rulesFor(PipelineRun run) {
        return QualityRuleCatalog.DEFAULT.freeze(run.getPipelineCode());
    }

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
            // S2-04：本轮输入必须能归属到一个源，而"哪个源"唯一来自 profile.source_id。
            // 必须**早于**执行器构造判定：装配路径下 `SparkStageExecutorFactory.create` 会抛
            // PlatformBizException(SOURCE_NOT_BOUND)，那是业务异常、落到下面的通用 catch 会变成
            // RUN_INTERNAL（真机实测口径：错误码被吞掉，运维看到的是"平台内部错误"而不是"未绑定源"）。
            // 这里先判一次，让"未绑定源"永远以稳定错误码 SOURCE_NOT_BOUND 落在 run 上；
            // 同时它也是"清单按源过滤"这一前提的显式化 —— 不依赖别的组件的副作用。
            Long runSourceId = profile.getSourceId();
            if (runSourceId == null) {
                throw new PipelineStageException("SOURCE_NOT_BOUND",
                        "运行环境未绑定源（runtime_profile.source_id 为空），无法确定数仓命名空间、"
                                + "也无法按源选择 Landing 输入清单（runtime_profile_id=" + profile.getId()
                                + "，请先激活一个源再运行）");
            }
            SparkStageExecutor executor = stageExecutorFactory.create(snapshot);
            Path landingRoot = LandingUri.resolve(profile.getLandingUri());

            // §7.3.1 line 520：**一次 run 冻结完整规则版本与指纹**。
            // 本 run 内所有严重度判定（写侧 QualityChecker、读侧 DataQualityGate、证据留痕）
            // 全部使用这一份 rules；run 期间不再重新解析目录 ⇒ run 内不可能出现两套口径。
            QualityRuleCatalog.FrozenRules rules = rulesFor(run);
            log.info("pipeline {}: 规则冻结 ruleFingerprint={} catalog={} compatPolicy={}",
                    run.getId(), rules.fingerprint(), rules.catalogVersion(), rules.compatPolicyVersion());

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
            // S2-04：并**按源归属**过滤（否则他源批次会落进本源的库，且事后不可察觉）
            Map<String, Object> manifest = manifestForRun(landingRoot, run.getId(), runSourceId);
            // §9.1：ODS 只能读取 accepted（好的批次数据）；§5.3.3 只装载业务日事件
            Path acceptedDir = manifest == null ? null
                    : landingRoot.resolve(String.valueOf(manifest.get("acceptedUri")));
            String datePrefix = businessDate.substring(0, 4) + "-" + businessDate.substring(4, 6)
                    + "-" + businessDate.substring(6, 8);
            List<EventEnvelope> events = new ArrayList<>();
            // DEF-04：订单总额索引覆盖**整批**（不受业务日切片限制），供金额对账使用。
            // 电商订单跨日支付是常态（T 日下单、T+1 日支付），只用切片会对真实数据误判对账失败。
            Map<String, BigDecimal> batchOrderTotals = new HashMap<>();
            if (acceptedDir != null && Files.isDirectory(acceptedDir)) {
                readAcceptedEvents(acceptedDir, datePrefix, events, batchOrderTotals);
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
                // S2-04：把"这批字节属于哪个源"写进阶段证据。选择器已保证它与本轮运行源一致，
                // 此处留痕是为了验收时能从证据直接回答"ODS 里这批行的 source_system 是注入的哪一行"。
                evidence.put("sourceId", manifest.get("sourceId"));
                evidence.put("sourceCode", manifest.get("sourceCode"));
                return new StageOutcome(LandingManifestSelector.longOf(manifest.get("acceptedRecords")),
                        evidence);
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
                // R6-13：老清单的计数可能是字符串，用选择器的同一口径读，避免 (Number) 强转炸成 RUN_INTERNAL
                if (manifest != null && manifest.get("quarantinedRecords") != null) {
                    evidence.put("odsQuarantinedRecords",
                            LandingManifestSelector.longOf(manifest.get("quarantinedRecords")));
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
                // ① 内联规则（§5.4.1）：严重度由**本 run 冻结的规则集**决定，BLOCKING/ERROR 未过即阻断（D-142 §1）
                QualityChecker.QualitySummary quality =
                        qualityChecker.check(events, run.getId(), batchOrderTotals, rules);
                persistQuality(run.getId(), snapshotIdRef, "LANDING", quality.results(), rules);
                evidence.put("batchOrderTotalsSize", batchOrderTotals.size());
                evidence.put("businessDayEvents", events.size());
                evidence.put("landingRules", quality.results().stream().map(r -> Map.of(
                        "ruleCode", String.valueOf(r.getRuleCode()),
                        "checkCount", r.getCheckCount(),
                        "errorCount", r.getErrorCount(),
                        "passed", r.getPassed())).toList());
                evidence.put("landingCorePassed", quality.corePassed());
                evidence.put("blocking", "RuleSeverity.blocks：BLOCKING/ERROR 未过即阻断（D-142 §1）");
                if (!quality.corePassed()) {
                    // 失败证据必须先落库，再抛出阻断（否则失败原因丢失）
                    evidence.put("published", false);
                    updateStageEvidence(run.getId(), "QUALITY_CHECK", evidence);
                    throw new PipelineStageException("PIPELINE_QUALITY_FAILED",
                            "阻断级 Landing 质量规则未通过，正式分区未发布");
                }
                // ② ADS 暂存层质量门：读 staging 结果 + DWS 对账，任一 BLOCKING 未过 → 阶段失败、不发布
                SparkStageExecutor.StageExecution ex = executor.executeStage(snapshot, run.getId(),
                        "QUALITY_CHECK", businessDate, run.getAttemptNo(),
                        Map.of("outputSnapshotId", snapshotIdRef), confs);
                evidence.putAll(jobEvidence(ex, rules));
                evidence.put("adsChecksPersisted", persistChecks(run.getId(), snapshotIdRef, ex.checks(), rules));
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
                    Map<String, Object> evidence = jobEvidence(ex, rules);
                    evidence.put("adsSnapshotId", snapshotIdRef);
                    evidence.put("adsJobs", adsEvidence.get("jobs"));
                    evidence.put("hiveAds", "正式分区以 Hive 元数据指针指向本次暂存路径（§14.4）");
                    evidence.put("adsChecksPersisted", persistChecks(run.getId(), snapshotIdRef, ex.checks(), rules));
                    evidence.put("adsChecks", ex.checks().stream().map(c -> Map.of(
                            "ruleCode", c.ruleCode(), "severity", c.severity(),
                            "passed", c.passed(), "detail", c.detail())).toList());
                    if (ex.failed()) {
                        return new StageOutcome(ex.totalOutputRecords(), evidence,
                                new PipelineStageException("RUN_PUBLISH_FAILED",
                                        "正式分区发布失败: " + ex.errorMessage()));
                    }

                    // ── F-88：发布前质量门断言（D-142 §1 / §7.3）──────────────────────────
                    // BLOCKING 与 ERROR 未通过 ⇒ 不发布新快照；WARN/INFO 未通过只记录、放行。
                    // 断言读的是刚落库的 data_quality_result（同一 run），与质量门同一口径。
                    List<String> gateFailures = qualityGate.blockingFailuresForRun(run.getId(), rules);
                    evidence.put("prePublishGate", Map.of(
                            "rule", "RuleSeverity.blocks：BLOCKING/ERROR 未通过即阻断",
                            "blockingFailures", gateFailures,
                            "passed", gateFailures.isEmpty()));
                    if (!gateFailures.isEmpty()) {
                        evidence.put("published", false);
                        updateStageEvidence(run.getId(), "PUBLISH_METRIC", evidence);
                        throw new PipelineStageException("PIPELINE_QUALITY_FAILED",
                                "阻断级质量规则未通过，不发布新快照（旧 ACTIVE 不变）: " + String.join(", ", gateFailures));
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
            Map<String, Object> evidence = jobEvidence(ex, rulesFor(run));
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
     * 严重度一律取 {@link RuleSeverity}（唯一所有者，D-142 §1）：AMOUNT_RECONCILE /
     * REQUIRED_FIELD_NULL_RATE / ENUM_WHITELIST 为阻断项（BLOCKING），event_id 重复率为 WARN
     * ——「下游已确定性去重且重复率未超已批准阈值」才允许降级，理由见
     * {@link RuleSeverity#rationale(String)}。
     */
    private void persistQuality(Long runId, String snapshotId, String layer,
                               List<DataQualityResult> results, QualityRuleCatalog.FrozenRules rules) {
        for (DataQualityResult r : results) {
            r.setRunId(runId);
            r.setLayer(cap(layer, 32));
            // 有效严重度按**本 run 冻结的规则集**解析：QualityChecker 已按同一 rules 写入，
            // 此处再解析一次结果必然一致（同一 rules、同一 passed），而非回落到全局 of(ruleCode)。
            // F-88：声明档位/生效档位/版本/策略版本/指纹走写侧唯一实现（QualityChecker.applyVersionedSeverity），
            // 旧实现只写 severity=生效档位，声明档位在此丢失（本文件 :739 是缺口所在）。
            RuleSeverity.RuleVerdict verdict = RuleSeverity.resolve(rules, r.getRuleCode(), r.getPassed());
            QualityChecker.applyVersionedSeverity(r, verdict, rules);
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
     * <p>F-88：落库的 severity 以 {@link RuleSeverity}（唯一所有者，D-142 §1）为准，**不原样照抄**
     * 作业回传字面量 —— 作业侧历史上把「观察项」写成 ERROR（如 ADS_STAGING_SNAPSHOT_ISOLATION）、
     * 把「必须阻断」写成 ERROR（如 REQUIRED_FIELD_NULL_RATE 的 ADS 侧同名规则）。归一化只改
     * 落库标签，作业自身的成败判据（BLOCKING 未过即 FAILED）不变。</p>
     *
     * <p>V20/F-88 起归一化值落两列：{@code severity}=目录**声明**档位、
     * {@code effectiveSeverity}=条件判定后的**生效**档位；另落 {@code ruleVersion}/
     * {@code compatPolicyVersion}/{@code ruleFingerprint}，使「这条结果按哪一版规则判的」可事后复算。</p>
     *
     * @return 实际写库的规则条数
     */
    private int persistChecks(Long runId, String snapshotId, List<JobResultParser.CheckInfo> checks,
                              QualityRuleCatalog.FrozenRules rules) {
        int inserted = 0;
        for (JobResultParser.CheckInfo c : checks) {
            // 审计项判据不经过 RuleSeverity：作业回传 INFO 的一律只进证据，不写规则表
            if (RuleSeverity.INFO.equalsIgnoreCase(String.valueOf(c.severity()).trim())) {
                continue;
            }
            DataQualityResult r = new DataQualityResult();
            r.setRunId(runId);
            r.setRuleCode(cap(c.ruleCode(), 64));
            r.setLayer(cap(c.layer(), 32));
            // F-88：解析一次拿全四要素，与 QualityChecker/persistQuality 共用写侧唯一实现。
            // severity = 目录**声明**档位（不再照抄作业回传字面量，也不再顶替生效档位）；
            // effectiveSeverity = 条件判定后的**生效**档位。两者不同时（如声明 WARN 超阈值生效 BLOCKING）
            // 正是本改动要能事后区分的信息，不能只留一个。
            RuleSeverity.RuleVerdict verdict = RuleSeverity.resolve(rules, c.ruleCode(),
                    RuleSeverity.failedFlag(c.passed()));
            QualityChecker.applyVersionedSeverity(r, verdict, rules);
            // 列宽保护只加在真正落库的字符串列上（severity/effective_severity 均为 VARCHAR(16)）。
            r.setSeverity(cap(r.getSeverity(), 16));
            r.setEffectiveSeverity(cap(r.getEffectiveSeverity(), 16));
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
    private Map<String, Object> jobEvidence(SparkStageExecutor.StageExecution ex,
                                            QualityRuleCatalog.FrozenRules rules) {
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
                    // 作业回传的原始 severity 与平台归一化后的 severity 都留痕：
                    // 两者不同时（如 ERROR→WARN）证据必须能看出「是平台改判的」，不能只剩一个值。
                    cm.put("severity", c.severity());
                    cm.put("normalizedSeverity", RuleSeverity.resolve(rules, c.ruleCode(),
                            RuleSeverity.failedFlag(c.passed())).effectiveSeverity());
                    cm.put("targetTable", c.targetTable());
                    cm.put("checkCount", c.checkCount());
                    cm.put("errorCount", c.errorCount());
                    cm.put("threshold", c.threshold());
                    cm.put("passed", c.passed());
                    cm.put("detail", c.detail());
                    return cm;
                }).toList());
                // 平台口径下「未通过的阻断级规则」（BLOCKING/ERROR 未过；作业回传 ERROR 但被改判
                // WARN 的观察项不算）。作业自身为什么 FAILED 看 blockingFailedRaw（作业侧判据）。
                item.put("blockingFailed", j.checks().stream()
                        .filter(c -> RuleSeverity.blocks(rules, c.ruleCode(), RuleSeverity.failedFlag(c.passed())))
                        .map(JobResultParser.CheckInfo::ruleCode).toList());
                item.put("blockingFailedRawJobSeverity", j.checks().stream()
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
     *
     * <p>S2-04：本方法只负责"钉住哪个批次"（从证据里正则读 batchId，见 §314 的列宽说明），
     * 清单的**可归属性与新旧比较**全部交给 {@link LandingManifestSelector}；返回的清单必定属于
     * {@code sourceId}，否则为 null（调用方按 RUN_EMPTY_LANDING 拒绝，不回落、不猜）。</p>
     */
    private Map<String, Object> manifestForRun(Path landingRoot, Long runId, long sourceId) {
        PipelineStageRun landing = stageMapper.selectOne(new LambdaQueryWrapper<PipelineStageRun>()
                .eq(PipelineStageRun::getRunId, runId)
                .eq(PipelineStageRun::getStageCode, "WAIT_LANDING")
                .orderByAsc(PipelineStageRun::getId).last("LIMIT 1"));
        Long pinnedBatchId = null;
        if (landing != null && landing.getEvidence() != null) {
            Matcher m = Pattern.compile("\"batchId\"\\s*:\\s*(\\d+)").matcher(landing.getEvidence());
            if (m.find()) {
                pinnedBatchId = Long.valueOf(m.group(1));
            }
        }
        return manifestSelector.select(landingRoot, pinnedBatchId, sourceId);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    /**
     * 阶段证据 JSON（R6-12 / 2026-09-11 重写）。
     *
     * <p>历史：V8 列宽 500 → R6-12 起加宽到 4000 并在代码侧「截断兜底」。但真机真实链路
     * 的 BUILD_ADS 证据（逐作业 × 逐输出分区：表名/dt/快照/行数/路径）会超过 4000 字符，
     * 而 {@code substring} 截断发生在**字符串中间** → 落库的是**非法 JSON**；重试/恢复路径
     * {@link #stageEvidence} 解析失败后静默退化成空 Map → PUBLISH_METRIC 报
     * 「缺少 BUILD_ADS 真实作业证据，拒绝发布」（实测 pipeline run 22，且该 run 在平台重启后
     * 被自动恢复重试时复现）。</p>
     *
     * <p>现在两道保证：① V15 把列型改为 MEDIUMTEXT（16MB），本项目证据量级不会再触发；
     * ② 兜底改成**结构化缩减**（逐列表限量 + 标注 {@code _evidenceTruncated}），
     * 无论怎么截都仍是**合法 JSON**，绝不切断字符串。</p>
     */
    String evidenceJson(Map<String, Object> evidence) {
        String json = toJson(evidence);
        if (json.length() <= EVIDENCE_MAX_CHARS) {
            return json;
        }
        for (int cap : new int[]{200, 50, 10, 2}) {
            String reduced = toJson(reduceLists(evidence, cap, json.length()));
            if (reduced.length() <= EVIDENCE_MAX_CHARS) {
                return reduced;
            }
        }
        // 极端情况：只保留最小可用摘要（重试路径判定「证据是否存在」依赖 jobs 非空）
        Map<String, Object> minimal = new LinkedHashMap<>();
        minimal.put("_evidenceTruncated", true);
        minimal.put("_originalLength", json.length());
        minimal.put("_note", "证据超长，仅保留作业摘要（完整内容见 landing/logs 作业日志）");
        Object jobs = evidence.get("jobs");
        if (jobs instanceof List<?> list) {
            minimal.put("jobCodes", list.stream()
                    .filter(Map.class::isInstance)
                    .map(m -> String.valueOf(((Map<?, ?>) m).get("jobCode")))
                    .toList());
            minimal.put("jobCount", list.size());
        }
        return toJson(minimal);
    }

    /** 逐列表限量，并标注被截断的列表（保持 JSON 合法；标量字段全部保留） */
    private static Map<String, Object> reduceLists(Map<String, Object> evidence, int cap, int originalLength) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : evidence.entrySet()) {
            Object v = e.getValue();
            if (v instanceof List<?> list && list.size() > cap) {
                out.put(e.getKey(), new ArrayList<>(list.subList(0, cap)));
                out.put(e.getKey() + "_truncated", Map.of("kept", cap, "total", list.size()));
            } else {
                out.put(e.getKey(), v);
            }
        }
        out.put("_evidenceTruncated", true);
        out.put("_originalLength", originalLength);
        return out;
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
    /**
     * 读取 accepted 批次事件。
     *
     * @param out              只收**业务日切片**的事件（§5.3.3 只装载业务日事件）
     * @param batchOrderTotals 收**整批**订单总额（order_id → total_amount），供金额对账跨日使用（DEF-04）
     */
    private void readAcceptedEvents(Path acceptedDir, String datePrefix, List<EventEnvelope> out,
                                    Map<String, BigDecimal> batchOrderTotals) {
        try (Stream<Path> list = Files.list(acceptedDir)) {
            for (Path f : list.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        EventEnvelope envelope = EventEnvelope.fromJson(line, objectMapper);
                        if (EventContract.ORDER_CREATED.equals(envelope.eventType())) {
                            String orderId = envelope.payload().get("order_id") == null ? ""
                                    : String.valueOf(envelope.payload().get("order_id"));
                            if (!orderId.isEmpty()) {
                                QualityChecker.putTotal(batchOrderTotals, orderId,
                                        envelope.payload().get("total_amount"));
                            }
                        }
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


