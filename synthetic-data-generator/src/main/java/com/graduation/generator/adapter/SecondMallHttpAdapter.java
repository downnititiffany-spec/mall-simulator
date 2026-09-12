package com.graduation.generator.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 第二家商城（{@code SECOND_MALL_HTTP}）的适配器：<b>处处与参考商城不同</b>，用来证明生成器没有写死 {@code mock-mall}
 * （V2.1 §3.4-6、指导书 §3 第 6 条：可以用第二个 {@code MallTargetAdapter} 证明平台不是为一家商城写死）。
 *
 * <h2>它与参考商城哪里不同（逐条都是刻意的）</h2>
 * <table border="1">
 *   <caption>两家的差异点</caption>
 *   <tr><th>维度</th><th>参考商城</th><th>第二家商城（本类）</th></tr>
 *   <tr><td>路由前缀</td><td>{@code /api/v1/mall/**}、{@code /api/v1/admin/**}</td><td>{@code /open/v2/**}</td></tr>
 *   <tr><td>响应信封</td><td>{@code {code,message,data,traceId}}，{@code code=="OK"}</td>
 *       <td>{@code {success,result,errMsg}}，{@code success==true}</td></tr>
 *   <tr><td>错误码</td><td>{@code OK}/{@code NOT_FOUND}/…</td><td>{@code E_*}（如 {@code E_NO_STOCK}）</td></tr>
 *   <tr><td>商品字段</td><td>{@code productId/productName/price/status}</td>
 *       <td>{@code sku/title/unit_price_cents/state}</td></tr>
 *   <tr><td>金额单位</td><td>元（{@code 19.90}）</td><td><b>整数分</b>（{@code 1990}）</td></tr>
 *   <tr><td>用户字段</td><td>{@code userId}</td><td>{@code buyer_ref}</td></tr>
 *   <tr><td>订单字段</td><td>{@code orderId/status/totalAmount}</td>
 *       <td>{@code order_no/pay_state/total_cents}</td></tr>
 *   <tr><td>订单路由</td><td>{@code /orders/{id}/pay|cancel}</td>
 *       <td>{@code /orders/{id}/settle|void}</td></tr>
 *   <tr><td>状态词</td><td>{@code on_sale}/{@code PAID}</td><td>{@code SALE}/{@code SETTLED}</td></tr>
 * </table>
 *
 * <h2>三件事必须在这里说清</h2>
 * <ol>
 *   <li><b>金额换算是显式的</b>：商城给整数分，规范 DTO 要元。{@link #centsToYuan(String)} 用
 *       {@link BigDecimal#movePointLeft(int)} 与 {@link #CENTS_PER_YUAN} 常量完成，<b>全程不经 double</b>——
 *       浮点尾差会被下游当成"金额对不上"。小数分一律拒绝（不是四舍五入掉），
 *       因为"商城给了一个生成器读不懂的金额"必须响亮失败。</li>
 *   <li><b>状态词映射归适配器（F-25）</b>：第二家说 {@code SALE}，规范事件契约对
 *       {@code product_created.status} 只认 {@code on_sale/off_sale/pending}。
 *       映射表 {@link #CANONICAL_PRODUCT_STATUS} 是本适配器内的显式常量，引擎不参与；
 *       <b>认不出的词映射为 {@code null}（表示"映射不到"）并登记进
 *       {@link #unmappedStatusWords()}，由引擎报成缺口</b>——既不把商城原词塞进规范字段
 *       （那会撞契约枚举），也不让它无声消失。原词与件数都会出现在预检流水与运行报告里，
 *       见 {@link #unmappedStatusWords()} 的说明。</li>
 *   <li><b>能力差异如实声明</b>：不支持 {@code admin}（没有改价/改库存）与 {@code refund}
 *       （没有退款单接口），{@code reset_state} 也不支持。不支持的必须走"记缺口"路径，
 *       <b>不得伪造成功</b>——写操作的方法体由接口默认实现抛
 *       {@link MallOperationException}，这里连覆盖都不覆盖。</li>
 * </ol>
 *
 * <p><b>凭据</b>：与参考商城同一纪律——{@code credential_ref} 是<b>引用名</b>（如
 * {@code SECOND_MALL_TOKEN}），适配器按引用取值当 Bearer 令牌，取不到就<b>拒绝发送匿名请求</b>；
 * 令牌值永不进日志、异常信息、流水或库。</p>
 *
 * <p><b>本类不联网做声明</b>：{@link #capabilities(TargetConfig)} 只按 {@code base_url} + 凭据可取
 * + {@code config_json.format} 给出声明；行不行由每次调用的真实应答证明。</p>
 */
public final class SecondMallHttpAdapter implements MallTargetAdapter, MallStatusVocabulary {

    private static final Logger log = LoggerFactory.getLogger(SecondMallHttpAdapter.class);

    /** 注册表的唯一查找键（§4.2 {@code generator_target.adapter_type}） */
    public static final String ADAPTER_TYPE = "SECOND_MALL_HTTP";

    /** {@code config_json} 里声明公开行为埋点接口路径的键（路径未冻结，必须显式声明） */
    public static final String CONFIG_BEHAVIOR_PATH = "behavior_path";

    /** {@code config_json.format} 必须等于它，否则声明一律 {@code UNDETERMINED}——不猜对方是什么格式 */
    public static final String CONFIG_FORMAT = "open-v2";

    /** 整数分 → 元的进制（显式常量；金额换算只经 BigDecimal，绝不经 double） */
    public static final int CENTS_PER_YUAN = 100;

    /** 上面那个进制对应的十进制位数（{@code movePointLeft/movePointRight} 的入参；写成常量而非 {@code Math.log10}） */
    private static final int CENTS_PER_YUAN_LOG10 = 2;

    /** 小数的位数（元，两位） */
    private static final int YUAN_SCALE = 2;

    /** 金额换算类失败的"操作名"：不是 §4.1 的七项之一，用它标明错在换算而不是某次调用 */
    private static final String OPERATION_MONEY = "centsToYuan";

    // ---------- 路由（唯一来源：业务方法发请求与 operationRoutes 报流水都引这里） ----------

    private static final String ITEMS_PATH = "/open/v2/items";
    private static final String MEMBERS_PATH = "/open/v2/members";
    private static final String ORDERS_PATH = "/open/v2/orders";
    private static final String SETTLE_PATH = ORDERS_PATH + "/{orderId}/settle";
    private static final String VOID_PATH = ORDERS_PATH + "/{orderId}/void";

    // ---------- 请求字段名（第二家自己的词表） ----------

    private static final String FIELD_BUYER_REF = "buyer_ref";
    private static final String FIELD_SKU = "sku";
    private static final String FIELD_QUANTITY = "quantity";
    private static final String FIELD_UNIT_PRICE_CENTS = "unit_price_cents";
    private static final String FIELD_CATEGORY = "category_code";
    private static final String FIELD_MEMBER_TIER = "member_tier";
    private static final String FIELD_TRACK_TYPE = "track_type";
    private static final String FIELD_CHANNEL = "channel";
    private static final String FIELD_SESSION = "visit_id";
    private static final String FIELD_REMARK = "remark";

    // ---------- 应答字段名（第二家自己的信封与词表） ----------

    private static final String ENVELOPE_SUCCESS = "success";
    private static final String ENVELOPE_RESULT = "result";
    private static final String ENVELOPE_ERROR = "errMsg";

    /** 第二家的结构化错误码（{@code E_*}）：给程序判的部分，与给人看的 {@code errMsg} 分开 */
    private static final String ENVELOPE_ERROR_CODE = "error_code";

    private static final String RESULT_SKU = "sku";
    private static final String RESULT_TITLE = "title";
    private static final String RESULT_CATEGORY = "category_code";
    private static final String RESULT_UNIT_PRICE_CENTS = "unit_price_cents";
    private static final String RESULT_STATE = "state";
    private static final String RESULT_BUYER_REF = "buyer_ref";
    private static final String RESULT_ORDER_NO = "order_no";
    private static final String RESULT_PAY_STATE = "pay_state";
    private static final String RESULT_TOTAL_CENTS = "total_cents";
    private static final String RESULT_LINES = "lines";

    // ---------- 词表（第二家 → 规范） ----------

    /** 在售词（第二家自有词）；只有它算"在售"，过滤器、能力判定都以规范词为准 */
    public static final String STATE_SALE = "SALE";

    /**
     * 商品状态：<b>第二家的词 → 规范词</b>（F-25 的落点，硬约束见 §4.1.1 补记）。
     *
     * <p>规范词表由 {@code ContractEnums.PRODUCT_STATUS} 冻结为 {@code on_sale/off_sale/pending}；
     * 映射是<b>适配器内</b>的显式常量，引擎不参与（引擎只负责把 {@link ExternalProduct#status()} 写进载荷）。</p>
     */
    private static final Map<String, String> CANONICAL_PRODUCT_STATUS = Map.of(
            "SALE", "on_sale",
            "OFF_SHELF", "off_sale",
            "PENDING", "pending");

    /** 订单支付状态：第二家的词 → 流水里可读的原文（订单状态机归商城，这里只标注等价词，不翻译规范枚举） */
    private static final Map<String, String> ORDER_STATE_ALIAS = Map.of(
            "NEW", "CREATED",
            "SETTLED", "PAID",
            "VOID", "CANCELLED");

    private final Function<String, String> credentialLookup;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 探测说明文本在静态上下文里拼装，因此另备一个只读 JSON 的 ObjectMapper */
    private static final ObjectMapper CONFIG_READER = new ObjectMapper();

    /** 本适配器见过的"映射不到"的商城原词 → 出现次数（{@link #unmappedStatusWords()} 的数据源） */
    private final Map<String, Integer> unmappedStatusWords = new ConcurrentHashMap<>();

    /**
     * @param credentialLookup 按 {@code credential_ref} 取凭据（生产传 {@code System::getenv}）
     * @param timeout          建连与单条请求的超时
     */
    public SecondMallHttpAdapter(Function<String, String> credentialLookup, Duration timeout) {
        this.credentialLookup = credentialLookup;
        this.timeout = timeout;
    }

    /** 生产装配形态：凭据取环境变量 */
    public static SecondMallHttpAdapter withEnvironment(Duration timeout) {
        return new SecondMallHttpAdapter(System::getenv, timeout);
    }

    @Override
    public String adapterType() {
        return ADAPTER_TYPE;
    }

    // ---------- §4.1 capabilities()：静态声明（不联网） ----------

    /**
     * 声明第二家商城能做什么：{@code product}/{@code user}/{@code order} 三段公开接口是它的固定形态，
     * 在 {@code base_url} 合法、凭据可取、{@code config_json.format=open-v2} 时声明为 {@code SUPPORTED}；
     * {@code behavior} 只有显式声明了 {@code behavior_path} 才 {@code SUPPORTED}。
     *
     * <p><b>刻意不支持的</b>：{@code admin}（第二家没有改价/改库存接口）与 {@code refund}
     * （没有退款单接口）恒为 {@code ABSENT}——不是"没证实"，是"这家商城就没有"。{@code reset_state}
     * 同样 {@code ABSENT}。这三项由引擎的能力门转成"记缺口"，不会被伪造成成功。</p>
     */
    @Override
    public TargetCapabilities capabilities(TargetConfig config) {
        Map<MallCapability, CapabilityVerdict> verdicts = new EnumMap<>(MallCapability.class);
        for (MallCapability capability : MallCapability.values()) {
            verdicts.put(capability, CapabilityVerdict.UNDETERMINED);
        }
        verdicts.put(MallCapability.ADMIN, CapabilityVerdict.ABSENT);
        verdicts.put(MallCapability.REFUND, CapabilityVerdict.ABSENT);
        verdicts.put(MallCapability.RESET_STATE, CapabilityVerdict.ABSENT);

        boolean usableBase = baseUri(config) != null;
        boolean credentialAvailable = credentialState(config.credentialRef()).available();
        boolean formatMatches = CONFIG_FORMAT.equals(declaredFormat(config.configJson()));
        if (usableBase && credentialAvailable && formatMatches) {
            verdicts.put(MallCapability.PRODUCT, CapabilityVerdict.SUPPORTED);
            verdicts.put(MallCapability.USER, CapabilityVerdict.SUPPORTED);
            verdicts.put(MallCapability.ORDER, CapabilityVerdict.SUPPORTED);
        }
        if (usableBase && credentialAvailable && declaredBehaviorPath(config.configJson()) != null) {
            verdicts.put(MallCapability.BEHAVIOR, CapabilityVerdict.SUPPORTED);
        }
        return TargetCapabilities.declared(verdicts);
    }

    /**
     * §4.1.1 的 {@code operationRoutes}：本适配器真实使用的方法与路径（硬约束 6）。
     *
     * <p>七项里只给<b>真的会打</b>的键：{@code refund} 第二家没有（能力 {@code ABSENT}，调用走默认的
     * 响亮失败），因此<b>不给条目</b>；{@code emitBehavior} 的路径来自 {@code config_json.behavior_path}
     * 声明，未声明就不出现。声明与不声明都必须如实——流水的路由只能是真发出去的形状。</p>
     */
    @Override
    public Map<String, TargetRoute> operationRoutes(TargetConfig config) {
        Map<String, TargetRoute> routes = new LinkedHashMap<>();
        routes.put("listProducts", new TargetRoute("GET", ITEMS_PATH));
        routes.put("createSyntheticUser", new TargetRoute("POST", MEMBERS_PATH));
        routes.put("createOrder", new TargetRoute("POST", ORDERS_PATH));
        routes.put("pay", new TargetRoute("POST", SETTLE_PATH));
        routes.put("cancel", new TargetRoute("POST", VOID_PATH));
        String behaviorPath = declaredBehaviorPath(config.configJson());
        if (behaviorPath != null) {
            routes.put("emitBehavior", new TargetRoute("POST", behaviorPath));
        }
        return Map.copyOf(routes);
    }

    // ---------- §4.1 其余方法：真实调用 ----------

    @Override
    public ProductPage listProducts(TargetConfig config, ProductQuery query) {
        if (query == null) {
            throw new MallOperationException("listProducts", "ProductQuery 不得为 null");
        }
        String path = ITEMS_PATH + (query.categoryId() == null ? "" : "?category_code=" + query.categoryId());
        JsonNode result = call(config, "listProducts", "GET", path, null);
        if (!result.isArray()) {
            throw new MallOperationException("listProducts",
                    "应答 result 不是数组，无法解析商品列表：" + abbreviate(result));
        }
        List<ExternalProduct> all = new ArrayList<>();
        for (JsonNode node : result) {
            all.add(toProduct(node));
        }
        List<ExternalProduct> filtered = query.keyword() == null ? all : all.stream()
                .filter(product -> product.name() != null
                        && product.name().toLowerCase().contains(query.keyword().toLowerCase()))
                .toList();
        return new ProductPage(query.window(filtered), filtered.size());
    }

    @Override
    public ExternalUser createSyntheticUser(TargetConfig config, UserCommand command) {
        if (command == null) {
            throw new MallOperationException("createSyntheticUser", "UserCommand 不得为 null");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("age_segment", command.ageGroup());
        body.put("city_tier", command.cityLevel());
        body.put(FIELD_MEMBER_TIER, command.memberLevel());
        JsonNode result = call(config, "createSyntheticUser", "POST", MEMBERS_PATH, body);
        return new ExternalUser(requireText(result, RESULT_BUYER_REF, "createSyntheticUser"),
                command.memberLevel());
    }

    @Override
    public void emitBehavior(TargetConfig config, BehaviorCommand command) {
        if (command == null) {
            throw new MallOperationException("emitBehavior", "BehaviorCommand 不得为 null");
        }
        String path = declaredBehaviorPath(config.configJson());
        if (path == null) {
            throw new MallOperationException("emitBehavior", "目标未声明行为埋点接口：请在 config_json."
                    + CONFIG_BEHAVIOR_PATH + " 里给出第二家商城的公开埋点路径（未声明即能力 UNDETERMINED，"
                    + "生成器不会猜一个路由去发、也不会静默跳过）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_BUYER_REF, command.userId());
        body.put(FIELD_SKU, command.productId());
        body.put(FIELD_SESSION, command.sessionId());
        body.put(FIELD_TRACK_TYPE, command.behaviorType());
        body.put(FIELD_CHANNEL, command.channel());
        call(config, "emitBehavior", "POST", path, body);
    }

    @Override
    public ExternalOrder createOrder(TargetConfig config, OrderCommand command) {
        if (command == null) {
            throw new MallOperationException("createOrder", "OrderCommand 不得为 null");
        }
        Map<String, BigDecimal> pricesBySku = catalogPricesBySku(config);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderCommand.Item item : command.items()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put(FIELD_SKU, item.productId());
            line.put(FIELD_QUANTITY, item.quantity());
            BigDecimal unitPrice = pricesBySku.get(item.productId());
            // 单价只有从目录真读到才写；读不到就交给商城按自己的目录定价——绝不编一个金额发出去
            line.put(FIELD_UNIT_PRICE_CENTS, unitPrice == null ? null : yuanToCents(unitPrice));
            lines.add(line);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_BUYER_REF, command.userId());
        body.put(RESULT_LINES, lines);

        JsonNode result = call(config, "createOrder", "POST", ORDERS_PATH, body);
        String orderNo = requireText(result, RESULT_ORDER_NO, "createOrder");
        return readOrder(result, orderNo, command.userId(), "createOrder");
    }

    /**
     * 支付：第二家的路由是 {@code /open/v2/orders/{orderNo}/settle}（不是 {@code /pay}），
     * 且应答带回订单快照（{@code pay_state=SETTLED}），因此状态取自应答而不是"调用成功就当成已支付"。
     */
    @Override
    public ExternalOrder pay(TargetConfig config, PayCommand command) {
        if (command == null) {
            throw new MallOperationException("pay", "PayCommand 不得为 null");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_BUYER_REF, command.userId());
        JsonNode result = call(config, "pay", "POST",
                SETTLE_PATH.replace("{orderId}", encode(command.orderId())), body);
        return readOrder(result, command.orderId(), command.userId(), "pay");
    }

    /** 取消：第二家叫 {@code /void}（不是 {@code /cancel}），状态同样取自应答原文 */
    @Override
    public ExternalOrder cancel(TargetConfig config, CancelCommand command) {
        if (command == null) {
            throw new MallOperationException("cancel", "CancelCommand 不得为 null");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_BUYER_REF, command.userId());
        body.put(FIELD_REMARK, command.reason());
        JsonNode result = call(config, "cancel", "POST",
                VOID_PATH.replace("{orderId}", encode(command.orderId())), body);
        return readOrder(result, command.orderId(), command.userId(), "cancel");
    }

    // refund()/test() 之外的能力差异不在这里"补一个假的"：refund 由接口默认实现抛 MallOperationException。

    // ---------- §4.1 test()：真实探测（OPTIONS 代表路由） ----------

    /**
     * 探测第二家商城：对每位能力的代表路由发 {@code OPTIONS}，看路径<b>存在/受保护/不存在</b>。
     *
     * <p>与参考商城同法（不是抄它的代码，是同一套判据）：只要有一条 404 就 {@code ABSENT}，
     * 401/403、非预期状态码或无应答只能 {@code UNDETERMINED}，全部 2xx 才 {@code SUPPORTED}。
     * 唯一不同之处是：第二家没有的段（{@code refund}/{@code admin}/{@code reset_state}）
     * 连代表路由都不发——<b>"这家商城根本没有该接口"是已知事实，不需要靠探测才发现</b>，
     * 直接按 {@code ABSENT} 收口。</p>
     */
    @Override
    public TargetCheckResult test(TargetConfig config) {
        URI base = baseUri(config);
        if (base == null) {
            return unmeasured(config, "base_url 非法或为空，无法探测第二家商城（/open/v2/**）");
        }
        Map<String, List<MallCapability>> routes = probeRoutes(config);
        String token = resolveToken(config.credentialRef());

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        Map<String, RouteOutcome> outcomes = new LinkedHashMap<>();
        int answered = 0;
        IOException lastFailure = null;
        for (String path : routes.keySet()) {
            RouteOutcome outcome = probe(client, base, path, token);
            outcomes.put(path, outcome);
            if (outcome.answered()) {
                answered++;
            }
            if (outcome.failure() != null) {
                lastFailure = outcome.failure();
            }
        }

        if (answered == 0) {
            String why = "无法连接 " + base + "（" + (lastFailure == null ? "无应答"
                    : lastFailure.getClass().getSimpleName() + ": " + lastFailure.getMessage()) + "）";
            log.warn("第二家商城能力探测失败：id={} {}", config.id(), why);
            return unmeasured(config, why);
        }

        Map<MallCapability, CapabilityVerdict> verdicts = new EnumMap<>(MallCapability.class);
        for (MallCapability capability : MallCapability.values()) {
            verdicts.put(capability, CapabilityVerdict.UNDETERMINED);
        }
        // 这家商城没有的段：按已知事实收口，不靠探测"碰巧没测到"
        verdicts.put(MallCapability.ADMIN, CapabilityVerdict.ABSENT);
        verdicts.put(MallCapability.REFUND, CapabilityVerdict.ABSENT);
        verdicts.put(MallCapability.RESET_STATE, CapabilityVerdict.ABSENT);
        for (MallCapability capability : List.of(MallCapability.PRODUCT, MallCapability.USER,
                MallCapability.ORDER, MallCapability.BEHAVIOR)) {
            verdicts.put(capability, aggregate(routes, outcomes, capability));
        }

        String detail = describeProbe(base, routes, outcomes, verdicts, config);
        log.info("第二家商城能力探测完成：id={} {}", config.id(), verdicts);
        return new TargetCheckResult(config.id(), true, detail, verdicts);
    }

    /** 代表路由：只含第二家真的有的三段 + 显式声明的埋点路径 */
    private Map<String, List<MallCapability>> probeRoutes(TargetConfig config) {
        Map<String, List<MallCapability>> routes = new LinkedHashMap<>();
        routes.put(ITEMS_PATH, List.of(MallCapability.PRODUCT));
        routes.put(MEMBERS_PATH, List.of(MallCapability.USER));
        routes.put(ORDERS_PATH, List.of(MallCapability.ORDER));
        String behaviorPath = declaredBehaviorPath(config.configJson());
        if (behaviorPath != null) {
            routes.put(behaviorPath, List.of(MallCapability.BEHAVIOR));
        }
        return routes;
    }

    /**
     * 判定：代表路由里只要有一条 404 就是 {@code ABSENT}（接口确实没有）；只要有一条 401/403、
     * 未预期状态码或无应答，就只能 {@code UNDETERMINED}（存在但未证实）；全部 2xx 才 {@code SUPPORTED}。
     */
    private static CapabilityVerdict aggregate(Map<String, List<MallCapability>> routes,
                                               Map<String, RouteOutcome> outcomes,
                                               MallCapability capability) {
        List<ProbeStatus> own = new ArrayList<>();
        for (Map.Entry<String, List<MallCapability>> entry : routes.entrySet()) {
            if (entry.getValue().contains(capability)) {
                own.add(outcomes.get(entry.getKey()).status());
            }
        }
        if (own.isEmpty()) {
            return CapabilityVerdict.UNDETERMINED;
        }
        if (own.contains(ProbeStatus.MISSING)) {
            return CapabilityVerdict.ABSENT;
        }
        if (own.contains(ProbeStatus.GATED) || own.contains(ProbeStatus.UNEXPECTED)
                || own.contains(ProbeStatus.FAILED)) {
            return CapabilityVerdict.UNDETERMINED;
        }
        return CapabilityVerdict.SUPPORTED;
    }

    private RouteOutcome probe(HttpClient client, URI base, String path, String token) {
        URI uri = URI.create(stripTrailingSlash(base.toString()) + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .timeout(timeout)
                .header("Accept", "application/json");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<Void> response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            ProbeStatus classification;
            if (status >= 200 && status < 300) {
                classification = ProbeStatus.HTTP_ANSWERED;
            } else if (status == 401 || status == 403) {
                classification = ProbeStatus.GATED;
            } else if (status == 404) {
                classification = ProbeStatus.MISSING;
            } else {
                classification = ProbeStatus.UNEXPECTED;
            }
            return new RouteOutcome(classification, status, null);
        } catch (IOException e) {
            return new RouteOutcome(ProbeStatus.FAILED, -1, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RouteOutcome(ProbeStatus.FAILED, -1, new IOException("探测被中断", e));
        }
    }

    private static String describeProbe(URI base, Map<String, List<MallCapability>> routes,
                                        Map<String, RouteOutcome> outcomes,
                                        Map<MallCapability, CapabilityVerdict> verdicts, TargetConfig config) {
        StringBuilder text = new StringBuilder("OPTIONS 探测 ").append(base)
                .append("（第二家商城 /open/v2/**）：").append(routes.size()).append(" 条代表路由");
        for (MallCapability capability : MallCapability.values()) {
            text.append("；").append(capability.key()).append('=').append(verdicts.get(capability));
        }
        text.append("。路由明细：");
        boolean first = true;
        for (Map.Entry<String, List<MallCapability>> entry : routes.entrySet()) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append(entry.getKey()).append('=').append(outcomes.get(entry.getKey()).describe());
        }
        String ref = config.credentialRef();
        text.append("。").append(ref == null || ref.isBlank()
                ? "未配置 credential_ref：第二家商城对 /open/v2/** 全部要求 Bearer，匿名探测一律 401，能力只能判 UNDETERMINED"
                : "已按凭据引用 " + ref.trim() + " 取值并对所有代表路由附带（值不回显）");
        text.append("。admin/refund/reset_state 按已知事实收口为 ABSENT：第二家商城没有改价/改库存、"
                + "没有退款单接口、没有重置接口");
        if (declaredBehaviorPathStatic(config.configJson()) == null) {
            text.append("。behavior 需在 config_json.").append(CONFIG_BEHAVIOR_PATH)
                    .append(" 声明埋点路径（路径未冻结，B-04）");
        }
        return text.toString();
    }

    /**
     * 与 {@link #declaredBehaviorPath(String)} 同口径的静态版本（探测说明文本在静态上下文里拼装）。
     *
     * <p>两处都只认 {@code "/"} 开头的字符串，非法声明一律当作"没声明"——不猜、不拼、不发请求。</p>
     */
    private static String declaredBehaviorPathStatic(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            JsonNode value = CONFIG_READER.readTree(configJson).path(CONFIG_BEHAVIOR_PATH);
            return value.isTextual() && value.asText().startsWith("/") ? value.asText() : null;
        } catch (IOException e) {
            log.warn("config_json 不是合法 JSON，已忽略其中的声明：{}", e.getMessage());
            return null;
        }
    }

    // ---------- 金额换算（T2 的被测对象） ----------

    /**
     * 整数分 → 元：{@code "12345"} ⇒ {@code 123.45}。
     *
     * <p>只经 {@link BigDecimal#movePointLeft(int)}，除数用 {@link #CENTS_PER_YUAN} 显式常量表达，
     * <b>全程不经 double</b>（{@code 12345 / 100.0} 这类写法会带出尾差，下游 Hive 侧按 {@code decimal(18,2)}
     * 读出来就是"金额对不上"）。</p>
     *
     * <p>契约边界：商城的金额是<b>整数分</b>。给了小数分（如 {@code "12.345"} 元，即半分）说明对方口径与
     * 本适配器不一致，这里<b>响亮失败</b>而不是静默四舍五入——把读不懂的金额悄悄改成另一个数，比报错更糟。</p>
     *
     * @throws MallOperationException 非整数、负数、超长或非数字的金额
     */
    public static BigDecimal centsToYuan(String cents) {
        String text = cents == null ? "" : cents.trim();
        if (text.isEmpty()) {
            throw new MallOperationException(OPERATION_MONEY,
                    "商城未给出 unit_price_cents，无法得到规范单价");
        }
        if (!text.matches("-?\\d+")) {
            throw new MallOperationException(OPERATION_MONEY,
                    "unit_price_cents 必须是整数分，实际=" + abbreviateText(text)
                            + "（小数分说明商城口径不一致，本适配器不擅自取整）");
        }
        // 整数分 → 元：movePointLeft(2) 恰好得到两位小数，setScale 只是把"两位"这一契约显式写出来
        // （输入是整数、左移 2 位，因此 setScale 不会发生舍入，不存在 ArithmeticException）
        return new BigDecimal(text)
                .movePointLeft(CENTS_PER_YUAN_LOG10)
                .setScale(YUAN_SCALE);
    }

    /** 元 → 整数分（下单时把目录单价报给商城）；只经 BigDecimal，不经 double */
    static long yuanToCents(BigDecimal yuan) {
        return yuan.movePointRight(CENTS_PER_YUAN_LOG10).longValueExact();
    }

    // ---------- 词表映射（F-25 的被测对象） ----------

    /**
     * 第二家的商品状态词 → <b>规范词</b>（F-25）：{@code SALE} ⇒ {@code on_sale}。
     *
     * @return 规范词（{@code on_sale}/{@code off_sale}/{@code pending}）；
     *         <b>{@code null} 的语义是"商城的词映射不到规范词表"</b>（含字段缺失/空值），
     *         绝不允许把商城原词放进这个位置——见 {@link #unmappedStatusWords()}
     */
    public static String toCanonicalProductStatus(String mallState) {
        if (mallState == null) {
            return null;
        }
        return CANONICAL_PRODUCT_STATUS.get(mallState.trim());
    }

    /**
     * 登记一个"映射不到"的商城原词（按出现次数计数，线程安全）。
     *
     * <p>为什么要留下原词：只说"有 K 件读不懂"无法指导修复；原词直接指向适配器映射表里要补的那一行。
     * 空值/字段缺失单独记成 {@link #MISSING_STATE_WORD}，与"给了个怪词"分开——两者的修法不同。</p>
     */
    private void recordUnmappedStatus(String mallState) {
        String word = mallState == null || mallState.isBlank() ? MISSING_STATE_WORD : mallState.trim();
        unmappedStatusWords.merge(word, 1, Integer::sum);
    }

    /**
     * 最近一次目录读取里出现过的、映射不到的商城原词（去重、按字典序）。
     *
     * <p>这是 {@link MallStatusVocabulary} 的实现，也是"被排除的商品"在流水/报告里能被点名的唯一来源：
     * 引擎只会按 {@code status == null} 数件数，原词只有拥有词表的适配器知道。</p>
     *
     * <p>计数只增不减（同一次运行里读多次目录时会累计），因为它的用途是"本次运行见过哪些读不懂的词"，
     * 而不是"当前目录里还剩几件"——件数由引擎按目录快照统计，两者分开才不会互相污染。</p>
     */
    @Override
    public List<String> unmappedStatusWords() {
        return unmappedStatusWords.keySet().stream().sorted().toList();
    }

    /** 字段缺失/空值在缺口里的原词占位（与"给了个怪词"分开登记） */
    public static final String MISSING_STATE_WORD = "(商城未给 state 字段)";

    // ---------- 调用原语 ----------

    /**
     * 一次真实业务调用：附凭据 → 发请求 → 解<b>第二家的信封</b>（{@code success/result/errMsg}）
     * → 返回 {@code result}。
     *
     * <p>五条失败路径都抛 {@link MallOperationException}，信息里带操作名、URL 与商城原话：
     * 缺凭据（发请求之前就抛，绝不匿名发）、连不上、HTTP 状态非 2xx、{@code success=false}、
     * 应答不是合法 JSON。</p>
     */
    private JsonNode call(TargetConfig config, String operation, String method, String path, Object body) {
        URI uri = baseUri(config);
        if (uri == null) {
            throw new MallOperationException(operation, "base_url 非法或为空，无法调用：" + config.baseUrl());
        }
        String token = requireCredential(config, operation);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(stripTrailingSlash(uri.toString()) + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token);
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            byte[] payload;
            try {
                payload = mapper.writeValueAsBytes(body == null ? Map.of() : body);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new MallOperationException(operation, "请求体无法序列化：" + e.getOriginalMessage(), e);
            }
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
        }

        String responseBody;
        int status;
        try {
            HttpResponse<String> response = client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            responseBody = response.body();
        } catch (IOException e) {
            throw new MallOperationException(operation, "无法连接 " + uri + "：" + abbreviateException(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MallOperationException(operation, "调用被中断：" + uri, e);
        }

        JsonNode envelope;
        try {
            envelope = mapper.readTree(responseBody);
        } catch (IOException e) {
            throw new MallOperationException(operation, "HTTP " + status + " 应答不是合法 JSON（"
                    + abbreviateText(responseBody) + "）", e);
        }
        boolean success = envelope.path(ENVELOPE_SUCCESS).asBoolean(false);
        if (status < 200 || status >= 300 || !success) {
            String error = envelope.path(ENVELOPE_ERROR).asText("");
            // 错误码与错误原话都要留：第二家把两者分开放（errMsg 给人看，error_code 给程序判），
            // 只留其中一个都会让排障时缺一半事实——尤其 HTTP 200 + success=false 这种
            // "状态码很好看"的失败，错误码是唯一的结构化线索。
            String errorCode = envelope.path(ENVELOPE_ERROR_CODE).asText("");
            if (error.isEmpty()) {
                error = errorCode;
            }
            throw new MallOperationException(operation, "商城拒绝：" + uri + " → HTTP " + status
                    + " success=" + success
                    + " errMsg=" + (error.isEmpty() ? "(无)" : error)
                    + (errorCode.isEmpty() ? "" : " error_code=" + errorCode));
        }
        return envelope.path(ENVELOPE_RESULT);
    }

    private HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 取凭据；取不到就抛（响亮失败），信息只出现引用名，绝不出现取值 */
    private String requireCredential(TargetConfig config, String operation) {
        Credential credential = credentialState(config.credentialRef());
        if (credential.available()) {
            return credential.token();
        }
        String why = credential.ref() == null
                ? "目标未配置 credential_ref，而第二家商城对 /open/v2/** 全部要求 Bearer，匿名调用必然被拒"
                : "凭据引用 " + credential.ref() + " 指向的环境变量未设置或为空";
        throw new MallOperationException(operation, "缺少凭据，拒绝发送匿名请求：" + why
                + "（credential_ref 只存引用，取值由部署环境注入）");
    }

    private Credential credentialState(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return new Credential(null, null);
        }
        return new Credential(credentialRef.trim(), resolveToken(credentialRef));
    }

    /** 商品应答 → {@link ExternalProduct}：第二家的 {@code sku/title/unit_price_cents/state} 在这里落成规范字段 */
    private ExternalProduct toProduct(JsonNode node) {
        String sku = requireText(node, RESULT_SKU, "listProducts");
        JsonNode category = node.path(RESULT_CATEGORY);
        JsonNode state = node.path(RESULT_STATE);
        String mallState = state.isMissingNode() || state.isNull() ? null : state.asText();
        String canonical = toCanonicalProductStatus(mallState);
        if (canonical == null) {
            // "读不懂的词"必须留痕：登记原词，交引擎报成缺口（件数 + 原词）。
            // 绝不静默丢弃，也绝不把原词写进规范字段 status（那会产出违约数据）。
            recordUnmappedStatus(mallState);
            log.debug("第二家商城返回了未登记的商品状态词，已登记为目录缺口：state={} sku={}", mallState, sku);
        }
        return new ExternalProduct(sku, node.path(RESULT_TITLE).asText(null),
                category.isNumber() ? category.asLong() : parseLongOrNull(category.asText(null)),
                centsToYuan(node.path(RESULT_UNIT_PRICE_CENTS).asText(null)), canonical);
    }

    /**
     * 订单类应答 → {@link ExternalOrder}：第二家的 {@code order_no/pay_state/total_cents} 落成规范字段。
     *
     * <p>{@code pay_state} 是<b>商城原文</b>（{@code NEW}/{@code SETTLED}/{@code VOID}）——订单状态机归商城所有，
     * 这里只按 {@link #ORDER_STATE_ALIAS} 在流水里附上可读的等价词，<b>不</b>把它翻译成生成器自己的枚举。</p>
     */
    private static ExternalOrder readOrder(JsonNode result, String orderNo, String buyerRef, String operation) {
        if (result == null || !result.isObject()) {
            throw new MallOperationException(operation,
                    "应答 result 不是对象，读不到订单快照：" + abbreviate(result));
        }
        String mallState = result.path(RESULT_PAY_STATE).asText(null);
        String status = mallState == null || mallState.isBlank() ? "UNKNOWN"
                : mallState + (ORDER_STATE_ALIAS.containsKey(mallState) ? "(" + ORDER_STATE_ALIAS.get(mallState) + ")" : "");
        JsonNode total = result.path(RESULT_TOTAL_CENTS);
        BigDecimal totalYuan = total.isNumber() || total.isTextual()
                ? centsToYuan(total.asText())
                : null;
        JsonNode lines = result.path(RESULT_LINES);
        return new ExternalOrder(orderNo, buyerRef, status, totalYuan, lines.isArray() ? lines.size() : -1);
    }

    /** 目录单价表（下单时报给商城用）；读不到就返回空表，让商城按自己的目录定价，绝不编金额 */
    private Map<String, BigDecimal> catalogPricesBySku(TargetConfig config) {
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        try {
            for (ExternalProduct product : listProducts(config, ProductQuery.firstPage(ProductQuery.MAX_LIMIT))
                    .products()) {
                prices.put(product.productId(), product.price());
            }
        } catch (RuntimeException e) {
            log.debug("下单前读取目录失败，单价交由商城按自己的目录定价：{}", e.getMessage());
        }
        return prices;
    }

    private static Long parseLongOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String requireText(JsonNode node, String field, String operation) {
        if (node == null || !node.isObject()) {
            throw new MallOperationException(operation,
                    "应答 result 缺少对象，读不到 " + field + "：" + abbreviate(node));
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            throw new MallOperationException(operation, "应答 result 缺少字段 " + field + "（实际=" + abbreviate(node)
                    + "）；不返回空 ID 冒充成功");
        }
        return value.asText();
    }

    /** 只接受 {@code /...} 形式的路径声明；非法声明当作"没声明"并留日志 */
    private String declaredBehaviorPath(String configJson) {
        JsonNode root = readConfig(configJson);
        if (root == null) {
            return null;
        }
        JsonNode value = root.path(CONFIG_BEHAVIOR_PATH);
        if (value.isTextual() && value.asText().startsWith("/")) {
            return value.asText();
        }
        if (!value.isMissingNode() && !value.isNull()) {
            log.warn("config_json.{} 必须是 '/...' 形式的路径，已忽略：{}", CONFIG_BEHAVIOR_PATH, value);
        }
        return null;
    }

    private String declaredFormat(String configJson) {
        JsonNode root = readConfig(configJson);
        if (root == null) {
            return null;
        }
        JsonNode value = root.path("format");
        return value.isTextual() ? value.asText() : null;
    }

    private JsonNode readConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(configJson);
        } catch (IOException e) {
            log.warn("config_json 不是合法 JSON，已忽略其中的声明：{}", e.getMessage());
            return null;
        }
    }

    private String resolveToken(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return null;
        }
        try {
            return credentialLookup.apply(credentialRef.trim());
        } catch (RuntimeException e) {
            log.warn("凭据引用取值失败：ref={} {}", credentialRef, e.getClass().getSimpleName());
            return null;
        }
    }

    private static URI baseUri(TargetConfig config) {
        if (config == null || config.baseUrl() == null || config.baseUrl().isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(config.baseUrl().trim());
            return uri.getHost() == null ? null : uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stripTrailingSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String encode(String pathSegment) {
        return java.net.URLEncoder.encode(pathSegment, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 报文摘要：单行、限长，避免把整页 HTML 或长栈塞进运行报告 */
    private static String abbreviate(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "(无)";
        }
        return abbreviateText(node.toString());
    }

    private static String abbreviateText(String text) {
        if (text == null) {
            return "(无)";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 200) + "…";
    }

    private static String abbreviateException(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + ": " + (message == null ? "(无消息)"
                : message.length() <= 160 ? message : message.substring(0, 160) + "…");
    }

    /** 凭据状态：引用名 + 取值（取值只在本对象里流转，永不进日志/异常/库） */
    private record Credential(String ref, String token) {

        boolean available() {
            return token != null && !token.isBlank();
        }
    }

    /** 一次探测的原始事实：分类、观察到的 HTTP 状态码（-1 表示无应答）、可能的异常 */
    private record RouteOutcome(ProbeStatus status, int observedStatus, IOException failure) {

        boolean answered() {
            return status != ProbeStatus.FAILED;
        }

        String describe() {
            return observedStatus > 0 ? status.name() + "(" + observedStatus + ")" : status.name();
        }
    }

    /** 探测时的路由状态分类 */
    private enum ProbeStatus {

        /** 2xx：路径存在且接受 OPTIONS */
        HTTP_ANSWERED,

        /** 401/403：路径存在但需要凭据 */
        GATED,

        /** 404：路径不存在 */
        MISSING,

        /** 其它状态码：存在性无法判定 */
        UNEXPECTED,

        /** 请求本身失败（连接被拒/超时） */
        FAILED
    }

    /** 探测结论无法给出时：可达性未证实，能力全部 {@code UNDETERMINED}（绝不默认成"支持"） */
    private static TargetCheckResult unmeasured(TargetConfig config, String detail) {
        Map<MallCapability, CapabilityVerdict> verdicts = new EnumMap<>(MallCapability.class);
        for (MallCapability capability : MallCapability.values()) {
            verdicts.put(capability, CapabilityVerdict.UNDETERMINED);
        }
        return new TargetCheckResult(config.id(), false, detail, verdicts);
    }
}
