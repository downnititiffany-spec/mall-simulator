package com.graduation.generator.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 参考商城的<b>本地真实 HTTP 夹具</b>（真套接字、真状态码、真 JSON 信封）。
 *
 * <p>用途：让 MALL_API 引擎的端到端测试不去依赖"8090 那台正在跑的商城"——CI 或别人机器上没有它，
 * 测试就变成看运气。夹具复刻了现场实测到的三件事：</p>
 * <ol>
 *   <li>网关按 D-033 要求 {@code /api/v1/**} 一律带 {@code Authorization: Bearer <token>}，缺失/不匹配回 401；</li>
 *   <li>应答信封是 {@code {code,message,data,traceId}}，{@code POST /orders} 与 {@code POST /orders/{id}/refunds}
 *       只回 ID 映射，{@code pay}/{@code cancel}/{@code refunds/{id}/complete} 回 {@code data:null}；</li>
 *   <li>ID 是雪花（十进制字符串，19 位），必须按字符串传递。</li>
 * </ol>
 *
 * <p><b>它是有状态的</b>：用户/订单/退款真的被记录、订单真的按 CREATED→PAID/CANCELLED→REFUNDED 迁移。
 * 只回固定应答的夹具无法证明"引擎按顺序、按依赖关系调用"。</p>
 */
public final class FakeMallServer implements AutoCloseable {

    public static final String DEFAULT_BEHAVIOR_PATH = "/api/v1/mall/behaviors";
    public static final String REFUND_COMPLETE_PATTERN = "POST /api/v1/mall/refunds/{refundId}/complete";

    /** 传给构造器的特殊令牌：网关只校验"带了非空 Bearer"，不校验令牌值（用于构造"凭据取值不对"的场面） */
    public static final String ANY_TOKEN = "*";

    /**
     * 被网关 401 拒掉的路由的计数前缀。
     *
     * <p>网关在路由之前就拒绝，因此这条请求不会出现在 {@link #hits(String)} 的正常键里。
     * 但"凭据不对时预检到底有没有真的发一次请求"恰恰是必须可观测的事实——用这个前缀把
     * "到了商城、但被拒"与"压根没发"区分开。</p>
     */
    public static final String REJECTED_PREFIX = "REJECTED ";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern REFUND_COMPLETE = Pattern.compile("^/api/v1/mall/refunds/([^/]+)/complete$");

    private final HttpServer server;
    private final String token;
    private final String behaviorPath;
    private final long orderCapacity;
    private final AtomicLong ordersAccepted = new AtomicLong();

    private final Map<String, Product> products = new LinkedHashMap<>();
    private final Map<String, ObjectNode> users = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> orders = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> refunds = new ConcurrentHashMap<>();
    private final List<String> exchanges = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> bodiesByRoute = new ConcurrentHashMap<>();
    private final Map<String, Integer> countsByRoute = new ConcurrentHashMap<>();
    private final List<Integer> statuses = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    /** 路由键（{@code "METHOD /path"}，动态段写成 {@code {orderId}}）→ 命中次数 */
    private final Map<String, Integer> routeHits = new ConcurrentHashMap<>();

    /** 一条商品记录（只放引擎真正会读的字段） */
    private record Product(String productId, String name, long categoryId, BigDecimal price, String status) {
    }

    /**
     * @param token        网关要求的 Bearer 令牌；{@code null} 表示不校验（用于测 401 的反面），
     *                     {@link #ANY_TOKEN} 表示只校验带了 Bearer 而不校验值
     * @param behaviorPath 公开行为埋点路径；{@code null} 表示这台"商城"没有该接口（复刻参考商城现状：404）
     * @param productCount 目录里的在售商品数
     */
    public FakeMallServer(String token, String behaviorPath, int productCount) throws IOException {
        this(token, behaviorPath, productCount, Long.MAX_VALUE);
    }

    /**
     * @param orderCapacity 这台"商城"总共还接得下多少单；超出后 {@code POST /orders} 回
     *                      {@code 400 INSUFFICIENT_STOCK}。复刻真机 8090 的行为：库存是真实扣减的，
     *                      跑到一半会真的拒单——这是"MALL_API 必须能如实报失败"的唯一可信来源，
     *                      用打桩是验不出来的。
     */
    public FakeMallServer(String token, String behaviorPath, int productCount, long orderCapacity)
            throws IOException {
        this.token = token;
        this.behaviorPath = behaviorPath;
        this.orderCapacity = orderCapacity;
        for (int i = 1; i <= productCount; i++) {
            String id = String.valueOf(910000000000000000L + i);
            products.put(id, new Product(id, "夹具商品" + i, 10L + (i % 5),
                    new BigDecimal("19.90").multiply(new BigDecimal(String.valueOf(i))), "on_sale"));
        }
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "fake-mall");
            thread.setDaemon(true);
            return thread;
        }));
        this.server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String behaviorPath() {
        return behaviorPath;
    }

    // ---------- 观测 ----------

    /** 全部请求行（{@code "METHOD /path"}，按到达顺序） */
    public List<String> exchanges() {
        return List.copyOf(exchanges);
    }

    /** 命中指定路由键的次数；动态段写成 {@code {orderId}} 等占位符 */
    public int hits(String routeKey) {
        return routeHits.getOrDefault(routeKey, 0);
    }

    public List<String> bodiesOf(String routeKey) {
        return List.copyOf(bodiesByRoute.getOrDefault(routeKey, List.of()));
    }

    public List<Integer> statuses() {
        return List.copyOf(statuses);
    }

    public int userCount() {
        return users.size();
    }

    public int orderCount() {
        return orders.size();
    }

    /** 被 {@code POST /orders} 处理过的下单请求总数（含因库存不足被拒的那些） */
    public long orderAttempts() {
        return ordersAccepted.get();
    }

    public int refundCount() {
        return refunds.size();
    }

    public BigDecimal productPrice(String productId) {
        Product product = products.get(productId);
        if (product == null) {
            throw new IllegalArgumentException("夹具商品不存在：" + productId);
        }
        return product.price();
    }

    public BigDecimal orderTotal(String orderId) {
        ObjectNode order = orders.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("夹具订单不存在：" + orderId);
        }
        return new BigDecimal(order.path("totalAmount").asText());
    }

    /** 商城侧订单状态分布（用于对账"支付/取消真的发生了"） */
    public Map<String, Integer> orderStatusHistogram() {
        Map<String, Integer> histogram = new LinkedHashMap<>();
        orders.values().forEach(order -> histogram.merge(order.path("status").asText(), 1, Integer::sum));
        return histogram;
    }

    // ---------- HTTP ----------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchanges.add(method + " " + path);
            bodiesByRoute.computeIfAbsent(method + " " + path, key -> new CopyOnWriteArrayList<>()).add(body);
            countsByRoute.merge(method + " " + path, 1, Integer::sum);

            if (requiresToken() && !hasBearer(exchange)) {
                // 网关在路由之前就把匿名/错令牌挡掉（复刻参考商城行为），所以这里必须单独记一次
                // "被打到过但被拒" —— 否则"凭据不对时预检到底有没有真发请求"这件事在夹具上完全不可观测。
                hit(exchange, REJECTED_PREFIX + method + " " + path);
                respond(exchange, 401, "UNAUTHORIZED", "缺少或错误的 Authorization Bearer 令牌", null);
                return;
            }
            route(exchange, method, path, body);
        } catch (RuntimeException e) {
            respond(exchange, 500, "FIXTURE_ERROR", String.valueOf(e.getMessage()), null);
        }
    }

    /** 是否需要校验 Bearer：{@code null} 完全不校验；{@link #ANY_TOKEN} 只校验"带了 Bearer"不校验值 */
    private boolean requiresToken() {
        return token != null;
    }

    private boolean hasBearer(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Authorization");
        if (headers == null) {
            return false;
        }
        if (ANY_TOKEN.equals(token)) {
            return headers.stream().anyMatch(value -> value.startsWith("Bearer ") && value.length() > "Bearer ".length());
        }
        return headers.stream().anyMatch(value -> value.equals("Bearer " + token));
    }

    private void route(HttpExchange exchange, String method, String path, String body) throws IOException {
        if ("GET".equals(method) && "/api/v1/mall/products".equals(path)) {
            ArrayNode array = MAPPER.createArrayNode();
            String categoryId = queryParam(exchange, "categoryId");
            products.values().stream()
                    .filter(product -> categoryId == null || String.valueOf(product.categoryId()).equals(categoryId))
                    .forEach(product -> {
                        ObjectNode node = MAPPER.createObjectNode();
                        node.put("productId", product.productId());
                        node.put("productName", product.name());
                        node.put("categoryId", product.categoryId());
                        node.put("brandId", 5L);
                        node.put("price", product.price());
                        node.put("cost", product.price().multiply(new BigDecimal("0.6")));
                        node.put("status", product.status());
                        array.add(node);
                    });
            hit(exchange, "GET /api/v1/mall/products");
            respond(exchange, 200, "OK", "success", array);
            return;
        }
        if ("POST".equals(method) && "/api/v1/mall/users".equals(path)) {
            JsonNode request = read(body);
            String userId = nextId("81");
            ObjectNode stored = MAPPER.createObjectNode();
            stored.put("userId", userId);
            stored.set("request", request);
            users.put(userId, stored);
            hit(exchange, "POST /api/v1/mall/users");
            respond(exchange, 200, "OK", "success", MAPPER.createObjectNode().put("userId", userId));
            return;
        }
        if ("POST".equals(method) && behaviorPath != null && behaviorPath.equals(path)) {
            hit(exchange, "POST " + behaviorPath);
            respond(exchange, 200, "OK", "success", null);
            return;
        }
        if ("POST".equals(method) && "/api/v1/mall/orders".equals(path)) {
            JsonNode request = read(body);
            String userId = request.path("userId").asText();
            if (!users.containsKey(userId)) {
                respond(exchange, 404, "NOT_FOUND", "用户不存在：" + userId, null);
                return;
            }
            String orderId = nextId("82");
            if (ordersAccepted.incrementAndGet() > orderCapacity) {
                // 先把"被拒"记进路由命中，再回绝：真机那次 170 条 INSUFFICIENT_STOCK 就是这样发生的
                hit(exchange, "POST /api/v1/mall/orders");
                respond(exchange, 400, "INSUFFICIENT_STOCK",
                        "库存不足: " + request.path("items").path(0).path("productId").asText(), null);
                return;
            }
            ObjectNode order = MAPPER.createObjectNode();
            order.put("orderId", orderId);
            order.put("userId", userId);
            order.put("status", "CREATED");
            order.set("items", request.path("items").deepCopy());
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode item : request.path("items")) {
                Product product = products.get(item.path("productId").asText());
                if (product == null) {
                    respond(exchange, 404, "NOT_FOUND", "商品不存在：" + item.path("productId").asText(), null);
                    return;
                }
                total = total.add(product.price().multiply(new BigDecimal(item.path("quantity").asInt())));
            }
            order.put("totalAmount", total.setScale(2, java.math.RoundingMode.HALF_UP));
            orders.put(orderId, order);
            hit(exchange, "POST /api/v1/mall/orders");
            respond(exchange, 200, "OK", "success", MAPPER.createObjectNode().put("orderId", orderId));
            return;
        }
        Matcher pay = Pattern.compile("^/api/v1/mall/orders/([^/]+)/pay$").matcher(path);
        if ("POST".equals(method) && pay.matches()) {
            ObjectNode order = orders.get(pay.group(1));
            if (order == null) {
                respond(exchange, 404, "NOT_FOUND", "订单不存在：" + pay.group(1), null);
                return;
            }
            if (!"CREATED".equals(order.path("status").asText())) {
                respond(exchange, 409, "INVALID_STATE",
                        "订单当前状态不允许支付：" + order.path("status").asText(), null);
                return;
            }
            order.put("status", "PAID");
            hit(exchange, "POST /api/v1/mall/orders/{orderId}/pay");
            respond(exchange, 200, "OK", "success", null);
            return;
        }
        Matcher cancel = Pattern.compile("^/api/v1/mall/orders/([^/]+)/cancel$").matcher(path);
        if ("POST".equals(method) && cancel.matches()) {
            ObjectNode order = orders.get(cancel.group(1));
            if (order == null) {
                respond(exchange, 404, "NOT_FOUND", "订单不存在：" + cancel.group(1), null);
                return;
            }
            if (!"CREATED".equals(order.path("status").asText())) {
                respond(exchange, 409, "INVALID_STATE",
                        "订单当前状态不允许取消：" + order.path("status").asText(), null);
                return;
            }
            order.put("status", "CANCELLED");
            hit(exchange, "POST /api/v1/mall/orders/{orderId}/cancel");
            respond(exchange, 200, "OK", "success", null);
            return;
        }
        Matcher refundApply = Pattern.compile("^/api/v1/mall/orders/([^/]+)/refunds$").matcher(path);
        if ("POST".equals(method) && refundApply.matches()) {
            ObjectNode order = orders.get(refundApply.group(1));
            if (order == null) {
                respond(exchange, 404, "NOT_FOUND", "订单不存在：" + refundApply.group(1), null);
                return;
            }
            if (!"PAID".equals(order.path("status").asText())) {
                respond(exchange, 409, "INVALID_STATE",
                        "订单当前状态不允许退款：" + order.path("status").asText(), null);
                return;
            }
            JsonNode refundRequest = read(body);
            BigDecimal refundAmount;
            try {
                refundAmount = new BigDecimal(refundRequest.path("amount").asText());
            } catch (RuntimeException e) {
                respond(exchange, 400, "INVALID_AMOUNT", "退款金额非法", null);
                return;
            }
            BigDecimal paidAmount = new BigDecimal(order.path("totalAmount").asText());
            BigDecimal alreadyRefunded = refunds.values().stream()
                    .filter(existing -> refundApply.group(1).equals(existing.path("orderId").asText()))
                    .map(existing -> new BigDecimal(existing.path("request").path("amount").asText()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (refundAmount.compareTo(paidAmount.subtract(alreadyRefunded)) > 0) {
                hit(exchange, "POST /api/v1/mall/orders/{orderId}/refunds");
                respond(exchange, 400, "REFUND_EXCEEDS_PAID",
                        "退款金额超过已付未退金额: " + refundAmount, null);
                return;
            }
            String refundId = nextId("83");
            ObjectNode refund = MAPPER.createObjectNode();
            refund.put("refundId", refundId);
            refund.put("orderId", refundApply.group(1));
            refund.put("status", "APPLIED");
            refund.set("request", refundRequest);
            refunds.put(refundId, refund);
            hit(exchange, "POST /api/v1/mall/orders/{orderId}/refunds");
            respond(exchange, 200, "OK", "success", MAPPER.createObjectNode().put("refundId", refundId));
            return;
        }
        Matcher refundComplete = REFUND_COMPLETE.matcher(path);
        if ("POST".equals(method) && refundComplete.matches()) {
            String refundId = refundComplete.group(1);
            ObjectNode refund = refunds.get(refundId);
            if (refund == null) {
                respond(exchange, 404, "NOT_FOUND", "退款单不存在：" + refundId, null);
                return;
            }
            refund.put("status", "COMPLETED");
            ObjectNode order = orders.get(refund.path("orderId").asText());
            if (order != null) {
                order.put("status", "REFUNDED");
            }
            hit(exchange, REFUND_COMPLETE_PATTERN);
            respond(exchange, 200, "OK", "success", null);
            return;
        }
        if ("GET".equals(method) && "/api/v1/mall/orders".equals(path)) {
            ArrayNode array = MAPPER.createArrayNode();
            String userId = queryParam(exchange, "userId");
            orders.values().stream()
                    .filter(order -> userId == null || userId.equals(order.path("userId").asText()))
                    .forEach(order -> array.add(order.deepCopy()));
            hit(exchange, "GET /api/v1/mall/orders");
            respond(exchange, 200, "OK", "success", array);
            return;
        }
        // 公开行为埋点未部署（参考商城现状）：任何未登记路径都回 404，逼出"能力 ABSENT"的真实判定
        hit(exchange, method + " " + path);
        respond(exchange, 404, "NOT_FOUND", "接口不存在：" + method + " " + path, null);
    }

    private void hit(HttpExchange exchange, String routeKey) {
        routeHits.merge(routeKey, 1, Integer::sum);
    }

    private String nextId(String prefix) {
        return prefix + String.format("%017d", sequence.incrementAndGet());
    }

    private static JsonNode read(String body) {
        try {
            return body == null || body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        } catch (IOException e) {
            throw new IllegalArgumentException("夹具收到非法 JSON：" + body, e);
        }
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0 && name.equals(pair.substring(0, split))) {
                return URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void respond(HttpExchange exchange, int status, String code, String message, JsonNode data)
            throws IOException {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("code", code);
        envelope.put("message", message);
        envelope.set("data", data == null ? MAPPER.nullNode() : data);
        envelope.put("traceId", "fixture-" + sequence.incrementAndGet());
        byte[] bytes = MAPPER.writeValueAsBytes(envelope);
        statuses.add(status);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 商品 ID 列表（按目录顺序），供测试断言"引擎对齐了真实目录" */
    public List<String> productIds() {
        return new ArrayList<>(products.keySet());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
