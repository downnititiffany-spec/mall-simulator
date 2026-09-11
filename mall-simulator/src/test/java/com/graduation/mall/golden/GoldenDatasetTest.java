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
import java.util.LinkedHashMap;
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

    /** DWD 行为事实的白名单（spark-jobs DwdSql：payload_behavior_type IN (...)），不在其中者 DWD 直接丢弃 */
    private static final Set<String> BEHAVIOR_TYPES = Set.of("view", "favorite", "cart_add", "cart_remove", "search");

    private static List<EventEnvelope> events;
    private static JsonNode expected;
    /** 夹具中有意注入、且 ODL 阶段确实会拒绝的脏行（R6-8b 扩充到 55 行时加入）：必须仍然脏，且恰好 3 行 */
    private static final List<String> rejectedReasons = new ArrayList<>();
    /** ODL 接受、但 behavior_type 不在 DWD 白名单的行为事件（夹具里 1 行 purchase），平台侧由 DWD 丢弃 */
    private static final List<String> nonDwdBehaviorTypes = new ArrayList<>();
    /** 夹具中重复出现的 event_id（DWD 去重淘汰，写入 dwd_reject_record 的 DUPLICATE_EVENT） */
    private static final List<String> duplicateEventIds = new ArrayList<>();
    /** ODL 接受的事件数（去重前，标准答案 scope.event_count） */
    private static int odlAcceptedCount;

    @BeforeAll
    static void loadGolden() throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path eventsFile = repoRoot.resolve("tests/golden-dataset/events/golden-20260901.jsonl");
        Path expectedFile = repoRoot.resolve("tests/golden-dataset/expected/golden-20260901-expected.json");
        assertTrue(Files.exists(eventsFile), "黄金事件文件不存在: " + eventsFile.toAbsolutePath());
        assertTrue(Files.exists(expectedFile), "标准答案文件不存在: " + expectedFile.toAbsolutePath());

        expected = MAPPER.readTree(expectedFile.toFile());
        int inputLines = 0;
        List<EventEnvelope> list = new ArrayList<>();
        for (String line : Files.readAllLines(eventsFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            inputLines++;
            EventEnvelope env;
            try {
                env = EventEnvelope.fromJson(line, MAPPER);
            } catch (Exception e) {
                rejectedReasons.add("JSON_PARSE");
                continue;
            }
            // 拒绝口径必须与真实 ODL 阶段一致（spark-jobs EventOdsLoadJob/OdsLoadSql 隔离条件）：
            // schema_version != '1.0' 或 event_id/event_type/event_time 缺失。**不**在此校验 behavior_type，
            // 因为平台是在 DWD 阶段按白名单丢弃未知行为类型（见下），若此处提前拒绝就会与真链行数不符。
            if (!EventContract.SCHEMA_VERSION.equals(env.schemaVersion())) {
                rejectedReasons.add("SCHEMA_VERSION");
                continue;
            }
            if (env.eventId() == null || env.eventId().isBlank()
                    || env.eventType() == null || env.eventType().isBlank()
                    || env.eventTime() == null || env.eventTime().isBlank()) {
                rejectedReasons.add("MISSING_REQUIRED");
                continue;
            }
            list.add(env);
            if (EventContract.BEHAVIOR.equals(env.eventType())) {
                Object bt = env.payload().get("behavior_type");
                if (!(bt instanceof String s) || !BEHAVIOR_TYPES.contains(s)) {
                    nonDwdBehaviorTypes.add(String.valueOf(bt));
                }
            }
        }
        // DWD 行为清洗按 event_id 去重（DwdSql.behaviorClean：ROW_NUMBER() OVER (PARTITION BY event_id ORDER BY ingest_time)=1，
        // 被淘汰行写入 dwd_reject_record 的 DUPLICATE_EVENT）。夹具里 golden-evt-008 有意出现两次，
        // 指标标准答案 pv=7 正是去重后的值，因此这里必须先去重再算指标。
        odlAcceptedCount = list.size();
        Map<String, EventEnvelope> dedup = new LinkedHashMap<>();
        for (EventEnvelope e : list) {
            if (dedup.putIfAbsent(e.eventId(), e) != null) {
                duplicateEventIds.add(e.eventId());
            }
        }
        events = new ArrayList<>(dedup.values());

        // 夹具规模与脏行必须与标准答案一致：夹具被"悄悄修干净"或脏行增多都要红
        assertEquals(expected.get("scope").get("input_lines").asInt(), inputLines, "黄金夹具行数变化");
        assertEquals(expected.get("scope").get("rejected").asInt(), rejectedReasons.size(),
                "被拒绝行数与标准答案不一致: " + rejectedReasons);
        assertEquals(expected.get("scope").get("event_count").asInt(), list.size(), "ODL 接受行数与标准答案不一致");
        assertEquals(List.of("golden-evt-008"), duplicateEventIds,
                "夹具中「DWD 去重淘汰」的重复 event_id 发生变化: " + duplicateEventIds);
    }

    @Test
    @DisplayName("脏数据行：恰好 3 行被 ODL 拒绝（解析失败/schema_version/缺必需字段）+ 1 行重复由 DWD 去重淘汰")
    void dirtyLinesAreRejected() {
        assertEquals(3, rejectedReasons.size(), "脏行数量变化: " + rejectedReasons);
        assertTrue(rejectedReasons.contains("JSON_PARSE"), "缺少无法解析为 JSON 的脏行: " + rejectedReasons);
        assertTrue(rejectedReasons.contains("SCHEMA_VERSION"), "缺少 schema_version 非 1.0 的脏行: " + rejectedReasons);
        assertTrue(rejectedReasons.contains("MISSING_REQUIRED"), "缺少缺必需字段的脏行: " + rejectedReasons);
        assertEquals(1, duplicateEventIds.size(), "重复 event_id 数量变化: " + duplicateEventIds);
    }

    @Test
    @DisplayName("非 DWD 白名单行为：夹具保留 1 行 purchase，由 DWD 阶段丢弃（ODL 不拒绝）")
    void unknownBehaviorTypeDroppedByDwd() {
        assertEquals(List.of("purchase"), nonDwdBehaviorTypes,
                "夹具中「ODL 接受但 DWD 丢弃」的行为类型发生变化: " + nonDwdBehaviorTypes);
    }

    @Test
    @DisplayName("信封结构：ODL 接受的 52 条事件全部通过契约校验")
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
        }
        assertTrue(violations.isEmpty(), "信封校验失败:\n" + String.join("\n", violations));
        assertEquals(expected.get("scope").get("event_count").asInt(), odlAcceptedCount, "ODL 接受行数与标准答案不一致");
        assertEquals(odlAcceptedCount - 1, events.size(), "去重后事件数应为 ODL 接受数 - 1（夹具 1 条重复 event_id）");
    }

    private static void checkRequired(EventEnvelope e, List<String> violations) {
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
            if (k.equals("items")) {
                for (Map<String, Object> m : itemList(en.getValue())) {
                    out.put("item.unit_price", String.valueOf(m.get("unit_price")));
                    out.put("item.discount", String.valueOf(m.get("discount")));
                    out.put("item.amount", String.valueOf(m.get("amount")));
                }
            }
        }
        return out;
    }

    /**
     * 取 order_created.items（两种编码都要能读）：
     * - 契约 docs/contracts/event-contract.md §3 规定 items 为**数组**，商城自己的 outbox 落盘就是数组
     *   （landing/accepted/13/2026090701.jsonl 实测 2181 条数组形态）；
     * - 但黄金夹具与 ODS 现有 schema（OdsLoadSql：payload.items STRING + from_json）用的是 **JSON 字符串**。
     * 两处口径不一致已作为真实缺陷登记（见 docs/remediation-status.md「订单项 items 编码分歧」），
     * 本测试只做读取兼容，不去掩盖分歧。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemList(Object raw) {
        if (raw instanceof List<?> l) {
            return (List<Map<String, Object>>) l;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return MAPPER.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
            } catch (Exception e) {
                fail("items JSON 字符串无法解析: " + s);
            }
        }
        return List.of();
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
                    orderItems.put(orderId, itemList(e.payload().get("items")));
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
                BigDecimal q = dec(item.get("quantity"));
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
                    String bt = str(e.payload().get("behavior_type"));
                    // 与 DWD 行为事实过滤一致：非白名单行为（夹具里 1 行 purchase）不进入行为类指标
                    if (!BEHAVIOR_TYPES.contains(bt)) {
                        break;
                    }
                    dauUsers.add(userId);
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
        // 有效复购率（口径见 docs/contracts/metric-dictionary.md：取消不计购买，**完全退款订单从有效复购率排除**）：
        // 若只按"支付订单数≥2"统计会得到 2/3=0.6667，与标准答案 0.3333 不符——因为其中一位复购用户有一单被全额退款。
        Map<String, BigDecimal> refundSumByOrder = new HashMap<>();
        for (EventEnvelope e : events) {
            if (EventContract.REFUND_COMPLETED.equals(e.eventType())) {
                String oid = str(e.payload().get("order_id"));
                refundSumByOrder.merge(oid, dec(e.payload().get("amount")), BigDecimal::add);
            }
        }
        Set<String> fullyRefundedOrders = new HashSet<>();
        for (Map.Entry<String, BigDecimal> p : paidByOrder.entrySet()) {
            if (refundSumByOrder.getOrDefault(p.getKey(), BigDecimal.ZERO).compareTo(p.getValue()) >= 0) {
                fullyRefundedOrders.add(p.getKey());
            }
        }
        Map<String, Integer> validPaidCntByUser = new HashMap<>();
        for (EventEnvelope e : events) {
            if (EventContract.ORDER_PAID.equals(e.eventType())
                    && !fullyRefundedOrders.contains(str(e.payload().get("order_id")))) {
                validPaidCntByUser.merge(str(e.payload().get("user_id")), 1, Integer::sum);
            }
        }
        long repeatUsers = validPaidCntByUser.values().stream().filter(c -> c >= 2).count();

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
                        .divide(BigDecimal.valueOf(buyUsers.size()), 4, RoundingMode.HALF_UP), "repeat_rate");
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