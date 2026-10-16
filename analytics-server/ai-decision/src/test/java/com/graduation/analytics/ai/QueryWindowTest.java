package com.graduation.analytics.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-04：查询时间窗口的单一所有者。
 *
 * <p>2026-09-22 独立复评 QA-04：页面写「近30天」，SQL 实际只扫 7 天，解释口径又照抄请求标签，
 * 三处各说各话。修法是把「请求区间 / 实际生效区间 / 结果覆盖天数」收敛成一个结构化的
 * {@link QueryWindow}，由 SQL 校验、解释、证据、导出共用；文案从数据派生，不再有人手写。</p>
 */
class QueryWindowTest {

    private static Map<String, Object> day(String dt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dt", dt);
        row.put("sale_amount", "1.00");
        return row;
    }

    @Test
    @DisplayName("QA-04 请求「近30天」但 SQL 只扫 7 天、结果只覆盖 1 天：区间/天数/覆盖/提示都从数据派生")
    void requestedLabelAndEffectiveRangeAreBothExposed() {
        QueryWindow window = QueryWindow.of("近30天",
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 17), List.of(day("20260917")));

        assertThat(window.requested()).isEqualTo("近30天");
        assertThat(window.requestedDays()).isEqualTo(30);
        assertThat(window.from()).isEqualTo("2026-09-11");
        assertThat(window.to()).isEqualTo("2026-09-17");
        assertThat(window.days()).isEqualTo(7);
        assertThat(window.referenceBusinessDate()).isEqualTo("2026-09-17");
        assertThat(window.coveredDays()).isEqualTo(1);
        assertThat(window.notice())
                .contains("近30天")
                .contains("7 天")
                .contains("只覆盖 1 天")
                .contains("不能据此判断趋势");
        // 解释口径用的文本与窗口同源，且必须说清覆盖比例（1/7），不能只写「近30天」
        assertThat(window.effectiveRangeText()).isEqualTo("2026-09-11 ~ 2026-09-17（覆盖 1/7 天）");
    }

    @Test
    @DisplayName("QA-04 请求天数与生效天数一致时不产生口径矛盾提示，仍给出覆盖比例")
    void noContradictionNoticeWhenRequestedMatchesEffective() {
        QueryWindow window = QueryWindow.of("近7天",
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 17),
                List.of(day("20260911"), day("20260912"), day("20260913"), day("20260914"),
                        day("20260915"), day("20260916"), day("20260917")));

        assertThat(window.requestedDays()).isEqualTo(7);
        assertThat(window.coveredDays()).isEqualTo(7);
        assertThat(window.notice()).isNull();
        assertThat(window.effectiveRangeText()).isEqualTo("2026-09-11 ~ 2026-09-17（覆盖 7/7 天）");
    }

    @Test
    @DisplayName("QA-04 覆盖天数是去重计数：紧凑与 ISO 两种写法的同一天只算一天")
    void coveredDaysDeduplicatesMixedDateForms() {
        QueryWindow window = QueryWindow.of(null,
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 17),
                List.of(day("20260911"), day("2026-09-11"), day("20260912"), day(" 20260912 ")));

        assertThat(window.coveredDays()).isEqualTo(2);
        assertThat(window.requested()).isNull();
        assertThat(window.requestedDays()).isNull();
        assertThat(window.notice()).contains("只覆盖 2 天");
    }

    @Test
    @DisplayName("QA-04 结果为空：覆盖天数为 0 且提示「结果为空」，不伪装成完整覆盖")
    void emptyRowsMeanZeroCoverage() {
        QueryWindow window = QueryWindow.of("近30天",
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 17), List.of());

        assertThat(window.coveredDays()).isZero();
        assertThat(window.notice()).contains("结果为空").contains("不能据此判断趋势");
    }

    @Test
    @DisplayName("QA-04 结果没有 dt 列：覆盖天数未知（null），不臆造 100% 覆盖")
    void rowsWithoutDtColumnReportUnknownCoverage() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("gmv", "2042.0000"));

        QueryWindow window = QueryWindow.of("近7天",
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 17), rows);

        assertThat(window.coveredDays()).isNull();
        assertThat(window.notice()).isNull();
        assertThat(window.effectiveRangeText()).isEqualTo("2026-09-11 ~ 2026-09-17（7 天）");
    }

    @Test
    @DisplayName("QA-04 拿不到生效区间时（校验未过/无 WHERE 区间）：天数为 0，口径文本回落为请求标签")
    void windowWithoutEffectiveRangeFallsBackToRequested() {
        QueryWindow window = QueryWindow.of("近30天", null, null, LocalDate.of(2026, 9, 17), List.of());

        assertThat(window.from()).isNull();
        assertThat(window.to()).isNull();
        assertThat(window.days()).isZero();
        assertThat(window.effectiveRangeText()).isNull();
        assertThat(window.notice()).isNull();
    }

    @Test
    @DisplayName("QA-04 请求标签里的天数只按字面解析（今天=1），解析不出就是 null，不猜")
    void requestedDaysParsingIsLiteral() {
        LocalDate from = LocalDate.of(2026, 9, 17);
        assertThat(QueryWindow.of("今天", from, from, from, List.of()).requestedDays()).isEqualTo(1);
        assertThat(QueryWindow.of("近90天", from, from, from, List.of()).requestedDays()).isEqualTo(90);
        assertThat(QueryWindow.of("自定义区间", from, from, from, List.of()).requestedDays()).isNull();
        assertThat(QueryWindow.of("  ", from, from, from, List.of()).requested()).isNull();
    }
}
