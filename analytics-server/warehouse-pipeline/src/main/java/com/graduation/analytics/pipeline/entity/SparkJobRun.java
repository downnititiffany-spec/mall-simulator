package com.graduation.analytics.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Spark 作业运行记录（§8.2/§13.3，V7__platform_runtime_profile.sql）：
 * 每个 spark 作业必须保存 job_code + 流水线/阶段关联 + external_job_id（非空即提交成功）
 * + submitter_type + arguments_json + 记录数 + 日志 URI（可溯源，§21.10）。
 */
@Data
@TableName("spark_job_run")
public class SparkJobRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runtimeProfileId;

    private Integer runtimeProfileVersion;

    private Long pipelineRunId;

    private String stageCode;

    private String jobCode;

    /** 非空即提交成功（§13.3）；local-process 为 lp-{ts}-{rand} */
    private String externalJobId;

    /** local-process / ssh / livy */
    private String submitterType;

    /** 完整提交参数（spark-submit ... --key=value），溯源证据 */
    private String argumentsJson;

    private Long inputRecords;

    private Long outputRecords;

    private Long rejectedRecords;

    /** SUBMITTED / RUNNING / SUCCESS / FAILED / CANCELLED */
    private String status;

    /** 可访问的日志 URI（landing/logs/...） */
    private String logUri;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}