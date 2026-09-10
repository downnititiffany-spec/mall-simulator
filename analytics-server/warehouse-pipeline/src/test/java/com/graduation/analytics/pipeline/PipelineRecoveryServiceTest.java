package com.graduation.analytics.pipeline;

import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.pipeline.entity.PipelineRun;
import com.graduation.analytics.pipeline.entity.PipelineStageRun;
import com.graduation.analytics.pipeline.entity.SparkJobRun;
import com.graduation.analytics.pipeline.mapper.PipelineRunMapper;
import com.graduation.analytics.pipeline.mapper.PipelineStageRunMapper;
import com.graduation.analytics.pipeline.mapper.SparkJobRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R6-14（§23.1）启动对账快速测试（不起服务、不连库）：
 * ①RUNNING → FAILED/RUN_INTERRUPTED + 未结束作业置 UNKNOWN（不冒充成功）
 * ②PENDING → 交给 resume 重排
 * ③无异常状态 → 空报告
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineRecoveryServiceTest {

    @Mock PipelineRunMapper runMapper;
    @Mock PipelineStageRunMapper stageMapper;
    @Mock SparkJobRunMapper jobMapper;
    @Mock PipelineService pipelineService;

    private final EventClock eventClock = new EventClock(Clock.fixed(
            java.time.Instant.parse("2026-09-01T10:00:00Z"), ZoneId.of("Asia/Shanghai")));

    private PipelineRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new PipelineRecoveryService(runMapper, stageMapper, jobMapper, pipelineService, eventClock);
    }

    private static PipelineRun run(long id, String status) {
        PipelineRun r = new PipelineRun();
        r.setId(id);
        r.setStatus(status);
        r.setAttemptNo(1);
        return r;
    }

    @Test
    void runningRunIsMarkedInterruptedAndOrphanJobUnknown() {
        PipelineRun running = run(7L, PipelineRun.STATUS_RUNNING);
        when(runMapper.selectList(any())).thenReturn(List.of(running), List.of());
        SparkJobRun job = new SparkJobRun();
        job.setId(31L);
        job.setStatus(SparkJobRun.STATUS_RUNNING);
        job.setExternalJobId("app-123");
        when(jobMapper.selectList(any())).thenReturn(List.of(job));
        PipelineStageRun stage = new PipelineStageRun();
        stage.setId(5L);
        stage.setEvidence("ods=51");
        when(stageMapper.selectOne(any())).thenReturn(stage);

        PipelineRecoveryService.Report report = service.reconcile("system:startup");

        assertThat(report.interruptedRunning()).containsExactly(7L);
        assertThat(report.orphanJobsMarkedUnknown()).isEqualTo(1);
        verify(runMapper).updateById(running);
        assertThat(running.getStatus()).isEqualTo(PipelineRun.STATUS_FAILED);
        assertThat(running.getErrorCode()).isEqualTo("RUN_INTERRUPTED");
        assertThat(running.getFinishedAt()).isNotNull();
        // 未结束作业：UNKNOWN 且带 JOB_ORPHANED，不写成 SUCCESS/FAILED
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<SparkJobRun>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(jobMapper).update(eq(null), cap.capture());
        assertThat(cap.getValue().getSqlSet()).contains("status", "error_code", "finished_at");
        // 阶段证据留审计
        verify(stageMapper).update(eq(null), any());
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void pendingRunIsRequeued() {
        PipelineRun pending = run(9L, PipelineRun.STATUS_PENDING);
        when(runMapper.selectList(any())).thenReturn(List.of(), List.of(pending));

        PipelineRecoveryService.Report report = service.reconcile("system:startup");

        assertThat(report.requeuedPending()).containsExactly(9L);
        verify(pipelineService).resume(eq(9L), eq("system:startup"), anyString(), anyString());
        verify(runMapper, never()).updateById(any(PipelineRun.class));
    }

    @Test
    void cleanStateProducesEmptyReport() {
        when(runMapper.selectList(any())).thenReturn(List.of());

        PipelineRecoveryService.Report report = service.reconcile("admin");

        assertThat(report.requeuedPending()).isEmpty();
        assertThat(report.interruptedRunning()).isEmpty();
        assertThat(report.orphanJobsMarkedUnknown()).isZero();
        assertThat(report.at()).isEqualTo(LocalDateTime.of(2026, 9, 1, 18, 0));
    }
}
