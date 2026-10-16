package com.graduation.analytics.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryWindowCoverageDateValidationTest {

    private static Map<String, Object> row(String dt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dt", dt);
        return row;
    }

    @Test
    @DisplayName("coveredDays 只计严格日期格式、有效日历值且位于生效闭区间内的去重日期")
    void countsOnlyValidDatesInsideEffectiveWindow() {
        QueryWindow window = QueryWindow.of(null,
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 17),
                List.of(
                        row("20260911"),             // 窗口起点，紧凑格式
                        row("2026-09-11"),           // 同一天，ISO 格式去重
                        row("2026-09-17"),           // 窗口终点
                        row("2026-09-10"),           // 窗口外下界
                        row("20260918"),             // 窗口外上界
                        row("2026-02-30"),           // ISO 外形正确但不是日历日期
                        row("20260931"),             // 紧凑格式外形正确但不是日历日期
                        row("2026-9-12"),            // 非严格 ISO 格式
                        row("2026/09/12"),           // 未支持格式
                        row("not-a-date")            // 非日期
                ));

        assertThat(window.coveredDays()).isEqualTo(2);
        assertThat(window.effectiveRangeText()).isEqualTo("2026-09-11 ~ 2026-09-17（覆盖 2/7 天）");
        assertThat(window.notice()).contains("只覆盖 2 天");
    }

    @Test
    @DisplayName("dt 列存在但日期全部非法或越界时覆盖为 0，而不是把非法文本计作一天")
    void invalidOrOutOfRangeDatesProduceZeroCoverage() {
        QueryWindow window = QueryWindow.of(null,
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 17),
                List.of(row("2026-02-30"), row("20260918"), row("2026-9-12")));

        assertThat(window.coveredDays()).isZero();
        assertThat(window.notice()).contains("结果为空").contains("不能据此判断趋势");
    }
}
