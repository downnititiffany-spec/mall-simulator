package com.graduation.mall.metric;

import com.graduation.mall.metric.entity.MetricValue;

import java.util.List;

/**
 * 指标服务接口（§19.3）：查询/发布/健康检查。
 * 首版实现 MySqlMetricStore；第二阶段 DorisMetricStore；ClickHouse 只保留契约。
 */
public interface MetricStore {

    String type();

    /** 查询指标（可指定快照或取最新 ACTIVE；可选指标码过滤） */
    List<MetricValue> query(MetricQuery query);

    /** 发布指标快照：写指标值与 ACTIVE 指针切换（幂等：同快照重复发布覆盖） */
    int publish(SnapshotRef snapshot, List<MetricValue> datasets);

    /** 连通性检查 */
    HealthResult healthCheck();

    record MetricQuery(String snapshotId, boolean latestActive) {
    }

    record SnapshotRef(String snapshotId, Long runtimeProfileId, String period) {
    }

    record HealthResult(boolean ok, String detail) {
    }
}