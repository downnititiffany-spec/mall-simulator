package com.graduation.analytics.decision;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.decision.DecisionService.ApproveReq;
import com.graduation.analytics.decision.DecisionService.CreateDraftReq;
import com.graduation.analytics.decision.DecisionService.SubmitReq;
import com.graduation.analytics.decision.entity.DecisionEvaluation;
import com.graduation.analytics.decision.entity.DecisionTask;
import com.graduation.analytics.decision.mapper.DecisionEvaluationMapper;
import com.graduation.analytics.decision.mapper.DecisionTaskMapper;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.entity.MetricValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 决策服务（R8-3 §3.3/§3.4、V2.0 §20.3/§20.4）：Mockito 打桩，不连库。
 *
 * <p>覆盖四类硬约束：①AI 只能 DRAFT；②提交前齐备校验；③身份只来自 AuditActor；
 * ④等长窗口评价（含 UP/DOWN、基线 0、无新快照、达目标值、未达阈值）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DecisionServiceTest {

    private static final String METRIC = "order_paid_amount";

    @Mock
    private DecisionTaskMapper taskMapper;
    @Mock
    private DecisionEvaluationMapper evaluationMapper;
    @Mock
    private MetricStore metricStore;
    @Mock
    private OperationAuditService audit;

    private DecisionService service;

    private final AuditActor alice = AuditActor.of("trace-1", "alice", "operator", "127.0.0.1");

    @BeforeEach
    void setUp() {
        service = new DecisionService(taskMapper, evaluationMapper, metricStore, audit);
        ReflectionTestUtils.setField(service, "defaultEvalWindowDays", 3);
        ReflectionTestUtils.setField(service, "effectiveThreshold", new BigDecimal("0.05"));
        ReflectionTestUtils.setField(service, "partialThreshold", BigDecimal.ZERO);
        ReflectionTestUtils.setField(service, "evalDefinitionVersion", "r8-window-v1");
    }

    // ── ① AI 只能 DRAFT ─────────────────────────────────────────────────

    @Test
    @DisplayName("createDraft：source=ai 也只能落 DRAFT，created_by 取登录用户")
    void createDraftIsAlwaysDraft() {
        DecisionTask saved = service.createDraft(new CreateDraftReq("提高支付转化", "调整商品详情页",
                METRIC, "UP", "S20260901_24", "LOW", "alice", "EV-1"), alice, "ai");

        assertEquals(DecisionStateMachine.DRAFT, saved.getStatus());
        assertEquals("ai", saved.getSource());
        assertEquals("alice", saved.getCreatedBy());
        assertNotNull(saved.getDecisionNo());
        verify(audit).success(any(AuditActor.class), anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("createDraft：非法 source 直接拒绝，不落库")
    void createDraftRejectsIllegalSource() {
        PlatformBizException e = assertThrows(PlatformBizException.class, () -> service.createDraft(
                new CreateDraftReq("t", "a", METRIC, "UP", null, null, "alice", null), alice, "robot"));
        assertEquals(PlatformBizException.PARAM_INVALID, e.getCode());
        verify(taskMapper, never()).insert(any(DecisionTask.class));
    }

    @Test
    @DisplayName("createDraft：title/action 缺失即 PARAM_INVALID")
    void createDraftRequiresTitleAndAction() {
        assertThrows(PlatformBizException.class, () -> service.createDraft(
                new CreateDraftReq(" ", "a", METRIC, "UP", null, null, "alice", null), alice, "ai"));
        assertThrows(PlatformBizException.class, () -> service.createDraft(
                new CreateDraftReq("t", "", METRIC, "UP", null, null, "alice", null), alice, "ai"));
    }

    // ── ② 提交齐备校验 ──────────────────────────────────────────────────

    @Test
    @DisplayName("submit：缺 owner/目标指标/方向/证据 → PARAM_INVALID 并逐项报缺")
    void submitRequiresAllFields() {
        DecisionTask task = draft();
        task.setOwner(null);
        task.setTargetMetricCode(null);
        task.setTargetDirection(null);
        task.setSuggestionSnapshotId(null);
        task.setEvidencePackageId(null);
        when(taskMapper.selectById(1L)).thenReturn(task);

        PlatformBizException e = assertThrows(PlatformBizException.class, () -> service.submit(1L, null, alice));
        assertEquals(PlatformBizException.PARAM_INVALID, e.getCode());
        assertTrue(e.getMessage().contains("owner"), e.getMessage());
        assertTrue(e.getMessage().contains("target_metric_code"), e.getMessage());
        assertTrue(e.getMessage().contains("target_direction"), e.getMessage());
        assertTrue(e.getMessage().contains("evidence"), e.getMessage());
        assertEquals(DecisionStateMachine.DRAFT, task.getStatus(), "校验失败不得改状态");
        verify(taskMapper, never()).updateById(any(DecisionTask.class));
    }

    @Test
    @DisplayName("submit：字段齐备 → PENDING_REVIEW，且前端发 {} 时用手上已有字段校验")
    void submitMovesToPendingReview() {
        DecisionTask task = completeDraft();
        when(taskMapper.selectById(1L)).thenReturn(task);

        DecisionTask after = service.submit(1L, new SubmitReq(null, null), alice);
        assertEquals(DecisionStateMachine.PENDING_REVIEW, after.getStatus());
        verify(taskMapper).updateById(task);
    }

    @Test
    @DisplayName("submit：approve 之后不能再提交（非法流转）")
    void submitRejectedFromApproved() {
        DecisionTask task = completeDraft();
        task.setStatus(DecisionStateMachine.APPROVED);
        when(taskMapper.selectById(1L)).thenReturn(task);

        assertThrows(DecisionStateMachine.IllegalDecisionStateException.class,
                () -> service.submit(1L, null, alice));
    }

    // ── ③ 身份与批准 ────────────────────────────────────────────────────

    @Test
    @DisplayName("approve：approved_by 取登录用户，并钉住基线值/基线快照/口径版本/批准时间")
    void approvePinsBaselineFromActor() {
        DecisionTask task = completeDraft();
        task.setStatus(DecisionStateMachine.PENDING_REVIEW);
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class)))
                .thenReturn(List.of(metric("S20260901_24", METRIC, "100", "day:2026-09-01", "metric-v1")));

        DecisionTask after = service.approve(1L,
                new ApproveReq("bob", LocalDate.of(2026, 9, 10), null, 3, "同意试点"), alice);

        assertEquals(DecisionStateMachine.APPROVED, after.getStatus());
        assertEquals("alice", after.getApprovedBy());
        assertEquals("bob", after.getOwner());
        assertEquals(0, new BigDecimal("100").compareTo(after.getBaselineValue()));
        assertEquals("S20260901_24", after.getBaselineSnapshotId());
        assertEquals("metric-v1", after.getDefinitionVersion());
        assertEquals(3, after.getEvalWindowDays());
        assertNotNull(after.getApprovedAt());
        assertTrue(after.getApprovalNote().contains("基线快照=S20260901_24"), after.getApprovalNote());
    }

    @Test
    @DisplayName("approve：缺 dueDate → PARAM_INVALID；缺目标指标 → PARAM_INVALID（不得成为正式任务）")
    void approveRequiresDueDateAndMetric() {
        DecisionTask task = completeDraft();
        task.setStatus(DecisionStateMachine.PENDING_REVIEW);
        when(taskMapper.selectById(1L)).thenReturn(task);
        assertThrows(PlatformBizException.class, () -> service.approve(1L, new ApproveReq("bob", null, null, 3, null), alice));

        DecisionTask noMetric = completeDraft();
        noMetric.setStatus(DecisionStateMachine.PENDING_REVIEW);
        noMetric.setTargetMetricCode(null);
        when(taskMapper.selectById(2L)).thenReturn(noMetric);
        assertThrows(PlatformBizException.class,
                () -> service.approve(2L, new ApproveReq("bob", LocalDate.now(), null, 3, null), alice));
    }

    @Test
    @DisplayName("approve：当前快照没有目标指标 → PARAM_INVALID（不能凭空批准）")
    void approveRejectsMissingBaseline() {
        DecisionTask task = completeDraft();
        task.setStatus(DecisionStateMachine.PENDING_REVIEW);
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenReturn(List.of());

        PlatformBizException e = assertThrows(PlatformBizException.class,
                () -> service.approve(1L, new ApproveReq("bob", LocalDate.now(), null, 3, null), alice));
        assertTrue(e.getMessage().contains("基线"), e.getMessage());
    }

    @Test
    @DisplayName("reject/cancel：必须填原因（§20.3），缺原因 → PARAM_INVALID 且状态不变")
    void rejectAndCancelRequireReason() {
        DecisionTask pending = completeDraft();
        pending.setStatus(DecisionStateMachine.PENDING_REVIEW);
        when(taskMapper.selectById(1L)).thenReturn(pending);
        PlatformBizException e = assertThrows(PlatformBizException.class, () -> service.reject(1L, "  ", alice));
        assertEquals(PlatformBizException.PARAM_INVALID, e.getCode());
        assertTrue(e.getMessage().contains("原因"), e.getMessage());
        assertEquals(DecisionStateMachine.PENDING_REVIEW, pending.getStatus());

        DecisionTask running = completeDraft();
        running.setStatus(DecisionStateMachine.IN_PROGRESS);
        when(taskMapper.selectById(2L)).thenReturn(running);
        assertThrows(PlatformBizException.class, () -> service.cancel(2L, null, alice));

        DecisionTask rejected = service.reject(1L, "指标口径不对", alice);
        assertEquals(DecisionStateMachine.REJECTED, rejected.getStatus());
        assertEquals("指标口径不对", rejected.getRejectReason());
    }

    @Test
    @DisplayName("start/complete：APPROVED→IN_PROGRESS→COMPLETED（完成 ≠ 有效）")
    void startAndComplete() {
        DecisionTask task = completeDraft();
        task.setStatus(DecisionStateMachine.APPROVED);
        when(taskMapper.selectById(1L)).thenReturn(task);

        assertEquals(DecisionStateMachine.IN_PROGRESS, service.start(1L, alice).getStatus());
        DecisionTask completed = service.complete(1L, "已上线", alice);
        assertEquals(DecisionStateMachine.COMPLETED, completed.getStatus());
        assertEquals("已上线", completed.getExecutionNote());
    }

    // ── ④ 等长窗口评价 ──────────────────────────────────────────────────

    @Test
    @DisplayName("evaluate：UP 改善 20% ≥ 阈值 → EFFECTIVE，窗口等长、落前后快照与样本数")
    void evaluateUpEffective() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenAnswer(inv -> {
            MetricStore.MetricQuery q = inv.getArgument(0);
            return "BS-1".equals(q.snapshotId())
                    ? List.of(metric("BS-1", METRIC, "100", "day:2026-09-01", "metric-v1"))
                    : List.of(metric("AS-2", METRIC, "120", "day:2026-09-05", "metric-v1"));
        });

        DecisionEvaluation evaluation = service.evaluate(1L, alice);

        assertEquals(DecisionStateMachine.EFFECTIVE, evaluation.getResult());
        assertEquals(0, new BigDecimal("0.2000").compareTo(evaluation.getImprovementRate()));
        assertEquals("BS-1", evaluation.getBaselineSnapshotId());
        assertEquals("AS-2", evaluation.getActualSnapshotId());
        assertEquals(0, new BigDecimal("100").compareTo(evaluation.getBaselinePeriodValue()));
        assertEquals(0, new BigDecimal("120").compareTo(evaluation.getActualPeriodValue()));
        assertEquals(2, evaluation.getSampleCount());
        assertEquals("r8-window-v1", evaluation.getDefinitionVersion());
        assertEquals("alice", evaluation.getEvaluatedBy());
        assertEquals(LocalDate.of(2026, 9, 5), evaluation.getWindowStart());
        assertEquals(LocalDate.of(2026, 9, 7), evaluation.getWindowEnd());
        assertEquals(3, evaluation.getEvalWindowDays());
        assertTrue(evaluation.getNote().contains("非因果"), evaluation.getNote());
        assertTrue(evaluation.getNote().contains("口径 r8-window-v1"), evaluation.getNote());
        assertEquals(DecisionStateMachine.EFFECTIVE, task.getStatus());
        assertNotNull(task.getEvaluatedAt());
    }

    @Test
    @DisplayName("evaluate：DOWN 指标下降即改善（取反后 20% → EFFECTIVE）")
    void evaluateDownDirectionInvertsRate() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        task.setTargetDirection("DOWN");
        task.setTargetMetricCode("refund_rate");
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenAnswer(inv -> {
            MetricStore.MetricQuery q = inv.getArgument(0);
            return "BS-1".equals(q.snapshotId())
                    ? List.of(metric("BS-1", "refund_rate", "100", "day:2026-09-01", "metric-v1"))
                    : List.of(metric("AS-2", "refund_rate", "80", "day:2026-09-05", "metric-v1"));
        });

        DecisionEvaluation evaluation = service.evaluate(1L, alice);
        assertEquals(DecisionStateMachine.EFFECTIVE, evaluation.getResult());
        assertEquals(0, new BigDecimal("0.2000").compareTo(evaluation.getImprovementRate()));
    }

    @Test
    @DisplayName("evaluate：改善 2% 未达 5% 阈值但为正 → PARTIAL")
    void evaluatePartial() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenAnswer(inv -> {
            MetricStore.MetricQuery q = inv.getArgument(0);
            return "BS-1".equals(q.snapshotId())
                    ? List.of(metric("BS-1", METRIC, "100", "day:2026-09-01", null))
                    : List.of(metric("AS-2", METRIC, "102", "day:2026-09-05", null));
        });

        assertEquals(DecisionStateMachine.PARTIAL, service.evaluate(1L, alice).getResult());
    }

    @Test
    @DisplayName("evaluate：指标未改善 → INEFFECTIVE")
    void evaluateIneffective() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenAnswer(inv -> {
            MetricStore.MetricQuery q = inv.getArgument(0);
            return "BS-1".equals(q.snapshotId())
                    ? List.of(metric("BS-1", METRIC, "100", "day:2026-09-01", null))
                    : List.of(metric("AS-2", METRIC, "90", "day:2026-09-05", null));
        });

        assertEquals(DecisionStateMachine.INEFFECTIVE, service.evaluate(1L, alice).getResult());
    }

    @Test
    @DisplayName("evaluate：达到目标值即 EFFECTIVE，即使改善率低于阈值")
    void evaluateTargetValueReached() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        task.setTargetValue(new BigDecimal("110"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenAnswer(inv -> {
            MetricStore.MetricQuery q = inv.getArgument(0);
            return "BS-1".equals(q.snapshotId())
                    ? List.of(metric("BS-1", METRIC, "100", "day:2026-09-01", null))
                    : List.of(metric("AS-2", METRIC, "110", "day:2026-09-05", null));
        });

        assertEquals(DecisionStateMachine.EFFECTIVE, service.evaluate(1L, alice).getResult());
    }

    @Test
    @DisplayName("evaluate：基线为 0 → INSUFFICIENT_DATA（不得判为无效）")
    void evaluateZeroBaselineIsInsufficient() {
        DecisionTask task = completedTask(BigDecimal.ZERO);
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenAnswer(inv -> {
            MetricStore.MetricQuery q = inv.getArgument(0);
            return "BS-1".equals(q.snapshotId())
                    ? List.of(metric("BS-1", METRIC, "0", "day:2026-09-01", null))
                    : List.of(metric("AS-2", METRIC, "10", "day:2026-09-05", null));
        });

        DecisionEvaluation evaluation = service.evaluate(1L, alice);
        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, evaluation.getResult());
        assertTrue(evaluation.getNote().contains("基线值为 0"), evaluation.getNote());
    }

    @Test
    @DisplayName("evaluate：完成后尚未发布新快照 → INSUFFICIENT_DATA")
    void evaluateSameSnapshotIsInsufficient() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class)))
                .thenReturn(List.of(metric("BS-1", METRIC, "100", "day:2026-09-01", null)));

        DecisionEvaluation evaluation = service.evaluate(1L, alice);
        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, evaluation.getResult());
        assertTrue(evaluation.getNote().contains("尚未发布新快照"), evaluation.getNote());
    }

    @Test
    @DisplayName("evaluate：新快照业务日仍 ≤ 完成日 → INSUFFICIENT_DATA（窗口未产生数据）")
    void evaluateBusinessDateInsideWindowRequired() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenAnswer(inv -> {
            MetricStore.MetricQuery q = inv.getArgument(0);
            return "BS-1".equals(q.snapshotId())
                    ? List.of(metric("BS-1", METRIC, "100", "day:2026-09-01", null))
                    : List.of(metric("AS-2", METRIC, "120", "day:2026-09-02", null));
        });

        DecisionEvaluation evaluation = service.evaluate(1L, alice);
        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, evaluation.getResult());
        assertTrue(evaluation.getNote().contains("完成日"), evaluation.getNote());
    }

    @Test
    @DisplayName("evaluate：无实际数据 / 缺批准时间 → 数据不足或 PARAM_INVALID，不得给结论")
    void evaluateHandlesMissingInputs() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenAnswer(inv -> {
            MetricStore.MetricQuery q = inv.getArgument(0);
            return "BS-1".equals(q.snapshotId()) ? List.of() : List.of();
        });
        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, service.evaluate(1L, alice).getResult());

        DecisionTask noApprovedAt = completedTask(new BigDecimal("100"));
        noApprovedAt.setApprovedAt(null);
        when(taskMapper.selectById(2L)).thenReturn(noApprovedAt);
        assertThrows(PlatformBizException.class, () -> service.evaluate(2L, alice));
    }

    @Test
    @DisplayName("evaluate：终态不可重复评价")
    void evaluateRejectedOnTerminalState() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        task.setStatus(DecisionStateMachine.EFFECTIVE);
        when(taskMapper.selectById(1L)).thenReturn(task);

        assertThrows(DecisionStateMachine.IllegalDecisionStateException.class,
                () -> service.evaluate(1L, alice));
    }

    @Test
    @DisplayName("未知决策 id → PARAM_INVALID，且不写任何评价")
    void unknownDecisionRejected() {
        when(taskMapper.selectById(99L)).thenReturn(null);
        PlatformBizException e = assertThrows(PlatformBizException.class, () -> service.evaluate(99L, alice));
        assertEquals(PlatformBizException.PARAM_INVALID, e.getCode());
        verify(evaluationMapper, never()).insert(any(DecisionEvaluation.class));
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private DecisionTask draft() {
        DecisionTask task = new DecisionTask();
        task.setId(1L);
        task.setDecisionNo("DC-20260901120000-abcd");
        task.setSource("ai");
        task.setTitle("t");
        task.setAction("a");
        task.setStatus(DecisionStateMachine.DRAFT);
        task.setEvalWindowDays(3);
        task.setCreatedBy("alice");
        return task;
    }

    /** 齐备草稿：owner/目标指标/方向/窗口/证据锚点都在 */
    private DecisionTask completeDraft() {
        DecisionTask task = draft();
        task.setOwner("alice");
        task.setTargetMetricCode(METRIC);
        task.setTargetDirection("UP");
        task.setSuggestionSnapshotId("S20260901_24");
        task.setCreatedAt(LocalDateTime.of(2026, 9, 1, 12, 0));
        return task;
    }

    /** 已完成、待评价的决策：批准 2026-09-01、完成 2026-09-04、窗口 3 天 */
    private DecisionTask completedTask(BigDecimal baseline) {
        DecisionTask task = completeDraft();
        task.setStatus(DecisionStateMachine.COMPLETED);
        task.setApprovedBy("alice");
        task.setApprovedAt(LocalDateTime.of(2026, 9, 1, 9, 0));
        task.setCompletedAt(LocalDateTime.of(2026, 9, 4, 18, 0));
        task.setBaselineValue(baseline);
        task.setBaselineSnapshotId("BS-1");
        task.setDefinitionVersion("metric-v1");
        return task;
    }

    private MetricValue metric(String snapshotId, String code, String value, String period, String definitionVersion) {
        MetricValue metric = new MetricValue();
        metric.setSnapshotId(snapshotId);
        metric.setMetricCode(code);
        metric.setMetricValue(new BigDecimal(value));
        metric.setPeriod(period);
        metric.setDefinitionVersion(definitionVersion);
        return metric;
    }
}
