package com.graduation.generator.engine;

import com.graduation.generator.adapter.MallCapability;
import com.graduation.generator.contract.EventTypes;

import java.util.Map;

/**
 * 规范事件类型 → §4.1 商城操作 的**唯一对照表**（MALL_API 模式的枢纽）。
 *
 * <p><b>为什么要有这张表</b>：MALL_API 模式与文件模式读的是同一份生成计划（同一套场景逻辑、同一个 seed），
 * 区别只在"事件往哪儿落"。有了这张表，"计划里的第 N 条事件"到"商城的哪一次公开调用"是<b>数据</b>而不是
 * 散落的 {@code if}，运行报告、操作流水与验收都能引用同一份对照关系。</p>
 *
 * <p><b>{@code mallBacked=false} 的语义</b>：该事件类型在生成计划里存在，但参考商城的公开接口没有任何
 * 对应操作（改价、库存预留/释放/入库）。这类事件在 MALL_API 模式下**不写入规范事件流**，只在操作流水里
 * 留一行 {@code SKIPPED} 缺口——绝不为了凑数把它当成功写出去。落到指标里就是"这台商城确实没有这类动作"，
 * 而不是"生成器伪造了一堆动作"。</p>
 *
 * @param capability 该事件对应的能力位（能力不 {@code SUPPORTED} 时整类降级并记缺口）
 * @param operation  §4.1 的操作名（写进操作流水）
 * @param method     真实使用的 HTTP 方法
 * @param route      真实请求路径（仅用于流水与排障，不含 base_url 与凭据）
 * @param mallBacked 该事件在参考商城是否有对应的公开写操作
 */
public record MallDispatchPlan(MallCapability capability, String operation, String method, String route,
                               boolean mallBacked) {

    /** §4.1 操作名（与适配器 SPI 的方法名逐字一致） */
    public static final String OP_LIST_PRODUCTS = "listProducts";
    public static final String OP_CREATE_USER = "createSyntheticUser";
    public static final String OP_EMIT_BEHAVIOR = "emitBehavior";
    public static final String OP_CREATE_ORDER = "createOrder";
    public static final String OP_PAY = "pay";
    public static final String OP_CANCEL = "cancel";
    public static final String OP_REFUND = "refund";

    public static final String PRODUCTS_ROUTE = "/api/v1/mall/products";
    public static final String USERS_ROUTE = "/api/v1/mall/users";
    public static final String ORDERS_ROUTE = "/api/v1/mall/orders";

    private static final Map<String, MallDispatchPlan> BY_EVENT_TYPE = Map.ofEntries(
            Map.entry(EventTypes.USER_REGISTERED, new MallDispatchPlan(
                    MallCapability.USER, OP_CREATE_USER, "POST", USERS_ROUTE, true)),
            // 商品：参考商城的公开接口只能"读目录"，没有公开的建品/改价。计划里的商品池因此按外部目录
            // 里真实存在的商品逐一对齐（见 MallApiDispatchSink 的 productCatalog），plan 里的 product_created
            // 事件被改写为商城的真实商品快照——记的是"商城里确实有这件商品"，不是凭空造的商品。
            Map.entry(EventTypes.PRODUCT_CREATED, new MallDispatchPlan(
                    MallCapability.PRODUCT, OP_LIST_PRODUCTS, "GET", PRODUCTS_ROUTE, true)),
            Map.entry(EventTypes.PRODUCT_UPDATED, new MallDispatchPlan(
                    MallCapability.ADMIN, "updateProduct", "PATCH", "/api/v1/admin/products/{id}", false)),
            Map.entry(EventTypes.BEHAVIOR, new MallDispatchPlan(
                    MallCapability.BEHAVIOR, OP_EMIT_BEHAVIOR, "POST", "（config_json.behavior_path 声明）", true)),
            Map.entry(EventTypes.ORDER_CREATED, new MallDispatchPlan(
                    MallCapability.ORDER, OP_CREATE_ORDER, "POST", ORDERS_ROUTE, true)),
            Map.entry(EventTypes.ORDER_PAID, new MallDispatchPlan(
                    MallCapability.ORDER, OP_PAY, "POST", ORDERS_ROUTE + "/{orderId}/pay", true)),
            Map.entry(EventTypes.ORDER_CANCELLED, new MallDispatchPlan(
                    MallCapability.ORDER, OP_CANCEL, "POST", ORDERS_ROUTE + "/{orderId}/cancel", true)),
            Map.entry(EventTypes.REFUND_CREATED, new MallDispatchPlan(
                    MallCapability.REFUND, OP_REFUND, "POST", ORDERS_ROUTE + "/{orderId}/refunds", true)),
            Map.entry(EventTypes.REFUND_COMPLETED, new MallDispatchPlan(
                    MallCapability.REFUND, OP_REFUND, "POST", "/api/v1/mall/refunds/{refundId}/complete", true)),
            Map.entry(EventTypes.STOCK_RESERVED, new MallDispatchPlan(
                    MallCapability.ORDER, "reserveStock", "（无）", "（商城无公开库存预留接口）", false)),
            Map.entry(EventTypes.STOCK_RELEASED, new MallDispatchPlan(
                    MallCapability.ORDER, "releaseStock", "（无）", "（商城无公开库存释放接口）", false)),
            Map.entry(EventTypes.STOCK_CHANGED, new MallDispatchPlan(
                    MallCapability.ADMIN, "changeStock", "（无）", "（商城无公开入库接口）", false)));

    /** 查不到就抛：新增事件类型却忘了登记对照关系，必须在测试/首跑就炸，而不是静默丢事件 */
    public static MallDispatchPlan of(String eventType) {
        MallDispatchPlan plan = BY_EVENT_TYPE.get(eventType);
        if (plan == null) {
            throw new IllegalArgumentException("事件类型未登记 MALL_API 对照关系：" + eventType
                    + "（请更新 MallDispatchPlan，绝不静默丢弃事件）");
        }
        return plan;
    }

    public static Map<String, MallDispatchPlan> all() {
        return BY_EVENT_TYPE;
    }

    public boolean isMallBacked() {
        return mallBacked;
    }
}
