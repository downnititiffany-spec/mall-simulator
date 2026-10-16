package com.graduation.analytics.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.graduation.analytics.pipeline.entity.PipelineBatchConsumption;

/**
 * G31-11（D-049a）：批次消费台账的唯一读写口（{@code @MapperScan} 已覆盖本包）。
 * 历史回填不经过本接口——回填是 V33 脚本的一次性动作，不经运行时代码。
 */
public interface PipelineBatchConsumptionMapper extends BaseMapper<PipelineBatchConsumption> {
}
