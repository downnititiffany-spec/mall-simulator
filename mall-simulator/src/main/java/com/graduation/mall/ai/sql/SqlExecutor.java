package com.graduation.mall.ai.sql;

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
 * 只读 SQL 执行器（§8.6 第三/四层）：应用层只读连接 + 30s 超时 + 行数上限，
 * 金额转字符串防精度丢失；即使校验被绕过，数据库侧账号权限仍为底线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlExecutor {

    public static final int QUERY_TIMEOUT_SECONDS = 30;
    public static final int MAX_ROWS = 1000;

    private final DataSource dataSource;

    public record ExecutionResult(List<Map<String, Object>> rows, long elapsedMs, boolean truncated) {
    }

    public ExecutionResult execute(String sql) throws SQLException {
        long start = System.currentTimeMillis();
        Connection conn = DataSourceUtils.getConnection(dataSource);
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
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }
}