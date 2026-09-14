package com.graduation.generator.engine;

import com.graduation.generator.adapter.BehaviorCommand;
import com.graduation.generator.adapter.CancelCommand;
import com.graduation.generator.adapter.CapabilityVerdict;
import com.graduation.generator.adapter.ExternalOrder;
import com.graduation.generator.adapter.ExternalRefund;
import com.graduation.generator.adapter.ExternalUser;
import com.graduation.generator.adapter.MallCapability;
import com.graduation.generator.adapter.MallOperationException;
import com.graduation.generator.adapter.MallTargetAdapter;
import com.graduation.generator.adapter.OrderCommand;
import com.graduation.generator.adapter.PayCommand;
import com.graduation.generator.adapter.ProductPage;
import com.graduation.generator.adapter.ProductQuery;
import com.graduation.generator.adapter.ReferenceMallHttpAdapter;
import com.graduation.generator.adapter.RefundCommand;
import com.graduation.generator.adapter.TargetCapabilities;
import com.graduation.generator.adapter.TargetCheckResult;
import com.graduation.generator.adapter.TargetConfig;
import com.graduation.generator.adapter.UserCommand;
import com.graduation.generator.contract.Artifact;
import com.graduation.generator.contract.ArtifactManifest;
import com.graduation.generator.contract.CanonicalEvent;
import com.graduation.generator.contract.EventSink;
import com.graduation.generator.fixture.FakeMallServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4b：MALL_API 生成引擎的单元与集成验收。
 *
 * <p>四类事必须在这里被钉死：</p>
 * <ol>
 *   <li><b>响亮失败</b>：必备能力缺失、凭据不可用、目录不足、脏档位、没有目标适配器——
 *       全部抛异常并说清缺什么，<b>绝不</b>降级成文件模式、绝不静默跳过、绝不留半截状态；</li>
 *   <li><b>计划同源</b>：MALL_API 与文件模式读同一份 {@link GenerationRequest}，被摘掉的事件类型
 *       <b>在计划层</b>就不生成，因此两边的种子/时间窗/剩余事件流逐条一致（这也是"不重复实现一套规划逻辑"的物证）；</li>
 *   <li><b>统计口径</b>：{@code success_count} 只数"商城真的做成了"的事件，跳过与失败分开记账；</li>
 *   <li><b>真实调用</b>：用真套接字上的 {@link FakeMallServer} 而不是打桩，核对调用次数、顺序依赖与
 *       规范 ID → 商城外部 ID 的改写。</li>
 * </ol>
 */
class MallApiGenerationEngineTest {

    private static final String ENV_NAME = "GENERATOR_UT_MALL_TOKEN";
    private static final String TOKEN = "ut-token-7a1e";
    private static final String BASE_BEHAVIOR_PATH = FakeMallServer.DEFAULT_BEHAVIOR_PATH;
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-08T00:00:00Z");
    private static final long EVENT_COUNT = 400L;

    static {
        System.setProperty(ENV_NAME, TOKEN);
    }

    private final MallApiGenerationEngine engine = new MallApiGenerationEngine();
    private FakeMallServer mall;

    @AfterEach
    void stopMall() {
        if (mall != null) {
            mall.close();
        }
    }

    // ---------- 1. 正常路径：真实商城 + 规范流同源 ----------

    @Test
    @DisplayName("正常路径：逐条先打商城、成功了才写规范流，且统计与产物逐条对账")
    void happyPathDrivesMallThenWritesCanonicalStream() throws IOException {
        mall = new FakeMallServer(TOKEN, null, 60);
        TargetConfig target = mallTarget(mall.baseUrl(), ENV_NAME);
        ReferenceMallHttpAdapter adapter = adapter();

        RecordingSink sink = new RecordingSink();
        MallApiGenerationEngine.MallRunOutcome outcome =
                engine.runForTarget(request("run-ok", "none"), target, adapter, sink, () -> false);

        // 事件流：只含商城能承载的类型，条数 = 落盘条数 = success_count
        Set<String> types = new java.util.LinkedHashSet<>();
        sink.events.forEach(event -> types.add(event.eventType()));
        Set<String> expected = MallApiGenerationEngine.mallBackedEventTypes(adapter.capabilities(target));
        assertTrue(expected.containsAll(types), "事件类型不许越出能力白名单：" + types);
        for (String absent : List.of("product_updated", "stock_reserved", "stock_released", "stock_changed",
                "behavior")) {
            assertFalse(types.contains(absent), "商城公开接口没有对应动作，这类事件不许进产物：" + absent);
        }
        assertTrue(types.containsAll(Set.of("user_registered", "product_created", "order_created")),
                "至少这三条链路必须真的跑起来：" + types);
        assertEquals(sink.events.size(), outcome.forwardedCount(), "转发条数必须等于落盘条数");
        assertEquals(sink.events.size(), outcome.outcome().successCount(), "success_count 必须等于真实落盘条数");
        assertEquals(0L, outcome.outcome().failedCount(), "正常路径不应有失败；失败流水="
                + outcome.dispatch().entries().stream()
                .filter(e -> !e.succeeded()).limit(5).map(OperationJournalEntry::detail).toList()
                + " notes=" + outcome.dispatch().notes()
                + " DBG=" + sink.events.stream().limit(60)
                .map(e -> e.eventType() + ":" + e.payload().get("order_id")).toList());

        // 商城与规范流逐类对账
        Map<String, Integer> counts = new TreeMap<>();
        sink.events.forEach(event -> counts.merge(event.eventType(), 1, Integer::sum));
        assertEquals(counts.getOrDefault("user_registered", 0), mall.hits("POST /api/v1/mall/users"));
        assertEquals(counts.getOrDefault("order_created", 0), mall.hits("POST /api/v1/mall/orders"));
        assertEquals(counts.getOrDefault("order_paid", 0), mall.hits("POST /api/v1/mall/orders/{orderId}/pay"));
        assertEquals(counts.getOrDefault("order_cancelled", 0), mall.hits("POST /api/v1/mall/orders/{orderId}/cancel"));
        assertEquals(counts.getOrDefault("refund_created", 0),
                mall.hits("POST /api/v1/mall/orders/{orderId}/refunds"));
        assertEquals(counts.getOrDefault("refund_completed", 0),
                mall.hits(FakeMallServer.REFUND_COMPLETE_PATTERN));

        // 规范 ID → 商城外部 ID：产物里落的是商城 ID，映射表里能查到
        CanonicalEvent firstOrder = sink.events.stream()
                .filter(event -> "order_created".equals(event.eventType())).findFirst().orElseThrow();
        String orderId = String.valueOf(firstOrder.payload().get("order_id"));
        assertTrue(orderId.startsWith("82") && orderId.length() == 19, "订单号必须是商城雪花 ID：" + orderId);
        assertEquals(Set.of("user", "order", "refund", "product"),
                outcome.dispatch().traceability().keySet().stream()
                        .map(key -> key.substring(0, key.indexOf(':'))).collect(java.util.stream.Collectors.toSet()),
                "映射表必须覆盖用户/订单/退款/商品四类：" + outcome.dispatch().traceability().keySet());

        // payload 中的商品事实被改写成商城真实目录值（不是自造的 P00001）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) firstOrder.payload().get("items");
        String itemProductId = String.valueOf(items.get(0).get("product_id"));
        assertTrue(mall.productIds().contains(itemProductId),
                "订单商品必须来自真实目录：" + itemProductId);

        // 操作流水：预检 1 条真实读取 + 每个"有公开写接口"的事件各 1 条真实调用。
        // D12 起这两个数必须分开数：product_created 走的是预检已取回的目录（本地对齐记账，不发请求），
        // refund_completed 复用申请那次的退款单（同样是本地记账）。
        long refundCompletions = sink.events.stream()
                .filter(event -> "refund_completed".equals(event.eventType())).count();
        long productAlignments = sink.events.stream()
                .filter(event -> "product_created".equals(event.eventType())).count();
        long realCalls = outcome.dispatch().entries().stream().filter(OperationJournalEntry::realHttp).count();
        assertEquals(1 + sink.events.size() - productAlignments - refundCompletions, realCalls,
                "真实调用流水条数 = 预检 1 条 + 有公开写接口的事件条数"
                        + "（product_created " + productAlignments + " 条是本地对齐、refund_completed "
                        + refundCompletions + " 条复用申请那次的退款单，都不发请求）");
        assertEquals(productAlignments + refundCompletions,
                outcome.dispatch().entries().stream()
                        .filter(OperationJournalEntry::localAccounting).count(),
                "本地对齐记账条数 = 商品对齐 + 退款完成复用");
        assertTrue(sink.events.stream().noneMatch(event -> "product_updated".equals(event.eventType())
                        || "stock_reserved".equals(event.eventType())),
                "商城公开接口无对应动作的事件类型不该出现在产物里");
        assertEquals(sink.events.size(), outcome.dispatch().succeeded(), "成功操作数 = 落盘条数");
        // 缺口怎么被"如实登记"：behavior 这类事件在<b>计划层</b>就没生成，所以它不会走到派发、
        // 也就不占 skipped 计数；缺口落在运行报告的 notes 里（下方断言），而不是悄悄消失。
        assertEquals(0L, outcome.dispatch().skipped(), "计划层摘掉的事件不该走到派发，skipped 应保持 0");
        assertEquals(0, mall.hits("POST " + BASE_BEHAVIOR_PATH), "不该去撞不存在的埋点路径");
        assertTrue(outcome.outcome().notes().stream().anyMatch(note -> note.contains("行为埋点")
                        && note.contains("UNDETERMINED")),
                "能力缺口必须写进运行报告：" + outcome.outcome().notes());

        // 预检事实：能力取自声明、目录取自真实读取
        assertEquals(ReferenceMallHttpAdapter.ADAPTER_TYPE, outcome.preflight().adapterType());
        assertEquals(CapabilityVerdict.SUPPORTED, outcome.preflight().verdict(MallCapability.ORDER));
        assertEquals(CapabilityVerdict.UNDETERMINED, outcome.preflight().verdict(MallCapability.BEHAVIOR),
                "未声明的行为埋点只能 UNDETERMINED");
        assertEquals(60, outcome.preflight().catalog().size(), "目录规模必须来自真实 listProducts");
        assertEquals(1, mall.hits("GET /api/v1/mall/products"), "预检只读一次目录");
    }

    // ---------- 1b. D12：流水必须自报"这一次到底有没有真的发请求" ----------

    @Test
    @DisplayName("D12：real_http 只数真发过请求的行，本地对齐记账另行标注，且与商城服务端收到的请求数对账")
    void journalSeparatesRealHttpFromLocalAccounting() throws IOException {
        mall = new FakeMallServer(TOKEN, null, 60);
        TargetConfig target = mallTarget(mall.baseUrl(), ENV_NAME);
        RecordingSink sink = new RecordingSink();

        MallApiGenerationEngine.MallRunOutcome outcome =
                engine.runForTarget(request("run-d12", "none"), target, adapter(), sink, () -> false);
        List<OperationJournalEntry> entries = outcome.dispatch().entries();

        long realHttp = entries.stream()
                .filter(entry -> Boolean.TRUE.equals(entry.toJson().get("real_http"))).count();
        long local = entries.stream()
                .filter(entry -> Boolean.TRUE.equals(entry.toJson().get("local_accounting"))).count();
        long gaps = entries.stream()
                .filter(entry -> OperationJournalEntry.STATUS_SKIPPED.equals(entry.status())).count();

        Map<String, Integer> eventCounts = new TreeMap<>();
        sink.events.forEach(event -> eventCounts.merge(event.eventType(), 1, Integer::sum));
        long expectedRealRows = 1 + eventCounts.getOrDefault("user_registered", 0)
                + eventCounts.getOrDefault("order_created", 0) + eventCounts.getOrDefault("order_paid", 0)
                + eventCounts.getOrDefault("order_cancelled", 0) + eventCounts.getOrDefault("refund_created", 0);
        assertEquals(expectedRealRows, realHttp,
                "real_http 必须只数真发过请求的操作：预检 1 次 + 有公开写接口的事件各一次"
                        + "（product_created 用的是预检已取回的目录、refund_completed 复用申请那次退款单，都不是请求）");
        assertEquals(entries.size(), realHttp + local + gaps,
                "每一行必须恰好属于一类：真实调用 / 本地记账 / 能力缺口（real=" + realHttp + " local=" + local
                        + " gap=" + gaps + " 合计=" + entries.size() + "）");

        for (OperationJournalEntry entry : entries) {
            Map<String, Object> row = entry.toJson();
            if (Boolean.TRUE.equals(row.get("local_accounting"))) {
                assertFalse(Boolean.TRUE.equals(row.get("real_http")), "本地记账行不得同时声称发过请求：" + row);
                assertEquals(null, row.get("http_method"), "没发请求就不该写请求方法：" + row);
                assertEquals(null, row.get("route"), "没发请求就不该写请求路径：" + row);
            }
        }

        // D12 的原始症状：listProducts 的 33 行里只有预检那 1 行是真读目录，另外 32 行是本地对齐
        assertEquals(1, mall.hits("GET /api/v1/mall/products"), "预检只读一次目录");
        assertEquals(1, entries.stream().filter(entry -> "listProducts".equals(entry.operation())
                        && Boolean.TRUE.equals(entry.toJson().get("real_http"))).count(),
                "真读目录的流水行只该有预检那一条");
        assertTrue(entries.stream().anyMatch(entry -> "listProducts".equals(entry.operation())
                        && entry.canonicalId() != null
                        && Boolean.TRUE.equals(entry.toJson().get("local_accounting"))),
                "商品对齐行必须显式标成 local_accounting（D12 要求补的字段）");

        // 服务端独立对照：请求数 = real_http 行数 + 退款申请数（退款"申请+完成"两次 HTTP 只占一行流水）
        long refundApplies = eventCounts.getOrDefault("refund_created", 0);
        assertEquals(realHttp + refundApplies, mall.exchanges().size(),
                "商城服务端收到的请求数 = 真实请求行数 + 退款申请数（退款两步只占一行流水）");
    }

    @Test
    @DisplayName("计划同源：MALL_API 与文件模式读同一份计划——同种子/同时间窗/同白名单/同预算，且摘掉的事件类型不属于计划")
    void mallApiPlanIsTheSamePlanAsFileMode() {
        // 这条断言刻意<b>不比逐条事件流</b>：实测（2026-09-11，本机）已证明过滤运行得到的是另一份事件流，
        // 不是不过滤运行的子序列。机制是 FileModeGenerationEngine.write() 的白名单分支
        // 「既不进产物、也不占预算、也不动 counters」（L637-642）：被摘掉的事件不消耗 event_count 预算，
        // 于是同一个序号位置在过滤运行里被后面的事件顶上——同一 event_id 在两边的 event_type 都不一样
        // （实测 E135282f00000054 不过滤是 behavior、过滤后是 order_created）。
        // 这是文件模式既有的预算语义，与 MALL_API 无关；README 的"已发现偏差"一节如实登记了它。
        // 因此这里断言两边真正共享的东西：同一份计划（种子/时间窗/预算/白名单）。
        Set<String> allowed = MallApiGenerationEngine.mallBackedEventTypes(
                adapter().capabilities(mallTarget("http://127.0.0.1:1", ENV_NAME)));
        assertTrue(allowed.contains("order_created") && !allowed.contains("behavior"), allowed.toString());

        GenerationRequest plan = request("run-parity", "none");
        GenerationRequest mallPlan = plan.withEventFilter(allowed);

        // 1) 白名单只摘类型，绝不动种子的可复现键（时间窗/预算/场景/脏数据档位）
        assertEquals(plan.reproducibilityKey(), mallPlan.reproducibilityKey(),
                "摘类型不许改计划的可复现键，否则'同一份计划'就是空话");
        assertEquals(plan.seed(), mallPlan.seed());
        assertEquals(plan.startTime(), mallPlan.startTime());
        assertEquals(plan.endTime(), mallPlan.endTime());
        assertEquals(plan.eventCount(), mallPlan.eventCount());

        // 2) 计划层的事件类型表：文件模式计划 = 全量类型，MALL_API 计划 = 全量类型 ∩ 能力白名单
        Set<String> filePlan = FileModeGenerationEngine.plannedEventTypes(plan);
        Set<String> mallPlanTypes = FileModeGenerationEngine.plannedEventTypes(mallPlan);
        assertTrue(filePlan.containsAll(mallPlanTypes), "MALL_API 的计划类型必须是文件模式计划的子集");
        assertTrue(mallPlanTypes.containsAll(allowed), "能力白名单里的类型必须都在 MALL_API 计划里");
        assertEquals(Set.of("product_updated", "stock_reserved", "stock_released", "stock_changed", "behavior"),
                FileModeGenerationEngine.filteredEventTypes(mallPlan),
                "被摘掉的必须正好是商城公开接口没有对应动作的那几类");
        assertEquals(filePlan, java.util.stream.Stream.concat(mallPlanTypes.stream(),
                        FileModeGenerationEngine.filteredEventTypes(mallPlan).stream())
                .collect(java.util.stream.Collectors.toSet()),
                "被摘掉的类型 + 保留的类型必须正好还原文件模式的完整类型表");

        // 3) 真跑一把过滤计划：产物里不许出现白名单外的类型，且预算按"落进产物"的条数用满
        RecordingSink mallSink = new RecordingSink();
        new FileModeGenerationEngine().run(mallPlan, mallSink, () -> false);
        assertTrue(mallSink.events.stream().allMatch(event -> allowed.contains(event.eventType())),
                "过滤运行的产物里不许出现白名单外的类型");
        assertEquals(EVENT_COUNT, (long) mallSink.events.size(),
                "白名单只决定'生成哪几类'，不缩短预算：落进产物的事件数仍应等于 event_count");
        assertTrue(mallSink.events.stream()
                        .allMatch(event -> !Instant.parse(event.eventTime()).isBefore(START)
                                && !Instant.parse(event.eventTime()).isAfter(END)),
                "被过滤运行的事件时间必须仍落在计划的 time_window 内");
    }

    @Test
    @DisplayName("同种子可复现：同一份计划跑两遍，MALL_API 的类型序列与规范 ID 序列逐条相同")
    void mallApiIsReproducibleForTheSamePlan() throws IOException {
        // 与上一条互补：跨模式不比逐条，但同一模式内必须逐条可复现（§4.2 的 plan_version + seed + time_window）。
        // 注意"可复现"到规范流为止：商城侧的外部 ID 由商城自己生成，不承诺两次运行相同（B-04）。
        mall = new FakeMallServer(TOKEN, null, 60);
        TargetConfig target = mallTarget(mall.baseUrl(), ENV_NAME);
        ReferenceMallHttpAdapter adapter = adapter();

        RecordingSink first = new RecordingSink();
        MallApiGenerationEngine.MallRunOutcome firstOutcome =
                engine.runForTarget(request("run-rep-1", "none"), target, adapter, first, () -> false);
        RecordingSink second = new RecordingSink();
        MallApiGenerationEngine.MallRunOutcome secondOutcome =
                engine.runForTarget(request("run-rep-2", "none"), target, adapter, second, () -> false);

        assertEquals(first.events.stream().map(this::fingerprint).toList(),
                second.events.stream().map(this::fingerprint).toList(),
                "同一份计划（同 seed/时间窗/预算）的规范流必须逐条可复现");
        assertEquals(firstOutcome.outcome().successCount(), secondOutcome.outcome().successCount());
        assertEquals(firstOutcome.dispatch().entries().size(), secondOutcome.dispatch().entries().size(),
                "两次运行的商城调用流水条数必须相同");
    }

    @Test
    @DisplayName("商城补上行为埋点后：behavior 自动进入白名单并逐条真实调用，缺口随之消失")
    void behaviorCapabilityTurnsIntoRealCalls() throws IOException {
        mall = new FakeMallServer(TOKEN, BASE_BEHAVIOR_PATH, 60);
        TargetConfig target = mallTargetWithBehavior(mall.baseUrl(), ENV_NAME);
        ReferenceMallHttpAdapter adapter = adapter();
        assertTrue(MallApiGenerationEngine.mallBackedEventTypes(adapter.capabilities(target)).contains("behavior"),
                "config_json 声明了 behavior_path ⇒ 行为埋点必须是 SUPPORTED");

        RecordingSink sink = new RecordingSink();
        MallApiGenerationEngine.MallRunOutcome outcome =
                engine.runForTarget(request("run-behavior", "none"), target, adapter, sink, () -> false);

        long behaviors = sink.events.stream().filter(event -> "behavior".equals(event.eventType())).count();
        assertTrue(behaviors > 0, "声明了埋点路径就必须真的产生行为事件");
        assertEquals((int) behaviors, mall.hits("POST " + BASE_BEHAVIOR_PATH),
                "行为事件条数必须等于真实埋点调用次数");
        assertEquals(0L, outcome.outcome().failedCount(), "埋点路径存在时不该有失败");
        assertTrue(outcome.dispatch().notes().stream().noneMatch(note -> note.contains("行为埋点")),
                "能力齐了就不该再记缺口：" + outcome.dispatch().notes());
    }

    /** 事件指纹：不含被改写成商城外部 ID 的字段，只留规划产物（ID/类型/时间/traceId） */
    private String fingerprint(CanonicalEvent event) {
        return "%s|%s|%s|%s".formatted(event.eventId(), event.eventType(), event.eventTime(), event.traceId());
    }

    @Test
    @DisplayName("取消信号：文件引擎已有的取消语义在 MALL_API 里同样生效，且不破坏已发生的商城调用")
    void cancelSignalIsHonoured() throws IOException {
        mall = new FakeMallServer(TOKEN, null, 60);
        TargetConfig target = mallTarget(mall.baseUrl(), ENV_NAME);
        RecordingSink sink = new RecordingSink();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        MallApiGenerationEngine.MallRunOutcome outcome = engine.runForTarget(request("run-cancel", "none"),
                target, adapter(), sink, () -> calls.incrementAndGet() > 1);

        assertTrue(outcome.forwardedCount() < EVENT_COUNT,
                "取消后应当提前收尾，实际落盘 " + outcome.forwardedCount());
        assertEquals(outcome.forwardedCount(), sink.events.size(), "落盘条数必须与记账一致");
    }

    // ---------- 2. 响亮失败 ----------

    @Test
    @DisplayName("必备能力缺失：product/user/order 任一不支持就响亮拒绝，绝不降级成文件模式")
    void missingMandatoryCapabilityFailsLoudly() throws IOException {
        mall = new FakeMallServer(TOKEN, null, 60);
        TargetConfig target = mallTarget(mall.baseUrl(), ENV_NAME);

        // ORDER 声明为 ABSENT：能力门必须在任何 HTTP 之前就挡住
        MallTargetAdapter broken = withVerdicts(target, Map.of(MallCapability.ORDER, CapabilityVerdict.ABSENT));

        RecordingSink sink = new RecordingSink();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> engine.runForTarget(request("run-broken", "none"), target, broken, sink, () -> false));
        assertTrue(e.getMessage().contains("order=ABSENT"), "必须点名缺的是哪项能力：" + e.getMessage());
        assertTrue(e.getMessage().contains("不会降级成文件模式"), "必须明确拒绝降级：" + e.getMessage());
        assertTrue(sink.events.isEmpty(), "预检失败不得产出任何事件");
        assertTrue(mall.exchanges().isEmpty(), "能力就不满足时不该对商城发任何请求");

        // 行为/退款缺失不属于"必备能力"：降级 + 记缺口，而不是拒绝
        MallTargetAdapter degradable = withVerdicts(target,
                Map.of(MallCapability.BEHAVIOR, CapabilityVerdict.ABSENT, MallCapability.REFUND,
                        CapabilityVerdict.ABSENT));
        RecordingSink degradedSink = new RecordingSink();
        MallApiGenerationEngine.MallRunOutcome outcome = engine.runForTarget(request("run-degraded", "none"),
                target, degradable, degradedSink, () -> false);
        assertTrue(outcome.forwardedCount() > 0, "退款/行为缺失不妨碍其余链路照跑");
        assertEquals(0, mall.hits("POST " + BASE_BEHAVIOR_PATH), "不许伪造行为埋点调用");
        assertTrue(outcome.outcome().notes().stream().anyMatch(note -> note.contains("行为埋点")),
                "缺口必须写进 notes：" + outcome.outcome().notes());
    }

    @Test
    @DisplayName("凭据不可用：预检的目录读取当场 401，异常里点名凭据引用且不回显令牌")
    void missingCredentialFailsLoudly() throws IOException {
        // 这台"商城"要的是别的令牌：声明出来的能力是 SUPPORTED（引用名有值），
        // 真实调用却会被网关 401 掉——这正是"声明 ≠ 实测"要暴露的场面
        mall = new FakeMallServer(TOKEN + "-NOT-THE-CLIENT-ONE", null, 60);
        TargetConfig target = mallTarget(mall.baseUrl(), ENV_NAME);

        RecordingSink sink = new RecordingSink();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> engine.runForTarget(request("run-nocred", "none"), target, adapter(), sink, () -> false));
        assertTrue(e.getMessage().contains("预检失败"), "必须点明发生在预检：" + e.getMessage());
        assertTrue(e.getMessage().contains(ENV_NAME), "必须点名凭据引用：" + e.getMessage());
        assertFalse(e.getMessage().contains(TOKEN), "异常信息绝不能回显令牌值");
        assertTrue(sink.events.isEmpty(), "预检失败不得产出任何事件");
        assertTrue(mall.statuses().contains(401), "真实商城网关必须拒掉凭据不对的调用：" + mall.statuses());
        assertEquals(0, mall.orderCount(), "失败发生在任何写操作之前");

        // 引用名本身取不到值：能力表只能是 UNDETERMINED，同样在调用商城之前就响亮拒绝
        try (FakeMallServer other = new FakeMallServer(TOKEN, null, 60)) {
            RecordingSink otherSink = new RecordingSink();
            IllegalStateException notDeclared = assertThrows(IllegalStateException.class,
                    () -> engine.runForTarget(request("run-noref", "none"),
                            mallTarget(other.baseUrl(), "GENERATOR_UT_ABSENT_TOKEN"), adapter(), otherSink,
                            () -> false));
            assertTrue(notDeclared.getMessage().contains("UNDETERMINED"), notDeclared.getMessage());
            assertTrue(other.exchanges().isEmpty(), "凭据取不到值时不该去打扰商城");
        }
    }

    @Test
    @DisplayName("目录不足：商品数少于计划需要的商品池时响亮拒绝，并说清用哪条规则算出来的")
    void insufficientCatalogFailsLoudly() throws IOException {
        mall = new FakeMallServer(TOKEN, null, FileModeGenerationEngine.productsFor(EVENT_COUNT) - 1);
        TargetConfig target = mallTarget(mall.baseUrl(), ENV_NAME);
        RecordingSink sink = new RecordingSink();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> engine.runForTarget(request("run-small", "none"), target, adapter(), sink, () -> false));
        assertTrue(e.getMessage().contains("商城目录商品"), e.getMessage());
        assertTrue(e.getMessage().contains("event_count=" + EVENT_COUNT), "必须说清是按哪条规则算出来的：" + e.getMessage());
        assertTrue(sink.events.isEmpty(), "拒绝必须发生在写入之前");

        // 目录里没有在售商品：另一条独立的拒绝分支
        try (FakeMallServer empty = new FakeMallServer(TOKEN, null, 0)) {
            IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                    () -> engine.runForTarget(request("run-empty", "none"),
                            mallTarget(empty.baseUrl(), ENV_NAME), adapter(), new RecordingSink(), () -> false));
            assertTrue(none.getMessage().contains("没有在售商品"), none.getMessage());
        }
    }

    @Test
    @DisplayName("脏数据档位：MALL_API 直接拒绝，理由写清（脏样本只属于文件模式）")
    void dirtyProfileIsRejectedLoudly() throws IOException {
        mall = new FakeMallServer(TOKEN, null, 60);
        TargetConfig target = mallTarget(mall.baseUrl(), ENV_NAME);
        RecordingSink sink = new RecordingSink();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> engine.runForTarget(request("run-dirty", "light"), target, adapter(), sink, () -> false));
        assertTrue(e.getMessage().contains("脏数据档位 light"), e.getMessage());
        assertTrue(mall.exchanges().isEmpty(), "拒绝必须发生在任何商城调用之前");
    }

    @Test
    @DisplayName("run(request, sink, cancelled)：直接当文件模式引擎用必须响亮失败，不许静默空跑")
    void plainRunIsRefused() {
        RecordingSink sink = new RecordingSink();
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> engine.run(request("run-plain", "none"), sink, () -> false));
        assertTrue(e.getMessage().contains("runForTarget"), e.getMessage());
        assertTrue(sink.events.isEmpty());
    }

    @Test
    @DisplayName("未知事件类型：派发表里没有的事件类型抛 IllegalArgumentException，绝不猜一个接口去发")
    void unknownEventTypeIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MallDispatchPlan.of("not_a_real_event_type"));
        assertTrue(e.getMessage().contains("not_a_real_event_type"), e.getMessage());
    }

    @Test
    @DisplayName("规范 ID → 外部 ID 的改写：payload 里出现商城不认识的自造 ID 时必须响亮失败")
    void dispatchRequiresRealCatalogEntry() throws IOException {
        mall = new FakeMallServer(TOKEN, null, 60);
        TargetConfig target = mallTarget(mall.baseUrl(), ENV_NAME);
        // 商品目录只有 1 件，而计划需要 productsFor(400)=20 件：这里必须拒绝而不是"顺序错位地"硬发
        try (FakeMallServer tiny = new FakeMallServer(TOKEN, null, 1)) {
            RecordingSink sink = new RecordingSink();
            assertThrows(IllegalArgumentException.class, () -> engine.runForTarget(request("run-tiny", "none"),
                    mallTarget(tiny.baseUrl(), ENV_NAME), adapter(), sink, () -> false));
            assertTrue(sink.events.isEmpty());
        }
        assertNotNull(target);
    }

    @Test
    @DisplayName("商城真实拒绝订单时绝不许报 SUCCESS：failed_count>0 必须变成响亮的运行失败")
    void mallRejectionNeverEndsInSuccess() throws IOException {
        // 真机上就是这么发生的：8090 的库存被真实扣减，createOrder 开始回 400 INSUFFICIENT_STOCK。
        // 文件引擎会在 sink.write 外面吞掉 RuntimeException，所以"运行结果自己报成功"这件事
        // 只能由 MALL_API 引擎在跑完之后按 failed 数收口——这条用例锁的就是那个收口。
        // 目录要够 productsFor(400)=20 件：这条用例验的是"订单被拒之后的收口"，不是目录不足
        mall = new FakeMallServer(TOKEN, null, 100);
        TargetConfig target = mallTarget(mall.baseUrl(), ENV_NAME);
        MallTargetAdapter rejecting = rejectingOrderAdapter();
        int rejected = 0;
        int ranToCompletion = 0;
        for (int i = 0; i < 6; i++) {
            try {
                engine.runForTarget(request("run-reject-" + i, "none"), target, rejecting,
                        new RecordingSink(), () -> false);
                ranToCompletion++;
            } catch (MallOperationException e) {
                rejected++;
                assertTrue(e.getMessage().contains("createOrder"), "异常必须点名是哪个操作被拒：" + e.getMessage());
                assertTrue(e.getMessage().contains("INSUFFICIENT_STOCK"), "异常必须带上商城原始错误码：" + e.getMessage());
                assertTrue(e.getMessage().contains("已写入规范流"), "异常必须报出真实入流条数：" + e.getMessage());
            }
        }
        assertEquals(0, ranToCompletion, "有真实商城失败的运行一次都不许「正常返回」");
        assertEquals(6, rejected, "每一次带真实拒绝的运行都必须响亮失败，一次都不许混成 SUCCESS");

        // 预算封顶：被拒的事件也要占 event_count 的额度，否则文件引擎会一路补产到
        // "成功条数 == event_count"（真机上就是 400 条的计划打了 568 次商城调用）。
        // 注意计数口径：ForwardingSink 只数白名单内的事件，商城拒绝只可能来自真实派发，
        // 所以 attempts == 真派发次数。真机 400 条计划的那次是 232 入流 + 168 被拒 = 400。
        MallOperationException budget = assertThrows(MallOperationException.class,
                () -> engine.runForTarget(request("run-budget", "none"), target, rejecting,
                        new RecordingSink(), () -> false));
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("运行中有 (\\d+) 次商城操作被真实拒绝（已写入规范流 (\\d+) 条）").matcher(budget.getMessage());
        assertTrue(m.find(), "异常里必须同时报出被拒次数与真实入流条数：" + budget.getMessage());
        long failedOps = Long.parseLong(m.group(1));
        long written = Long.parseLong(m.group(2));
        assertTrue(written < EVENT_COUNT, "有拒绝时入流条数必然短于预算，实际 " + written);
        assertTrue(failedOps + written <= EVENT_COUNT,
                "打商城的次数 + 入流条数不得超过 event_count：被拒 %d、入流 %d、预算 %d"
                        .formatted(failedOps, written, EVENT_COUNT));
        assertTrue(failedOps > 0, "这条用例的前提就是订单会被拒");
    }

    @Test
    @DisplayName("D10：引擎抛异常之后，调用方手里的逐类账本仍记着「抛之前真的进了规范流的事件」")
    void eventStatsLedgerSurvivesMallRejection() throws IOException {
        // 真机上的失败形态就是这样：事件已经写进规范流，引擎才因为"有商城拒绝"抛异常。
        // 账本若留在引擎内部，这次运行的逐类分布就随异常一起丢了（event_stats: []）。
        mall = new FakeMallServer(TOKEN, null, 100);
        TargetConfig target = mallTarget(mall.baseUrl(), ENV_NAME);
        RecordingSink sink = new RecordingSink();
        EventStatsRecorder ledger = new EventStatsRecorder();

        assertThrows(MallOperationException.class, () -> engine.runForTarget(request("run-d10-ledger", "none"),
                target, rejectingOrderAdapter(), sink, () -> false, null, ledger));

        Map<String, Long> streamCounts = new TreeMap<>();
        sink.events.forEach(event -> streamCounts.merge(event.eventType(), 1L, Long::sum));
        Map<String, Long> ledgerCounts = new TreeMap<>();
        ledger.snapshot().forEach((type, stat) -> ledgerCounts.put(type, stat.count()));

        assertFalse(sink.events.isEmpty(), "这条用例的前提是失败前真的写进了规范流");
        assertFalse(ledger.isEmpty(), "失败样本的逐类分布不许随异常一起丢");
        // 逐类相等是"不重不漏"的判据：多一条 = 文件引擎那本"尝试账"被共用（重复记账），少一条 = 漏记
        assertEquals(streamCounts, ledgerCounts, "账本必须与规范流逐类相等");
        assertEquals(sink.events.size(), ledger.totalCount(), "账本条数之和必须等于入流条数");
    }

    // ---------- 辅助 ----------

    /**
     * 真适配器 + 真实 HTTP，只在 {@code createOrder} 上忠实转发商城的拒绝。
     *
     * <p>不用打桩替身伪造整条链路：目录读取、建号、HTTP 信封、错误解析都还是真的，
     * 被替换的只是"商城会不会接这一单"这个外部事实。</p>
     */
    private MallTargetAdapter rejectingOrderAdapter() {
        MallTargetAdapter inner = adapter();
        return new MallTargetAdapter() {

            @Override
            public String adapterType() {
                return inner.adapterType();
            }

            @Override
            public TargetCheckResult test(TargetConfig target) {
                return inner.test(target);
            }

            @Override
            public TargetCapabilities capabilities(TargetConfig target) {
                return inner.capabilities(target);
            }

            @Override
            public ProductPage listProducts(TargetConfig target, ProductQuery query) {
                return inner.listProducts(target, query);
            }

            @Override
            public ExternalUser createSyntheticUser(TargetConfig target, UserCommand command) {
                return inner.createSyntheticUser(target, command);
            }

            @Override
            public ExternalOrder createOrder(TargetConfig target, OrderCommand command) {
                throw new MallOperationException("createOrder",
                        "商城拒绝：HTTP 400 code=INSUFFICIENT_STOCK message=库存不足: 1001");
            }

            @Override
            public ExternalOrder pay(TargetConfig target, PayCommand command) {
                return inner.pay(target, command);
            }

            @Override
            public ExternalOrder cancel(TargetConfig target, CancelCommand command) {
                return inner.cancel(target, command);
            }

            @Override
            public ExternalRefund refund(TargetConfig target, RefundCommand command) {
                return inner.refund(target, command);
            }
        };
    }

    private ReferenceMallHttpAdapter adapter() {
        return new ReferenceMallHttpAdapter(name -> {
            String property = System.getProperty(name);
            return property != null ? property : System.getenv(name);
        }, Duration.ofSeconds(5));
    }

    private TargetConfig mallTarget(String baseUrl, String credentialRef) {
        return new TargetConfig(11L, ReferenceMallHttpAdapter.ADAPTER_TYPE, baseUrl, credentialRef, "{}");
    }

    /** 显式声明行为埋点路径的目标：用于验证"商城补上接口后行为事件自动进入白名单" */
    private TargetConfig mallTargetWithBehavior(String baseUrl, String credentialRef) {
        return new TargetConfig(11L, ReferenceMallHttpAdapter.ADAPTER_TYPE, baseUrl, credentialRef,
                "{\"behavior_path\":\"" + BASE_BEHAVIOR_PATH + "\"}");
    }

    private GenerationRequest request(String runId, String dirtyProfile) {
        return new GenerationRequest(runId, "normal", 20260911L, START, END, EVENT_COUNT, 0, dirtyProfile);
    }

    /**
     * 把真实适配器的七个操作原样转发出去，只改写 {@code capabilities()} 的个别判定。
     *
     * <p>这样"能力门"与"真实调用"能在同一条链路上分别验证：能力缺了就必须在发请求之前挡住，
     * 而能力够、凭据不对时必须真的被商城 401 掉——两件事都不许用打桩糊过去。</p>
     */
    private MallTargetAdapter withVerdicts(TargetConfig config, Map<MallCapability, CapabilityVerdict> overrides) {
        MallTargetAdapter inner = adapter();
        TargetCapabilities base = inner.capabilities(config);
        Map<MallCapability, CapabilityVerdict> verdicts = new EnumMap<>(MallCapability.class);
        for (MallCapability capability : MallCapability.values()) {
            verdicts.put(capability, overrides.getOrDefault(capability, base.verdict(capability)));
        }
        TargetCapabilities declared = TargetCapabilities.declared(verdicts);
        return new MallTargetAdapter() {

            @Override
            public String adapterType() {
                return inner.adapterType();
            }

            @Override
            public TargetCheckResult test(TargetConfig target) {
                return inner.test(target);
            }

            @Override
            public TargetCapabilities capabilities(TargetConfig target) {
                return declared;
            }

            @Override
            public ProductPage listProducts(TargetConfig target, ProductQuery query) {
                return inner.listProducts(target, query);
            }

            @Override
            public ExternalUser createSyntheticUser(TargetConfig target, UserCommand command) {
                return inner.createSyntheticUser(target, command);
            }

            @Override
            public void emitBehavior(TargetConfig target, BehaviorCommand command) {
                inner.emitBehavior(target, command);
            }

            @Override
            public ExternalOrder createOrder(TargetConfig target, OrderCommand command) {
                return inner.createOrder(target, command);
            }

            @Override
            public ExternalOrder pay(TargetConfig target, PayCommand command) {
                return inner.pay(target, command);
            }

            @Override
            public ExternalOrder cancel(TargetConfig target, CancelCommand command) {
                return inner.cancel(target, command);
            }

            @Override
            public ExternalRefund refund(TargetConfig target, RefundCommand command) {
                return inner.refund(target, command);
            }
        };
    }

    /** 内存落点：记录事件与统计，不落盘（落盘链路由 GeneratorApiSmokeTest 覆盖） */
    private static final class RecordingSink implements EventSink {

        private final List<CanonicalEvent> events = new ArrayList<>();
        private int flushes;

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
            flushes++;
        }

        @Override
        public ArtifactManifest closeAndBuildManifest() {
            // 契约清单在 record_count>0 时必须给出 min/max event_time（否则分不清"真没有"与"没算出来"）
            String min = events.isEmpty() ? null : events.get(0).eventTime();
            String max = events.isEmpty() ? null : events.get(events.size() - 1).eventTime();
            return new ArtifactManifest("run", "memory://recording-sink", null, 0L, events.size(),
                    min, max, "1.0", true);
        }

        @SuppressWarnings("unused")
        int flushes() {
            return flushes;
        }
    }
}
