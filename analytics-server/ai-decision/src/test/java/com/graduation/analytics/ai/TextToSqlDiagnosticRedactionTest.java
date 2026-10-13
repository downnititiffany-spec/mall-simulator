package com.graduation.analytics.ai;

import com.graduation.analytics.ai.entity.AiCallLog;
import com.graduation.analytics.ai.entity.AiQueryHistory;
import com.graduation.analytics.ai.llm.LlmProvider;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.mapper.AiQueryHistoryMapper;
import com.graduation.analytics.ai.sql.AiScope;
import com.graduation.analytics.ai.sql.AiScopeResolver;
import com.graduation.analytics.ai.sql.QueryCostGuard;
import com.graduation.analytics.ai.sql.SqlExecutor;
import com.graduation.analytics.ai.sql.SqlPolicy;
import com.graduation.analytics.ai.sql.SqlSafetyValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TextToSqlDiagnosticRedactionTest {

    @Test
    void sqlExecutionFailureUsesStablePublicAndAuditDiagnostic() throws Exception {
        AiScope scope = AiScope.of("S20260901_24", "v2", LocalDate.of(2026, 9, 4), 17L);
        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve()).thenReturn(scope);

        LlmProvider llm = mock(LlmProvider.class);
        when(llm.healthCheck()).thenReturn(true);
        when(llm.providerName()).thenReturn("mock");
        when(llm.complete(any(LlmProvider.AiRequest.class))).thenReturn(new LlmProvider.AiResponse(
                "{\"sql\":\"SELECT dt, sale_amount FROM ads_sale_trend_m WHERE snapshot_id = 'S20260901_24' "
                        + "AND dt >= '20260901' AND dt <= '20260904' LIMIT 100\",\"assumptions\":[]}",
                10, 10, "mock"));

        QueryCostGuard costGuard = mock(QueryCostGuard.class);
        when(costGuard.check(anyString())).thenReturn(new QueryCostGuard.CostEstimate(10L, 1));
        SqlExecutor executor = mock(SqlExecutor.class);
        when(executor.execute(anyString())).thenThrow(new SQLException("database error UNTRUSTED_SECRET_MARKER"));
        AiQueryHistoryMapper historyMapper = mock(AiQueryHistoryMapper.class);
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);
        TextToSqlService service = new TextToSqlService(llm, new SemanticCatalog(),
                new SqlSafetyValidator(new SemanticCatalog()), costGuard, resolver, executor,
                historyMapper, callLogMapper);

        TextToSqlService.QueryResult result = service.query("最近销售额", "u1");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo(SqlPolicy.SQL_EXECUTION_FAILED);
        assertThat(result.error()).isEqualTo("只读 SQL 执行失败");
        assertThat(result.error()).doesNotContain("UNTRUSTED_SECRET_MARKER");
        ArgumentCaptor<AiQueryHistory> history = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(historyMapper).insert(history.capture());
        assertThat(history.getValue().getErrors()).contains(SqlPolicy.SQL_EXECUTION_FAILED);
        assertThat(history.getValue().getErrors()).doesNotContain("UNTRUSTED_SECRET_MARKER");
        ArgumentCaptor<AiCallLog> callLog = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callLogMapper).insert(callLog.capture());
        assertThat(callLog.getValue().getStatus()).isEqualTo("OK");
    }
}
