package com.graduation.mall.metric.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 指标快照（§21.11）：BUILDING→VERIFYING→ACTIVE；旧 ACTIVE→ARCHIVED；失败 FAILED。
 */
@Data
@TableName("metric_snapshot")
public class MetricSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String snapshotId;

    private Long runtimeProfileId;

    private java.time.LocalDateTime businessTime;

    private Long pipelineRunId;

    private String status;

    private Integer version;

    private java.time.LocalDateTime dataUpdatedAt;

    private java.time.LocalDateTime publishedAt;

    private String source;

    private java.time.LocalDateTime createdAt;

    public static final String STATUS_BUILDING = "BUILDING";
    public static final String STATUS_VERIFYING = "VERIFYING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String STATUS_FAILED = "FAILED";
}