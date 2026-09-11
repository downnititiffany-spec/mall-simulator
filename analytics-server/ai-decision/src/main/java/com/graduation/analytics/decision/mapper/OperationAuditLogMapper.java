package com.graduation.analytics.decision.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.graduation.analytics.decision.entity.OperationAuditLog;

/**
 * 操作审计写入/查询（analytics_meta）。
 * 包路径已在 AnalyticsApplication 的 @MapperScan 中（com.graduation.analytics.decision.mapper）。
 */
public interface OperationAuditLogMapper extends BaseMapper<OperationAuditLog> {
}
