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

    /**
     * 查询已发布快照的指标。显式 {@code snapshotId} 按精确 ID 读取，且只允许 ACTIVE/ARCHIVED；
     * 不得由调用方当前 RuntimeProfile 推断历史归属，也不得在未知或未发布时回退到 ACTIVE。
     * 未指定 ID 且 {@code latestActive=true} 时，按当前 ACTIVE RuntimeProfile 解析。
     */
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

    /** Resolve the ACTIVE snapshot for the current runtime profile. */
    String activeSnapshotId();

    /** Legacy bounded snapshot list retained for existing clients. */
    List<MetricSnapshot> listSnapshots(int limit);

    /**
     * Read a bounded, deterministically ordered page of snapshot metadata. Implementations must
     * constrain sort fields to a fixed allowlist and keep count and rows on a consistent read view.
     */
    SnapshotPage listSnapshotsPage(long page, int size, String sort);

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

    /**
     * 描述当前 Store 实例可提供的查询能力。
     * <p>{@code maxRows == null} 明确表示 Store 查询没有行数上限；不得把它解释为未知能力或
     * 虚构上限。此信息供调用方按能力选择路径，实际查询仍须遵循各自的权限和快照约束。</p>
     */
    Capabilities capabilities();

    record MetricQuery(String snapshotId, boolean latestActive, Long runtimeProfileId) {
        public MetricQuery(String snapshotId, boolean latestActive) {
            this(snapshotId, latestActive, null);
        }
    }

    record WindowMetricQuery(Long runtimeProfileId, Long sourceId, String metricCode, LocalDate from, LocalDate to,
                             String definitionVersion) {
    }

    record WindowMetricValue(String snapshotId, Long runtimeProfileId, Long sourceId, String metricCode, BigDecimal value,
                             LocalDate businessDate, String definitionVersion, LocalDateTime publishedAt) {
    }

    record SnapshotRef(String snapshotId, Long runtimeProfileId, String period) {
    }

    record SnapshotPage(List<MetricSnapshot> items, long page, int size, long total, long totalPages,
                        String sort) {
    }

    record HealthResult(boolean ok, String detail) {
    }

    /** 当前实现能力快照。availability 是本次读取时的健康状态，不代表持续可用承诺。 */
    record Capabilities(String type, boolean supportsSnapshot, boolean supportsDimensions, Integer maxRows,
                        int queryTimeoutSeconds, boolean available) {
    }
}
