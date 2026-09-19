package com.graduation.analytics.decision;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.decision.DecisionService.CreateDraftReq;
import com.graduation.analytics.decision.DecisionService.SubmitReq;
import com.graduation.analytics.decision.entity.DecisionTask;
import com.graduation.analytics.decision.mapper.DecisionEvaluationMapper;
import com.graduation.analytics.decision.mapper.DecisionTaskMapper;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
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
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 03.5 跨源证据守卫（G31-03/D-034）：suggestionSnapshotId 解析回源比对，跨源拒绝为
 * SOURCE_MISMATCH(409)、快照不存在为 PARAM_INVALID(400)、无 ACTIVE 环境为 fail-closed(400)；
 * 创建与提交两处闸门都生效，controller 包装器把业务异常落审计 FAILED 行（审计复用既有机制）。
 *
 * <p>Mockito 打桩不连库；同源放行的状态机/评价语义归 {@link DecisionServiceTest}，本类只盯守卫。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DecisionServiceSourceMismatchTest {

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

    private final AuditActor alice = AuditActor.of("trace-1", "alice", "analyst", "127.0.0.1");

    @BeforeEach
    void setUp() {
        service = new DecisionService(taskMapper, evaluationMapper, metricStore, runtimeProfileService, audit);
        ReflectionTestUtils.setField(service, "defaultEvalWindowDays", 3);
        ReflectionTestUtils.setField(service, "effectiveThreshold", new BigDecimal("0.05"));
        ReflectionTestUtils.setField(service, "partialThreshold", BigDecimal.ZERO);
        ReflectionTestUtils.setField(service, "evalDefinitionVersion", "r8-window-v1");
    }

    @Test
    @DisplayName("createDraft：证据快照属别源 → SOURCE_MISMATCH，不落库")
    void createDraftRejectsCrossSourceSnapshot() {
        when(metricStore.findSnapshot("S-CROSS")).thenReturn(snapshot("S-CROSS", 8L));
        when(runtimeProfileService.get(8L)).thenReturn(profile(8L, 2L));
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile(7L, 1L)));

        PlatformBizException e = assertThrows(PlatformBizException.class, () -> service.createDraft(
                new CreateDraftReq("提高支付转化", "调整详情页", "order_paid_amount", "UP",
                        "S-CROSS", "LOW", "alice", null), alice, "human"));

        assertEquals(PlatformBizException.SOURCE_MISMATCH, e.getCode());
        assertTrue(e.getMessage().contains("source_id=2"), e.getMessage());
        assertTrue(e.getMessage().contains("source_id=1"), e.getMessage());
        verify(taskMapper, never()).insert(any(DecisionTask.class));
        verify(audit, never()).success(any(), anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("createDraft：快照不存在 → PARAM_INVALID（参数化注入面不当'未知源'处理）")
    void createDraftRejectsUnknownSnapshot() {
        when(metricStore.findSnapshot("S-GHOST")).thenReturn(null);

        PlatformBizException e = assertThrows(PlatformBizException.class, () -> service.createDraft(
                new CreateDraftReq("t", "a", "order_paid_amount", "UP",
                        "S-GHOST", null, "alice", null), alice, "human"));

        assertEquals(PlatformBizException.PARAM_INVALID, e.getCode());
        verify(taskMapper, never()).insert(any(DecisionTask.class));
    }

    @Test
    @DisplayName("createDraft：无 ACTIVE 运行环境 → fail-closed PARAM_INVALID（不放行未核对的证据）")
    void createDraftFailsClosedWithoutActiveProfile() {
        when(metricStore.findSnapshot("S-ANY")).thenReturn(snapshot("S-ANY", 7L));
        when(runtimeProfileService.get(7L)).thenReturn(profile(7L, 1L));
        when(runtimeProfileService.findActive()).thenReturn(Optional.empty());

        PlatformBizException e = assertThrows(PlatformBizException.class, () -> service.createDraft(
                new CreateDraftReq("t", "a", "order_paid_amount", "UP",
                        "S-ANY", null, "alice", null), alice, "human"));

        assertEquals(PlatformBizException.PARAM_INVALID, e.getCode());
        assertTrue(e.getMessage().contains("ACTIVE"), e.getMessage());
        verify(taskMapper, never()).insert(any(DecisionTask.class));
    }

    @Test
    @DisplayName("createDraft：同源（profile 版本不同但 source 相同）放行")
    void createDraftAllowsSameSourceDifferentProfile() {
        when(metricStore.findSnapshot("S-SAME")).thenReturn(snapshot("S-SAME", 9L));
        when(runtimeProfileService.get(9L)).thenReturn(profile(9L, 1L));
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile(7L, 1L)));

        DecisionTask saved = service.createDraft(
                new CreateDraftReq("t", "a", "order_paid_amount", "UP",
                        "S-SAME", null, "alice", null), alice, "human");

        assertEquals(DecisionStateMachine.DRAFT, saved.getStatus());
        verify(taskMapper).insert(saved);
    }

    @Test
    @DisplayName("submit：证据快照属别源 → 提交闸门重核 SOURCE_MISMATCH，状态留在 DRAFT")
    void submitRechecksCrossSourceAnchor() {
        DecisionTask task = draft("S-A-SNAP");
        task.setOwner("alice");
        task.setTargetMetricCode("order_paid_amount");
        task.setTargetDirection("UP");
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.findSnapshot("S-A-SNAP")).thenReturn(snapshot("S-A-SNAP", 8L));
        when(runtimeProfileService.get(8L)).thenReturn(profile(8L, 2L));
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile(7L, 1L)));

        PlatformBizException e = assertThrows(PlatformBizException.class,
                () -> service.submit(1L, new SubmitReq(null, null), alice));

        assertEquals(PlatformBizException.SOURCE_MISMATCH, e.getCode());
        assertEquals(DecisionStateMachine.DRAFT, task.getStatus(), "守卫失败不得改状态");
        verify(taskMapper, never()).updateById(any(DecisionTask.class));
    }

    @Test
    @DisplayName("submit：同源锚点放行 → PENDING_REVIEW")
    void submitAllowsSameSourceAnchor() {
        DecisionTask task = draft("S-A-SNAP");
        task.setOwner("alice");
        task.setTargetMetricCode("order_paid_amount");
        task.setTargetDirection("UP");
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(metricStore.findSnapshot("S-A-SNAP")).thenReturn(snapshot("S-A-SNAP", 7L));
        when(runtimeProfileService.get(7L)).thenReturn(profile(7L, 1L));
        when(runtimeProfileService.findActive()).thenReturn(Optional.of(profile(7L, 1L)));

        assertEquals(DecisionStateMachine.PENDING_REVIEW, service.submit(1L, new SubmitReq(null, null), alice).getStatus());
        verify(taskMapper).updateById(task);
    }

    @Test
    @DisplayName("证据包路径（无 suggestionSnapshotId）不经守卫，跨源概念不适用")
    void evidencePackagePathSkipsGuard() {
        DecisionTask task = draft(null);
        task.setEvidencePackageId("EV-1");
        task.setOwner("alice");
        task.setTargetMetricCode("order_paid_amount");
        task.setTargetDirection("UP");
        when(taskMapper.selectById(1L)).thenReturn(task);

        assertEquals(DecisionStateMachine.PENDING_REVIEW, service.submit(1L, new SubmitReq(null, null), alice).getStatus());
        verify(metricStore, never()).findSnapshot(anyString());
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private DecisionTask draft(String suggestionSnapshotId) {
        DecisionTask task = new DecisionTask();
        task.setId(1L);
        task.setDecisionNo("DC-20260920050000-g31x");
        task.setSource("human");
        task.setTitle("t");
        task.setAction("a");
        task.setStatus(DecisionStateMachine.DRAFT);
        task.setEvalWindowDays(3);
        task.setCreatedBy("alice");
        task.setSuggestionSnapshotId(suggestionSnapshotId);
        task.setCreatedAt(LocalDateTime.of(2026, 9, 20, 5, 0));
        return task;
    }

    private MetricSnapshot snapshot(String snapshotId, Long runtimeProfileId) {
        MetricSnapshot snap = new MetricSnapshot();
        snap.setSnapshotId(snapshotId);
        snap.setRuntimeProfileId(runtimeProfileId);
        snap.setStatus(MetricSnapshot.STATUS_ACTIVE);
        return snap;
    }

    private RuntimeProfile profile(Long id, Long sourceId) {
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(id);
        profile.setSourceId(sourceId);
        profile.setStatus(RuntimeProfile.STATUS_ACTIVE);
        return profile;
    }
}
