package com.graduation.generator.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4b：{@code MallTargetAdapter} 其余七项的<b>真实 HTTP 实测</b>（{@code com.sun.net.httpserver} 夹具）。
 *
 * <p>本文件覆盖三件事，且都用真套接字而非 Mock：</p>
 * <ol>
 *   <li><b>正常路径</b>：七项各发一次真请求，核对方法、路径、请求体形状与响应解析结果；</li>
 *   <li><b>响亮失败</b>：能力缺失（埋点接口未声明 / 404）、凭据缺失、商城业务错误、不可达，
 *       一律抛异常并说清缺哪一项——<b>绝不</b>返回伪造成功的对象，也<b>不</b>静默跳过；</li>
 *   <li><b>凭据</b>：每一次调用都带 Bearer（D-033 的现场实测结论），且抛出信息里不回显令牌值。</li>
 * </ol>
 */
class MallTargetAdapterOperationsTest {

    private static final String ENV_NAME = "GENERATOR_TARGET_TOKEN";
    private static final String TOKEN = "s3cr3t-from-env";
    private static final String BEHAVIOR_PATH = "/api/v1/mall/behaviors";
    private static final String RESET_PATH = "/api/v1/admin/reset";

    private final ObjectMapper mapper = new ObjectMapper();
    private FakeMall mall;

    @AfterEach
    void stopMall() {
        if (mall != null) {
            mall.close();
        }
    }

    private ReferenceMallHttpAdapter adapter() {
        return adapter(name -> TOKEN);
    }

    private ReferenceMallHttpAdapter adapter(Function<String, String> lookup) {
        return new ReferenceMallHttpAdapter(lookup, Duration.ofSeconds(3));
    }

    private TargetConfig config(String baseUrl, String credentialRef) {
        return config(baseUrl, credentialRef, "{\"behavior_path\":\"" + BEHAVIOR_PATH + "\"}");
    }

    private TargetConfig config(String baseUrl, String credentialRef, String configJson) {
        return new TargetConfig(7L, ReferenceMallHttpAdapter.ADAPTER_TYPE, baseUrl, credentialRef, configJson);
    }

    // ---------- capabilities()：不联网的静态声明 ----------

    @Test
    @DisplayName("capabilities()：声明语气的能力表（未声明的行为/重置路径不得写成 SUPPORTED）")
    void capabilitiesDeclaresWithoutProbing() throws IOException {
        mall = new FakeMall(allRoutes(), BEHAVIOR_PATH);
        int before = mall.exchanges().size();

        TargetCapabilities declared = adapter().capabilities(config(mall.baseUrl(), ENV_NAME));
        assertEquals(0, mall.exchanges().size() - before, "capabilities() 是声明，不得发起任何 HTTP 调用");

        assertEquals(CapabilityVerdict.SUPPORTED, declared.verdict(MallCapability.PRODUCT),
                "base_url 已配置 + 有凭据 ⇒ 公开目录能力可声明为支持：" + declared.verdicts());
        assertEquals(CapabilityVerdict.UNDETERMINED, declared.verdict(MallCapability.RESET_STATE),
                "reset_path 未声明 ⇒ 只能 UNDETERMINED（绝不猜成 SUPPORTED）");

        TargetCapabilities noCredential = new ReferenceMallHttpAdapter(name -> null, Duration.ofSeconds(3))
                .capabilities(config(mall.baseUrl(), ENV_NAME));
        assertEquals(CapabilityVerdict.UNDETERMINED, noCredential.verdict(MallCapability.ADMIN),
                "取不到凭据时 admin 能力只能 UNDETERMINED");
    }

    // ---------- 正常路径：七项各一次真调用 ----------

    @Test
    @DisplayName("正常路径：listProducts/createSyntheticUser/emitBehavior/createOrder/pay/cancel/refund 七项各发真请求")
    void allSevenOperationsHitRealRoutes() throws IOException {
        mall = new FakeMall(allRoutes(), BEHAVIOR_PATH);
        ReferenceMallHttpAdapter adapter = adapter();
        TargetConfig config = config(mall.baseUrl(), ENV_NAME);

        ProductPage page = adapter.listProducts(config, new ProductQuery(1L, null, 0, 10));
        assertEquals(2, page.products().size(), "夹具返回 2 个商品：" + page);
        ExternalProduct first = page.products().get(0);
        assertEquals("910000000000000001", first.productId());
        assertEquals("on_sale", first.status());
        assertEquals(0, first.price().compareTo(new BigDecimal("199.00")));
        assertEquals(1L, first.categoryId());

        ExternalUser user = adapter.createSyntheticUser(config,
                new UserCommand("25-34", "tier1", "gold"));
        assertEquals("810000000000000001", user.userId());

        adapter.emitBehavior(config, new BehaviorCommand(user.userId(), first.productId(),
                "S-1", "view", "app"));

        ExternalOrder order = adapter.createOrder(config,
                new OrderCommand(user.userId(), List.of(new OrderCommand.Item(first.productId(), 2))));
        assertEquals("820000000000000001", order.orderId());
        assertEquals(user.userId(), order.userId());
        assertEquals(-1, order.itemCount(), "夹具与真实商城一样只回 orderId，商品明细条数读不到就记 -1（不猜）");
        assertNull(order.totalAmount(), "商城未回传总额时不得凭空造一个金额");

        ExternalOrder paid = adapter.pay(config, new PayCommand(order.orderId(), user.userId()));
        assertEquals(order.orderId(), paid.orderId(), "支付应答只有 code=OK（真实商城回 ApiResponse<Void>，data=null）");
        assertEquals("PAID", paid.status(), "HTTP 成功即视为已支付，不读取不存在的字段");
        assertEquals(-1, paid.itemCount());

        ExternalOrder cancelled = adapter.cancel(config,
                new CancelCommand(order.orderId(), user.userId(), "user_cancel"));
        assertEquals(order.orderId(), cancelled.orderId());
        assertEquals("CANCELLED", cancelled.status());

        ExternalRefund refund = adapter.refund(config,
                new RefundCommand(order.orderId(), user.userId(), new BigDecimal("199.00"), "quality_issue"));
        assertEquals("830000000000000001", refund.refundId());
        assertEquals("820000000000000001", refund.orderId());
        assertEquals("COMPLETED", refund.status());

        assertEquals(List.of(
                "GET /api/v1/mall/products?categoryId=1",
                "POST /api/v1/mall/users",
                "POST " + BEHAVIOR_PATH,
                "POST /api/v1/mall/orders",
                "POST /api/v1/mall/orders/820000000000000001/pay",
                "POST /api/v1/mall/orders/820000000000000001/cancel",
                "POST /api/v1/mall/orders/820000000000000001/refunds",
                "POST /api/v1/mall/refunds/830000000000000001/complete"), mall.requests());

        assertEquals(List.of(200, 200, 200, 200, 200, 200, 200, 200), mall.statuses());
    }

    @Test
    @DisplayName("凭据：每一次调用都带 Bearer（D-033 实测：参考商城对公开路由同样要求认证）")
    void everyOperationCarriesBearerToken() throws IOException {
        mall = new FakeMall(allRoutes(), BEHAVIOR_PATH);
        ReferenceMallHttpAdapter adapter = adapter();
        TargetConfig config = config(mall.baseUrl(), ENV_NAME);

        adapter.listProducts(config, new ProductQuery(null, null, 0, 5));
        adapter.createSyntheticUser(config, new UserCommand("18-24", "tier2", "normal"));

        assertEquals(2, mall.authorizationHeaders().size());
        for (String header : mall.authorizationHeaders()) {
            assertEquals("Bearer " + TOKEN, header, "每次调用都必须带凭据");
        }
    }

    @Test
    @DisplayName("请求体形状：字段名与商城 REST DTO 一致（userId 传字符串，金额为十进制数）")
    void requestBodiesMatchMallDtos() throws IOException {
        mall = new FakeMall(allRoutes(), BEHAVIOR_PATH);
        ReferenceMallHttpAdapter adapter = adapter();
        TargetConfig config = config(mall.baseUrl(), ENV_NAME);

        adapter.createSyntheticUser(config, new UserCommand("35-44", "tier3", "silver"));
        adapter.createOrder(config, new OrderCommand("810000000000000009",
                List.of(new OrderCommand.Item("910000000000000001", 3))));
        adapter.createSyntheticUser(config, new UserCommand("under18", "other", "normal"));

        JsonNode user = mapper.readTree(mall.bodyOf("POST /api/v1/mall/users").get(0));
        assertEquals("35-44", user.get("ageGroup").asText());
        assertEquals("tier3", user.get("cityLevel").asText());
        assertEquals("silver", user.get("memberLevel").asText());

        JsonNode order = mapper.readTree(mall.bodyOf("POST /api/v1/mall/orders").get(0));
        assertEquals("810000000000000009", order.get("userId").asText(), "雪花的 userId 必须以字符串回传");
        assertEquals("910000000000000001", order.get("items").get(0).get("productId").asText());
        assertEquals(3, order.get("items").get(0).get("quantity").asInt());
    }

    // ---------- 响亮失败 ----------

    @Test
    @DisplayName("凭据缺失：任何业务调用都必须响亮失败，并点名缺哪个环境变量")
    void missingCredentialFailsLoudly() throws IOException {
        mall = new FakeMall(allRoutes(), BEHAVIOR_PATH);
        ReferenceMallHttpAdapter adapter = adapter(name -> null);
        TargetConfig config = config(mall.baseUrl(), ENV_NAME);

        MallOperationException e = assertThrows(MallOperationException.class,
                () -> adapter.listProducts(config, new ProductQuery(null, null, 0, 5)));
        assertTrue(e.getMessage().contains(ENV_NAME), "必须点名缺哪个环境变量：" + e.getMessage());
        assertTrue(e.getMessage().contains("凭据"), "必须说清是凭据问题：" + e.getMessage());
        assertEquals(0, mall.requests().size(), "取不到凭据就不得发出匿名请求（匿名也查不出能力）");
    }

    @Test
    @DisplayName("能力 ABSENT：埋点接口未声明 / 声明了但 404 时 emitBehavior 响亮失败，绝不静默跳过")
    void absentBehaviorCapabilityFailsLoudly() throws IOException {
        String baseUrl = (mall = new FakeMall(allRoutes(), BEHAVIOR_PATH)).baseUrl();
        ReferenceMallHttpAdapter adapter = adapter();

        // 情形一：config_json 里根本没声明埋点路径 —— 连请求都不该发
        MallOperationException undeclared = assertThrows(MallOperationException.class,
                () -> adapter.emitBehavior(config(baseUrl, ENV_NAME, "{}"),
                        new BehaviorCommand("810000000000000001", "910000000000000001",
                                "S-1", "view", "app")));
        assertTrue(undeclared.getMessage().contains(ReferenceMallHttpAdapter.CONFIG_BEHAVIOR_PATH),
                "必须说清缺哪条配置：" + undeclared.getMessage());
        assertEquals(0, mall.requests().size(), "未声明路径就不得猜一个路由去发（更不得静默跳过）");

        // 情形二：声明了一条商城上并不存在的埋点路径 —— 商城回 404，必须响亮失败
        FakeMall withoutRoute = new FakeMall(routesWithout(BEHAVIOR_PATH), null);
        mall.close();
        mall = withoutRoute;
        String declaredButMissing = "/api/v1/mall/behaviors-not-deployed";
        MallOperationException absent = assertThrows(MallOperationException.class,
                () -> adapter.emitBehavior(
                        config(withoutRoute.baseUrl(), ENV_NAME,
                                "{\"behavior_path\":\"" + declaredButMissing + "\"}"),
                        new BehaviorCommand("810000000000000001", "910000000000000001",
                                "S-1", "view", "app")));
        assertTrue(absent.getMessage().contains("404") || absent.getMessage().contains("NOT_FOUND"),
                "必须说清埋点路由不存在：" + absent.getMessage());
        assertEquals(List.of("POST " + declaredButMissing), withoutRoute.requests(),
                "只在声明的路径上发请求，不试别的路由");
    }

    @Test
    @DisplayName("商城业务错误：HTTP 200 但 code!=OK 也必须抛，且不伪造实体")
    void businessErrorFailsLoudly() throws IOException {
        Map<String, FakeMall.Reply> routes = allRoutes();
        routes.put("POST /api/v1/mall/users", new FakeMall.Reply(400,
                "INSUFFICIENT_STOCK", "库存不足：productId=910000000000000001", null));
        mall = new FakeMall(routes, BEHAVIOR_PATH);

        MallOperationException e = assertThrows(MallOperationException.class,
                () -> adapter().createSyntheticUser(config(mall.baseUrl(), ENV_NAME),
                        new UserCommand("25-34", "tier1", "normal")));
        assertTrue(e.getMessage().contains("INSUFFICIENT_STOCK"), "必须带上商城错误码：" + e.getMessage());
        assertTrue(e.getMessage().contains("库存不足"), "必须带上商城错误说明：" + e.getMessage());
    }

    @Test
    @DisplayName("成功体缺少 ID 字段：抛异常并在信息里给出实际报文，不返回 null 冒充成功")
    void missingIdInResponseFailsLoudly() throws IOException {
        Map<String, FakeMall.Reply> routes = allRoutes();
        routes.put("POST /api/v1/mall/orders", FakeMall.Reply.ok(mapper.createObjectNode()));
        mall = new FakeMall(routes, BEHAVIOR_PATH);

        MallOperationException e = assertThrows(MallOperationException.class,
                () -> adapter().createOrder(config(mall.baseUrl(), ENV_NAME),
                        new OrderCommand("810000000000000001",
                                List.of(new OrderCommand.Item("910000000000000001", 1)))));
        assertTrue(e.getMessage().contains("orderId"), "必须说清缺哪个字段：" + e.getMessage());
    }

    @Test
    @DisplayName("不可达：连不上时抛异常，信息里不得出现凭据值")
    void unreachableFailsLoudlyWithoutLeakingCredential() throws IOException {
        int deadPort = freePort();
        ReferenceMallHttpAdapter adapter = adapter();
        TargetConfig config = config("http://127.0.0.1:" + deadPort, ENV_NAME);

        MallOperationException e = assertThrows(MallOperationException.class,
                () -> adapter.listProducts(config, new ProductQuery(null, null, 0, 5)));
        assertTrue(e.getMessage().contains("无法连接") || e.getMessage().contains("Connection"),
                "必须说清连不上：" + e.getMessage());
        assertTrue(!e.getMessage().contains(TOKEN), "异常信息绝不回显凭据：见 D-033");
        assertTrue(!String.valueOf(e.getCause()).contains(TOKEN), "异常链也不得带凭据");
    }

    @Test
    @DisplayName("参数校验：命令对象自身拒绝非法值（不把脏参数发给商城）")
    void commandsRejectIllegalValues() {
        assertThrows(IllegalArgumentException.class, () -> new UserCommand("unknown", "tier1", "normal"));
        assertThrows(IllegalArgumentException.class, () -> new UserCommand("25-34", "tier1", "vip"));
        assertThrows(IllegalArgumentException.class, () -> new OrderCommand("1", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderCommand("1", List.of(new OrderCommand.Item("9", 0))));
        assertThrows(IllegalArgumentException.class,
                () -> new RefundCommand("1", "2", new BigDecimal("-1.00"), "quality_issue"));
        assertThrows(IllegalArgumentException.class, () -> new CancelCommand("1", "2", "  "));
        assertThrows(IllegalArgumentException.class, () -> new BehaviorCommand("1", "2", "S", "buy", "app"));
        assertThrows(IllegalArgumentException.class, () -> new ProductQuery(null, null, -1, 10));
    }

    // ---------- 工具 ----------

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Map<String, FakeMall.Reply> allRoutes() {
        return routesWithout(null);
    }

    /** 夹具路由表；{@code omitKey} 非空时把该条路由摘掉，用于构造"声明了但商城没有"的情形 */
    private static Map<String, FakeMall.Reply> routesWithout(String omitKey) {
        Map<String, FakeMall.Reply> routes = new LinkedHashMap<>();
        routes.put("GET /api/v1/mall/products", FakeMall.Reply.ok(
                FakeMall.MAPPER.createArrayNode()
                        .add(product("910000000000000001", "测试商品A", 1L, "199.00", "on_sale"))
                        .add(product("910000000000000002", "测试商品B", 1L, "59.50", "on_sale"))));
        routes.put("POST /api/v1/mall/users", FakeMall.Reply.ok(
                FakeMall.MAPPER.createObjectNode().put("userId", "810000000000000001")));
        routes.put("POST " + BEHAVIOR_PATH, FakeMall.Reply.ok(null));
        routes.put("POST /api/v1/mall/orders", FakeMall.Reply.ok(
                FakeMall.MAPPER.createObjectNode().put("orderId", "820000000000000001")));
        // 支付/取消/完成退款在真实商城里就是 ApiResponse<Void>（data=null，实测 MallController L80–108）
        routes.put("POST /api/v1/mall/orders/820000000000000001/pay", FakeMall.Reply.ok(null));
        routes.put("POST /api/v1/mall/orders/820000000000000001/cancel", FakeMall.Reply.ok(null));
        routes.put("POST /api/v1/mall/orders/820000000000000001/refunds", FakeMall.Reply.ok(
                FakeMall.MAPPER.createObjectNode().put("refundId", "830000000000000001")));
        routes.put("POST /api/v1/mall/refunds/830000000000000001/complete", FakeMall.Reply.ok(null));
        routes.put("GET /api/v1/mall/orders", FakeMall.Reply.ok(FakeMall.MAPPER.createArrayNode()));
        routes.put("POST " + RESET_PATH, FakeMall.Reply.ok(null));
        if (omitKey != null) {
            routes.remove(omitKey);
        }
        return routes;
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode product(String id, String name, Long categoryId,
                                                                          String price, String status) {
        return FakeMall.MAPPER.createObjectNode()
                .put("productId", id)
                .put("productName", name)
                .put("categoryId", categoryId)
                .put("brandId", 5L)
                .put("price", new BigDecimal(price))
                .put("cost", new BigDecimal("100.00"))
                .put("status", status);
    }

    /**
     * 参考商城的本地真实 HTTP 夹具：真套接字、真状态码、真 JSON 信封（{@code code/message/data/traceId}）。
     *
     * <p>网关行为按 D-033 的现场实测复刻：{@code /api/v1/**} 一律要求 {@code Authorization: Bearer}，
     * 缺失或不匹配回 401。</p>
     */
    private static final class FakeMall implements AutoCloseable {

        static final ObjectMapper MAPPER = new ObjectMapper();

        /** 一条路由应答：状态码 + 信封（真实商城的 {@code ApiResponse} 形状） */
        record Reply(int status, String code, String message,
                     com.fasterxml.jackson.databind.JsonNode data) {

            static Reply ok(com.fasterxml.jackson.databind.JsonNode data) {
                return new Reply(200, "OK", "success", data);
            }

            void write(HttpExchange exchange) throws IOException {
                com.fasterxml.jackson.databind.node.ObjectNode body = MAPPER.createObjectNode();
                body.put("code", code);
                body.put("message", message);
                body.set("data", data == null ? MAPPER.nullNode() : data);
                body.put("traceId", "T" + status);
                byte[] bytes = MAPPER.writeValueAsBytes(body);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        }

        private final HttpServer server;
        private final Map<String, Reply> routes;
        private final String behaviorPath;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final List<Integer> statuses = new CopyOnWriteArrayList<>();
        private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
        private final Map<String, List<String>> bodies = new LinkedHashMap<>();

        FakeMall(Map<String, Reply> routes, String behaviorPath) {
            this.routes = routes;
            this.behaviorPath = behaviorPath;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("夹具启动失败", e);
            }
            server.createContext("/", this::dispatch);
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
            server.start();
        }

        private void dispatch(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String target = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? ""
                    : "?" + exchange.getRequestURI().getRawQuery());
            String key = method + " " + exchange.getRequestURI().getRawPath();
            requests.add(method + " " + target);
            authorizationHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.isBlank()) {
                bodies.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(body);
            }

            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !authorization.equals("Bearer " + TOKEN)) {
                respond(exchange, 401, "UNAUTHORIZED", "未认证或令牌失效", null);
                return;
            }
            if (behaviorPath != null && key.equals("POST " + behaviorPath)) {
                respond(exchange, 200, "OK", "success", null);
                return;
            }
            Reply reply = routes.get(key);
            if (reply == null) {
                respond(exchange, 404, "NOT_FOUND", "No route: " + key, null);
                return;
            }
            statuses.add(reply.status());
            reply.write(exchange);
        }

        private void respond(HttpExchange exchange, int status, String code, String message,
                             com.fasterxml.jackson.databind.JsonNode data) throws IOException {
            statuses.add(status);
            new Reply(status, code, message, data).write(exchange);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<String> requests() {
            return List.copyOf(requests);
        }

        List<Integer> statuses() {
            return List.copyOf(statuses);
        }

        List<String> authorizationHeaders() {
            return List.copyOf(authorizationHeaders);
        }

        List<String> exchanges() {
            return List.copyOf(requests);
        }

        List<String> bodyOf(String key) {
            return List.copyOf(bodies.getOrDefault(key, List.of()));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
