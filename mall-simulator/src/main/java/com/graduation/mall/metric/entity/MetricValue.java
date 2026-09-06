package com.graduation.mall.metric.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 快照指标值（看板/AI 统一入口；uk(snapshot_id, metric_code)）。
 */
@Data
@TableName("metric_value")
public class MetricValue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String snapshotId;

    private String metricCode;

    private BigDecimal metricValue;

    private String unit;

    private String period;

    private String definitionVersion;

    private LocalDateTime updatedAt;
}