package com.graduation.analytics.contracts;

import java.util.Map;
import java.util.Set;

/**
 * 事件契约常量（docs/contracts/event-contract.md，schema_version 1.0）。
 * 事件类型全集 12 类；未知类型/未知版本 → 隔离区，不进入 DWD。
 *
 * R6-9（V2.0 §15.3）：本类是"12 类契约 + ODS 主题路由"的**唯一 Java 侧来源**，
 * 与 scala 侧 {@code OdsLoadSql.eventTypeToTable} 键集/取值逐项一致（由测试锁定）。
 * 生产编排（PipelineService）不得再维护私有事件白名单。
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

    /** ODS 四主题表（§10.1/§12.2） */
    public static final String ODS_USER_EVENT = "ods_user_event";
    public static final String ODS_PRODUCT_EVENT = "ods_product_event";
    public static final String ODS_BEHAVIOR_EVENT = "ods_behavior_event";
    public static final String ODS_TRADE_EVENT = "ods_trade_event";

    /** 非法/未知事件类型的稳定原因码（§15.3 R6-9：不得悄悄跳过） */
    public static final String UNKNOWN_EVENT_TYPE = "UNKNOWN_EVENT_TYPE";

    /** 事件类型 → ODS 主题表（与 spark-jobs OdsLoadSql.eventTypeToTable 逐项一致） */
    public static final Map<String, String> ODS_TABLE_BY_TYPE = Map.ofEntries(
            Map.entry(USER_REGISTERED, ODS_USER_EVENT),
            Map.entry(PRODUCT_CREATED, ODS_PRODUCT_EVENT),
            Map.entry(PRODUCT_UPDATED, ODS_PRODUCT_EVENT),
            Map.entry(STOCK_RESERVED, ODS_PRODUCT_EVENT),
            Map.entry(STOCK_RELEASED, ODS_PRODUCT_EVENT),
            Map.entry(STOCK_CHANGED, ODS_PRODUCT_EVENT),
            Map.entry(BEHAVIOR, ODS_BEHAVIOR_EVENT),
            Map.entry(ORDER_CREATED, ODS_TRADE_EVENT),
            Map.entry(ORDER_PAID, ODS_TRADE_EVENT),
            Map.entry(ORDER_CANCELLED, ODS_TRADE_EVENT),
            Map.entry(REFUND_CREATED, ODS_TRADE_EVENT),
            Map.entry(REFUND_COMPLETED, ODS_TRADE_EVENT));

    /** 12 类事件全集（= ODS_TABLE_BY_TYPE 键集；顺序无关） */
    public static final Set<String> EVENT_TYPES = ODS_TABLE_BY_TYPE.keySet();

    /** 是否属于已发布契约的 12 类事件 */
    public static boolean isKnownType(String eventType) {
        return eventType != null && ODS_TABLE_BY_TYPE.containsKey(eventType);
    }

    /** 事件类型对应的 ODS 表；未知类型返回 null（调用方须按 UNKNOWN_EVENT_TYPE 处理） */
    public static String odsTable(String eventType) {
        return eventType == null ? null : ODS_TABLE_BY_TYPE.get(eventType);
    }

    private EventContract() {
    }
}