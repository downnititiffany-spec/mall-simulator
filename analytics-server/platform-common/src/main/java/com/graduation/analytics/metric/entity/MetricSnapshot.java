package com.graduation.analytics.metric.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 指标快照（§21.11）：BUILDING→VERIFYING→ACTIVE；旧 ACTIVE→ARCHIVED；失败 FAILED。
 * R7-1：该实体已**不绑定 MyBatis mapper**（analytics_metric 无 mapper 扫描），
 * 仅作为 JdbcTemplate 行映射与 API 返回结构使用；字段与 db/metric V1 列一一对应。
 */
@Data
@TableName("metric_snapshot")
public class MetricSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String snapshotId;

    private Long runtimeProfileId;

    /** §8.1：每次快照保存实际 profile_version（V7 列） */
    private Integer runtimeProfileVersion;

    private java.time.LocalDateTime businessTime;

    private Long pipelineRunId;

    private String status;

    private Integer version;

    /** R7-2：本快照使用的指标口径版本（metric_definition.definition_version） */
    private String definitionVersion;

    private java.time.LocalDateTime dataUpdatedAt;

    private java.time.LocalDateTime publishedAt;

    private String source;

    /** R7-2：验证/发布失败原因（§17.5 第 6 步） */
    private String failureReason;

    private java.time.LocalDateTime createdAt;

    public static final String STATUS_BUILDING = "BUILDING";
    public static final String STATUS_VERIFYING = "VERIFYING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String STATUS_FAILED = "FAILED";

    /** ACTIVE 行 active_flag=1，其它状态为 NULL（唯一 ACTIVE 的可空唯一列策略，§17.3） */
    public static final int ACTIVE_FLAG = 1;
}