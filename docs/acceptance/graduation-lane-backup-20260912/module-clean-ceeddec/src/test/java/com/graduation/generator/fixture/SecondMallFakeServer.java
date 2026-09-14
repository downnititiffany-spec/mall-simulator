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
 * <b>第二家商城</b>的本地真实 HTTP 夹具：真套接字、真状态码、真 JSON 信封。
 *
 * <p>它存在的唯一理由：M1-9 要证明"换一家商城"在实现上成立。生成器若只对着
 * {@link FakeMallServer}（参考商城）能跑通，那证明的是"能跑通参考商城"，不是"能换商城"。
 * 因此这台夹具<b>刻意处处与参考商城不同</b>，且这些不同点全部由 {@code SecondMallHttpAdapter} 吸收：</p>
 *
 * <table border="1">
 *   <caption>与参考商城夹具的对照</caption>
 *   <tr><th></th><th>{@link FakeMallServer}</th><th>本类</th></tr>
 *   <tr><td>路由</td><td>{@code /api/v1/mall/**}</td><td>{@code /open/v2/**}</td></tr>
 *   <tr><td>信封</td><td>{@code {code,message,data,traceId}}，成功看 {@code code=="OK"}</td>
 *       <td>{@code {success,result,errMsg}}，成功看 {@code success==true}</td></tr>
 *   <tr><td>失败码</td><td>{@code NOT_FOUND}/{@code INSUFFICIENT_STOCK}</td>
 *       <td>{@code E_*}（如 {@code E_NO_STOCK}）</td></tr>
 *   <tr><td>商品</td><td>{@code productId/productName/price(元)/status=on_sale}</td>
 *       <td>{@code sku/title/unit_price_cents(<b>整数分</b>)/state=<b>SALE</b>}</td></tr>
 *   <tr><td>用户</td><td>{@code userId}</td><td>{@code buyer_ref}</td></tr>
 *   <tr><td>订单</td><td>{@code orderId/status(PAID)/totalAmount}</td>
 *       <td>{@code order_no/pay_state(SETTLED)/total_cents}</td></tr>
 *   <tr><td>订单动作</td><td>{@code /orders/{id}/pay|cancel}</td><td>{@code /orders/{id}/settle|void}</td></tr>
 *   <tr><td>退款</td><td>有两步退款接口</td><td><b>没有</b>：任何 {@code /refund*} 路径都是 404</td></tr>
 *   <tr><td>商品管理</td><td>有 {@code /api/v1/admin/**}</td><td><b>没有</b>：{@code /open/v2/admin/**} 恒 404</td></tr>
 * </table>
 *
 * <p><b>它是独立写的，不是参考夹具的拷贝</b>：拷贝过来的夹具会把两家的形状差异一起搬过来，
 * 于是"生成器能换商城"这件事就失去了证据价值。状态机同样是真的：
 * 订单在 {@code NEW → SETTLED}/{@code VOID} 之间迁移，非法迁移回 {@code E_BAD_STATE}。</p>
 *
 * <p><b>端口</b>：绑 {@code 127.0.0.1:0}，由内核分配，{@link #baseUrl()} 读实际端口——
 * 固定端口会让并行测试互相踩。</p>
 */
public final class SecondMallFakeServer implements AutoCloseable {

    /** 埋点路径的默认形状（第二家自己的前缀）；夹具按构造参数决定是否真的有这个接口 */
    public static final String DEFAULT_BEHAVIOR_PATH = "/open/v2/track";

    /** 传给构造器的特殊令牌：只校验"带了非空 Bearer"，不校验值 */
    public static final String ANY_TOKEN = "*";

    /** 被网关 401 拒掉的路由的计数前缀（与参考夹具同约定，便于"打到过但被拒"可观测） */
    public static final String REJECTED_PREFIX = "REJECTED ";

    // ---------- 第二家的路由（与适配器各自独立书写，两处能对上才算真的对上了） ----------

    public static final String ITEMS_ROUTE = "GET /open/v2/items";
    public static final String MEMBERS_ROUTE = "POST /open/v2/members";
    public static final String ORDERS_ROUTE = "POST /open/v2/orders";
    public static final String SETTLE_ROUTE = "POST /open/v2/orders/{orderNo}/settle";
    public static final String VOID_ROUTE = "POST /open/v2/orders/{orderNo}/void";
    public static final String TRACK_ROUTE = "POST " + DEFAULT_BEHAVIOR_PATH;

    /** 失败注入：让下单接口回 {@code success=false}（HTTP 200），专门用来验证"成功位"而不是状态码 */
    public static final String FAIL_ORDER_SUCCESS_FALSE = "ORDER_SUCCESS_FALSE";

    /** 失败注入：让下单接口回 HTTP 400 + {@code E_NO_STOCK}（模拟库存耗尽） */
    public static final String FAIL_ORDER_NO_STOCK = "ORDER_NO_STOCK";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SETTLE = Pattern.compile("^/open/v2/orders/([^/]+)/settle$");
    private static final Pattern VOID = Pattern.compile("^/open/v2/orders/([^/]+)/void$");

    private final HttpServer server;
    private final String token;
    private final String behaviorPath;
    private final long orderCapacity;
    private final String failureInjection;

    private final Map<String, Item> items = new LinkedHashMap<>();
    private final Map<String, ObjectNode> members = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> orders = new ConcurrentHashMap<>();
    private final List<String> exchanges = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> bodiesByRoute = new ConcurrentHashMap<>();
    private final List<Integer> statuses = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> routeHits = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong orderAttempts = new AtomicLong();

    /**
     * 一条商品记录（第二家的字段名与单位）
     *
     * @param state 第二家的状态词（{@code SALE}/{@code OFF_SHELF}/{@code PENDING}）；
     *              可被构造成未登记的怪词，用于验证适配器"认不出就不硬塞进规范枚举"
     */
    private record Item(String sku, String title, long categoryCode, long unitPriceCents, String state) {
    }

    public SecondMallFakeServer(String token, String behaviorPath, int itemCount) throws IOException {
        this(token, behaviorPath, itemCount, Long.MAX_VALUE, null);
    }

    public SecondMallFakeServer(String token, String behaviorPath, int itemCount, long orderCapacity)
            throws IOException {
        this(token, behaviorPath, itemCount, orderCapacity, null);
    }

    /**
     * @param token            网关要求的 Bearer；{@code null} 不校验，{@link #ANY_TOKEN} 只校验带了 Bearer
     * @param behaviorPath     埋点路径；{@code null} 表示这家"商城"没有埋点接口（404）
     * @param itemCount        目录里的在售商品数
     * @param orderCapacity    总共接得下多少单，超出回 {@code E_NO_STOCK}
     * @param failureInjection {@link #FAIL_ORDER_SUCCESS_FALSE} / {@link #FAIL_ORDER_NO_STOCK} / {@code null}
     */
    public SecondMallFakeServer(String token, String behaviorPath, int itemCount, long orderCapacity,
                                String failureInjection) throws IOException {
        this.token = token;
        this.behaviorPath = behaviorPath;
        this.orderCapacity = orderCapacity;
        this.failureInjection = failureInjection;
        for (int i = 1; i <= itemCount; i++) {
            String sku = "SKU%05d".formatted(i);
            // 单价用"分的整数"，且刻意不是整元：500 + i*85 分（如 585 分 = 5.85 元），
            // 这样"分 → 元"的换算只要错一点就会在断言里露出来
            items.put(sku, new Item(sku, "第二家商品-" + i, 20L + (i % 4), 500L + i * 85L, "SALE"));
        }
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "second-mall");
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

    // ---------- 观测（测试断言的事实来源） ----------

    /** 全部请求行（{@code "METHOD /path"}，按到达顺序） */
    public List<String> exchanges() {
        return List.copyOf(exchanges);
    }

    /** 命中路由的次数；动态段写成 {@code {orderNo}} */
    public int hits(String routeKey) {
        return routeHits.getOrDefault(routeKey, 0);
    }

    public List<String> bodiesOf(String routeKey) {
        return List.copyOf(bodiesByRoute.getOrDefault(routeKey, List.of()));
    }

    public List<Integer> statuses() {
        return List.copyOf(statuses);
    }

    public int memberCount() {
        return members.size();
    }

    public int orderCount() {
        return orders.size();
    }

    /** 被 {@code POST /open/v2/orders} 处理过的下单请求总数（含被拒的） */
    public long orderAttempts() {
        return orderAttempts.get();
    }

    public List<String> itemSkus() {
        return new ArrayList<>(items.keySet());
    }

    /** 目录里某件商品的原始应答 JSON（用于与适配器解析出的规范 DTO 逐字段对照） */
    public JsonNode itemJson(String sku) {
        Item item = items.get(sku);
        return item == null ? null : itemNode(item);
    }

    /** 某笔订单的当前商城侧快照（{@code pay_state} 是第二家自己的词） */
    public JsonNode orderJson(String orderNo) {
        ObjectNode order = orders.get(orderNo);
        return order == null ? null : order.deepCopy();
    }

    /** 商城侧订单状态分布（第二家的词：{@code NEW}/{@code SETTLED}/{@code VOID}） */
    public Map<String, Integer> orderStateHistogram() {
        Map<String, Integer> histogram = new LinkedHashMap<>();
        orders.values().forEach(order -> histogram.merge(order.path("pay_state").asText(), 1, Integer::sum));
        return histogram;
    }

    /** 把某件商品的状态词改成任意值（用于验证未登记状态词的处理策略） */
    public void overrideItemState(String sku, String state) {
        Item item = items.get(sku);
        if (item != null) {
            items.put(sku, new Item(item.sku(), item.title(), item.categoryCode(), item.unitPriceCents(), state));
        }
    }

    // ---------- HTTP ----------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchanges.add(method + " " + path);
            bodiesByRoute.computeIfAbsent(method + " " + path, key -> new CopyOnWriteArrayList<>()).add(body);

            if (requiresToken() && !hasBearer(exchange)) {
                // 第二家的网关同样在路由之前拒绝；单独记一次"到了但被拒"
                hit(REJECTED_PREFIX + method + " " + path);
                fail(exchange, 401, "E_UNAUTHORIZED", "missing or invalid bearer");
                return;
            }
            route(exchange, method, path, body);
        } catch (RuntimeException e) {
            fail(exchange, 500, "E_FIXTURE", String.valueOf(e.getMessage()));
        }
    }

    private boolean requiresToken() {
        return token != null;
    }

    private boolean hasBearer(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Authorization");
        if (headers == null) {
            return false;
        }
        if (ANY_TOKEN.equals(token)) {
            return headers.stream().anyMatch(value -> value.startsWith("Bearer ")
                    && value.length() > "Bearer ".length());
        }
        return headers.stream().anyMatch(value -> value.equals("Bearer " + token));
    }

    private void route(HttpExchange exchange, String method, String path, String body) throws IOException {
        if ("GET".equals(method) && "/open/v2/items".equals(path)) {
            ArrayNode array = MAPPER.createArrayNode();
            String category = queryParam(exchange, "category_code");
            items.values().stream()
                    .filter(item -> category == null || String.valueOf(item.categoryCode()).equals(category))
                    .forEach(item -> array.add(itemNode(item)));
            hit(ITEMS_ROUTE);
            ok(exchange, array);
            return;
        }
        if ("POST".equals(method) && "/open/v2/members".equals(path)) {
            JsonNode request = read(body);
            String buyerRef = "BR" + String.format("%015d", sequence.incrementAndGet());
            ObjectNode stored = MAPPER.createObjectNode();
            stored.put("buyer_ref", buyerRef);
            stored.set("request", request);
            members.put(buyerRef, stored);
            hit(MEMBERS_ROUTE);
            ok(exchange, MAPPER.createObjectNode().put("buyer_ref", buyerRef));
            return;
        }
        if ("POST".equals(method) && behaviorPath != null && behaviorPath.equals(path)) {
            hit("POST " + behaviorPath);
            ok(exchange, null);
            return;
        }
        if ("POST".equals(method) && "/open/v2/orders".equals(path)) {
            JsonNode request = read(body);
            orderAttempts.incrementAndGet();
            if (FAIL_ORDER_SUCCESS_FALSE.equals(failureInjection)) {
                // HTTP 200 但 success=false：只按状态码判成功率的实现会在这里翻车
                hit(ORDERS_ROUTE);
                fail(exchange, 200, "E_ITEM_OFF_SHELF", "item is not on sale");
                return;
            }
            if (FAIL_ORDER_NO_STOCK.equals(failureInjection) || orderAttempts.get() > orderCapacity) {
                hit(ORDERS_ROUTE);
                fail(exchange, 400, "E_NO_STOCK", "no stock for " + firstSku(request));
                return;
            }
            String buyerRef = request.path("buyer_ref").asText();
            if (!members.containsKey(buyerRef)) {
                fail(exchange, 404, "E_NO_MEMBER", "unknown buyer_ref: " + buyerRef);
                return;
            }
            long totalCents = 0;
            ObjectNode order = MAPPER.createObjectNode();
            ArrayNode lines = MAPPER.createArrayNode();
            for (JsonNode line : request.path("lines")) {
                Item item = items.get(line.path("sku").asText());
                if (item == null) {
                    fail(exchange, 404, "E_NO_ITEM", "unknown sku: " + line.path("sku").asText());
                    return;
                }
                int quantity = line.path("quantity").asInt();
                // 商城按<b>自己的目录</b>定价并回算总额；请求里带的 unit_price_cents 只做校验，
                // 对不上就拒单——这样"适配器有没有正确报单价"也是可观测的事实
                JsonNode quoted = line.path("unit_price_cents");
                if (!quoted.isNull() && quoted.asLong() != item.unitPriceCents()) {
                    fail(exchange, 409, "E_PRICE_MISMATCH", "unit_price_cents mismatch for " + item.sku());
                    return;
                }
                totalCents += item.unitPriceCents() * quantity;
                lines.add(MAPPER.createObjectNode()
                        .put("sku", item.sku())
                        .put("quantity", quantity)
                        .put("unit_price_cents", item.unitPriceCents()));
            }
            String orderNo = "ON" + String.format("%015d", sequence.incrementAndGet());
            order.put("order_no", orderNo);
            order.put("buyer_ref", buyerRef);
            order.put("pay_state", "NEW");
            order.put("total_cents", totalCents);
            order.set("lines", lines);
            orders.put(orderNo, order);
            hit(ORDERS_ROUTE);
            ok(exchange, order.deepCopy());
            return;
        }
        Matcher settle = SETTLE.matcher(path);
        if ("POST".equals(method) && settle.matches()) {
            ObjectNode order = orders.get(settle.group(1));
            if (order == null) {
                fail(exchange, 404, "E_NO_ORDER", "unknown order_no: " + settle.group(1));
                return;
            }
            if (!"NEW".equals(order.path("pay_state").asText())) {
                fail(exchange, 409, "E_BAD_STATE", "cannot settle order in state " + order.path("pay_state").asText());
                return;
            }
            order.put("pay_state", "SETTLED");
            hit(SETTLE_ROUTE);
            ok(exchange, order.deepCopy());
            return;
        }
        Matcher cancel = VOID.matcher(path);
        if ("POST".equals(method) && cancel.matches()) {
            ObjectNode order = orders.get(cancel.group(1));
            if (order == null) {
                fail(exchange, 404, "E_NO_ORDER", "unknown order_no: " + cancel.group(1));
                return;
            }
            if (!"NEW".equals(order.path("pay_state").asText())) {
                fail(exchange, 409, "E_BAD_STATE", "cannot void order in state " + order.path("pay_state").asText());
                return;
            }
            order.put("pay_state", "VOID");
            hit(VOID_ROUTE);
            ok(exchange, order.deepCopy());
            return;
        }
        // 第二家没有的路由（含 /open/v2/admin/**、任何 /refund* 变体）：404 + E_NO_ROUTE
        hit(method + " " + path);
        fail(exchange, 404, "E_NO_ROUTE", "no such endpoint: " + method + " " + path);
    }

    private static ObjectNode itemNode(Item item) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("sku", item.sku());
        node.put("title", item.title());
        node.put("category_code", item.categoryCode());
        node.put("unit_price_cents", item.unitPriceCents());
        node.put("state", item.state());
        // 第二家还会给一些生成器用不上的字段：多给字段必须不影响解析（参考商城也有 brandId/cost）
        node.put("marketing_tag", "NONE");
        return node;
    }

    private static String firstSku(JsonNode request) {
        JsonNode lines = request.path("lines");
        return lines.isArray() && !lines.isEmpty() ? lines.get(0).path("sku").asText() : "(no sku)";
    }

    private void hit(String routeKey) {
        routeHits.merge(routeKey, 1, Integer::sum);
    }

    private static JsonNode read(String body) {
        try {
            return body == null || body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        } catch (IOException e) {
            throw new IllegalArgumentException("第二家夹具收到非法 JSON：" + body, e);
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
                return java.net.URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** 成功信封：{@code {"success":true,"result":…,"errMsg":null}}——与参考商城的 {@code code/message/data} 不同 */
    private void ok(HttpExchange exchange, JsonNode result) throws IOException {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("success", true);
        envelope.set("result", result == null ? MAPPER.nullNode() : result);
        envelope.set("errMsg", MAPPER.nullNode());
        envelope.put("trace_id", "second-" + sequence.incrementAndGet());
        write(exchange, 200, envelope);
    }

    /** 失败信封：{@code success=false} + {@code errMsg}（错误码另放 {@code error_code}） */
    private void fail(HttpExchange exchange, int status, String errorCode, String errMsg) throws IOException {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("success", false);
        envelope.set("result", MAPPER.nullNode());
        envelope.put("errMsg", errMsg);
        envelope.put("error_code", errorCode);
        write(exchange, status, envelope);
    }

    private void write(HttpExchange exchange, int status, JsonNode envelope) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(envelope);
        statuses.add(status);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 目录里第一条商品的"分"值，供测试独立复算"分 → 元" */
    public long firstUnitPriceCents() {
        return items.values().iterator().next().unitPriceCents();
    }

    /** 期望的元值（测试侧独立复算，不经被测代码） */
    public BigDecimal firstUnitPriceYuan() {
        return new BigDecimal(firstUnitPriceCents()).movePointLeft(2);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
