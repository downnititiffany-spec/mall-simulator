package com.graduation.generator.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
import java.util.function.Function;

/**
 * 参考商城（{@code reference-mall} / 8090）的 HTTP 适配器：按公开 REST 路由<b>实测</b>目标商城能力。
 *
 * <p><b>只发 OPTIONS</b>。探测绝不能在别人的商城里下单、退款、改库存——那不是"检查"，是污染数据。
 * {@code OPTIONS} 是安全方法，Spring MVC 对已映射路径会回 2xx（真实应答由 E3 对 8090 的实测给出，
 * 本类不假设），因此"路径存在与否"可以在零副作用下测出来。这条约束由测试
 * {@code probeUsesOnlySafeMethodsAndNeverWrites} 看守。</p>
 *
 * <p><b>路由表从哪来</b>：公开路由取自 2026-09-11 对 8090 的实测清单（{@code /api/v1/mall/products}、
 * {@code /users}、{@code /orders}、{@code /orders/{id}/pay|cancel}、{@code /orders/{id}/refunds}、
 * {@code /refunds/{id}/complete}、{@code /api/v1/admin/products[/{id}/price|stock|status]}），
 * 路径变量用字面量 {@code 0} 占位。两处<b>不猜</b>：公开行为埋点接口（B-04 记载参考商城尚无此接口）与
 * 受保护的重置接口，路径都未冻结，只能由 {@code config_json.behavior_path} / {@code config_json.reset_path}
 * 声明；未声明一律 {@link CapabilityVerdict#UNDETERMINED}。</p>
 *
 * <p><b>凭据</b>：{@code credential_ref} 是环境变量名，适配器按引用取值当 Bearer 令牌，对<b>所有</b>代表路由都附带头。
 * 这不是"顺手统一"，而是 2026-09-11 对 8090 实测出来的：参考商城对 {@code /api/v1/mall/**} 与
 * {@code /api/v1/admin/**} 都要求 Bearer，<b>匿名</b>发 OPTIONS 时连公开路由也回 401，于是"只给 /admin 带凭据"
 * 会把公开能力全判成未证实（第一次 E3 实测即如此，见 {@code docs/acceptance/m1-4-s4a-adapter-20260911}）。
 * 取不到凭据就照常匿名探测，判 {@code UNDETERMINED} 并在说明里点名缺哪个变量。凭据值永不进 detail、永不入库。</p>
 */
public final class ReferenceMallHttpAdapter implements MallTargetAdapter {

    private static final Logger log = LoggerFactory.getLogger(ReferenceMallHttpAdapter.class);

    public static final String ADAPTER_TYPE = "REFERENCE_MALL_HTTP";

    /** {@code config_json} 里声明公开行为埋点接口路径的键（B-04：路径未冻结，必须显式声明） */
    public static final String CONFIG_BEHAVIOR_PATH = "behavior_path";

    /** {@code config_json} 里声明受保护重置接口路径的键 */
    public static final String CONFIG_RESET_PATH = "reset_path";

    // 公开业务路由模板（唯一来源）：业务方法发请求、operationRoutes 报流水，两处都引这里，
    // 于是"报出去的形状"与"真发出去的形状"不可能各写一份而漂移（硬约束 6）。
    private static final String PRODUCTS_PATH = "/api/v1/mall/products";
    private static final String USERS_PATH = "/api/v1/mall/users";
    private static final String ORDERS_PATH = "/api/v1/mall/orders";
    private static final String PAY_PATH = ORDERS_PATH + "/{orderId}/pay";
    private static final String CANCEL_PATH = ORDERS_PATH + "/{orderId}/cancel";
    private static final String REFUND_APPLY_PATH = ORDERS_PATH + "/{orderId}/refunds";
    private static final String REFUND_COMPLETE_PATH = "/api/v1/mall/refunds/{refundId}/complete";

    /** 公开业务路由（参考商城实测：公开路由同样要求 Bearer，故有凭据时一并发头）；插入顺序即报告顺序 */
    private static final Map<MallCapability, List<String>> PUBLIC_ROUTES = publicRoutes();

    /** 管理员路由；缺凭据时会回 401，于是判 UNDETERMINED */
    private static final Map<MallCapability, List<String>> ADMIN_ROUTES = adminRoutes();

    private final Function<String, String> credentialLookup;
    private final Duration timeout;
    /**
     * JDK HttpClient 自带连接池/Selector 线程，必须按适配器实例复用。
     * 如果每次请求都 newBuilder().build()，600-event MALL_API 真跑会持续创建新的内部线程，
     * 最终把 Windows 原生线程额度耗尽（Stage 7 Batch S 曾实测到 _beginthreadex EACCES）。
     */
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param credentialLookup 按 {@code credential_ref} 取凭据（生产传 {@code System::getenv}）
     * @param timeout          建连与单条请求的超时
     */
    public ReferenceMallHttpAdapter(Function<String, String> credentialLookup, Duration timeout) {
        this.credentialLookup = credentialLookup;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 生产装配形态：凭据取环境变量 */
    public static ReferenceMallHttpAdapter withEnvironment(Duration timeout) {
        return new ReferenceMallHttpAdapter(System::getenv, timeout);
    }

    @Override
    public String adapterType() {
        return ADAPTER_TYPE;
    }

    // ---------- §4.1 capabilities()：静态声明（不联网） ----------

    /**
     * 按 {@code base_url} + 凭据 + {@code config_json} 的声明给出"这台适配器应该能做什么"。
     *
     * <p>判定规则（三条都是"不猜"）：① 公开目录路由（product/user/order/refund）在 base_url 合法、
     * 凭据可取时声明为 {@code SUPPORTED}，缺凭据时一律 {@code UNDETERMINED}（D-033 实测：参考商城
     * 对公开路由同样要求 Bearer，匿名连不上就等于没证实）；② {@code behavior}/{@code reset_state}
     * 只有 {@code config_json} 显式声明了路径才可能为 {@code SUPPORTED}，否则 {@code UNDETERMINED}；
     * ③ {@code admin} 依赖凭据，缺凭据即 {@code UNDETERMINED}。</p>
     *
     * <p><b>它不是实测</b>：{@code declared=true}。运行报告记录的是 {@link #test(TargetConfig)} 的结果。</p>
     */
    @Override
    public TargetCapabilities capabilities(TargetConfig config) {
        Map<MallCapability, CapabilityVerdict> verdicts = new EnumMap<>(MallCapability.class);
        Credential credential = credentialState(config.credentialRef());
        boolean usableBase = baseUri(config) != null;
        Map<String, String> declared = declaredPaths(config.configJson());

        for (MallCapability capability : MallCapability.values()) {
            verdicts.put(capability, CapabilityVerdict.UNDETERMINED);
        }
        if (usableBase && credential.available()) {
            verdicts.put(MallCapability.PRODUCT, CapabilityVerdict.SUPPORTED);
            verdicts.put(MallCapability.USER, CapabilityVerdict.SUPPORTED);
            verdicts.put(MallCapability.ORDER, CapabilityVerdict.SUPPORTED);
            verdicts.put(MallCapability.REFUND, CapabilityVerdict.SUPPORTED);
            verdicts.put(MallCapability.ADMIN, CapabilityVerdict.SUPPORTED);
        }
        if (usableBase && declared.containsKey(CONFIG_BEHAVIOR_PATH)) {
            verdicts.put(MallCapability.BEHAVIOR, CapabilityVerdict.SUPPORTED);
        }
        if (usableBase && credential.available() && declared.containsKey(CONFIG_RESET_PATH)) {
            verdicts.put(MallCapability.RESET_STATE, CapabilityVerdict.SUPPORTED);
        }
        return TargetCapabilities.declared(verdicts);
    }

    /**
     * §4.1.1 的 {@code operationRoutes}：本适配器<b>真实使用</b>的方法与路径（硬约束 6 的所有权落点）。
     *
     * <p>逐条对应下方七个业务方法里发出去的请求，路径模板用 {@code {orderId}}/{@code {refundId}} 占位；
     * <b>不含</b> base_url、凭据与 {@code ?categoryId=} 查询串。{@code emitBehavior} 的路径未冻结
     * （B-04：参考商城当前尚无该接口），<b>只有</b> {@code config_json.behavior_path} 显式声明时才出现该键；
     * 未声明就不给条目——"路由未知"必须诚实为空，而不是猜一个。</p>
     *
     * <p>{@code refund} 一次走两步（申请 + 完成），这里给出第一步（申请）——它是这次操作的主体路径，
     * 与流水里"一行 = 一次操作"的口径一致（第二步在流水里不单独占行，见 {@code MallApiDispatchSink}）。</p>
     */
    @Override
    public Map<String, TargetRoute> operationRoutes(TargetConfig config) {
        // 键是 §4.1.1.1 的 SPI 操作名字面量；适配器不依赖 engine 包（那是调用方），所以这里写字面量。
        Map<String, TargetRoute> routes = new LinkedHashMap<>();
        routes.put("listProducts", new TargetRoute("GET", PRODUCTS_PATH));
        routes.put("createSyntheticUser", new TargetRoute("POST", USERS_PATH));
        routes.put("createOrder", new TargetRoute("POST", ORDERS_PATH));
        routes.put("pay", new TargetRoute("POST", PAY_PATH));
        routes.put("cancel", new TargetRoute("POST", CANCEL_PATH));
        routes.put("refund", new TargetRoute("POST", REFUND_APPLY_PATH));
        String behaviorPath = declaredPaths(config.configJson()).get(CONFIG_BEHAVIOR_PATH);
        if (behaviorPath != null) {
            routes.put("emitBehavior", new TargetRoute("POST", behaviorPath));
        }
        return Map.copyOf(routes);
    }

    // ---------- §4.1 其余七项：真实调用 ----------

    @Override
    public ProductPage listProducts(TargetConfig config, ProductQuery query) {
        if (query == null) {
            throw new MallOperationException("listProducts", "ProductQuery 不得为 null");
        }
        JsonNode data = call(config, "listProducts", "GET",
                PRODUCTS_PATH + categoryQuery(query.categoryId()),
                null, query.offset() + query.limit());
        if (!data.isArray()) {
            throw new MallOperationException("listProducts",
                    "应答 data 不是数组，无法解析商品列表：" + abbreviate(data));
        }
        List<ExternalProduct> all = new ArrayList<>();
        for (JsonNode node : data) {
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
        body.put("ageGroup", command.ageGroup());
        body.put("cityLevel", command.cityLevel());
        body.put("memberLevel", command.memberLevel());
        JsonNode data = call(config, "createSyntheticUser", "POST", USERS_PATH, body, 0);
        return new ExternalUser(requireId(data, "userId", "createSyntheticUser"), command.memberLevel());
    }

    @Override
    public void emitBehavior(TargetConfig config, BehaviorCommand command) {
        if (command == null) {
            throw new MallOperationException("emitBehavior", "BehaviorCommand 不得为 null");
        }
        String path = declaredPaths(config.configJson()).get(CONFIG_BEHAVIOR_PATH);
        if (path == null) {
            throw new MallOperationException("emitBehavior", "目标未声明行为埋点接口：请在 config_json."
                    + CONFIG_BEHAVIOR_PATH + " 里给出公开埋点路径（B-04：参考商城当前尚无该接口，"
                    + "未声明即能力 ABSENT，生成器不会猜一个路由去发、也不会静默跳过）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", command.userId());
        body.put("productId", command.productId());
        body.put("sessionId", command.sessionId());
        body.put("behaviorType", command.behaviorType());
        body.put("channel", command.channel());
        call(config, "emitBehavior", "POST", path, body, 0);
    }

    @Override
    public ExternalOrder createOrder(TargetConfig config, OrderCommand command) {
        if (command == null) {
            throw new MallOperationException("createOrder", "OrderCommand 不得为 null");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", command.userId());
        body.put("items", command.items().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", item.productId());
            row.put("quantity", item.quantity());
            return row;
        }).toList());
        JsonNode data = call(config, "createOrder", "POST", ORDERS_PATH, body, 0);
        return readOrder(data, command.userId(), "createOrder", null);
    }

    @Override
    public ExternalOrder pay(TargetConfig config, PayCommand command) {
        if (command == null) {
            throw new MallOperationException("pay", "PayCommand 不得为 null");
        }
        // 参考商城的支付应答是 ApiResponse<Void>（data=null，实测商城订单接口 L80）：HTTP 2xx + code=OK
        // 就是"已支付"，没有可读的状态字段可解析——这里的 PAID 是"调用成功"的记录，不是从应答里读来的值。
        call(config, "pay", "POST",
                PAY_PATH.replace("{orderId}", encode(command.orderId())),
                Map.of("userId", command.userId()), 0);
        return new ExternalOrder(command.orderId(), command.userId(), "PAID", null, -1);
    }

    @Override
    public ExternalOrder cancel(TargetConfig config, CancelCommand command) {
        if (command == null) {
            throw new MallOperationException("cancel", "CancelCommand 不得为 null");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", command.userId());
        body.put("reason", command.reason());
        // 同上：取消也是 ApiResponse<Void>
        call(config, "cancel", "POST",
                CANCEL_PATH.replace("{orderId}", encode(command.orderId())), body, 0);
        return new ExternalOrder(command.orderId(), command.userId(), "CANCELLED", null, -1);
    }

    @Override
    public ExternalRefund refund(TargetConfig config, RefundCommand command) {
        if (command == null) {
            throw new MallOperationException("refund", "RefundCommand 不得为 null");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", command.userId());
        body.put("amount", command.amount());
        body.put("reason", command.reason());
        JsonNode data = call(config, "refund", "POST",
                REFUND_APPLY_PATH.replace("{orderId}", encode(command.orderId())), body, 0);
        String refundId = requireId(data, "refundId", "refund");
        // 参考商城的退款是两步（申请 + 完成）；只申请不完成会留下"永远不完成"的退款单，
        // 因此这里把完成也走一遍——两步都成，返回的退款项才算 COMPLETED。
        call(config, "refund", "POST", REFUND_COMPLETE_PATH.replace("{refundId}", encode(refundId)),
                Map.of("userId", command.userId()), 0);
        return new ExternalRefund(refundId, command.orderId(), "COMPLETED", command.amount());
    }

    // ---------- 调用原语 ----------

    /**
     * 一次真实业务调用：附凭据 → 发请求 → 解信封 → 校验 {@code code==OK} → 返回 {@code data}。
     *
     * <p>四条失败路径都抛 {@link MallOperationException}，信息里带操作名、URL 与商城原话：
     * 缺凭据（发请求之前就抛，绝不匿名发）、连不上、HTTP 状态非 2xx、{@code code != OK}。</p>
     *
     * @param expectSize 期望的 {@code data} 是数组时其大小（用于内存上界）；非数组传 0
     */
    private JsonNode call(TargetConfig config, String operation, String method, String path,
                          Object body, int expectSize) {
        URI uri = baseUri(config);
        if (uri == null) {
            throw new MallOperationException(operation,
                    "base_url 非法或为空，无法调用：" + config.baseUrl());
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
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
        String code = envelope.path("code").asText("");
        if (status < 200 || status >= 300 || !"OK".equals(code)) {
            String message = envelope.path("message").asText("");
            throw new MallOperationException(operation, "商城拒绝：" + uri + " → HTTP " + status
                    + " code=" + (code.isEmpty() ? "(缺失)" : code)
                    + " message=" + (message.isEmpty() ? "(无)" : message));
        }
        JsonNode data = envelope.path("data");
        if (data.isArray() && expectSize > 0 && data.size() > expectSize) {
            // 参考商城无分页参数（客户端侧分页），这里只做"别把整表读进内存"的上界保护
            log.debug("{} 返回 {} 条，超出本次请求上界 {}（客户端侧分页仍会裁剪）", path, data.size(), expectSize);
        }
        return data;
    }

    /** 取凭据；取不到就抛（响亮失败），信息只出现引用名，绝不出现取值 */
    private String requireCredential(TargetConfig config, String operation) {
        Credential credential = credentialState(config.credentialRef());
        if (credential.available()) {
            return credential.token();
        }
        String why = credential.ref() == null
                ? "目标未配置 credential_ref，而参考商城对 /api/v1/** 全部要求 Bearer（D-033 实测），"
                + "匿名调用必然 401"
                : "凭据引用 " + credential.ref() + " 指向的环境变量未设置或为空";
        throw new MallOperationException(operation, "缺少凭据，拒绝发送匿名请求：" + why
                + "（credential_ref 只存引用，取值由部署环境注入，见 D-033）");
    }

    private Credential credentialState(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return new Credential(null, null);
        }
        return new Credential(credentialRef.trim(), resolveToken(credentialRef));
    }

    /** 商品应答 → {@link ExternalProduct}；缺 productId 直接抛，不返回半成品 */
    private static ExternalProduct toProduct(JsonNode node) {
        JsonNode id = node.path("productId");
        if (id.isMissingNode() || id.isNull() || id.asText().isBlank()) {
            throw new MallOperationException("listProducts",
                    "商品缺少 productId，无法作为可引用主体：" + abbreviate(node));
        }
        JsonNode price = node.path("price");
        java.math.BigDecimal amount = price.isNumber() || price.isTextual()
                ? new java.math.BigDecimal(price.asText())
                : java.math.BigDecimal.ZERO;
        JsonNode category = node.path("categoryId");
        JsonNode status = node.path("status");
        return new ExternalProduct(id.asText(), node.path("productName").asText(null),
                category.isNumber() ? category.asLong() : null, amount,
                status.isMissingNode() || status.isNull() ? null : status.asText());
    }

    /**
     * 订单类应答 → {@link ExternalOrder}。
     *
     * <p>{@code defaultStatus} 用于商城只回 {@code {"orderId": "..."}}（下单）或 {@code data=null}
     * （支付/取消）的情形——没有状态字段时记调用成功对应的状态，而不是编造服务端返回值。</p>
     */
    private static ExternalOrder readOrder(JsonNode data, String userId, String operation, String defaultStatus) {
        String orderId = requireId(data, "orderId", operation);
        JsonNode status = data.path("status");
        JsonNode total = data.path("totalAmount");
        JsonNode items = data.path("items");
        return new ExternalOrder(orderId, userId,
                status.isMissingNode() || status.isNull() ? defaultStatus : status.asText(),
                total.isNumber() || total.isTextual() ? new java.math.BigDecimal(total.asText()) : null,
                items.isArray() ? items.size() : -1);
    }

    private static String requireId(JsonNode data, String field, String operation) {
        if (data == null || !data.isObject()) {
            throw new MallOperationException(operation,
                    "应答缺少 data 对象，读不到 " + field + "：" + abbreviate(data));
        }
        JsonNode value = data.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            throw new MallOperationException(operation, "应答 data 缺少字段 " + field + "（实际=" + abbreviate(data)
                    + "）；不返回空 ID 冒充成功");
        }
        return value.asText();
    }

    private static String categoryQuery(Long categoryId) {
        return categoryId == null ? "" : "?categoryId=" + categoryId;
    }

    private static String encode(String pathSegment) {
        return java.net.URLEncoder.encode(pathSegment, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 报文/异常摘要：单行、限长，避免把整页 HTML 或长栈塞进运行报告 */
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

    @Override
    public TargetCheckResult test(TargetConfig config) {
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            return unmeasured(config, "base_url 为空，无法探测商城能力");
        }
        URI base;
        try {
            base = URI.create(config.baseUrl().trim());
        } catch (IllegalArgumentException e) {
            return unmeasured(config, "base_url 不是合法 URI：" + config.baseUrl());
        }
        if (base.getHost() == null) {
            return unmeasured(config, "base_url 缺少 host：" + config.baseUrl());
        }

        Map<String, String> declared = declaredPaths(config.configJson());
        String token = resolveToken(config.credentialRef());
        String credentialNote = credentialNote(config.credentialRef(), token);

        // 路径 → 它能为哪些能力作证
        Map<String, List<MallCapability>> routes = new LinkedHashMap<>();
        collect(routes, PUBLIC_ROUTES);
        collect(routes, ADMIN_ROUTES);
        declare(routes, declared.get(CONFIG_BEHAVIOR_PATH), MallCapability.BEHAVIOR);
        declare(routes, declared.get(CONFIG_RESET_PATH), MallCapability.RESET_STATE);

        Map<String, RouteOutcome> outcomes = new LinkedHashMap<>();
        int answered = 0;
        IOException lastFailure = null;
        for (String path : routes.keySet()) {
            RouteOutcome outcome = send(httpClient, base, path, token);
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
            log.warn("目标能力探测失败：id={} {}", config.id(), why);
            return unmeasured(config, why);
        }

        Map<MallCapability, CapabilityVerdict> verdicts = new EnumMap<>(MallCapability.class);
        for (MallCapability capability : MallCapability.values()) {
            boolean declaredCapability = capability == MallCapability.BEHAVIOR
                    || capability == MallCapability.RESET_STATE;
            String configKey = capability == MallCapability.BEHAVIOR ? CONFIG_BEHAVIOR_PATH : CONFIG_RESET_PATH;
            verdicts.put(capability, declaredCapability && !declared.containsKey(configKey)
                    ? CapabilityVerdict.UNDETERMINED
                    : aggregate(routes, outcomes, capability));
        }

        String detail = describe(base, routes, outcomes, verdicts, credentialNote, declared);
        log.info("目标能力探测完成：id={} {}", config.id(), verdicts);
        return new TargetCheckResult(config.id(), true, detail, verdicts);
    }

    // ---------- 判定 ----------

    /**
     * 代表路由里只要有一条 404 就是 {@code ABSENT}（接口确实没有）；只要有一条 401/403、未预期状态码或无应答，
     * 就只能 {@code UNDETERMINED}（存在但未证实）；全部 2xx 才 {@code SUPPORTED}。
     */
    private static CapabilityVerdict aggregate(Map<String, List<MallCapability>> routes,
                                               Map<String, RouteOutcome> outcomes,
                                               MallCapability capability) {
        List<RouteStatus> own = new ArrayList<>();
        for (Map.Entry<String, List<MallCapability>> entry : routes.entrySet()) {
            if (entry.getValue().contains(capability)) {
                own.add(outcomes.get(entry.getKey()).status());
            }
        }
        if (own.isEmpty()) {
            return CapabilityVerdict.UNDETERMINED;
        }
        if (own.contains(RouteStatus.MISSING)) {
            return CapabilityVerdict.ABSENT;
        }
        if (own.contains(RouteStatus.GATED) || own.contains(RouteStatus.UNEXPECTED)
                || own.contains(RouteStatus.FAILED)) {
            return CapabilityVerdict.UNDETERMINED;
        }
        return CapabilityVerdict.SUPPORTED;
    }

    // ---------- 探测原语 ----------

    private RouteOutcome send(HttpClient client, URI base, String path, String token) {
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
            return new RouteOutcome(classify(response.statusCode()), response.statusCode(), null);
        } catch (IOException e) {
            return new RouteOutcome(RouteStatus.FAILED, -1, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RouteOutcome(RouteStatus.FAILED, -1, new IOException("探测被中断", e));
        }
    }

    private static RouteStatus classify(int status) {
        if (status >= 200 && status < 300) {
            return RouteStatus.HTTP_ANSWERED;
        }
        if (status == 401 || status == 403) {
            return RouteStatus.GATED;
        }
        if (status == 404) {
            return RouteStatus.MISSING;
        }
        return RouteStatus.UNEXPECTED;
    }

    private static String describe(URI base, Map<String, List<MallCapability>> routes,
                                   Map<String, RouteOutcome> outcomes,
                                   Map<MallCapability, CapabilityVerdict> verdicts,
                                   String credentialNote, Map<String, String> declared) {
        StringBuilder text = new StringBuilder("OPTIONS 探测 ").append(base)
                .append("：").append(routes.size()).append(" 条代表路由");
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
        if (!credentialNote.isEmpty()) {
            text.append("。").append(credentialNote);
        }
        if (!declared.containsKey(CONFIG_BEHAVIOR_PATH)) {
            text.append("。behavior 需在 config_json.").append(CONFIG_BEHAVIOR_PATH)
                    .append(" 声明公开行为埋点接口路径（参考商城尚无该接口，路径未冻结，B-04）");
        }
        if (!declared.containsKey(CONFIG_RESET_PATH)) {
            text.append("。reset_state 需在 config_json.").append(CONFIG_RESET_PATH)
                    .append(" 声明受保护重置接口路径");
        }
        return text.toString();
    }

    // ---------- 配置与凭据 ----------

    /** base_url → {@link URI}；非法或缺 host 时返回 null（调用方据此响亮失败） */
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

    private Map<String, String> declaredPaths(String configJson) {
        Map<String, String> declared = new LinkedHashMap<>();
        if (configJson == null || configJson.isBlank()) {
            return declared;
        }
        JsonNode root;
        try {
            root = mapper.readTree(configJson);
        } catch (IOException e) {
            log.warn("config_json 不是合法 JSON，已忽略其中的路径声明：{}", e.getMessage());
            return declared;
        }
        for (String key : List.of(CONFIG_BEHAVIOR_PATH, CONFIG_RESET_PATH)) {
            JsonNode value = root.path(key);
            if (value.isTextual() && value.asText().startsWith("/")) {
                declared.put(key, value.asText());
            } else if (!value.isMissingNode() && !value.isNull()) {
                log.warn("config_json.{} 必须是 '/...' 形式的路径，已忽略：{}", key, value);
            }
        }
        return declared;
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

    /** 缺凭据的说明文本；只用引用名，绝不出现凭据值 */
    private static String credentialNote(String credentialRef, String token) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return "未配置 credential_ref：参考商城对公开路由同样要求 Bearer，匿名探测一律 401，"
                    + "所有能力只能判 UNDETERMINED";
        }
        if (token == null || token.isBlank()) {
            return "凭据引用 " + credentialRef.trim() + " 对应的环境变量未设置或为空，所有路由都无法证实";
        }
        return "已按凭据引用 " + credentialRef.trim() + " 取值并对所有代表路由附带（值不回显）";
    }

    // ---------- 小工具 ----------

    private static void collect(Map<String, List<MallCapability>> routes,
                                Map<MallCapability, List<String>> declared) {
        for (Map.Entry<MallCapability, List<String>> entry : declared.entrySet()) {
            for (String path : entry.getValue()) {
                declare(routes, path, entry.getKey());
            }
        }
    }

    private static void declare(Map<String, List<MallCapability>> routes, String path, MallCapability capability) {
        if (path == null) {
            return;
        }
        List<MallCapability> owners = routes.computeIfAbsent(path, key -> new ArrayList<>());
        if (!owners.contains(capability)) {
            owners.add(capability);
        }
    }

    private static String stripTrailingSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static Map<MallCapability, List<String>> publicRoutes() {
        Map<MallCapability, List<String>> routes = new LinkedHashMap<>();
        routes.put(MallCapability.PRODUCT, List.of("/api/v1/mall/products"));
        routes.put(MallCapability.USER, List.of("/api/v1/mall/users"));
        routes.put(MallCapability.ORDER, List.of(
                "/api/v1/mall/orders",
                "/api/v1/mall/orders/0/pay",
                "/api/v1/mall/orders/0/cancel"));
        routes.put(MallCapability.REFUND, List.of(
                "/api/v1/mall/orders/0/refunds",
                "/api/v1/mall/refunds/0/complete"));
        return routes;
    }

    private static Map<MallCapability, List<String>> adminRoutes() {
        Map<MallCapability, List<String>> routes = new LinkedHashMap<>();
        routes.put(MallCapability.ADMIN, List.of(
                "/api/v1/admin/products",
                "/api/v1/admin/products/0/price",
                "/api/v1/admin/products/0/stock",
                "/api/v1/admin/products/0/status"));
        return routes;
    }

    /** 一次探测的原始事实：分类、观察到的 HTTP 状态码（-1 表示无应答）、可能的异常 */
    private record RouteOutcome(RouteStatus status, int observedStatus, IOException failure) {

        boolean answered() {
            return status != RouteStatus.FAILED;
        }

        String describe() {
            return observedStatus > 0 ? status.name() + "(" + observedStatus + ")" : status.name();
        }
    }

    /** 路由状态分类 */
    private enum RouteStatus {

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

    private static TargetCheckResult unmeasured(TargetConfig config, String detail) {
        Map<MallCapability, CapabilityVerdict> verdicts = new EnumMap<>(MallCapability.class);
        for (MallCapability capability : MallCapability.values()) {
            verdicts.put(capability, CapabilityVerdict.UNDETERMINED);
        }
        return new TargetCheckResult(config.id(), false, detail, verdicts);
    }
}
