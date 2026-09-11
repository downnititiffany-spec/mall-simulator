package com.graduation.analytics.ai.sql;

import lombok.RequiredArgsConstructor;
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
 * 只读 SQL 执行器（§8.6 第三/四层）：独立只读账号（metric_read，DB 层仅 SELECT 已发布
 * ADS，§7.2）+ 应用层只读连接双保险 + 30s 超时 + 行数上限，金额转字符串防精度丢失。
 *
 * 反熵（R7-4）：**取消"未配置只读源就回退主（元）数据源"的降级**。回退会让 AI 拿到
 * analytics_meta 里的原始/中间表，直接违反 §17.1「AI 只能读已发布 ADS」与 §7.2 最小权限；
 * 现在改为 fail-closed：只读源缺失时抛 IllegalStateException，宁可 AI 查询不可用，也不越权取数。
 */
@Slf4j
@Component
public class SqlExecutor {

    public static final int QUERY_TIMEOUT_SECONDS = 30;
    public static final int MAX_ROWS = 1000;

    /**
     * 只读账号数据源（metric_read → analytics_metric 已发布 ADS）。
     * 仍以 required=false 注入，是为了让容器启动不被数据源缺失阻断；一旦执行 SQL 就 fail-closed。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("metricReadDataSource")
    private DataSource readerDataSource;

    public record ExecutionResult(List<Map<String, Object>> rows, long elapsedMs, boolean truncated) {
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
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    while (rs.next()) {
                        if (rows.size() >= MAX_ROWS) {
                            truncated = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            Object v = rs.getObject(i);
                            if (v instanceof BigDecimal bd) {
                                v = bd.toPlainString(); // 防精度丢失（§21.2）
                            }
                            row.put(meta.getColumnLabel(i).toLowerCase(), v);
                        }
                        rows.add(row);
                    }
                }
            }
            return new ExecutionResult(rows, System.currentTimeMillis() - start, truncated);
        } finally {
            conn.setReadOnly(false);
            DataSourceUtils.releaseConnection(conn, effective);
        }
    }
}