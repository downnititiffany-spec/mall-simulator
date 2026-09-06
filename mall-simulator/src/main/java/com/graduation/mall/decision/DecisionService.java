package com.graduation.mall.decision;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.mall.decision.entity.DecisionEvaluation;
import com.graduation.mall.decision.entity.DecisionTask;
import com.graduation.mall.decision.mapper.DecisionEvaluationMapper;
import com.graduation.mall.decision.mapper.DecisionTaskMapper;
import com.graduation.mall.metric.MetricStore;
import com.graduation.mall.metric.entity.MetricValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 决策中心服务（§22.6/§21.10）：
 * - AI 只能创建 DRAFT（source=ai 强制）；从 PENDING_REVIEW 到 APPROVED 必须人工；
 * - APPROVED 时锁定基线（当前 ACTIVE 快照目标指标值）；
 * - evaluate：actual=当前 ACTIVE 快照同指标值，improve=(a-b)/|b|，DOWN 方向取反；
 *   分级 EFFECTIVE/PARTIAL/INEFFECTIVE/INSUFFICIENT_DATA（前后对比≠因果推断）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionService {

    private static final BigDecimal EFFECTIVE_THRESHOLD = new BigDecimal("0.05");

    private final DecisionTaskMapper taskMapper;
    private final DecisionEvaluationMapper evaluationMapper;
    private final MetricStore metricStore;

    public record CreateDraftReq(String title, String action, String targetMetricCode,
                                 String targetDirection, String suggestionSnapshotId, String risk) {
    }

    public record ApproveReq(String owner, LocalDate dueDate, BigDecimal targetValue,
                             Integer evalWindowDays) {
    }

    // ── 创建（AI 只能 DRAFT） ────────────────────────────────────────────

    public DecisionTask createDraft(CreateDraftReq req, String createdBy, String source) {
        DecisionTask task = new DecisionTask();
        task.setDecisionNo("DC-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
                + "-" + UUID.randomUUID().toString().substring(0, 4));
        task.setSource("ai".equalsIgnoreCase(source) ? "ai" : "human");
        task.setTitle(req.title());
        task.setAction(req.action());
        task.setTargetMetricCode(req.targetMetricCode());
        task.setTargetDirection(req.targetDirection());
        task.setSuggestionSnapshotId(req.suggestionSnapshotId());
        task.setRisk(req.risk());
        task.setStatus(DecisionStateMachine.DRAFT);
        task.setEvalWindowDays(3);
        task.setCreatedBy(createdBy);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
    }

    // ── 审核流 ───────────────────────────────────────────────────────────

    public DecisionTask transition(Long id, String to, String operator) {
        DecisionTask task = require(id);
        DecisionStateMachine.validate(task.getStatus(), to);
        task.setStatus(to);
        task.setUpdatedAt(LocalDateTime.now());
        switch (to) {
            case DecisionStateMachine.REJECTED -> task.setRejectReason("由 " + operator + " 驳回");
            case DecisionStateMachine.IN_PROGRESS -> task.setStartedAt(LocalDateTime.now());
            case DecisionStateMachine.COMPLETED -> task.setCompletedAt(LocalDateTime.now());
            default -> {
            }
        }
        taskMapper.updateById(task);
        return task;
    }

    /** 批准：必须设置负责人/期限/目标指标，并锁定基线（§22.7） */
    public DecisionTask approve(Long id, ApproveReq req, String approver) {
        DecisionTask task = require(id);
        DecisionStateMachine.validate(task.getStatus(), DecisionStateMachine.APPROVED);
        if (task.getTargetMetricCode() == null || task.getTargetMetricCode().isBlank()) {
            throw new IllegalArgumentException("无目标指标的决策不能进入正式任务（§22.3）");
        }
        if (req.owner() == null || req.owner().isBlank() || req.dueDate() == null) {
            throw new IllegalArgumentException("必须设置负责人与截止时间");
        }
        BigDecimal baseline = currentMetricValue(task.getTargetMetricCode());
        if (baseline == null) {
            throw new IllegalArgumentException("当前快照缺少目标指标 " + task.getTargetMetricCode()
                    + " 的基线，无法批准");
        }
        task.setOwner(req.owner());
        task.setDueDate(req.dueDate());
        task.setTargetValue(req.targetValue());
        task.setEvalWindowDays(req.evalWindowDays() == null ? 3 : req.evalWindowDays());
        task.setBaselineValue(baseline);
        task.setApprovedBy(approver);
        task.setStatus(DecisionStateMachine.APPROVED);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("decision {} approved by {} baseline={} (metric {})",
                task.getDecisionNo(), approver, baseline, task.getTargetMetricCode());
        return task;
    }

    /** 取消（执行中，必须记录原因） */
    public DecisionTask cancel(Long id, String reason, String operator) {
        DecisionTask task = require(id);
        DecisionStateMachine.validate(task.getStatus(), DecisionStateMachine.CANCELLED);
        task.setCancelReason(reason == null || reason.isBlank() ? "由 " + operator + " 取消" : reason);
        task.setStatus(DecisionStateMachine.CANCELLED);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    // ── 效果评价（§21.10） ───────────────────────────────────────────────

    /**
     * 评价：actual=当前 ACTIVE 快照目标指标值；
     * improve=(actual−baseline)/|baseline|；target_direction=DOWN 时取反。
     */
    public DecisionEvaluation evaluate(Long id, String evaluator) {
        DecisionTask task = require(id);
        DecisionStateMachine.validate(task.getStatus(), DecisionStateMachine.EVALUATING);
        if (task.getBaselineValue() == null) {
            throw new IllegalStateException("缺少基线，无法评价");
        }
        BigDecimal actual = currentMetricValue(task.getTargetMetricCode());
        DecisionEvaluation evaluation = new DecisionEvaluation();
        evaluation.setDecisionId(id);
        evaluation.setBaselineValue(task.getBaselineValue());
        evaluation.setEvaluatedBy(evaluator);
        evaluation.setCreatedAt(LocalDateTime.now());
        if (actual == null) {
            evaluation.setResult(DecisionStateMachine.INSUFFICIENT_DATA);
            evaluation.setNote("评价窗口内无目标指标快照数据");
        } else {
            BigDecimal diff = actual.subtract(task.getBaselineValue());
            BigDecimal rate = diff.divide(task.getBaselineValue().abs(), 4, RoundingMode.HALF_UP);
            if ("DOWN".equalsIgnoreCase(task.getTargetDirection())) {
                rate = rate.negate(); // 越低越好指标取反
            }
            evaluation.setActualValue(actual);
            evaluation.setImprovementRate(rate);
            String result;
            String note;
            if (rate.compareTo(EFFECTIVE_THRESHOLD) >= 0) {
                result = DecisionStateMachine.EFFECTIVE;
                note = "改善率 " + rate + " ≥ 5%，目标达成";
            } else if (rate.compareTo(BigDecimal.ZERO) > 0) {
                result = DecisionStateMachine.PARTIAL;
                note = "改善率 " + rate + " > 0 但未达 5%";
            } else {
                result = DecisionStateMachine.INEFFECTIVE;
                note = "改善率 " + rate + " ≤ 0，目标未达成";
            }
            evaluation.setResult(result);
            evaluation.setNote(note + "（前后对比，非因果推断）");
        }
        evaluationMapper.insert(evaluation);

        task.setStatus(evaluation.getResult());
        task.setEvaluatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
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

    // ── 内部 ─────────────────────────────────────────────────────────────

    private BigDecimal currentMetricValue(String metricCode) {
        List<MetricValue> values = metricStore.query(new MetricStore.MetricQuery(null, true));
        return values.stream()
                .filter(v -> v.getMetricCode().equals(metricCode))
                .map(MetricValue::getMetricValue)
                .findFirst().orElse(null);
    }

    private DecisionTask require(Long id) {
        DecisionTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new IllegalArgumentException("决策不存在: " + id);
        }
        return task;
    }
}