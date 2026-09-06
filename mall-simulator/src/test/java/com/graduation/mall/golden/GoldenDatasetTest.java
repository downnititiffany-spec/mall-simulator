package com.graduation.mall.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.mall.outbox.EventContract;
import com.graduation.mall.outbox.EventEnvelope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 黄金数据对账测试（§27.1/§28.4）：
 * - 逐行校验事件信封（必需字段、枚举、金额格式、版本与来源）；
 * - 按指标字典口径（docs/contracts/metric-dictionary.md v1）计算指标；
 * - 与人工核算的标准答案（tests/golden-dataset/expected）逐项一致。
 * 后续 Spark 清洗/聚合作业上线后，本测试作为黄金数据回归的第一道闸门。
 */
class GoldenDatasetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern AMOUNT = Pattern.compile(EventContract.AMOUNT_PATTERN);

    private static final Set<String> KNOWN_EVENT_TYPES = Set.of(
            EventContract.USER_REGISTERED, EventContract.PRODUCT_CREATED, EventContract.PRODUCT_UPDATED,
            EventContract.BEHAVIOR, EventContract.ORDER_CREATED, EventContract.ORDER_PAID,
            EventContract.ORDER_CANCELLED, EventContract.REFUND_CREATED, EventContract.REFUND_COMPLETED,
            EventContract.STOCK_RESERVED, EventContract.STOCK_RELEASED, EventContract.STOCK_CHANGED);

    private static final Set<String> BEHAVIOR_TYPES = Set.of("view", "favorite", "cart_add", "cart_remove", "search");

    private static List<EventEnvelope> events;
    private static JsonNode expected;

    @BeforeAll
    static void loadGolden() throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path eventsFile = repoRoot.resolve("tests/golden-dataset/events/golden-20260901.jsonl");
        Path expectedFile = repoRoot.resolve("tests/golden-dataset/expected/golden-20260901-expected.json");
        assertTrue(Files.exists(eventsFile), "黄金事件文件不存在: " + eventsFile.toAbsolutePath());
        assertTrue(Files.exists(expectedFile), "标准答案文件不存在: " + expectedFile.toAbsolutePath());

        List<EventEnvelope> list = new ArrayList<>();
        for (String line : Files.readAllLines(eventsFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            list.add(EventEnvelope.fromJson(line, MAPPER));
        }
        events = list;
        expected = MAPPER.readTree(expectedFile.toFile());
    }

    @Test
    @DisplayName("信封结构：30 条事件全部通过契约校验")
    void envelopeContractValid() {
        List<String> violations = new ArrayList<>();
        for (EventEnvelope e : events) {
            checkRequired(e, violations);
            if (!KNOWN_EVENT_TYPES.contains(e.eventType())) {
                violations.add(e.eventId() + ": 未知 event_type=" + e.eventType());
            }
            if (!EventContract.SCHEMA_VERSION.equals(e.schemaVersion())) {
                violations.add(e.eventId() + ": schema_version=" + e.schemaVersion());
            }
            if (!EventContract.SOURCE_SYSTEM.equals(e.sourceSystem())) {
                violations.add(e.eventId() + ": source_system=" + e.sourceSystem());
            }
            if (e.eventTime() == null || !e.eventTime().contains("+08:00")) {
                violations.add(e.eventId() + ": event_time 缺失或非 +08:00 时区");
            }
            if (e.ingestTime() == null || e.ingestTime().isBlank()) {
                violations.add(e.eventId() + ": ingest_time 缺失");
            }
            if (EventContract.BEHAVIOR.equals(e.eventType())) {
                Object bt = e.payload().get("behavior_type");
                if (!(bt instanceof String s) || !BEHAVIOR_TYPES.contains(s)) {
                    violations.add(e.eventId() + ": 非法 behavior_type=" + bt);
                }
            }
        }
        assertTrue(violations.isEmpty(), "信封校验失败:\n" + String.join("\n", violations));
        assertEquals(expected.get("scope").get("event_count").asInt(), events.size());
    }

    private void checkRequired(EventEnvelope e, List<String> violations) {
        for (String field : List.of("event_id", "event_type", "event_time", "ingest_time",
                "source_system", "schema_version", "trace_id")) {
            String value = switch (field) {
                case "event_id" -> e.eventId();
                case "event_type" -> e.eventType();
                case "event_time" -> e.eventTime();
                case "ingest_time" -> e.ingestTime();
                case "source_system" -> e.sourceSystem();
                case "schema_version" -> e.schemaVersion();
                default -> e.traceId();
            };
            if (value == null || value.isBlank()) {
                violations.add("event 缺失字段 " + field);
            }
        }
        if (e.payload() == null) {
            violations.add(e.eventId() + ": payload 缺失");
        }
    }

    @Test
    @DisplayName("金额格式：所有金额字段满足 ^\\d+(\\.\\d{1,2})?$")
    void moneyFormatValid() {
        List<String> bad = new ArrayList<>();
        for (EventEnvelope e : events) {
            for (Map.Entry<String, Object> entry : flatAmounts(e).entrySet()) {
                String key = e.eventId() + "." + entry.getKey() + "=" + entry.getValue();
                if (!(entry.getValue() instanceof String s) || !AMOUNT.matcher(s).matches()) {
                    bad.add(key);
                }
            }
        }
        assertTrue(bad.isEmpty(), "金额格式违规:\n" + String.join("\n", bad));
    }

    /** 提取事件中所有金额字段（payload 直接字段 + order items） */
    private Map<String, Object> flatAmounts(EventEnvelope e) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> en : e.payload().entrySet()) {
            String k = en.getKey();
            if (k.equals("price") || k.equals("cost") || k.equals("unit_price") || k.equals("discount")
                    || k.equals("amount") || k.equals("total_amount") || k.equals("available_qty")
                    || k.equals("reserved_qty")) {
                out.put(k, en.getValue());
            }
            if (k.equals("items") && en.getValue() instanceof List<?> items) {
                for (Object o : items) {
                    if (o instanceof Map<?, ?> m) {
                        out.put("item.unit_price", String.valueOf(m.get("unit_price")));
                        out.put("item.discount", String.valueOf(m.get("discount")));
                        out.put("item.amount", String.valueOf(m.get("amount")));
                    }
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("业务一致性：支付金额=订单总额；退款≤已付；金额=Σ(quantity×unit_price−discount)")
    void businessConsistency() {
        Map<String, BigDecimal> orderTotals = new HashMap<>();
        Map<String, List<Map<String, Object>>> orderItems = new HashMap<>();
        Map<String, BigDecimal> paidAmounts = new HashMap<>();
        Map<String, BigDecimal> refundCompleted = new HashMap<>();

        for (EventEnvelope e : events) {
            String orderId = str(e.payload().get("order_id"));
            switch (e.eventType()) {
                case EventContract.ORDER_CREATED -> {
                    orderTotals.put(orderId, dec(e.payload().get("total_amount")));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> items = (List<Map<String, Object>>) e.payload().get("items");
                    orderItems.put(orderId, items);
                }
                case EventContract.ORDER_PAID -> paidAmounts.put(orderId, dec(e.payload().get("amount")));
                case EventContract.REFUND_COMPLETED -> refundCompleted.put(orderId, dec(e.payload().get("amount")));
                default -> {
                }
            }
        }

        // 支付金额 = 订单总额
        for (Map.Entry<String, BigDecimal> pa : paidAmounts.entrySet()) {
            assertEquals(orderTotals.get(pa.getKey()), pa.getValue(),
                    "订单 " + pa.getKey() + " 支付金额必须等于订单总额");
        }
        // 订单项金额 = quantity×unit_price−discount；求和 = 总额
        for (Map.Entry<String, List<Map<String, Object>>> it : orderItems.entrySet()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> item : it.getValue()) {
                BigDecimal q = BigDecimal.valueOf(((Number) item.get("quantity")).longValue());
                BigDecimal unit = dec(item.get("unit_price"));
                BigDecimal disc = dec(item.get("discount"));
                BigDecimal amount = dec(item.get("amount"));
                assertEquals(0, q.multiply(unit).subtract(disc).compareTo(amount),
                        "订单 " + it.getKey() + " 明细金额公式不符");
                sum = sum.add(amount);
            }
            assertEquals(0, sum.compareTo(orderTotals.get(it.getKey())),
                    "订单 " + it.getKey() + " 明细求和必须等于总额");
        }
        // 退款 ≤ 已付
        for (Map.Entry<String, BigDecimal> rf : refundCompleted.entrySet()) {
            assertTrue(rf.getValue().compareTo(paidAmounts.getOrDefault(rf.getKey(), BigDecimal.ZERO)) <= 0,
                    "订单 " + rf.getKey() + " 退款超过已付");
        }
    }

    @Test
    @DisplayName("指标对账：按指标字典口径计算并与标准答案一致（黄金数据回归闸门）")
    void metricsMatchStandardAnswers() {
        int pv = 0, fav = 0, cartAdd = 0;
        Set<String> viewUsers = new HashSet<>();
        Set<String> dauUsers = new HashSet<>();
        Set<String> paidOrders = new HashSet<>();
        Set<String> buyUsers = new HashSet<>();
        Map<String, BigDecimal> paidByOrder = new HashMap<>();
        Map<String, BigDecimal> refundByOrder = new HashMap<>();

        for (EventEnvelope e : events) {
            String userId = str(e.payload().get("user_id"));
            switch (e.eventType()) {
                case EventContract.BEHAVIOR -> {
                    dauUsers.add(userId);
                    String bt = str(e.payload().get("behavior_type"));
                    if ("view".equals(bt)) {
                        pv++;
                        viewUsers.add(userId);
                    } else if ("favorite".equals(bt)) {
                        fav++;
                    } else if ("cart_add".equals(bt)) {
                        cartAdd++;
                    }
                }
                case EventContract.ORDER_PAID -> {
                    paidOrders.add(str(e.payload().get("order_id")));
                    buyUsers.add(userId);
                    paidByOrder.put(str(e.payload().get("order_id")), dec(e.payload().get("amount")));
                }
                case EventContract.REFUND_COMPLETED -> refundByOrder.put(
                        str(e.payload().get("order_id")), dec(e.payload().get("amount")));
                default -> {
                }
            }
        }

        BigDecimal gmv = paidByOrder.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refunds = refundByOrder.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netSale = gmv.subtract(refunds);
        BigDecimal avgOrder = paidOrders.isEmpty()
                ? null : gmv.divide(BigDecimal.valueOf(paidOrders.size()), 2, RoundingMode.HALF_UP);
        BigDecimal refundRate = paidOrders.isEmpty()
                ? null : BigDecimal.valueOf(refundByOrder.size())
                .divide(BigDecimal.valueOf(paidOrders.size()), 2, RoundingMode.HALF_UP);
        BigDecimal buyRate = viewUsers.isEmpty()
                ? null : BigDecimal.valueOf(buyUsers.size())
                .divide(BigDecimal.valueOf(viewUsers.size()), 4, RoundingMode.HALF_UP);
        // 有效复购率：观察期 1 天内支付订单 >=2 的用户数 / 支付用户数
        Map<String, Integer> paidCntByUser = new HashMap<>();
        for (EventEnvelope e : events) {
            if (EventContract.ORDER_PAID.equals(e.eventType())) {
                String u = str(e.payload().get("user_id"));
                paidCntByUser.merge(u, 1, Integer::sum);
            }
        }
        long repeatUsers = paidCntByUser.values().stream().filter(c -> c >= 2).count();

        assertEquals(expected.get("metrics").get("pv").asInt(), pv);
        assertEquals(expected.get("metrics").get("uv").asInt(), viewUsers.size());
        assertEquals(expected.get("metrics").get("dau").asInt(), dauUsers.size());
        assertEquals(expected.get("metrics").get("fav_cnt").asInt(), fav);
        assertEquals(expected.get("metrics").get("cart_add_cnt").asInt(), cartAdd);
        assertEquals(expected.get("metrics").get("paid_order_cnt").asInt(), paidOrders.size());
        assertEquals(expected.get("metrics").get("buy_users").asInt(), buyUsers.size());
        assertMoney(expected.get("metrics").get("gmv").asText(), gmv, "gmv");
        assertMoney(expected.get("metrics").get("net_sale").asText(), netSale, "net_sale");
        assertMoney(expected.get("metrics").get("avg_order_value").asText(), avgOrder, "avg_order_value");
        assertMoney(expected.get("metrics").get("refund_rate").asText(), refundRate, "refund_rate");
        assertMoney(expected.get("metrics").get("buy_rate").asText(), buyRate, "buy_rate");
        assertMoney(expected.get("metrics").get("repeat_rate").asText(),
                BigDecimal.valueOf(repeatUsers)
                        .divide(BigDecimal.valueOf(buyUsers.size()), 2, RoundingMode.HALF_UP), "repeat_rate");
    }

    private void assertMoney(String expectedText, BigDecimal actual, String label) {
        assertEquals(0, new BigDecimal(expectedText).compareTo(actual), label + " 与标准答案不一致");
    }

    private static BigDecimal dec(Object v) {
        return new BigDecimal(String.valueOf(v));
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}