package com.graduation.analytics.metric.dict;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 指标字典 mapper（R7-3）：表 {@code metric_definition} 属 <b>analytics_meta</b>（§17.2 表所有权），
 * 走 @Primary 的 meta 数据源。发布前用它做「指标码 + 口径版本」对账（§17.5）。
 */
public interface MetricDefinitionMapper extends BaseMapper<MetricDefinition> {
}
