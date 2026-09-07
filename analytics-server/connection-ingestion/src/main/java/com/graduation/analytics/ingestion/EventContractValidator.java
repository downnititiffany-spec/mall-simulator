package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.contracts.EventContract;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 事件契约逐行校验器（采集层第一道闸门，§5.2.1/§5.2.5）：
 * 信封必需字段、事件类型白名单、版本白名单、金额格式、行为枚举。
 * 纯函数：输入一行 JSON，返回违规描述或 null。
 */
@Component
public class EventContractValidator {

    private static final Pattern AMOUNT = Pattern.compile(EventContract.AMOUNT_PATTERN);
    private static final Set<String> KNOWN_TYPES = Set.of(
            EventContract.USER_REGISTERED, EventContract.PRODUCT_CREATED, EventContract.PRODUCT_UPDATED,
            EventContract.BEHAVIOR, EventContract.ORDER_CREATED, EventContract.ORDER_PAID,
            EventContract.ORDER_CANCELLED, EventContract.REFUND_CREATED, EventContract.REFUND_COMPLETED,
            EventContract.STOCK_RESERVED, EventContract.STOCK_RELEASED, EventContract.STOCK_CHANGED);
    private static final Set<String> KNOWN_VERSIONS = Set.of("1.0");
    private static final Set<String> BEHAVIOR_TYPES = Set.of("view", "favorite", "cart_add", "cart_remove", "search");

    public record Violation(int lineNo, String eventId, String schemaVersion, String reason) {
    }

    private final ObjectMapper objectMapper;

    public EventContractValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @return null=通过；否则违规详情
     */
    public Violation check(String jsonLine, int lineNo) {
        JsonNode node;
        try {
            node = objectMapper.readTree(jsonLine);
        } catch (JsonProcessingException e) {
            return new Violation(lineNo, null, null, "JSON 解析失败: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            return new Violation(lineNo, null, null, "非 JSON 对象");
        }
        String eventId = text(node, "event_id");
        String schemaVersion = text(node, "schema_version");
        String eventType = text(node, "event_type");

        for (String required : new String[]{"event_id", "event_type", "event_time", "ingest_time",
                "source_system", "schema_version", "trace_id"}) {
            if (isBlank(text(node, required))) {
                return new Violation(lineNo, eventId, schemaVersion, "缺失必要字段: " + required);
            }
        }
        if (node.get("payload") == null || !node.get("payload").isObject()) {
            return new Violation(lineNo, eventId, schemaVersion, "payload 缺失或非对象");
        }
        if (!KNOWN_TYPES.contains(eventType)) {
            return new Violation(lineNo, eventId, schemaVersion, "未知事件类型: " + eventType);
        }
        if (!KNOWN_VERSIONS.contains(schemaVersion)) {
            return new Violation(lineNo, eventId, schemaVersion, "未支持 schema_version: " + schemaVersion);
        }
        String missingPayload = missingPayloadField(eventType, node.get("payload"));
        if (missingPayload != null) {
            return new Violation(lineNo, eventId, schemaVersion, "payload 缺失必要字段: " + missingPayload);
        }
        if (EventContract.BEHAVIOR.equals(eventType)) {
            String bt = text(node.path("payload"), "behavior_type");
            if (!BEHAVIOR_TYPES.contains(bt)) {
                return new Violation(lineNo, eventId, schemaVersion, "非法 behavior_type: " + bt);
            }
        }
        String amountBad = findBadAmount(node.get("payload"));
        if (amountBad != null) {
            return new Violation(lineNo, eventId, schemaVersion, "金额格式违规: " + amountBad);
        }
        return null;
    }

    /** 各事件类型的 payload 必要字段（事件契约 §2） */
    private String missingPayloadField(String eventType, JsonNode payload) {
        String[] required = switch (eventType) {
            case EventContract.USER_REGISTERED -> new String[]{"user_id", "age_group", "member_level"};
            case EventContract.BEHAVIOR -> new String[]{"user_id", "product_id", "session_id", "behavior_type"};
            case EventContract.PRODUCT_CREATED, EventContract.PRODUCT_UPDATED ->
                    new String[]{"product_id", "product_name", "category_id", "price"};
            case EventContract.ORDER_CREATED -> new String[]{"order_id", "user_id", "items", "total_amount"};
            case EventContract.ORDER_PAID -> new String[]{"order_id", "user_id", "payment_id", "amount"};
            case EventContract.ORDER_CANCELLED -> new String[]{"order_id", "user_id", "reason"};
            case EventContract.REFUND_CREATED, EventContract.REFUND_COMPLETED ->
                    new String[]{"refund_id", "order_id", "user_id", "amount"};
            case EventContract.STOCK_RESERVED, EventContract.STOCK_RELEASED, EventContract.STOCK_CHANGED ->
                    new String[]{"product_id"};
            default -> new String[0];
        };
        for (String field : required) {
            JsonNode v = payload.get(field);
            if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
                return field;
            }
        }
        return null;
    }

    private String findBadAmount(JsonNode payload) {
        for (String key : new String[]{"price", "cost", "unit_price", "discount", "amount",
                "total_amount", "available_qty", "reserved_qty"}) {
            JsonNode v = payload.get(key);
            if (v != null && v.isTextual() && !AMOUNT.matcher(v.asText()).matches()) {
                return key + "=" + v.asText();
            }
            if (v != null && !v.isTextual() && !v.isNumber()) {
                return key + " 类型异常";
            }
        }
        JsonNode items = payload.get("items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                for (String key : new String[]{"unit_price", "discount", "amount"}) {
                    JsonNode v = item.get(key);
                    if (v != null && v.isTextual() && !AMOUNT.matcher(v.asText()).matches()) {
                        return "items[]. " + key + "=" + v.asText();
                    }
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}