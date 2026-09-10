package com.graduation.analytics.metric;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * R7-2：analytics_metric ADS 宽表**批量写入** DAO（V2.0 §24.4「建批量写入和清理索引，避免逐行 insert」）。
 *
 * <ul>
 *   <li>数据源：{@code metricPublishJdbcTemplate}（metric_pub，analytics_metric 读写）；</li>
 *   <li>事务：{@code metricPublishTransactionManager}（与 MySqlMetricStore.publish 同一事务边界）；</li>
 *   <li>表名/列名：只允许 {@link MetricAdsCatalog} 白名单内的标识符，反引号拼接；
 *       外部传入的表名或行内出现的非白名单列名一律 {@link IllegalArgumentException}，不落任何 SQL；</li>
 *   <li>所有写入强制带 snapshot_id：没有 snapshot_id 的 ADS 行无法按快照回滚/审计（§17.3 禁止 dt 单列主键）。</li>
 * </ul>
 */
@Slf4j
@Component
public class MetricAdsWriter {

    private static final String COL_SNAPSHOT_ID = "snapshot_id";
    private static final String COL_DT = "dt";

    private final JdbcTemplate publishJdbc;

    /** 显式构造器保留 @Qualifier：多数据源下 Lombok 不会把字段注解复制到构造参数 */
    public MetricAdsWriter(@Qualifier("metricPublishJdbcTemplate") JdbcTemplate publishJdbc) {
        this.publishJdbc = publishJdbc;
    }

    /**
     * 按快照清理：删除该 snapshot_id 在 8 张 ADS 表里的全部行。
     * 只清 ADS 物化行，不动 metric_snapshot/metric_value（快照审计记录由发布流程管理）。
     *
     * @return 删除的总行数
     */
    @Transactional(transactionManager = "metricPublishTransactionManager", rollbackFor = Exception.class)
    public int deleteSnapshot(String snapshotId) {
        requireSnapshotId(snapshotId);
        int deleted = 0;
        for (MetricAdsCatalog table : MetricAdsCatalog.ALL) {
            deleted += publishJdbc.update("DELETE FROM `" + table.name() + "` WHERE `" + COL_SNAPSHOT_ID + "` = ?",
                    snapshotId);
        }
        log.info("metric ads delete snapshot {}: 共删除 {} 行（{} 张表）", snapshotId, deleted, MetricAdsCatalog.ALL.size());
        return deleted;
    }

    /**
     * 批量写入一批 ADS 行（INSERT，不 upsert：同一快照同一主键重复写入会报唯一键冲突而不是静默覆盖；
     * 发布流程应先用 {@link #deleteSnapshot(String)} 清理）。
     *
     * <p>列集合 = 行内显式提供的列（同一批各行必须完全一致，至少包含全部主键列）；
     * 整列未提供时交给 DDL 默认值，避免写出显式 NULL 撞 NOT NULL。</p>
     *
     * @param table      白名单表名
     * @param snapshotId 快照号（所有行统一使用，行内的 snapshot_id 会被忽略）
     * @param dt         业务日期分区（VARCHAR(16)，如 2026-09-04）
     * @param rows       行数据，键必须是白名单列名；主键列（含 snapshot_id/dt）必须逐行提供
     * @return 实际写入行数
     */
    @Transactional(transactionManager = "metricPublishTransactionManager", rollbackFor = Exception.class)
    public int insertRows(String table, String snapshotId, String dt, List<Map<String, Object>> rows) {
        MetricAdsCatalog spec = MetricAdsCatalog.require(table);
        requireSnapshotId(snapshotId);
        if (dt == null || dt.isBlank()) {
            throw new IllegalArgumentException("dt 必填（ADS 宽表按 snapshot_id+dt 分区）");
        }
        if (rows == null || rows.isEmpty()) {
            return 0;
        }

        java.util.Set<String> provided = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (row == null || row.isEmpty()) {
                throw new IllegalArgumentException("空行不允许写入 " + table);
            }
            for (String key : row.keySet()) {
                if (!spec.columns().contains(key) && !COL_SNAPSHOT_ID.equals(key) && !COL_DT.equals(key)) {
                    throw new IllegalArgumentException("非法列名（不在 " + table + " 白名单）: " + key);
                }
            }
            if (provided.isEmpty()) {
                provided.addAll(row.keySet());
            } else if (!provided.equals(row.keySet())) {
                throw new IllegalArgumentException(
                        "同一批 rows 的列必须一致（" + table + "）：" + provided + " vs " + row.keySet());
            }
        }
        for (String key : spec.keyColumns()) {
            if (!provided.contains(key) || anyNull(rows, key)) {
                throw new IllegalArgumentException(
                        "主键列 " + key + " 不能为空（" + table + " 主键含 snapshot_id+dt+" + key + "）");
            }
        }

        List<String> columns = new ArrayList<>();
        columns.add(COL_SNAPSHOT_ID);
        columns.add(COL_DT);
        for (String column : spec.columns()) {
            if (provided.contains(column)) {
                columns.add(column);
            }
        }

        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            cols.append(i == 0 ? "" : ",").append('`').append(columns.get(i)).append('`');
            marks.append(i == 0 ? "" : ",").append('?');
        }
        String sql = "INSERT INTO `" + spec.name() + "` (" + cols + ") VALUES (" + marks + ")";

        List<Object[]> batch = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object[] args = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                String column = columns.get(i);
                if (COL_SNAPSHOT_ID.equals(column)) {
                    args[i] = snapshotId;
                } else if (COL_DT.equals(column)) {
                    args[i] = dt;
                } else {
                    args[i] = row.get(column);
                }
            }
            batch.add(args);
        }
        int[] affected = publishJdbc.batchUpdate(sql, batch);
        int total = 0;
        for (int a : affected) {
            total += a;
        }
        log.info("metric ads insert {} (snapshot={}, dt={}): {} 行 / {} 列",
                spec.name(), snapshotId, dt, total, columns.size());
        return total;
    }

    private static boolean anyNull(List<Map<String, Object>> rows, String column) {
        for (Map<String, Object> row : rows) {
            if (row.get(column) == null) {
                return true;
            }
        }
        return false;
    }

    private static void requireSnapshotId(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshot_id 必填（ADS 行必须可按快照回滚/审计）");
        }
    }
}
