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
 * ADS，§7.2）优先 + 应用层只读连接双保险 + 30s 超时 + 行数上限，金额转字符串防精度丢失；
 * 未配置只读数据源时回退应用数据源（降级友好），文档提示生产必须配置。
 */
@Slf4j
@Component
public class SqlExecutor {

    public static final int QUERY_TIMEOUT_SECONDS = 30;
    public static final int MAX_ROWS = 1000;

    @org.springframework.beans.factory.annotation.Autowired
    private DataSource dataSource;

    /** 只读账号数据源（可选：未配置 metricReadDataSource 时为 null，回退主源） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("metricReadDataSource")
    private DataSource readerDataSource;

    public record ExecutionResult(List<Map<String, Object>> rows, long elapsedMs, boolean truncated) {
    }

    /** 优先只读账号数据源；未配置时回退应用数据源（§3.5.5 降级原则） */
    DataSource effectiveDataSource() {
        return readerDataSource != null ? readerDataSource : dataSource;
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