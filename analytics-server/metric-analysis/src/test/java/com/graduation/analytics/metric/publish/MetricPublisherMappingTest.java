package com.graduation.analytics.metric.publish;

import com.graduation.analytics.metric.entity.MetricValue;
import com.graduation.analytics.metric.publish.MetricPublisherPort.DefinitionRef;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-03 复购率发布映射（**不连库**的纯映射单测）。
 *
 * <p>为什么必须有这个测试：{@code buildCoreMetricValues} 此前只被 {@code MetricPublisherMySqlIT}
 * 间接覆盖（本机不跑的 IT），映射表漏一列/窗口声明写错都不会被任何本地门禁抓到 —— 而这两件事恰恰是本轮
 * S3-03 的核心交付（设计 §11.2 L433「必须声明观察期和变体」）。</p>
 *
 * <p>判据（对应用例名）：</p>
 * <ol>
 *   <li>{@code repeat_rate} 必须从 ADS 概览行映射成同名指标码（此前完全缺失 ⇒ 页面/发布通路不存在）；</li>
 *   <li>复购率是**窗口指标**，{@code metric_value.period} 必须声明观察期窗口（{@code window:起..止}），
 *       不得像单日指标那样写成 {@code day:}（否则 30 天窗口会被谎报成单日）；</li>
 *   <li>其余单日指标的 {@code period} 仍是 {@code day:<ISO 业务日>}（不得被本轮改动带偏）；</li>
 *   <li>窗口列缺失/为空的镜像行必须**退回 {@code day:} 而不是编造窗口**（失败保旧、宁缺勿造）。</li>
 * </ol>
 */
class MetricPublisherMappingTest {

    private static final String SID = "S20260901_24";
    private static final String DT = "20260901";

    /** 映射只读 request + rowsByTable，其余协作者可为空（不在本用例路径上） */
    private final MetricPublisher publisher = new MetricPublisher(null, null, null, null, null, null);

    @Test
    @DisplayName("repeat_rate 进 metric_value，且 period 声明观察期窗口而非 day:")
    void repeatRateIsMappedWithWindowPeriod() {
        Map<String, Object> overview = overviewRow();
        overview.put("repeat_rate", new BigDecimal("0.3333"));
        overview.put("repeat_period_start", "2026-08-31");
        overview.put("repeat_period_end", "2026-09-01");

        List<MetricValue> values = publisher.buildCoreMetricValues(request(), rows(overview));

        MetricValue repeat = value(values, "repeat_rate").orElseThrow(
                () -> new AssertionError("概览行有 repeat_rate 却没有映射成指标值，码=" + codes(values)));
        assertThat(repeat.getMetricValue()).isEqualByComparingTo("0.3333");
        assertThat(repeat.getPeriod()).isEqualTo("window:2026-08-31..2026-09-01");
        assertThat(repeat.getDefinitionVersion()).isEqualTo("v1");
    }

    @Test
    @DisplayName("单日指标的 period 仍是 day:<ISO 业务日>（窗口声明不得污染其它指标）")
    void dailyMetricsKeepDayPeriod() {
        Map<String, Object> overview = overviewRow();
        overview.put("repeat_rate", new BigDecimal("0.3333"));
        overview.put("repeat_period_start", "2026-08-31");
        overview.put("repeat_period_end", "2026-09-01");

        List<MetricValue> values = publisher.buildCoreMetricValues(request(), rows(overview));

        for (String code : List.of("pv", "gmv", "net_sale", "refund_rate", "full_refund_rate",
                "buy_rate", "cart_rate", "fav_cnt", "cart_add_cnt")) {
            assertThat(value(values, code).orElseThrow().getPeriod())
                    .as("指标 %s 的 period", code)
                    .isEqualTo("day:2026-09-01");
        }
    }

    @Test
    @DisplayName("cart_rate 从漏斗行 overall_cart_rate 映射成同名指标码（phase 3 S3-04）")
    void cartRateIsMappedFromFunnelRow() {
        List<MetricValue> values = publisher.buildCoreMetricValues(request(), rows(overviewRow()));

        MetricValue cart = value(values, "cart_rate").orElseThrow(
                () -> new AssertionError("漏斗行有 overall_cart_rate 却没有映射成 cart_rate，码=" + codes(values)));
        assertThat(cart.getMetricValue()).isEqualByComparingTo("0.6000");
        assertThat(cart.getPeriod()).isEqualTo("day:2026-09-01");
        assertThat(cart.getDefinitionVersion()).isEqualTo("v1");
    }

    @Test
    @DisplayName("fav_cnt / cart_add_cnt 从概览行映射成同名指标码（phase 3 S3-08，次数口径）")
    void favAndCartCountAreMappedFromOverviewRow() {
        Map<String, Object> overview = overviewRow();
        overview.put("fav_cnt", 3L);
        overview.put("cart_add_cnt", 5L);

        List<MetricValue> values = publisher.buildCoreMetricValues(request(), rows(overview));

        MetricValue fav = value(values, "fav_cnt").orElseThrow(
                () -> new AssertionError("概览行有 fav_cnt 却没有映射成指标值，码=" + codes(values)));
        MetricValue cart = value(values, "cart_add_cnt").orElseThrow(
                () -> new AssertionError("概览行有 cart_add_cnt 却没有映射成指标值，码=" + codes(values)));
        assertThat(fav.getMetricValue()).isEqualByComparingTo("3");
        assertThat(cart.getMetricValue()).isEqualByComparingTo("5");
        // 次数是单日指标：period 必须是 day:<ISO 业务日>（不得谎报窗口、不得写成人数）
        assertThat(fav.getPeriod()).isEqualTo("day:2026-09-01");
        assertThat(cart.getPeriod()).isEqualTo("day:2026-09-01");
        assertThat(fav.getDefinitionVersion()).isEqualTo("v1");
        assertThat(cart.getDefinitionVersion()).isEqualTo("v1");
    }

    @Test
    @DisplayName("overall_cart_rate 为 NULL（当日浏览 0）时跳过 cart_rate，但不带走 buy_rate")
    void nullCartRateIsSkippedWithoutAffectingBuyRate() {
        Map<String, Object> overview = overviewRow();
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        rows.put("ads_operation_overview_m", List.of(overview));
        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("stage", "pay");
        funnel.put("user_count", 0L);
        funnel.put("overall_buy_rate", null);
        funnel.put("overall_cart_rate", null);
        rows.put("ads_behavior_funnel_m", List.of(funnel));

        // 同表四行时，只要任一行非空即可映射；这里四行全空（浏览 0）⇒ 两个率都不写
        List<MetricValue> values = publisher.buildCoreMetricValues(request(), rows);
        assertThat(value(values, "cart_rate")).isEmpty();
        assertThat(value(values, "buy_rate")).isEmpty();
        assertThat(value(values, "gmv")).isPresent();

        // 反例对照：只有加购率为空时，购买率必须照常写入（一个空值不得带走另一个指标）
        funnel.put("overall_buy_rate", new BigDecimal("0.2000"));
        List<MetricValue> mixed = publisher.buildCoreMetricValues(request(), rows);
        assertThat(value(mixed, "cart_rate")).isEmpty();
        assertThat(value(mixed, "buy_rate")).isPresent();
    }

    @Test
    @DisplayName("观察期声明缺失时不编造窗口：退回 day:（且不阻断其它指标）")
    void missingWindowFallsBackToDayPeriod() {
        Map<String, Object> overview = overviewRow();
        overview.put("repeat_rate", new BigDecimal("0.3333"));

        List<MetricValue> values = publisher.buildCoreMetricValues(request(), rows(overview));

        assertThat(value(values, "repeat_rate").orElseThrow().getPeriod()).isEqualTo("day:2026-09-01");
        assertThat(value(values, "gmv")).isPresent();
    }

    @Test
    @DisplayName("repeat_rate 为 NULL（无买家）时不写 metric_value（列 NOT NULL，宁缺勿造）")
    void nullRepeatRateIsSkipped() {
        Map<String, Object> overview = overviewRow();
        overview.put("repeat_rate", null);

        List<MetricValue> values = publisher.buildCoreMetricValues(request(), rows(overview));

        assertThat(value(values, "repeat_rate")).isEmpty();
        assertThat(value(values, "gmv")).isPresent();
    }

    // ------------------------------------------------------------------ 夹具

    private Optional<MetricValue> value(List<MetricValue> values, String code) {
        return values.stream().filter(v -> code.equals(v.getMetricCode())).findFirst();
    }

    private List<String> codes(List<MetricValue> values) {
        List<String> codes = new ArrayList<>();
        values.forEach(v -> codes.add(v.getMetricCode()));
        return codes;
    }

    private PublishRequest request() {
        return new PublishRequest(1L, 7, SID, DT, "2026-09-01T00:00", 99L, Path.of("target/none"), dictionary());
    }

    private Map<String, DefinitionRef> dictionary() {
        Map<String, DefinitionRef> dict = new LinkedHashMap<>();
        dict.put("pv", new DefinitionRef("v1", "次"));
        dict.put("uv", new DefinitionRef("v1", "人"));
        dict.put("dau", new DefinitionRef("v1", "人"));
        dict.put("paid_order_cnt", new DefinitionRef("v1", "单"));
        dict.put("gmv", new DefinitionRef("v1", "元"));
        dict.put("net_sale", new DefinitionRef("v1", "元"));
        dict.put("avg_order_value", new DefinitionRef("v1", "元"));
        dict.put("refund_rate", new DefinitionRef("v2", ""));
        dict.put("full_refund_rate", new DefinitionRef("v1", ""));
        dict.put("repeat_rate", new DefinitionRef("v1", ""));
        dict.put("buy_rate", new DefinitionRef("v1", ""));
        dict.put("cart_rate", new DefinitionRef("v1", ""));
        // S3-08（字典 metric-dictionary.md:21/:22）：收藏/加购**次数**两枚码，源表 dwd_user_behavior_detail
        dict.put("fav_cnt", new DefinitionRef("v1", "次"));
        dict.put("cart_add_cnt", new DefinitionRef("v1", "次"));
        return dict;
    }

    private Map<String, List<Map<String, Object>>> rows(Map<String, Object> overview) {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        rows.put("ads_operation_overview_m", List.of(overview));
        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("stage", "pay");
        funnel.put("user_count", 3L);
        funnel.put("overall_buy_rate", new BigDecimal("1.0000"));
        funnel.put("overall_cart_rate", new BigDecimal("0.6000"));
        rows.put("ads_behavior_funnel_m", List.of(funnel));
        return rows;
    }

    private Map<String, Object> overviewRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pv", 7L);
        row.put("uv", 3L);
        row.put("dau", 3L);
        row.put("order_count", 5L);
        row.put("sale_amount", new BigDecimal("2042.00"));
        row.put("net_sale_amount", new BigDecimal("1493.00"));
        row.put("avg_order_value", new BigDecimal("408.40"));
        row.put("refund_rate", new BigDecimal("0.6000"));
        row.put("full_refund_rate", new BigDecimal("0.2000"));
        // S3-08：收藏/加购次数（次数口径；与人数口径 2/3 刻意不同，防「拿人数冒次数」）
        row.put("fav_cnt", 3L);
        row.put("cart_add_cnt", 5L);
        return row;
    }
}
