package com.graduation.analytics.metric.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 指标字典（唯一口径来源的落库副本，§24.1）。
 *
 * <p>R7-3：本类从 {@code metric.entity} 迁到 {@code metric.dict}，使
 * {@link MetricDefinitionMapper} 落在被 {@code @MapperScan} 扫描的包内——字典表属
 * <b>analytics_meta</b>（§17.2 表所有权），流水线发布前要用它做「指标码/口径版本」对账。
 * 原先放在未扫描的 {@code metric.mapper} 包内等于「有 mapper 但没人能用」，属 R7-1 的遗留缺口。</p>
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