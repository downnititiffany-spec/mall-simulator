package com.graduation.generator.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 12 类事件的载荷构造器（契约 {@code canonical-event.v1.schema.json} 的 {@code $defs} 逐条对应）。
 *
 * <p>为什么集中在一处：载荷键名写错、金额退化成 {@code double}、时间漏时区，这三类错误都会产出
 * "平台能收下但按契约属脏"的数据（真实数据里已经发生过：{@code order_created.items} 被写成字符串、
 * {@code behavior.channel="web"}、{@code stock_changed.change_type="restock"}）。因此载荷只允许由这些工厂方法产生，
 * 键集与契约的 {@code required} 由 {@code GeneratorContractParityTest} 对账。</p>
 *
 * <p>各方法的参数顺序与契约字段顺序一致，返回 {@link LinkedHashMap} 以保证序列化顺序稳定（可复现）。</p>
 */
public final class CanonicalPayloads {

    private CanonicalPayloads() {
    }

    // ---------- 用户与商品 ----------

    public static Map<String, Object> userRegistered(String userId, String ageGroup, String cityLevel,
                                                     String memberLevel, String registerTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", userId);
        payload.put("age_group", ContractEnums.requireIn(ContractEnums.AGE_GROUP, ageGroup, "age_group"));
        payload.put("city_level", ContractEnums.requireIn(ContractEnums.CITY_LEVEL, cityLevel, "city_level"));
        payload.put("member_level", ContractEnums.requireIn(ContractEnums.MEMBER_LEVEL, memberLevel, "member_level"));
        payload.put("register_time", ContractFormat.requireTime(registerTime));
        return payload;
    }

    public static Map<String, Object> productCreated(String productId, String productName, String categoryId,
                                                     String brandId, String price, String cost, String status) {
        return productSnapshot(productId, productName, categoryId, brandId, price, cost, status);
    }

    public static Map<String, Object> productUpdated(String productId, String productName, String categoryId,
                                                     String brandId, String price, String cost, String status) {
        return productSnapshot(productId, productName, categoryId, brandId, price, cost, status);
    }

    private static Map<String, Object> productSnapshot(String productId, String productName, String categoryId,
                                                       String brandId, String price, String cost, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("product_id", productId);
        payload.put("product_name", productName);
        payload.put("category_id", categoryId);
        payload.put("brand_id", brandId);
        payload.put("price", ContractFormat.requireAmount(price));
        payload.put("cost", ContractFormat.requireAmount(cost));
        payload.put("status", ContractEnums.requireIn(ContractEnums.PRODUCT_STATUS, status, "status"));
        return payload;
    }

    // ---------- 行为与订单 ----------

    public static Map<String, Object> behavior(String userId, String productId, String sessionId,
                                               String behaviorType, String channel) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", userId);
        payload.put("product_id", productId);
        payload.put("session_id", sessionId);
        payload.put("behavior_type", ContractEnums.requireIn(ContractEnums.BEHAVIOR_TYPE, behaviorType, "behavior_type"));
        payload.put("channel", ContractEnums.requireIn(ContractEnums.CHANNEL, channel, "channel"));
        return payload;
    }

    /**
     * 订单创建（契约 §2.4）。
     *
     * <p>注意两个必须遵守的点：{@code items} 必须是**数组**而不是 JSON 字符串；{@code status} 契约固定为
     * {@code CREATED}；{@code total_amount} = Σ item.amount（跨字段一致性，运行期校验）。</p>
     */
    public static Map<String, Object> orderCreated(String orderId, String userId, List<OrderItem> items,
                                                   String totalAmount, String createdAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", orderId);
        payload.put("user_id", userId);
        payload.put("items", items.stream().map(OrderItem::toJson).toList());
        payload.put("total_amount", ContractFormat.requireAmount(totalAmount));
        payload.put("status", "CREATED");
        payload.put("created_at", ContractFormat.requireTime(createdAt));
        return payload;
    }

    public static Map<String, Object> orderPaid(String orderId, String userId, String paymentId,
                                                String amount, String paidAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", orderId);
        payload.put("user_id", userId);
        payload.put("payment_id", paymentId);
        payload.put("amount", ContractFormat.requireAmount(amount));
        payload.put("paid_at", ContractFormat.requireTime(paidAt));
        return payload;
    }

    public static Map<String, Object> orderCancelled(String orderId, String userId, String reason,
                                                     String cancelledAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", orderId);
        payload.put("user_id", userId);
        payload.put("reason", reason);
        payload.put("cancelled_at", ContractFormat.requireTime(cancelledAt));
        return payload;
    }

    public static Map<String, Object> refundCreated(String refundId, String orderId, String userId,
                                                    String amount, String reason, String createdAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refund_id", refundId);
        payload.put("order_id", orderId);
        payload.put("user_id", userId);
        payload.put("amount", ContractFormat.requireAmount(amount));
        payload.put("reason", reason);
        payload.put("created_at", ContractFormat.requireTime(createdAt));
        return payload;
    }

    public static Map<String, Object> refundCompleted(String refundId, String orderId, String userId,
                                                      String amount, String completedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refund_id", refundId);
        payload.put("order_id", orderId);
        payload.put("user_id", userId);
        payload.put("amount", ContractFormat.requireAmount(amount));
        payload.put("completed_at", ContractFormat.requireTime(completedAt));
        return payload;
    }

    // ---------- 库存 ----------

    public static Map<String, Object> stockReserved(String productId, String orderId, int quantity,
                                                    String reservedQty, String availableQty) {
        return stockMovement(EventTypes.STOCK_RESERVED, productId, orderId, quantity, reservedQty, availableQty);
    }

    public static Map<String, Object> stockReleased(String productId, String orderId, int quantity,
                                                    String reservedQty, String availableQty) {
        return stockMovement(EventTypes.STOCK_RELEASED, productId, orderId, quantity, reservedQty, availableQty);
    }

    private static Map<String, Object> stockMovement(String eventType, String productId, String orderId,
                                                     int quantity, String reservedQty, String availableQty) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("product_id", productId);
        if (orderId != null) {
            payload.put("order_id", orderId);
        }
        payload.put("quantity", quantity);
        payload.put("reserved_qty", ContractFormat.requireAmount(reservedQty));
        payload.put("available_qty", ContractFormat.requireAmount(availableQty));
        return payload;
    }

    /** 库存变动（契约 §2.10：{@code change_type} ∈ {inbound, outbound, adjust}，数量为正数） */
    public static Map<String, Object> stockChanged(String productId, String changeType, Number quantity,
                                                   String availableQty) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("product_id", productId);
        payload.put("change_type", ContractEnums.requireIn(ContractEnums.STOCK_CHANGE_TYPE, changeType, "change_type"));
        payload.put("quantity", quantity);
        payload.put("available_qty", ContractFormat.requireAmount(availableQty));
        return payload;
    }

    /** 订单项（契约 §2.4 子表：5 个字段全部必填，金额一律十进制字符串） */
    public record OrderItem(String productId, int quantity, String unitPrice, String discount, String amount) {

        public OrderItem {
            if (quantity < 1) {
                throw new IllegalArgumentException("订单项 quantity 必须 ≥ 1（契约 §2.4 L87）");
            }
            ContractFormat.requireAmount(unitPrice);
            ContractFormat.requireAmount(discount);
            ContractFormat.requireAmount(amount);
        }

        Map<String, Object> toJson() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("product_id", productId);
            item.put("quantity", quantity);
            item.put("unit_price", unitPrice);
            item.put("discount", discount);
            item.put("amount", amount);
            return item;
        }
    }
}
