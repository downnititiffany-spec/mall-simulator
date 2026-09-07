package com.graduation.analytics.metric.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 指标字典（唯一口径来源的落库副本，§24.1）。
 */
@Data
@TableName("metric_definition")
public class MetricDefinition {

    @TableId(type = IdType.INPUT)
    private String metricCode;

    private String metricName;

    private String formula;

    private String grain;

    private String defaultTimeField;

    private String unit;

    private String definitionVersion;
}