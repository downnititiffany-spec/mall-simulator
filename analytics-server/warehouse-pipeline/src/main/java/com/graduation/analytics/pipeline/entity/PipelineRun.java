package com.graduation.analytics.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流水线实例（§23.1：幂等键唯一；失败后同键重试递增 attempt_no）。
 */
@Data
@TableName("pipeline_run")
public class PipelineRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String idempotencyKey;

    private Long runtimeProfileId;

    /** 创建任务时冻结的 source_registry.id；历史 run 无法可靠反推时保持 NULL。 */
    private Long sourceId;

    /** §8.1：每次运行必须保存实际 profile_version（V7 列） */
    private Integer runtimeProfileVersion;

    private String pipelineCode;

    private LocalDateTime businessTime;

    private String sourceDataVersion;

    /** V7：来源批次（ingestion_batch.id），溯源链路 */
    private Long inputBatchId;

    /** V7：目标快照 ID（S{date}_{runId}），溯源链路 */
    private String targetSnapshotId;

    /**
     * V33（G31-11 / D-049e）：显式重算理由（recalculate 入口必填；非空 = 本 run 是重算，
     * 豁免 M3 no-op 门并强制钉住 input_batch_id）。NULL = 普通调度/重试 run。
     */
    private String recalcReason;

    private Integer attemptNo;

    private String status;

    /** V7：当前阶段码（WAIT_LANDING/LOAD_ODS/...） */
    private String currentStage;

    private String errorCode;

    /** V7：失败详情 */
    private String errorMessage;

    /** V7：发起人 */
    private String createdBy;

    private String traceId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** V7：实际执行起始/结束时间 */
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
}
