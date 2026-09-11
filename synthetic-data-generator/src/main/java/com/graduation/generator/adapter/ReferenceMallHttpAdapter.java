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

    /** 公开业务路由（参考商城实测：公开路由同样要求 Bearer，故有凭据时一并发头）；插入顺序即报告顺序 */
    private static final Map<MallCapability, List<String>> PUBLIC_ROUTES = publicRoutes();

    /** 管理员路由；缺凭据时会回 401，于是判 UNDETERMINED */
    private static final Map<MallCapability, List<String>> ADMIN_ROUTES = adminRoutes();

    private final Function<String, String> credentialLookup;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param credentialLookup 按 {@code credential_ref} 取凭据（生产传 {@code System::getenv}）
     * @param timeout          建连与单条请求的超时
     */
    public ReferenceMallHttpAdapter(Function<String, String> credentialLookup, Duration timeout) {
        this.credentialLookup = credentialLookup;
        this.timeout = timeout;
    }

    /** 生产装配形态：凭据取环境变量 */
    public static ReferenceMallHttpAdapter withEnvironment(Duration timeout) {
        return new ReferenceMallHttpAdapter(System::getenv, timeout);
    }

    @Override
    public String adapterType() {
        return ADAPTER_TYPE;
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

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        Map<String, RouteOutcome> outcomes = new LinkedHashMap<>();
        int answered = 0;
        IOException lastFailure = null;
        for (String path : routes.keySet()) {
            RouteOutcome outcome = send(client, base, path, token);
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
