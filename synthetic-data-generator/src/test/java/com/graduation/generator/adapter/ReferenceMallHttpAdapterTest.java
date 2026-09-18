package com.graduation.generator.adapter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4a：{@code ReferenceMallHttpAdapter} 的能力探测必须是<b>真 HTTP 实测</b>，不是写死的能力表。
 *
 * <p>本测试起一个真实的 localhost HTTP 服务器（{@code com.sun.net.httpserver}，真套接字、真状态码），
 * 让适配器去探它，再核对判定结果。被测对象是适配器的<b>判定规则</b>：2xx＝支持、
 * 401/403＝存在但需凭据（`UNDETERMINED`）、404＝不存在（`ABSENT`）。
 * 真实商城（Spring MVC）对 OPTIONS 的实际应答由 E3 对 8090 的实测给出，不在本文件里假设。</p>
 *
 * <p>另一条同等重要的断言：探测<b>只允许安全方法</b>（OPTIONS/GET）。对着真实商城做探测绝不能不慎改数据，
 * 因此 {@link #probeUsesOnlySafeMethodsAndNeverWrites()} 逐条核对服务器端记录到的请求方法。</p>
 */
class ReferenceMallHttpAdapterTest {

    private static final String ADMIN_ENV = "GENERATOR_TARGET_TOKEN";

    /** 夹具里受保护路由的判据：路径前缀 + 期望的 Bearer 令牌值（令牌值只存在于夹具内，不写进配置） */
    private static final String GATED_PREFIX = "/api/v1/admin";
    private static final String GATED_TOKEN = "s3cr3t-from-env";

    private FakeMall mall;

    @AfterEach
    void stopMall() {
        if (mall != null) {
            mall.close();
        }
    }

    @Test
    void probeReportsSupportedCapabilitiesFromRealResponses() throws IOException {
        Map<String, Integer> routes = allPublicRoutes(200);
        routes.put("OPTIONS /api/v1/mall/behaviors", 200);
        routes.put("OPTIONS /api/v1/admin/reset", 200);
        mall = new FakeMall(routes, null, null);
        ReferenceMallHttpAdapter adapter = adapter(name -> null);

        TargetCheckResult check = adapter.test(config(mall.baseUrl(), null,
                "{\"behavior_path\":\"/api/v1/mall/behaviors\",\"reset_path\":\"/api/v1/admin/reset\"}"));

        assertTrue(check.reachable(), "服务器在监听，必须判定可达：" + check.detail());
        assertEquals(CapabilityVerdict.SUPPORTED, check.capabilities().get(MallCapability.PRODUCT), check.detail());
        assertEquals(CapabilityVerdict.SUPPORTED, check.capabilities().get(MallCapability.USER), check.detail());
        assertEquals(CapabilityVerdict.SUPPORTED, check.capabilities().get(MallCapability.ORDER), check.detail());
        assertEquals(CapabilityVerdict.SUPPORTED, check.capabilities().get(MallCapability.REFUND), check.detail());
        assertEquals(CapabilityVerdict.SUPPORTED, check.capabilities().get(MallCapability.BEHAVIOR), check.detail());
        assertEquals(CapabilityVerdict.SUPPORTED, check.capabilities().get(MallCapability.RESET_STATE), check.detail());
        assertEquals(CapabilityVerdict.SUPPORTED, check.capabilities().get(MallCapability.ADMIN), check.detail());
    }

    @Test
    void probeReportsAbsentForMissingRoute() throws IOException {
        Map<String, Integer> routes = allPublicRoutes(200);
        routes.remove("OPTIONS /api/v1/mall/orders/0/refunds");
        routes.remove("OPTIONS /api/v1/mall/refunds/0/complete");
        routes.put("OPTIONS /api/v1/mall/orders/0/refunds", 404);
        routes.put("OPTIONS /api/v1/mall/refunds/0/complete", 404);
        mall = new FakeMall(routes, null, null);

        TargetCheckResult check = adapter(name -> null).test(config(mall.baseUrl(), null, null));

        assertEquals(CapabilityVerdict.ABSENT, check.capabilities().get(MallCapability.REFUND),
                "退款路由缺失时必须判定 ABSENT，而不是笼统的不可达：" + check.detail());
        assertEquals(CapabilityVerdict.SUPPORTED, check.capabilities().get(MallCapability.ORDER), check.detail());
    }

    @Test
    void probeReportsUndeterminedWhenCredentialIsMissing() throws IOException {
        mall = new FakeMall(allPublicRoutes(200), GATED_PREFIX, GATED_TOKEN);

        TargetCheckResult check = adapter(name -> null).test(config(mall.baseUrl(), ADMIN_ENV, null));

        assertEquals(CapabilityVerdict.UNDETERMINED, check.capabilities().get(MallCapability.ADMIN),
                "受保护路由返回 401 时只能判 UNDETERMINED（能力存在但未证实）：" + check.detail());
        assertTrue(check.detail().contains(ADMIN_ENV),
                "detail 必须写明缺的是哪个 credential_ref：" + check.detail());
        assertEquals(CapabilityVerdict.SUPPORTED, check.capabilities().get(MallCapability.PRODUCT), check.detail());
    }

    @Test
    void credentialResolvedFromLookupIsAttachedToProbedRoutes() throws IOException {
        mall = new FakeMall(allPublicRoutes(200), GATED_PREFIX, GATED_TOKEN);

        TargetCheckResult check = adapter(name -> ADMIN_ENV.equals(name) ? GATED_TOKEN : null)
                .test(config(mall.baseUrl(), ADMIN_ENV, null));

        assertEquals(CapabilityVerdict.SUPPORTED, check.capabilities().get(MallCapability.ADMIN),
                "带上有效凭据后管理路由必须判 SUPPORTED：" + check.detail());
        assertFalse(check.detail().contains(GATED_TOKEN), "detail 绝不能回显凭据值：" + check.detail());
    }

    @Test
    void credentialIsSentOnPublicRoutesToo() throws IOException {
        // 这条规则来自 2026-09-11 对 8090 的现场实测（E3，见 m1-4-s4a-adapter-20260911）：
        // 参考商城对 /api/v1/mall/** 也要求 Bearer，不带 token 时公开路由同样回 401。
        // 因此"能取到凭据就对所有代表路由附带头"，而不是只给 /api/v1/admin。
        mall = new FakeMall(allPublicRoutes(200), "/", GATED_TOKEN);

        TargetCheckResult check = adapter(name -> GATED_TOKEN).test(config(mall.baseUrl(), ADMIN_ENV, null));

        assertEquals(CapabilityVerdict.SUPPORTED, check.capabilities().get(MallCapability.PRODUCT),
                "公开路由也必须带上凭据，否则会被 401 判成 UNDETERMINED：" + check.detail());
        assertEquals(CapabilityVerdict.SUPPORTED, check.capabilities().get(MallCapability.ADMIN), check.detail());
    }

    @Test
    void behaviorAndResetNeedDeclaredPathBecauseTheirRoutesAreNotFrozen() throws IOException {
        mall = new FakeMall(allPublicRoutes(200), null, null);

        TargetCheckResult check = adapter(name -> null).test(config(mall.baseUrl(), null, "{}"));

        assertEquals(CapabilityVerdict.UNDETERMINED, check.capabilities().get(MallCapability.BEHAVIOR),
                "未声明 behavior_path 时不得猜路径，只能 UNDETERMINED：" + check.detail());
        assertTrue(check.detail().contains("behavior_path"), "detail 必须指出缺哪个配置：" + check.detail());
        assertEquals(CapabilityVerdict.UNDETERMINED, check.capabilities().get(MallCapability.RESET_STATE), check.detail());
    }

    @Test
    void probeReportsUnreachableWhenNothingListens() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        TargetCheckResult check = adapter(name -> null)
                .test(config("http://127.0.0.1:" + closedPort, null, null));

        assertFalse(check.reachable(), "没有监听者时必须判定不可达");
        assertEquals(CapabilityVerdict.UNDETERMINED, check.capabilities().get(MallCapability.PRODUCT),
                "不可达时没有任何实测结论，不能写成 ABSENT：" + check.detail());
        assertNotNull(check.detail());
    }

    @Test
    void probeUsesOnlySafeMethodsAndNeverWrites() throws IOException {
        mall = new FakeMall(allPublicRoutes(200), GATED_PREFIX, GATED_TOKEN);

        adapter(name -> ADMIN_ENV.equals(name) ? GATED_TOKEN : null)
                .test(config(mall.baseUrl(), ADMIN_ENV,
                        "{\"behavior_path\":\"/api/v1/mall/behaviors\",\"reset_path\":\"/api/v1/admin/reset\"}"));

        assertFalse(mall.requests().isEmpty(), "探测必须真的发出请求（否则本测试是空跑）");
        for (String request : mall.requests()) {
            String method = request.substring(0, request.indexOf(' '));
            assertTrue("OPTIONS".equals(method) || "GET".equals(method),
                    "探测只允许安全方法，实际发出：" + request);
        }
    }

    // ---------- 夹具 ----------

    private static ReferenceMallHttpAdapter adapter(Function<String, String> credentialLookup) {
        return new ReferenceMallHttpAdapter(credentialLookup, java.time.Duration.ofSeconds(3));
    }


    private static TargetConfig config(String baseUrl, String credentialRef, String configJson) {
        return new TargetConfig(42L, "REFERENCE_MALL_HTTP", baseUrl, credentialRef, configJson);
    }

    /** 参考商城公开路由的完整集合（与 8090 实测到的路由同名同法） */
    private static Map<String, Integer> allPublicRoutes(int status) {
        Map<String, Integer> routes = new LinkedHashMap<>();
        for (String path : List.of("/api/v1/mall/products", "/api/v1/mall/users", "/api/v1/mall/orders",
                "/api/v1/mall/orders/0/pay", "/api/v1/mall/orders/0/cancel",
                "/api/v1/mall/orders/0/refunds", "/api/v1/mall/refunds/0/complete",
                "/api/v1/admin/products", "/api/v1/admin/products/0/price",
                "/api/v1/admin/products/0/stock", "/api/v1/admin/products/0/status")) {
            routes.put("OPTIONS " + path, status);
        }
        return routes;
    }

    /**
     * 最小真实 HTTP 服务器：按 {@code "METHOD /path"} 查表答状态码；{@code gatedPrefix} 下的路径要求
     * {@code Authorization: Bearer <token>}，缺失或不对就答 401（照参考商城的管理员接口形态）；
     * 未知路径答 404。记录收到的每个请求（供"只发安全方法"的断言使用）。
     *
     * <p>注意：它<b>不</b>模拟 Spring 对 OPTIONS 的处理细节——那属于 E3 对真实 8090 的实测范围。
     * 这里只提供"2xx / 401 / 404"三种可配响应，用来测适配器的判定规则本身。</p>
     */
    private static final class FakeMall implements AutoCloseable {

        private static final int NOT_FOUND = 404;

        private final HttpServer server;
        private final Map<String, Integer> routes;
        private final String gatedPrefix;
        private final String token;
        private final List<String> requests = new CopyOnWriteArrayList<>();

        FakeMall(Map<String, Integer> routes, String gatedPrefix, String token) throws IOException {
            this.routes = Map.copyOf(routes);
            this.gatedPrefix = gatedPrefix;
            this.token = token;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", this::handle);
            this.server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            requests.add(method + " " + path);
            int status;
            if (gatedPrefix != null && path.startsWith(gatedPrefix)) {
                String expected = "Bearer " + token;
                status = expected.equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401;
            } else {
                status = routes.getOrDefault(method + " " + path, NOT_FOUND);
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<String> requests() {
            return List.copyOf(requests);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
