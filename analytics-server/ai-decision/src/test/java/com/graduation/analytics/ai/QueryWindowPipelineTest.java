package com.graduation.analytics.ai;

import com.graduation.analytics.ai.entity.AiQueryHistory;
import com.graduation.analytics.ai.llm.LlmProvider;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.mapper.AiQueryHistoryMapper;
import com.graduation.analytics.ai.sql.AiScope;
import com.graduation.analytics.ai.sql.AiScopeResolver;
import com.graduation.analytics.ai.sql.QueryCostGuard;
import com.graduation.analytics.ai.sql.SqlExecutor;
import com.graduation.analytics.ai.sql.SqlSafetyValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA-04 端到端（不连库）：问数接口的「请求区间」与「实际生效区间」必须同时出现在
 * {@code QueryResult.window} 里，且生效区间来自**真实 SQL 校验器解析出的 dt 字面量**，
 * 不是请求标签的复制品。
 */
class QueryWindowPipelineTest {

    private static final AiScope SCOPE = AiScope.of("S20260917_01", "v2", LocalDate.of(2026, 9, 17));

    /** 模型生成的 SQL 只扫 7 天（业务日往前推 6 天），与请求的「近30天」不一致 */
    private static final String SQL = "SELECT dt, sale_amount FROM ads_sale_trend_m "
            + "WHERE snapshot_id = 'S20260917_01' AND dt >= '20260911' AND dt <= '20260917' "
            + "ORDER BY dt LIMIT 200";

    private static Map<String, Object> day(String dt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dt", dt);
        row.put("sale_amount", "500.00");
        return row;
    }

    /** LLM 可用路径：真实校验器 + 桩执行器（生效区间只能由校验器解析得到） */
    private static TextToSqlService llmService(List<Map<String, Object>> rows) throws Exception {
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.healthCheck()).thenReturn(true);
        when(llm.providerName()).thenReturn("mock-provider");
        when(llm.complete(any())).thenReturn(new LlmProvider.AiResponse(
                "{\"sql\":\"" + SQL + "\",\"assumptions\":[]}", 1, 1, "mock-provider"));

        SemanticCatalog catalog = mock(SemanticCatalog.class);
        when(catalog.selectTables(anyString())).thenReturn(List.of("ads_sale_trend_m"));
        when(catalog.schemaJson(any())).thenReturn("{}");
        when(catalog.fewShots(any(), eq(SCOPE))).thenReturn("[]");
        Set<String> cols = new LinkedHashSet<>(List.of("snapshot_id", "dt", "order_count", "buyer_count",
                "sale_amount", "avg_order_value", "net_sale_amount"));
        when(catalog.tableWhitelisted("ads_sale_trend_m")).thenReturn(true);
        when(catalog.fieldExists(eq("ads_sale_trend_m"), anyString()))
                .thenAnswer(inv -> cols.contains(((String) inv.getArgument(1)).toLowerCase(java.util.Locale.ROOT)));

        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve()).thenReturn(SCOPE);

        SqlExecutor executor = mock(SqlExecutor.class);
        when(executor.execute(anyString())).thenReturn(new SqlExecutor.ExecutionResult(rows, 3L, false));

        QueryCostGuard guard = mock(QueryCostGuard.class);
        when(guard.check(anyString())).thenReturn(new QueryCostGuard.CostEstimate(10L, 1));

        return new TextToSqlService(llm, catalog, new SqlSafetyValidator(catalog), guard, resolver, executor,
                mock(AiQueryHistoryMapper.class), mock(AiCallLogMapper.class));
    }

    @Test
    @DisplayName("QA-04 请求「近30天」+ 实际 7 天 SQL + 1 天结果：window 同时说清请求、生效与覆盖")
    void windowExposesRequestedAndEffectiveRange() throws Exception {
        TextToSqlService svc = llmService(List.of(day("20260917")));

        TextToSqlService.QueryResult result = svc.query("最近销售趋势", "u1", "近30天");

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(result.window()).isNotNull();
        assertThat(result.window().requested()).isEqualTo("近30天");
        assertThat(result.window().requestedDays()).isEqualTo(30);
        assertThat(result.window().from()).isEqualTo("2026-09-11");
        assertThat(result.window().to()).isEqualTo("2026-09-17");
        assertThat(result.window().days()).isEqualTo(7);
        assertThat(result.window().referenceBusinessDate()).isEqualTo("2026-09-17");
        assertThat(result.window().coveredDays()).isEqualTo(1);
        assertThat(result.window().notice()).contains("近30天").contains("只覆盖 1 天");
    }

    @Test
    @DisplayName("QA-04 未指定区间时 window 仍然给出生效区间与覆盖天数（requested 为 null）")
    void windowWithoutRequestedLabelStillReportsEffectiveRange() throws Exception {
        TextToSqlService svc = llmService(List.of(day("20260911"), day("20260912")));

        TextToSqlService.QueryResult result = svc.query("最近销售趋势", "u1");

        assertThat(result.window()).isNotNull();
        assertThat(result.window().requested()).isNull();
        assertThat(result.window().days()).isEqualTo(7);
        assertThat(result.window().coveredDays()).isEqualTo(2);
        assertThat(result.window().notice()).contains("只覆盖 2 天");
    }

    @Test
    @DisplayName("QA-04 规则回退路径（模型不可用）同样带出结构化 7 天窗口，审计字段不变")
    void ruleFallbackAlsoCarriesWindow() throws Exception {
        AiQueryHistoryMapper history = mock(AiQueryHistoryMapper.class);
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.healthCheck()).thenReturn(false);
        SemanticCatalog catalog = new SemanticCatalog();

        AiScopeResolver resolver = mock(AiScopeResolver.class);
        when(resolver.resolve()).thenReturn(SCOPE);
        SqlExecutor executor = mock(SqlExecutor.class);
        when(executor.execute(anyString())).thenReturn(new SqlExecutor.ExecutionResult(List.of(), 1L, false));
        QueryCostGuard guard = mock(QueryCostGuard.class);
        when(guard.check(anyString())).thenReturn(new QueryCostGuard.CostEstimate(10L, 1));

        TextToSqlService svc = new TextToSqlService(llm, catalog, new SqlSafetyValidator(catalog), guard,
                resolver, executor, history, mock(AiCallLogMapper.class));

        TextToSqlService.QueryResult result = svc.query("销售趋势", "u1", "近30天");

        assertThat(result.errorCode()).isNull();
        assertThat(result.window()).isNotNull();
        assertThat(result.window().days()).isEqualTo(7);
        assertThat(result.window().requestedDays()).isEqualTo(30);
        assertThat(result.window().notice()).contains("近30天").contains("7 天");

        ArgumentCaptor<AiQueryHistory> captor = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(history).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isNotBlank();
    }
}
