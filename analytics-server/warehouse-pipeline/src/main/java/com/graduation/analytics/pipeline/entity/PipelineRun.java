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

    private String pipelineCode;

    private LocalDateTime businessTime;

    private String sourceDataVersion;

    private Integer attemptNo;

    private String status;

    private String errorCode;

    private String traceId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
}