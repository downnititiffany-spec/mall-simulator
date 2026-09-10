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

    /**
     * R6-12（V2.0 §15.3）：真实输出分区证据 JSON 数组
     * [{"table","dt","snapshotId","rowCount","path"}]，来自 spark-jobs JobResult.outputPartitions。
     */
    private String outputPartitionsJson;

    /** SUBMITTED / RUNNING / SUCCESS / FAILED / CANCELLED */
    private String status;

    /** 可访问的日志 URI（landing/logs/...） */
    private String logUri;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String errorCode;

    private String errorMessage;

    // 注意：spark_job_run 表**没有** created_at/updated_at 列（见 V1/V10 迁移）。
    // 早期实体多声明了这两个字段：insert 时值为 null → MP 走 NOT_NULL 策略被跳过，所以插入正常；
    // 但 selectList 会显式列出全部实体字段 → 报 "Unknown column 'created_at'"，R6-14 启动对账首次
    // 触发该查询时暴露。这里删除幻影字段（时间语义由 started_at/finished_at 承担），而不是加列。

    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** §23.1 重启对账：未结束的作业结果不可判定（不当作成功，也不当作失败） */
    public static final String STATUS_UNKNOWN = "UNKNOWN";
}