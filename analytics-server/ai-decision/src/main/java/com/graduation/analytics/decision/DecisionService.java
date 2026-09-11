package com.graduation.analytics.decision;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.common.MallBizException;
import com.graduation.analytics.decision.entity.DecisionEvaluation;
import com.graduation.analytics.decision.entity.DecisionTask;
import com.graduation.analytics.decision.mapper.DecisionEvaluationMapper;
import com.graduation.analytics.decision.mapper.DecisionTaskMapper;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.entity.MetricValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 决策中心服务（R8-3 契约 §3.3/§3.4、V2.0 §20.3/§20.4/§22.6）。
 *
 * <ul>
 *   <li><b>AI 只能 DRAFT</b>：{@link #createDraft} 无论 source 是什么都落 DRAFT，
 *       到 APPROVED 必须经人工 {@link #submit} → {@link #approve}（状态机硬约束）。</li>
 *   <li><b>提交前齐备校验</b>：action / owner / 目标指标 / 目标方向 / 评价窗口 / 证据锚点缺一即
 *       {@code PARAM_INVALID}，不允许「先提交后补字段」。</li>
 *   <li><b>身份不可伪造</b>：created_by / approved_by / evaluated_by 全部取 {@link AuditActor}
 *       （控制层由 {@code CurrentUserHolder} 组装），服务签名里不再有「传一个用户名进来」的口子；
 *       每次动作同时写 operation_audit_log。</li>
 *   <li><b>等长窗口评价</b>：baseline 窗口 [approvedDate-N+1, approvedDate] 读批准时钉住的
 *       {@code baseline_snapshot_id}；actual 窗口 [completedDate+1, completedDate+N] 读评价时的最新
 *       ACTIVE 快照；两端窗口长度都是 N 天，窗口与前后快照号、样本数、口径版本一并落库；
 *       数据不足判 {@code INSUFFICIENT_DATA}（绝不因为「没数据/基线为 0」就判无效，§20.4）。</li>
 *   <li><b>非因果</b>：结论文案固定为「执行前后指标变化」，不做因果推断。</li>
 * </ul>
 *
 * <p><b>口径限制（诚实登记，不假装有逐日序列）</b>：ai-decision 依赖 metric-analysis（见 ai-decision/pom.xml），
 * 但可用的指标读接口只有 {@link MetricStore} 一个，而它**没有按日期区间读序列的方法**，
 * 其 period 形如 {@code day:2026-09-01}，即一个快照只有一个业务日观测点；逐日 ADS 序列要走
 * metric-analysis 的 {@code MetricAdsReader}（只认原始 ADS 表名/列名），而 metricCode → ADS 表/列的映射
 * 由 metric-analysis 的 {@code AnalysisService} 独占维护 —— 在 ai-decision 里重抄一份映射就是制造第二个
 * 「口径所有者」，属反熵禁止项，故本轮不做。因此本实现的「窗口聚合」实际是：窗口取边界观测点（快照值），
 * 窗口内样本数如实记为命中该指标的行数，并把观测业务日写进 note —— 没有伪造逐日序列，也没有把单点观测
 * 说成窗口均值。真正的逐日等长窗口聚合需要 metric-analysis/SemanticCatalog 暴露「按日期区间读指标」的
 * 接口（后续轮次），本条限制已同步登记在交付报告与本文件注释中。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionService {

    /** 评价窗口默认长度（天）：批准/完成时未指定时使用 */
    @Value("${decision.eval.default-window-days:3}")
    private int defaultEvalWindowDays;

    /** EFFECTIVE 阈值：改善率 ≥ 阈值（或达到目标值）即有效，可配置（§20.4） */
    @Value("${decision.eval.effective-threshold:0.05}")
    private BigDecimal effectiveThreshold;

    /** PARTIAL 阈值：改善率 > 阈值（默认 0）但未达 effective 阈值 */
    @Value("${decision.eval.partial-threshold:0}")
    private BigDecimal partialThreshold;

    /** 评价口径版本（阈值/算法的版本标识，随评价落库，口径变更后可追溯） */
    @Value("${decision.eval.definition-version:r8-window-v1}")
    private String evalDefinitionVersion;

    private final DecisionTaskMapper taskMapper;
    private final DecisionEvaluationMapper evaluationMapper;
    private final MetricStore metricStore;
    private final OperationAuditService audit;

    /** 允许的决策来源（AI 只能 DRAFT；人工创建同样从 DRAFT 起步） */
    private static final String SOURCE_AI = "ai";
    private static final String SOURCE_HUMAN = "human";

    /** 目标方向 */
    private static final String DIRECTION_UP = "UP";
    private static final String DIRECTION_DOWN = "DOWN";

    public record CreateDraftReq(String title, String action, String targetMetricCode, String targetDirection,
                                 String suggestionSnapshotId, String risk, String owner, String evidencePackageId) {
    }

    /** 提交审批：可补 owner / 证据锚点（前端只发 {}，字段为空则用草稿上已有的值校验） */
    public record SubmitReq(String owner, String evidencePackageId) {
    }

    public record ApproveReq(String owner, LocalDate dueDate, BigDecimal targetValue, Integer evalWindowDays,
                             String note) {
    }

    /** 驳回/取消：reason 必填（§20.3） */
    public record ReasonReq(String reason) {
    }

    /** 执行完成备注（可选） */
    public record ExecuteReq(String note) {
    }

    /** 窗口内的一次观测（快照粒度，见类注释的口径限制） */
    private record Observation(String snapshotId, String metricCode, BigDecimal value, int sampleCount,
                               LocalDate businessDate, String metricDefinitionVersion) {

        static Observation empty(String metricCode) {
            return new Observation(null, metricCode, null, 0, null, null);
        }

        boolean isEmpty() {
            return value == null;
        }
    }

    // ── 创建（AI 只能 DRAFT） ────────────────────────────────────────────

    public DecisionTask createDraft(CreateDraftReq req, AuditActor actor, String source) {
        if (req == null) {
            throw new MallBizException("PARAM_INVALID", "请求体不能为空");
        }
        String normalizedSource = normalizeSource(source);
        requireText(req.title(), "title（决策标题）");
        requireText(req.action(), "action（建议动作）");

        DecisionTask task = new DecisionTask();
        task.setDecisionNo("DC-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
                + "-" + UUID.randomUUID().toString().substring(0, 4));
        task.setSource(normalizedSource);
        task.setTitle(req.title().trim());
        task.setAction(req.action().trim());
        task.setTargetMetricCode(trimToNull(req.targetMetricCode()));
        task.setTargetDirection(trimToNull(req.targetDirection()));
        task.setSuggestionSnapshotId(trimToNull(req.suggestionSnapshotId()));
        task.setRisk(trimToNull(req.risk()));
        task.setOwner(trimToNull(req.owner()));
        task.setEvidencePackageId(trimToNull(req.evidencePackageId()));
        // §20.3：AI 只能创建 DRAFT（不信任调用方传 status；此处只有 DRAFT 一条路径）
        task.setStatus(DecisionStateMachine.DRAFT);
        task.setEvalWindowDays(defaultEvalWindowDays);
        task.setCreatedBy(actor.userId());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        audit.success(actor, OperationAuditService.ACTION_DECISION_CREATE,
                OperationAuditService.RESOURCE_DECISION_TASK, String.valueOf(task.getId()),
                null, digestOf(task), "source=" + normalizedSource + "（AI 只能 DRAFT，§20.3）");
        log.info("决策草稿创建 id={} no={} source={} by={}", task.getId(), task.getDecisionNo(),
                normalizedSource, actor.userId());
        return task;
    }

    // ── 审核流 ───────────────────────────────────────────────────────────

    /** 提交审批：齐备校验通过才允许 DRAFT → PENDING_REVIEW */
    public DecisionTask submit(Long id, SubmitReq req, AuditActor actor) {
        DecisionTask task = require(id);
        if (req != null) {
            if (trimToNull(req.owner()) != null) {
                task.setOwner(req.owner().trim());
            }
            if (trimToNull(req.evidencePackageId()) != null) {
                task.setEvidencePackageId(req.evidencePackageId().trim());
            }
        }
        List<String> missing = missingForSubmit(task);
        if (!missing.isEmpty()) {
            throw new MallBizException("PARAM_INVALID",
                    "提交审批前必须齐备（§20.3），缺失: " + String.join(", ", missing));
        }
        DecisionStateMachine.validate(task.getStatus(), DecisionStateMachine.PENDING_REVIEW);
        String before = digestOf(task);
        task.setStatus(DecisionStateMachine.PENDING_REVIEW);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        audit.success(actor, OperationAuditService.ACTION_DECISION_SUBMIT,
                OperationAuditService.RESOURCE_DECISION_TASK, String.valueOf(id), before, digestOf(task),
                "提交审批，证据锚点=" + evidenceAnchor(task));
        return task;
    }

    /** 批准：必须设置负责人/期限/目标指标，并锁定基线快照与口径版本（§20.4、§22.7） */
    public DecisionTask approve(Long id, ApproveReq req, AuditActor actor) {
        DecisionTask task = require(id);
        DecisionStateMachine.validate(task.getStatus(), DecisionStateMachine.APPROVED);
        requireText(task.getTargetMetricCode(), "目标指标（无目标指标的决策不能进入正式任务，§22.3）");

        String owner = req != null && trimToNull(req.owner()) != null ? req.owner().trim() : trimToNull(task.getOwner());
        if (owner == null || (req == null || req.dueDate() == null)) {
            throw new MallBizException("PARAM_INVALID",
                    "必须设置负责人与截止时间（owner/dueDate）");
        }
        int windowDays = resolveWindowDays(req == null ? null : req.evalWindowDays(), task.getEvalWindowDays());

        // 基线：批准时刻的最新 ACTIVE 快照，同时钉住快照号（事后换快照无法伪造基线）
        Observation baseline = observe(task.getTargetMetricCode(), null);
        if (baseline.isEmpty()) {
            throw new MallBizException("PARAM_INVALID", "当前快照缺少目标指标 " + task.getTargetMetricCode()
                    + " 的基线，无法批准");
        }

        String before = digestOf(task);
        LocalDateTime approvedAt = LocalDateTime.now();
        task.setOwner(owner);
        task.setDueDate(req.dueDate());
        task.setTargetValue(req.targetValue());
        task.setEvalWindowDays(windowDays);
        task.setBaselineValue(baseline.value());
        task.setBaselineSnapshotId(baseline.snapshotId());
        task.setDefinitionVersion(baseline.metricDefinitionVersion());
        task.setApprovedBy(actor.userId());
        task.setApprovedAt(approvedAt);
        task.setApprovalNote(composeApprovalNote(req.note(), baseline, windowDays, approvedAt.toLocalDate()));
        task.setStatus(DecisionStateMachine.APPROVED);
        task.setUpdatedAt(approvedAt);
        taskMapper.updateById(task);

        audit.success(actor, OperationAuditService.ACTION_DECISION_APPROVE,
                OperationAuditService.RESOURCE_DECISION_TASK, String.valueOf(id), before, digestOf(task),
                "批准，基线快照=" + baseline.snapshotId() + "，基线业务日=" + baseline.businessDate()
                        + "，窗口=" + windowDays + " 天");
        log.info("决策 {} 批准 by={} 基线={}（快照 {}）窗口 {} 天", task.getDecisionNo(), actor.userId(),
                baseline.value(), baseline.snapshotId(), windowDays);
        return task;
    }

    /** 驳回：必须记录原因（§20.3），只允许审核前状态 */
    public DecisionTask reject(Long id, String reason, AuditActor actor) {
        DecisionTask task = require(id);
        requireText(reason, "驳回原因（§20.3 驳回必须记录原因）");
        DecisionStateMachine.validate(task.getStatus(), DecisionStateMachine.REJECTED);
        String before = digestOf(task);
        task.setRejectReason(truncate(reason.trim(), 255));
        task.setApprovalNote(truncate("驳回: " + reason.trim()
                + "（审批人 " + actor.userId() + "）", 512));
        task.setStatus(DecisionStateMachine.REJECTED);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        audit.success(actor, OperationAuditService.ACTION_DECISION_REJECT,
                OperationAuditService.RESOURCE_DECISION_TASK, String.valueOf(id), before, digestOf(task),
                reason.trim());
        return task;
    }

    /** 开始执行：APPROVED → IN_PROGRESS */
    public DecisionTask start(Long id, AuditActor actor) {
        DecisionTask task = require(id);
        DecisionStateMachine.validate(task.getStatus(), DecisionStateMachine.IN_PROGRESS);
        String before = digestOf(task);
        task.setStatus(DecisionStateMachine.IN_PROGRESS);
        task.setStartedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        audit.success(actor, OperationAuditService.ACTION_DECISION_START,
                OperationAuditService.RESOURCE_DECISION_TASK, String.valueOf(id), before, digestOf(task),
                "开始执行");
        return task;
    }

    /** 完成执行：IN_PROGRESS → COMPLETED（完成 ≠ 有效，§20.3） */
    public DecisionTask complete(Long id, String note, AuditActor actor) {
        DecisionTask task = require(id);
        DecisionStateMachine.validate(task.getStatus(), DecisionStateMachine.COMPLETED);
        String before = digestOf(task);
        task.setStatus(DecisionStateMachine.COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        if (trimToNull(note) != null) {
            task.setExecutionNote(truncate(note.trim(), 512));
        }
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        audit.success(actor, OperationAuditService.ACTION_DECISION_COMPLETE,
                OperationAuditService.RESOURCE_DECISION_TASK, String.valueOf(id), before, digestOf(task),
                "执行完成（完成不代表有效，需等评价窗口）");
        return task;
    }

    /** 取消（执行中，必须记录原因） */
    public DecisionTask cancel(Long id, String reason, AuditActor actor) {
        DecisionTask task = require(id);
        requireText(reason, "取消原因（§20.3 取消必须记录原因）");
        DecisionStateMachine.validate(task.getStatus(), DecisionStateMachine.CANCELLED);
        String before = digestOf(task);
        task.setCancelReason(truncate(reason.trim(), 255));
        task.setStatus(DecisionStateMachine.CANCELLED);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        audit.success(actor, OperationAuditService.ACTION_DECISION_CANCEL,
                OperationAuditService.RESOURCE_DECISION_TASK, String.valueOf(id), before, digestOf(task),
                reason.trim());
        return task;
    }

    // ── 效果评价（§20.4） ────────────────────────────────────────────────

    /**
     * 评价：等长窗口前后对比。
     * baseline = 批准时钉住快照在 [approvedDate-N+1, approvedDate] 的观测；
     * actual = 最新 ACTIVE 快照在 [completedDate+1, completedDate+N] 的观测；
     * improve=(actual−baseline)/|baseline|，target_direction=DOWN 取反；
     * 分级 EFFECTIVE（达到目标值或改善率≥阈值）/ PARTIAL（改善但未达标）/ INEFFECTIVE（未改善）/
     * INSUFFICIENT_DATA（无后快照、窗口未产生数据、基线为 0 等，**不得**判为无效）。
     */
    public DecisionEvaluation evaluate(Long id, AuditActor actor) {
        DecisionTask task = require(id);
        DecisionStateMachine.validate(task.getStatus(), DecisionStateMachine.EVALUATING);
        String metricCode = requireText(task.getTargetMetricCode(), "目标指标");
        String direction = normalizeDirection(task.getTargetDirection());
        int windowDays = resolveWindowDays(null, task.getEvalWindowDays());
        if (task.getApprovedAt() == null || task.getCompletedAt() == null) {
            throw new MallBizException("PARAM_INVALID", "决策缺少批准时间或完成时间，无法计算评价窗口");
        }
        LocalDate approvedDate = task.getApprovedAt().toLocalDate();
        LocalDate completedDate = task.getCompletedAt().toLocalDate();
        LocalDate baselineWindowStart = approvedDate.minusDays(windowDays - 1L);
        LocalDate actualWindowStart = completedDate.plusDays(1);
        LocalDate actualWindowEnd = completedDate.plusDays(windowDays);

        Observation baseline = observe(metricCode, task.getBaselineSnapshotId());
        Observation actual = observe(metricCode, null);

        DecisionEvaluation evaluation = new DecisionEvaluation();
        evaluation.setDecisionId(id);
        evaluation.setEvaluatedBy(actor.userId());
        evaluation.setCreatedAt(LocalDateTime.now());
        evaluation.setEvalWindowDays(windowDays);
        evaluation.setWindowStart(actualWindowStart);
        evaluation.setWindowEnd(actualWindowEnd);
        evaluation.setBaselineSnapshotId(baseline.isEmpty() ? task.getBaselineSnapshotId() : baseline.snapshotId());
        evaluation.setActualSnapshotId(actual.snapshotId());
        evaluation.setBaselinePeriodValue(baseline.value());
        evaluation.setActualPeriodValue(actual.value());
        evaluation.setSampleCount(baseline.sampleCount() + actual.sampleCount());
        evaluation.setDefinitionVersion(evalDefinitionVersion);
        // baseline_value 非空约束：批准时已落库的基线值可作证据复用（同一钉住快照读出的值）
        evaluation.setBaselineValue(baseline.value() != null ? baseline.value() : task.getBaselineValue());
        if (evaluation.getBaselineValue() == null) {
            throw new MallBizException("PARAM_INVALID", "决策缺少基线值，无法评价（批准时未锁定基线）");
        }

        String insufficient = insufficientReason(task, baseline, actual, windowDays,
                baselineWindowStart, approvedDate, completedDate);
        if (insufficient != null) {
            evaluation.setResult(DecisionStateMachine.INSUFFICIENT_DATA);
            evaluation.setNote(insufficient);
        } else {
            BigDecimal baselineValue = evaluation.getBaselineValue();
            BigDecimal actualValue = actual.value();
            BigDecimal diff = actualValue.subtract(baselineValue);
            BigDecimal rate = diff.divide(baselineValue.abs(), 4, RoundingMode.HALF_UP);
            if (DIRECTION_DOWN.equals(direction)) {
                rate = rate.negate(); // 越低越好指标取反
            }
            evaluation.setActualValue(actualValue);
            evaluation.setImprovementRate(rate);
            evaluation.setResult(grade(task, actualValue, rate, direction));
            evaluation.setNote(note(task, baseline, actual, rate, direction, windowDays,
                    actualWindowStart, actualWindowEnd, baselineWindowStart, approvedDate, evaluation.getResult()));
        }
        evaluationMapper.insert(evaluation);

        String before = digestOf(task);
        task.setStatus(evaluation.getResult());
        task.setEvaluatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        audit.success(actor, OperationAuditService.ACTION_DECISION_EVALUATE,
                OperationAuditService.RESOURCE_DECISION_TASK, String.valueOf(id), before, digestOf(task),
                evaluation.getResult() + "：" + evaluation.getNote());
        log.info("决策 {} 评价结果={} 基线={} 实际={} 改善率={}（窗口 {}~{}，口径 {}）", task.getDecisionNo(),
                evaluation.getResult(), evaluation.getBaselineValue(), evaluation.getActualValue(),
                evaluation.getImprovementRate(), actualWindowStart, actualWindowEnd, evalDefinitionVersion);
        return evaluation;
    }

    // ── 查询 ─────────────────────────────────────────────────────────────

    public DecisionTask get(Long id) {
        return require(id);
    }

    public List<DecisionTask> list(int limit) {
        return taskMapper.selectList(new LambdaQueryWrapper<DecisionTask>()
                .orderByDesc(DecisionTask::getId)
                .last("LIMIT " + Math.max(1, Math.min(100, limit))));
    }

    public List<DecisionEvaluation> evaluations(Long decisionId) {
        return evaluationMapper.selectList(new LambdaQueryWrapper<DecisionEvaluation>()
                .eq(DecisionEvaluation::getDecisionId, decisionId)
                .orderByDesc(DecisionEvaluation::getId));
    }

    // ── 内部：齐备校验 ───────────────────────────────────────────────────

    /** 提交审批前的齐备校验项（§20.3）：缺哪项就报哪项 */
    private List<String> missingForSubmit(DecisionTask task) {
        List<String> missing = new ArrayList<>();
        if (trimToNull(task.getAction()) == null) {
            missing.add("action");
        }
        if (trimToNull(task.getOwner()) == null) {
            missing.add("owner");
        }
        if (trimToNull(task.getTargetMetricCode()) == null) {
            missing.add("target_metric_code");
        }
        if (!DIRECTION_UP.equals(task.getTargetDirection()) && !DIRECTION_DOWN.equals(task.getTargetDirection())) {
            missing.add("target_direction(UP/DOWN)");
        }
        if (task.getEvalWindowDays() == null || task.getEvalWindowDays() <= 0) {
            missing.add("eval_window_days");
        }
        if (evidenceAnchor(task) == null) {
            missing.add("evidence(evidence_package_id 或 suggestion_snapshot_id)");
        }
        return missing;
    }

    /**
     * 证据锚点：优先证据包 id（人工/工作台产出），其次 AI 建议快照 id（AI 生成草稿的溯源点）。
     * 两者都是可溯源的证据引用，审计里会记录实际用的是哪一个。
     */
    private String evidenceAnchor(DecisionTask task) {
        String pkg = trimToNull(task.getEvidencePackageId());
        return pkg != null ? "evidence_package:" + pkg : trimToNull(task.getSuggestionSnapshotId()) == null
                ? null : "suggestion_snapshot:" + task.getSuggestionSnapshotId();
    }

    // ── 内部：窗口观测 ───────────────────────────────────────────────────

    /**
     * 读目标指标观测：snapshotId 为空 → 最新 ACTIVE；否则读指定快照（不再回退 ACTIVE，
     * 否则「基线快照」会随新发布漂移）。同快照同指标多行时取等权均值并记样本数。
     */
    private Observation observe(String metricCode, String snapshotId) {
        List<MetricValue> values = metricStore.query(new MetricStore.MetricQuery(snapshotId, snapshotId == null));
        List<MetricValue> matched = new ArrayList<>();
        for (MetricValue v : values) {
            if (metricCode.equals(v.getMetricCode()) && v.getMetricValue() != null) {
                matched.add(v);
            }
        }
        if (matched.isEmpty()) {
            return Observation.empty(metricCode);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (MetricValue v : matched) {
            sum = sum.add(v.getMetricValue());
        }
        BigDecimal aggregated = sum.divide(BigDecimal.valueOf(matched.size()), 4, RoundingMode.HALF_UP);
        MetricValue first = matched.get(0);
        return new Observation(
                snapshotId != null ? snapshotId : first.getSnapshotId(),
                metricCode,
                aggregated,
                matched.size(),
                parseBusinessDate(first.getPeriod()),
                trimToNull(first.getDefinitionVersion()));
    }

    /** period 形如 day:2026-09-01（MetricPublisher 写入口径），也兼容纯 ISO 日期；解析不出返回 null */
    private static LocalDate parseBusinessDate(String period) {
        if (period == null || period.isBlank()) {
            return null;
        }
        String value = period.trim();
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            log.warn("指标 period 无法解析为业务日期: {}", period);
            return null;
        }
    }

    /** 数据不足的原因（返回 null 表示可以出结论） */
    private String insufficientReason(DecisionTask task, Observation baseline, Observation actual, int windowDays,
                                      LocalDate baselineWindowStart, LocalDate approvedDate, LocalDate completedDate) {
        if (baseline.isEmpty() && task.getBaselineValue() == null) {
            return "评价窗口内无基线数据：快照 " + task.getBaselineSnapshotId() + " 无目标指标 "
                    + task.getTargetMetricCode() + " 观测（数据不足，按 §20.4 不判无效）";
        }
        if (actual.isEmpty()) {
            return "评价窗口内无实际数据：最新已发布快照无目标指标 " + task.getTargetMetricCode()
                    + " 观测（数据不足，按 §20.4 不判无效）";
        }
        if (actual.snapshotId() != null && actual.snapshotId().equals(evaluationBaselineSnapshot(task, baseline))) {
            return "完成后尚未发布新快照（当前 ACTIVE=" + actual.snapshotId()
                    + " 即基线快照）：完成不代表有效，请等数据覆盖窗口后再评价（§20.3）";
        }
        if (actual.businessDate() == null) {
            return "最新快照缺少可解析的业务日期（period），无法确认数据落在评价窗口 ["
                    + completedDate.plusDays(1) + ", " + completedDate.plusDays(windowDays) + "] 内";
        }
        if (!actual.businessDate().isAfter(completedDate)) {
            return "完成后尚无新业务日数据：最新快照业务日 " + actual.businessDate() + " ≤ 完成日 " + completedDate
                    + "，评价窗口 [" + completedDate.plusDays(1) + ", " + completedDate.plusDays(windowDays)
                    + "] 尚未产生数据（数据不足，按 §20.4 不判无效）";
        }
        BigDecimal baselineValue = baseline.value() != null ? baseline.value() : task.getBaselineValue();
        if (baselineValue.compareTo(BigDecimal.ZERO) == 0) {
            return "基线值为 0，改善率 (actual−baseline)/|baseline| 无定义（数据不足，按 §20.4 不判无效）；"
                    + "基线窗口 [" + baselineWindowStart + ", " + approvedDate + "] 观测值 0";
        }
        return null;
    }

    private String evaluationBaselineSnapshot(DecisionTask task, Observation baseline) {
        return baseline.isEmpty() ? task.getBaselineSnapshotId() : baseline.snapshotId();
    }

    /** 分级：达到目标值（若有）或改善率 ≥ 阈值 → EFFECTIVE；改善为正 → PARTIAL；否则 INEFFECTIVE */
    private String grade(DecisionTask task, BigDecimal actualValue, BigDecimal rate, String direction) {
        if (task.getTargetValue() != null) {
            boolean reached = DIRECTION_DOWN.equals(direction)
                    ? actualValue.compareTo(task.getTargetValue()) <= 0
                    : actualValue.compareTo(task.getTargetValue()) >= 0;
            if (reached) {
                return DecisionStateMachine.EFFECTIVE;
            }
        } else if (rate.compareTo(effectiveThreshold) >= 0) {
            return DecisionStateMachine.EFFECTIVE;
        }
        if (rate.compareTo(partialThreshold) > 0) {
            return DecisionStateMachine.PARTIAL;
        }
        return DecisionStateMachine.INEFFECTIVE;
    }

    /** 结论文案：固定「执行前后指标变化」，显式非因果（§20.4） */
    private String note(DecisionTask task, Observation baseline, Observation actual, BigDecimal rate,
                        String direction, int windowDays, LocalDate windowStart, LocalDate windowEnd,
                        LocalDate baselineWindowStart, LocalDate approvedDate, String result) {
        BigDecimal baselineValue = baseline.value() != null ? baseline.value() : task.getBaselineValue();
        StringBuilder sb = new StringBuilder();
        sb.append("执行前后指标变化（非因果推断）：").append(task.getTargetMetricCode())
                .append(' ').append(baselineValue.stripTrailingZeros().toPlainString())
                .append("（基线窗口 ").append(baselineWindowStart).append('~').append(approvedDate)
                .append("，观测业务日 ").append(baseline.businessDate()).append("，快照 ")
                .append(evaluationBaselineSnapshot(task, baseline)).append(')')
                .append(" → ").append(actual.value().stripTrailingZeros().toPlainString())
                .append("（评价窗口 ").append(windowStart).append('~').append(windowEnd)
                .append("，观测业务日 ").append(actual.businessDate()).append("，快照 ")
                .append(actual.snapshotId()).append(')')
                .append("；改善率 ").append(rate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP))
                .append("%（方向 ").append(direction).append("，窗口 ").append(windowDays).append(" 天")
                .append("，样本数 ").append(baseline.sampleCount() + actual.sampleCount())
                .append("，口径 ").append(evalDefinitionVersion).append("）；结论 ").append(result);
        if (task.getTargetValue() != null) {
            sb.append("，目标值 ").append(task.getTargetValue().stripTrailingZeros().toPlainString());
        }
        return truncate(sb.toString(), 512);
    }

    /** 审批备注：人工备注 + 基线溯源信息（窗口/观测日/快照号） */
    private String composeApprovalNote(String userNote, Observation baseline, int windowDays, LocalDate approvedDate) {
        LocalDate windowStart = approvedDate.minusDays(windowDays - 1L);
        String trace = "基线快照=" + baseline.snapshotId() + ";基线业务日=" + baseline.businessDate()
                + ";基线窗口=" + windowStart + "~" + approvedDate + ";窗口=" + windowDays + "天";
        String note = trimToNull(userNote);
        return truncate(note == null ? trace : note + ";" + trace, 512);
    }

    // ── 内部：通用 ───────────────────────────────────────────────────────

    private DecisionTask require(Long id) {
        DecisionTask task = id == null ? null : taskMapper.selectById(id);
        if (task == null) {
            throw new MallBizException("PARAM_INVALID", "决策不存在: " + id);
        }
        return task;
    }

    private String normalizeSource(String source) {
        String value = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || SOURCE_HUMAN.equals(value)) {
            return SOURCE_HUMAN;
        }
        if (SOURCE_AI.equals(value)) {
            return SOURCE_AI;
        }
        throw new MallBizException("PARAM_INVALID", "非法决策来源: " + source + "（仅 ai/human）");
    }

    private String normalizeDirection(String direction) {
        if (DIRECTION_UP.equals(direction) || DIRECTION_DOWN.equals(direction)) {
            return direction;
        }
        throw new MallBizException("PARAM_INVALID",
                "目标方向必须是 UP 或 DOWN（当前: " + direction + "），无法判定指标好坏（§20.3）");
    }

    /** 窗口天数：入参 > 决策已存 > 配置默认；必须 > 0 */
    private int resolveWindowDays(Integer requested, Integer stored) {
        Integer value = requested != null && requested > 0 ? requested : stored;
        int days = value != null && value > 0 ? value : defaultEvalWindowDays;
        if (days <= 0) {
            throw new MallBizException("PARAM_INVALID", "评价窗口天数必须大于 0");
        }
        return days;
    }

    private static String requireText(String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new MallBizException("PARAM_INVALID", "缺少必填字段: " + field);
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }

    /** 审计摘要：状态与关键业务字段（不含任何敏感值） */
    private static String digestOf(DecisionTask task) {
        return OperationAuditService.digest(
                "no", task.getDecisionNo(),
                "status", task.getStatus(),
                "source", task.getSource(),
                "metric", task.getTargetMetricCode(),
                "direction", task.getTargetDirection(),
                "owner", task.getOwner(),
                "baseline", task.getBaselineValue() == null ? null : task.getBaselineValue().toPlainString(),
                "baselineSnapshot", task.getBaselineSnapshotId(),
                "evalWindowDays", task.getEvalWindowDays() == null ? null : String.valueOf(task.getEvalWindowDays()));
    }
}
