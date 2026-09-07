package com.graduation.analytics.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流水线阶段实例（§23.1：每阶段记录状态/记录数/外部任务 ID/错误码）。
 */
@Data
@TableName("pipeline_stage_run")
public class PipelineStageRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private String stageCode;

    private String status;

    private String externalJobId;

    private Long records;

    private String errorCode;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
}