package com.graduation.analytics.controller;

import com.graduation.analytics.analysis.AnalysisService;
import com.graduation.analytics.analysis.AnalysisService.ProductsData;
import com.graduation.analytics.analysis.AnalysisViewModel;
import com.graduation.analytics.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.3（S3-18）：`/api/v1/analysis/products` 分页参数**接线**测试。
 *
 * <p>为什么单独测接线：`page`/`size`/`sort`/`topN` 都是可空的原始/字符串参数，参数错位**编译器查不出来**
 * （`sort` 与 `topN` 类型不同，错位会编译不过；但 `page`/`size`/`topN` 之间只靠位置区分），
 * 而服务层测试（`AnalysisServiceTest`）直接调服务、绕过了控制器。本类只钉「按位置原样下传 +
 * 缺省不代解析」；分页/排序语义与非法参数（`PARAM_INVALID`）由服务层测试覆盖，
 * 不在两处重复断言（避免两个所有者）。</p>
 */
class AnalysisControllerTest {

    private final AnalysisService analysisService = mock(AnalysisService.class);
    private final AnalysisController controller = new AnalysisController(analysisService);

    @Test
    @DisplayName("products：page/size/sort/topN 按位置原样下传，不交换、不代解析")
    void productsForwardsPagingParamsInOrder() {
        AnalysisViewModel<ProductsData> model = AnalysisViewModel.of("S1", "spark-ads", "2026-09-01T00:00:00",
                "2026-09-01T00:00:00", "v2", "PASS", java.util.Map.of(),
                new ProductsData(List.of(), List.of(), 5, 2, 5, 15, true), List.of());
        when(analysisService.products("S1", 2, 5, "buy,desc", 3, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2)))
                .thenReturn(model);

        ApiResponse<AnalysisViewModel<ProductsData>> response = controller.products(
                2, 5, "buy,desc", 3, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), "S1");

        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.traceId()).isNotBlank();
        assertThat(response.data()).isSameAs(model);
        verify(analysisService)
                .products("S1", 2, 5, "buy,desc", 3, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2));
    }

    @Test
    @DisplayName("products：四个分页/排序参数全缺省时传 null（缺省解析的唯一所有者是服务层）")
    void productsPassesNullsWhenPagingParamsAbsent() {
        when(analysisService.products(null, null, null, null, null, null, null))
                .thenReturn(AnalysisViewModel.empty(java.util.Map.of(), List.of()));

        controller.products(null, null, null, null, null, null, null);

        verify(analysisService).products(null, null, null, null, null, null, null);
    }
}
