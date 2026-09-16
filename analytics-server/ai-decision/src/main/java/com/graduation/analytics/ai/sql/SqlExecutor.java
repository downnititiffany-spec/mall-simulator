package com.graduation.analytics.ai.sql;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读 SQL 执行器（§19.5 末段 / §21.2 最小权限）：独立只读账号（metric_read，DB 层仅 SELECT 已发布
 * ADS，§7.2）+ 应用层只读连接双保险 + 30s 超时 + 行数上限，金额转字符串防精度丢失。
 *
 * <p>反熵 ①（R7-4）：**取消"未配置只读源就回退主（元）数据源"的降级**。回退会让 AI 拿到
 * analytics_meta 里的原始/中间表，直接违反 §17.1「AI 只能读已发布 ADS」与 §7.2 最小权限；
 * 现在改为 fail-closed：只读源缺失时抛 IllegalStateException，宁可 AI 查询不可用，也不越权取数。</p>
 *
 * <p>反熵 ②（R8-2 契约 §2.3）：{@code MAX_ROWS} 由 1000 收紧到 200（与 {@link AiScope#rowLimit()}
 * 一致），并补 {@link #setMaxRows(int)}；新增 {@link #explain(String)} 供
 * {@link QueryCostGuard} 复用**同一个只读源**做成本预估 —— EXPLAIN 也必须走只读连接，
 * 不允许为了"只是看一眼执行计划"就换到可写数据源。</p>
 */
@Slf4j
@Component
public class SqlExecutor {

    /**
     * 只读查询超时（秒）。S3-19 起**不再自带字面量**，改取平台唯一数值属主
     * {@link com.graduation.analytics.common.QueryTimeoutPolicy}（指导书 V3.0 L158「超时统一」）：
     * 阶段4 的分析只读链路在 {@code metricReadJdbcTemplate} 上取同一个值。
     * 值不变（30 秒，设计 L569 先例），但改属主即两条路径同时改。
     */
    public static final int QUERY_TIMEOUT_SECONDS =
            com.graduation.analytics.common.QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS;

    /** 契约 §2.3 rowLimit：结果行上限 200（LIMIT 不生效时由 JDBC 层兜底截断） */
    public static final int MAX_ROWS = AiScope.DEFAULT_ROW_LIMIT;

    /** EXPLAIN 结果里的表格型计划（MultiResultsetExplain）：仅用于日志，不做解析 */
    public static final String MULTI_RESULTSET_KEY = "Multi-Resultset";

    /**
     * 只读账号数据源（metric_read → analytics_metric 已发布 ADS）。
     * 仍以 required=false 注入，是为了让容器启动不被数据源缺失阻断；一旦执行 SQL 就 fail-closed。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("metricReadDataSource")
    private DataSource readerDataSource;

    public record ExecutionResult(List<Map<String, Object>> rows, long elapsedMs, boolean truncated) {
    }

    /**
     * EXPLAIN 原始结果。
     *
     * @param columns   结果列名（小写，如 id/select_type/table/type/rows）
     * @param planRows  行数
     * @param plan      行内容（列名 → 值）
     */
    public record ExplainResult(List<String> columns, int planRows, List<Map<String, Object>> plan) {

        /** 是否 MySQL 表格型计划（EXPLAIN FORMAT=TRADITIONAL，含 rows 列） */
        public boolean tabular() {
            return columns.stream().anyMatch(c -> "rows".equalsIgnoreCase(c));
        }
    }

    /** 只读账号数据源（唯一允许的执行源）；缺失即失败，不回退任何其他数据源（§17.1） */
    DataSource effectiveDataSource() {
        if (readerDataSource == null) {
            throw new IllegalStateException(
                    "metricReadDataSource 未配置：AI 只读 SQL 必须走 metric_read（已发布 ADS），禁止回退 analytics_meta（§17.1）");
        }
        return readerDataSource;
    }

    public ExecutionResult execute(String sql) throws SQLException {
        long start = System.currentTimeMillis();
        DataSource effective = effectiveDataSource();
        Connection conn = DataSourceUtils.getConnection(effective);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        try {
            conn.setReadOnly(true);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                ps.setMaxRows(MAX_ROWS); // 契约 §2.3：JDBC 层再兜一层行数上限
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    while (rs.next()) {
                        if (rows.size() >= MAX_ROWS) {
                            truncated = true;
                            break;
                        }
                        rows.add(row(rs, meta, cols));
                    }
                }
            }
            return new ExecutionResult(rows, System.currentTimeMillis() - start, truncated);
        } finally {
            conn.setReadOnly(false);
            DataSourceUtils.releaseConnection(conn, effective);
        }
    }

    /**
     * 在一次只读连接上取 {@code EXPLAIN <sql>} 的原始计划（供 {@link QueryCostGuard} 解析成本）。
     *
     * <p>与 {@link #execute(String)} 同源同策略：metric_read、{@code setReadOnly(true)}、30s 超时、
     * 只读源缺失 fail-closed。EXPLAIN 不是"只读查询的普通路径"，但仍必须是只读连接 ——
     * 成本校验失败不能变成"顺手用可写账号看一眼计划"的口子。</p>
     */
    public ExplainResult explain(String sql) throws SQLException {
        DataSource effective = effectiveDataSource();
        Connection conn = DataSourceUtils.getConnection(effective);
        try {
            conn.setReadOnly(true);
            try (PreparedStatement ps = conn.prepareStatement("EXPLAIN " + sql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                List<String> columns = new ArrayList<>();
                List<Map<String, Object>> plan = new ArrayList<>();
                // 说明（如实登记）：这里只读**第一个**结果集。EXPLAIN FORMAT=TRADITIONAL（默认）就是
                // 那张含 rows 列的表格；FORMAT=TREE/JSON 才会是另一种形态，而本执行器固定发
                // "EXPLAIN <sql>"（不带 FORMAT），MySQL 返回即为传统表格。
                // 若驱动侧把计划拆成多个结果集，本方法不会读第二个 —— QueryCostGuard 对此
                // fail-closed（缺 rows 列即拒绝），不会误放行高成本 SQL。
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    for (int i = 1; i <= cols; i++) {
                        columns.add(meta.getColumnLabel(i).toLowerCase());
                    }
                    while (rs.next()) {
                        plan.add(row(rs, meta, cols));
                    }
                }
                return new ExplainResult(columns, plan.size(), plan);
            }
        } finally {
            conn.setReadOnly(false);
            DataSourceUtils.releaseConnection(conn, effective);
        }
    }

    /**
     * 显式声明本次执行的行数上限（返回真正生效值）。
     * 契约 §2.3 固定上限 200：调用方传更大的值只会被夹到 {@link #MAX_ROWS}，
     * 因此「忘了改配置」不会把行数上限放大。
     */
    public static int setMaxRows(int rows) {
        return Math.min(Math.max(rows, 1), MAX_ROWS);
    }

    private static Map<String, Object> row(ResultSet rs, ResultSetMetaData meta, int cols) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= cols; i++) {
            Object v = rs.getObject(i);
            if (v instanceof BigDecimal bd) {
                v = bd.toPlainString(); // 防精度丢失（§21.2）
            }
            row.put(meta.getColumnLabel(i).toLowerCase(), v);
        }
        return row;
    }
}
