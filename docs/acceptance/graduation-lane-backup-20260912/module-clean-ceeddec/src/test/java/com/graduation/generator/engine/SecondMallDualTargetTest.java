package com.graduation.generator.engine;

import com.graduation.generator.adapter.CapabilityVerdict;
import com.graduation.generator.adapter.ExternalProduct;
import com.graduation.generator.adapter.ExternalUser;
import com.graduation.generator.adapter.MallCapability;
import com.graduation.generator.adapter.MallTargetAdapter;
import com.graduation.generator.adapter.ProductPage;
import com.graduation.generator.adapter.ProductQuery;
import com.graduation.generator.adapter.ReferenceMallHttpAdapter;
import com.graduation.generator.adapter.SecondMallHttpAdapter;
import com.graduation.generator.adapter.TargetCapabilities;
import com.graduation.generator.adapter.TargetConfig;
import com.graduation.generator.adapter.TargetRoute;
import com.graduation.generator.adapter.UserCommand;
import com.graduation.generator.contract.Artifact;
import com.graduation.generator.contract.ArtifactManifest;
import com.graduation.generator.contract.CanonicalEvent;
import com.graduation.generator.contract.ContractEnums;
import com.graduation.generator.contract.EventSink;
import com.graduation.generator.fixture.FakeMallServer;
import com.graduation.generator.fixture.SecondMallFakeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-9 ②：<b>两家商城同机并存</b>（T6）与"路由归适配器"的回归守卫。
 *
 * <p>三件事在这里被钉死：</p>
 * <ol>
 *   <li><b>T6 不串台</b>：同一个 JVM 里依次对两家商城各跑一次运行，各自 {@code TargetConfig} 一份；
 *       每家的流水只出现自己家的路由形状，每家的夹具只收到自己家的路径——
 *       "换 TargetConfig 就换商城"必须是可观测的事实，而不是文档里的承诺。</li>
 *   <li><b>路由解析次数</b>：{@code operationRoutes} 每次运行只解析一次（预检那次），
 *       随 {@link MallApiGenerationEngine.Preflight} 带着走；流水条数的路由不该是"每条事件问一次适配器"
 *       的结果，否则"同一次运行里路由中途变了"这种事将无法察觉。</li>
 *   <li><b>没声明路由时的表现</b>：适配器返回空路由表 ⇒ 流水里是明确占位、且<b>整本流水不含任何商城路径字面量</b>
 *       （硬约束 6：引擎与落点不许内置任何一家商城的路由，也不许回落到参考商城）。</li>
 * </ol>
 *
 * <p>本类刻意同时用两个夹具：{@link FakeMallServer}（参考商城 {@code /api/v1/**}）与
 * {@link SecondMallFakeServer}（第二家 {@code /open/v2/**}）。两家的前缀不同，因此
 * "流水里出现了哪一家的路径"是一条不需要解释的证据。</p>
 */
class SecondMallDualTargetTest {

    private static final String REF_ENV = "GENERATOR_DUAL_REF_TOKEN";
    private static final String SECOND_ENV = "SECOND_MALL_TOKEN";
    private static final String REF_TOKEN = "ut-token-dual-ref";
    private static final String SECOND_TOKEN = "ut-token-dual-second";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-04T00:00:00Z");
    private static final long EVENT_COUNT = 60L;
    private static final int CATALOG_SIZE = 60;

    /** 第二家返回的、本适配器词表里没有的商品状态词（用来验证"读不懂必须被点名"） */
    private static final String UNMAPPABLE_STATE = "ARCHIVED";

    static {
        System.setProperty(REF_ENV, REF_TOKEN);
        System.setProperty(SECOND_ENV, SECOND_TOKEN);
    }

    private final MallApiGenerationEngine engine = new MallApiGenerationEngine();
    private FakeMallServer referenceMall;
    private SecondMallFakeServer secondMall;

    @AfterEach
    void stopMalls() {
        if (referenceMall != null) {
            referenceMall.close();
        }
        if (secondMall != null) {
            secondMall.close();
        }
    }

    // ---------- T6：两家商城各跑一次，流水与真调用都不串台 ----------

    @Test
    @DisplayName("T6 双目标并存：每家一次运行，流水与真实调用都只出现自己家的路由")
    void twoTargetsInOneJvmDoNotCrossTalk() throws IOException {
        referenceMall = new FakeMallServer(REF_TOKEN, null, CATALOG_SIZE);
        secondMall = new SecondMallFakeServer(SECOND_TOKEN, null, CATALOG_SIZE);

        TargetConfig referenceTarget = new TargetConfig(31L, ReferenceMallHttpAdapter.ADAPTER_TYPE,
                referenceMall.baseUrl(), REF_ENV, "{}");
        TargetConfig secondTarget = new TargetConfig(32L, SecondMallHttpAdapter.ADAPTER_TYPE,
                secondMall.baseUrl(), SECOND_ENV, "{\"format\":\"open-v2\"}");

        RecordingSink referenceSink = new RecordingSink();
        RecordingSink secondSink = new RecordingSink();
        MallApiGenerationEngine.MallRunOutcome referenceOutcome = engine.runForTarget(
                request("run-ref"), referenceTarget, referenceAdapter(), referenceSink, () -> false);
        MallApiGenerationEngine.MallRunOutcome secondOutcome = engine.runForTarget(
                request("run-second"), secondTarget, secondAdapter(), secondSink, () -> false);

        // 1) 两次运行都真的写进了规范流，条数一致（否则下面的"不串台"没有对照物）
        assertEquals(EVENT_COUNT, referenceOutcome.forwardedCount(), "参考商城那次必须跑满计划条数");
        assertEquals(EVENT_COUNT, secondOutcome.forwardedCount(), "第二家那次必须跑满计划条数");
        assertEquals(referenceOutcome.forwardedCount(), referenceSink.events.size());

        List<String> referenceRoutes = journalRoutes(referenceOutcome);
        List<String> secondRoutes = journalRoutes(secondOutcome);
        assertFalse(referenceRoutes.isEmpty(), "参考商城那次必须留下真实调用行");
        assertFalse(secondRoutes.isEmpty(), "第二家那次必须留下真实调用行");

        // 2) 流水不串台：每本流水只出现自己家的前缀（这是硬约束 6/9 的可观测形态）
        assertTrue(referenceRoutes.stream().allMatch(route -> route.startsWith("/api/v1/")),
                "参考商城的流水里混进了别的商城的路由：" + distinct(referenceRoutes));
        assertTrue(secondRoutes.stream().allMatch(route -> route.startsWith("/open/v2/")),
                "第二家的流水里混进了别的商城的路由：" + distinct(secondRoutes));

        // 3) 真调用不串台：每家的夹具只收到自己家的路径（"换了 TargetConfig 就换了商城"）
        List<String> referenceExchanges = referenceMall.exchanges();
        assertFalse(referenceExchanges.isEmpty(), "参考商城夹具必须真的收到了请求");
        assertTrue(referenceExchanges.stream().allMatch(line -> line.contains(" /api/v1/")),
                "参考商城收到了不属于它的请求：" + referenceExchanges.stream().distinct().toList());
        assertTrue(secondMall.exchanges().stream().allMatch(line -> line.contains(" /open/v2/")),
                "第二家收到了不属于它的请求：" + secondMall.exchanges().stream().distinct().toList());

        // 4) 两边的规范事件都过契约枚举：商品状态必须是规范词（F-25），且第二家那次的事件是用
        //    第二家的字段读回来的（标题里带第二家的字眼，说明数据真的来自第二家而不是参考商城）
        assertReferenceEventsUseCanonicalStatus(referenceSink);
        assertSecondEventsUseCanonicalStatusAndSecondMallData(secondSink);

        // 5) 两家夹具的"商城主键"形状不同：规范 ID → 外部 ID 的改写必须各按各家来
        assertTrue(secondMall.itemSkus().stream().allMatch(sku -> sku.startsWith("SKU")),
                "第二家的主键形状（SKU…）必须来自第二家夹具");
        assertEquals(0, secondMall.hits("GET /api/v1/mall/products"), "第二家不该被参考商城的路径打到");
        assertEquals(0, referenceMall.hits("GET /open/v2/items"), "参考商城不该被第二家的路径打到");
    }

    @Test
    @DisplayName("T6 路由解析：operationRoutes 每次运行只问适配器一次（预检那次），不是每条事件一次")
    void operationRoutesAreResolvedOncePerRun() throws IOException {
        secondMall = new SecondMallFakeServer(SECOND_TOKEN, null, CATALOG_SIZE);
        TargetConfig secondTarget = new TargetConfig(33L, SecondMallHttpAdapter.ADAPTER_TYPE,
                secondMall.baseUrl(), SECOND_ENV, "{\"format\":\"open-v2\"}");

        CountingAdapter counting = new CountingAdapter(secondAdapter());
        RecordingSink sink = new RecordingSink();
        MallApiGenerationEngine.MallRunOutcome outcome =
                engine.runForTarget(request("run-count"), secondTarget, counting, sink, () -> false);

        assertEquals(EVENT_COUNT, outcome.forwardedCount());
        assertEquals(1, counting.routeResolutions,
                "operationRoutes 必须每次运行只解析一次（预检），实际=" + counting.routeResolutions
                        + "；每条事件问一次会让\"同一次运行里路由中途变了\"无法察觉");
        // 能力判定可以被问多次（预检一次、构造落点一次），但它必须是不联网的纯本地判定——
        // 这里只要求"次数是常数级"，不把它钉成 1，避免把实现细节当成契约。
        assertTrue(counting.capabilityResolutions >= 1 && counting.capabilityResolutions <= 4,
                "capabilities 的调用次数应当是常数级，实际=" + counting.capabilityResolutions);

        // 预检带着的那张路由表，与适配器自报的一致（流水用的就是它，不是引擎自己拼的）
        Map<String, TargetRoute> declared = secondAdapter().operationRoutes(secondTarget);
        assertEquals(declared, outcome.preflight().operationRoutes(),
                "Preflight 里的路由表必须就是适配器自报的那张");
    }

    // ---------- 硬约束 6 的回归守卫：没声明路由时必须占位，且不许回落到任何商城 ----------

    @Test
    @DisplayName("适配器不声明路由 ⇒ 流水是明确占位，且整本流水不含任何商城路径字面量")
    void undeclaredRoutesProducePlaceholdersAndNoMallLiterals() throws IOException {
        secondMall = new SecondMallFakeServer(SECOND_TOKEN, null, CATALOG_SIZE);
        TargetConfig target = new TargetConfig(34L, SecondMallHttpAdapter.ADAPTER_TYPE,
                secondMall.baseUrl(), SECOND_ENV, "{\"format\":\"open-v2\"}");

        MallTargetAdapter noRoutes = routesStripped(secondAdapter());
        RecordingSink sink = new RecordingSink();
        MallApiGenerationEngine.MallRunOutcome outcome =
                engine.runForTarget(request("run-noroute"), target, noRoutes, sink, () -> false);

        assertEquals(1, ((CountingAdapter) noRoutes).routeResolutions,
                "没声明路由也必须只解析一次（占位符不是\"每条事件现算\"的结果）");
        List<OperationJournalEntry> entries = outcome.dispatch().entries();
        List<OperationJournalEntry> realHttp = entries.stream()
                .filter(OperationJournalEntry::realHttp).toList();
        assertFalse(realHttp.isEmpty(), "这次运行必须留下真实调用行（否则守卫没有对照物）");
        assertTrue(realHttp.stream().allMatch(entry ->
                        MallApiDispatchSink.ROUTE_UNDECLARED_METHOD.equals(entry.httpMethod())
                                && MallApiDispatchSink.ROUTE_UNDECLARED_PATH.equals(entry.route())),
                "没声明路由时，真实调用行必须写明确占位，而不是空着或猜一个：" + realHttp.stream()
                        .map(entry -> entry.httpMethod() + " " + entry.route()).distinct().toList());

        // 整本流水（含本地记账行）不许出现任何商城路径字面量——引擎与落点没有内置任何一家的路由
        List<String> rows = entries.stream()
                .map(entry -> entry.operation() + " " + entry.httpMethod() + " " + entry.route()
                        + " " + entry.detail())
                .toList();
        String journalText = String.join("\n", rows);
        assertFalse(journalText.contains("/api/"), "流水里出现了参考商城的路径字面量：\n" + journalText);
        assertFalse(journalText.contains("/open/"), "流水里出现了第二家的路径字面量：\n" + journalText);
        // 本地记账行（没发请求）必须保持"没有方法/路径"，不许被占位符污染（D12：本地行不许被算成真实调用）
        assertTrue(entries.stream().filter(OperationJournalEntry::localAccounting)
                        .allMatch(entry -> entry.httpMethod() == null && entry.route() == null),
                "本地记账行不许带方法/路径（占位符也不行）");
        assertTrue(entries.stream().anyMatch(OperationJournalEntry::localAccounting),
                "这次运行必须有本地记账行（商品对齐是本地记账），否则\"本地行不带路由\"没有被检验");
    }

    // ---------- F-25 / 【必改 3】：读不懂的状态词必须被点名，且绝不进入规范流 ----------

    @Test
    @DisplayName("商品状态词读不懂：流水点名件数与商城原词，该商品不进规范流，可映射的照常进")
    void unmappableStatusWordIsNamedAndExcluded() throws IOException {
        secondMall = new SecondMallFakeServer(SECOND_TOKEN, null, CATALOG_SIZE);
        // 只让目录里第一件商品带一个本适配器词表里没有的状态词：其余仍是 SALE，
        // 这样"被排除 1 件"与"其余照常进流"能同时被观测到（全改掉就只能看到前者）。
        String unmappableSku = secondMall.itemSkus().get(0);
        secondMall.overrideItemState(unmappableSku, UNMAPPABLE_STATE);
        int mappableCount = CATALOG_SIZE - 1;

        TargetConfig target = new TargetConfig(35L, SecondMallHttpAdapter.ADAPTER_TYPE,
                secondMall.baseUrl(), SECOND_ENV, "{\"format\":\"open-v2\"}");

        RecordingSink sink = new RecordingSink();
        MallApiGenerationEngine.MallRunOutcome outcome =
                engine.runForTarget(request("run-unmapped"), target, secondAdapter(), sink, () -> false);

        // 1) 缺口必须被点名：件数 + 商城原词（F-25 的"读不懂要能看见"）
        String gapText = String.join("\n", outcome.preflight().journal().entries().stream()
                .map(entry -> String.valueOf(entry.detail())).toList())
                + "\n" + String.join("\n", outcome.preflight().notes());
        assertTrue(gapText.contains(UNMAPPABLE_STATE),
                "缺口说明里必须出现商城的原词 " + UNMAPPABLE_STATE + "：\n" + gapText);
        assertTrue(gapText.contains("无法映射"),
                "缺口说明必须直说\"无法映射\"，而不是含糊其辞：\n" + gapText);
        assertTrue(gapText.contains("1 件"),
                "缺口说明必须带件数（这里恰好 1 件，含\"1 件\"字样）：\n" + gapText);
        // 2) 可用目录必须恰好少了这一件：预检那行"在售 M 件"要能对上 M = 目录 - 缺口
        assertTrue(gapText.contains("目录 %d 件，在售 %d 件".formatted(CATALOG_SIZE, mappableCount)),
                "预检行必须显示目录件数与在售件数（%d/%d）：\n".formatted(CATALOG_SIZE, mappableCount) + gapText);
        assertEquals(mappableCount, outcome.preflight().catalog().size(),
                "可用目录必须把读不懂的那件排除掉");
        assertTrue(outcome.preflight().catalog().stream()
                        .noneMatch(product -> unmappableSku.equals(product.productId())),
                "读不懂状态词的商品不许留在可用目录里：" + unmappableSku);

        // 3) 读不懂的那件绝不进入规范流（它的 SKU 不该出现在任何事件的载荷里）
        assertFalse(sink.events.toString().contains(unmappableSku),
                "读不懂状态词的商品不许进入规范流（连外键都不该出现）：" + unmappableSku);

        // 4) 正向对照：可映射的（SALE ⇒ on_sale）商品照常进规范流，且状态是规范词
        assertFalse(sink.events.isEmpty(), "排除了 1 件商品，不该把整次运行也弄空");
        List<CanonicalEvent> productEvents = sink.events.stream()
                .filter(event -> "product_created".equals(event.eventType())).toList();
        assertFalse(productEvents.isEmpty(), "至少要有商品事件（否则正向对照不成立）");
        assertTrue(productEvents.stream().allMatch(event ->
                        ContractEnums.PRODUCT_STATUS.contains(String.valueOf(event.payload().get("status")))),
                "规范事件的 product.status 必须是契约枚举里的词："
                        + productEvents.stream().map(event -> event.payload().get("status")).distinct().toList());
        assertTrue(productEvents.stream().anyMatch(event -> "on_sale".equals(event.payload().get("status"))),
                "第二家说 SALE 的商品必须映射成 on_sale 后进流");
        // 5) 映射不到的判据本身也要被钉住：这一词在第二家的映射表里没有对应规范词
        assertNull(SecondMallHttpAdapter.toCanonicalProductStatus(UNMAPPABLE_STATE),
                "未登记的商城状态词必须映射成 null（而不是原样透出或猜一个）");
        assertTrue(SecondMallHttpAdapter.toCanonicalProductStatus("SALE") != null,
                "正向对照：SALE 是有映射的，说明上面的 null 不是\"映射表整个坏了\"");
    }

    // ---------- 断言辅助 ----------

    private static void assertReferenceEventsUseCanonicalStatus(RecordingSink sink) {
        List<CanonicalEvent> productEvents = sink.events.stream()
                .filter(event -> "product_created".equals(event.eventType())).toList();
        assertFalse(productEvents.isEmpty(), "参考商城那次也要有商品事件");
        assertTrue(productEvents.stream().allMatch(event -> "on_sale".equals(event.payload().get("status"))),
                "参考商城的商品状态必须落在规范词上");
    }

    private static void assertSecondEventsUseCanonicalStatusAndSecondMallData(RecordingSink sink) {
        List<CanonicalEvent> productEvents = sink.events.stream()
                .filter(event -> "product_created".equals(event.eventType())).toList();
        assertFalse(productEvents.isEmpty(), "第二家那次也要有商品事件");
        // 第二家说 SALE ⇒ 规范流里必须是 on_sale（F-25：映射归适配器，规范流只装规范词）
        assertTrue(productEvents.stream().allMatch(event -> "on_sale".equals(event.payload().get("status"))),
                "第二家的商品状态必须是 on_sale，实际="
                        + productEvents.stream().map(event -> event.payload().get("status")).distinct().toList());
        // 数据真的来自第二家：名称是第二家夹具的词（"第二家商品-N"），而不是参考商城的商品名
        List<Object> names = productEvents.stream().map(event -> event.payload().get("product_name"))
                .distinct().toList();
        assertTrue(names.stream().allMatch(name -> String.valueOf(name).startsWith("第二家商品-")),
                "规范事件里的商品数据必须来自第二家（而不是参考商城）：" + names);
    }

    private static List<String> journalRoutes(MallApiGenerationEngine.MallRunOutcome outcome) {
        return outcome.dispatch().entries().stream()
                .filter(OperationJournalEntry::realHttp)
                .map(OperationJournalEntry::route)
                .toList();
    }

    private static List<String> distinct(List<String> routes) {
        return routes.stream().distinct().sorted().toList();
    }

    /** 把适配器的路由表清空（其余原样转发、照常计数）：用来验证"没声明路由"时的行为 */
    private static MallTargetAdapter routesStripped(MallTargetAdapter inner) {
        return new CountingAdapter(inner) {
            @Override
            public Map<String, TargetRoute> operationRoutes(TargetConfig config) {
                super.operationRoutes(config);
                return Map.of();
            }
        };
    }

    // ---------- 适配器与落点（辅助） ----------

    private static ReferenceMallHttpAdapter referenceAdapter() {
        return new ReferenceMallHttpAdapter(name -> System.getProperty(name, System.getenv(name)),
                Duration.ofSeconds(5));
    }

    private static SecondMallHttpAdapter secondAdapter() {
        return new SecondMallHttpAdapter(name -> System.getProperty(name, System.getenv(name)),
                Duration.ofSeconds(5));
    }

    private static GenerationRequest request(String runId) {
        return new GenerationRequest(runId, "normal", 20260912L, START, END, EVENT_COUNT, 0,
                GenerationRequest.DIRTY_NONE);
    }

    /**
     * 计数包装：把七个操作原样转发给真实适配器，只记录"路由表被问了几次"。
     *
     * <p>为什么用包装而不是打桩：被测的是"引擎问了几次"，而适配器必须仍是<b>真的会发 HTTP</b> 的那一个，
     * 否则"解析次数"这条断言就与真实调用脱钩了。</p>
     */
    private static class CountingAdapter implements MallTargetAdapter {

        private final MallTargetAdapter inner;
        private int routeResolutions;
        private int capabilityResolutions;

        CountingAdapter(MallTargetAdapter inner) {
            this.inner = inner;
        }

        @Override
        public String adapterType() {
            return inner.adapterType();
        }

        @Override
        public TargetCapabilities capabilities(TargetConfig config) {
            capabilityResolutions++;
            return inner.capabilities(config);
        }

        @Override
        public Map<String, TargetRoute> operationRoutes(TargetConfig config) {
            routeResolutions++;
            return inner.operationRoutes(config);
        }

        @Override
        public ProductPage listProducts(TargetConfig config, ProductQuery query) {
            return inner.listProducts(config, query);
        }

        @Override
        public ExternalUser createSyntheticUser(TargetConfig config, UserCommand command) {
            return inner.createSyntheticUser(config, command);
        }

        @Override
        public com.graduation.generator.adapter.TargetCheckResult test(TargetConfig config) {
            return inner.test(config);
        }

        @Override
        public void emitBehavior(TargetConfig config, com.graduation.generator.adapter.BehaviorCommand command) {
            inner.emitBehavior(config, command);
        }

        @Override
        public com.graduation.generator.adapter.ExternalOrder createOrder(
                TargetConfig config, com.graduation.generator.adapter.OrderCommand command) {
            return inner.createOrder(config, command);
        }

        @Override
        public com.graduation.generator.adapter.ExternalOrder pay(
                TargetConfig config, com.graduation.generator.adapter.PayCommand command) {
            return inner.pay(config, command);
        }

        @Override
        public com.graduation.generator.adapter.ExternalOrder cancel(
                TargetConfig config, com.graduation.generator.adapter.CancelCommand command) {
            return inner.cancel(config, command);
        }

        @Override
        public com.graduation.generator.adapter.ExternalRefund refund(
                TargetConfig config, com.graduation.generator.adapter.RefundCommand command) {
            return inner.refund(config, command);
        }
    }

    /** 内存落点：记录事件，不落盘（落盘链路由既有的 smoke 用例覆盖） */
    private static final class RecordingSink implements EventSink {

        private final List<CanonicalEvent> events = new ArrayList<>();

        @Override
        public void write(CanonicalEvent event) {
            events.add(event);
        }

        @Override
        public Optional<Artifact> rotateIfNeeded() {
            return Optional.empty();
        }

        @Override
        public void flush() {
        }

        @Override
        public ArtifactManifest closeAndBuildManifest() {
            String min = events.isEmpty() ? null : events.get(0).eventTime();
            String max = events.isEmpty() ? null : events.get(events.size() - 1).eventTime();
            return new ArtifactManifest("run", "memory://dual-sink", null, 0L, events.size(),
                    min, max, "1.0", true);
        }
    }
}
