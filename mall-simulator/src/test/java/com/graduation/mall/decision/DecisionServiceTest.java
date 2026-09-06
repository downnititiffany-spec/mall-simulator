package com.graduation.mall.decision;

import com.graduation.mall.decision.DecisionService.ApproveReq;
import com.graduation.mall.decision.DecisionService.CreateDraftReq;
import com.graduation.mall.decision.DecisionStateMachine.IllegalDecisionStateException;
import com.graduation.mall.decision.entity.DecisionEvaluation;
import com.graduation.mall.decision.entity.DecisionTask;
import com.graduation.mall.metric.entity.MetricSnapshot;
import com.graduation.mall.metric.entity.MetricValue;
import com.graduation.mall.metric.mapper.MetricSnapshotMapper;
import com.graduation.mall.metric.mapper.MetricValueMapper;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策闭环集成测试（§22.6/§22.7/§21.10）：
 * AI 只能建 DRAFT；批准锁定基线；UP/DOWN 方向评价；数据不足识别；非法流转拒绝。
 */
class DecisionServiceTest extends MallTestSupport {

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private MetricSnapshotMapper snapshotMapper;

    @Autowired
    private MetricValueMapper valueMapper;

    private MetricSnapshot activeSnapshot(String snapshotId) {
        MetricSnapshot s = new MetricSnapshot();
        s.setSnapshotId(snapshotId);
        s.setRuntimeProfileId(1L);
        s.setBusinessTime(LocalDateTime.of(2026, 9, 5, 0, 0));
        s.setStatus(MetricSnapshot.STATUS_ACTIVE);
        s.setVersion(1);
        s.setDataUpdatedAt(LocalDateTime.now());
        s.setSource("test");
        s.setCreatedAt(LocalDateTime.now());
        snapshotMapper.insert(s);
        return s;
    }

    private void putMetric(String snapshotId, String code, BigDecimal value) {
        MetricValue v = new MetricValue();
        v.setSnapshotId(snapshotId);
        v.setMetricCode(code);
        v.setMetricValue(value);
        v.setUnit("元");
        v.setPeriod("day:2026-09-05");
        v.setDefinitionVersion("v1");
        valueMapper.insert(v);
    }

    @BeforeEach
    void seedActiveSnapshot() {
        activeSnapshot("S_DEC");
        putMetric("S_DEC", "gmv", new BigDecimal("3702.50"));
        putMetric("S_DEC", "refund_rate", new BigDecimal("0.12"));
    }

    private DecisionTask createDraft(String targetMetric, String direction) {
        return decisionService.createDraft(new CreateDraftReq(
                "补充安全库存", "为热销商品增加 500 件库存",
                targetMetric, direction, "S_DEC", "低"), "ai-user", "ai");
    }

    @Test
    @DisplayName("AI 草稿全流程：审核→批准锁定基线→执行→评价 EFFECTIVE")
    void fullClosedLoopUpMetric() {
        DecisionTask draft = createDraft("gmv", "UP");
        assertEquals(DecisionStateMachine.DRAFT, draft.getStatus());
        assertThrows(IllegalDecisionStateException.class, () ->
                decisionService.transition(draft.getId(), DecisionStateMachine.APPROVED, "demo"),
                "DRAFT 不能直接批准");

        decisionService.transition(draft.getId(), DecisionStateMachine.PENDING_REVIEW, "demo");
        // 审批人必须设置负责人/期限 → 基线从当前 ACTIVE 快照锁定 = 3702.50
        DecisionTask approved = decisionService.approve(draft.getId(),
                new ApproveReq("运营-小李", LocalDate.now().plusDays(3), new BigDecimal("4000.00"), 3),
                "admin");
        assertEquals(DecisionStateMachine.APPROVED, approved.getStatus());
        assertEquals(0, new BigDecimal("3702.50").compareTo(approved.getBaselineValue()), "批准必须锁定基线");

        decisionService.transition(draft.getId(), DecisionStateMachine.IN_PROGRESS, "demo");
        decisionService.transition(draft.getId(), DecisionStateMachine.COMPLETED, "demo");

        // 评价：新快照 gmv 提升到 4200（模拟执行效果）
        activeSnapshot("S_DEC_AFTER");
        putMetric("S_DEC_AFTER", "gmv", new BigDecimal("4200.00"));
        DecisionEvaluation evaluation = decisionService.evaluate(draft.getId(), "admin");
        assertEquals(DecisionStateMachine.EFFECTIVE, evaluation.getResult());
        // (4200-3702.5)/3702.5 ≈ 0.1344 ≥ 0.05
        assertTrue(evaluation.getImprovementRate().compareTo(new BigDecimal("0.13")) > 0);
        assertEquals(DecisionStateMachine.EFFECTIVE, decisionService.get(draft.getId()).getStatus(), "终态落库");
        assertNotNull(evaluation.getNote());
        assertTrue(evaluation.getNote().contains("非因果推断"), "必须声明非因果");
    }

    @Test
    @DisplayName("DOWN 指标（退款率）下降 = 改善率为正")
    void downMetricDirectionReversed() {
        DecisionTask draft = createDraft("refund_rate", "DOWN");
        decisionService.transition(draft.getId(), DecisionStateMachine.PENDING_REVIEW, "demo");
        decisionService.approve(draft.getId(),
                new ApproveReq("运营-小王", LocalDate.now().plusDays(5), new BigDecimal("0.06"), 3), "admin");
        decisionService.transition(draft.getId(), DecisionStateMachine.IN_PROGRESS, "demo");
        decisionService.transition(draft.getId(), DecisionStateMachine.COMPLETED, "demo");

        activeSnapshot("S_DEC_AFTER2");
        putMetric("S_DEC_AFTER2", "refund_rate", new BigDecimal("0.08")); // 0.12 → 0.08 下降
        DecisionEvaluation evaluation = decisionService.evaluate(draft.getId(), "admin");
        // (0.08-0.12)/0.12 = -0.3333 → 取反 = +0.3333 → EFFECTIVE
        assertEquals(DecisionStateMachine.EFFECTIVE, evaluation.getResult());
        assertTrue(evaluation.getImprovementRate().compareTo(BigDecimal.ZERO) > 0, "DOWN 指标下降应计为正改善");
    }

    @Test
    @DisplayName("评价窗口无目标指标数据 → INSUFFICIENT_DATA")
    void insufficientData() {
        DecisionTask draft = createDraft("gmv", "UP");
        decisionService.transition(draft.getId(), DecisionStateMachine.PENDING_REVIEW, "demo");
        decisionService.approve(draft.getId(),
                new ApproveReq("运营-小张", LocalDate.now().plusDays(3), new BigDecimal("4000.00"), 3), "admin");
        decisionService.transition(draft.getId(), DecisionStateMachine.IN_PROGRESS, "demo");
        decisionService.transition(draft.getId(), DecisionStateMachine.COMPLETED, "demo");

        // 当前 ACTIVE 快照中删除 gmv → 评价窗口无目标指标数据
        valueMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MetricValue>()
                .eq(MetricValue::getMetricCode, "gmv"));
        DecisionEvaluation evaluation = decisionService.evaluate(draft.getId(), "admin");
        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, evaluation.getResult());
        assertTrue(evaluation.getActualValue() == null);
    }

    @Test
    @DisplayName("无目标指标的 AI 建议不能创建正式任务（§22.3）")
    void noTargetMetricCannotApprove() {
        DecisionTask draft = decisionService.createDraft(new CreateDraftReq(
                "一般提示", "优化页面加载速度", null, null, "S_DEC", null), "ai-user", "ai");
        decisionService.transition(draft.getId(), DecisionStateMachine.PENDING_REVIEW, "demo");
        assertThrows(IllegalArgumentException.class, () -> decisionService.approve(draft.getId(),
                new ApproveReq("运营", LocalDate.now().plusDays(1), null, 3), "admin"),
                "无目标指标不能进入正式决策");
    }

    @Test
    @DisplayName("执行中可取消（记录原因）；终态不可再流转")
    void cancelAndTerminal() {
        DecisionTask draft = createDraft("gmv", "UP");
        decisionService.transition(draft.getId(), DecisionStateMachine.PENDING_REVIEW, "demo");
        decisionService.approve(draft.getId(),
                new ApproveReq("运营", LocalDate.now().plusDays(3), null, 3), "admin");
        decisionService.transition(draft.getId(), DecisionStateMachine.IN_PROGRESS, "demo");
        DecisionTask cancelled = decisionService.cancel(draft.getId(), "库存策略调整", "demo");
        assertEquals(DecisionStateMachine.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getCancelReason());
        assertThrows(IllegalDecisionStateException.class, () ->
                decisionService.transition(draft.getId(), DecisionStateMachine.COMPLETED, "demo"));
    }
}