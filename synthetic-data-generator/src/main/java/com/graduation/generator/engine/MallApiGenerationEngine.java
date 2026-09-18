package com.graduation.generator.engine;

import com.graduation.generator.adapter.CapabilityVerdict;
import com.graduation.generator.adapter.ExternalProduct;
import com.graduation.generator.adapter.MallCapability;
import com.graduation.generator.adapter.MallOperationException;
import com.graduation.generator.adapter.MallTargetAdapter;
import com.graduation.generator.adapter.ProductPage;
import com.graduation.generator.adapter.ProductQuery;
import com.graduation.generator.adapter.TargetCapabilities;
import com.graduation.generator.adapter.TargetConfig;
import com.graduation.generator.adapter.TargetRoute;
import com.graduation.generator.contract.CanonicalEvent;
import com.graduation.generator.contract.EventSink;
import com.graduation.generator.contract.EventTypes;
import com.graduation.generator.core.GenerationResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * MALL_API 生成引擎（V2.1 §3.3 A）：读<b>同一份</b>生成计划，把事件驱动成对目标商城公开接口的真实调用。
 *
 * <h2>与文件模式的关系（共享什么、分歧什么）</h2>
 * <ul>
 *   <li><b>共享</b>：场景、分布、抽样权重、金额算法、事件时间推进，全部复用 {@link FileModeGenerationEngine}——
 *       本类<b>不重写任何场景逻辑</b>，只换落点。于是"两个模式读同一份计划"是构造上成立的，
 *       而不是靠两处实现互相约定。</li>
 *   <li><b>分歧</b>：① 每条事件在商城里要有对应动作（{@link MallApiDispatchSink}）；
 *       ② 商城公开接口不存在的动作（改价、库存预留/释放/入库）在<b>计划层</b>被摘掉，
 *       从不进入产物；③ {@code dirty_profile} 只对文件模式有意义，MALL_API 非 {@code none} 直接拒绝；
 *       ④ {@code success_count} 的语义是"商城侧真实做成的操作数"。</li>
 * </ul>
 *
 * <h2>能力门（硬约束）</h2>
 * <ul>
 *   <li>{@code PRODUCT}/{@code USER}/{@code ORDER} 任一不是 {@code SUPPORTED} ⇒ <b>响亮失败</b>，
 *       绝不"降级成文件模式"或"跳过这些事件还报成功"。</li>
 *   <li>{@code BEHAVIOR}/{@code REFUND} 不是 {@code SUPPORTED} ⇒ 不调用、不产物，
 *       逐类记缺口说明（B-04：参考商城当前无埋点接口，属商城侧依赖）。</li>
 *   <li>能力判定只用 {@code capabilities()} 的<b>声明</b>（不联网）；真实调用是否成功由每次调用的
 *       响应与操作流水证明。实测结果<b>不回写</b> {@code generator_target.capabilities}。</li>
 * </ul>
 *
 * <h2>可复现性的诚实边界</h2>
 * <p>MALL_API 不承诺"同 seed 逐字节可复现"：商城侧会分配雪花 ID、库存会真实扣减，第二次运行面对的是
 * 变了的世界。本模式保证的是<b>可追溯的场景分布</b>（同一 seed 得到同分布的规范事件序列，
 * 且每个规范 ID 都能对到商城外部 ID）——见 B-04。</p>
 */
public final class MallApiGenerationEngine implements GenerationEngine {

    /** 预检产物上限（够用即止，避免把商城目录整表拉进内存） */
    static final int CATALOG_PROBE_LIMIT = 500;

    private final FileModeGenerationEngine fileEngine = new FileModeGenerationEngine();

    /**
     * 刻意留成响亮失败：MALL_API 引擎没有目标适配器就没有商城可打，也就没有 MALL_API 运行。
     *
     * <p>为什么不实现成"降级成文件模式"：那样某人把它当文件模式引擎塞进 {@link GenerationEngine} 位置后，
     * 会静默跑成一次"打了商城的旗号、实际只写文件"的空运行。3 参重载由接口默认方法转到这里，
     * 因此两条入口的失败信息完全一致。</p>
     */
    @Override
    public EngineOutcome run(GenerationRequest request, EventSink sink, BooleanSupplier cancelled,
                             EventStatsRecorder eventStats) {
        throw new UnsupportedOperationException("MALL_API 引擎必须经 runForTarget(request, target, adapter, sink, cancelled) 调用："
                + "没有目标适配器就没有商城可打，也就没有 MALL_API 运行。"
                + "本方法刻意留成响亮失败，避免有人把它当文件模式引擎直接塞进 GenerationEngine 位置后静默跑成空运行");
    }

    /**
     * 执行一次 MALL_API 运行。
     *
     * @param request    生成请求（不含 runId 的字段即计划不可变部分；{@code dirtyProfile} 必须是 {@code none}）
     * @param target     目标配置（§4.2 {@code generator_target}）
     * @param adapter    由 {@code adapter_type} 解析出的适配器
     * @param sink       规范事件落点（MALL_API 运行里它只接收"商城侧真的做成了"的事件）
     * @param cancelled  取消信号
     */
    public MallRunOutcome runForTarget(GenerationRequest request, TargetConfig target, MallTargetAdapter adapter,
                                       EventSink sink, BooleanSupplier cancelled) {
        return runForTarget(request, target, adapter, sink, cancelled, null, new EventStatsRecorder());
    }

    /**
     * 执行一次 MALL_API 运行，复用调用方已经做过的预检。
     *
     * <p>为什么要能复用：运行服务在落库之前必须先预检（拒绝要发生在
     * {@code generation_run} 写成 RUNNING 之前），真正开跑时不该再读一次目录——
     * 2026-09-11 的真实运行里，"预检必须只读一次目录"这条断言就是被这里的第二次读打破的，
     * 而多出来的一次调用还会让"目录读了几次"这种可观测事实对不上账。</p>
     *
     * @param reusedPreflight 已经做过的预检；{@code null} 表示本次自己预检
     */
    public MallRunOutcome runForTarget(GenerationRequest request, TargetConfig target, MallTargetAdapter adapter,
                                       EventSink sink, BooleanSupplier cancelled, Preflight reusedPreflight) {
        return runForTarget(request, target, adapter, sink, cancelled, reusedPreflight, new EventStatsRecorder());
    }

    /**
     * 执行一次 MALL_API 运行，并把逐类事件账本交给调用方持有。
     *
     * <p><b>为什么账本要由调用方持有（D10）</b>：本方法在"商城真实拒单"时是
     * <b>事件已经写进规范流之后</b>才抛 {@link MallOperationException} 的，
     * 运行服务因此拿不到 {@code MallRunOutcome}；账本若留在本方法内部，这次运行的逐类分布就随异常一起丢了
     * （{@code event_stats: []}，失败样本缺分布）。交给调用方之后，失败路径读到的仍是
     * "抛异常之前真的进了规范流的事件"。</p>
     *
     * @param eventStats 逐类事件账本（由调用方创建并持有；本方法只往里记"已转写进规范流"的事件）
     */
    public MallRunOutcome runForTarget(GenerationRequest request, TargetConfig target, MallTargetAdapter adapter,
                                       EventSink sink, BooleanSupplier cancelled, Preflight reusedPreflight,
                                       EventStatsRecorder eventStats) {
        if (request.injectsDirtySamples()) {
            throw new IllegalArgumentException("MALL_API 模式不支持脏数据档位 " + request.dirtyProfile()
                    + "：异常样本要真实写进商城才能验证采集侧隔离，本模式不做（§3.3 B 的脏样本只属于文件模式）");
        }
        Preflight preflight = reusedPreflight != null ? reusedPreflight : preflight(request, target, adapter);
        OperationJournal journal = preflight.journal();

        GenerationRequest filtered = request.withEventFilter(
                mallBackedEventTypes(adapter.capabilities(target)));
        long expectedProducts = FileModeGenerationEngine.productsFor(request.eventCount());
        if (preflight.catalog().size() < expectedProducts) {
            throw new IllegalArgumentException(
                    "商城目录商品 %d 件 < 计划需要的商品池 %d 件（event_count=%d 派生）："
                            .formatted(preflight.catalog().size(), expectedProducts, request.eventCount())
                            + "参考商城公开接口不提供建品，MALL_API 只能用目录里真实存在的商品（B-04）");
        }

        // failFast=false 是刻意的：文件引擎会在每次 sink.write 外面 catch RuntimeException 并计入
        // failed_count（见 FileModeGenerationEngine 的写入段），所以 failFast=true 在这里<根本拦不住运行>，
        // 只会把一次商城失败变成"少写一条事件、继续往下跑"。2026-09-11 对着真机 8090 跑的那次就是这样：
        // 170 条 [createOrder] INSUFFICIENT_STOCK 被打成 failed_count=170，运行却报 SUCCESS。
        // 因此改成"跑完再据实收口"：让每条事件都拿到真实结论、流水记全，最后有任何失败就不许报成功。
        MallApiDispatchSink dispatch = new MallApiDispatchSink(adapter, target, adapter.capabilities(target),
                journal, preflight.catalog(), false, preflight.operationRoutes());
        ForwardingSink forwarding = new ForwardingSink(sink, dispatch, request.eventCount(), eventStats);

        // 刻意用 3 参重载：MALL_API 模式下"权威账本"是 ForwardingSink 那本（只记真的转写进规范流的事件），
        // 而文件引擎自己那本记的是"生成器尝试产出"的事件（含被商城拒绝、没进流的那些）。
        // 两者若共用一本账，每条转发成功的事件会被记两次。文件引擎那本在这里本就被丢弃（结果里用不到）。
        EngineOutcome fileOutcome = fileEngine.run(filtered, forwarding, cancelled);
        MallApiDispatchSink.DispatchResult dispatchResult = dispatch.result();
        if (dispatchResult.failed() > 0) {
            // 商城侧没有回滚，已成功的写操作是真实存在的——所以这里必须响亮失败并把话说明白，
            // 而不是留下一个"SUCCESS + failed_count=170"的记录让人以为跑成了。
            throw new MallOperationException("runForTarget", "运行中有 %d 次商城操作被真实拒绝（已写入规范流 %d 条）：%s"
                    .formatted(dispatchResult.failed(), forwarding.forwarded(), firstFailure(dispatchResult))
                    + "。商城侧不回滚，已成功的写操作真实存在；请清理后再重跑，或调小 event_count 让订单量落在库存可承受范围内");
        }
        if (!cancelled.getAsBoolean() && forwarding.forwarded() < request.eventCount()) {
            // 没有失败、也没被取消，却短于预算：只可能是能力门/白名单把事件挡在了落点之外，
            // 这属于"少写了事件"，同样不许安静收场。（取消是调用方明确要的半截产物，不算。）
            throw new IllegalStateException("MALL_API 运行只写入 %d 条事件 < 计划 event_count=%d：本次实际尝试 %d 次%s"
                    .formatted(forwarding.forwarded(), request.eventCount(), forwarding.attempted(),
                            forwarding.budgetExhausted() ? "（预算已被挡下的事件吃满）" : ""));
        }

        List<String> notes = new ArrayList<>(preflight.notes());
        notes.add("MALL_API 落点：%d 条事件写规范流；商城操作成功 %d 次、失败 %d 次、因能力缺口跳过 %d 次"
                .formatted(forwarding.forwarded(), dispatchResult.succeeded(), dispatchResult.failed(),
                        dispatchResult.skipped()));
        notes.add("能力判定取自适配器声明（不联网）；每次调用的真实结果见 OPERATION_JOURNAL 制品");
        notes.addAll(dispatchResult.notes());

        GenerationResult base = fileOutcome.result();
        Map<String, EventTypeStat> actualStats = eventStats.snapshot();
        long actualUsers = statCount(actualStats, EventTypes.USER_REGISTERED);
        long actualProducts = statCount(actualStats, EventTypes.PRODUCT_CREATED);
        long actualOrdersCreated = statCount(actualStats, EventTypes.ORDER_CREATED);
        long actualOrdersPaid = statCount(actualStats, EventTypes.ORDER_PAID);
        long actualOrdersCancelled = statCount(actualStats, EventTypes.ORDER_CANCELLED);
        long actualRefundsApplied = statCount(actualStats, EventTypes.REFUND_CREATED);
        long actualRefundsCompleted = statCount(actualStats, EventTypes.REFUND_COMPLETED);
        long actualOrdersCompleted = Math.max(0L, actualOrdersPaid - actualRefundsCompleted);
        BigDecimal actualGmv = statAmount(actualStats, EventTypes.ORDER_PAID);
        BigDecimal actualRefundAmount = statAmount(actualStats, EventTypes.REFUND_COMPLETED);
        BigDecimal actualNetSale = actualGmv.subtract(actualRefundAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal actualAvgOrderValue = actualOrdersPaid == 0
                ? BigDecimal.ZERO.setScale(2)
                : actualGmv.divide(BigDecimal.valueOf(actualOrdersPaid), 2, RoundingMode.HALF_UP);
        GenerationResult result = GenerationResult.builder()
                .configKey(base.configKey())
                .scenario(base.scenario())
                .expectedEffect(base.expectedEffect())
                .usersCreated(actualUsers)
                .productsCreated(actualProducts)
                .behaviorsByType(base.behaviorsByType())
                .ordersCreated(actualOrdersCreated)
                .ordersPaid(actualOrdersPaid)
                .ordersCancelled(actualOrdersCancelled)
                .ordersCompleted(actualOrdersCompleted)
                .refundsApplied(actualRefundsApplied)
                .refundsCompleted(actualRefundsCompleted)
                .gmv(actualGmv)
                .netSale(actualNetSale)
                .avgOrderValue(actualAvgOrderValue)
                .stockShortageHits(base.stockShortageHits())
                .totalEvents(forwarding.forwarded())
                .sampleEventIds(base.sampleEventIds())
                .dirtySamples(base.dirtySamples())
                .build();

        List<String> allNotes = new ArrayList<>(fileOutcome.notes());
        allNotes.addAll(notes);
        return new MallRunOutcome(
                new EngineOutcome(result, eventStats.snapshot(), forwarding.forwarded(),
                        dispatchResult.failed(), List.of(), allNotes),
                preflight, dispatchResult, forwarding.forwarded());
    }

    private static long statCount(Map<String, EventTypeStat> stats, String eventType) {
        EventTypeStat stat = stats.get(eventType);
        return stat == null ? 0L : stat.count();
    }

    private static BigDecimal statAmount(Map<String, EventTypeStat> stats, String eventType) {
        EventTypeStat stat = stats.get(eventType);
        return stat == null ? BigDecimal.ZERO.setScale(2)
                : stat.amount().setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 运行前预检：<b>在任何状态落库、任何商城调用之前</b>把"这台商城能不能承载这份计划"问清楚。
     *
     * <p>为何要提前：能力不足/凭据不可用若等到运行中才发现，{@code generation_run} 已经写成 RUNNING、
     * 商城侧可能已经建了半个用户，留下一个"失败的运行 + 半截数据"。预检把这些变成<b>一次响亮的拒绝</b>，
     * 且唯一一次真实调用是安全的只读 {@code listProducts}——它同时充当凭据校验（D-033：每一次调用都带凭据，
     * 凭据不对会当场 401，而不是运行到一半才炸）。</p>
     */
    public Preflight preflight(GenerationRequest request, TargetConfig target, MallTargetAdapter adapter) {
        TargetCapabilities capabilities = adapter.capabilities(target);
        // 硬约束 6：路由的所有者是适配器。这里解析一次、随 Preflight 带着走，
        // 流水与预检都用这一份，绝不内置任何商城字面量。
        Map<String, TargetRoute> operationRoutes = adapter.operationRoutes(target);
        OperationJournal journal = new OperationJournal();
        List<String> notes = new ArrayList<>();

        List<String> missing = new ArrayList<>();
        for (MallCapability mandatory : List.of(MallCapability.PRODUCT, MallCapability.USER, MallCapability.ORDER)) {
            if (!capabilities.isSupported(mandatory)) {
                missing.add(mandatory.key() + "=" + capabilities.verdict(mandatory));
            }
        }
        if (!missing.isEmpty()) {
            // 这里刻意不写"调哪个地址去实测"：探活的地址由适配器自己声明（operationRoutes），
            // 引擎里出现任何一家的具体路径都会让"换一家商城"退化成改引擎（硬约束 6）。
            throw new IllegalStateException("目标适配器 " + adapter.adapterType()
                    + " 的能力不满足 MALL_API 运行：缺少 " + String.join("、", missing)
                    + "。请先修复目标配置（base_url/凭据引用/config_json）并对该目标执行一次探活实测确认"
                    + "（探活地址以目标适配器自报的路由为准）；本引擎不会降级成文件模式，也不会跳过这些事件报成功"
                    // 点名凭据<b>引用名</b>（不是值）：能力判定 UNDETERMINED 绝大多数是"凭据取不到值"造成的
                    // （参考商城对公开接口全部要求 Bearer，D-033），不说清楚就只能看到一串 UNDETERMINED。
                    // 引用名不是秘密，令牌值永远不进日志、不进流水、不进异常信息。
                    + "。本次调用用的凭据引用是 " + describeCredentialRef(target)
                    + "（凭据取不到值时所有路由都判 UNDETERMINED；凭据值不回显）");
        }

        ProductPage page;
        try {
            page = adapter.listProducts(target, ProductQuery.firstPage(CATALOG_PROBE_LIMIT));
        } catch (RuntimeException e) {
            journal.append(MallDispatchPlan.OP_LIST_PRODUCTS, true,
                    routeMethod(operationRoutes, MallDispatchPlan.OP_LIST_PRODUCTS),
                    routePath(operationRoutes, MallDispatchPlan.OP_LIST_PRODUCTS),
                    null, null, OperationJournalEntry.STATUS_FAILED, e.getMessage(), false);
            // 点名凭据<b>引用名</b>（不是值）：排查时要知道去修哪一个引用，而不是只知道"401 了"。
            // 这里只说引用名，令牌值永远不进日志、不进流水、不进异常信息。
            throw new IllegalArgumentException("MALL_API 预检失败：读不到商城商品目录（"
                    + e.getMessage() + "）。本次调用用的凭据引用是 " + target.credentialRef()
                    + "（凭据不可用/商城不可达时不允许启动运行，也不会降级成文件模式）", e);
        }
        List<ExternalProduct> catalog = page.products().stream().filter(ExternalProduct::onSale).toList();
        // 目录缺口必须被点名（硬约束 16 / F-25 同族）：只报"目录 N 件、在售 M 件"会让 N-M 里混着
        // 三种完全不同的东西——"商城说它下架了"（正常）、"生成器读不懂商城的状态词/商城没给状态字段"
        // （缺口）、"报价读不懂而被适配器排除出目录"（缺口）。后两种都必须能看出件数与名单，
        // 否则商品只是无声消失，运维会以为是商城没货。
        String gaps = describeCatalogGaps(page.products(),
                page.unmappedStateWords(), page.stateFieldMissing(), page.priceUnreadable());
        journal.append(MallDispatchPlan.OP_LIST_PRODUCTS, true,
                routeMethod(operationRoutes, MallDispatchPlan.OP_LIST_PRODUCTS),
                routePath(operationRoutes, MallDispatchPlan.OP_LIST_PRODUCTS),
                null, null, OperationJournalEntry.STATUS_OK,
                "目录 %d 件，在售 %d 件（预检兼凭据校验）".formatted(page.total(), catalog.size()), false);
        if (gaps != null) {
            // 缺口单独占一行（SKIPPED，且不是真实调用）：这样"预检读了几次目录""在售几件""多少件有缺口"
            // 三件事在流水里各自可数，不会被合并成一句话而失去可核对性。三个通道并列写在同一行里，
            // 不挑一个报——只报状态词缺口正是 H2 的"有收集、无出口"。
            journal.append(MallDispatchPlan.OP_LIST_PRODUCTS, false, null, null, null, null,
                    OperationJournalEntry.STATUS_SKIPPED,
                    "目录缺口（三个通道并列）：" + gaps, false);
            // 这里只说两个通道的**共同后果**，不复述具体成因：成因与修法由 describeCatalogGaps 按
            // "哪个通道真有缺口"逐条写（写成一句无条件的"状态词缺口里的商品…"会让只有报价缺口的
            // 运行也读到"状态词缺口"，读报告的人分不清这次到底是哪种缺口）
            notes.add("目录缺口：" + gaps + "；缺口商品不会进入规范事件流，也绝不会被静默当成「在售」，"
                    + "更不会带着读不懂的金额进流水（状态词映射归适配器，见 F-25）");
        }
        if (catalog.isEmpty()) {
            throw new IllegalArgumentException("商城商品目录里没有在售商品，MALL_API 无法生成任何订单："
                    + "请先在商城侧准备商品（参考商城公开接口只读目录，B-04）");
        }
        if (capabilities.isSupported(MallCapability.BEHAVIOR)) {
            notes.add("行为埋点：能力判定 SUPPORTED，将经 config_json.behavior_path 真实调用");
        } else {
            notes.add("行为埋点：能力判定 " + capabilities.verdict(MallCapability.BEHAVIOR)
                    + "，behavior 事件不进入本模式产物（B-04：参考商城当前无该接口，属商城侧依赖）");
        }
        if (!capabilities.isSupported(MallCapability.REFUND)) {
            notes.add("退款：能力判定 " + capabilities.verdict(MallCapability.REFUND)
                    + "，退款事件不进入本模式产物");
        }
        return new Preflight(adapter.adapterType(), capabilities, catalog, List.copyOf(notes), journal,
                operationRoutes);
    }

    /**
     * 流水里该记的方法：<b>只查适配器声明的路由表</b>，未声明就给明确占位（硬约束 6）。
     *
     * <p>与 {@code MallApiDispatchSink#routeOf} 同口径、共用同一组占位常量——它们是"适配器没告诉我们"
     * 的同一个事实在两处的同一种写法，不是两套口径。</p>
     */
    static String routeMethod(Map<String, TargetRoute> operationRoutes, String operation) {
        return routeOf(operationRoutes, operation).method();
    }

    /** 流水里该记的路径（口径同 {@link #routeMethod}） */
    static String routePath(Map<String, TargetRoute> operationRoutes, String operation) {
        return routeOf(operationRoutes, operation).path();
    }

    private static TargetRoute routeOf(Map<String, TargetRoute> operationRoutes, String operation) {
        TargetRoute route = operationRoutes == null ? null : operationRoutes.get(operation);
        return route != null ? route
                : new TargetRoute(MallApiDispatchSink.ROUTE_UNDECLARED_METHOD, MallApiDispatchSink.ROUTE_UNDECLARED_PATH);
    }

    /**
     * 目录缺口的<b>三通道并列</b>说明：<b>(1)</b> 状态词缺口——件数 + 商城原词 + 未给状态字段的商品 ID；
     * <b>(2)</b> 报价缺口——报价读不懂、已被适配器排除出目录的商品 ID（清单按字典序，输出可复现）。
     *
     * <p><b>引擎对任何一家商城的词表零知识</b>：本方法只吃三个<b>通用</b>清单
     * （{@link ProductPage#unmappedStateWords()}、{@link ProductPage#stateFieldMissing()}、
     * {@link ProductPage#priceUnreadable()}），不认识也不去问"这家适配器属于哪个类型"。
     * 谁有词表、谁缺字段、谁的报价读不懂是<b>适配器在读目录时</b>的事，事实随 {@link ProductPage}
     * 一起返回；引擎只读页，不按适配器类型分支——这正是"映射与词表归适配器、计数与报缺口归引擎"的分工。</p>
     *
     * <p>状态缺口的判据仍然是 {@link ExternalProduct#status()} 为 {@code null}（F-25 的约定：适配器把
     * "商城的词映射不到规范词表/商城没给"表达成 {@code null}，而不是把原词塞进规范字段）；
     * 两个状态类清单只用来把 {@code null} 的<b>成因</b>说清楚，不参与这个计数。报价缺口不按件数重算，
     * 因为报价读不懂的商品<b>根本不在页里</b>（适配器已排除），它的唯一事实来源就是清单本身——
     * 这也正是它以前会静默消失的原因（H2）。</p>
     *
     * <p>为什么必须并列报全：这些商品既不是"商城说下架"（那是正常业务事实），也不能被当作在售，
     * 只能被排除；少报一个通道，它们就只是"目录里少了几件"，运维会以为是商城没货。
     * 本方法原名 {@code describeUnmappedStatuses}（只有两个状态类清单、没有第三通道），
     * D-067 加入报价通道后改名：名字只描述状态词的话，实现就又在"少说话"了。</p>
     *
     * @param unmappedStateWords 本次目录读取里商城返回过、但映射不到规范词表的原词（可为 null/空）
     * @param stateFieldMissing  本次目录读取里商城没给状态字段（或为空）的商品 ID（可为 null/空）
     * @param priceUnreadable    本次目录读取里报价读不懂、已被适配器排除出目录的商品 ID（可为 null/空）
     * @return 一句可直接写进流水/运行报告的中文；三个通道都没有缺口时返回 {@code null}
     */
    public static String describeCatalogGaps(List<ExternalProduct> products,
                                             List<String> unmappedStateWords,
                                             List<String> stateFieldMissing,
                                             List<String> priceUnreadable) {
        int statusGapCount = 0;
        if (products != null) {
            for (ExternalProduct product : products) {
                if (product != null && product.status() == null) {
                    statusGapCount++;
                }
            }
        }
        List<String> words = distinctSorted(unmappedStateWords);
        List<String> missing = distinctSorted(stateFieldMissing);
        List<String> unreadablePrices = distinctSorted(priceUnreadable);
        if (statusGapCount == 0 && words.isEmpty() && missing.isEmpty() && unreadablePrices.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        if (statusGapCount > 0 || !words.isEmpty() || !missing.isEmpty()) {
            text.append(statusGapCount > 0 ? "状态词缺口 " + statusGapCount + " 件（" : "状态词缺口成因清单（");
            if (!words.isEmpty()) {
                // 写"无法映射"这四个字是有意的：报告要直说成因，不能让读者自己从"原词"推
                text.append("无法映射到规范词表的商城原词：").append(String.join("、", words));
            }
            if (!missing.isEmpty()) {
                if (!words.isEmpty()) {
                    text.append("；");
                }
                text.append("商城未给状态字段的商品：").append(String.join("、", missing));
            }
            if (words.isEmpty() && missing.isEmpty()) {
                // 件数对得上但适配器没登记成因：如实说明"成因未知"，不编一个原因
                text.append("适配器未登记成因清单");
            }
            // 成因与修法写在同一处（谁的通道谁带修法）：这样"没有缺口的通道"不会在话术里被顺口捎上，
            // 读报告的人也不必自己把成因和动作对起来
            text.append("）（需在对应适配器的状态词映射表或商城侧数据里补齐）");
        }
        if (!unreadablePrices.isEmpty()) {
            if (text.length() > 0) {
                text.append("；");
            }
            text.append("报价读不懂、已被排除出目录的商品 ").append(unreadablePrices.size())
                    .append(" 件：").append(String.join("、", unreadablePrices))
                    .append("（需查商城的计价口径：报价读不懂的成因在商城侧，不在映射表）");
        }
        return text.toString();
    }

    /** 去重 + 字典序：缺口说明在任何一次运行里都要是同一串（可复现的取证口径） */
    private static List<String> distinctSorted(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }

    /**
     * 该商城真实能承载的事件类型（能力门 + "商城有没有这个公开动作"）。
     *
     * <p>与 {@link FileModeGenerationEngine#plannedEventTypes} 配合：引擎按这份集合摘事件，
     * 运行报告按同一份集合解释"为什么产物里没有 stock_reserved"。</p>
     */
    public static Set<String> mallBackedEventTypes(TargetCapabilities capabilities) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String eventType : EventTypes.ALL) {
            MallDispatchPlan plan = MallDispatchPlan.of(eventType);
            if (!plan.isMallBacked()) {
                continue;
            }
            if (MallDispatchPlan.OP_LIST_PRODUCTS.equals(plan.operation())
                    || capabilities.isSupported(plan.capability())) {
                allowed.add(eventType);
            }
        }
        return Set.copyOf(allowed);
    }

    /**
     * 取第一条失败流水的可读描述（用于把"到底哪一步被拒了"直接写进异常）。
     *
     * <p>只说第一条不是偷懒：同一批失败往往是同一个原因（例如库存耗尽），
     * 全量在 {@code operation-journal} 制品里逐条可查。</p>
     */
    private static String firstFailure(MallApiDispatchSink.DispatchResult result) {
        return result.entries().stream()
                .filter(entry -> OperationJournalEntry.STATUS_FAILED.equals(entry.status()))
                .findFirst()
                .map(entry -> entry.operation() + " → " + entry.detail())
                .orElse("（流水里没有失败明细）");
    }

    /**
     * 凭据引用的可读描述（只说引用名，绝不说值）。
     *
     * <p>能力判 UNDETERMINED 时，最常见的根因是"环境变量没设"——把引用名说清楚，
     * 运维才知道去补哪一个变量，而不是对着一串 UNDETERMINED 猜。</p>
     */
    private static String describeCredentialRef(TargetConfig target) {
        String ref = target == null ? null : target.credentialRef();
        return ref == null || ref.isBlank() ? "（目标根本没配 credential_ref）" : ref;
    }

    /** 预检结果（运行服务据此写运行报告；不落库，避免把声明当事实固化） */
    public record Preflight(String adapterType,
                            TargetCapabilities capabilities,
                            List<ExternalProduct> catalog,
                            List<String> notes,
                            OperationJournal journal,
                            Map<String, TargetRoute> operationRoutes) {
        public Preflight {
            catalog = List.copyOf(catalog);
            notes = List.copyOf(notes);
            operationRoutes = Map.copyOf(operationRoutes);
        }

        public CapabilityVerdict verdict(MallCapability capability) {
            return capabilities.verdict(capability);
        }
    }

    /**
     * 一次 MALL_API 运行的产出：通用 {@link EngineOutcome} + MALL_API 专有的预检与派发对账。
     *
     * @param outcome        通用产出（运行服务写 {@code generation_run}/{@code generation_event_stat}）
     * @param preflight      预检事实（能力声明、目录规模、缺口说明）
     * @param dispatch       派发对账（成功/失败/跳过、规范 ID → 商城外部 ID、操作流水）
     * @param forwardedCount 写入规范流的事件数
     */
    public record MallRunOutcome(EngineOutcome outcome, Preflight preflight,
                                 MallApiDispatchSink.DispatchResult dispatch, long forwardedCount) {
    }

    /**
     * 落点包装：先让商城真的做成这件事，成功才把事件（规范 ID 已换成商城外部 ID）转写给外层 sink。
     *
     * <p>转写之后才统计 {@code eventStats}：统计口径与产物逐条一致，不存在"统计说有、产物里没有"。</p>
     *
     * <p><b>预算封顶</b>：文件引擎只在写成功时递增 {@code emitted}（见 {@code FileModeGenerationEngine.write}），
     * 所以被商城拒绝的事件既不进产物、也不占预算，生成器会一直补产直到"成功条数 == event_count"。
     * 2026-09-11 对着真机 8090 的那次就是这样：400 条的计划实际打了 568 次商城调用
     * （232 条进产物 + 168 条被拒），一条 FAILED 的运行却记着 632 条"成功"。
     * 这里按<span>尝试次数</span>封顶，把"打商城的次数"重新钉回计划里写的 {@code event_count}；
     * 这不是在改文件引擎的白名单语义，只是 MALL_API 侧的落点闸门。</p>
     */
    private static final class ForwardingSink implements EventSink {

        private final EventSink delegate;
        private final MallApiDispatchSink dispatch;
        /** 由运行服务持有的逐类账本：只记"真的转写进规范流"的事件（D10） */
        private final EventStatsRecorder eventStats;
        private final long attemptCap;
        private long forwarded;
        private long attempted;

        ForwardingSink(EventSink delegate, MallApiDispatchSink dispatch, long attemptCap,
                       EventStatsRecorder eventStats) {
            this.delegate = delegate;
            this.dispatch = dispatch;
            this.attemptCap = attemptCap;
            this.eventStats = eventStats;
        }

        @Override
        public void write(CanonicalEvent event) {
            if (attempted >= attemptCap) {
                // 预算用尽：不再碰商城。返回而不抛，让文件引擎自然收尾（它只看 emitted）。
                budgetExhausted = true;
                return;
            }
            attempted++;
            if (!dispatch.write(event)) {
                return;
            }
            CanonicalEvent rewritten = dispatch.rewrite(event);
            delegate.write(rewritten);
            forwarded++;
            // 记账点在"真的转写进规范流"之后：账本与产物逐条一致，被商城拒绝的事件不进账本。
            // MALL_API 会把计划估价重写成商城真实成交价，统计也必须读 rewritten，
            // 否则 generation_event_stat 会和规范流/商城三边金额不一致。
            eventStats.record(rewritten.eventType(), amountOf(rewritten));
        }

        private boolean budgetExhausted;

        /** 预算是否被商城拒绝"吃满"过（用于运行报告如实说明产物为什么短于 event_count） */
        boolean budgetExhausted() {
            return budgetExhausted;
        }

        long attempted() {
            return attempted;
        }

        /** 金额口径与文件模式逐条一致（见 FileModeGenerationEngine 的 write 调用点） */
        private static java.math.BigDecimal amountOf(CanonicalEvent event) {
            String key = switch (event.eventType()) {
                case EventTypes.ORDER_CREATED -> "total_amount";
                case EventTypes.ORDER_PAID, EventTypes.REFUND_CREATED, EventTypes.REFUND_COMPLETED -> "amount";
                default -> null;
            };
            if (key == null) {
                return null;
            }
            Object value = event.payload().get(key);
            return value == null ? null : new java.math.BigDecimal(String.valueOf(value));
        }

        long forwarded() {
            return forwarded;
        }

        @Override
        public java.util.Optional<com.graduation.generator.contract.Artifact> rotateIfNeeded() {
            return delegate.rotateIfNeeded();
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public com.graduation.generator.contract.ArtifactManifest closeAndBuildManifest() {
            return delegate.closeAndBuildManifest();
        }
    }
}
