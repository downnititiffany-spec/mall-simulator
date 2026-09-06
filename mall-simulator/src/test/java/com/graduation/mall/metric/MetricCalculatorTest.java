package com.graduation.mall.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.mall.outbox.EventEnvelope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标计算器对账（§27.1）：黄金数据 → 计算结果 == 人工核算标准答案（指标字典 v1）。
 */
class MetricCalculatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static List<EventEnvelope> goldenEvents;
    private static JsonNode expected;

    @BeforeAll
    static void load() throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path eventsFile = repoRoot.resolve("tests/golden-dataset/events/golden-20260901.jsonl");
        Path expectedFile = repoRoot.resolve("tests/golden-dataset/expected/golden-20260901-expected.json");
        goldenEvents = new ArrayList<>();
        for (String line : Files.readAllLines(eventsFile, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                goldenEvents.add(EventEnvelope.fromJson(line, MAPPER));
            }
        }
        expected = MAPPER.readTree(expectedFile.toFile());
    }

    @Test
    @DisplayName("黄金数据指标全部等于标准答案")
    void goldenMetricsMatchExpected() {
        MetricCalculator.MetricDataset ds =
                new MetricCalculator().compute(goldenEvents, "2026-09-01");

        assertMoney("6", ds.metrics().get("pv"), "pv");
        assertMoney("3", ds.metrics().get("uv"), "uv");
        assertMoney("3", ds.metrics().get("dau"), "dau");
        assertMoney("1", ds.metrics().get("fav_cnt"), "fav_cnt");
        assertMoney("2", ds.metrics().get("cart_add_cnt"), "cart_add_cnt");
        assertMoney("2", ds.metrics().get("paid_order_cnt"), "paid_order_cnt");

        assertMoney("1275.00", ds.metrics().get("gmv"), "gmv");
        assertMoney("1225.00", ds.metrics().get("net_sale"), "net_sale");
        assertMoney("637.50", ds.metrics().get("avg_order_value"), "avg_order_value");
        assertMoney("0.50", ds.metrics().get("refund_rate"), "refund_rate");
        assertMoney("0.6667", ds.metrics().get("buy_rate"), "buy_rate");

        // 漏斗宽松口径
        assertEquals(3L, ds.funnel().get("view"));
        assertEquals(2L, ds.funnel().get("intent"));
        assertEquals(3L, ds.funnel().get("order"));
        assertEquals(2L, ds.funnel().get("pay"));
    }

    @Test
    @DisplayName("空事件集：无可计算指标不发布空值")
    void emptyEventsProducesMinimalMetrics() {
        MetricCalculator.MetricDataset ds =
                new MetricCalculator().compute(List.of(), "2026-09-02");
        assertEquals(0, new BigDecimal("0").compareTo(ds.metrics().get("pv")));
        assertTrue(!ds.metrics().containsKey("avg_order_value"), "分母 0 不发布客单价");
    }

    private static void assertMoney(String expect, BigDecimal actual, String label) {
        assertEquals(0, new BigDecimal(expect).compareTo(actual), label + " 与标准答案不一致");
    }
}