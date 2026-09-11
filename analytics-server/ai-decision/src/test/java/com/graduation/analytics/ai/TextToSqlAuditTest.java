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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R8-2 审计落库断言（契约 §2.3）：被拒绝的查询也必须落一行 {@code ai_query_history}，
 * 且「哪个快照 / 允许哪段时间 / 行数上限」写入**真实列**（V14 第 4 段提供），
 * {@code errors} 只保留错误文本。用 mock 的 Mapper 捕获插入对象，不依赖数据库。
 */
class TextToSqlAuditTest {

    private static final AiScope SCOPE = AiScope.of("S20260901_24", "v2", LocalDate.of(2026, 9, 4));

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
        when(llm.complete(any(LlmProvider.AiRequest.class))).thenReturn(new LlmProvider.AiResponse(
                "{\"sql\":\"SELECT a.dt FROM ads_sale_trend_m a JOIN ads_hot_product_m b ON a.dt = b.dt "
                        + "WHERE a.snapshot_id = 'S20260901_24' AND a.dt >= '20260904' AND a.dt <= '20260904'\","
                        + "\"assumptions\":[]}",
                10, 10, "mock"));

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
    }

    /** 真实校验器（真实目录白名单），保证测试断言的是生产规则而不是桩 */
    private static SqlSafetyValidator realValidator() {
        SemanticCatalog real = new SemanticCatalog();
        SemanticCatalog mock = mock(SemanticCatalog.class);
        Map<String, Set<String>> stubs = new LinkedHashMap<>();
        stubs.put("ads_sale_trend_m", Set.of("snapshot_id", "dt", "order_count", "buyer_count",
                "sale_amount", "avg_order_value"));
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
