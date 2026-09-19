package com.graduation.analytics.metric;

import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;

import java.util.List;

/**
 * 指标服务接口（§19.3）：查询/发布/健康检查。
 * 首版实现 MySqlMetricStore；第二阶段 DorisMetricStore；ClickHouse 只保留契约。
 */
public interface MetricStore {

    String type();

    /** 查询指标（可指定快照或取最新 ACTIVE；可选指标码过滤） */
    List<MetricValue> query(MetricQuery query);

    /**
     * 按快照号读快照元数据行（不存在返回 null）；证据归属核对等元数据读取使用（G31-03 03.5/D-034）。
     * 归属核对的读取所有者仍是指标库实现本身——调用方不得绕开本接口直查 metric_snapshot。
     */
    MetricSnapshot findSnapshot(String snapshotId);

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