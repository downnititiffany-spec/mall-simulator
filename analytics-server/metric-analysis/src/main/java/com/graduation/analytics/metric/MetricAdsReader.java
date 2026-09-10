package com.graduation.analytics.metric;

import com.graduation.analytics.metric.entity.MetricSnapshot;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * R7-2：analytics_metric ADS 宽表**只读查询** DAO（§17.5 第 8 步：看板先取一个 ACTIVE snapshotId，
 * 整个请求内固定使用同一快照）。
 *
 * <ul>
 *   <li>数据源：{@code metricReadJdbcTemplate}（metric_read 只读账号，DB 层仅 SELECT）；
 *       **不回退 meta 源**：读不到返回空集合/null，绝不去查 analytics_meta 的同名副本表（§17.1/§17.2）；</li>
 *   <li>表名只允许 {@link MetricAdsCatalog} 白名单，列名固定取白名单列，全部反引号拼接，外部输入只做参数绑定；</li>
 *   <li>快照一致性：<ol>
 *       <li>{@link #activeSnapshotId()} 只解析一次 ACTIVE 快照号；</li>
 *       <li>其余查询用 {@link #selectBySnapshot}/{@link #selectOneBySnapshot}/{@link #countRows}
 *           显式传入该 snapshotId，保证同一次页面请求读到的是同一快照（即使期间发布了新快照）。</li>
 *     </ol></li>
 * </ul>
 */
@Component
public class MetricAdsReader {

    private static final String COL_SNAPSHOT_ID = "snapshot_id";
    private static final String COL_DT = "dt";

    private final JdbcTemplate readJdbc;

    /** 显式构造器保留 @Qualifier：多数据源下 Lombok 不会把字段注解复制到构造参数 */
    public MetricAdsReader(@Qualifier("metricReadJdbcTemplate") JdbcTemplate readJdbc) {
        this.readJdbc = readJdbc;
    }

    /** 平台（最新）ACTIVE 快照号；无 ACTIVE 返回 null。 */
    public String activeSnapshotId() {
        List<String> ids = readJdbc.query(
                "SELECT snapshot_id FROM metric_snapshot WHERE status = ? ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getString(1), MetricSnapshot.STATUS_ACTIVE);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 指定运行环境的 ACTIVE 快照号；无 ACTIVE 返回 null。
     *
     * <p>注：metric_snapshot.snapshot_id 是 VARCHAR(64)（发布号形如 {@code snap-20260904-1}），
     * 因此返回 String 而不是 Long —— Long 无法作为快照主键使用（R7-2 实现说明）。</p>
     */
    public String activeSnapshotId(long runtimeProfileId) {
        List<String> ids = readJdbc.query(
                "SELECT snapshot_id FROM metric_snapshot WHERE status = ? AND runtime_profile_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getString(1), MetricSnapshot.STATUS_ACTIVE, runtimeProfileId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 当前 ACTIVE 快照下某日期分区的多行（如漏斗 4 个 stage、商品排行 N 行） */
    public List<Map<String, Object>> selectActive(String table, String dt) {
        String snapshotId = activeSnapshotId();
        if (snapshotId == null) {
            return List.of();
        }
        return selectBySnapshot(table, snapshotId, dt);
    }

    /** 当前 ACTIVE 快照下某日期分区的单行（运营大盘/销售趋势等一行一天的表）；无数据返回 null */
    public Map<String, Object> selectOneActive(String table, String dt) {
        String snapshotId = activeSnapshotId();
        if (snapshotId == null) {
            return null;
        }
        return selectOneBySnapshot(table, snapshotId, dt);
    }

    /** 固定快照读取多行（§17.5 第 8 步：同一请求内 snapshotId 不变） */
    public List<Map<String, Object>> selectBySnapshot(String table, String snapshotId, String dt) {
        MetricAdsCatalog spec = MetricAdsCatalog.require(table);
        requireSnapshotId(snapshotId);
        List<Object> args = new ArrayList<>();
        String sql = selectSql(spec, args, snapshotId, dt);
        return readJdbc.query(sql, new ColumnMapRowMapper(), args.toArray());
    }

    /** 固定快照读取单行（按主键剩余列稳定排序 + LIMIT 1）；无数据返回 null */
    public Map<String, Object> selectOneBySnapshot(String table, String snapshotId, String dt) {
        MetricAdsCatalog spec = MetricAdsCatalog.require(table);
        requireSnapshotId(snapshotId);
        List<Object> args = new ArrayList<>();
        String sql = selectSql(spec, args, snapshotId, dt);
        if (!spec.orderColumns().isEmpty()) {
            StringBuilder order = new StringBuilder(" ORDER BY ");
            for (int i = 0; i < spec.orderColumns().size(); i++) {
                order.append(i == 0 ? "" : ",").append('`').append(spec.orderColumns().get(i)).append('`');
            }
            sql = sql + order;
        }
        sql = sql + " LIMIT 1";
        List<Map<String, Object>> rows = readJdbc.query(sql, new ColumnMapRowMapper(), args.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 该快照在某 ADS 表的行数（发布对账：Hive ADS 行数 = MySQL 行数，§17.4） */
    public long countRows(String table, String snapshotId) {
        MetricAdsCatalog spec = MetricAdsCatalog.require(table);
        requireSnapshotId(snapshotId);
        Long count = readJdbc.queryForObject(
                "SELECT COUNT(*) FROM `" + spec.name() + "` WHERE `" + COL_SNAPSHOT_ID + "` = ?",
                Long.class, snapshotId);
        return count == null ? 0L : count;
    }

    private static String selectSql(MetricAdsCatalog spec, List<Object> args, String snapshotId, String dt) {
        List<String> columns = new ArrayList<>();
        columns.add(COL_SNAPSHOT_ID);
        columns.add(COL_DT);
        columns.addAll(spec.columns());
        StringBuilder cols = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            cols.append(i == 0 ? "" : ",").append('`').append(columns.get(i)).append('`');
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(cols)
                .append(" FROM `").append(spec.name()).append('`')
                .append(" WHERE `").append(COL_SNAPSHOT_ID).append("` = ?");
        args.add(snapshotId);
        if (dt != null && !dt.isBlank()) {
            sql.append(" AND `").append(COL_DT).append("` = ?");
            args.add(dt);
        }
        return sql.toString();
    }

    private static void requireSnapshotId(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshot_id 必填（禁止无快照条件的全表读取）");
        }
    }
}
