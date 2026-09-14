package com.graduation.generator.contract;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 规范事件类型常量（contract-specs/schemas/canonical-event.v1.schema.json 的 {@code event_type.enum}，12 类）。
 *
 * <p>生成器**不共享**分析平台的 {@code EventContract}（V2.1 §3.1 / §3.4-3：不得通过共享 Java 实体耦合），
 * 因此这 12 个常量在这里独立声明，并由 {@code GeneratorContractParityTest} 与契约文件对账——
 * 契约改了而这里没改，测试会红。</p>
 */
public final class EventTypes {

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

    /** 顺序与契约 enum 原文一致（对账测试逐项比较顺序） */
    public static final Set<String> ALL = java.util.Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.List.of(
                    USER_REGISTERED, PRODUCT_CREATED, PRODUCT_UPDATED, BEHAVIOR,
                    ORDER_CREATED, ORDER_PAID, ORDER_CANCELLED,
                    REFUND_CREATED, REFUND_COMPLETED,
                    STOCK_RESERVED, STOCK_RELEASED, STOCK_CHANGED)));

    private EventTypes() {
    }
}
