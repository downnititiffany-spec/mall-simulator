package com.graduation.analytics.metric;

import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 指标服务实现（§19.3/§21.11，R7-1 改造）。
 *
 * <p>R7-1 决策：analytics_metric **不使用 MyBatis-Plus mapper**，改为两个专用 JdbcTemplate
 * （写用 metricPublishJdbcTemplate → metric_pub；读用 metricReadJdbcTemplate → metric_read），
 * 因此不存在多数据源 mapper 扫描歧义，也不需要为指标库配置 SqlSessionFactory。</p>
 *
 * <p>读路径**禁止回退 meta 源**：只读源读不到就返回空列表（不查 analytics_meta 的同名副本表，§17.2）。</p>
 *
 * <p>publish 在 metricPublishTransactionManager 单事务内：写 metric_value（按快照整体覆盖）
 * → 旧 ACTIVE→ARCHIVED（active_flag 置 NULL）→ 新快照→ACTIVE（active_flag=1）。
 * 先归档再激活，配合 uk_active_profile(runtime_profile_id, active_flag) 保证「同 profile 最多一个 ACTIVE」；
 * 事务提交前任何一步失败，全部回滚 → 旧 ACTIVE 保持（§17.5 第 6/7 步）。</p>
 */
@Slf4j
@Service
public class MySqlMetricStore implements MetricStore {

    private static final String VALUE_COLUMNS =
            "snapshot_id, metric_code, metric_value, unit, period, definition_version, updated_at";
    private static final String SNAPSHOT_COLUMNS =
            "id, snapshot_id, runtime_profile_id, runtime_profile_version, business_time, pipeline_run_id, "
                    + "status, version, definition_version, data_updated_at, published_at, source, "
                    + "failure_reason, created_at";

    /** metric_value 行映射（dimension_json/dimension_key 暂未进入 MetricValue 契约，R7-3 扩展） */
    private static final RowMapper<MetricValue> VALUE_MAPPER = (ResultSet rs, int rowNum) -> {
        MetricValue v = new MetricValue();
        v.setSnapshotId(rs.getString("snapshot_id"));
        v.setMetricCode(rs.getString("metric_code"));
        v.setMetricValue(rs.getBigDecimal("metric_value"));
        v.setUnit(rs.getString("unit"));
        v.setPeriod(rs.getString("period"));
        v.setDefinitionVersion(rs.getString("definition_version"));
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        v.setUpdatedAt(updatedAt == null ? null : updatedAt.toLocalDateTime());
        return v;
    };

    /** metric_snapshot 行映射（API /snapshots 返回结构与实体字段一致） */
    public static final RowMapper<MetricSnapshot> SNAPSHOT_MAPPER = (ResultSet rs, int rowNum) -> {
        MetricSnapshot s = new MetricSnapshot();
        s.setId(rs.getLong("id"));
        s.setSnapshotId(rs.getString("snapshot_id"));
        s.setRuntimeProfileId(rs.getLong("runtime_profile_id"));
        s.setRuntimeProfileVersion(rs.getObject("runtime_profile_version", Integer.class));
        Timestamp businessTime = rs.getTimestamp("business_time");
        s.setBusinessTime(businessTime == null ? null : businessTime.toLocalDateTime());
        s.setPipelineRunId(rs.getObject("pipeline_run_id", Long.class));
        s.setStatus(rs.getString("status"));
        s.setVersion(rs.getInt("version"));
        s.setDefinitionVersion(rs.getString("definition_version"));
        Timestamp dataUpdatedAt = rs.getTimestamp("data_updated_at");
        s.setDataUpdatedAt(dataUpdatedAt == null ? null : dataUpdatedAt.toLocalDateTime());
        Timestamp publishedAt = rs.getTimestamp("published_at");
        s.setPublishedAt(publishedAt == null ? null : publishedAt.toLocalDateTime());
        s.setSource(rs.getString("source"));
        s.setFailureReason(rs.getString("failure_reason"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        s.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
        return s;
    };

    private final JdbcTemplate publishJdbc;
    private final JdbcTemplate readJdbc;

    /** 显式构造器：多数据源下必须保留 @Qualifier（Lombok 不会把字段注解复制到构造参数） */
    public MySqlMetricStore(@Qualifier("metricPublishJdbcTemplate") JdbcTemplate publishJdbc,
                            @Qualifier("metricReadJdbcTemplate") JdbcTemplate readJdbc) {
        this.publishJdbc = publishJdbc;
        this.readJdbc = readJdbc;
    }

    @Override
    public String type() {
        return "mysql";
    }

    // ── 读（metric_read 只读源，禁止回退 meta） ─────────────────────────────

    @Override
    public List<MetricValue> query(MetricQuery query) {
        String snapshotId = query == null ? null : query.snapshotId();
        if ((snapshotId == null || snapshotId.isBlank()) && query != null && query.latestActive()) {
            snapshotId = activeSnapshotId();
        }
        if (snapshotId == null || snapshotId.isBlank()) {
            // 读不到就是空：不查 analytics_meta 的弃用副本表（§17.2 表所有权）
            return List.of();
        }
        return readJdbc.query("SELECT " + VALUE_COLUMNS + " FROM metric_value WHERE snapshot_id = ? "
                + "ORDER BY metric_code", VALUE_MAPPER, snapshotId);
    }

    /** 最新 ACTIVE 快照号（metric_read）；无 ACTIVE 返回 null */
    public String activeSnapshotId() {
        List<String> ids = readJdbc.query(
                "SELECT snapshot_id FROM metric_snapshot WHERE status = ? ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getString(1), MetricSnapshot.STATUS_ACTIVE);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 快照列表（管理页 /api/v1/metrics/snapshots；只读源） */
    public List<MetricSnapshot> listSnapshots(int limit) {
        int capped = Math.max(1, Math.min(100, limit));
        return readJdbc.query("SELECT " + SNAPSHOT_COLUMNS + " FROM metric_snapshot ORDER BY id DESC LIMIT ?",
                SNAPSHOT_MAPPER, capped);
    }

    /**
     * R7-4：按快照号读快照元数据（只读源）；不存在返回 null。
     *
     * <p>分析接口的信封需要 businessTime / dataUpdatedAt / definitionVersion / pipelineRunId
     * （契约 §2），这些只在 metric_snapshot 上。{@link #listSnapshots(int)} 是"最近 N 条"，
     * 无法按请求指定的历史快照号取，因此补一个按主键等值查询的只读方法；仍走 metric_read，
     * 不新增数据源、不写库。</p>
     */
    public MetricSnapshot findSnapshot(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            return null;
        }
        List<MetricSnapshot> rows = readJdbc.query(
                "SELECT " + SNAPSHOT_COLUMNS + " FROM metric_snapshot WHERE snapshot_id = ?",
                SNAPSHOT_MAPPER, snapshotId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── 写（metric_pub 发布源，专用事务管理器） ─────────────────────────────

    @Override
    @Transactional(transactionManager = "metricPublishTransactionManager", rollbackFor = Exception.class)
    public int publish(SnapshotRef snapshot, List<MetricValue> datasets) {
        String snapshotId = snapshot.snapshotId();
        LocalDateTime now = LocalDateTime.now();

        // 1. 整体覆盖该快照的指标值（幂等：重复发布不产生重复行）。
        //    dimension_key 统一写规范化空串而非 NULL：MySQL 唯一索引允许多个 NULL，
        //    写 NULL 会让 uk(snapshot_id, metric_code, period, dimension_key) 形同失效。
        publishJdbc.update("DELETE FROM metric_value WHERE snapshot_id = ?", snapshotId);
        if (datasets != null && !datasets.isEmpty()) {
            List<Object[]> batch = new ArrayList<>(datasets.size());
            for (MetricValue v : datasets) {
                batch.add(new Object[]{
                        snapshotId,
                        v.getMetricCode(),
                        v.getMetricValue() == null ? BigDecimal.ZERO : v.getMetricValue(),
                        v.getUnit() == null ? "" : v.getUnit(),
                        v.getPeriod() == null ? "" : v.getPeriod(),
                        v.getDefinitionVersion() == null ? "" : v.getDefinitionVersion(),
                        Timestamp.valueOf(now),
                        null,
                        ""});
            }
            publishJdbc.batchUpdate("INSERT INTO metric_value (snapshot_id, metric_code, metric_value, unit, "
                    + "period, definition_version, updated_at, dimension_json, dimension_key) "
                    + "VALUES (?,?,?,?,?,?,?,?,?)", batch);
        }

        // 2. 同 profile 旧 ACTIVE → ARCHIVED（active_flag 置 NULL，释放唯一键）
        Long profileId = snapshot.runtimeProfileId();
        if (profileId == null) {
            List<Long> found = publishJdbc.query(
                    "SELECT runtime_profile_id FROM metric_snapshot WHERE snapshot_id = ?",
                    (rs, rowNum) -> rs.getLong(1), snapshotId);
            profileId = found.isEmpty() ? null : found.get(0);
        }
        if (profileId != null) {
            int archived = publishJdbc.update("UPDATE metric_snapshot SET status = ?, active_flag = NULL "
                            + "WHERE runtime_profile_id = ? AND status = ? AND snapshot_id <> ?",
                    MetricSnapshot.STATUS_ARCHIVED, profileId, MetricSnapshot.STATUS_ACTIVE, snapshotId);
            if (archived > 0) {
                log.info("metric publish: profile {} 旧 ACTIVE 归档 {} 条", profileId, archived);
            }
        }

        // 3. 新快照 → ACTIVE（仅 VERIFYING 可激活；先归档再激活 → 不触发 uk_active_profile 冲突）
        int switched = publishJdbc.update("UPDATE metric_snapshot SET status = ?, active_flag = ?, published_at = ? "
                        + "WHERE snapshot_id = ? AND status = ?",
                MetricSnapshot.STATUS_ACTIVE, MetricSnapshot.ACTIVE_FLAG, Timestamp.valueOf(now),
                snapshotId, MetricSnapshot.STATUS_VERIFYING);
        if (switched == 0) {
            log.warn("metric publish: 快照 {} 未处于 VERIFYING，未切换为 ACTIVE（无 ACTIVE 指针变更，旧快照保持）",
                    snapshotId);
        }
        return switched;
    }

    @Override
    public HealthResult healthCheck() {
        try {
            // 只读账号 SELECT 1：只证明指标库只读链路可用，不碰 meta 源
            readJdbc.queryForObject("SELECT 1", Integer.class);
            return new HealthResult(true, "mysql metric store ok (metric_read)");
        } catch (Exception e) {
            return new HealthResult(false, e.getMessage());
        }
    }
}
