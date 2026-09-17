package com.graduation.analytics.ai;

import com.graduation.analytics.ai.entity.AiQueryHistory;
import com.graduation.analytics.ai.llm.LlmProvider;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.mapper.AiQueryHistoryMapper;
import com.graduation.analytics.ai.sql.AiScope;
import com.graduation.analytics.ai.sql.AiScopeResolver;
import com.graduation.analytics.ai.sql.AiSqlException;
import com.graduation.analytics.ai.sql.QueryCostGuard;
import com.graduation.analytics.ai.sql.SqlExecutor;
import com.graduation.analytics.ai.sql.SqlPolicy;
import com.graduation.analytics.ai.sql.SqlSafetyValidator;
import com.graduation.analytics.common.PlatformBizException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLTimeoutException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S3-58：AI Text-to-SQL 的 EXPLAIN/EXECUTE 超时统一使用已有 QUERY_TIMEOUT 稳定码。 */
class AiQueryTimeoutMappingTest {

    private static final AiScope SCOPE = AiScope.of("S20260917_01", "v2", LocalDate.of(2026, 9, 17));
    private static final String SQL = "SELECT dt FROM ads_sale_trend_m WHERE snapshot_id = 'S20260917_01' "
            + "AND dt >= '20260917' AND dt <= '20260917' LIMIT 10";

    @Test
    void explain超时不能伪装成成本过高() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenThrow(new QueryTimeoutException("timeout"));
        QueryCostGuard guard = new QueryCostGuard(mock(SqlExecutor.class), jdbc, 500_000L);

        AiSqlException ex = assertThrows(AiSqlException.class, () -> guard.check(SQL));

        assertEquals(PlatformBizException.QUERY_TIMEOUT, ex.code());
    }

    @Test
    void execute超时必须返回FAILED并把稳定码写入审计() throws Exception {
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.healthCheck()).thenReturn(true);
        when(llm.providerName()).thenReturn("mock-provider");
        when(llm.complete(any())).thenReturn(new LlmProvider.AiResponse(
                "{\"sql\":\"" + SQL + "\",\"assumptions\":[]}", 1, 1, "mock-provider"));

        SemanticCatalog catalog = mock(SemanticCatalog.class);
        when(catalog.selectTables(anyString())).thenReturn(List.of("ads_sale_trend_m"));
        when(catalog.schemaJson(any())).thenReturn("{}");
        when(catalog.fewShots(any(), eq(SCOPE))).thenReturn("[]");

        SqlSafetyValidator validator = mock(SqlSafetyValidator.class);
        when(validator.validate(anyString(), eq(SCOPE))).thenReturn(SqlSafetyValidator.ValidationResult.pass(SQL));

        QueryCostGuard guard = mock(QueryCostGuard.class);
        when(guard.check(SQL)).thenReturn(new QueryCostGuard.CostEstimate(10L, 1));

        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve()).thenReturn(SCOPE);

        SqlExecutor executor = mock(SqlExecutor.class);
        when(executor.execute(SQL)).thenThrow(new SQLTimeoutException("statement timed out"));

        AiQueryHistoryMapper history = mock(AiQueryHistoryMapper.class);
        TextToSqlService service = new TextToSqlService(llm, catalog, validator, guard, resolver,
                executor, history, mock(AiCallLogMapper.class));

        TextToSqlService.QueryResult result = service.query("今天趋势", "u1");

        assertEquals("FAILED", result.status());
        assertEquals(PlatformBizException.QUERY_TIMEOUT, result.errorCode());
        assertTrue(result.error().contains("超时"));

        ArgumentCaptor<AiQueryHistory> captor = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(history).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertTrue(captor.getValue().getErrors().contains(PlatformBizException.QUERY_TIMEOUT));
    }

    @Test
    void 普通explain故障仍按成本守卫failClosed而不是超时() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenThrow(new IllegalStateException("driver failed"));
        QueryCostGuard guard = new QueryCostGuard(mock(SqlExecutor.class), jdbc, 500_000L);

        AiSqlException ex = assertThrows(AiSqlException.class, () -> guard.check(SQL));

        assertEquals(SqlPolicy.SQL_COST_TOO_HIGH, ex.code());
    }
}
