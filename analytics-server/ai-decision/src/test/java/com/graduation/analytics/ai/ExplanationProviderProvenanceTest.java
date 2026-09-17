package com.graduation.analytics.ai;

import com.graduation.analytics.ai.evidence.EvidencePackage;
import com.graduation.analytics.ai.evidence.EvidenceTemplates;
import com.graduation.analytics.ai.llm.LlmProvider;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.sql.SqlExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
                        List.of("ads_operation_overview_m"), 1L, "S20260917_01"),
                List.of());
    }

    private static ExplanationService service(LlmProvider provider) {
        return new ExplanationService(provider, mock(AiCallLogMapper.class), mock(SqlExecutor.class));
    }

    @Test
    void 模型输出即使逐字等于模板也必须记录真实provider() {
        EvidencePackage pkg = pkg();
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.providerName()).thenReturn("mock-provider");
        // 旧控制器通过“摘要是否等于模板”推断来源，这个场景会被误标 template。
        when(provider.complete(any())).thenReturn(new LlmProvider.AiResponse(
                EvidenceTemplates.render(pkg).summary(), 10, 10, "mock-provider"));

        ExplanationService.ExplanationResult result = service(provider).explain(pkg, "昨天销售如何");

        assertThat(result.summary()).isEqualTo(EvidenceTemplates.render(pkg).summary());
        assertThat(result.providerUsed()).isEqualTo("mock-provider");
    }

    @Test
    void 模型调用失败后采用模板必须记录template而不是曾尝试的provider() {
        EvidencePackage pkg = pkg();
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.healthCheck()).thenReturn(true);
        when(provider.providerName()).thenReturn("openai-compat");
        when(provider.complete(any())).thenThrow(new LlmProvider.LlmException("TIMEOUT", "timeout"));

        ExplanationService.ExplanationResult result = service(provider).explain(pkg, "昨天销售如何");

        assertThat(result.summary()).isEqualTo(EvidenceTemplates.render(pkg).summary());
        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
    }

    @Test
    void provider不可用时模板来源必须显式记录() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.healthCheck()).thenReturn(false);

        ExplanationService.ExplanationResult result = service(provider).explain(pkg(), "昨天销售如何");

        assertThat(result.providerUsed()).isEqualTo(EvidenceTemplates.Narrative.PROVIDER_TEMPLATE);
    }
}
