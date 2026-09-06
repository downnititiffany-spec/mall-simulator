package com.graduation.mall.outbox;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 12 类事件 payload 构造（事件契约 §2）。金额一律 BigDecimal → 十进制字符串；
 * 时间一律 ISO-8601 带时区字符串。禁止 null 值进入 payload。
 */
public final class EventPayloadFactory {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private EventPayloadFactory() {
    }

    private static String money(BigDecimal v) {
        if (v == null) {
            throw new IllegalArgumentException("金额不能为 null");
        }
        return v.setScale(2).toPlainString();
    }

    private static String time(OffsetDateTime t) {
        return ISO.format(t);
    }

    public static Map<String, Object> userRegistered(Long userId, String ageGroup, String cityLevel,
                                                    String memberLevel, OffsetDateTime registerTime) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("user_id", String.valueOf(userId));
        p.put("age_group", ageGroup);
        p.put("city_level", cityLevel);
        p.put("member_level", memberLevel);
        p.put("register_time", time(registerTime));
        return p;
    }

    public static Map<String, Object> productCreated(Long productId, String name, Long categoryId,
                                                     Long brandId, BigDecimal price, BigDecimal cost, String status) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("product_id", String.valueOf(productId));
        p.put("product_name", name);
        p.put("category_id", String.valueOf(categoryId));
        p.put("brand_id", String.valueOf(brandId));
        p.put("price", money(price));
        p.put("cost", money(cost));
        p.put("status", status);
        return p;
    }

    /** 商品更新事件沿用建档字段（§2.2，product_updated 结构相同） */
    public static Map<String, Object> productUpdated(Long productId, String name, Long categoryId,
                                                     Long brandId, BigDecimal price, BigDecimal cost, String status) {
        return productCreated(productId, name, categoryId, brandId, price, cost, status);
    }

    public static Map<String, Object> behavior(Long userId, Long productId, String sessionId,
                                               String behaviorType, String channel, OffsetDateTime eventTime) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("user_id", String.valueOf(userId));
        p.put("product_id", String.valueOf(productId));
        p.put("session_id", sessionId);
        p.put("behavior_type", behaviorType);
        p.put("channel", channel);
        p.put("event_time_utc", time(eventTime));
        return p;
    }

    public static Map<String, Object> orderCreated(Long orderId, Long userId, List<OrderItemVo> items,
                                                   BigDecimal totalAmount, OffsetDateTime createdAt) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("order_id", String.valueOf(orderId));
        p.put("user_id", String.valueOf(userId));
        List<Map<String, Object>> itemList = new ArrayList<>();
        for (OrderItemVo it : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("product_id", String.valueOf(it.productId()));
            m.put("quantity", it.quantity());
            m.put("unit_price", money(it.unitPrice()));
            m.put("discount", money(it.discount()));
            m.put("amount", money(it.amount()));
            itemList.add(m);
        }
        p.put("items", itemList);
        p.put("total_amount", money(totalAmount));
        p.put("status", "CREATED");
        p.put("created_at", time(createdAt));
        return p;
    }

    public static Map<String, Object> orderPaid(Long orderId, Long userId, Long paymentId,
                                                BigDecimal amount, OffsetDateTime paidAt) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("order_id", String.valueOf(orderId));
        p.put("user_id", String.valueOf(userId));
        p.put("payment_id", String.valueOf(paymentId));
        p.put("amount", money(amount));
        p.put("paid_at", time(paidAt));
        return p;
    }

    public static Map<String, Object> orderCancelled(Long orderId, Long userId, String reason,
                                                     OffsetDateTime cancelledAt) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("order_id", String.valueOf(orderId));
        p.put("user_id", String.valueOf(userId));
        p.put("reason", reason);
        p.put("cancelled_at", time(cancelledAt));
        return p;
    }

    public static Map<String, Object> refundCreated(Long refundId, Long orderId, Long userId,
                                                    BigDecimal amount, String reason, OffsetDateTime createdAt) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("refund_id", String.valueOf(refundId));
        p.put("order_id", String.valueOf(orderId));
        p.put("user_id", String.valueOf(userId));
        p.put("amount", money(amount));
        p.put("reason", reason);
        p.put("created_at", time(createdAt));
        return p;
    }

    public static Map<String, Object> refundCompleted(Long refundId, Long orderId, Long userId,
                                                      BigDecimal amount, OffsetDateTime completedAt) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("refund_id", String.valueOf(refundId));
        p.put("order_id", String.valueOf(orderId));
        p.put("user_id", String.valueOf(userId));
        p.put("amount", money(amount));
        p.put("completed_at", time(completedAt));
        return p;
    }

    public static Map<String, Object> stockReserved(Long productId, int quantity, Long orderId,
                                                    int reservedQty, int availableQty) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("product_id", String.valueOf(productId));
        p.put("quantity", quantity);
        p.put("order_id", String.valueOf(orderId));
        p.put("reserved_qty", String.valueOf(reservedQty));
        p.put("available_qty", String.valueOf(availableQty));
        return p;
    }

    public static Map<String, Object> stockReleased(Long productId, int quantity, Long orderId,
                                                    int reservedQty, int availableQty) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("product_id", String.valueOf(productId));
        p.put("quantity", quantity);
        p.put("order_id", String.valueOf(orderId));
        p.put("reserved_qty", String.valueOf(reservedQty));
        p.put("available_qty", String.valueOf(availableQty));
        return p;
    }

    public static Map<String, Object> stockChanged(Long productId, String changeType, int quantity,
                                                   int availableQty) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("product_id", String.valueOf(productId));
        p.put("change_type", changeType);
        p.put("quantity", quantity);
        p.put("available_qty", String.valueOf(availableQty));
        return p;
    }

    /** 订单项值对象（contract §2.4 items[]） */
    public record OrderItemVo(Long productId, int quantity, BigDecimal unitPrice,
                              BigDecimal discount, BigDecimal amount) {
    }
}