package com.graduation.analytics.pipeline;

import com.graduation.analytics.metric.MetricQualityGate;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import com.graduation.analytics.pipeline.mapper.DataQualityResultMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * R7-4 质量门 L0 单测：analytics_meta.data_quality_result → PASS/FAIL/UNKNOWN 的判定口径。
 *
 * <p>为什么单独测这个组件：看板信封的 {@code qualityStatus} 直接由它决定，
 * 判定放宽（例如把 ERROR 也当阻断、或把"查不到"当 PASS）会让页面显示错误的质量结论（§16.3/§18.3）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataQualityGateTest {

    @Mock
    private DataQualityResultMapper qualityMapper;

    @InjectMocks
    private DataQualityGate gate;

    @Test
    @DisplayName("存在 BLOCKING 未通过 → FAIL")
    void blockingFailureFailsTheGate() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result("BLOCKING", 1), result("ERROR", 0), result("BLOCKING", 0)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.FAIL);
    }

    @Test
    @DisplayName("只有非阻断规则失败（ERROR/INFO）→ PASS，不误报为 FAIL")
    void nonBlockingFailureKeepsPass() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result("ERROR", 0), result("INFO", 0), result("BLOCKING", 1)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.PASS);
    }

    @Test
    @DisplayName("全部通过 → PASS")
    void allRulesPassed() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(result("BLOCKING", 1), result("ERROR", 1)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.PASS);
    }

    @Test
    @DisplayName("BLOCKING 的 passed 为 NULL（未判定）按未通过处理 → FAIL")
    void nullPassedOnBlockingIsTreatedAsFailed() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(result("BLOCKING", null)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.FAIL);
    }

    @Test
    @DisplayName("该 run 没有任何质量结果 → UNKNOWN（不臆造为 PASS）")
    void missingResultsAreUnknown() {
        when(qualityMapper.selectList(any())).thenReturn(List.of());
        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.UNKNOWN);

        when(qualityMapper.selectList(any())).thenReturn(null);
        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.UNKNOWN);
    }

    @Test
    @DisplayName("没有 pipeline_run_id（快照未关联流水线）→ UNKNOWN，且不查库")
    void nullRunIdIsUnknown() {
        assertThat(gate.statusForRun(null)).isEqualTo(MetricQualityGate.UNKNOWN);
    }

    private static DataQualityResult result(String severity, Integer passed) {
        DataQualityResult entity = new DataQualityResult();
        entity.setRunId(24L);
        entity.setSeverity(severity);
        entity.setPassed(passed);
        return entity;
    }
}
