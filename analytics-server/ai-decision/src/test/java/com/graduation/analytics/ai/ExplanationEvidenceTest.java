package com.graduation.analytics.ai;

import com.graduation.analytics.ai.evidence.EvidencePackage;
import com.graduation.analytics.ai.evidence.EvidenceTemplates;
import com.graduation.analytics.ai.llm.LlmProvider;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.sql.SqlExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R8-1 证据解释测试（§19.3）：模板优先；模型只改措辞且受**数值守卫**约束；
 * 模型不可用/越界/异常一律回退模板（永远有结论）。
 */
@ExtendWith(MockitoExtension.class)
class ExplanationEvidenceTest {

    private static final String SNAP = "S20260901_24";

    @Mock
    private LlmProvider llmProvider;
    @Mock
    private AiCallLogMapper callLogMapper;
    @Mock
    private SqlExecutor executor;

    private ExplanationService service() {
        return new ExplanationService(llmProvider, callLogMapper, executor);
    }

    private EvidencePackage pkg() {
        return new EvidencePackage("EV-20260911-abc123", EvidencePackage.TEMPLATE_VERSION, SNAP, "v2",
                "2026-09-11T09:00:00",
                new EvidencePackage.Period("2026-09-01", "2026-09-01"),
                new EvidencePackage.Period("2026-09-01", "2026-09-01"), null,
                List.of(new EvidencePackage.Fact("gmv", "销售额(GMV)", "2042.0000", "元",
                        "day:2026-09-01", "metric_value.gmv@" + SNAP)),
                List.of(),
                Map.of("product", List.of(new EvidencePackage.DimensionContribution(
                                "3", "保温杯", "11.0904", "0.5161", "product_heat",
                                "ads_hot_product_m.heat_score@" + SNAP)),
                        "category", List.of(), "region", List.of(), "channel", List.of()),
                List.of(new EvidencePackage.AnomalyCandidate("REFUND_RATE_HIGH", "refund_rate", "MEDIUM",
                        "0.6000", "0.3000", "0.3000",
                        "refund_rate 高于阈值 0.3000，当前 0.6000（可能相关，不构成因果）",
                        "metric_value.refund_rate@" + SNAP)),
                new EvidencePackage.DataQuality("PASS", 4, 3, List.of("EVENT_ID_UNIQUE"), List.of()),
                new EvidencePackage.Lineage(List.of("dw_ads.ads_operation_overview"),
                        List.of("ads_operation_overview_m", "ads_data_quality_m"), 21L, SNAP),
                List.of(EvidencePackage.WARN_NO_COMPARISON_PERIOD,
                        EvidencePackage.WARN_UNKNOWN_DIMENSION_TABLE));
    }

    @Test
    @DisplayName("模型不可用：完全走模板，facts 带 evidenceIds，异常进 possibleCauses，且不调用模型")
    void templateOnlyWhenProviderUnavailable() {
        when(llmProvider.healthCheck()).thenReturn(false);

        ExplanationService.ExplanationResult result = service().explain(pkg(), "昨天卖得怎么样");

        assertThat(result.summary()).contains(SNAP).contains("PASS");
        assertThat(result.facts()).isNotEmpty();
        assertThat(result.facts()).allSatisfy(f ->
                assertThat((List<?>) f.get("evidenceIds")).isNotEmpty());
        assertThat(result.possibleCauses()).hasSize(1);
        assertThat(result.possibleCauses().get(0).get("confidence")).isEqualTo("LOW");
        assertThat(result.suggestions()).isNotEmpty();
        assertThat(result.limitations()).anySatisfy(l -> assertThat(l).contains("未调用大模型"));

        assertThat(result.evidence().evidenceId()).isEqualTo("EV-20260911-abc123");
        assertThat(result.evidence().templateVersion()).isEqualTo(EvidencePackage.TEMPLATE_VERSION);
        assertThat(result.evidence().tables()).contains("ads_operation_overview_m");
        assertThat(result.evidence().returnedRows()).isEqualTo(1);
        verify(llmProvider, never()).complete(any());
    }

    @Test
    @DisplayName("模型改写通过数值守卫：采用改写摘要并记录 OK 调用日志")
    void acceptsRewriteThatKeepsNumbers() {
        when(llmProvider.healthCheck()).thenReturn(true);
        when(llmProvider.providerName()).thenReturn("mock");
        when(llmProvider.complete(any())).thenReturn(
                new LlmProvider.AiResponse("快照 " + SNAP + " 共 1 个指标，质量门 PASS，命中 1 条候选异常。",
                        10, 20, "mock"));

        ExplanationService.ExplanationResult result = service().explain(pkg(), "昨天卖得怎么样");

        assertThat(result.summary()).startsWith("快照 " + SNAP);
        assertThat(result.limitations()).noneSatisfy(l -> assertThat(l).contains("数值校验"));
        verify(callLogMapper).insert(any(com.graduation.analytics.ai.entity.AiCallLog.class));
    }

    @Test
    @DisplayName("模型编造数字：摘要回退模板并登记 REJECTED（禁止无依据数值）")
    void rejectsFabricatedNumbers() {
        when(llmProvider.healthCheck()).thenReturn(true);
        when(llmProvider.providerName()).thenReturn("mock");
        when(llmProvider.complete(any())).thenReturn(new LlmProvider.AiResponse(
                "销售额达到 99999.0000 元，同比大涨 88.88%。", 10, 20, "mock"));

        ExplanationService.ExplanationResult result = service().explain(pkg(), "昨天卖得怎么样");

        assertThat(EvidenceTemplates.render(pkg()).summary()).isEqualTo(result.summary());
        assertThat(result.summary()).doesNotContain("99999");
        assertThat(result.limitations()).anySatisfy(l -> assertThat(l).contains("数值校验"));

        ArgumentCaptor<com.graduation.analytics.ai.entity.AiCallLog> captor =
                ArgumentCaptor.forClass(com.graduation.analytics.ai.entity.AiCallLog.class);
        verify(callLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("REJECTED");
        assertThat(captor.getValue().getError()).contains("SUMMARY_NUMBER_GUARD");
    }

    @Test
    @DisplayName("模型抛异常：回退模板，不把异常抛给调用方")
    void fallsBackWhenProviderThrows() {
        when(llmProvider.healthCheck()).thenReturn(true);
        when(llmProvider.providerName()).thenReturn("openai-compat");
        when(llmProvider.complete(any())).thenThrow(new LlmProvider.LlmException("TIMEOUT", "timeout"));

        ExplanationService.ExplanationResult result = service().explain(pkg(), "昨天卖得怎么样");

        assertThat(result.summary()).isEqualTo(EvidenceTemplates.render(pkg()).summary());
        assertThat(result.limitations()).anySatisfy(l -> assertThat(l).contains("数值校验"));
    }

    @Test
    @DisplayName("数值守卫：只放过证据叙述里出现过的数字")
    void numberGuardAllowList() {
        String evidence = "快照 S20260901_24（2026-09-01）gmv = 2042.0000，环比 1.0420";

        assertThat(ExplanationService.numberViolations("gmv 2042.0000，环比 1.0420", evidence)).isEmpty();
        assertThat(ExplanationService.numberViolations("gmv 9999.0000", evidence)).containsExactly("9999.0000");
    }
}
