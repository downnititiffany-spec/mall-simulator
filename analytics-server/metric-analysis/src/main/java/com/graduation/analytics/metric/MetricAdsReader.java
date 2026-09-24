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

    /**
     * 固定快照 + {@code dt} 闭区间读取多行（QA-01：趋势按日期筛选的唯一读路径）。
     *
     * <p>两端都可为 null（只绑给出的一端，另一端不设边界）；两端都为 null 属于「整快照读取」，
     * 必须走 {@link #selectBySnapshot(String, String, String)}，这里直接拒绝——
     * 不允许出现第二条语义不清的整快照入口。</p>
     *
     * <p>{@code dt} 是 ADS 落库的紧凑 {@code yyyyMMdd} 文本（见 {@code AiScope.DT_FORMAT} 与
     * 2026-09-11 事故记录）：字符串比较只在同格式下成立，因此非紧凑格式一律**拒绝**
     * （宁可 loud 报错，也不放行成静默 0 行）。倒置区间同样在发 SQL 之前拒绝。</p>
     */
    public List<Map<String, Object>> selectBySnapshotRange(String table, String snapshotId,
                                                           String dtFrom, String dtTo) {
        MetricAdsCatalog spec = MetricAdsCatalog.require(table);
        requireSnapshotId(snapshotId);
        requireCompactDate(dtFrom, "dtFrom");
        requireCompactDate(dtTo, "dtTo");
        if (dtFrom == null && dtTo == null) {
            throw new IllegalArgumentException(
                    "dt 区间两端都为空：整快照读取必须用 selectBySnapshot(table, snapshotId, null)");
        }
        if (dtFrom != null && dtTo != null && dtFrom.compareTo(dtTo) > 0) {
            throw new IllegalArgumentException("dt 区间倒置：dtFrom=" + dtFrom + " 晚于 dtTo=" + dtTo);
        }
        List<Object> args = new ArrayList<>();
        String sql = selectRangeSql(spec, args, snapshotId, dtFrom, dtTo);
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
        StringBuilder sql = new StringBuilder(baseSelect(spec))
                .append(" WHERE `").append(COL_SNAPSHOT_ID).append("` = ?");
        args.add(snapshotId);
        if (dt != null && !dt.isBlank()) {
            sql.append(" AND `").append(COL_DT).append("` = ?");
            args.add(dt);
        }
        return sql.toString();
    }

    /** 区间读取：只绑定非空的端点（缺失端不伪造边界值） */
    private static String selectRangeSql(MetricAdsCatalog spec, List<Object> args, String snapshotId,
                                        String dtFrom, String dtTo) {
        StringBuilder sql = new StringBuilder(baseSelect(spec))
                .append(" WHERE `").append(COL_SNAPSHOT_ID).append("` = ?");
        args.add(snapshotId);
        if (dtFrom != null) {
            sql.append(" AND `").append(COL_DT).append("` >= ?");
            args.add(dtFrom);
        }
        if (dtTo != null) {
            sql.append(" AND `").append(COL_DT).append("` <= ?");
            args.add(dtTo);
        }
        return sql.toString();
    }

    /** 列清单与 FROM 的唯一所有者：单点读取与区间读取共用，避免两处列顺序漂移 */
    private static String baseSelect(MetricAdsCatalog spec) {
        List<String> columns = new ArrayList<>();
        columns.add(COL_SNAPSHOT_ID);
        columns.add(COL_DT);
        columns.addAll(spec.columns());
        StringBuilder cols = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            cols.append(i == 0 ? "" : ",").append('`').append(columns.get(i)).append('`');
        }
        return "SELECT " + cols + " FROM `" + spec.name() + "`";
    }

    /** 紧凑 yyyyMMdd 校验（不接受 ISO/空白/长度不符：格式不符会静默 0 行） */
    private static void requireCompactDate(String dt, String name) {
        if (dt != null && !dt.matches("\\d{8}")) {
            throw new IllegalArgumentException(
                    name + " 必须是 ADS 落库的紧凑 yyyyMMdd 日期（如 20260901），实际: " + dt);
        }
    }

    private static void requireSnapshotId(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshot_id 必填（禁止无快照条件的全表读取）");
        }
    }
}
