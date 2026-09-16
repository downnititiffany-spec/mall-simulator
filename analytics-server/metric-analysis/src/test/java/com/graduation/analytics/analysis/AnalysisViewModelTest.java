package com.graduation.analytics.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R7-4 信封结构 L0 单测（不连库）：验证契约 §2 的 9 个字段（v1.2 起含 `source`）与空信封语义。
 */
class AnalysisViewModelTest {

    @Test
    @DisplayName("empty()：无快照时字段取 null/UNKNOWN，data 是空对象且带警告")
    void emptyEnvelopeCarriesWarningAndNullMetadata() {
        AnalysisViewModel<Map<String, Object>> model =
                AnalysisViewModel.empty(Map.of("from", "2026-09-01"), List.of(AnalysisViewModel.WARN_NO_ACTIVE_SNAPSHOT));

        assertThat(model.snapshotId()).isNull();
        assertThat(model.source()).isNull();
        assertThat(model.businessTime()).isNull();
        assertThat(model.dataUpdatedAt()).isNull();
        assertThat(model.definitionVersion()).isNull();
        assertThat(model.qualityStatus()).isEqualTo("UNKNOWN");
        assertThat(model.filters()).containsEntry("from", "2026-09-01");
        assertThat(model.warnings()).containsExactly("NO_ACTIVE_SNAPSHOT");
        // data 必须是空对象而不是 null：前端 empty 态判定靠"data 里没有行"（契约 §4）
        assertThat(model.data()).isNotNull().isEqualTo(Map.of());
    }

    @Test
    @DisplayName("of()：filters/warnings 为 null 时收敛为空集合，不出现 null 字段")
    void nullCollectionsAreNormalized() {
        AnalysisViewModel<String> model =
                AnalysisViewModel.of("S1", "spark-ads", null, null, "v2", "PASS", null, "data", null);

        assertThat(model.source()).isEqualTo("spark-ads");
        assertThat(model.filters()).isEmpty();
        assertThat(model.warnings()).isEmpty();
    }

    @Test
    @DisplayName("信封是不可变快照：入参集合后续修改不影响已构造的信封")
    void envelopeCopiesInputCollections() {
        Map<String, Object> filters = new HashMap<>();
        filters.put("snapshotId", "S1");
        List<String> warnings = new ArrayList<>();
        warnings.add("W1");

        AnalysisViewModel<String> model =
                AnalysisViewModel.of("S1", "spark-ads", "2026-09-01T00:00:00", "2026-09-01T00:00:00", "v2", "PASS",
                        filters, "data", warnings);
        filters.put("snapshotId", "S2");
        warnings.add("W2");

        assertThat(model.filters()).containsEntry("snapshotId", "S1");
        assertThat(model.warnings()).containsExactly("W1");
    }
}
