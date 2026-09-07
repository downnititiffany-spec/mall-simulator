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

    private Long checkCount;

    private Long errorCount;

    private BigDecimal errorRate;

    private String threshold;

    private Integer passed;

    private String detail;

    private LocalDateTime createdAt;
}