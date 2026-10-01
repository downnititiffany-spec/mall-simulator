package com.graduation.analytics.ai.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlExecutorTimeoutConfigurationTest {

    @Test
    @DisplayName("执行只读 SQL 时向 JDBC 下发共享超时配置")
    void executeUsesConfiguredTimeout() throws Exception {
        Fixture fixture = fixture("9");
        fixture.executor().execute("select 1");
        verify(fixture.statement()).setQueryTimeout(9);
    }

    @Test
    @DisplayName("执行 EXPLAIN 时也向 JDBC 下发相同共享超时配置")
    void explainUsesConfiguredTimeout() throws Exception {
        Fixture fixture = fixture("9");
        fixture.executor().explain("select 1");
        verify(fixture.statement()).setQueryTimeout(9);
    }

    private Fixture fixture(String timeout) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(0);
        when(resultSet.next()).thenReturn(false);

        SqlExecutor executor = new SqlExecutor();
        ReflectionTestUtils.setField(executor, "readerDataSource", dataSource);
        ReflectionTestUtils.setField(executor, "queryTimeoutProperty", timeout);
        return new Fixture(executor, statement);
    }

    private record Fixture(SqlExecutor executor, PreparedStatement statement) {
    }
}
