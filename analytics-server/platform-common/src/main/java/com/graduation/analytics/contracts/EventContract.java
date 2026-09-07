package com.graduation.analytics.contracts;

/**
 * 事件契约常量（docs/contracts/event-contract.md，schema_version 1.0）。
 * 事件类型全集 12 类；未知类型/未知版本 → 隔离区，不进入 DWD。
 */
public final class EventContract {

    public static final String SCHEMA_VERSION = "1.0";
    public static final String SOURCE_SYSTEM = "mock-mall";
    /** 金额十进制字符串格式：^\d+(\.\d{1,2})?$ */
    public static final String AMOUNT_PATTERN = "^\\d+(\\.\\d{1,2})?$";

    // 事件类型（§2）
    public static final String USER_REGISTERED = "user_registered";
    public static final String PRODUCT_CREATED = "product_created";
    public static final String PRODUCT_UPDATED = "product_updated";
    public static final String BEHAVIOR = "behavior";
    public static final String ORDER_CREATED = "order_created";
    public static final String ORDER_PAID = "order_paid";
    public static final String ORDER_CANCELLED = "order_cancelled";
    public static final String REFUND_CREATED = "refund_created";
    public static final String REFUND_COMPLETED = "refund_completed";
    public static final String STOCK_RESERVED = "stock_reserved";
    public static final String STOCK_RELEASED = "stock_released";
    public static final String STOCK_CHANGED = "stock_changed";

    // 聚合类型（outbox.aggregate_type）
    public static final String AGG_USER = "user";
    public static final String AGG_PRODUCT = "product";
    public static final String AGG_ORDER = "order";
    public static final String AGG_PAYMENT = "payment";
    public static final String AGG_REFUND = "refund";
    public static final String AGG_INVENTORY = "inventory";

    private EventContract() {
    }
}