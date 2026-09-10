package com.graduation.analytics.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据质量结果（§5.4：规则、计数、阈值、是否阻断）。
 */
@Data
@TableName("data_quality_result")
public class DataQualityResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private String ruleCode;

    /** 规则层次（R6-13 §16.1：LANDING/DWD/DWS/ADS_STAGING/PUBLISH），运维页分层展示 */
    private String layer;

    /** 严重度（BLOCKING 阻断发布 / ERROR 记录 / INFO 操作审计） */
    private String severity;

    /** 规则作用对象（表名或分区范围） */
    private String targetTable;

    /** 本次快照号（可追溯规则作用于哪份快照） */
    private String snapshotId;

    private Long checkCount;

    private Long errorCount;

    private BigDecimal errorRate;

    private String threshold;

    private Integer passed;

    private String detail;

    private LocalDateTime createdAt;
}