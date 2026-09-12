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
import com.graduation.generator.contract.CanonicalEvent;
import com.graduation.generator.contract.EventSink;
import com.graduation.generator.contract.EventTypes;
import com.graduation.generator.core.GenerationResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

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

    @Override
    public EngineOutcome run(GenerationRequest request, EventSink sink, BooleanSupplier cancelled) {
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
        return runForTarget(request, target, adapter, sink, cancelled, null);
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
                journal, preflight.catalog(), false);
        ForwardingSink forwarding = new ForwardingSink(sink, dispatch, request.eventCount());

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
        GenerationResult result = GenerationResult.builder()
                .configKey(base.configKey())
                .scenario(base.scenario())
                .expectedEffect(base.expectedEffect())
                .usersCreated(base.usersCreated())
                .productsCreated(base.productsCreated())
                .behaviorsByType(base.behaviorsByType())
                .ordersCreated(base.ordersCreated())
                .ordersPaid(base.ordersPaid())
                .ordersCancelled(base.ordersCancelled())
                .ordersCompleted(base.ordersCompleted())
                .refundsApplied(base.refundsApplied())
                .refundsCompleted(base.refundsCompleted())
                .gmv(base.gmv())
                .netSale(base.netSale())
                .avgOrderValue(base.avgOrderValue())
                .stockShortageHits(base.stockShortageHits())
                .totalEvents(forwarding.forwarded())
                .sampleEventIds(base.sampleEventIds())
                .dirtySamples(base.dirtySamples())
                .build();

        List<String> allNotes = new ArrayList<>(fileOutcome.notes());
        allNotes.addAll(notes);
        return new MallRunOutcome(
                new EngineOutcome(result, new TreeMap<>(forwarding.stats()), forwarding.forwarded(),
                        dispatchResult.failed(), List.of(), allNotes),
                preflight, dispatchResult, forwarding.forwarded());
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
        OperationJournal journal = new OperationJournal();
        List<String> notes = new ArrayList<>();

        List<String> missing = new ArrayList<>();
        for (MallCapability mandatory : List.of(MallCapability.PRODUCT, MallCapability.USER, MallCapability.ORDER)) {
            if (!capabilities.isSupported(mandatory)) {
                missing.add(mandatory.key() + "=" + capabilities.verdict(mandatory));
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("目标适配器 " + adapter.adapterType()
                    + " 的能力不满足 MALL_API 运行：缺少 " + String.join("、", missing)
                    + "。请先修复目标配置（base_url/凭据引用/config_json）并用 "
                    + "POST /api/v1/targets/{id}/probe 实测确认；本引擎不会降级成文件模式，也不会跳过这些事件报成功"
                    // 点名凭据<b>引用名</b>（不是值）：能力判定 UNDETERMINED 绝大多数是"凭据取不到值"造成的
                    // （参考商城对 /api/v1/** 全部要求 Bearer，D-033），不说清楚就只能看到一串 UNDETERMINED。
                    // 引用名不是秘密，令牌值永远不进日志、不进流水、不进异常信息。
                    + "。本次调用用的凭据引用是 " + describeCredentialRef(target)
                    + "（凭据取不到值时所有路由都判 UNDETERMINED；凭据值不回显）");
        }

        ProductPage page;
        try {
            page = adapter.listProducts(target, ProductQuery.firstPage(CATALOG_PROBE_LIMIT));
        } catch (RuntimeException e) {
            journal.append(MallDispatchPlan.OP_LIST_PRODUCTS, true, "GET", MallDispatchPlan.PRODUCTS_ROUTE,
                    null, null, OperationJournalEntry.STATUS_FAILED, e.getMessage());
            // 点名凭据<b>引用名</b>（不是值）：排查时要知道去修哪一个引用，而不是只知道"401 了"。
            // 这里只说引用名，令牌值永远不进日志、不进流水、不进异常信息。
            throw new IllegalArgumentException("MALL_API 预检失败：读不到商城商品目录（"
                    + e.getMessage() + "）。本次调用用的凭据引用是 " + target.credentialRef()
                    + "（凭据不可用/商城不可达时不允许启动运行，也不会降级成文件模式）", e);
        }
        List<ExternalProduct> catalog = page.products().stream().filter(ExternalProduct::onSale).toList();
        journal.append(MallDispatchPlan.OP_LIST_PRODUCTS, true, "GET", MallDispatchPlan.PRODUCTS_ROUTE,
                null, null, OperationJournalEntry.STATUS_OK,
                "目录 %d 件，在售 %d 件（预检兼凭据校验）".formatted(page.total(), catalog.size()));
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
        return new Preflight(adapter.adapterType(), capabilities, catalog, List.copyOf(notes), journal);
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
                            OperationJournal journal) {
        public Preflight {
            catalog = List.copyOf(catalog);
            notes = List.copyOf(notes);
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
        private final Map<String, EventTypeStat> stats = new TreeMap<>();
        private final long attemptCap;
        private long forwarded;
        private long attempted;

        ForwardingSink(EventSink delegate, MallApiDispatchSink dispatch, long attemptCap) {
            this.delegate = delegate;
            this.dispatch = dispatch;
            this.attemptCap = attemptCap;
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
            delegate.write(dispatch.rewrite(event));
            forwarded++;
            stats.merge(event.eventType(), new EventTypeStat(1, amountOf(event)),
                    (left, right) -> left.plus(right.count(), right.amount()));
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

        Map<String, EventTypeStat> stats() {
            return stats;
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
