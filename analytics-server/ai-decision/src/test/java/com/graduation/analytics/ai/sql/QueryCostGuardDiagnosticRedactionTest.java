package com.graduation.analytics.ai.sql;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryCostGuardDiagnosticRedactionTest {

    @Test
    void explainFailureDoesNotExposeDriverMessageOrCause() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("EXPLAIN"))).thenThrow(
                new DataAccessResourceFailureException("jdbc failure UNTRUSTED_SECRET_MARKER"));
        QueryCostGuard guard = new QueryCostGuard(mock(SqlExecutor.class), jdbc, 500_000L);

        assertThatThrownBy(() -> guard.check("SELECT dt FROM ads_sale_trend_m"))
                .isInstanceOfSatisfying(AiSqlException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(SqlPolicy.SQL_COST_TOO_HIGH);
                    assertThat(failure.getMessage()).isEqualTo("EXPLAIN 执行失败，按 fail-closed 拒绝");
                    assertThat(failure.getMessage()).doesNotContain("UNTRUSTED_SECRET_MARKER");
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void invalidPlanDiagnosticDoesNotSerializePlanRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table", "UNTRUSTED_SECRET_MARKER");
        row.put("rows", "UNTRUSTED_SECRET_MARKER");
        when(jdbc.queryForList(contains("EXPLAIN"))).thenReturn(List.of(row));
        QueryCostGuard guard = new QueryCostGuard(mock(SqlExecutor.class), jdbc, 500_000L);

        assertThatThrownBy(() -> guard.check("SELECT dt FROM ads_sale_trend_m"))
                .isInstanceOfSatisfying(AiSqlException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(SqlPolicy.SQL_COST_TOO_HIGH);
                    assertThat(failure.getMessage()).doesNotContain("UNTRUSTED_SECRET_MARKER");
                });
    }
}
