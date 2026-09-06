package com.graduation.mall.ai.sql;

import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库只读账号防线（§8.6 第四层）：
 * mall_reader 必须可 SELECT 指标表，且任何写语句被 MySQL 权限层拒绝。
 * 凭据与 V6 迁移的 placeholder 默认值一致（测试专用，非生产秘密）。
 */
class ReaderAccountSecurityTest extends MallTestSupport {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/mall_simulator_test?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true";
    private static final String USER = "mall_reader";
    private static final String PASSWORD = "mall_reader_pw";

    private Connection reader() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    @Test
    @DisplayName("只读账号可 SELECT 指标表（第一断言：账号存在且已授权）")
    void readerCanSelectMetricTables() throws SQLException {
        try (Connection conn = reader();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) AS c FROM metric_value");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "应有查询结果");
            assertNotNull(rs.getLong(1), "count 可读");
        }
    }

    @Test
    @DisplayName("只读账号写语句被权限层拒绝（最终防线实测）")
    void readerWriteIsRejected() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = reader();
                 Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE metric_value SET metric_value = 0");
            }
        }, "mall_reader 执行 UPDATE 必须被 MySQL 权限拒绝");
    }

    @Test
    @DisplayName("只读账号 DDL 同样被拒绝（TRUNCATE/DROP 防御）")
    void readerDdlIsRejected() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = reader();
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS metric_value");
            }
        }, "mall_reader 执行 DROP 必须被拒绝");
    }
}