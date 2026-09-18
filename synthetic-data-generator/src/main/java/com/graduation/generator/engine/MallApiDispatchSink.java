package com.graduation.generator.engine;

import com.graduation.generator.adapter.BehaviorCommand;
import com.graduation.generator.adapter.CancelCommand;
import com.graduation.generator.adapter.CapabilityVerdict;
import com.graduation.generator.adapter.ExternalOrder;
import com.graduation.generator.adapter.ExternalProduct;
import com.graduation.generator.adapter.ExternalRefund;
import com.graduation.generator.adapter.ExternalUser;
import com.graduation.generator.adapter.MallCapability;
import com.graduation.generator.adapter.MallOperationException;
import com.graduation.generator.adapter.MallTargetAdapter;
import com.graduation.generator.adapter.OrderCommand;
import com.graduation.generator.adapter.PayCommand;
import com.graduation.generator.adapter.RefundCommand;
import com.graduation.generator.adapter.TargetCapabilities;
import com.graduation.generator.adapter.TargetConfig;
import com.graduation.generator.adapter.TargetRoute;
import com.graduation.generator.adapter.UserCommand;
import com.graduation.generator.contract.CanonicalEvent;
import com.graduation.generator.contract.EventTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MALL_API 模式的派发器：把生成计划里的<b>一条</b>事件翻成<b>一次真实商城调用</b>，
 * 并回答"这条事件到底有没有在商城里发生过"。
 *
 * <p><b>为什么是"逐条派发"而不是"自己再实现一遍场景逻辑"</b>：两个模式必须读同一份生成计划
 * （§4.2 冻结版本 + seed + 时间窗）。若 MALL_API 另写一份场景/分布，两份实现迟早漂移，
 * "生成计划一致"就退化成一句口号。因此场景与分布仍然由 {@code FileModeGenerationEngine} 产生，
 * 本类只在落点上分流——计划一致是<b>构造上</b>保证的（对账见 {@code MallApiGenerationEngineTest}）。</p>
 *
 * <p><b>硬约束（§3.3 A）</b>：只调 {@link MallTargetAdapter} 上的 §4.1 七项，绝不直连商城库、
 * 绝不注入商城内部类、绝不绕过商城校验。本类里的"商城知识"只有操作名；写进流水的请求方法/路径
 * <b>全部来自调用方传入的路由表</b>（{@code adapter.operationRoutes(config)} 的产物，硬约束 6），
 * 因此本类里没有任何一家商城的路由字面量。</p>
 *
 * <p><b>返回值语义</b>：{@link #write(CanonicalEvent)} 返回 {@code true} 仅当这次调用真的成功了。
 * 返回 {@code false} 的三种情形——能力未 {@code SUPPORTED}、事件类型在商城无公开写操作、
 * 适配器调用失败且未开启 fail-fast——都不写入规范事件流，只记操作流水。
 * <b>绝不"跳过不发还照记成功"。</b></p>
 */
public final class MallApiDispatchSink {

    /** 无商城动作支撑时的缺口说明 */
    static final String GAP_NO_MALL_OPERATION = "商城无公开写接口，事件不写入规范流（只记缺口）";

    /**
     * 适配器<b>未声明</b>该操作路由时，流水里写的明确占位（硬约束 6）。
     *
     * <p>刻意不是 {@code null}（那会被读成"本地记账"），更不是参考商城的字面量：这两个值要让读流水的人
     * 一眼看出"这次运行的适配器没告诉我们它打哪儿"，而不是看到一个编出来的路径信以为真。</p>
     */
    static final String ROUTE_UNDECLARED_METHOD = "（未声明）";
    static final String ROUTE_UNDECLARED_PATH = "（适配器未声明路由）";

    private final MallTargetAdapter adapter;
    private final TargetConfig target;
    private final TargetCapabilities capabilities;
    private final OperationJournal journal;
    private final List<ExternalProduct> productCatalog;
    private final boolean failFast;

    /**
     * 本次运行的"操作名 → 真实路由"表：<b>由调用方（引擎）运行开始前解析一次</b>并传进来。
     *
     * <p>为什么不在本类里让适配器现算：① 每次派发都算一遍是重复劳动，且万一适配器实现有副作用
     * （计数/缓存），流水的可复现性就受影响；② 本类<b>不持有</b>造 {@link TargetConfig} 的权力——
     * 目标配置是运行级事实，只该有一个来源。空表（默认实现）读作"一条路由都没声明"。</p>
     */
    private final Map<String, TargetRoute> operationRoutes;

    private final Map<String, String> externalUserByCanonical = new LinkedHashMap<>();
    private final Map<String, String> externalOrderByCanonical = new LinkedHashMap<>();
    /** 规范订单 → 参考商城按真实目录价格计算出的成交总额。 */
    private final Map<String, BigDecimal> realOrderTotalByCanonical = new LinkedHashMap<>();
    /** 规范退款 → 商城真实退款 ID；与按订单复用的映射分开，避免拿 order_id 当 refund_id 的键。 */
    private final Map<String, String> externalRefundByCanonical = new LinkedHashMap<>();
    private final Map<String, String> externalRefundByOrder = new LinkedHashMap<>();
    private final Map<String, String> productRefByCanonical = new LinkedHashMap<>();
    private final Deque<ExternalProduct> availableProducts;
    private final Set<String> recordedGapEventTypes = new LinkedHashSet<>();
    private final List<String> notes = new ArrayList<>();

    private long succeeded;
    private long failed;
    private long skipped;

    /**
     * @param operationRoutes 本次运行的"操作名 → 真实路由"表（由引擎运行前解析 {@code adapter.operationRoutes}
     *                        一次得到，见本类字段说明）；传空表即"适配器一条路由都没声明"
     */
    public MallApiDispatchSink(MallTargetAdapter adapter,
                               TargetConfig target,
                               TargetCapabilities capabilities,
                               OperationJournal journal,
                               List<ExternalProduct> productCatalog,
                               boolean failFast,
                               Map<String, TargetRoute> operationRoutes) {
        this.adapter = adapter;
        this.target = target;
        this.capabilities = capabilities;
        this.journal = journal;
        this.productCatalog = List.copyOf(productCatalog);
        this.availableProducts = new ArrayDeque<>(this.productCatalog);
        this.failFast = failFast;
        this.operationRoutes = Map.copyOf(operationRoutes);
    }

    /**
     * 该操作在流水里该记的方法/路径：<b>只查适配器声明的表</b>，查不到就给明确占位。
     *
     * <p>这里刻意没有"如果没有就用参考商城那套"的分支——那正是本轮要消灭的回退（F-25 同族的证据真实性问题：
     * 流水的路由必须是这次真发出去的形状）。</p>
     */
    private TargetRoute routeOf(MallDispatchPlan plan) {
        return operationRoutes.getOrDefault(plan.operation(),
                new TargetRoute(ROUTE_UNDECLARED_METHOD, ROUTE_UNDECLARED_PATH));
    }

    /**
     * 派发一条事件。
     *
     * @return {@code true} 表示商城侧真的做成了这件事（调用方可以把它写入规范事件流）
     */
    public boolean write(CanonicalEvent event) {
        MallDispatchPlan plan = MallDispatchPlan.of(event.eventType());

        if (!MallDispatchPlan.OP_LIST_PRODUCTS.equals(plan.operation()) && !isSupported(plan.capability())) {
            skipped++;
            recordGap(event.eventType(), plan, "能力 " + plan.capability().key() + " 判定为 "
                    + verdict(plan.capability()) + "，不调用 " + plan.operation());
            return false;
        }
        if (!plan.isMallBacked()) {
            skipped++;
            recordGap(event.eventType(), plan, GAP_NO_MALL_OPERATION);
            return false;
        }
        TargetRoute route = routeOf(plan);

        try {
            boolean ok = dispatch(event, plan, route);
            if (ok) {
                succeeded++;
            } else {
                skipped++;
            }
            return ok;
        } catch (MallOperationException e) {
            failed++;
            journal.append(plan.operation(), true, route.method(), route.path(),
                    canonicalIdOf(event), null, OperationJournalEntry.STATUS_FAILED, e.getMessage(), false);
            if (failFast) {
                throw e;
            }
            notes.add("商城调用失败（未写入规范流）：" + plan.operation() + " → " + e.getMessage());
            return false;
        }
    }

    private boolean dispatch(CanonicalEvent event, MallDispatchPlan plan, TargetRoute route) {
        return switch (event.eventType()) {
            case EventTypes.USER_REGISTERED -> dispatchUser(event, plan, route);
            case EventTypes.PRODUCT_CREATED -> dispatchProduct(event, plan, route);
            case EventTypes.BEHAVIOR -> dispatchBehavior(event, plan, route);
            case EventTypes.ORDER_CREATED -> dispatchOrder(event, plan, route);
            case EventTypes.ORDER_PAID -> dispatchPay(event, plan, route);
            case EventTypes.ORDER_CANCELLED -> dispatchCancel(event, plan, route);
            case EventTypes.REFUND_CREATED -> dispatchRefundApply(event, plan, route);
            case EventTypes.REFUND_COMPLETED -> dispatchRefundComplete(event, plan, route);
            default -> throw new IllegalStateException("事件类型未实现派发：" + event.eventType());
        };
    }

    private boolean dispatchUser(CanonicalEvent event, MallDispatchPlan plan, TargetRoute route) {
        String canonicalId = canonicalIdOf(event);
        String existing = externalUserByCanonical.get(canonicalId);
        if (existing != null) {
            // 同一用户第二次注册：不重复建号，但要留一行"复用"，否则流水条数会对不上事件条数。
            // 这行是本地记账（没发请求）：D12 起显式标注，免得它被算成一次真实调用。
            journal.appendLocal(plan.operation(), canonicalId, existing, "复用已创建用户");
            return true;
        }
        ExternalUser user = adapter.createSyntheticUser(target,
                new UserCommand(text(event, "age_group"), text(event, "city_level"), text(event, "member_level")));
        externalUserByCanonical.put(canonicalId, user.userId());
        journal.append(plan.operation(), true, route.method(), route.path(), canonicalId, user.userId(),
                OperationJournalEntry.STATUS_OK, "member_level=" + user.memberLevel(), false);
        return true;
    }

    /**
     * 商品：计划里的商品池与商城目录<b>按位对齐</b>（第 N 件计划商品 → 目录第 N 件真实商品）。
     *
     * <p>参考商城的公开接口只能"读目录"，没有公开建品/改价（写商品走受保护的 admin 接口，
     * 本引擎不使用）。因此 {@code product_created} 记的是"商城里确实存在的这件商品"，
     * 价格/分类/名称全部来自商城应答（由引擎改写进事件），不是计划里的估价。</p>
     */
    private boolean dispatchProduct(CanonicalEvent event, MallDispatchPlan plan, TargetRoute route) {
        String canonicalId = canonicalIdOf(event);
        ExternalProduct product = availableProducts.pollFirst();
        if (product == null) {
            throw new MallOperationException(plan.operation(),
                    "商城目录商品不足：计划需要 " + productCatalog.size() + " 件，已用尽"
                            + "（参考商城公开接口不提供建品，B-04：MALL_API 只能使用目录里真实存在的商品）");
        }
        productRefByCanonical.put(canonicalId, product.productId());
        // 本地对齐记账：目录是预检那一次真实读取取回来的，这里不再发请求（D12 的原始症状就在这一行）
        journal.appendLocal(plan.operation(), canonicalId, product.productId(),
                "对齐商城目录商品：" + product.name());
        return true;
    }

    private boolean dispatchBehavior(CanonicalEvent event, MallDispatchPlan plan, TargetRoute route) {
        String externalUser = requireUser(event, plan.operation());
        adapter.emitBehavior(target, new BehaviorCommand(externalUser,
                externalProductOf(text(event, "product_id")), text(event, "session_id"),
                text(event, "behavior_type"), text(event, "channel")));
        journal.append(plan.operation(), true, route.method(), route.path(), canonicalIdOf(event), null,
                OperationJournalEntry.STATUS_OK, "behavior_type=" + text(event, "behavior_type"), false);
        return true;
    }

    private boolean dispatchOrder(CanonicalEvent event, MallDispatchPlan plan, TargetRoute route) {
        String canonicalId = canonicalIdOf(event);
        List<OrderCommand.Item> items = new ArrayList<>();
        BigDecimal realTotal = BigDecimal.ZERO;
        for (Object raw : list(event, "items")) {
            Map<?, ?> item = (Map<?, ?>) raw;
            String canonicalProduct = String.valueOf(item.get("product_id"));
            ExternalProduct realProduct = realProductOf(canonicalProduct);
            int quantity = ((Number) item.get("quantity")).intValue();
            items.add(new OrderCommand.Item(realProduct.productId(), quantity));
            realTotal = realTotal.add(realProduct.price().multiply(BigDecimal.valueOf(quantity)));
        }
        realTotal = money(realTotal);
        ExternalOrder order = adapter.createOrder(target,
                new OrderCommand(requireUser(event, plan.operation()), items));
        externalOrderByCanonical.put(canonicalId, order.orderId());
        realOrderTotalByCanonical.put(canonicalId, realTotal);
        journal.append(plan.operation(), true, route.method(), route.path(), canonicalId, order.orderId(),
                OperationJournalEntry.STATUS_OK,
                "件数=" + items.size() + "，商城成交额=" + realTotal.toPlainString(), false);
        return true;
    }

    private boolean dispatchPay(CanonicalEvent event, MallDispatchPlan plan, TargetRoute route) {
        String canonicalOrder = text(event, "order_id");
        ExternalOrder paid = adapter.pay(target, new PayCommand(requireOrder(canonicalOrder, plan.operation()),
                requireUser(event, plan.operation())));
        journal.append(plan.operation(), true, route.method(), route.path(), canonicalOrder, paid.orderId(),
                OperationJournalEntry.STATUS_OK, "status=" + describeMallStatus(paid.status()), false);
        return true;
    }

    private boolean dispatchCancel(CanonicalEvent event, MallDispatchPlan plan, TargetRoute route) {
        String canonicalOrder = text(event, "order_id");
        ExternalOrder cancelled = adapter.cancel(target, new CancelCommand(
                requireOrder(canonicalOrder, plan.operation()), requireUser(event, plan.operation()),
                text(event, "reason")));
        journal.append(plan.operation(), true, route.method(), route.path(), canonicalOrder, cancelled.orderId(),
                OperationJournalEntry.STATUS_OK, "status=" + describeMallStatus(cancelled.status()), false);
        return true;
    }

    private boolean dispatchRefundApply(CanonicalEvent event, MallDispatchPlan plan, TargetRoute route) {
        String canonicalOrder = text(event, "order_id");
        String externalOrder = requireOrder(canonicalOrder, plan.operation());
        BigDecimal realRefundAmount = requireOrderTotal(canonicalOrder, plan.operation());
        ExternalRefund refund = adapter.refund(target, new RefundCommand(externalOrder,
                requireUser(event, plan.operation()), realRefundAmount, text(event, "reason")));
        externalRefundByCanonical.put(text(event, "refund_id"), refund.refundId());
        externalRefundByOrder.put(canonicalOrder, refund.refundId());
        // 一行流水 = 两次 HTTP（参考商城的退款是"申请 + 完成"两步，适配器内一次走完）：
        // 因此"真实调用条数"与"HTTP 请求次数"不是同一个数，对账时按后者要再加上本条数。
        journal.append(plan.operation(), true, route.method(), route.path(), canonicalOrder, refund.refundId(),
                OperationJournalEntry.STATUS_OK, "status=" + describeMallStatus(refund.status()), false);
        return true;
    }

    /** 参考商城的退款两步（申请 + 完成）已在上一条事件里一次走完；这里不重复提交，只记"复用" */
    private boolean dispatchRefundComplete(CanonicalEvent event, MallDispatchPlan plan, TargetRoute route) {
        String canonicalOrder = text(event, "order_id");
        String refundId = externalRefundByOrder.get(canonicalOrder);
        if (refundId == null) {
            // 没找到已完成退款 = 这一条什么都没发生：按缺口记账（SKIPPED）。
            // 以前这里写的是 status=OK + "记缺口"，一行自相矛盾的字（D12 同族问题，顺手一并纠正）。
            recordGap(event.eventType(), plan, "refund_completed：未找到已完成的退款申请，无法复用退款单");
            return false;
        }
        journal.appendLocal(plan.operation(), canonicalOrder, refundId, "复用已完成退款");
        return true;
    }

    // ---------- 转写：规范 ID → 商城外部 ID ----------

    /**
     * 把事件载荷里的规范 ID 换成商城的真实 ID。
     *
     * <p>不换会怎样：产物里记的是 {@code U000001} 这类生成器内部序号，拿它去商城查任何东西都查不到，
     * "这份产物对应商城里的哪些数据"就断了。商品事件额外按商城真实商品事实重写
     * （见 {@link #dispatchProduct}）。</p>
     */
    public CanonicalEvent rewrite(CanonicalEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        String canonicalOrder = payload.get("order_id") == null ? null : String.valueOf(payload.get("order_id"));
        replace(payload, "user_id", externalUserByCanonical);
        replace(payload, "order_id", externalOrderByCanonical);
        replace(payload, "refund_id", externalRefundByCanonical);
        if (EventTypes.PRODUCT_CREATED.equals(event.eventType())) {
            rewriteProduct(payload);
        }
        if (EventTypes.ORDER_CREATED.equals(event.eventType())) {
            rewriteOrderCreated(payload, canonicalOrder);
        } else {
            rewriteNestedProductIds(payload);
        }
        if (canonicalOrder != null && (EventTypes.ORDER_PAID.equals(event.eventType())
                || EventTypes.REFUND_CREATED.equals(event.eventType())
                || EventTypes.REFUND_COMPLETED.equals(event.eventType()))) {
            payload.put("amount", moneyText(requireOrderTotal(canonicalOrder, "rewrite:" + event.eventType())));
        }
        return new CanonicalEvent(event.eventId(), event.eventType(), event.eventTime(), event.ingestTime(),
                event.sourceSystem(), event.schemaVersion(), event.traceId(), payload);
    }

    /**
     * MALL_API 的订单金额必须描述商城真正成交的事实，而不是文件模式计划里的估价/随机折扣。
     * 参考商城下单规则是 Σ(realCatalogPrice * quantity) 且 discount=0，因此按同一真实目录
     * 重写明细与总额。
     */
    private void rewriteOrderCreated(Map<String, Object> payload, String canonicalOrder) {
        Object items = payload.get("items");
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("order_created 缺 items，无法按商城真实价格重写：" + canonicalOrder);
        }
        List<Object> rewritten = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalStateException("order_created.items 含非对象项：" + item);
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            String canonicalProduct = String.valueOf(copy.get("product_id"));
            ExternalProduct realProduct = realProductOf(canonicalProduct);
            int quantity = ((Number) copy.get("quantity")).intValue();
            BigDecimal lineAmount = money(realProduct.price().multiply(BigDecimal.valueOf(quantity)));
            copy.put("product_id", realProduct.productId());
            copy.put("unit_price", moneyText(realProduct.price()));
            copy.put("discount", "0.00");
            copy.put("amount", moneyText(lineAmount));
            rewritten.add(copy);
        }
        payload.put("items", rewritten);
        payload.put("total_amount", moneyText(requireOrderTotal(canonicalOrder, "rewrite:order_created")));
    }

    /**
     * 改写嵌套在 {@code items[]} 里的商品 ID。
     *
     * <p>只改顶层 {@code product_id} 是不够的：{@code order_created} 的商品挂在
     * {@code items[].product_id} 上（见 {@code CanonicalPayloads.orderCreated}）。
     * 2026-09-11 的真实运行暴露过这一点——顶层商品被换成了商城 ID，明细里还留着 {@code P00014}，
     * 于是产物里同一张订单的主档与明细对不上商城。</p>
     *
     * <p>{@code items} 是新造的列表（原事件的 {@code List} 不动），改写只发生在副本上。</p>
     */
    private void rewriteNestedProductIds(Map<String, Object> payload) {
        Object items = payload.get("items");
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<Object> rewritten = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                rewritten.add(item);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            Object canonicalProduct = copy.get("product_id");
            if (canonicalProduct != null) {
                String externalId = productRefByCanonical.get(String.valueOf(canonicalProduct));
                if (externalId != null) {
                    copy.put("product_id", externalId);
                }
            }
            rewritten.add(copy);
        }
        payload.put("items", rewritten);
    }

    /**
     * 商品快照按商城应答改写：价格/分类/名称都以商城为准（计划里的估价只是生成用的中间量）。
     *
     * <p><b>状态字段（F-25 / 硬约束 3）</b>：{@code product_created.status} 在契约里是
     * <b>required + 枚举</b>（{@code on_sale/off_sale/pending}），因此这里绝不允许出现
     * "因为商城状态词读不懂就不写 {@code status}"的静默行为——那会产出一条缺必需字段的规范事件，
     * 下游按 schema 校验必然失败，而失败点离原因（商城词表）很远。</p>
     *
     * <p>两种 {@code status} 异常各有明确出口：</p>
     * <ol>
     *   <li>{@code null}（适配器明确表示"商城状态词映射不到规范词表"）：<b>响亮失败</b>。
     *       正常情况下不可能走到这里——预检已按"在售"过滤目录，{@code status} 为 null 的商品
     *       根本进不了可用目录，也就不会成为对齐目标；真走到了说明调用方绕过了预检，
     *       这属于生成器内部错误，必须立刻可见。</li>
     *   <li>非空但不在规范枚举里：同样响亮失败，避免把商城的词原样写成规范事实（D12 同族）。</li>
     * </ol>
     */
    private void rewriteProduct(Map<String, Object> payload) {
        String canonicalProduct = String.valueOf(payload.get("product_id"));
        String externalId = productRefByCanonical.get(canonicalProduct);
        if (externalId == null) {
            return;
        }
        productCatalog.stream().filter(p -> externalId.equals(p.productId())).findFirst().ifPresent(product -> {
            payload.put("product_id", product.productId());
            payload.put("product_name", product.name());
            if (product.categoryId() != null) {
                payload.put("category_id", product.categoryId());
            }
            if (product.price() != null) {
                payload.put("price", product.price().setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
            if (product.status() == null) {
                throw new IllegalStateException("不能产出缺少 status 的商品事件：" + product.productId()
                        + " 的规范状态为 null（适配器表示商城状态词映射不到规范词表）。"
                        + "这类商品本应在预检按「在售」过滤时就被排除并计入目录缺口；"
                        + "它出现在这里说明目录被绕过或状态词映射不完整，"
                        + "生成器宁可响亮失败，也不产出一条缺必需字段的规范事件");
            }
            if (!com.graduation.generator.contract.ContractEnums.PRODUCT_STATUS.contains(product.status())) {
                throw new IllegalStateException("商城状态词被原样写进了规范字段：" + product.productId()
                        + " 的 status=" + product.status() + " 不在规范词表内（"
                        + "状态词映射归适配器，见 F-25）；生成器不代替适配器做映射，也不写出违约的枚举值");
            }
            payload.put("status", product.status());
        });
    }

    /**
     * 流水明细里的"商城侧状态"渲染：商城没给状态字段时写清"未给"，而不是让 {@code null}
     * 变成字符串 {@code null}（那既不是商城原词，也读不出是"商城没给"还是"我们读漏了"）。
     *
     * <p><b>这里为什么不响亮失败</b>：订单与退款的状态只进这一行<b>说明文字</b>，没有任何消费方
     * 需要它非空（{@code ExternalOrder}/{@code ExternalRefund} 的状态判定方法全项目零调用点）；
     * 而这一行记的是"这次 HTTP 调用真的成功了"——商城有没有回状态字段与调用成不成功是两件事，
     * 因为后者把一次<b>真实发生过的商城写操作</b>记成失败，反而是错的账。
     * 真正<b>需要</b>非空状态的地方（商品的规范状态要写进规范事件）走的是响亮失败，见
     * {@link #rewriteProduct}。</p>
     */
    private static String describeMallStatus(String status) {
        return status == null || status.isBlank() ? "（商城未给状态字段）" : status;
    }

    private static void replace(Map<String, Object> payload, String key, Map<String, String> mapping) {
        Object value = payload.get(key);
        if (value == null) {
            return;
        }
        String external = mapping.get(String.valueOf(value));
        if (external != null) {
            payload.put(key, external);
        }
    }

    private void recordGap(String eventType, MallDispatchPlan plan, String detail) {
        journal.append(plan.operation(), false, null, null, null, null,
                OperationJournalEntry.STATUS_SKIPPED, eventType + "：" + detail, false);
        if (recordedGapEventTypes.add(eventType)) {
            notes.add("缺口 " + eventType + "（" + plan.capability().key() + "）：" + detail);
        }
    }

    // ---------- 结果 ----------

    public DispatchResult result() {
        Map<String, String> traceability = new LinkedHashMap<>();
        externalUserByCanonical.forEach((canonical, external) -> traceability.put("user:" + canonical, external));
        externalOrderByCanonical.forEach((canonical, external) -> traceability.put("order:" + canonical, external));
        externalRefundByCanonical.forEach((canonical, external) -> traceability.put("refund:" + canonical, external));
        productRefByCanonical.forEach((canonical, external) -> traceability.put("product:" + canonical, external));
        return new DispatchResult(succeeded, failed, skipped, Map.copyOf(traceability),
                List.copyOf(notes), journal.entries());
    }

    public Set<String> gapEventTypes() {
        return Set.copyOf(recordedGapEventTypes);
    }

    public List<ExternalProduct> productCatalog() {
        return productCatalog;
    }

    private boolean isSupported(MallCapability capability) {
        return capabilities != null && capabilities.isSupported(capability);
    }

    private CapabilityVerdict verdict(MallCapability capability) {
        return capabilities == null ? CapabilityVerdict.UNDETERMINED : capabilities.verdict(capability);
    }

    private String requireUser(CanonicalEvent event, String operation) {
        String canonical = text(event, "user_id");
        String external = externalUserByCanonical.get(canonical);
        if (external == null) {
            throw new MallOperationException(operation,
                    "用户 " + canonical + " 在商城侧还没有外部 ID（注册事件未成功派发），拒绝用假 ID 继续");
        }
        return external;
    }

    private String requireOrder(String canonicalOrder, String operation) {
        String external = externalOrderByCanonical.get(canonicalOrder);
        if (external == null) {
            throw new MallOperationException(operation,
                    "订单 " + canonicalOrder + " 在商城侧还没有外部 ID（下单事件未成功派发），拒绝用假 ID 继续");
        }
        return external;
    }

    private String externalProductOf(String canonicalProduct) {
        String external = productRefByCanonical.get(canonicalProduct);
        if (external == null) {
            throw new MallOperationException(MallDispatchPlan.OP_LIST_PRODUCTS,
                    "商品 " + canonicalProduct + " 未与商城目录对齐（商品事件未成功派发），拒绝用假 ID 继续");
        }
        return external;
    }

    private ExternalProduct realProductOf(String canonicalProduct) {
        String external = externalProductOf(canonicalProduct);
        ExternalProduct product = productCatalog.stream()
                .filter(p -> external.equals(p.productId()))
                .findFirst()
                .orElseThrow(() -> new MallOperationException(MallDispatchPlan.OP_LIST_PRODUCTS,
                        "商品 " + canonicalProduct + " 已映射为 " + external + "，但真实目录快照中不存在"));
        if (product.price() == null) {
            throw new MallOperationException(MallDispatchPlan.OP_LIST_PRODUCTS,
                    "商品 " + external + " 没有可读价格，无法计算商城真实订单金额");
        }
        return product;
    }

    private BigDecimal requireOrderTotal(String canonicalOrder, String operation) {
        BigDecimal total = realOrderTotalByCanonical.get(canonicalOrder);
        if (total == null) {
            throw new MallOperationException(operation,
                    "订单 " + canonicalOrder + " 没有商城真实成交额（下单未成功或金额事实丢失），拒绝继续");
        }
        return total;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String moneyText(BigDecimal value) {
        return money(value).toPlainString();
    }

    private static String text(CanonicalEvent event, String key) {
        Object value = event.payload().get(key);
        if (value == null) {
            throw new IllegalArgumentException("事件 " + event.eventType() + " 缺少载荷字段 " + key
                    + "（契约 required），无法派发到商城");
        }
        return String.valueOf(value);
    }

    private static BigDecimal amount(CanonicalEvent event, String key) {
        return new BigDecimal(text(event, key)).setScale(2, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(CanonicalEvent event, String key) {
        Object value = event.payload().get(key);
        if (!(value instanceof List<?> rows)) {
            throw new IllegalArgumentException("事件 " + event.eventType() + " 的载荷字段 " + key + " 必须是数组");
        }
        return (List<Object>) rows;
    }

    /**
     * 这条事件<b>自己是谁</b>的规范 ID —— 也就是要拿去映射商城外部 ID 的那个键。
     *
     * <p>不能按"载荷里第一个非空的 ID 字段"去猜：订单事件的载荷里同时有 {@code user_id} 与 {@code order_id}，
     * 猜错会把外部订单号记到用户键上，后面 {@code pay}/{@code cancel} 拿着 {@code O00000001} 查不到映射，
     * 就会以"下单事件未成功派发"整批失败——2026-09-11 的一次真实跑批就是这么炸出来的（先记 ID 再判定归属，
     * 是这里唯一正确的顺序）。</p>
     */
    private static String canonicalIdOf(CanonicalEvent event) {
        String key = switch (event.eventType()) {
            case EventTypes.USER_REGISTERED -> "user_id";
            case EventTypes.PRODUCT_CREATED, EventTypes.PRODUCT_UPDATED -> "product_id";
            case EventTypes.BEHAVIOR -> "session_id";
            case EventTypes.ORDER_CREATED, EventTypes.ORDER_PAID, EventTypes.ORDER_CANCELLED -> "order_id";
            case EventTypes.REFUND_CREATED, EventTypes.REFUND_COMPLETED -> "refund_id";
            case EventTypes.STOCK_RESERVED, EventTypes.STOCK_RELEASED, EventTypes.STOCK_CHANGED -> "product_id";
            default -> null;
        };
        if (key == null) {
            return null;
        }
        Object value = event.payload().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 一次 MALL_API 运行的派发结果。
     *
     * @param succeeded    商城侧真实做成的操作数（＝写入规范流的事件数）
     * @param failed       商城调用失败数
     * @param skipped      因能力缺口/无对应公开接口而未调用的条数
     * @param traceability 规范 ID → 商城外部 ID（{@code user:U000001} → 雪花 ID），"可追溯"的物证
     * @param notes        缺口说明（逐类一条）
     * @param entries      操作流水（落成 {@code OPERATION_JOURNAL} 制品）
     */
    public record DispatchResult(long succeeded,
                                 long failed,
                                 long skipped,
                                 Map<String, String> traceability,
                                 List<String> notes,
                                 List<OperationJournalEntry> entries) {
        public DispatchResult {
            traceability = Map.copyOf(traceability);
            notes = List.copyOf(notes);
            entries = List.copyOf(entries);
        }
    }
}
