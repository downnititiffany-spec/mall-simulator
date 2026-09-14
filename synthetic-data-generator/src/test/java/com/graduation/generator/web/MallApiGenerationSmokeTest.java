package com.graduation.generator.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.generator.adapter.CapabilityVerdict;
import com.graduation.generator.adapter.MallCapability;
import com.graduation.generator.adapter.ReferenceMallHttpAdapter;
import com.graduation.generator.adapter.TargetCapabilities;
import com.graduation.generator.contract.Artifact;
import com.graduation.generator.engine.GenerationRequest;
import com.graduation.generator.engine.MallApiGenerationEngine;
import com.graduation.generator.fixture.FakeMallServer;
import com.graduation.generator.meta.GeneratorMetaStore;
import com.graduation.generator.meta.GeneratorMetaStore.PlanRow;
import com.graduation.generator.web.dto.GeneratorApiDtos.ArtifactView;
import com.graduation.generator.web.dto.GeneratorApiDtos.RunStarted;
import com.graduation.generator.web.dto.GeneratorApiDtos.RunView;
import com.graduation.generator.web.dto.GeneratorApiDtos.StartRunRequest;
import com.graduation.generator.web.dto.GeneratorApiDtos.TargetRequest;
import com.graduation.generator.web.dto.GeneratorApiDtos.TargetView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import com.graduation.itguard.GeneratorIsolationTestConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * S4b §4.4 MALL_API 主链路的<b>真实端到端</b>验收：真 HTTP 客户端 → 真服务 → 真 MALL_API 引擎 →
 * 真 {@code ReferenceMallHttpAdapter} → 真套接字上的商城夹具 → 真文件 → 真 MySQL。
 *
 * <p>"商城"用本地夹具（{@link FakeMallServer}）而不是 8090 那台进程：验收不该依赖某台机器上恰好跑着的进程。
 * 夹具复刻的网关/信封/雪花 ID 行为全部来自现场实测（D-033），而且<b>有状态</b>——订单真的按
 * CREATED→PAID/CANCELLED→REFUNDED 迁移，非法迁移回 409。因此"调用成功"与"商城状态真的变了"是两件事，
 * 本测试分别断言。</p>
 *
 * <p>与 {@code MallApiGenerationEngineTest} 的分工：那边在引擎层证"计划与统计口径和文件模式同源"，
 * 这边证"整条 HTTP 链路真的把事件打到了商城、又原样落进了规范流，并且失败时响亮且不留半截状态"。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"generator.output.root=target/it-mall-output", "generator.cli=",
                "generator.target.probe-timeout-ms=5000"})
@Import(GeneratorIsolationTestConfig.class)
// 分类标记（V25-S02 / K-02）：本类需要真实数据库隔离实例（3307）。
//   * 默认纯测试套件（mvn test）按 pom 的 <excludedGroups>it</excludedGroups> 不选中本类；
//   * 显式集成套件（mvn test -Pisolated-tests）选中本类，缺隔离档案时**硬拒（红）而非 skip**。
@Tag("it")
class MallApiGenerationSmokeTest {

    /** 夹具按商品<b>存在性</b>校验订单，所以目录规模要够探针分页与商品动作（{@code eventCount/20}，下限 4） */
    private static final int PRODUCT_COUNT = 100;

    /**
     * 事件预算：要能把商城公开接口承载的七类事件<b>全都</b>走到。
     *
     * <p>行数越大越能覆盖订单链（支付/取消/退款），但每一条订单链都要真打几次 HTTP，
     * 所以取值是"覆盖七类"与"别把验收跑成压测"之间的折中：{@code usersFor(1000)=83}、
     * {@code productsFor(1000)=50}，扣掉用户/商品池后仍有约 860 条事件留给行为流与订单链。</p>
     */
    private static final long EVENT_COUNT = 1000L;
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-08T00:00:00Z");
    private static final String MODE_MALL = "MALL_API";
    private static final String MODE_FILE = "CANONICAL_EVENT_FILE";

    /**
     * 凭据<b>引用名</b>（落库的只有这个名字）。与系统属性重名是刻意的：凭据解析器先看系统属性再环境变量
     * （见 {@code GeneratorBeans#generatorCredentialResolver}），已经跑起来的 JVM 注入不了环境变量，
     * 自动化验收只能走系统属性这一档——它不改变"凭据只按引用名取、绝不落库/落盘"的约束。
     */
    private static final String CREDENTIAL_REF = "GENERATOR_IT_MALL_TOKEN";
    private static final String TOKEN = "it-token-9f2c1b";

    private static final long RUN_TIMEOUT_MS = 180_000;

    static {
        System.setProperty(CREDENTIAL_REF, TOKEN);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private GeneratorMetaStore store;

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------- 1. 正常路径：真实商城 + 规范流对账 ----------

    @Test
    void mallApiRunDrivesRealMallAndWritesCanonicalStream() throws IOException {
        try (FakeMallServer mall = new FakeMallServer(TOKEN, null, PRODUCT_COUNT)) {
            Plan plan = appendPlan(MODE_MALL, createMallTarget(mall.baseUrl(), CREDENTIAL_REF), "none");

            RunView view = runToTerminal(plan);
            assertEquals("SUCCESS", view.status(), "MALL_API 运行必须以 SUCCESS 结束：" + view.error());
            assertEquals(0L, view.failedCount(), "商城侧不应有失败操作");
            assertNotNull(view.checksum(), "校验和必须由真实制品算出");

            // ① 目录探针真的发过一次 GET /products，且每一次调用都带凭据（D-033）
            assertEquals(1, mall.hits("GET /api/v1/mall/products"), "预检必须真的读一次商品目录");
            assertFalse(mall.statuses().contains(401), "每一次调用都必须带 Bearer，不应出现 401：" + mall.statuses());

            // ② 事件流的类型集合 == 商城公开接口能承载的集合（行为埋点缺失 ⇒ 不许出现 behavior 事件）
            List<String> lines = readEventStream(view.runId());
            assertEquals(view.successCount(), lines.size(),
                    "规范流行数必须等于运行记录的 success_count（成功计数就是真实落盘条数）");
            Map<String, Integer> streamCounts = countBy(lines, "event_type");
            assertEquals(Set.of("user_registered", "product_created", "order_created", "order_paid",
                            "order_cancelled", "refund_created", "refund_completed"),
                    new LinkedHashSet<>(streamCounts.keySet()),
                    "事件类型必须恰好是商城能承载的集合：" + streamCounts.keySet());
            assertFalse(streamCounts.containsKey("behavior"), "商城没有行为埋点接口时绝不允许伪造 behavior 事件");
            for (String absent : List.of("product_updated", "stock_reserved", "stock_released", "stock_changed")) {
                assertFalse(streamCounts.containsKey(absent), "无对应公开接口的动作不得进入产物：" + absent);
            }

            // ③ 商城侧真的收到了对应操作，且逐类对账相等（不是"大概打了几个请求"）
            assertMallCalls(streamCounts, mall);

            // ④ 商城状态真的迁移了（"返回 200"≠"状态变了"）
            assertEquals(streamCounts.getOrDefault("order_created", 0), mall.orderCount(), "商城订单数必须等于建单数");
            assertEquals(streamCounts.getOrDefault("refund_created", 0), mall.refundCount(), "商城退款单数必须等于申请数");
            Map<String, Integer> histogram = mall.orderStatusHistogram();
            // 订单是<b>有状态</b>的：支付后申请退款，它就离开 PAID 桶进 REFUNDED 桶。
            // 所以"付过的单"= 当前还在 PAID + 后来退了款的，不能直接拿 PAID 桶对 order_paid。
            assertEquals(streamCounts.getOrDefault("order_paid", 0),
                    histogram.getOrDefault("PAID", 0) + histogram.getOrDefault("REFUNDED", 0),
                    "已支付订单数必须对得上（退款单是从 PAID 迁移走的）：" + histogram);
            assertEquals(streamCounts.getOrDefault("order_cancelled", 0), histogram.getOrDefault("CANCELLED", 0),
                    "已取消订单数必须对得上：" + histogram);
            assertEquals(streamCounts.getOrDefault("refund_completed", 0), histogram.getOrDefault("REFUNDED", 0),
                    "已退款订单数必须对得上：" + histogram);

            // ⑤ 规范 ID → 商城外部 ID：落盘的是真实商城雪花 ID
            String externalOrderId = firstOfType(lines, "order_created").path("payload").path("order_id").asText();
            assertTrue(externalOrderId.startsWith("82") && externalOrderId.length() == 19,
                    "落盘的 order_id 必须是商城雪花 ID（19 位）：" + externalOrderId);
            String externalUserId = firstOfType(lines, "user_registered").path("payload").path("user_id").asText();
            assertTrue(externalUserId.startsWith("81") && externalUserId.length() == 19,
                    "落盘的 user_id 必须是商城雪花 ID（19 位）：" + externalUserId);
            String externalProductId = firstOfType(lines, "order_created").path("payload")
                    .path("items").get(0).path("product_id").asText();
            assertTrue(mall.productIds().contains(externalProductId),
                    "订单里的商品必须来自真实目录：" + externalProductId);

            // ⑥ 操作流水制品：与运行记录、规范流三方对账，且绝不回显凭据
            ArtifactView journalArtifact = artifactBySuffix(view.runId(), "operation-journal.jsonl");
            assertNotNull(journalArtifact, "MALL_API 必须留下操作流水制品");
            assertTrue(journalArtifact.synthetic(), "流水也是合成数据制品");
            List<String> journalLines = readLines(localPath(journalArtifact.uri()));
            assertEquals(journalArtifact.recordCount().longValue(), (long) journalLines.size(),
                    "record_count 必须等于实际行数");
            // 契约的 GenerationArtifact 没有 kind 字段，读取体也就没有 kind——所以这里用"落盘字节数"确证它不是空壳；
            // 制品 kind=OPERATION_JOURNAL 由服务层落库（Artifact.KIND_OPERATION_JOURNAL）保证。
            assertEquals(journalArtifact.bytes().longValue(),
                    Files.readAllBytes(localPath(journalArtifact.uri())).length, "bytes 必须等于文件真实大小");
            assertEquals("OPERATION_JOURNAL", Artifact.KIND_OPERATION_JOURNAL);
            Map<String, Integer> journalByOperation = new LinkedHashMap<>();
            int ok = 0;
            int skipped = 0;
            int realHttp = 0;
            int localRows = 0;
            int preflightReads = 0;
            int preflightRealReads = 0;
            int realProductAlignments = 0;
            int localProductAlignments = 0;
            for (String line : journalLines) {
                JsonNode entry = mapper.readTree(line);
                journalByOperation.merge(entry.path("operation").asText(), 1, Integer::sum);
                String status = entry.path("status").asText();
                if ("OK".equals(status)) {
                    ok++;
                } else if ("SKIPPED".equals(status)) {
                    skipped++;
                } else {
                    fail("流水里出现非 OK/SKIPPED 的状态（失败应当在启动阶段就响亮抛错）：" + line);
                }
                assertFalse(entry.path("detail").asText().contains(TOKEN), "流水里绝不允许出现凭据值：" + line);
                // D12：每一行必须自报家门——真发出去的请求（real_http）还是本地对齐记账（local_accounting）
                boolean isReal = entry.path("real_http").asBoolean();
                boolean isLocal = entry.path("local_accounting").asBoolean();
                assertFalse(isReal && isLocal, "一行不可能既是真实调用又是本地记账：" + line);
                assertTrue(entry.has("real_http") && entry.has("local_accounting"),
                        "D12 之后流水必须带 real_http / local_accounting 两列：" + line);
                if (isReal) {
                    realHttp++;
                    assertFalse(entry.path("http_method").isNull() || entry.path("route").isNull(),
                            "真实调用行必须带方法/路径：" + line);
                }
                if (isLocal) {
                    localRows++;
                    assertTrue(entry.path("http_method").isNull() && entry.path("route").isNull(),
                            "本地记账行不许带请求形状（D12 的原始症状就是它带了）：" + line);
                }
                // 预检那次目录读取是"无主体"的读操作（canonicalId 为空），与 product_created 驱动的那次
                // 目录对齐必须分得开——否则"预检只读一次"这条事实在流水里就对不出来。
                if ("listProducts".equals(entry.path("operation").asText())) {
                    if (entry.path("canonical_id").isNull()) {
                        preflightReads++;
                        if (isReal) {
                            preflightRealReads++;
                        }
                    } else if (isReal) {
                        realProductAlignments++;
                    } else if (isLocal) {
                        localProductAlignments++;
                    }
                }
            }
            assertEquals(journalLines.size(), realHttp + localRows + skipped,
                    "三类行必须覆盖每一行（真实调用 / 本地记账 / 能力缺口）：real=" + realHttp
                            + " local=" + localRows + " skipped=" + skipped + " 行数=" + journalLines.size());
            assertEquals(1, preflightReads, "流水必须记录预检那次目录读取（且只记一次）");
            assertEquals(1, preflightRealReads, "预检那次目录读取本身就是真实调用（它才对应商城那次真 HTTP）");
            assertEquals(0, realProductAlignments,
                    "商品对齐行一条都不许标成 real_http（D12 的原始症状）");
            assertEquals(streamCounts.getOrDefault("product_created", 0), localProductAlignments,
                    "每条 product_created 对齐一条本地记账，而不是一次真实目录读取");
            assertEquals(1 + streamCounts.getOrDefault("product_created", 0),
                    journalByOperation.getOrDefault("listProducts", 0),
                    "目录相关流水行 = 预检 1 条 + 每条 product_created 一条：" + journalByOperation);
            long totalEvents = streamCounts.values().stream().mapToLong(Integer::longValue).sum();
            assertEquals(totalEvents, view.successCount(), "规范流条数 = 运行记录的成功数");
            assertEquals(1 + totalEvents - streamCounts.getOrDefault("product_created", 0)
                            - streamCounts.getOrDefault("refund_completed", 0), realHttp,
                    "真实调用条数 = 预检 1 条 + 有公开写接口的事件条数"
                            + "（商品对齐与退款完成复用不发请求；事件计数=" + streamCounts + "）");
            // 被计划层摘掉的事件类型（行为埋点、库存、改价）根本不会走到派发层，
            // 所以流水里一条 SKIPPED 都不该有；缺口写在运行报告的 notes 里（见 ⑦），不写进流水。
            assertEquals(0, skipped, "进不了计划的类型不会到派发层，流水里不该出现 SKIPPED：" + journalLines);
            assertEquals(view.successCount(), ok - 1,
                    "成功操作数 = 规范流条数（扣掉预检那次 listProducts）：ok=" + ok + " success=" + view.successCount());
            assertTrue(mall.hits("POST " + FakeMallServer.DEFAULT_BEHAVIOR_PATH) == 0,
                    "适配器不该去撞那台商城根本不存在的埋点路径");

            // ⑦ 运行报告：MALL_API 的缺口与口径必须写进 notes，而不是留空
            JsonNode report = mapper.readTree(Files.readString(
                    Path.of("target", "it-mall-output", view.runId(), "run-report.json"), StandardCharsets.UTF_8));
            assertEquals("SUCCESS", report.path("status").asText());
            assertEquals(view.successCount(), report.path("success_count").asLong());
            String notes = report.path("notes").toString();
            assertTrue(notes.contains(ReferenceMallHttpAdapter.ADAPTER_TYPE), "报告必须写明 adapter_type：" + notes);
            assertTrue(notes.contains("不承诺同 seed"), "报告必须如实写出 MALL_API 的可复现性口径：" + notes);
            assertTrue(notes.contains("能力缺口"), "报告必须写出因能力缺口跳过的数量：" + notes);
            assertTrue(notes.contains("规范 ID → 商城外部 ID"), "报告必须写出 ID 映射规模：" + notes);
        }
    }

    private static void assertMallCalls(Map<String, Integer> streamCounts, FakeMallServer mall) {
        assertEquals(streamCounts.getOrDefault("user_registered", 0), mall.hits("POST /api/v1/mall/users"),
                "注册用户数必须等于真实建用户调用数");
        assertEquals(streamCounts.getOrDefault("order_created", 0), mall.hits("POST /api/v1/mall/orders"),
                "下单数必须等于真实建单调用数");
        assertEquals(streamCounts.getOrDefault("order_paid", 0),
                mall.hits("POST /api/v1/mall/orders/{orderId}/pay"), "支付数必须等于真实支付调用数");
        assertEquals(streamCounts.getOrDefault("order_cancelled", 0),
                mall.hits("POST /api/v1/mall/orders/{orderId}/cancel"), "取消数必须等于真实取消调用数");
        assertEquals(streamCounts.getOrDefault("refund_created", 0),
                mall.hits("POST /api/v1/mall/orders/{orderId}/refunds"), "退款申请数必须等于真实退款调用数");
        assertEquals(streamCounts.getOrDefault("refund_completed", 0),
                mall.hits(FakeMallServer.REFUND_COMPLETE_PATTERN), "退款完成数必须等于真实完成调用数");
        // 商品事件没有公开建品接口：只允许读目录，绝不允许写商品
        assertEquals(0, mall.hits("POST /api/v1/mall/products"), "商城没有公开建品接口，不得尝试写入");
    }

    // ---------- 2. 响亮失败：凭据缺失 / 未知 adapter_type / 脏档位 ----------

    @Test
    void missingCredentialFailsLoudlyAndLeavesNoRun() throws IOException {
        String absentRef = "GENERATOR_IT_ABSENT_TOKEN_" + UUID.randomUUID().toString().substring(0, 6);
        try (FakeMallServer mall = new FakeMallServer(TOKEN, null, PRODUCT_COUNT)) {
            Plan plan = appendPlan(MODE_MALL, createMallTarget(mall.baseUrl(), absentRef), "none");

            ResponseEntity<String> response = rest.postForEntity("/api/v1/generation-runs",
                    new StartRunRequest(plan.planId(), plan.version()), String.class);
            String body = Objects.requireNonNull(response.getBody());
            // 引用名取不到值时，能力表只能 UNDETERMINED，于是这里当场被拒（INVALID_STATE）；
            // 无论是 400（预检调用失败）还是 409（能力未声明），要的都是同一件事：响亮、点名、不留痕。
            assertTrue(List.of(400, 409).contains(response.getStatusCode().value()),
                    "凭据不可用必须响亮失败，绝不降级成文件模式或假装成功：" + body);
            assertTrue(body.contains(absentRef), "错误必须点名缺哪个凭据引用：" + body);
            assertFalse(body.contains(TOKEN), "错误信息绝不能回显令牌值");
            assertTrue(body.contains("UNDETERMINED") || body.contains("凭据"),
                    "错误必须说清是凭据/能力这一档出的问题：" + body);

            // 商城一次都没被打扰：能力/凭据这一档的失败发生在任何 HTTP 之前
            assertTrue(mall.exchanges().isEmpty(),
                    "凭据引用取不到值时不该去打扰商城：" + mall.exchanges());

            // 预检发生在任何状态落库之前 ⇒ 计划下不该留下半截运行记录
            assertTrue(store.listRunsByPlan(plan.planId(), 10).isEmpty(),
                    "响亮失败不允许留下 PENDING/FAILED 的半截运行记录");
        }
    }

    @Test
    void wrongCredentialFailsLoudlyOnRealHttpCall() throws IOException {
        // 适配器拿得到"某个值"，但商城要的是另一个：声明层面看不出问题（SUPPORTED），
        // 只有真去读目录才会被 401 掉——这正是"预检必须是一次真实调用"的存在理由
        try (FakeMallServer mall = new FakeMallServer(TOKEN + "-NOT-THE-CLIENT-ONE", null, PRODUCT_COUNT)) {
            Plan plan = appendPlan(MODE_MALL, createMallTarget(mall.baseUrl(), CREDENTIAL_REF), "none");

            ResponseEntity<String> response = rest.postForEntity("/api/v1/generation-runs",
                    new StartRunRequest(plan.planId(), plan.version()), String.class);
            String body = Objects.requireNonNull(response.getBody());
            assertEquals(400, response.getStatusCode().value(), "预检真实调用失败必须响亮失败（400）：" + body);
            assertTrue(body.contains(CREDENTIAL_REF), "错误必须点名凭据引用：" + body);
            assertFalse(body.contains(TOKEN), "错误信息绝不能回显令牌值");

            // 预检确实发了一次真实请求——只是被商城网关按 D-033 的 Bearer 规则 401 掉了。
            // 网关在路由之前拒绝，所以要看 REJECTED 计数；正常键会是 0（这正是"没被放行"的证据）。
            assertEquals(1, mall.hits(FakeMallServer.REJECTED_PREFIX + "GET /api/v1/mall/products"),
                    "预检必须真的去读一次目录（失败也要留下到过商城的痕迹）：" + mall.exchanges());
            assertEquals(0, mall.hits("GET /api/v1/mall/products"), "凭据不对时请求不该被放行");
            assertTrue(mall.statuses().contains(401), "凭据不对必须被商城网关拒掉：" + mall.statuses());
            assertEquals(0, mall.orderCount(), "失败发生在任何写操作之前");
            assertTrue(store.listRunsByPlan(plan.planId(), 10).isEmpty(), "失败不得留下运行记录");
        }
    }

    @Test
    void unknownAdapterTypeKeepsFailingLoudly() throws IOException {
        try (FakeMallServer mall = new FakeMallServer(TOKEN, null, PRODUCT_COUNT)) {
            TargetView target = rest.postForObject("/api/v1/targets",
                    new TargetRequest(null, "it-mall-unknown-" + UUID.randomUUID().toString().substring(0, 6),
                            "SOME_OTHER_MALL", mall.baseUrl(), CREDENTIAL_REF, null, null, null, null),
                    TargetView.class);
            assertNotNull(target);
            Plan plan = appendPlan(MODE_MALL, target.id(), "none");

            ResponseEntity<String> response = rest.postForEntity("/api/v1/generation-runs",
                    new StartRunRequest(plan.planId(), plan.version()), String.class);
            assertEquals(400, response.getStatusCode().value(),
                    "未注册的 adapter_type 必须响亮失败，绝不悄悄退回文件模式：" + response.getBody());
            assertTrue(Objects.requireNonNull(response.getBody()).contains(MODE_FILE),
                    "错误必须列出已注册类型：" + response.getBody());
            assertTrue(mall.exchanges().isEmpty(), "取不到适配器时不该对商城发任何请求");
            assertTrue(store.listRunsByPlan(plan.planId(), 10).isEmpty(), "失败不得留下运行记录");
        }
    }

    @Test
    void dirtyProfileIsRefusedInMallApiMode() throws IOException {
        try (FakeMallServer mall = new FakeMallServer(TOKEN, null, PRODUCT_COUNT)) {
            Plan plan = appendPlan(MODE_MALL, createMallTarget(mall.baseUrl(), CREDENTIAL_REF), "light");
            ResponseEntity<String> response = rest.postForEntity("/api/v1/generation-runs",
                    new StartRunRequest(plan.planId(), plan.version()), String.class);
            assertEquals(400, response.getStatusCode().value(),
                    "MALL_API 不能注入脏样本（脏数据会真的写进商城）：" + response.getBody());
            assertTrue(Objects.requireNonNull(response.getBody()).contains("脏数据"),
                    "错误必须点名脏数据档位：" + response.getBody());
            assertTrue(mall.exchanges().isEmpty(), "拒绝必须发生在任何商城调用之前");
            assertTrue(store.listRunsByPlan(plan.planId(), 10).isEmpty(), "失败不得留下运行记录");
        }
    }

    // ---------- 3. 计划口径：MALL_API 与文件模式同源 ----------

    /**
     * 真机 E3 复现：库存被扣完 → 真商城开始拒单 → 运行必须是 FAILED，且计数必须和产物对得上。
     *
     * <p>这条用例锁的是两个已经踩过的坑：(a) 文件引擎会在 sink.write 外面吞掉 RuntimeException，
     * 所以运行<曾经>报着 {@code SUCCESS + failed_count=170} 收场；(b) 失败路径的 {@code success_count}
     * 曾经把清单与操作流水的 record_count 一起加进去，报出比 {@code event_count} 还大的数。</p>
     */
    @Test
    void realMallRejectionIsRecordedAsFailureWithTruthfulCounts() throws IOException {
        // 容量只够 6 单，而 60 条事件的正常档位必然要下更多单：商城会真的开始回 400 INSUFFICIENT_STOCK
        try (FakeMallServer mall = new FakeMallServer(TOKEN, FakeMallServer.DEFAULT_BEHAVIOR_PATH, 8, 6)) {
            Plan plan = appendPlan(MODE_MALL, createMallTarget(mall.baseUrl(), CREDENTIAL_REF), "none", 60L);
            RunView view = runToTerminal(plan);

            assertNotEquals("SUCCESS", view.status(),
                    "有真实商城拒绝的运行绝不能报成功：" + view.error());
            assertEquals("FAILED", view.status(), "运行状态：" + view.error());
            assertNotNull(view.error(), "失败必须带可读原因");
            assertTrue(view.error().contains("INSUFFICIENT_STOCK"),
                    "失败原因必须带上商城原始错误码：" + view.error());
            assertTrue(view.error().contains("createOrder"), "失败原因必须点名被拒的操作：" + view.error());

            // 计数对账：success_count 只能是"真的写进规范流的条数"
            List<String> stream = readEventStream(view.runId());
            assertEquals(stream.size(), view.successCount(),
                    "success_count 必须等于事件流制品里的真实条数（曾经这里把清单/流水也算进去过）");
            assertTrue(view.successCount() < 60L,
                    "有拒绝时入流条数必然短于计划预算，实际 " + view.successCount());
            assertTrue(mall.orderAttempts() <= 60L,
                    "打商城的次数不得越过 event_count 预算，实际 " + mall.orderAttempts());
            assertTrue(mall.orderCount() >= 1, "被拒之前必须真的成功下过单");

            // 流水必须把每次拒绝都记下来（含商城原始错误码），否则"为什么失败"无处可查
            ArtifactView journal = artifactBySuffix(view.runId(), "operation-journal.jsonl");
            assertNotNull(journal, "MALL_API 运行必须留下操作流水制品");
            long failedLines = readLines(localPath(journal.uri())).stream()
                    .filter(line -> "FAILED".equals(json(line).path("status").asText()))
                    .count();
            assertTrue(failedLines > 0, "被拒的调用必须在流水里留下 FAILED 行");
        }
    }

    /**
     * D10：失败运行也必须留下<b>逐类事件分布</b>（{@code generation_event_stat} + 报告 {@code event_stats}）。
     *
     * <p>缺陷形态：MALL_API 引擎在"有商城拒绝"时是<b>在事件已经写进规范流之后</b>才抛异常的
     * （{@code MallApiGenerationEngine.runForTarget} 里 {@code dispatchResult.failed() > 0} 那一支），
     * 运行服务因此拿到 {@code outcome == null} 并早退，这次运行一条逐类统计都不留（{@code event_stats: []}）。
     * 失败样本于是缺了"失败前究竟发生了什么"的分布——而这份分布一直真实存在：规范流里逐条写着。</p>
     *
     * <p>断言口径全部落在<b>制品里真实存在的条数</b>上，不读实现自己的汇总数：逐类条数 == 规范流里该类事件
     * 的条数；逐类金额 == 按契约 payload 口径独立重算的金额。这样"失败路径写成空 / 金额写成 0"都过不去。</p>
     */
    @Test
    void failedRunStillKeepsPerClassEventStats() throws IOException {
        // 同 realMallRejectionIsRecordedAsFailureWithTruthfulCounts：容量只够 6 单 ⇒ 必然出现真实拒单
        try (FakeMallServer mall = new FakeMallServer(TOKEN, FakeMallServer.DEFAULT_BEHAVIOR_PATH, 8, 6)) {
            Plan plan = appendPlan(MODE_MALL, createMallTarget(mall.baseUrl(), CREDENTIAL_REF), "none", 60L);
            RunView view = runToTerminal(plan);
            assertEquals("FAILED", view.status(), "本用例要的正是失败运行：" + view.error());

            // ① 规范流 = 唯一可信的"已发生事实"，逐类条数与金额都从这里独立重算
            List<JsonNode> events = new ArrayList<>();
            for (String line : readEventStream(view.runId())) {
                events.add(json(line));
            }
            assertFalse(events.isEmpty(), "被拒之前真的写进规范流的事件不该消失");
            Map<String, Long> streamCounts = new TreeMap<>();
            Map<String, BigDecimal> streamAmounts = new TreeMap<>();
            for (JsonNode event : events) {
                String type = event.path("event_type").asText();
                streamCounts.merge(type, 1L, Long::sum);
                streamAmounts.merge(type, amountOf(event), BigDecimal::add);
            }
            assertTrue(streamAmounts.getOrDefault("order_created", BigDecimal.ZERO).signum() > 0,
                    "订单金额必须非零，否则'金额一律写 0'也能蒙过下面的对账");

            // ② 库内逐类统计必须非空，且逐类条数/金额与规范流完全相等
            Map<String, GeneratorMetaStore.EventStatRow> persisted = new TreeMap<>();
            for (GeneratorMetaStore.EventStatRow row : store.listEventStats(view.runId())) {
                persisted.put(row.eventType(), row);
            }
            assertFalse(persisted.isEmpty(), "失败运行也必须留下逐类分布（D10：event_stats 不许为空）");
            assertEquals(streamCounts.keySet(), persisted.keySet(), "逐类集合必须与规范流一致");
            for (Map.Entry<String, Long> entry : streamCounts.entrySet()) {
                assertEquals(entry.getValue().longValue(), persisted.get(entry.getKey()).eventCount(),
                        "逐类条数必须等于规范流里的真实条数：" + entry.getKey());
                assertEquals(0, streamAmounts.get(entry.getKey()).compareTo(persisted.get(entry.getKey()).amount()),
                        "逐类金额必须等于按契约口径独立重算的金额：" + entry.getKey()
                                + "，库内 " + persisted.get(entry.getKey()).amount());
            }

            // ③ 报告 JSON 与库内是同一份事实，不许各说各话（报告不注册为制品，按约定路径直接读盘）
            Path reportPath = Path.of("target", "it-mall-output", view.runId(), "run-report.json");
            assertTrue(Files.isRegularFile(reportPath), "失败运行也必须留下运行报告：" + reportPath);
            JsonNode reportJson = json(Files.readString(reportPath, StandardCharsets.UTF_8));
            Map<String, Long> reported = new TreeMap<>();
            for (JsonNode stat : reportJson.path("event_stats")) {
                reported.put(stat.path("event_type").asText(), stat.path("count").asLong());
            }
            assertEquals(streamCounts, reported, "报告里的 event_stats 必须与规范流/库内一致");
            assertEquals(view.successCount(), reported.values().stream().mapToLong(Long::longValue).sum(),
                    "逐类条数之和必须等于 success_count（同一本账）");
            String notes = reportJson.path("notes").toString();
            assertTrue(notes.contains("部分真相"), "失败路径的统计口径必须写进报告 notes：" + notes);
        }
    }

    /** 逐类金额口径（契约 payload 字段）：order_created 取 total_amount，支付/退款取 amount，其余类型无金额 */
    private static BigDecimal amountOf(JsonNode event) {
        String key = switch (event.path("event_type").asText()) {
            case "order_created" -> "total_amount";
            case "order_paid", "refund_created", "refund_completed" -> "amount";
            default -> null;
        };
        JsonNode value = key == null ? null : event.path("payload").path(key);
        return value == null || value.isMissingNode() || value.isNull() ? BigDecimal.ZERO
                : new BigDecimal(value.asText());
    }

    @Test
    void mallApiWhitelistFollowsCapabilitiesNotHardcoding() {
        // 能力三态：只声明商品/用户/订单 ⇒ 只有这三条链路的事件能进计划
        Map<MallCapability, CapabilityVerdict> minimal = new EnumMap<>(MallCapability.class);
        minimal.put(MallCapability.PRODUCT, CapabilityVerdict.SUPPORTED);
        minimal.put(MallCapability.USER, CapabilityVerdict.SUPPORTED);
        minimal.put(MallCapability.ORDER, CapabilityVerdict.SUPPORTED);
        Set<String> minimalTypes = MallApiGenerationEngine.mallBackedEventTypes(
                TargetCapabilities.declared(minimal));
        assertEquals(Set.of("user_registered", "product_created", "order_created", "order_paid",
                "order_cancelled"), minimalTypes,
                "能力表收缩时白名单必须跟着收缩（退款/行为都不该出现）：" + minimalTypes);

        // 补上退款 ⇒ 退款链路进白名单；行为仍未声明 ⇒ 依然不出现
        minimal.put(MallCapability.REFUND, CapabilityVerdict.SUPPORTED);
        Set<String> withRefund = MallApiGenerationEngine.mallBackedEventTypes(
                TargetCapabilities.declared(minimal));
        assertTrue(withRefund.containsAll(Set.of("refund_created", "refund_completed")), withRefund.toString());
        assertFalse(withRefund.contains("behavior"), "行为能力 UNDETERMINED ⇒ 不得进入白名单：" + withRefund);

        // 补上行为 ⇒ behavior 才进来；此时白名单恰好是参考商城夹具能承载的七类
        minimal.put(MallCapability.BEHAVIOR, CapabilityVerdict.SUPPORTED);
        Set<String> full = MallApiGenerationEngine.mallBackedEventTypes(TargetCapabilities.declared(minimal));
        assertEquals(Set.of("user_registered", "product_created", "behavior", "order_created", "order_paid",
                "order_cancelled", "refund_created", "refund_completed"), full, full.toString());

        // 摘掉的四类永远进不来：商城公开接口没有"改价"和"库存预留/释放/入库"
        for (String absent : List.of("product_updated", "stock_reserved", "stock_released", "stock_changed")) {
            assertFalse(full.contains(absent), "无对应公开接口的动作不得进入白名单：" + absent);
        }
    }

    @Test
    void filteredRequestKeepsSeedAndWindow() {
        GenerationRequest base = new GenerationRequest("run-1", "normal", 20260911L, START, END,
                EVENT_COUNT, 0, "none");
        GenerationRequest filtered = base.withEventFilter(Set.of("order_created"));
        assertEquals(base.reproducibilityKey(), filtered.reproducibilityKey(),
                "摘事件类型不得改变 seed/时间窗/事件预算——否则'两个模式读同一份计划'当场失效");
        assertEquals(20260911L, filtered.seed());
        assertEquals(START, filtered.startTime());
        assertEquals(END, filtered.endTime());
        assertEquals(EVENT_COUNT, filtered.eventCount());
        assertTrue(filtered.acceptsEventType("order_created"));
        assertFalse(filtered.acceptsEventType("behavior"));
        assertTrue(base.acceptsEventType("behavior"), "未设白名单的请求必须保持全部允许（向后兼容）");
    }

    // ---------- 辅助：目标 / 计划 / 运行 ----------

    private long createMallTarget(String baseUrl, String credentialRef) {
        TargetView created = rest.postForObject("/api/v1/targets",
                new TargetRequest(null, "it-mall-" + UUID.randomUUID().toString().substring(0, 8),
                        ReferenceMallHttpAdapter.ADAPTER_TYPE, baseUrl, credentialRef, null, null, null,
                        "{\"behavior_path\":\"" + FakeMallServer.DEFAULT_BEHAVIOR_PATH + "\"}"),
                TargetView.class);
        assertNotNull(created);
        assertNotNull(created.id(), "创建目标必须返回 id");
        return created.id();
    }

    private record Plan(String planId, int version) {
    }

    private Plan appendPlan(String mode, long targetId, String dirtyProfile) {
        return appendPlan(mode, targetId, dirtyProfile, EVENT_COUNT);
    }

    private Plan appendPlan(String mode, long targetId, String dirtyProfile, long eventCount) {
        String planId = "it-s4b-" + UUID.randomUUID().toString().substring(0, 8);
        int version = store.appendPlanVersion(new PlanRow(null, planId, 0, mode, targetId, "normal",
                20260911L, START, END, eventCount, 0, dirtyProfile, null));
        assertTrue(version >= 1, "计划版本必须从 1 起");
        return new Plan(planId, version);
    }

    private RunView runToTerminal(Plan plan) {
        ResponseEntity<RunStarted> started = rest.postForEntity("/api/v1/generation-runs",
                new StartRunRequest(plan.planId(), plan.version()), RunStarted.class);
        assertEquals(200, started.getStatusCode().value(), "契约把成功码记录为 200：" + started.getBody());
        String runId = Objects.requireNonNull(started.getBody()).runId();
        assertNotNull(runId);

        long deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS;
        RunView view = rest.getForObject("/api/v1/generation-runs/" + runId, RunView.class);
        while (view != null && !isTerminal(view.status())) {
            if (System.currentTimeMillis() > deadline) {
                fail("运行在 " + RUN_TIMEOUT_MS + "ms 内未达终态，当前状态：" + view.status() + " " + view.error());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待运行终态被中断", e);
            }
            view = rest.getForObject("/api/v1/generation-runs/" + runId, RunView.class);
        }
        assertNotNull(view);
        assertEquals(runId, view.runId());
        return view;
    }

    private static boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    // ---------- 辅助：制品 ----------

    private ArtifactView[] artifactsOf(String runId) {
        ArtifactView[] views = rest.getForObject("/api/v1/generation-runs/" + runId + "/artifacts",
                ArtifactView[].class);
        assertNotNull(views);
        return views;
    }

    private ArtifactView artifactBySuffix(String runId, String suffix) {
        for (ArtifactView view : artifactsOf(runId)) {
            if (view.uri().endsWith(suffix)) {
                return view;
            }
        }
        return null;
    }

    private List<String> readEventStream(String runId) {
        List<String> lines = new ArrayList<>();
        for (ArtifactView view : artifactsOf(runId)) {
            String uri = view.uri();
            if (uri.endsWith(".jsonl") && !uri.endsWith("dirty-samples.jsonl")
                    && !uri.endsWith("operation-journal.jsonl")) {
                lines.addAll(readLines(localPath(uri)));
            }
        }
        assertFalse(lines.isEmpty(), "必须至少有一个事件流制品");
        return lines;
    }

    private Map<String, Integer> countBy(List<String> lines, String field) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String line : lines) {
            counts.merge(json(line).path(field).asText(), 1, Integer::sum);
        }
        return counts;
    }

    private JsonNode firstOfType(List<String> lines, String eventType) {
        for (String line : lines) {
            JsonNode node = json(line);
            if (eventType.equals(node.path("event_type").asText())) {
                return node;
            }
        }
        throw new AssertionError("规范流里找不到事件类型：" + eventType);
    }

    private JsonNode json(String line) {
        try {
            return mapper.readTree(line);
        } catch (IOException e) {
            throw new AssertionError("不是合法 JSON：" + line, e);
        }
    }

    private static Path localPath(String uri) {
        return uri.startsWith("file:") ? Path.of(URI.create(uri)) : Path.of(uri);
    }

    private static List<String> readLines(Path path) {
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败：" + path, e);
        }
    }
}
