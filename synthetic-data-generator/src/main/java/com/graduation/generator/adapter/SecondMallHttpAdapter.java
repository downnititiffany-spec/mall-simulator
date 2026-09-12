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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *       <b>认不出的词映射为 {@code null}（表示"映射不到"）并随本次目录读取的
 *       {@link ProductPage#unmappedStateWords()} 返回</b>——既不把商城原词塞进规范字段
 *       （那会撞契约枚举），也不让它无声消失。原词与件数都会出现在预检流水与运行报告里。
 *       <b>缺口是"本次读取"的事实，随返回值走，不留在适配器实例上</b>（适配器是单例，见
 *       {@code config/GeneratorBeans}）：留在实例上就会跨运行、跨目标累计，让 B 商城这次的报告里
 *       出现 A 商城上次见过的词。</li>
 *   <li><b>能力差异如实声明</b>：不支持 {@code admin}（没有改价/改库存）与 {@code refund}
 *       （没有退款单接口），{@code reset_state} 也不支持。这三项的 {@code ABSENT} 是
 *       <b>静态声明</b>（读配置即得，不联网、不探测），不是探测结论——见
 *       {@link #capabilities(TargetConfig)} 的说明与探测文案里"未探测"的原话。
 *       不支持的必须走"记缺口"路径，<b>不得伪造成功</b>——写操作的方法体由接口默认实现抛
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
public final class SecondMallHttpAdapter implements MallTargetAdapter {

    private static final Logger log = LoggerFactory.getLogger(SecondMallHttpAdapter.class);

    /** 注册表的唯一查找键（§4.2 {@code generator_target.adapter_type}） */
    public static final String ADAPTER_TYPE = "SECOND_MALL_HTTP";

    /** {@code config_json} 里声明公开行为埋点接口路径的键（路径未冻结，必须显式声明） */
    public static final String CONFIG_BEHAVIOR_PATH = "behavior_path";

    /**
     * {@code config_json} 里声明"接口是哪一版形态"的<b>键名</b>（单点定义：读取、校验与运维话术
     * 都走这个常量，因此全类只有这一处出现键名的字面量）。
     *
     * <p><b>键名是本适配器与本地夹具之间的约定，契约没有规定任何键名</b>：§4.1/§4.1.1.3 与
     * {@code contract-specs/**} 都没有定义 {@code config_json.format}；{@code generator_target.config_json}
     * 只是一个自由 JSON 列（{@code V1__generator_meta.sql} 注释："适配器扩展配置（JSON）"）。
     * 所以这是<b>适配器私有声明</b>，不是平台契约的一部分——换一家商城就换自己的键。</p>
     */
    public static final String CONFIG_FORMAT_KEY = "format";

    /**
     * {@link #CONFIG_FORMAT_KEY} 的取值：只有它等于该值时，公开的 {@code product/user/order} 三段
     * 才被声明为 {@code SUPPORTED}——不猜对方是什么格式。
     *
     * <p>取值同样只在本适配器与夹具之间约定（见 {@link #CONFIG_FORMAT_KEY} 的说明）。</p>
     */
    public static final String CONFIG_FORMAT_VALUE = "open-v2";

    /** 整数分 → 元的进制（显式常量；金额换算只经 BigDecimal，绝不经 double） */
    public static final int CENTS_PER_YUAN = 100;

    /**
     * {@link #CENTS_PER_YUAN} 对应的十进制位数（{@code movePointLeft} 的入参）。
     *
     * <p><b>由进制常量推导，不是第二个字面量</b>（单一所有者）：写成
     * {@code private static final int CENTS_PER_YUAN_LOG10 = 2;} 时，换算就与
     * {@link #CENTS_PER_YUAN} 脱了钩——把它改成 1000 而这里仍是 2，金额会静默错 10 倍，
     * 而且拿夹具独立复算的断言照样是绿的（它比的是"分 → 元"的结果，不是常量本身）。</p>
     */
    private static final int CENTS_PER_YUAN_LOG10 = requirePowerOfTen(CENTS_PER_YUAN);

    /**
     * 求出 10 的幂对应的十进制位数；{@link #CENTS_PER_YUAN} 不是 10 的幂时<b>启动即失败</b>
     * （整数分 → 元只能靠十进制移位，取近似值就等于静默改金额）。
     */
    private static int requirePowerOfTen(int centsPerYuan) {
        int scale = 0;
        long value = 1;
        while (value < centsPerYuan) {
            value *= 10;
            scale++;
        }
        if (value != centsPerYuan) {
            throw new IllegalStateException("CENTS_PER_YUAN 必须是 10 的幂（否则分 → 元无法用移位精确表达）："
                    + centsPerYuan);
        }
        return scale;
    }

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

    private final Function<String, String> credentialLookup;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 探测说明文本在静态上下文里拼装，因此另备一个只读 JSON 的 ObjectMapper */
    private static final ObjectMapper CONFIG_READER = new ObjectMapper();

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
     * 在 {@code base_url} 合法、凭据可取、{@code config_json.format} 等于 {@link #CONFIG_FORMAT_VALUE}
     * 时声明为 {@code SUPPORTED}；{@code behavior} 还要求显式声明了 {@code behavior_path}
     * ——<b>与三段同一个门控</b>：格式没声明清楚时连埋点能力也不给（同一个 if，
     * 不留"格式门管不到它"的并列旁路）。
     *
     * <p><b>刻意不支持的</b>：{@code admin}（第二家没有改价/改库存接口）与 {@code refund}
     * （没有退款单接口）、{@code reset_state}。这三项写 {@code ABSENT} 是 <b>静态声明</b>：
     * 本方法<b>不联网</b>（§4.1 的 {@code capabilities} 就是声明口），这个 {@code ABSENT}
     * <b>不是探测结论</b>；{@link #test(TargetConfig)} 对它们<b>不发探测请求</b>，也从不谎称测过
     * （探测文案里写的是"静态声明（未探测）"）。它们不去猜 {@code SUPPORTED}，
     * 由引擎的能力门转成"记缺口"，不会被伪造成成功。</p>
     */
    @Override
    public TargetCapabilities capabilities(TargetConfig config) {
        Map<MallCapability, CapabilityVerdict> verdicts = new EnumMap<>(MallCapability.class);
        for (MallCapability capability : MallCapability.values()) {
            verdicts.put(capability, CapabilityVerdict.UNDETERMINED);
        }
        // 静态声明（未探测）：写操作类能力第二家根本没有对应接口，声明成 ABSENT 而不是"没证实"；
        // 这是本方法不联网就能给出的结论，取值来源是"这家商城的公开接口清单"，不是一次测量。
        verdicts.put(MallCapability.ADMIN, CapabilityVerdict.ABSENT);
        verdicts.put(MallCapability.REFUND, CapabilityVerdict.ABSENT);
        verdicts.put(MallCapability.RESET_STATE, CapabilityVerdict.ABSENT);

        boolean usableBase = baseUri(config) != null;
        boolean credentialAvailable = credentialState(config.credentialRef()).available();
        boolean formatMatches = CONFIG_FORMAT_VALUE.equals(declaredFormat(config.configJson()));
        // 单一门控：格式声明不成立 ⇒ 三段与埋点一并保持 UNDETERMINED，不猜对方是哪一版接口
        if (usableBase && credentialAvailable && formatMatches) {
            verdicts.put(MallCapability.PRODUCT, CapabilityVerdict.SUPPORTED);
            verdicts.put(MallCapability.USER, CapabilityVerdict.SUPPORTED);
            verdicts.put(MallCapability.ORDER, CapabilityVerdict.SUPPORTED);
            if (declaredBehaviorPath(config.configJson()) != null) {
                verdicts.put(MallCapability.BEHAVIOR, CapabilityVerdict.SUPPORTED);
            }
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
        ProductPage page = readCatalog(config, query.categoryId() == null
                ? ITEMS_PATH
                : ITEMS_PATH + "?category_code=" + query.categoryId());
        if (query.keyword() == null) {
            return page;
        }
        // 关键字过滤只改本页的商品集合；缺口是"本次读取"的事实（商城确实返回过这些词、这些报价读不懂），
        // 不随过滤增减——三个通道必须一起带过去，少带一个就等于让那个缺口静默消失（H2）。
        // total 也原样带过去：它的口径是"施加分页前适配器看到的候选总数"，换成 filtered.size() 会让
        // "有没有发生分页截断"在关键字查询下得出错误结论（同一个字段两种口径）。
        List<ExternalProduct> filtered = page.products().stream()
                .filter(product -> product.name() != null
                        && product.name().toLowerCase().contains(query.keyword().toLowerCase()))
                .toList();
        return new ProductPage(query.window(filtered), page.total(),
                page.unmappedStateWords(), page.stateFieldMissing(), page.priceUnreadable());
    }

    /**
     * 一次目录读取：把"本次读取"的缺口事实（读不懂的原词、没给状态字段的商品、报价读不懂的商品）
     * 收集在<b>方法局部</b>，随 {@link ProductPage} 的<b>三个通道</b>一起返回。
     *
     * <p><b>为什么不放在适配器字段上</b>：适配器是单例（{@code config/GeneratorBeans}），
     * 放字段就会跨运行、跨目标累计，让下一次运行（甚至另一家商城）的报告里出现上一次见过的词。
     * 缺口是"这一次读取"的事实，只能挂在这一次的返回值上。</p>
     *
     * <p><b>为什么三个通道都要返回</b>：被排除的商品必须能被点名（Javadoc 承诺"一定会被点名报出来"，
     * 实现就得真有出口）。只有"整份目录都读不懂"才响亮失败，<b>部分</b>读不懂的走"排除 + 点名"，
     * 因此 {@code priceUnreadable} 必须进页，否则它随局部变量一起消失，运维只能去翻 debug 日志。</p>
     */
    private ProductPage readCatalog(TargetConfig config, String path) {
        JsonNode result = call(config, "listProducts", "GET", path, null);
        if (!result.isArray()) {
            throw new MallOperationException("listProducts",
                    "应答 result 不是数组，无法解析商品列表：" + abbreviate(result));
        }
        List<ExternalProduct> products = new ArrayList<>();
        List<String> unmappedWords = new ArrayList<>();
        List<String> stateMissing = new ArrayList<>();
        List<String> priceUnreadable = new ArrayList<>();
        for (JsonNode node : result) {
            ExternalProduct product = toProduct(node, unmappedWords, stateMissing, priceUnreadable);
            if (product != null) {
                products.add(product);
            }
        }
        // 报价读不懂的商品被排除在目录之外——但绝不静默：一件都不剩时响亮失败，
        // 否则引擎会拿着空目录继续跑，运行报告里看不出差别
        if (products.isEmpty() && !priceUnreadable.isEmpty()) {
            throw new MallOperationException("listProducts",
                    "本次目录 " + priceUnreadable.size() + " 件商品的报价都读不懂（"
                            + String.join("、", sortedDistinct(priceUnreadable))
                            + "）：目录为空即响亮失败，绝不静默返回空目录继续跑");
        }
        // total = 商城这次**给了多少件商品**（含报价读不懂、已被排除的那些），不是"能用的有几件"：
        // 引擎把它渲染成"目录 N 件，在售 M 件"，N 与 M 的差额必须正好是缺口件数。
        // 若这里写 products.size()，报价缺口的 N 会悄悄变成 59 而状态缺口的 N 仍是 60——
        // 同一个"N"在两种缺口下有两种含义，运维无法从两个数字看出差额去了哪里（H2 的"无声消失"）。
        List<String> unreadablePrices = sortedDistinct(priceUnreadable);
        return new ProductPage(products, products.size() + unreadablePrices.size(),
                sortedDistinct(unmappedWords), sortedDistinct(stateMissing), unreadablePrices);
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

    /**
     * 下单：请求体只放"要买什么"（{@code sku}/{@code quantity}），<b>不放单价</b>——
     * 单价是商城按自己的目录定的，生成器凭空报一个价既没有出处、又会在商城侧撞价格校验。
     *
     * <p><b>金额从下单应答里读</b>（{@code result.lines[].unit_price_cents}，即商城真实记账的单价）：
     * 读不到就<b>响亮失败</b>（{@link MallOperationException}）。以前这里会在下单前额外拉一次目录、
     * 拉不到就把 {@code unit_price_cents: null} 发出去继续下单——那是把"价格未知"静默降级成
     * "商城自己算"，流水里看不出差别（硬约束 3 要的是响亮失败，不是 catch 掉）。</p>
     */
    @Override
    public ExternalOrder createOrder(TargetConfig config, OrderCommand command) {
        if (command == null) {
            throw new MallOperationException("createOrder", "OrderCommand 不得为 null");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderCommand.Item item : command.items()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put(FIELD_SKU, item.productId());
            line.put(FIELD_QUANTITY, item.quantity());
            lines.add(line);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_BUYER_REF, command.userId());
        body.put(RESULT_LINES, lines);

        JsonNode result = call(config, "createOrder", "POST", ORDERS_PATH, body);
        String orderNo = requireText(result, RESULT_ORDER_NO, "createOrder");
        return readCreatedOrder(result, orderNo, command.userId());
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
     * 连代表路由都不发——这三项在 {@link #capabilities(TargetConfig)} 里已是<b>静态声明</b>，
     * 这里<b>不对它们做探测（未探测）</b>，也就不存在"测出来的结论"被当成声明的情况：
     * 报告里它们是"声明为 ABSENT（未探测）"，而不是"实测 ABSENT"。</p>
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
        // 静态声明（未探测）：这三项没有代表路由、也就不发探测请求；取值与 capabilities() 同源，
        // 不写成 SUPPORTED，也不谎称是探测结果
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
        // 声明门（不联网）：capabilities() 判 UNDETERMINED 最常见的原因就是这一段声明没写。
        // 运维看得到的就是这段文字，所以必须点名"哪个键、该写什么值"——否则他只能去读源码；
        // 键名与取值一律经常量拼，不为了写话术再抄一遍字面量（全类单点定义）。
        String declaredFormat = declaredFormatStatic(config.configJson());
        boolean formatMatches = CONFIG_FORMAT_VALUE.equals(declaredFormat);
        text.append("。声明门（不联网，决定 capabilities() 的取值）：product/user/order 三段需要 config_json.")
                .append(CONFIG_FORMAT_KEY).append('=').append(CONFIG_FORMAT_VALUE);
        if (formatMatches) {
            text.append("（当前已声明 = ").append(declaredFormat).append("）");
        } else {
            text.append("，当前").append(declaredFormat == null
                            ? CONFIG_FORMAT_KEY + " 没声明（键缺失、取值不是文本，或 config_json 不是合法 JSON）"
                            : "声明为 " + declaredFormat + "，不等于 " + CONFIG_FORMAT_VALUE)
                    .append(" ⇒ 三段一律 UNDETERMINED，引擎的能力门会在发任何请求之前挡下整个运行；"
                            + "上面的 OPTIONS 判定只反映探测本身的应答形态，不能当成\"配置已按格式声明\"");
        }
        text.append("。admin/refund/reset_state 是静态声明为 ABSENT（未探测）：它们没有代表路由，"
                + "本次探测一条请求都没为它们发——不要把这三个 ABSENT 读成\"测过所以没有\"，"
                + "它们来自\"第二家商城的公开接口清单\"这份声明");
        if (declaredBehaviorPathStatic(config.configJson()) == null) {
            text.append("。behavior 另需在 config_json.").append(CONFIG_BEHAVIOR_PATH)
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
     * <p>只经 {@link BigDecimal#movePointLeft(int)}，位数由 {@link #CENTS_PER_YUAN} 推导
     * （见 {@link #CENTS_PER_YUAN_LOG10}），<b>全程不经 double</b>（{@code 12345 / 100.0} 这类写法会带出尾差，
     * 下游 Hive 侧按 {@code decimal(18,2)} 读出来就是"金额对不上"）。</p>
     *
     * <p>契约边界：商城的金额是<b>非负整数分</b>。小数分（如 {@code "12.345"}，即半分）与负分
     * （如 {@code "-585"}）都说明对方口径与本适配器不一致，这里<b>一律响亮失败</b>，既不擅自取整
     * 也不取绝对值——把读不懂的金额悄悄改成另一个数，比报错更糟。</p>
     *
     * <p><b>失败一律是 {@link MallOperationException}</b>（含负数这一种）：两个调用点
     * （{@link #toProduct}、{@link #readCreatedOrder}）都按"这个报价读不懂"处理它，这也正是
     * {@code engine/MallApiDispatchSink#write} 的 catch 面。金额类失败绝不能以
     * {@code IllegalArgumentException} 的形式穿透——那会绕过"记失败 + 记流水 + 记运行说明"的出口。
     * {@code ExternalProduct} 的"金额非负"是最后一道<b>类型不变量</b>，不是本方法的输入校验。</p>
     *
     * @throws MallOperationException 空值、非数字、带符号、小数分或任何不是非负整数的取值
     */
    public static BigDecimal centsToYuan(String cents) {
        String text = cents == null ? "" : cents.trim();
        if (text.isEmpty()) {
            throw new MallOperationException(OPERATION_MONEY,
                    "商城未给出 unit_price_cents，无法得到规范单价");
        }
        // 只认无符号十进制数字：'-'/'+' 都不匹配（负数不是"另一个合法价格"，是口径不一致）。
        // 长度不设上限：BigDecimal 精确表示任意长度的整数分，19 位也不失真（T2 边界三）。
        if (!text.matches("\\d+")) {
            throw new MallOperationException(OPERATION_MONEY,
                    "unit_price_cents 必须是非负整数分，实际=" + abbreviateText(text)
                            + "（小数分或负数说明商城口径不一致，本适配器既不擅自取整也不取绝对值）");
        }
        // 整数分 → 元：movePointLeft(CENTS_PER_YUAN_LOG10) 恰好得到两位小数，setScale 只是把"两位"
        // 这一契约显式写出来（输入是整数、左移 2 位，因此 setScale 不会发生舍入，不存在 ArithmeticException）
        return new BigDecimal(text)
                .movePointLeft(CENTS_PER_YUAN_LOG10)
                .setScale(YUAN_SCALE);
    }

    // ---------- 词表映射（F-25 的被测对象） ----------

    /**
     * 第二家的商品状态词 → <b>规范词</b>（F-25）：{@code SALE} ⇒ {@code on_sale}。
     *
     * @return 规范词（{@code on_sale}/{@code off_sale}/{@code pending}）；
     *         <b>{@code null} 的语义是"商城的词映射不到规范词表"</b>（含字段缺失/空值），
     *         绝不允许把商城原词放进这个位置——原词要随 {@link ProductPage#unmappedStateWords()} 上报
     */
    public static String toCanonicalProductStatus(String mallState) {
        if (mallState == null) {
            return null;
        }
        return CANONICAL_PRODUCT_STATUS.get(mallState.trim());
    }

    /** 去重 + 字典序（缺口清单要可复现：同一批应答在任何一次运行里得到同一串顺序） */
    private static List<String> sortedDistinct(List<String> words) {
        Set<String> unique = new LinkedHashSet<>(words);
        return unique.stream().sorted().toList();
    }

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
        // "引用名没配"与"引用名是空白串"是两件事（一个是漏配、一个是写错了，排障动作不同），
        // 因此按事实分开说，不合并成一句"未配置"（L5）
        String why;
        if (credential.ref() == null) {
            why = "目标未配置 credential_ref，而第二家商城对 /open/v2/** 全部要求 Bearer，匿名调用必然被拒";
        } else if (credential.ref().isEmpty()) {
            why = "目标的 credential_ref 已设置但去掉空白后是空串（等于没有引用名），"
                    + "而第二家商城对 /open/v2/** 全部要求 Bearer，匿名调用必然被拒";
        } else {
            why = "凭据引用 " + credential.ref() + " 指向的环境变量未设置或为空";
        }
        throw new MallOperationException(operation, "缺少凭据，拒绝发送匿名请求：" + why
                + "（credential_ref 只存引用，取值由部署环境注入）");
    }

    /**
     * 凭据状态：<b>"引用名没配"与"引用名是空白串"分成两种状态</b>（L5）——空白串的 {@code ref}
     * 归一成 {@code ""} 而不是 {@code null}，这样 {@link #requireCredential} 能按事实说话，
     * 不会把"写了个空白引用"报成"没配引用"。
     */
    private Credential credentialState(String credentialRef) {
        if (credentialRef == null) {
            return new Credential(null, null);
        }
        String ref = credentialRef.trim();
        return new Credential(ref, ref.isEmpty() ? null : resolveToken(ref));
    }

    /**
     * 商品应答 → {@link ExternalProduct}：第二家的 {@code sku/title/unit_price_cents/state} 在这里落成规范字段。
     *
     * <p>三个缺口清单由<b>调用方（本次读取）</b>持有并传入：<b>(1)</b> 认不出的状态原词进
     * {@code unmappedWords}；<b>(2)</b> 商城没给状态字段的商品 ID 进 {@code stateMissing}；
     * <b>(3)</b> 报价读不懂的商品 ID 进 {@code priceUnreadable}——三者刻意分开：状态原词要补适配器映射表、
     * 状态字段缺失要补商城侧数据、报价读不懂要查商城计价口径，混在一起会让报告说成
     * "商城返回过这个词"。</p>
     *
     * @param priceUnreadable 商城没给 {@code unit_price_cents}（或给的不是非负整数分）的商品 ID 出口；
     *                        被记进去的商品<b>不会</b>出现在返回页里，但一定会被点名报出来——
     *                        出口是 {@link ProductPage#priceUnreadable()}（引擎据此在流水与运行说明里
     *                        记一条缺口），以及"整份目录的报价都读不懂"时的响亮失败，不是 debug 日志
     * @return 规范商品；报价读不懂时返回 {@code null}（由调用方排除并报缺口），绝不返回带 null 金额的商品
     */
    private ExternalProduct toProduct(JsonNode node, List<String> unmappedWords, List<String> stateMissing,
                                      List<String> priceUnreadable) {
        String sku = requireText(node, RESULT_SKU, "listProducts");
        JsonNode category = node.path(RESULT_CATEGORY);
        JsonNode state = node.path(RESULT_STATE);
        String mallState = state.isMissingNode() || state.isNull() ? null : state.asText();
        String canonical = toCanonicalProductStatus(mallState);
        if (canonical == null) {
            if (mallState == null || mallState.isBlank()) {
                // 字段缺失/空值不是"商城给了一个词"：只记商品 ID，绝不混进未映射原词清单
                stateMissing.add(sku);
            } else {
                // "读不懂的词"必须留痕：登记原词，交引擎报成缺口（件数 + 原词）。
                // 绝不静默丢弃，也绝不把原词写进规范字段 status（那会产出违约数据）。
                unmappedWords.add(mallState.trim());
            }
            log.debug("第二家商城返回了未登记的商品状态词，已登记为目录缺口：state={} sku={}", mallState, sku);
        }
        // 报价：整数分 → 元。这一段的失败不许被 catch 成 null 继续跑——"读不懂的价格"
        // 与"读不懂的状态词"不同，它会给下游留一个**看起来正常**的金额口径。
        // 处理方式是"排除 + 点名"（与状态缺口同一形态：商品进不了可用目录，缺口被报出来），
        // 而不是把一件金额未知的商品留在目录里。
        BigDecimal price;
        try {
            price = centsToYuan(node.path(RESULT_UNIT_PRICE_CENTS).asText(null));
        } catch (MallOperationException e) {
            priceUnreadable.add(sku);
            log.debug("第二家商城的报价读不懂，商品已排除并登记为目录缺口：sku={} 原因={}", sku, e.getMessage());
            return null;
        }
        return new ExternalProduct(sku, node.path(RESULT_TITLE).asText(null),
                category.isNumber() ? category.asLong() : parseLongOrNull(category.asText(null)),
                price, canonical);
    }

    /**
     * 下单应答 → {@link ExternalOrder}：除了通用订单快照，还<b>必须</b>带回商城记账的明细单价
     * （{@code result.lines[].unit_price_cents}）。
     *
     * <p><b>价格缺失即响亮失败</b>：下单成功了却读不到单价，说明"这一单到底按什么价成交"这个事实
     * 在本次调用里不成立——此时返回一个 {@code totalAmount} 可能非空的订单，会让流水与运行报告
     * 看起来一切正常，把"读不懂的金额"静默带过（硬约束 3 与 §4.1.1 的金额口径）。因此这里逐行校验：
     * 没有 {@code lines}、某行没有 {@code unit_price_cents}、或该值不是合法整数分，一律抛
     * {@link MallOperationException}，绝不带 {@code null} 继续。</p>
     *
     * <p>上面这三条就是本方法的<b>全部</b>失败出口，每条都有夹具形态与用例钉着（T2d：
     * {@code OMIT_LINES}/{@code EMPTY_LINES}/{@code OMIT_LINE_PRICE} 与非法单价）。</p>
     *
     * <p>"{@code result} 是不是对象"<b>不在这里判</b>：唯一的调用方
     * {@link #createOrder} 在调本方法之前已经过 {@code requireText(result, order_no)}
     * （它第一件事就是判类型），再判一次就是同一条前置条件的<b>第二个所有者</b>——
     * 那时把任一处删掉都不会有用例变红，而"读不出订单"这件事仍然响亮失败。
     * 同一条规则只留一个所有者，所以这里只声明前置条件。</p>
     *
     * @param result 商城应答的 {@code result}，<b>必须是对象</b>（由调用方保证）
     */
    private static ExternalOrder readCreatedOrder(JsonNode result, String orderNo, String buyerRef) {
        JsonNode lines = result.path(RESULT_LINES);
        if (!lines.isArray() || lines.isEmpty()) {
            throw new MallOperationException("createOrder",
                    "应答 result 缺少 lines 明细，读不到商城记账单价：" + abbreviate(result)
                            + "；本适配器不接受\"下单成功但成交价未知\"");
        }
        for (JsonNode line : lines) {
            String cents = line.path(RESULT_UNIT_PRICE_CENTS).asText(null);
            if (cents == null || cents.isBlank()) {
                throw new MallOperationException("createOrder",
                        "应答 result 的明细行缺少 " + RESULT_UNIT_PRICE_CENTS + "（sku="
                                + line.path(RESULT_SKU).asText("?") + "）：价格缺失即响亮失败，"
                                + "绝不带 null 金额继续：" + abbreviate(result));
            }
            // 复用同一条换算口径：小数分/非整数在这里就炸，不留到下游才发现"金额对不上"
            centsToYuan(cents);
        }
        return readOrder(result, orderNo, buyerRef, "createOrder");
    }

    /**
     * 订单类应答 → {@link ExternalOrder}：第二家的 {@code order_no/pay_state/total_cents} 落成规范字段。
     *
     * <p><b>{@code status} 是商城原文的逐字符透出</b>（指导书 V2.4 §4.1.1.3"商城原样文本"）：
     * {@code pay_state} 是什么就写什么（{@code NEW}/{@code SETTLED}/{@code VOID}），
     * <b>不加括号别名、不翻译成生成器的枚举</b>——一旦拼上"等价词"，同一个字段就有了两种口径，
     * 按状态词聚合/对账的消费方会被分成两个桶；而且没有任何契约字段声明过"别名后缀"这种东西。</p>
     *
     * <p>商城没给 {@code pay_state} 时保持 {@code null}（{@link ExternalOrder} 的紧凑构造器把它读成
     * "无法读取"，那里是"空白 / 缺失 ⇒ null"这条规则的<b>唯一所有者</b>），
     * <b>不编造一个商城没给过的词</b>。因此<b>本方法不做</b>空白判断：在这里再写一次
     * {@code isBlank() ? null : …}，就会让同一条规则有两个所有者，而文档指认的那个（构造器）
     * 在读取路径上永远不生效——删掉构造器那两行也没人会发现（M2）。</p>
     *
     * <p>{@code pay}/{@code cancel} 的应答按商城自己的形态可能不带明细行，因此这里对 {@code lines}
     * 只做"有就计数"的处理；下单路径的单价校验在 {@link #readCreatedOrder} 里单独做。</p>
     */
    private static ExternalOrder readOrder(JsonNode result, String orderNo, String buyerRef, String operation) {
        if (result == null || !result.isObject()) {
            throw new MallOperationException(operation,
                    "应答 result 不是对象，读不到订单快照：" + abbreviate(result));
        }
        JsonNode state = result.path(RESULT_PAY_STATE);
        // 原样透出（含空白）：空白/缺失落成 null 是 ExternalOrder 构造器的事，这里不抢它的活
        String mallState = isPresent(state) ? state.asText() : null;
        JsonNode total = result.path(RESULT_TOTAL_CENTS);
        BigDecimal totalYuan = isReadableCents(total) ? centsToYuan(total.asText()) : null;
        JsonNode lines = result.path(RESULT_LINES);
        return new ExternalOrder(orderNo, buyerRef, mallState, totalYuan, lines.isArray() ? lines.size() : -1);
    }

    /** 该节点是不是"商城真的给了值"（字段缺失与显式 null 都算"没给"，与空白串区分开） */
    private static boolean isPresent(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull();
    }

    /**
     * 该节点是不是"读得出的整数分"：数字与字符串都收（第二家的 {@code total_cents} 两种形态都出现过）。
     *
     * <p>字段缺失、显式 {@code null}、布尔/对象/数组都返回 {@code false} ⇒ 总额保持 {@code null}
     * （"没给/读不懂"这一种表达，与 §4.1.1.3 的"未返回记 null"一致）。
     * 单独成方法是为了与 {@link #isPresent} 一个风格：判据有名字，读代码的人不必每次重新解释
     * 内联的 {@code isNumber() || isTextual()}。</p>
     */
    private static boolean isReadableCents(JsonNode node) {
        return node != null && (node.isNumber() || node.isTextual());
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

    /** 读 {@code config_json} 的 {@link #CONFIG_FORMAT_KEY} 取值；读法与判定都走这一个键常量（单点定义） */
    private String declaredFormat(String configJson) {
        return declaredFormatStatic(configJson);
    }

    /**
     * 与 {@link #declaredFormat(String)} 同口径的静态版本（运维可见的探测说明在静态上下文里拼装）。
     *
     * <p>只认<b>文本</b>取值：键不存在、值是数字/对象、或 {@code config_json} 不是合法 JSON，一律当作
     * "没声明"——不猜、不做类型转换、不发请求。</p>
     */
    private static String declaredFormatStatic(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            JsonNode value = CONFIG_READER.readTree(configJson).path(CONFIG_FORMAT_KEY);
            return value.isTextual() ? value.asText() : null;
        } catch (IOException e) {
            log.warn("config_json 不是合法 JSON，已忽略其中的声明：{}", e.getMessage());
            return null;
        }
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
