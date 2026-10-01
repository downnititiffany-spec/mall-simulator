package com.graduation.analytics.decision;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.decision.entity.DecisionEvaluation;
import com.graduation.analytics.decision.entity.DecisionTask;
import com.graduation.analytics.decision.mapper.DecisionEvaluationMapper;
import com.graduation.analytics.decision.mapper.DecisionTaskMapper;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
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
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
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
 *   <li><b>等长窗口评价</b>：baseline 窗口 [approvedDate-N+1, approvedDate] 在批准时冻结逐日快照血缘；
 *       actual 窗口 [completedDate+1, completedDate+N] 只查同 runtimeProfileId、sourceId 和指标口径的已发布日快照；
 *       两端必须完整覆盖 N 天，窗口、快照号、样本数和口径版本一并落库；
 *       数据不足判 {@code INSUFFICIENT_DATA}（绝不因为「没数据/基线为 0」就判无效，§20.4）。</li>
 *   <li><b>非因果</b>：结论文案固定为「执行前后指标变化」，不做因果推断。</li>
 * </ul>
 *
 * <p><b>口径限制</b>：只对代码中已验证的可加和日指标计算窗口总和；UV/DAU、各类比率、复购率、客单价等
 * 非可加和指标在没有分子/分母或专属聚合公式前返回 {@code INSUFFICIENT_DATA}。决策效果对比是前后变化，
 * 不是因果推断。新增指标必须补公式、血缘和边界测试，禁止将每日比率直接平均或将 distinct 指标求和。</p>
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
    private final RuntimeProfileService runtimeProfileService;
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

    /** 当前 ACTIVE 快照中的指标观测，仅用于批准时确定当前数据源及指标口径。 */
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
            throw new PlatformBizException("PARAM_INVALID", "请求体不能为空");
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
        requireEvidenceSourceConsistent(task.getSuggestionSnapshotId());
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
            throw new PlatformBizException("PARAM_INVALID",
                    "提交审批前必须齐备（§20.3），缺失: " + String.join(", ", missing));
        }
        requireEvidenceSourceConsistent(task.getSuggestionSnapshotId());
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
            throw new PlatformBizException("PARAM_INVALID",
                    "必须设置负责人与截止时间（owner/dueDate）");
        }
        int windowDays = resolveWindowDays(req == null ? null : req.evalWindowDays(), task.getEvalWindowDays());

        // ACTIVE 快照确定来源与当前指标版本；窗口数据从同源已发布历史快照中逐日取数。
        Observation activeValue = observe(task.getTargetMetricCode(), null);
        if (activeValue.isEmpty()) {
            throw new PlatformBizException("PARAM_INVALID", "当前快照缺少目标指标 " + task.getTargetMetricCode()
                    + " 的基线，无法批准");
        }
        MetricSnapshot activeSnapshot = metricStore.findSnapshot(activeValue.snapshotId());
        if (activeSnapshot == null || activeSnapshot.getRuntimeProfileId() == null
                || activeSnapshot.getSourceId() == null
                || activeValue.metricDefinitionVersion() == null) {
            throw new PlatformBizException("PARAM_INVALID", "当前指标快照缺少 runtimeProfileId、sourceId 或指标口径版本，"
                    + "无法冻结完整基线窗口");
        }
        LocalDateTime approvedAt = LocalDateTime.now();
        LocalDate approvedDate = approvedAt.toLocalDate();
        LocalDate baselineStart = approvedDate.minusDays(windowDays - 1L);
        List<MetricStore.WindowMetricValue> baselinePoints = metricStore.queryWindow(
                new MetricStore.WindowMetricQuery(activeSnapshot.getRuntimeProfileId(), activeSnapshot.getSourceId(),
                        task.getTargetMetricCode(),
                        baselineStart, approvedDate, activeValue.metricDefinitionVersion()));
        DecisionWindowAggregator.Result baseline = DecisionWindowAggregator.aggregate(task.getTargetMetricCode(),
                activeSnapshot.getRuntimeProfileId(), activeSnapshot.getSourceId(),
                activeValue.metricDefinitionVersion(), baselineStart, approvedDate, baselinePoints);
        if (!baseline.sufficient()) {
            throw new PlatformBizException("PARAM_INVALID", "基线窗口尚不满足批准条件："
                    + baseline.insufficientReason());
        }

        String before = digestOf(task);
        task.setOwner(owner);
        task.setDueDate(req.dueDate());
        task.setTargetValue(req.targetValue());
        task.setEvalWindowDays(windowDays);
        task.setBaselineValue(baseline.value());
        task.setBaselineSnapshotId(baseline.snapshotIds().get(baseline.snapshotIds().size() - 1));
        task.setBaselineSnapshotRefs(encodeSnapshotRefs(baseline.snapshotIds()));
        task.setBaselineWindowStart(baselineStart);
        task.setBaselineWindowEnd(approvedDate);
        task.setRuntimeProfileId(activeSnapshot.getRuntimeProfileId());
        task.setSourceId(activeSnapshot.getSourceId());
        task.setDefinitionVersion(activeValue.metricDefinitionVersion());
        task.setApprovedBy(actor.userId());
        task.setApprovedAt(approvedAt);
        task.setApprovalNote(composeApprovalNote(req.note(), baseline, windowDays, baselineStart, approvedDate));
        task.setStatus(DecisionStateMachine.APPROVED);
        task.setUpdatedAt(approvedAt);
        taskMapper.updateById(task);

        audit.success(actor, OperationAuditService.ACTION_DECISION_APPROVE,
                OperationAuditService.RESOURCE_DECISION_TASK, String.valueOf(id), before, digestOf(task),
                "批准，基线窗口快照=" + baseline.snapshotIds()
                        + "，窗口=" + windowDays + " 天");
        log.info("决策 {} 批准 by={} 基线窗口聚合值={}（快照 {}）窗口 {} 天", task.getDecisionNo(), actor.userId(),
                baseline.value(), baseline.snapshotIds(), windowDays);
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
     * baseline = 批准时冻结的逐日数据在 [approvedDate-N+1, approvedDate] 的聚合；
     * actual = 同运行环境、同来源、同指标口径的逐日数据在 [completedDate+1, completedDate+N] 的聚合；
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
            throw new PlatformBizException("PARAM_INVALID", "决策缺少批准时间或完成时间，无法计算评价窗口");
        }
        LocalDate approvedDate = task.getApprovedAt().toLocalDate();
        LocalDate completedDate = task.getCompletedAt().toLocalDate();
        LocalDate baselineWindowStart = approvedDate.minusDays(windowDays - 1L);
        LocalDate actualWindowStart = completedDate.plusDays(1);
        LocalDate actualWindowEnd = completedDate.plusDays(windowDays);

        List<String> pinnedBaselineRefs = decodeSnapshotRefs(task.getBaselineSnapshotRefs());
        List<MetricStore.WindowMetricValue> actualPoints = task.getRuntimeProfileId() == null
                || task.getSourceId() == null
                || task.getDefinitionVersion() == null ? List.of()
                : metricStore.queryWindow(new MetricStore.WindowMetricQuery(task.getRuntimeProfileId(),
                        task.getSourceId(), metricCode,
                        actualWindowStart, actualWindowEnd, task.getDefinitionVersion()));
        DecisionWindowAggregator.Result actual = DecisionWindowAggregator.aggregate(metricCode,
                task.getRuntimeProfileId(), task.getSourceId(),
                task.getDefinitionVersion(), actualWindowStart, actualWindowEnd, actualPoints);

        DecisionEvaluation evaluation = new DecisionEvaluation();
        evaluation.setDecisionId(id);
        evaluation.setEvaluatedBy(actor.userId());
        evaluation.setCreatedAt(LocalDateTime.now());
        evaluation.setEvalWindowDays(windowDays);
        evaluation.setWindowStart(actualWindowStart);
        evaluation.setWindowEnd(actualWindowEnd);
        evaluation.setBaselineWindowStart(baselineWindowStart);
        evaluation.setBaselineWindowEnd(approvedDate);
        evaluation.setRuntimeProfileId(task.getRuntimeProfileId());
        evaluation.setSourceId(task.getSourceId());
        evaluation.setMetricDefinitionVersion(task.getDefinitionVersion());
        evaluation.setBaselineSnapshotId(task.getBaselineSnapshotId());
        evaluation.setBaselineSnapshotRefs(task.getBaselineSnapshotRefs());
        evaluation.setActualSnapshotRefs(encodeSnapshotRefs(actual.snapshotIds()));
        evaluation.setActualSnapshotId(actual.snapshotIds().isEmpty() ? null
                : actual.snapshotIds().get(actual.snapshotIds().size() - 1));
        evaluation.setBaselinePeriodValue(task.getBaselineValue());
        evaluation.setActualPeriodValue(actual.value());
        evaluation.setBaselineSampleCount(pinnedBaselineRefs.size());
        evaluation.setActualSampleCount(actual.sampleCount());
        evaluation.setSampleCount(pinnedBaselineRefs.size() + actual.sampleCount());
        evaluation.setDefinitionVersion(evalDefinitionVersion);
        evaluation.setBaselineValue(task.getBaselineValue());
        if (evaluation.getBaselineValue() == null) {
            throw new PlatformBizException("PARAM_INVALID", "决策缺少基线值，无法评价（批准时未锁定基线）");
        }

        String insufficient = insufficientWindowReason(task, pinnedBaselineRefs, actual, windowDays,
                baselineWindowStart, approvedDate, actualWindowStart, actualWindowEnd);
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
            evaluation.setNote(windowNote(task, baselineValue, actualValue, actual.snapshotIds(), rate,
                    direction, windowDays, actualWindowStart, actualWindowEnd, baselineWindowStart, approvedDate,
                    evaluation.getResult(), pinnedBaselineRefs.size(), actual.sampleCount()));
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

    public record DecisionPage(List<DecisionTask> items, long page, int size, long total, long totalPages,
                               String sort) {
    }

    /** Paginated read view for the decision center; legacy list(int) remains unchanged. */
    public DecisionPage listPage(long page, int size, String sort) {
        if (page < 1 || size < 1 || size > 100) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "page 必须大于等于 1，size 必须在 1 到 100 之间");
        }
        String[] parts = sort == null ? new String[0] : sort.trim().toLowerCase(Locale.ROOT).split(",", -1);
        if (parts.length < 1 || parts.length > 2 || parts[0].isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sort 格式应为 字段[,asc|desc]");
        }
        String field = parts[0];
        String direction = parts.length == 1 ? "asc" : parts[1];
        if (!List.of("id", "createdat", "updatedat", "duedate", "status").contains(field)
                || !("asc".equals(direction) || "desc".equals(direction))) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sort 字段或方向不受支持");
        }
        LambdaQueryWrapper<DecisionTask> query = new LambdaQueryWrapper<>();
        switch (field) {
            case "createdat" -> { if ("asc".equals(direction)) query.orderByAsc(DecisionTask::getCreatedAt); else query.orderByDesc(DecisionTask::getCreatedAt); }
            case "updatedat" -> { if ("asc".equals(direction)) query.orderByAsc(DecisionTask::getUpdatedAt); else query.orderByDesc(DecisionTask::getUpdatedAt); }
            case "duedate" -> { if ("asc".equals(direction)) query.orderByAsc(DecisionTask::getDueDate); else query.orderByDesc(DecisionTask::getDueDate); }
            case "status" -> { if ("asc".equals(direction)) query.orderByAsc(DecisionTask::getStatus); else query.orderByDesc(DecisionTask::getStatus); }
            default -> { if ("asc".equals(direction)) query.orderByAsc(DecisionTask::getId); else query.orderByDesc(DecisionTask::getId); }
        }
        query.orderByDesc(DecisionTask::getId);
        long total = taskMapper.selectCount(new LambdaQueryWrapper<>());
        long totalPages = Math.max(1, (total + size - 1) / size);
        if (page > totalPages) page = totalPages;
        long offset = Math.multiplyExact(page - 1, (long) size);
        List<DecisionTask> items = taskMapper.selectList(query.last("LIMIT " + size + " OFFSET " + offset));
        return new DecisionPage(items, page, size, total, totalPages, field + "," + direction);
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

    /**
     * 03.5「跨源证据拒绝并留审计」（D-034）：suggestionSnapshotId 是唯一能把别源快照塞进
     * 决策证据链的入口——前端隐藏跨源选项拦不住构造 API 调用，故在 service 层解析回源：
     * 快照 → runtime_profile → source_id，与当前 ACTIVE 运行环境的 source_id 比对（比
     * profile id 宽一档：同源换版本/换环境不算跨源）。
     *
     * <ul>
     *   <li>快照不存在 → {@code PARAM_INVALID}(400)：快照号是参数化注入面，不当"未知源"处理；</li>
     *   <li>归属别源 → {@code SOURCE_MISMATCH}(409)：证据与当前状态不满足一致性前提，与
     *       SOURCE_NOT_BOUND/MAPPING_* 同族（见 {@link PlatformBizException#SOURCE_MISMATCH}）；
     *       controller 既有包装器把业务异常落审计 FAILED 行，无需新机制；</li>
     *   <li>无 ACTIVE 运行环境 → {@code PARAM_INVALID}(400)：fail-closed——没有权威比较基准时
     *       无法区分同源/跨源，放行等于把"未核对"伪装成"已核对"（与 SOURCE_NOT_BOUND 同一姿态）。</li>
     * </ul>
     *
     * <p>创建与提交两处都调用：创建时拒绝可防脏草稿入库；提交是证据锚点的绑定动作，
     * 创建后激活源可能已切换，故提交时必须重核。基线/评价路径不经此守卫——baseline 在
     * approve 时从最新 ACTIVE 快照钉住，与激活环境天然同源。</p>
     */
    private void requireEvidenceSourceConsistent(String suggestionSnapshotId) {
        String snapshotId = trimToNull(suggestionSnapshotId);
        if (snapshotId == null) {
            return; // 走 evidence_package_id 证据路径，与快照归属无关
        }
        MetricSnapshot snap = metricStore.findSnapshot(snapshotId);
        if (snap == null) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "建议快照不存在: " + snapshotId + "（请核对快照号，不存在或已删除）");
        }
        if (snap.getRuntimeProfileId() == null) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "建议快照缺少运行环境归属（snapshot_id=" + snapshotId + "），无法核对证据来源");
        }
        RuntimeProfile active = runtimeProfileService.findActive().orElseThrow(() ->
                new PlatformBizException(PlatformBizException.PARAM_INVALID,
                        "当前无 ACTIVE 运行环境，无法核对证据快照的来源归属（请先完成激活流程 §8.3）"));
        RuntimeProfile evidenceProfile = runtimeProfileService.get(snap.getRuntimeProfileId());
        if (evidenceProfile == null || !Objects.equals(evidenceProfile.getSourceId(), active.getSourceId())) {
            long evidenceSource = evidenceProfile == null || evidenceProfile.getSourceId() == null
                    ? -1L : evidenceProfile.getSourceId();
            throw new PlatformBizException(PlatformBizException.SOURCE_MISMATCH,
                    "证据快照与当前激活源不一致，拒绝跨源证据（03.5）：快照 " + snapshotId
                            + " 属 source_id=" + evidenceSource
                            + "，当前 ACTIVE 运行环境属 source_id=" + active.getSourceId()
                            + "；请使用本源快照，或先切换激活源");
        }
    }

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

    /** 数据不足的原因（返回 null 表示可以出结论）；任何不完整或不可复核窗口均 fail-closed。 */
    private String insufficientWindowReason(DecisionTask task, List<String> baselineRefs,
                                            DecisionWindowAggregator.Result actual, int windowDays,
                                            LocalDate baselineWindowStart, LocalDate approvedDate,
                                            LocalDate actualWindowStart, LocalDate actualWindowEnd) {
        if (task.getBaselineValue() == null || task.getRuntimeProfileId() == null || task.getSourceId() == null
                || task.getDefinitionVersion() == null || baselineRefs.size() != windowDays
                || !baselineWindowStart.equals(task.getBaselineWindowStart())
                || !approvedDate.equals(task.getBaselineWindowEnd())) {
            return "基线窗口证据不完整（历史单快照记录无法证明完整 N 日覆盖），不能得出效果结论";
        }
        if (!actual.sufficient()) {
            return "实际窗口 " + actualWindowStart + "~" + actualWindowEnd + " 数据不足："
                    + actual.insufficientReason() + "；按 §20.4 不判为无效";
        }
        if (task.getBaselineValue().compareTo(BigDecimal.ZERO) == 0) {
            return "基线窗口聚合值为 0，相对改善率无定义（基线窗口 " + baselineWindowStart + "~"
                    + approvedDate + "；按 §20.4 不判无效）";
        }
        return null;
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

    /** 结论文案：逐日窗口比较、列明血缘，明确不是因果推断（§20.4）。 */
    private String windowNote(DecisionTask task, BigDecimal baselineValue, BigDecimal actualValue,
                              List<String> actualSnapshotIds, BigDecimal rate, String direction,
                              int windowDays, LocalDate actualWindowStart, LocalDate actualWindowEnd,
                              LocalDate baselineWindowStart, LocalDate approvedDate, String result,
                              int baselineSamples, int actualSamples) {
        StringBuilder sb = new StringBuilder();
        sb.append("执行前后指标变化（非因果推断）：").append(task.getTargetMetricCode())
                .append(' ').append(baselineValue.stripTrailingZeros().toPlainString())
                .append("（基线窗口 ").append(baselineWindowStart).append('~').append(approvedDate)
                .append("，逐日样本 ").append(baselineSamples).append("，快照 ")
                .append(decodeSnapshotRefs(task.getBaselineSnapshotRefs())).append(')')
                .append(" → ").append(actualValue.stripTrailingZeros().toPlainString())
                .append("（评价窗口 ").append(actualWindowStart).append('~').append(actualWindowEnd)
                .append("，逐日样本 ").append(actualSamples).append("，快照 ")
                .append(actualSnapshotIds).append(')')
                .append("；改善率 ").append(rate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP))
                .append("%（方向 ").append(direction).append("，窗口 ").append(windowDays).append(" 天")
                .append("，同运行环境 runtimeProfileId=").append(task.getRuntimeProfileId())
                .append("、同源 sourceId=").append(task.getSourceId())
                .append("，指标口径 ").append(task.getDefinitionVersion())
                .append("，评价算法 ").append(evalDefinitionVersion).append("）；结论 ").append(result);
        if (task.getTargetValue() != null) {
            sb.append("，目标值 ").append(task.getTargetValue().stripTrailingZeros().toPlainString());
        }
        return truncate(sb.toString(), 512);
    }

    /** 审批备注：人工备注 + 已冻结的逐日基线窗口血缘。 */
    private String composeApprovalNote(String userNote, DecisionWindowAggregator.Result baseline, int windowDays,
                                       LocalDate windowStart, LocalDate approvedDate) {
        String trace = "基线窗口聚合值=" + baseline.value() + ";基线快照=" + baseline.snapshotIds()
                + ";基线窗口=" + windowStart + "~" + approvedDate + ";窗口=" + windowDays + "天";
        String note = trimToNull(userNote);
        return truncate(note == null ? trace : note + ";" + trace, 512);
    }

    // ── 内部：通用 ───────────────────────────────────────────────────────

    private DecisionTask require(Long id) {
        DecisionTask task = id == null ? null : taskMapper.selectById(id);
        if (task == null) {
            throw new PlatformBizException("PARAM_INVALID", "决策不存在: " + id);
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
        throw new PlatformBizException("PARAM_INVALID", "非法决策来源: " + source + "（仅 ai/human）");
    }

    private String normalizeDirection(String direction) {
        if (DIRECTION_UP.equals(direction) || DIRECTION_DOWN.equals(direction)) {
            return direction;
        }
        throw new PlatformBizException("PARAM_INVALID",
                "目标方向必须是 UP 或 DOWN（当前: " + direction + "），无法判定指标好坏（§20.3）");
    }

    /** 窗口天数：入参 > 决策已存 > 配置默认；必须 > 0 */
    private int resolveWindowDays(Integer requested, Integer stored) {
        Integer value = requested != null && requested > 0 ? requested : stored;
        int days = value != null && value > 0 ? value : defaultEvalWindowDays;
        if (days <= 0 || days > 90) {
            throw new PlatformBizException("PARAM_INVALID", "评价窗口天数必须在 1 到 90 天之间");
        }
        return days;
    }

    private static String encodeSnapshotRefs(List<String> snapshotIds) {
        return snapshotIds.stream()
                .map(id -> Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(id.getBytes(StandardCharsets.UTF_8)))
                .collect(Collectors.joining(","));
    }

    private static List<String> decodeSnapshotRefs(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        try {
            List<String> ids = new ArrayList<>();
            for (String token : encoded.split(",", -1)) {
                if (token.isBlank()) {
                    return List.of();
                }
                ids.add(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            }
            return List.copyOf(ids);
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
    }

    private static String requireText(String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new PlatformBizException("PARAM_INVALID", "缺少必填字段: " + field);
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
