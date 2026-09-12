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

    /** 订单类应答的形态开关（只改<b>应答形状</b>，商城内部那份快照照旧推进） */
    public enum OrderResponseShape {

        /** 原样深拷贝（默认；状态与明细行都回） */
        AS_IS,

        /** 删掉 {@code pay_state}：验证"商城没给状态字段"⇒ 规范字段保持 null */
        OMIT_STATE,

        /** {@code pay_state} 回一个空白串：验证"给了但是空白"与"没给"走同一条规范化规则 */
        BLANK_STATE,

        /** 删掉 {@code lines}：验证"读不出明细行"必须响亮失败，不许当成 0 行 */
        OMIT_LINES,

        /** {@code lines} 回空数组：同上，验证"为空"这半边也被判成读不出 */
        EMPTY_LINES,

        /** 明细行里删掉 {@code unit_price_cents}：验证"商城没回单价"必须响亮失败 */
        OMIT_LINE_PRICE,

        /** 整个 {@code result} 回一个 JSON 数组（信封照旧 {@code success=true}）：验证"结果不是对象"必须响亮失败 */
        RESULT_NOT_OBJECT
    }

    /** 订单类应答形态（默认原样） */
    private volatile OrderResponseShape orderResponseShape = OrderResponseShape.AS_IS;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong orderAttempts = new AtomicLong();

    /**
     * 一条商品记录（第二家的字段名与单位）
     *
     * @param state          第二家的状态词（{@code SALE}/{@code OFF_SHELF}/{@code PENDING}）；
     *                       可被构造成未登记的怪词，用于验证适配器"认不出就不硬塞进规范枚举"；
     *                       {@code null} 表示<b>商城这条应答里根本没有 state 字段</b>
     *                       （与"给了一个读不懂的词"是两种不同的缺口，见
     *                       {@link #overrideItemState(String, String)}）
     * @param unitPriceCents 整数分；{@link #MISSING_UNIT_PRICE_CENTS} 表示<b>这条应答里根本没有
     *                       unit_price_cents 字段</b>（用于验证"价格缺失必须响亮失败"）
     */
    private record Item(String sku, String title, long categoryCode, long unitPriceCents, String state) {
    }

    /**
     * 单价缺失的哨兵：带这个值的商品在应答里<b>整条 {@code unit_price_cents} 字段都不写</b>
     * （不是写 0、也不是写 null 文本）——模拟"商城这条记录里根本没有价格"。
     *
     * <p>用 {@code -1} 当哨兵而不是用 {@code null}：{@code long} 的取值域里负数本该不是合法价格，
     * 因此"哨兵"与"真实价格"不会混。<b>但负数确实能被写进应答</b>：要造"商城给了一个非法报价"
     * 的形状请用 {@link #overrideItemPriceCents(String, long)}，别传 {@code -1}
     * （那是"字段缺失"，会走到 {@code E_NO_PRICE} 拒单，与"报了个非法数"是两件事）。</p>
     */
    public static final long MISSING_UNIT_PRICE_CENTS = -1L;

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

    /**
     * 把某件商品的状态词改成任意值（用于验证未登记状态词的处理策略）。
     *
     * @param state 商城状态词；传 {@code null} 表示<b>这条应答里根本没有 state 字段</b>
     *              （用于验证"商城没给字段"与"给了一个读不懂的词"被分成两件事）
     */
    public void overrideItemState(String sku, String state) {
        Item item = items.get(sku);
        if (item != null) {
            items.put(sku, new Item(item.sku(), item.title(), item.categoryCode(), item.unitPriceCents(), state));
        }
    }

    /**
     * 把某件商品的单价改成任意整数分（含<b>负数</b>这种非法报价）。
     *
     * <p>存在的理由只有一个：造"商城给了一个读不懂/不合法的报价"的形状，验证适配器
     * <b>(1)</b> 不把它当成合法金额（不取绝对值、不静默取整）；<b>(2)</b> 响亮失败的异常属于
     * {@code MallOperationException} 这一族（不会被 {@code IllegalArgumentException} 顶掉）；
     * <b>(3)</b> 被排除的商品 ID 仍然被点名（{@code ProductPage.priceUnreadable}）。</p>
     *
     * @param cents 整数分；<b>不要传 {@link #MISSING_UNIT_PRICE_CENTS}</b>——那个值表示"应答里整条
     *              字段不写"，会走到 {@code E_NO_PRICE} 拒单，与本方法要造的"值本身非法"不同
     */
    public void overrideItemPriceCents(String sku, long cents) {
        Item item = items.get(sku);
        if (item != null) {
            items.put(sku, new Item(item.sku(), item.title(), item.categoryCode(), cents, item.state()));
        }
    }

    /** 把某件商品的单价改成"商城没给"（应答里整条字段不写）：复用同一件商品，不新增规模 */
    public void removeItemPrice(String sku) {
        Item item = items.get(sku);
        if (item != null) {
            items.put(sku, new Item(item.sku(), item.title(), item.categoryCode(),
                    MISSING_UNIT_PRICE_CENTS, item.state()));
        }
    }

    /** 目录里这件商品的应答里到底有没有 {@code unit_price_cents}（测试侧独立可判的事实） */
    public boolean itemHasPrice(String sku) {
        Item item = items.get(sku);
        return item != null && item.unitPriceCents() != MISSING_UNIT_PRICE_CENTS;
    }

    /**
     * 让订单类应答（下单/支付/取消）<b>不带 {@code pay_state} 字段</b>，而商城自己记的状态照旧推进。
     *
     * <p>存在的理由只有一个：验证"商城没给状态字段"时，生成器<b>不造词</b>——
     * {@code ExternalOrder#status()} 必须是 {@code null}（而不是 {@code "UNKNOWN"} 这类编出来的词），
     * 且这次调用仍然算成功（HTTP 真的成功了，与商城回没回状态字段是两件事）。</p>
     *
     * <p>它只改<b>应答形态</b>：{@link #orderJson(String)} 与 {@link #orderStateHistogram()} 读的是
     * 商城内部那份快照，仍然带着 {@code pay_state}——因此"状态缺失"是应答的事实，
     * 而不是夹具被改坏了。</p>
     *
     * <p>等价于 {@code orderResponseShape(OrderResponseShape.OMIT_STATE)}；保留这个短方法是因为
     * "状态缺失"这条路径被多处测试用到，而形状枚举还要覆盖明细行与整体结构。</p>
     */
    public void omitOrderStateInResponse() {
        orderResponseShape(OrderResponseShape.OMIT_STATE);
    }

    /**
     * 设置订单类应答的形态（一次设置对所有后续订单类应答生效，直到再次设置）。
     *
     * <p>为什么用枚举而不是几个布尔开关：形态是<b>互斥</b>的（应答不可能既"没有 lines"又"lines 是空数组"），
     * 用布尔就会出现"两个开关同时为真时以谁为准"的隐含优先级——那种形状在测试里没人说得清。
     * 枚举同时把"夹具能造哪几种坏形状"写成一个清单，新增一条失败路径就必须在这里加一个取值。</p>
     */
    public void orderResponseShape(OrderResponseShape shape) {
        this.orderResponseShape = shape;
    }

    /**
     * 订单应答体：按 {@link OrderResponseShape} 造形状；默认原样深拷贝。
     *
     * <p>{@code RESULT_NOT_OBJECT} 直接回 JSON 数组（不是对象）：信封仍是
     * {@code {success:true,result:[...]}}，验证适配器读明细行之前先判"结果是不是对象"。</p>
     */
    private JsonNode orderResponse(ObjectNode order) {
        if (orderResponseShape == OrderResponseShape.RESULT_NOT_OBJECT) {
            return MAPPER.createArrayNode();
        }
        ObjectNode copy = order.deepCopy();
        switch (orderResponseShape) {
            case OMIT_STATE -> copy.remove("pay_state");
            case BLANK_STATE -> copy.put("pay_state", "   ");
            case OMIT_LINES -> copy.remove("lines");
            case EMPTY_LINES -> copy.set("lines", MAPPER.createArrayNode());
            case OMIT_LINE_PRICE -> {
                for (JsonNode line : copy.path("lines")) {
                    ((ObjectNode) line).remove("unit_price_cents");
                }
            }
            default -> {
            }
        }
        return copy;
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
                // 目录里这件商品没有价格 ⇒ 商城自己也算不出可信总额，只能拒单（E_NO_PRICE）。
                // 真实商城也是这个形状：不可能用"未知单价"去记一笔账；拒单而不是回一笔
                // 带 null 单价的订单，正好让"价格缺失不许变成一笔成交"在**商城侧**也成立。
                if (item.unitPriceCents() == MISSING_UNIT_PRICE_CENTS) {
                    fail(exchange, 409, "E_NO_PRICE",
                            "item has no price, cannot quote: " + item.sku());
                    return;
                }
                // 商城按<b>自己的目录</b>定价并回算总额；请求里若带了 unit_price_cents 就做校验，
                // 对不上就拒单——这样"适配器有没有乱报价"也是可观测的事实。
                // 注意：Jackson 里"字段不存在"读到的是 MissingNode（isNull() 为 false、asLong() 为 0），
                // 所以这里必须同时判 missing 与 null，否则"没带价格"会被误判成"报了 0 分"。
                JsonNode quoted = line.path("unit_price_cents");
                if (!quoted.isMissingNode() && !quoted.isNull() && quoted.asLong() != item.unitPriceCents()) {
                    fail(exchange, 409, "E_PRICE_MISMATCH", "unit_price_cents mismatch for " + item.sku());
                    return;
                }
                totalCents += item.unitPriceCents() * quantity;
                // 下单应答的明细行如实回显"商城记账的单价"。价格缺失走不到这里：上面已按商城自己的
                // 规矩 409 E_NO_PRICE 拒单了——因此这里不再留"写一个 null 单价"的分支
                // （那是不可达代码，留着会让人以为商城真的会回一笔没有单价的订单）
                ObjectNode lineNode = MAPPER.createObjectNode()
                        .put("sku", item.sku())
                        .put("quantity", quantity)
                        .put("unit_price_cents", item.unitPriceCents());
                lines.add(lineNode);
            }
            String orderNo = "ON" + String.format("%015d", sequence.incrementAndGet());
            order.put("order_no", orderNo);
            order.put("buyer_ref", buyerRef);
            order.put("pay_state", "NEW");
            order.put("total_cents", totalCents);
            order.set("lines", lines);
            orders.put(orderNo, order);
            hit(ORDERS_ROUTE);
            ok(exchange, orderResponse(order));
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
            ok(exchange, orderResponse(order));
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
            ok(exchange, orderResponse(order));
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
        // 价格缺失时**整条字段不写**（不是写 0、也不是写 null）：这样"商城这条记录里没有价格"
        // 才是真的可观测形态，适配器也就没有"当成 0 元"的机会。
        if (item.unitPriceCents() != MISSING_UNIT_PRICE_CENTS) {
            node.put("unit_price_cents", item.unitPriceCents());
        }
        // 状态字段同理：null 表示商城这条记录里没有 state 字段，与"给了一个读不懂的词"分开
        if (item.state() != null) {
            node.put("state", item.state());
        }
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
