package com.graduation.analytics.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.pipeline.entity.PipelineRun;
import com.graduation.analytics.pipeline.entity.PipelineStageRun;
import com.graduation.analytics.pipeline.entity.SparkJobRun;
import com.graduation.analytics.pipeline.mapper.PipelineRunMapper;
import com.graduation.analytics.pipeline.mapper.PipelineStageRunMapper;
import com.graduation.analytics.pipeline.mapper.SparkJobRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * R6-14（§23.1 恢复）：服务重启时的 run 对账。
 *
 * <p>语义边界（只做**可证明**的事，不猜）：
 * <ul>
 *   <li>RUNNING：进程已经死了（本方法只在启动时运行），因此该状态不可能有活着的执行线程 →
 *       一律判定为中断：run 置 FAILED/INTERRUPTED，其未结束的 spark_job_run 置 UNKNOWN（保留证据，
 *       不回填任何成功数据），并在阶段证据里留一条 RECOVERY 审计。</li>
 *   <li>PENDING：从未开始（或已排队但线程池丢失）→ 直接重排，交给 {@link PipelineService#resume}，
 *       已 SUCCESS 的阶段仍按阶段记录跳过。</li>
 *   <li>SUCCESS/FAILED：终态，不动。</li>
 * </ul>
 * 中断的 RUNNING 不自动重跑：自动重跑会在"外部作业其实还在跑"时造成双写（本地 local[*] 提交器下
 * 尤其危险），故交由管理员显式 resume / retry-from-stage（§23.1 的管理员动作）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineRecoveryService {

    private final PipelineRunMapper runMapper;
    private final PipelineStageRunMapper stageMapper;
    private final SparkJobRunMapper jobMapper;
    private final PipelineService pipelineService;
    private final EventClock eventClock;

    private final AtomicReference<Report> lastReport = new AtomicReference<>();

    /** 启动对账报告（运维页/验收取证用） */
    public record Report(String operator, LocalDateTime at, List<Long> requeuedPending,
                         List<Long> interruptedRunning, int orphanJobsMarkedUnknown,
                         List<String> errors) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        Report report = reconcile("system:startup");
        log.info("R6-14 启动对账完成: pending重排={} running判定中断={} 孤立作业置UNKNOWN={} errors={}",
                report.requeuedPending(), report.interruptedRunning(),
                report.orphanJobsMarkedUnknown(), report.errors());
    }

    public Report lastReport() {
        return lastReport.get();
    }

    /** 对账：返回报告；任何单个 run 的失败都不影响其余 run（错误进 errors 列表） */
    public Report reconcile(String operator) {
        List<Long> requeued = new ArrayList<>();
        List<Long> interrupted = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int orphanJobs = 0;

        List<PipelineRun> running = runMapper.selectList(new LambdaQueryWrapper<PipelineRun>()
                .eq(PipelineRun::getStatus, PipelineRun.STATUS_RUNNING));
        for (PipelineRun run : running) {
            try {
                orphanJobs += markOrphanJobs(run.getId(), operator);
                String note = "[RECOVERY] action=INTERRUPTED operator=" + operator
                        + " reason=服务重启，RUNNING 执行线程已消失 at=" + eventClock.nowLdt();
                appendStageEvidence(run.getId(), note);
                run.setStatus(PipelineRun.STATUS_FAILED);
                run.setCurrentStage("FAILED");
                run.setErrorCode("RUN_INTERRUPTED");
                run.setErrorMessage("服务重启中断，执行线程已消失；已完成阶段证据保留，"
                        + "可用管理员 resume 或 retry-from-stage 继续（snapshotId 复用）");
                run.setFinishedAt(eventClock.nowLdt());
                run.setUpdatedAt(eventClock.nowLdt());
                runMapper.updateById(run);
                interrupted.add(run.getId());
            } catch (Exception e) {
                errors.add("run " + run.getId() + " 对账失败: " + e.getMessage());
                log.warn("R6-14 run {} 对账失败", run.getId(), e);
            }
        }

        List<PipelineRun> pending = runMapper.selectList(new LambdaQueryWrapper<PipelineRun>()
                .eq(PipelineRun::getStatus, PipelineRun.STATUS_PENDING));
        for (PipelineRun run : pending) {
            try {
                pipelineService.resume(run.getId(), operator, "启动对账：PENDING 重排", "recovery-" + eventClock.nowLdt());
                requeued.add(run.getId());
            } catch (Exception e) {
                errors.add("run " + run.getId() + " 重排失败: " + e.getMessage());
                log.warn("R6-14 run {} 重排失败", run.getId(), e);
            }
        }

        Report report = new Report(operator, eventClock.nowLdt(), requeued, interrupted, orphanJobs, errors);
        lastReport.set(report);
        return report;
    }

    /** 未结束的 Spark 作业：无法判定是否真的执行过 → 置 UNKNOWN（§23.1），不回填成功数据 */
    private int markOrphanJobs(Long runId, String operator) {
        List<SparkJobRun> jobs = jobMapper.selectList(new LambdaQueryWrapper<SparkJobRun>()
                .eq(SparkJobRun::getPipelineRunId, runId)
                .in(SparkJobRun::getStatus, List.of(SparkJobRun.STATUS_SUBMITTED, SparkJobRun.STATUS_RUNNING)));
        for (SparkJobRun job : jobs) {
            // 列名用字符串：LambdaUpdateWrapper.set 在构建期就解析 lambda 缓存，
            // 单测（mock mapper、无 MyBatis 上下文）会直接抛异常；UpdateWrapper 用显式列名无此问题。
            jobMapper.update(null, new UpdateWrapper<SparkJobRun>()
                    .eq("id", job.getId())
                    .set("status", SparkJobRun.STATUS_UNKNOWN)
                    .set("error_code", "JOB_ORPHANED")
                    .set("error_message",
                            "服务重启，未结束的 Spark 作业无法判定结果（externalJobId=" + job.getExternalJobId()
                                    + "），operator=" + operator)
                    .set("finished_at", eventClock.nowLdt()));
        }
        return jobs.size();
    }

    private void appendStageEvidence(Long runId, String note) {
        PipelineStageRun latest = stageMapper.selectOne(new LambdaQueryWrapper<PipelineStageRun>()
                .eq(PipelineStageRun::getRunId, runId)
                .orderByDesc(PipelineStageRun::getId).last("LIMIT 1"));
        if (latest == null) {
            return;
        }
        String merged = latest.getEvidence() == null || latest.getEvidence().isBlank()
                ? note : cut(latest.getEvidence() + " | " + note, 4000);
        stageMapper.update(null, new UpdateWrapper<PipelineStageRun>()
                .eq("id", latest.getId())
                .set("evidence", merged));
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

