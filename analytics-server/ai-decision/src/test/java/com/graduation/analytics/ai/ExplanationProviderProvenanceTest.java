package com.graduation.analytics.ai;

import com.graduation.analytics.ai.evidence.EvidencePackage;
import com.graduation.analytics.ai.evidence.EvidenceTemplates;
import com.graduation.analytics.ai.entity.AiCallLog;
import com.graduation.analytics.ai.llm.LlmProvider;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.sql.SqlExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S3-57：provider provenance 必须来自真实调用链，禁止通过“文本是否变化”反推。 */
class ExplanationProviderProvenanceTest {

    private static EvidencePackage pkg() {
        return new EvidencePackage("EV-20260917-provider", EvidencePackage.TEMPLATE_VERSION,
                "S20260917_01", "v2", "2026-09-17T08:00:00",
                new EvidencePackage.Period("2026-09-16", "2026-09-16"),
                new EvidencePackage.Period("2026-09-15", "2026-09-15"), null,
                List.of(new EvidencePackage.Fact("gmv", "销售额(GMV)", "100.0000", "元",
                        "day:2026-09-16", "metric_value.gmv@S20260917_01")),
                List.of(), Map.of("product", List.of(), "category", List.of(),
                        "region", List.of(), "channel", List.of()),
                List.of(),
                new EvidencePackage.DataQuality("PASS", 1, 1, List.of(), List.of()),
                new EvidencePackage.Lineage(List.of("dw_ads.ads_operation_overview"),
                        List.of("ads_operation_overview_m"), 1L, "S20260917_01", 17L),
                List.of());
    }

    private static ExplanationService service(LlmProvider provider) {
        return new ExplanationService(provider, mock(AiCallLogMapper.class), mock(SqlExecutor.class));
    }

    @Test
    void 模型输出即使逐字等于模板也必须记录真实provider() {
        EvidencePackage pkg = pkg();
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.providerName()).thenReturn("openai-compat");
        when(provider.modelName()).thenReturn("configured-model");
        // 旧控制器通过“摘要是否等于模板”推断来源，这个场景会被误标 template。
        when(provider.complete(any())).thenReturn(new LlmProvider.AiResponse(
                EvidenceTemplates.render(pkg).summary(), 10, 10, "response-model"));

        ExplanationService.ExplanationResult result = new ExplanationService(
                provider, callLogMapper, mock(SqlExecutor.class)).explain(pkg, "昨天销售如何");

        assertThat(result.summary()).isEqualTo(EvidenceTemplates.render(pkg).summary());
        assertThat(result.providerUsed()).isEqualTo("openai-compat");
        var captured = forClass(AiCallLog.class);
        verify(callLogMapper).insert(captured.capture());
        assertThat(captured.getValue().getProvider()).isEqualTo("openai-compat");
        assertThat(captured.getValue().getModel()).isEqualTo("response-model");
        assertThat(captured.getValue().getInputTokens()).isEqualTo(10);
        assertThat(captured.getValue().getOutputTokens()).isEqualTo(10);
        assertThat(captured.getValue().getSourceId()).isEqualTo(17L);
        assertThat(captured.getValue().getSnapshotId()).isEqualTo("S20260917_01");
        assertThat(captured.getValue().getRequestFingerprint()).matches("[0-9a-f]{64}")
                .doesNotContain("昨天销售如何", EvidenceTemplates.render(pkg).summary());
    }

    @Test
    void 模型调用失败后采用模板必须记录template而不是曾尝试的provider() {
        EvidencePackage pkg = pkg();
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.providerName()).thenReturn("openai-compat");
        when(provider.complete(any())).thenThrow(new LlmProvider.LlmException("TIMEOUT", "timeout"));

        ExplanationService.ExplanationResult result = new ExplanationService(
                provider, callLogMapper, mock(SqlExecutor.class)).explain(pkg, "昨天销售如何");

        assertThat(result.summary()).isEqualTo(EvidenceTemplates.render(pkg).summary());
        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        var captured = forClass(AiCallLog.class);
        verify(callLogMapper).insert(captured.capture());
        assertThat(captured.getValue().getSourceId()).isEqualTo(17L);
        assertThat(captured.getValue().getSnapshotId()).isEqualTo("S20260917_01");
        assertThat(captured.getValue().getRequestFingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void provider不可用时模板来源必须显式记录() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.healthCheck()).thenReturn(false);

        ExplanationService.ExplanationResult result = service(provider).explain(pkg(), "昨天销售如何");

        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIMEOUT", "RESPONSE_TOO_LARGE"})
    void providerFailureLogStoresOnlyStableErrorCode(String errorType) {
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.complete(any())).thenThrow(new LlmProvider.LlmException(
                errorType, "private-provider-body prompt-echo bearer provider-secret"));
        ExplanationService service = new ExplanationService(provider, callLogMapper, mock(SqlExecutor.class));

        ExplanationService.ExplanationResult result = service.explain(pkg(), "昨天销售如何");

        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        var captured = forClass(AiCallLog.class);
        verify(callLogMapper).insert(captured.capture());
        assertThat(captured.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captured.getValue().getInputTokens()).isNull();
        assertThat(captured.getValue().getOutputTokens()).isNull();
        assertThat(captured.getValue().getError()).isEqualTo(errorType)
                .doesNotContain("private-provider-body", "prompt-echo", "provider-secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "无依据的数字 99999"})
    void locallyRejectedResponseStillRecordsReportedUsage(String content) {
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.complete(any())).thenReturn(new LlmProvider.AiResponse(content, 23, 9, "actual-model"));

        ExplanationService.ExplanationResult result = new ExplanationService(
                provider, mapper, mock(SqlExecutor.class)).explain(pkg(), "昨天销售如何");

        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        var call = forClass(AiCallLog.class);
        verify(mapper).insert(call.capture());
        assertThat(call.getValue().getStatus()).isEqualTo("REJECTED");
        assertThat(call.getValue().getModel()).isEqualTo("actual-model");
        assertThat(call.getValue().getInputTokens()).isEqualTo(23);
        assertThat(call.getValue().getOutputTokens()).isEqualTo(9);
    }

    @Test
    void providerBudgetRejectionPreservesKnownUsage() {
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.complete(any())).thenThrow(new LlmProvider.LlmException(
                "OUTPUT_BUDGET_EXCEEDED", "limit", 21, 2048));

        new ExplanationService(provider, mapper, mock(SqlExecutor.class)).explain(pkg(), "昨天销售如何");

        var call = forClass(AiCallLog.class);
        verify(mapper).insert(call.capture());
        assertThat(call.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(call.getValue().getError()).isEqualTo("OUTPUT_BUDGET_EXCEEDED");
        assertThat(call.getValue().getInputTokens()).isEqualTo(21);
        assertThat(call.getValue().getOutputTokens()).isEqualTo(2048);
    }

    @Test
    void successfulResponseWithoutUsageKeepsAuditCountsNull() {
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.complete(any())).thenReturn(new LlmProvider.AiResponse(
                EvidenceTemplates.render(pkg()).summary(), null, null, "actual-model"));

        new ExplanationService(provider, mapper, mock(SqlExecutor.class)).explain(pkg(), "昨天销售如何");

        var call = forClass(AiCallLog.class);
        verify(mapper).insert(call.capture());
        assertThat(call.getValue().getStatus()).isEqualTo("OK");
        assertThat(call.getValue().getInputTokens()).isNull();
        assertThat(call.getValue().getOutputTokens()).isNull();
    }

    @Test
    void evidenceRewriteAuditWriteFailureFallsBackWithoutLeakingMapperDetail() {
        EvidencePackage pkg = pkg();
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.providerName()).thenReturn("mock");
        when(provider.complete(any())).thenReturn(new LlmProvider.AiResponse(
                EvidenceTemplates.render(pkg).summary(), 10, 7, "mock-model"));
        doThrow(new IllegalStateException("private-mapper-detail"))
                .when(mapper).insert(any(AiCallLog.class));

        ExplanationService.ExplanationResult result = new ExplanationService(
                provider, mapper, mock(SqlExecutor.class)).explain(pkg, "昨天销售如何");

        assertThat(result.summary()).isEqualTo(EvidenceTemplates.render(pkg).summary());
        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        assertThat(result.limitations()).anySatisfy(l -> assertThat(l)
                .contains("审计").doesNotContain("private-mapper-detail"));
        verify(provider).complete(any());
        verify(mapper, times(1)).insert(any(AiCallLog.class));
    }

    @Test
    void evidenceRewriteFailureAuditWriteFailureKeepsTemplateFallback() {
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.providerName()).thenReturn("mock");
        when(provider.complete(any())).thenThrow(new LlmProvider.LlmException(
                "TIMEOUT", "private-provider-detail"));
        doThrow(new IllegalStateException("private-mapper-detail"))
                .when(mapper).insert(any(AiCallLog.class));

        ExplanationService.ExplanationResult result = new ExplanationService(
                provider, mapper, mock(SqlExecutor.class)).explain(pkg(), "昨天销售如何");

        assertThat(result.summary()).isEqualTo(EvidenceTemplates.render(pkg()).summary());
        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        assertThat(result.limitations()).anySatisfy(l -> assertThat(l)
                .contains("审计").doesNotContain("private-provider-detail", "private-mapper-detail"));
        verify(provider).complete(any());
        verify(mapper, times(1)).insert(any(AiCallLog.class));
    }

    @Test
    void evidenceRewriteNullResponseAuditWriteFailureKeepsTemplateFallback() {
        EvidencePackage pkg = pkg();
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.complete(any())).thenReturn(null);
        doThrow(new IllegalStateException("private-mapper-detail"))
                .when(mapper).insert(any(AiCallLog.class));

        ExplanationService.ExplanationResult result = new ExplanationService(
                provider, mapper, mock(SqlExecutor.class)).explain(pkg, "昨天销售如何");

        assertThat(result.summary()).isEqualTo(EvidenceTemplates.render(pkg).summary());
        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        assertThat(result.limitations()).anySatisfy(l -> assertThat(l)
                .contains("审计").doesNotContain("private-mapper-detail"));
        verify(provider).complete(any());
        verify(mapper, times(1)).insert(any(AiCallLog.class));
    }

    @Test
    void evidenceRewriteFormatRejectionAuditWriteFailureKeepsTemplateFallback() {
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.complete(any())).thenReturn(new LlmProvider.AiResponse("   ", 10, 7, "mock-model"));
        doThrow(new IllegalStateException("private-mapper-detail"))
                .when(mapper).insert(any(AiCallLog.class));

        ExplanationService.ExplanationResult result = new ExplanationService(
                provider, mapper, mock(SqlExecutor.class)).explain(pkg(), "昨天销售如何");

        assertThat(result.summary()).isEqualTo(EvidenceTemplates.render(pkg()).summary());
        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        assertThat(result.limitations()).anySatisfy(l -> assertThat(l)
                .contains("格式审查").doesNotContain("private-mapper-detail"));
        verify(provider).complete(any());
        verify(mapper, times(1)).insert(any(AiCallLog.class));
    }

    @Test
    void evidenceRewriteNumberRejectionAuditWriteFailureKeepsTemplateFallback() {
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.complete(any())).thenReturn(new LlmProvider.AiResponse(
                "销售额达到 99999 元", 10, 7, "mock-model"));
        doThrow(new IllegalStateException("private-mapper-detail"))
                .when(mapper).insert(any(AiCallLog.class));

        ExplanationService.ExplanationResult result = new ExplanationService(
                provider, mapper, mock(SqlExecutor.class)).explain(pkg(), "昨天销售如何");

        assertThat(result.summary()).isEqualTo(EvidenceTemplates.render(pkg()).summary())
                .doesNotContain("99999");
        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        assertThat(result.limitations()).anySatisfy(l -> assertThat(l)
                .contains("数值审查").doesNotContain("private-mapper-detail"));
        verify(provider).complete(any());
        verify(mapper, times(1)).insert(any(AiCallLog.class));
    }

    @Test
    void queryExplanationAuditWriteFailureRejectsUnpersistedModelRewrite() {
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.providerName()).thenReturn("mock");
        when(provider.complete(any())).thenReturn(new LlmProvider.AiResponse(
                "{\"summary\":\"模型改写\",\"facts\":[],\"possibleCauses\":[],\"suggestions\":[],\"limitations\":[]}",
                10, 5, "mock-model"));
        doThrow(new IllegalStateException("private-mapper-detail"))
                .when(mapper).insert(any(AiCallLog.class));
        TextToSqlService.QueryResult query = new TextToSqlService.QueryResult(
                "EXECUTED", "select gmv from ads_operation_overview_m",
                List.of("ads_operation_overview_m"), 1, 12L,
                List.of(Map.of("gmv", "2042.0000")), List.of(), null, null,
                "rule-based", null, "S_QUERY_ACTUAL", 42L);

        ExplanationService.ExplanationResult result = new ExplanationService(
                provider, mapper, mock(SqlExecutor.class)).explain(query, "S_QUERY_ACTUAL", "销售额如何", "近7天");

        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        assertThat(result.summary()).doesNotContain("模型改写", "private-mapper-detail");
        assertThat(result.limitations()).anySatisfy(l -> assertThat(l)
                .contains("审计").doesNotContain("private-mapper-detail"));
        verify(provider).complete(any());
        verify(mapper, times(1)).insert(any(AiCallLog.class));
    }

    @Test
    void queryExplanationNullResponseAuditWriteFailureStaysOnRuleBasedPath() {
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.complete(any())).thenReturn(null);
        doThrow(new IllegalStateException("private-mapper-detail"))
                .when(mapper).insert(any(AiCallLog.class));
        TextToSqlService.QueryResult query = new TextToSqlService.QueryResult(
                "EXECUTED", "select gmv from ads_operation_overview_m",
                List.of("ads_operation_overview_m"), 1, 12L,
                List.of(Map.of("gmv", "2042.0000")), List.of(), null, null,
                "rule-based", null, "S_QUERY_ACTUAL", 42L);

        ExplanationService.ExplanationResult result = new ExplanationService(
                provider, mapper, mock(SqlExecutor.class)).explain(query, "S_QUERY_ACTUAL", "销售额如何", "近7天");

        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        assertThat(result.summary()).doesNotContain("private-mapper-detail");
        assertThat(result.limitations()).anySatisfy(l -> assertThat(l)
                .contains("审计").doesNotContain("private-mapper-detail"));
        verify(provider).complete(any());
        verify(mapper, times(1)).insert(any(AiCallLog.class));
    }

    @Test
    void queryExplanationNumberRejectionAuditWriteFailureStaysOnRuleBasedPath() {
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.complete(any())).thenReturn(new LlmProvider.AiResponse(
                "{\"summary\":\"GMV 达到 99999 元\",\"facts\":[],\"possibleCauses\":[],\"suggestions\":[],\"limitations\":[]}",
                10, 5, "mock-model"));
        doThrow(new IllegalStateException("private-mapper-detail"))
                .when(mapper).insert(any(AiCallLog.class));
        TextToSqlService.QueryResult query = new TextToSqlService.QueryResult(
                "EXECUTED", "select gmv from ads_operation_overview_m",
                List.of("ads_operation_overview_m"), 1, 12L,
                List.of(Map.of("gmv", "2042.0000")), List.of(), null, null,
                "rule-based", null, "S_QUERY_ACTUAL", 42L);

        ExplanationService.ExplanationResult result = new ExplanationService(
                provider, mapper, mock(SqlExecutor.class)).explain(query, "S_QUERY_ACTUAL", "销售额如何", "近7天");

        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        assertThat(result.summary()).doesNotContain("99999", "private-mapper-detail");
        assertThat(result.limitations()).anySatisfy(l -> assertThat(l)
                .contains("审计").doesNotContain("private-mapper-detail"));
        verify(provider).complete(any());
        verify(mapper, times(1)).insert(any(AiCallLog.class));
    }

    @Test
    void queryExplanationProviderAndFailureAuditWriteFailureStayOnRuleBasedPath() {
        LlmProvider provider = mock(LlmProvider.class);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.providerName()).thenReturn("mock");
        when(provider.complete(any())).thenThrow(new LlmProvider.LlmException(
                "TIMEOUT", "private-provider-detail"));
        doThrow(new IllegalStateException("private-mapper-detail"))
                .when(mapper).insert(any(AiCallLog.class));
        TextToSqlService.QueryResult query = new TextToSqlService.QueryResult(
                "EXECUTED", "select gmv from ads_operation_overview_m",
                List.of("ads_operation_overview_m"), 1, 12L,
                List.of(Map.of("gmv", "2042.0000")), List.of(), null, null,
                "rule-based", null, "S_QUERY_ACTUAL", 42L);

        ExplanationService.ExplanationResult result = new ExplanationService(
                provider, mapper, mock(SqlExecutor.class)).explain(query, "S_QUERY_ACTUAL", "销售额如何", "近7天");

        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
        assertThat(result.summary()).doesNotContain("private-provider-detail", "private-mapper-detail");
        assertThat(result.limitations()).anySatisfy(l -> assertThat(l)
                .contains("审计").doesNotContain("private-provider-detail", "private-mapper-detail"));
        verify(provider).complete(any());
        verify(mapper, times(1)).insert(any(AiCallLog.class));
    }
}
