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
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    private static final String METRIC = "gmv";

    @Mock
    private DecisionTaskMapper taskMapper;
    @Mock
    private DecisionEvaluationMapper evaluationMapper;
    @Mock
    private MetricStore metricStore;
    @Mock
    private RuntimeProfileService runtimeProfileService;
    @Mock
    private OperationAuditService audit;

    private DecisionService service;

    private final AuditActor alice = AuditActor.of("trace-1", "alice", "operator", "127.0.0.1");

    @BeforeEach
    void setUp() {
        service = new DecisionService(taskMapper, evaluationMapper, metricStore, runtimeProfileService, audit);
        ReflectionTestUtils.setField(service, "defaultEvalWindowDays", 3);
        ReflectionTestUtils.setField(service, "effectiveThreshold", new BigDecimal("0.05"));
        ReflectionTestUtils.setField(service, "partialThreshold", BigDecimal.ZERO);
        ReflectionTestUtils.setField(service, "evalDefinitionVersion", "r8-window-v1");
        // 03.5 跨源证据守卫的默认同源桩：夹具快照 S20260901_24 属 profile 7 / source 1，
        // ACTIVE 环境也是 source 1 —— 守卫放行，专注本类原本覆盖的状态机/评价语义。
        when(metricStore.findSnapshot("S20260901_24")).thenReturn(snapshot("S20260901_24", 7L));
        when(runtimeProfileService.get(7L)).thenReturn(profile(1L));
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile(1L)));
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenAnswer(inv -> {
            MetricStore.WindowMetricQuery q = inv.getArgument(0);
            return windowPoints(q, new BigDecimal("100"));
        });
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
        assertEquals(7L, after.getRuntimeProfileId());
        assertEquals(0, new BigDecimal("100").compareTo(after.getBaselineValue()));
        assertTrue(after.getBaselineSnapshotId().startsWith("SNAP-"));
        assertEquals(3, after.getBaselineSnapshotRefs().split(",").length);
        assertEquals(1L, after.getSourceId());
        assertEquals("metric-v1", after.getDefinitionVersion());
        assertEquals(3, after.getEvalWindowDays());
        assertEquals(7L, after.getRuntimeProfileId());
        assertNotNull(after.getApprovedAt());
        assertTrue(after.getApprovalNote().contains("基线快照=[SNAP-"), after.getApprovalNote());
    }

    @Test
    @DisplayName("approve：基线窗口缺业务日时拒绝批准，不冻结部分和")
    void approveRejectsIncompleteBaselineWindow() {
        DecisionTask task = completeDraft();
        task.setStatus(DecisionStateMachine.PENDING_REVIEW);
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class)))
                .thenReturn(List.of(metric("S20260901_24", METRIC, "100", "day:2026-09-01", "metric-v1")));
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenAnswer(inv -> {
            MetricStore.WindowMetricQuery q = inv.getArgument(0);
            return List.of(windowPoint("B1", q, new BigDecimal("10"), q.from()),
                    windowPoint("B2", q, new BigDecimal("20"), q.to()));
        });

        PlatformBizException e = assertThrows(PlatformBizException.class, () -> service.approve(1L,
                new ApproveReq("bob", LocalDate.of(2026, 9, 30), null, 3, null), alice));

        assertTrue(e.getMessage().contains("缺失"), e.getMessage());
        assertEquals(DecisionStateMachine.PENDING_REVIEW, task.getStatus());
        assertNull(task.getBaselineValue(), "基线不足时不得冻结聚合值");
        assertNull(task.getBaselineSnapshotId(), "基线不足时不得冻结基线快照");
        assertNull(task.getBaselineSnapshotRefs(), "基线不足时不得写逐日快照血缘");
        assertNull(task.getBaselineWindowStart(), "基线不足时不得写基线窗口起始日");
        assertNull(task.getBaselineWindowEnd(), "基线不足时不得写基线窗口结束日");
        assertNull(task.getRuntimeProfileId(), "基线不足时不得冻结运行环境血缘");
        assertNull(task.getSourceId(), "基线不足时不得冻结数据源血缘");
        assertNull(task.getDefinitionVersion(), "基线不足时不得冻结指标口径血缘");
        assertNull(task.getApprovedBy(), "基线不足时不得写批准人");
        assertNull(task.getApprovedAt(), "基线不足时不得写批准时间");
        assertNull(task.getApprovalNote(), "基线不足时不得写批准备注/血缘摘要");
        verify(taskMapper, never()).updateById(any(DecisionTask.class));
        verify(audit, never()).success(any(AuditActor.class), anyString(), anyString(), any(), any(), any(), anyString());
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
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenAnswer(inv -> {
            MetricStore.WindowMetricQuery q = inv.getArgument(0);
            return windowPoints(q, new BigDecimal("120"));
        });

        DecisionEvaluation evaluation = service.evaluate(1L, alice);

        assertEquals(DecisionStateMachine.EFFECTIVE, evaluation.getResult());
        assertEquals(0, new BigDecimal("0.2000").compareTo(evaluation.getImprovementRate()));
        assertTrue(evaluation.getBaselineSnapshotId().startsWith("BS-"));
        assertEquals("SNAP-2026-09-07", evaluation.getActualSnapshotId());
        assertEquals(0, new BigDecimal("100").compareTo(evaluation.getBaselinePeriodValue()));
        assertEquals(0, new BigDecimal("120").compareTo(evaluation.getActualPeriodValue()));
        assertEquals(6, evaluation.getSampleCount());
        assertEquals(3, evaluation.getBaselineSampleCount());
        assertEquals(3, evaluation.getActualSampleCount());
        assertEquals(7L, evaluation.getSourceId());
        assertEquals(7L, evaluation.getRuntimeProfileId());
        assertEquals("metric-v1", evaluation.getMetricDefinitionVersion());
        assertEquals(LocalDate.of(2026, 8, 30), evaluation.getBaselineWindowStart());
        assertEquals(LocalDate.of(2026, 9, 1), evaluation.getBaselineWindowEnd());
        assertEquals("r8-window-v1", evaluation.getDefinitionVersion());
        assertEquals("alice", evaluation.getEvaluatedBy());
        assertEquals(LocalDate.of(2026, 9, 5), evaluation.getWindowStart());
        assertEquals(LocalDate.of(2026, 9, 7), evaluation.getWindowEnd());
        assertEquals(3, evaluation.getEvalWindowDays());
        assertTrue(evaluation.getNote().contains("非因果"), evaluation.getNote());
        assertTrue(evaluation.getNote().contains("评价算法 r8-window-v1"), evaluation.getNote());
        assertEquals(DecisionStateMachine.EFFECTIVE, task.getStatus());
        assertNotNull(task.getEvaluatedAt());
        verify(metricStore).queryWindow(new MetricStore.WindowMetricQuery(7L, 7L, METRIC,
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 7), "metric-v1"));
    }

    @Test
    @DisplayName("evaluate：DOWN 指标下降即改善（取反后 20% → EFFECTIVE）")
    void evaluateDownDirectionInvertsRate() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        task.setTargetDirection("DOWN");
        task.setTargetMetricCode("net_sale");
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenAnswer(inv -> {
            MetricStore.WindowMetricQuery q = inv.getArgument(0);
            return windowPoints(q, new BigDecimal("80"));
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
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenAnswer(inv -> {
            MetricStore.WindowMetricQuery q = inv.getArgument(0);
            return windowPoints(q, new BigDecimal("102"));
        });

        assertEquals(DecisionStateMachine.PARTIAL, service.evaluate(1L, alice).getResult());
    }

    @Test
    @DisplayName("evaluate：指标未改善 → INEFFECTIVE")
    void evaluateIneffective() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenAnswer(inv -> {
            MetricStore.WindowMetricQuery q = inv.getArgument(0);
            return windowPoints(q, new BigDecimal("90"));
        });

        assertEquals(DecisionStateMachine.INEFFECTIVE, service.evaluate(1L, alice).getResult());
    }

    @Test
    @DisplayName("evaluate：达到目标值即 EFFECTIVE，即使改善率低于阈值")
    void evaluateTargetValueReached() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        task.setTargetValue(new BigDecimal("110"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenAnswer(inv -> {
            MetricStore.WindowMetricQuery q = inv.getArgument(0);
            return windowPoints(q, new BigDecimal("110"));
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
        assertTrue(evaluation.getNote().contains("聚合值为 0"), evaluation.getNote());
    }

    @Test
    @DisplayName("evaluate：完成后尚未发布新快照 → INSUFFICIENT_DATA")
    void evaluateSameSnapshotIsInsufficient() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenReturn(List.of());

        DecisionEvaluation evaluation = service.evaluate(1L, alice);
        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, evaluation.getResult());
        assertTrue(evaluation.getNote().contains("数据不足"), evaluation.getNote());
    }

    @Test
    @DisplayName("D1：首次评价数据不足后，完整窗口到齐可重评为 EFFECTIVE 并保留两次评价血缘")
    void reevaluateAfterInsufficientDataWhenCompleteWindowArrives() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        java.util.concurrent.atomic.AtomicInteger queryCount = new java.util.concurrent.atomic.AtomicInteger();
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenAnswer(invocation -> {
            MetricStore.WindowMetricQuery query = invocation.getArgument(0);
            return queryCount.getAndIncrement() == 0 ? List.of() : windowPoints(query, new BigDecimal("120"));
        });

        DecisionEvaluation first = service.evaluate(1L, alice);

        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, first.getResult());
        assertNull(first.getActualSnapshotId());
        assertEquals("", first.getActualSnapshotRefs());
        assertEquals(3, first.getBaselineSampleCount());
        assertEquals(0, first.getActualSampleCount());
        assertEquals(3, first.getSampleCount());
        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, task.getStatus());

        DecisionEvaluation second = service.evaluate(1L, alice);

        assertEquals(DecisionStateMachine.EFFECTIVE, second.getResult());
        assertEquals("SNAP-2026-09-07", second.getActualSnapshotId());
        assertEquals(encodedRefs("SNAP-2026-09-05", "SNAP-2026-09-06", "SNAP-2026-09-07"),
                second.getActualSnapshotRefs());
        assertEquals(3, second.getBaselineSampleCount());
        assertEquals(3, second.getActualSampleCount());
        assertEquals(6, second.getSampleCount());
        assertEquals(7L, second.getRuntimeProfileId());
        assertEquals(7L, second.getSourceId());
        assertEquals("metric-v1", second.getMetricDefinitionVersion());
        assertEquals("BS-1", second.getBaselineSnapshotId());
        assertEquals(task.getBaselineSnapshotRefs(), second.getBaselineSnapshotRefs());
        assertEquals(DecisionStateMachine.EFFECTIVE, task.getStatus());

        ArgumentCaptor<DecisionEvaluation> saved = ArgumentCaptor.forClass(DecisionEvaluation.class);
        verify(evaluationMapper, times(2)).insert(saved.capture());
        assertEquals(List.of(DecisionStateMachine.INSUFFICIENT_DATA, DecisionStateMachine.EFFECTIVE),
                saved.getAllValues().stream().map(DecisionEvaluation::getResult).toList());
        verify(taskMapper, times(2)).updateById(task);
        verify(metricStore, times(2)).queryWindow(new MetricStore.WindowMetricQuery(7L, 7L, METRIC,
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 7), "metric-v1"));
    }

    @Test
    @DisplayName("evaluate：新快照业务日仍 ≤ 完成日 → INSUFFICIENT_DATA（窗口未产生数据）")
    void evaluateBusinessDateInsideWindowRequired() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenAnswer(inv -> {
            MetricStore.WindowMetricQuery q = inv.getArgument(0);
            return List.of(windowPoint("AS-2", q, new BigDecimal("120"), q.from()));
        });

        DecisionEvaluation evaluation = service.evaluate(1L, alice);
        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, evaluation.getResult());
        assertTrue(evaluation.getNote().contains("缺失"), evaluation.getNote());
    }

    @Test
    @DisplayName("evaluate：实际快照日期晚于评价窗口 → INSUFFICIENT_DATA")
    void evaluateRejectsActualObservationAfterWindowEnd() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        task.setCompletedAt(LocalDateTime.of(2026, 9, 20, 18, 0)); // 实际窗口为 09-21..09-23
        task.setApprovedAt(LocalDateTime.of(2026, 9, 20, 9, 0));
        task.setBaselineWindowStart(LocalDate.of(2026, 9, 18));
        task.setBaselineWindowEnd(LocalDate.of(2026, 9, 20));
        task.setBaselineSnapshotRefs(encodedRefs("B1", "B2", "B3"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenAnswer(inv -> {
            MetricStore.WindowMetricQuery q = inv.getArgument(0);
            return List.of(windowPoint("AS-2", q, new BigDecimal("120"), LocalDate.of(2026, 9, 24)));
        });

        DecisionEvaluation evaluation = service.evaluate(1L, alice);

        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, evaluation.getResult());
        assertTrue(evaluation.getNote().contains("范围外"), evaluation.getNote());
        assertNull(evaluation.getActualValue(), "窗口外观测不能成为评价值");
        assertNull(evaluation.getImprovementRate(), "窗口外观测不能产生改善率");
    }

    @Test
    @DisplayName("evaluate：基线快照日期早于基线窗口 → INSUFFICIENT_DATA")
    void evaluateRejectsBaselineObservationBeforeWindowStart() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        task.setApprovedAt(LocalDateTime.of(2026, 9, 20, 9, 0)); // 基线窗口为 09-18..09-20
        task.setCompletedAt(LocalDateTime.of(2026, 9, 20, 18, 0));
        when(taskMapper.selectById(1L)).thenReturn(task);
        task.setBaselineSnapshotRefs(encodedRefs("B1"));
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenAnswer(inv -> {
            MetricStore.WindowMetricQuery q = inv.getArgument(0);
            return windowPoints(q, new BigDecimal("120"));
        });

        DecisionEvaluation evaluation = service.evaluate(1L, alice);

        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, evaluation.getResult());
        assertTrue(evaluation.getNote().contains("基线窗口证据不完整"), evaluation.getNote());
        assertNull(evaluation.getActualValue(), "无有效基线窗口时不能产出实际评价值");
        assertNull(evaluation.getImprovementRate(), "无有效基线窗口时不能产出改善率");
    }

    @Test
    @DisplayName("evaluate：无实际数据 / 缺批准时间 → 数据不足或 PARAM_INVALID，不得给结论")
    void evaluateHandlesMissingInputs() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenReturn(List.of());
        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, service.evaluate(1L, alice).getResult());

        DecisionTask noApprovedAt = completedTask(new BigDecimal("100"));
        noApprovedAt.setApprovedAt(null);
        when(taskMapper.selectById(2L)).thenReturn(noApprovedAt);
        assertThrows(PlatformBizException.class, () -> service.evaluate(2L, alice));
    }

    @Test
    @DisplayName("evaluate：历史任务缺少冻结的 runtimeProfileId → INSUFFICIENT_DATA，不查询跨环境窗口")
    void evaluateHistoricalTaskWithoutRuntimeProfileIsInsufficient() {
        DecisionTask task = completedTask(new BigDecimal("100"));
        task.setRuntimeProfileId(null);
        when(taskMapper.selectById(1L)).thenReturn(task);

        DecisionEvaluation evaluation = service.evaluate(1L, alice);

        assertEquals(DecisionStateMachine.INSUFFICIENT_DATA, evaluation.getResult());
        assertNull(evaluation.getActualValue());
        assertNull(evaluation.getImprovementRate());
        assertNull(evaluation.getRuntimeProfileId());
        assertTrue(evaluation.getNote().contains("基线窗口证据不完整"));
        verify(metricStore, never()).queryWindow(any(MetricStore.WindowMetricQuery.class));
    }

    @Test
    @DisplayName("approve：完整基线血缘冻结快照所属 runtimeProfileId 到任务")
    void approveFreezesRuntimeProfileIdentityAndWindowQuery() {
        DecisionTask task = completeDraft();
        task.setStatus(DecisionStateMachine.PENDING_REVIEW);
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.query(any(MetricStore.MetricQuery.class)))
                .thenReturn(List.of(metric("S20260901_24", METRIC, "100", "day:2026-09-23", "metric-v1")));

        DecisionTask approved = service.approve(1L,
                new ApproveReq("bob", LocalDate.now().plusDays(7), null, 3, null), alice);

        assertEquals(7L, approved.getRuntimeProfileId());
        verify(metricStore).queryWindow(any(MetricStore.WindowMetricQuery.class));
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
        task.setBaselineSnapshotRefs(encodedRefs("BS-2026-08-30", "BS-2026-08-31", "BS-2026-09-01"));
        task.setBaselineWindowStart(LocalDate.of(2026, 8, 30));
        task.setBaselineWindowEnd(LocalDate.of(2026, 9, 1));
        task.setRuntimeProfileId(7L);
        task.setSourceId(7L);
        task.setDefinitionVersion("metric-v1");
        return task;
    }

    private List<MetricStore.WindowMetricValue> windowPoints(MetricStore.WindowMetricQuery q, BigDecimal total) {
        long days = q.from().datesUntil(q.to().plusDays(1)).count();
        BigDecimal perDay = total.divide(BigDecimal.valueOf(days), 4, java.math.RoundingMode.HALF_UP);
        List<MetricStore.WindowMetricValue> points = new java.util.ArrayList<>();
        BigDecimal assigned = BigDecimal.ZERO;
        List<LocalDate> dates = q.from().datesUntil(q.to().plusDays(1)).toList();
        for (int i = 0; i < dates.size(); i++) {
            BigDecimal value = i == dates.size() - 1 ? total.subtract(assigned) : perDay;
            assigned = assigned.add(value);
            points.add(windowPoint("SNAP-" + dates.get(i), q, value, dates.get(i)));
        }
        return points;
    }

    private MetricStore.WindowMetricValue windowPoint(String snapshotId, MetricStore.WindowMetricQuery q,
                                                      BigDecimal value, LocalDate day) {
        return new MetricStore.WindowMetricValue(snapshotId, q.runtimeProfileId(), q.sourceId(), q.metricCode(), value, day,
                q.definitionVersion(), day.atTime(23, 0));
    }

    private static String encodedRefs(String... ids) {
        return java.util.Arrays.stream(ids)
                .map(id -> Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining(","));
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

    private MetricSnapshot snapshot(String snapshotId, Long runtimeProfileId) {
        MetricSnapshot snap = new MetricSnapshot();
        snap.setSnapshotId(snapshotId);
        snap.setRuntimeProfileId(runtimeProfileId);
        snap.setSourceId(1L);
        snap.setStatus(MetricSnapshot.STATUS_ACTIVE);
        return snap;
    }

    private RuntimeProfile profile(Long sourceId) {
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(7L);
        profile.setSourceId(sourceId);
        profile.setStatus(RuntimeProfile.STATUS_ACTIVE);
        return profile;
    }
}
