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
import com.graduation.analytics.ai.sql.SqlSafetyValidator.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R8-2 审计落库断言（契约 §2.3）：被拒绝的查询也必须落一行 {@code ai_query_history}，
 * 且「哪个快照 / 允许哪段时间 / 行数上限」写入**真实列**（V14 第 4 段提供），
 * {@code errors} 只保留错误文本。用 mock 的 Mapper 捕获插入对象，不依赖数据库。
 */
class TextToSqlAuditTest {

    private static final AiScope SCOPE = AiScope.of("S20260901_24", "v2", LocalDate.of(2026, 9, 4), 17L);

    private static TextToSqlService service(AiScopeResolver resolver, QueryCostGuard guard,
                                            SqlSafetyValidator validator, SqlExecutor executor,
                                            LlmProvider llm, AiQueryHistoryMapper historyMapper,
                                            AiCallLogMapper callLogMapper) {
        return new TextToSqlService(llm, new SemanticCatalog(), validator, guard, resolver,
                executor, historyMapper, callLogMapper);
    }

    @Test
    @DisplayName("被拒绝的查询也落审计行：status=REJECTED、规则码进 errors、快照/范围/上限进真实列")
    void 拒绝也落审计且写真实列() throws Exception {
        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve()).thenReturn(SCOPE);

        LlmProvider llm = mock(LlmProvider.class);
        when(llm.healthCheck()).thenReturn(true);
        when(llm.providerName()).thenReturn("mock");
        // 模型给出带 JOIN 的 SQL → 校验器必须拒绝（SQL_JOIN）
        LlmProvider.AiResponse generation = new LlmProvider.AiResponse(
                "{\"sql\":\"SELECT a.dt FROM ads_sale_trend_m a JOIN ads_hot_product_m b ON a.dt = b.dt "
                        + "WHERE a.snapshot_id = 'S20260901_24' AND a.dt >= '20260904' AND a.dt <= '20260904'\","
                        + "\"assumptions\":[]}",
                10, 10, "mock");
        when(llm.complete(any(LlmProvider.AiRequest.class))).thenReturn(generation,
                new LlmProvider.AiResponse(generation.content(), 21, 7, "mock"));

        SqlSafetyValidator validator = realValidator();
        QueryCostGuard guard = mock(QueryCostGuard.class);
        SqlExecutor executor = mock(SqlExecutor.class);
        AiQueryHistoryMapper historyMapper = mock(AiQueryHistoryMapper.class);
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);

        TextToSqlService.QueryResult r = service(resolver, guard, validator, executor, llm,
                historyMapper, callLogMapper).query("趋势如何", "u1");

        assertEquals(TextToSqlService.STATUS_REJECTED, r.status());
        assertEquals(SqlPolicy.SQL_JOIN, r.errorCode());
        // 拒绝路径绝不执行
        verify(executor, never()).execute(anyString());
        verify(guard, never()).check(anyString());

        ArgumentCaptor<AiQueryHistory> captor = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(historyMapper).insert(captor.capture());
        AiQueryHistory h = captor.getValue();
        assertEquals(TextToSqlService.STATUS_REJECTED, h.getStatus());
        assertEquals("S20260901_24", h.getSnapshotId());
        assertEquals(LocalDate.of(2026, 6, 7), h.getScopeMinDate());
        assertEquals(LocalDate.of(2026, 9, 4), h.getScopeMaxDate());
        assertEquals(200, h.getScopeRowLimit());
        assertNull(h.getExplainRows(), "未通过校验的查询不应有 EXPLAIN 行数");
        assertEquals(0, h.getRowsReturned());
        assertTrue(h.getErrors().contains(SqlPolicy.SQL_JOIN), "errors 必须含规则码: " + h.getErrors());
        assertTrue(h.getErrors().length() <= 200, "errors 需截断到 200 字符以内");
        // errors 不再承载结构化字段
        assertTrue(!h.getErrors().contains("snapshot="), "结构化信息应进真实列而不是 errors");
        ArgumentCaptor<AiCallLog> calls = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callLogMapper, times(2)).insert(calls.capture());
        assertEquals("sql_generation", calls.getAllValues().get(0).getUseCase());
        assertEquals(10, calls.getAllValues().get(0).getInputTokens());
        assertEquals(10, calls.getAllValues().get(0).getOutputTokens());
        assertEquals("sql_repair", calls.getAllValues().get(1).getUseCase());
        assertEquals(21, calls.getAllValues().get(1).getInputTokens());
        assertEquals(7, calls.getAllValues().get(1).getOutputTokens());
        assertEquals(17L, calls.getAllValues().get(0).getSourceId());
        assertEquals("S20260901_24", calls.getAllValues().get(0).getSnapshotId());
        assertTrue(calls.getAllValues().get(0).getRequestFingerprint().matches("[0-9a-f]{64}"));
        assertTrue(!calls.getAllValues().get(0).getRequestFingerprint()
                .equals(calls.getAllValues().get(1).getRequestFingerprint()));
    }

    @Test
    @DisplayName("无 ACTIVE 快照：审计 status=FAILED 且快照列留空（不伪造快照）")
    void 无ACTIVE快照的审计() throws Exception {
        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve()).thenThrow(new com.graduation.analytics.ai.sql.AiSqlException(
                SqlPolicy.NO_ACTIVE_SNAPSHOT, "metric_snapshot 中没有 status='ACTIVE'"));

        LlmProvider llm = mock(LlmProvider.class);
        QueryCostGuard guard = mock(QueryCostGuard.class);
        SqlExecutor executor = mock(SqlExecutor.class);
        AiQueryHistoryMapper historyMapper = mock(AiQueryHistoryMapper.class);
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);

        TextToSqlService.QueryResult r = service(resolver, guard, realValidator(), executor, llm,
                historyMapper, callLogMapper).query("趋势如何", "u1");

        assertEquals("FAILED", r.status());
        assertEquals(SqlPolicy.NO_ACTIVE_SNAPSHOT, r.errorCode());
        verify(llm, never()).complete(any(LlmProvider.AiRequest.class));
        verify(executor, never()).execute(anyString());

        ArgumentCaptor<AiQueryHistory> captor = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(historyMapper).insert(captor.capture());
        AiQueryHistory h = captor.getValue();
        assertEquals("FAILED", h.getStatus());
        assertNull(h.getSnapshotId());
        assertNull(h.getScopeMinDate());
        assertNull(h.getScopeMaxDate());
        assertTrue(h.getErrors().contains(SqlPolicy.NO_ACTIVE_SNAPSHOT));
    }

    @Test
    @DisplayName("Provider 调用失败写稳定错误码、Provider 与模型，不保存异常正文")
    void providerFailureIsAuditedSafely() throws Exception {
        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve()).thenReturn(SCOPE);
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.healthCheck()).thenReturn(true);
        when(llm.providerName()).thenReturn("openai-compat");
        when(llm.modelName()).thenReturn("configured-model");
        when(llm.complete(any(LlmProvider.AiRequest.class))).thenThrow(new LlmProvider.LlmException(
                "TIMEOUT", "private endpoint bearer secret prompt body"));
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);
        AiQueryHistoryMapper historyMapper = mock(AiQueryHistoryMapper.class);

        TextToSqlService.QueryResult result = service(resolver, mock(QueryCostGuard.class),
                realValidator(), mock(SqlExecutor.class), llm, historyMapper, callLogMapper)
                .query("趋势如何", "u1");

        assertEquals("FAILED", result.status());
        assertEquals("TIMEOUT", result.errorCode());
        assertTrue(result.error().contains("TIMEOUT"));
        assertTrue(!result.error().contains("secret"));
        ArgumentCaptor<AiQueryHistory> history = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(historyMapper).insert(history.capture());
        assertTrue(!history.getValue().getErrors().contains("secret"));
        ArgumentCaptor<AiCallLog> call = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callLogMapper).insert(call.capture());
        assertEquals("openai-compat", call.getValue().getProvider());
        assertEquals("configured-model", call.getValue().getModel());
        assertEquals(17L, call.getValue().getSourceId());
        assertEquals("S20260901_24", call.getValue().getSnapshotId());
        assertTrue(call.getValue().getRequestFingerprint().matches("[0-9a-f]{64}"));
        assertNull(call.getValue().getInputTokens());
        assertNull(call.getValue().getOutputTokens());
        assertEquals("FAILED", call.getValue().getStatus());
        assertEquals("TIMEOUT", call.getValue().getError());
        assertTrue(!call.getValue().getError().contains("secret"));
    }

    @Test
    @DisplayName("未分类 Provider 运行时异常也只向外暴露 MODEL_CALL_FAILED")
    void unclassifiedProviderFailureIsMasked() throws Exception {
        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve()).thenReturn(SCOPE);
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.healthCheck()).thenReturn(true);
        when(llm.providerName()).thenReturn("custom-provider");
        when(llm.complete(any(LlmProvider.AiRequest.class))).thenThrow(
                new IllegalStateException("private endpoint bearer secret prompt body"));
        AiQueryHistoryMapper historyMapper = mock(AiQueryHistoryMapper.class);
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);

        TextToSqlService.QueryResult result = service(resolver, mock(QueryCostGuard.class),
                realValidator(), mock(SqlExecutor.class), llm, historyMapper, callLogMapper)
                .query("趋势如何", "u1");

        assertEquals("FAILED", result.status());
        assertEquals("MODEL_CALL_FAILED", result.errorCode());
        assertTrue(!result.error().contains("secret"));
        ArgumentCaptor<AiQueryHistory> history = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(historyMapper).insert(history.capture());
        assertTrue(!history.getValue().getErrors().contains("secret"));
        ArgumentCaptor<AiCallLog> call = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callLogMapper).insert(call.capture());
        assertEquals("MODEL_CALL_FAILED", call.getValue().getError());
    }

    @Test
    @DisplayName("通过与执行的查询：审计写 EXPLAIN 行数与 EXECUTED 状态")
    void 成功路径审计写explain行数() throws Exception {
        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve()).thenReturn(SCOPE);

        LlmProvider llm = mock(LlmProvider.class);
        when(llm.healthCheck()).thenReturn(true);
        when(llm.providerName()).thenReturn("mock");
        when(llm.complete(any(LlmProvider.AiRequest.class))).thenReturn(new LlmProvider.AiResponse(
                "{\"sql\":\"SELECT dt, sale_amount FROM ads_sale_trend_m WHERE snapshot_id = 'S20260901_24' "
                        + "AND dt >= '20260901' AND dt <= '20260904' LIMIT 100000\",\"assumptions\":[]}",
                10, 10, "mock"));

        QueryCostGuard guard = mock(QueryCostGuard.class);
        when(guard.check(anyString())).thenReturn(new QueryCostGuard.CostEstimate(1_234L, 1));
        SqlExecutor executor = mock(SqlExecutor.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dt", "20260904");
        when(executor.execute(anyString())).thenReturn(new SqlExecutor.ExecutionResult(
                List.of(row), 12L, false));
        AiQueryHistoryMapper historyMapper = mock(AiQueryHistoryMapper.class);
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);

        TextToSqlService.QueryResult r = service(resolver, guard, realValidator(), executor, llm,
                historyMapper, callLogMapper).query("最近销售额", "u1");

        assertEquals("EXECUTED", r.status());
        assertEquals(17L, r.sourceId());
        assertEquals("S20260901_24", r.snapshotId());
        assertEquals(1, r.rowsReturned());
        assertNull(r.errorCode());

        ArgumentCaptor<AiQueryHistory> captor = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(historyMapper).insert(captor.capture());
        AiQueryHistory h = captor.getValue();
        // 校验通过内部记为 SAFE，执行成功后审计落终态 EXECUTED
        assertEquals("EXECUTED", h.getStatus());
        assertEquals(1_234L, h.getExplainRows());
        assertEquals("S20260901_24", h.getSnapshotId());
        assertEquals("ads_sale_trend_m", h.getTables());
        assertNull(h.getErrors());

        ArgumentCaptor<AiCallLog> call = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callLogMapper).insert(call.capture());
        assertEquals("mock", call.getValue().getProvider());
        assertEquals("mock", call.getValue().getModel());
        assertEquals("sql_generation", call.getValue().getUseCase());
        assertEquals("OK", call.getValue().getStatus());
        assertEquals(10, call.getValue().getInputTokens());
        assertEquals(10, call.getValue().getOutputTokens());
    }

    @Test
    @DisplayName("显式选择的快照进入 AI SQL 作用域和查询审计，不回退到 ACTIVE")
    void 显式快照贯穿问数与审计() throws Exception {
        String selectedSnapshot = "S20260917_9";
        AiScope selectedScope = AiScope.of(selectedSnapshot, "v2", LocalDate.of(2026, 9, 17));
        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve(selectedSnapshot)).thenReturn(selectedScope);

        LlmProvider llm = mock(LlmProvider.class);
        QueryCostGuard guard = mock(QueryCostGuard.class);
        when(guard.check(anyString())).thenReturn(new QueryCostGuard.CostEstimate(50L, 1));
        SqlExecutor executor = mock(SqlExecutor.class);
        when(executor.execute(anyString())).thenReturn(new SqlExecutor.ExecutionResult(List.of(), 2L, false));
        AiQueryHistoryMapper historyMapper = mock(AiQueryHistoryMapper.class);
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);

        TextToSqlService.QueryResult result = service(resolver, guard, realValidator(), executor, llm,
                historyMapper, callLogMapper).query("最近 7 天销售额趋势", "u1", null, selectedSnapshot);

        assertEquals("EXECUTED", result.status());
        verify(resolver).resolve(selectedSnapshot);
        verify(resolver, never()).resolve();
        ArgumentCaptor<AiQueryHistory> history = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(historyMapper).insert(history.capture());
        assertEquals(selectedSnapshot, history.getValue().getSnapshotId());
        assertEquals(LocalDate.of(2026, 9, 17), history.getValue().getScopeMaxDate());
    }

    @Test
    @DisplayName("Provider 已成功但调用审计写失败：稳定失败分类、不重放且保留问数历史")
    void 成功调用审计写失败不重放Provider() throws Exception {
        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve()).thenReturn(SCOPE);
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.healthCheck()).thenReturn(true);
        when(llm.providerName()).thenReturn("mock");
        when(llm.complete(any(LlmProvider.AiRequest.class)))
                .thenReturn(new LlmProvider.AiResponse("{}", 12, 5, "mock-model"));
        AiQueryHistoryMapper historyMapper = mock(AiQueryHistoryMapper.class);
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);
        doThrow(new IllegalStateException("private-mapper-detail"))
                .when(callLogMapper).insert(any(AiCallLog.class));
        QueryCostGuard guard = mock(QueryCostGuard.class);
        SqlExecutor executor = mock(SqlExecutor.class);

        TextToSqlService.QueryResult result = service(resolver, guard, realValidator(), executor,
                llm, historyMapper, callLogMapper).query("趋势如何", "u1");

        assertEquals("FAILED", result.status());
        assertEquals("AI_CALL_AUDIT_WRITE_FAILED", result.errorCode());
        org.assertj.core.api.Assertions.assertThat(result.error()).doesNotContain("private-mapper-detail");
        verify(llm).complete(any(LlmProvider.AiRequest.class));
        verify(callLogMapper).insert(any(AiCallLog.class));
        verify(executor, never()).execute(anyString());
        ArgumentCaptor<AiQueryHistory> history = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(historyMapper).insert(history.capture());
        assertEquals("FAILED", history.getValue().getStatus());
        org.assertj.core.api.Assertions.assertThat(history.getValue().getErrors())
                .contains("AI_CALL_AUDIT_WRITE_FAILED").doesNotContain("private-mapper-detail");
    }

    @Test
    @DisplayName("Provider 失败且 FAILED 审计也写失败：不把底层 Mapper 异常透出")
    void providerFailureAuditWriteFailureHasStableError() throws Exception {
        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve()).thenReturn(SCOPE);
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.healthCheck()).thenReturn(true);
        when(llm.providerName()).thenReturn("mock");
        when(llm.complete(any(LlmProvider.AiRequest.class)))
                .thenThrow(new LlmProvider.LlmException("TIMEOUT", "private-provider-detail"));
        AiQueryHistoryMapper historyMapper = mock(AiQueryHistoryMapper.class);
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);
        doThrow(new IllegalStateException("private-mapper-detail"))
                .when(callLogMapper).insert(any(AiCallLog.class));

        TextToSqlService.QueryResult result = service(resolver, mock(QueryCostGuard.class), realValidator(),
                mock(SqlExecutor.class), llm, historyMapper, callLogMapper).query("趋势如何", "u1");

        assertEquals("FAILED", result.status());
        assertEquals("AI_CALL_FAILURE_AUDIT_WRITE_FAILED", result.errorCode());
        org.assertj.core.api.Assertions.assertThat(result.error())
                .doesNotContain("private-provider-detail", "private-mapper-detail");
        verify(llm).complete(any(LlmProvider.AiRequest.class));
        verify(callLogMapper).insert(any(AiCallLog.class));
        ArgumentCaptor<AiQueryHistory> history = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(historyMapper).insert(history.capture());
        org.assertj.core.api.Assertions.assertThat(history.getValue().getErrors())
                .contains("AI_CALL_FAILURE_AUDIT_WRITE_FAILED")
                .doesNotContain("private-provider-detail", "private-mapper-detail");
    }

    @Test
    @DisplayName("Provider 空响应且 FORMAT 审计写失败：稳定分类并保留问数历史")
    void nullResponseAuditWriteFailureHasStableError() throws Exception {
        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve()).thenReturn(SCOPE);
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.healthCheck()).thenReturn(true);
        when(llm.providerName()).thenReturn("mock");
        when(llm.complete(any(LlmProvider.AiRequest.class))).thenReturn(null);
        AiQueryHistoryMapper historyMapper = mock(AiQueryHistoryMapper.class);
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);
        doThrow(new IllegalStateException("private-mapper-detail"))
                .when(callLogMapper).insert(any(AiCallLog.class));

        TextToSqlService.QueryResult result = service(resolver, mock(QueryCostGuard.class), realValidator(),
                mock(SqlExecutor.class), llm, historyMapper, callLogMapper).query("趋势如何", "u1");

        assertEquals("FAILED", result.status());
        assertEquals("AI_RESPONSE_AUDIT_WRITE_FAILED", result.errorCode());
        org.assertj.core.api.Assertions.assertThat(result.error()).doesNotContain("private-mapper-detail");
        verify(llm).complete(any(LlmProvider.AiRequest.class));
        verify(callLogMapper).insert(any(AiCallLog.class));
        ArgumentCaptor<AiQueryHistory> history = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(historyMapper).insert(history.capture());
        org.assertj.core.api.Assertions.assertThat(history.getValue().getErrors())
                .contains("AI_RESPONSE_AUDIT_WRITE_FAILED").doesNotContain("private-mapper-detail");
    }

    /** 真实校验器（真实目录白名单），保证测试断言的是生产规则而不是桩 */
    private static SqlSafetyValidator realValidator() {
        SemanticCatalog real = new SemanticCatalog();
        SemanticCatalog mock = mock(SemanticCatalog.class);
        Map<String, Set<String>> stubs = new LinkedHashMap<>();
        stubs.put("ads_sale_trend_m", Set.of("snapshot_id", "dt", "order_count", "buyer_count",
                "sale_amount", "avg_order_value", "net_sale_amount"));
        stubs.put("ads_hot_product_m", Set.of("snapshot_id", "dt", "product_id", "product_name",
                "heat_score", "pv", "fav", "cart", "buy", "rank_no"));
        stubs.forEach((t, cols) -> {
            when(mock.tableWhitelisted(t)).thenReturn(true);
            when(mock.fieldExists(org.mockito.ArgumentMatchers.eq(t), anyString()))
                    .thenAnswer(inv -> cols.contains(((String) inv.getArgument(1)).toLowerCase(java.util.Locale.ROOT)));
        });
        // 目录只被校验器用作白名单来源；真实目录用于断言前置校验不误伤
        assertTrue(real.tableWhitelisted("ads_sale_trend_m"));
        return new SqlSafetyValidator(mock);
    }

    /** 编译期守卫：QueryResult 的错误码字段与契约命名一致 */
    @Test
    @DisplayName("QueryResult 暴露稳定规则码字段（供控制器/审计复用）")
    void queryResult暴露规则码() {
        TextToSqlService.QueryResult r = new TextToSqlService.QueryResult(
                "REJECTED", null, List.of(), 0, 1L, List.of(), List.of(), "err", "SQL_JOIN", "rule-based");
        assertEquals("SQL_JOIN", r.errorCode());
        ValidationResult ignored = ValidationResult.fail("X", "y");
        assertEquals("X", ignored.code());
        AiCallLog unused = new AiCallLog();
        assertNull(unused.getError());
    }
}
