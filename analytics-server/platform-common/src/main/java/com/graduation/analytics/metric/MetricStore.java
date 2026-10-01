package com.graduation.analytics.metric;

import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * 指标服务接口（§19.3）：查询/发布/健康检查。
 * 首版实现 MySqlMetricStore；第二阶段 DorisMetricStore；ClickHouse 只保留契约。
 */
public interface MetricStore {

    String type();

    /** 查询指标（可指定快照或取最新 ACTIVE；可选指标码过滤） */
    List<MetricValue> query(MetricQuery query);

    /**
     * 查询已发布的日粒度指标序列。实现必须限定运行环境、业务 source、指标口径和闭区间日期，
     * 每个业务日只返回一个确定的已发布快照值；窗口缺日由调用方按数据不足处理。
     */
    List<WindowMetricValue> queryWindow(WindowMetricQuery query);

    /**
     * 按快照号读快照元数据行（不存在返回 null）；证据归属核对等元数据读取使用（G31-03 03.5/D-034）。
     * 归属核对的读取所有者仍是指标库实现本身——调用方不得绕开本接口直查 metric_snapshot。
     */
    MetricSnapshot findSnapshot(String snapshotId);

    /** 发布指标快照：写指标值与 ACTIVE 指针切换（幂等：同快照重复发布覆盖） */
    int publish(SnapshotRef snapshot, List<MetricValue> datasets);

    /**
     * 激活后的只读对账失败时，在写事务中撤销失败快照的 ACTIVE 状态并恢复此前 ACTIVE 快照。
     * 若该快照已被后续发布取代，不覆盖更新的 ACTIVE；同时清理失败快照的指标值。
     * 返回恢复/保留后的 ACTIVE 快照号；若当前无 ACTIVE 则返回 null。
     */
    String failActivationAndRestore(SnapshotRef failedSnapshot, String previousActiveSnapshotId,
                                    String failureReason);

    /** 连通性检查 */
    HealthResult healthCheck();

    record MetricQuery(String snapshotId, boolean latestActive) {
    }

    record WindowMetricQuery(Long runtimeProfileId, Long sourceId, String metricCode, LocalDate from, LocalDate to,
                             String definitionVersion) {
    }

    record WindowMetricValue(String snapshotId, Long runtimeProfileId, Long sourceId, String metricCode, BigDecimal value,
                             LocalDate businessDate, String definitionVersion, LocalDateTime publishedAt) {
    }

    record SnapshotRef(String snapshotId, Long runtimeProfileId, String period) {
    }

    record HealthResult(boolean ok, String detail) {
    }
}
