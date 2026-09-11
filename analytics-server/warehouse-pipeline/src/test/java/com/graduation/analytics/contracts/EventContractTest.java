package com.graduation.analytics.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.ingestion.EventContractValidator;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6-9（V2.0 §15.3）：统一事件契约测试。
 *
 * ①12 类事件全集唯一来源是 {@link EventContract}（生产编排不得再维护私有白名单）；
 * ②每一类都路由到 ODS 四主题之一，且与 scala 侧 {@code OdsLoadSql.eventTypeToTable} 逐项一致
 *   （跨语言契约锁：直接读取 scala 源文件比对；DEF-16 起定位走 {@code RepoRoot}，文件缺失即红）；
 * ③12 类事件在 {@link EventContractValidator} 下均通过（路由表与校验器同源）；
 * ④未知类型不得"悄悄跳过"：validator 明确给出未知类型违规，EventContract 返回 null 表名。
 */
class EventContractTest {

    /** 12 类事件的最小合法 payload（字段依 EventContractValidator.missingPayloadField） */
    private static final Map<String, String> VALID_PAYLOAD = Map.ofEntries(
            Map.entry(EventContract.USER_REGISTERED,
                    "{\"user_id\":\"u1\",\"age_group\":\"18-25\",\"member_level\":\"silver\"}"),
            Map.entry(EventContract.BEHAVIOR,
                    "{\"user_id\":\"u1\",\"product_id\":\"p1\",\"session_id\":\"s1\",\"behavior_type\":\"view\"}"),
            Map.entry(EventContract.PRODUCT_CREATED,
                    "{\"product_id\":\"p1\",\"product_name\":\"耳机\",\"category_id\":\"c1\",\"price\":\"99.00\"}"),
            Map.entry(EventContract.PRODUCT_UPDATED,
                    "{\"product_id\":\"p1\",\"product_name\":\"耳机\",\"category_id\":\"c1\",\"price\":\"89.00\"}"),
            Map.entry(EventContract.ORDER_CREATED,
                    "{\"order_id\":\"o1\",\"user_id\":\"u1\",\"items\":[],\"total_amount\":\"99.00\"}"),
            Map.entry(EventContract.ORDER_PAID,
                    "{\"order_id\":\"o1\",\"user_id\":\"u1\",\"payment_id\":\"pay1\",\"amount\":\"99.00\"}"),
            Map.entry(EventContract.ORDER_CANCELLED,
                    "{\"order_id\":\"o1\",\"user_id\":\"u1\",\"reason\":\"timeout\"}"),
            Map.entry(EventContract.REFUND_CREATED,
                    "{\"refund_id\":\"r1\",\"order_id\":\"o1\",\"user_id\":\"u1\",\"amount\":\"10.00\"}"),
            Map.entry(EventContract.REFUND_COMPLETED,
                    "{\"refund_id\":\"r1\",\"order_id\":\"o1\",\"user_id\":\"u1\",\"amount\":\"10.00\"}"),
            Map.entry(EventContract.STOCK_RESERVED,
                    "{\"product_id\":\"p1\",\"reserved_qty\":\"1\"}"),
            Map.entry(EventContract.STOCK_RELEASED,
                    "{\"product_id\":\"p1\",\"reserved_qty\":\"1\"}"),
            Map.entry(EventContract.STOCK_CHANGED,
                    "{\"product_id\":\"p1\",\"available_qty\":\"10\"}"));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventContractValidator validator = new EventContractValidator(objectMapper);

    static Stream<String> allEventTypes() {
        return EventContract.EVENT_TYPES.stream().sorted();
    }

    @Test
    void contractHasExactlyTwelveEventTypes() {
        assertThat(EventContract.EVENT_TYPES).hasSize(12);
        assertThat(EventContract.ODS_TABLE_BY_TYPE).hasSize(12);
        // 12 类应覆盖全部四张 ODS 主题表
        assertThat(EventContract.ODS_TABLE_BY_TYPE.values()).containsOnly(
                EventContract.ODS_USER_EVENT, EventContract.ODS_PRODUCT_EVENT,
                EventContract.ODS_BEHAVIOR_EVENT, EventContract.ODS_TRADE_EVENT);
    }

    @ParameterizedTest
    @MethodSource("allEventTypes")
    void everyEventTypeRoutesToOdsTopic(String eventType) {
        assertThat(EventContract.isKnownType(eventType)).isTrue();
        assertThat(EventContract.odsTable(eventType))
                .isIn(EventContract.ODS_USER_EVENT, EventContract.ODS_PRODUCT_EVENT,
                        EventContract.ODS_BEHAVIOR_EVENT, EventContract.ODS_TRADE_EVENT);
    }

    /** 路由表的权威语义：用户→user，商品/库存→product，行为→behavior，订单/退款→trade */
    @Test
    void odsRoutingMatchesTopicSemantics() {
        assertThat(EventContract.odsTable(EventContract.USER_REGISTERED))
                .isEqualTo(EventContract.ODS_USER_EVENT);
        assertThat(EventContract.odsTable(EventContract.BEHAVIOR))
                .isEqualTo(EventContract.ODS_BEHAVIOR_EVENT);
        for (String t : List.of(EventContract.PRODUCT_CREATED, EventContract.PRODUCT_UPDATED,
                EventContract.STOCK_RESERVED, EventContract.STOCK_RELEASED, EventContract.STOCK_CHANGED)) {
            assertThat(EventContract.odsTable(t)).as(t).isEqualTo(EventContract.ODS_PRODUCT_EVENT);
        }
        for (String t : List.of(EventContract.ORDER_CREATED, EventContract.ORDER_PAID,
                EventContract.ORDER_CANCELLED, EventContract.REFUND_CREATED, EventContract.REFUND_COMPLETED)) {
            assertThat(EventContract.odsTable(t)).as(t).isEqualTo(EventContract.ODS_TRADE_EVENT);
        }
    }

    /** ①12 类事件在统一校验器下全部通过（不得因命名漂移误拒，R6-8a 教训） */
    @ParameterizedTest
    @MethodSource("allEventTypes")
    void validatorAcceptsEveryContractedType(String eventType) {
        String line = envelope("evt-" + eventType, eventType, VALID_PAYLOAD.get(eventType));
        assertThat(validator.check(line, 1)).as("type=" + eventType).isNull();
    }

    /** ④未知类型必须显式拒绝（不得悄悄跳过） */
    @Test
    void unknownTypeIsRejectedNotSkipped() {
        String line = envelope("evt-x", "user_created",
                "{\"user_id\":\"u1\",\"age_group\":\"18-25\",\"member_level\":\"silver\"}");
        var violation = validator.check(line, 1);
        assertThat(violation).isNotNull();
        assertThat(violation.reason()).contains("未知事件类型");

        assertThat(EventContract.isKnownType("user_created")).isFalse();
        assertThat(EventContract.odsTable("user_created")).isNull();
        assertThat(EventContract.UNKNOWN_EVENT_TYPE).isEqualTo("UNKNOWN_EVENT_TYPE");
    }

    /**
     * ②跨语言契约锁：Java 侧 ODS_TABLE_BY_TYPE 必须与 scala 侧
     * {@code OdsLoadSql.eventTypeToTable} 键集/取值完全一致（R6-8a 契约漂移的回归防线）。
     *
     * <p>DEF-16：此前用相对路径 {@code ../../spark-jobs/...} + {@code assumeTrue(存在)}，
     * 于是 {@code -DforkCount=0}（CWD＝仓库根）下这条锁**被静默跳过**——一个看起来全绿的假信号。
     * 现在定位收归 {@link RepoRoot}，文件必须存在（缺了就红）。</p>
     */
    @Test
    void odsRoutingMatchesScalaLoadSql() throws Exception {
        Path scala = RepoRoot.path("spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala");
        assertThat(Files.exists(scala))
                .as("跨语言契约锁的 scala 源必须存在（DEF-16：不得静默跳过）: %s", scala)
                .isTrue();
        String src = Files.readString(scala, StandardCharsets.UTF_8);

        Pattern entry = Pattern.compile("\"([a-z_]+)\"\\s*->\\s*\"(ods_[a-z_]+)\"");
        Matcher m = entry.matcher(src);
        Map<String, String> scalaMap = new LinkedHashMap<>();
        while (m.find()) {
            scalaMap.put(m.group(1), m.group(2));
        }
        assertThat(scalaMap).as("未能从 OdsLoadSql.scala 解析出 eventTypeToTable").hasSize(12);
        assertThat(scalaMap).isEqualTo(EventContract.ODS_TABLE_BY_TYPE);
    }

    private String envelope(String eventId, String eventType, String payloadJson) {
        return "{\"event_id\":\"" + eventId + "\",\"event_type\":\"" + eventType + "\","
                + "\"event_time\":\"2026-09-01T10:00:00+08:00\",\"ingest_time\":\"2026-09-01T10:00:01+08:00\","
                + "\"source_system\":\"mock-mall\",\"schema_version\":\"1.0\",\"trace_id\":\"t1\","
                + "\"payload\":" + payloadJson + "}";
    }
}
