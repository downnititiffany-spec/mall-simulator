package com.graduation.analytics.metric.publish;

import com.graduation.analytics.metric.entity.MetricSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R7-3：快照生命周期登记 + 对账计数。
 *
 * <p>写走 {@code metricPublishJdbcTemplate}（metric_pub），读走 {@code metricReadJdbcTemplate}（metric_read）：
 * 对账必须用**看板同源的只读账号**读到数据，才算"看板能看到"（§17.1 三源纪律）。</p>
 *
 * <p>本类只登记 BUILDING/VERIFYING/FAILED 等中间状态；**ACTIVE 指针切换只有
 * {@code MySqlMetricStore.publish()} 能做**（同一事务内：写指标值 + 归档旧 ACTIVE + 激活新快照）。</p>
 */
@Slf4j
@Repository
public class MetricPublishRepository {

    private final JdbcTemplate publishJdbc;
    private final JdbcTemplate readJdbc;

    public MetricPublishRepository(@Qualifier("metricPublishJdbcTemplate") JdbcTemplate publishJdbc,
                                   @Qualifier("metricReadJdbcTemplate") JdbcTemplate readJdbc) {
        this.publishJdbc = publishJdbc;
        this.readJdbc = readJdbc;
    }

    /** 同一 profile 的下一个发布序号（version 从 1 起） */
    public int nextVersion(long runtimeProfileId) {
        Integer max = publishJdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM metric_snapshot WHERE runtime_profile_id = ?",
                Integer.class, runtimeProfileId);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * 登记/重置为 BUILDING（幂等重试安全）：
     * 已存在的同 snapshot_id 行先归零（active_flag=NULL 释放唯一 ACTIVE），再按需插入。
     */
    public void createBuilding(long runtimeProfileId, Integer runtimeProfileVersion, String snapshotId,
                               String businessDate, String businessTime, Long pipelineRunId,
                               String definitionVersion) {
        LocalDateTime business = parseBusinessTime(businessTime);
        publishJdbc.update("UPDATE metric_snapshot SET status = ?, active_flag = NULL, failure_reason = NULL, "
                        + "source = 'spark-ads', runtime_profile_version = ?, pipeline_run_id = ?, "
                        + "business_time = ?, data_updated_at = ? WHERE snapshot_id = ?",
                MetricSnapshot.STATUS_BUILDING, runtimeProfileVersion, pipelineRunId,
                Timestamp.valueOf(business), Timestamp.valueOf(business), snapshotId);

        int version = nextVersion(runtimeProfileId);
        publishJdbc.update("INSERT INTO metric_snapshot (snapshot_id, runtime_profile_id, runtime_profile_version, "
                        + "business_time, pipeline_run_id, status, version, definition_version, data_updated_at, "
                        + "source, active_flag) "
                        + "SELECT ?,?,?,?,?,?,?,?,?,?,NULL FROM DUAL "
                        + "WHERE NOT EXISTS (SELECT 1 FROM metric_snapshot WHERE snapshot_id = ?)",
                snapshotId, runtimeProfileId, runtimeProfileVersion, Timestamp.valueOf(business), pipelineRunId,
                MetricSnapshot.STATUS_BUILDING, version, definitionVersion == null ? "" : definitionVersion,
                Timestamp.valueOf(business), "spark-ads", snapshotId);
        log.info("metric publish: 快照 {} 登记 BUILDING（profile={}, version={}, businessDate={}）",
                snapshotId, runtimeProfileId, version, businessDate);
    }

    /** 状态流转（ACTIVE 不在此列，见类注释） */
    public void markStatus(String snapshotId, String status, String failureReason) {
        publishJdbc.update("UPDATE metric_snapshot SET status = ?, failure_reason = ? WHERE snapshot_id = ?",
                status, failureReason, snapshotId);
    }

    /** 删除某快照的 ADS 宽表行（发布失败补偿 / 幂等重试前清理） */
    public int deleteAdsRows(String mysqlTable, String snapshotId) {
        return publishJdbc.update("DELETE FROM " + mysqlTable + " WHERE snapshot_id = ?", snapshotId);
    }

    public String status(String snapshotId) {
        List<String> found = readJdbc.query("SELECT status FROM metric_snapshot WHERE snapshot_id = ?",
                (rs, rowNum) -> rs.getString(1), snapshotId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 最新 ACTIVE 快照号（看板同源只读账号；无 ACTIVE 返回 null） */
    public String activeSnapshotId(long runtimeProfileId) {
        List<String> found = readJdbc.query(
                "SELECT snapshot_id FROM metric_snapshot WHERE runtime_profile_id = ? AND status = ? "
                        + "ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getString(1), runtimeProfileId, MetricSnapshot.STATUS_ACTIVE);
        return found.isEmpty() ? null : found.get(0);
    }

    /** ADS 宽表实读行数（只读源） */
    public long countAdsRows(String mysqlTable, String snapshotId) {
        Long count = readJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + mysqlTable + " WHERE snapshot_id = ?", Long.class, snapshotId);
        return count == null ? 0L : count;
    }

    /** metric_value 实读行数（只读源） */
    public long countMetricValues(String snapshotId) {
        Long count = readJdbc.queryForObject(
                "SELECT COUNT(*) FROM metric_value WHERE snapshot_id = ?", Long.class, snapshotId);
        return count == null ? 0L : count;
    }

    /** metric_value 实读值（只读源，用于激活后与 ADS 对账） */
    public Map<String, BigDecimal> metricValues(String snapshotId) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        readJdbc.query("SELECT metric_code, metric_value FROM metric_value WHERE snapshot_id = ? ORDER BY metric_code",
                rs -> {
                    values.put(rs.getString(1), rs.getBigDecimal(2));
                }, snapshotId);
        return values;
    }

    /** 业务时间解析：前端/流水线可能给 ISO-8601（含 T）；解析失败按业务日 00:00 兜底并记录 */
    private LocalDateTime parseBusinessTime(String businessTime) {
        if (businessTime != null && !businessTime.isBlank()) {
            try {
                return LocalDateTime.parse(businessTime.replace(' ', 'T'));
            } catch (Exception e) {
                log.warn("metric publish: businessTime 非法（{}），退回当前时间", businessTime);
            }
        }
        return LocalDateTime.now();
    }
}
