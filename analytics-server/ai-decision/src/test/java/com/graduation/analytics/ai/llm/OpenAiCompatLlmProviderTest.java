package com.graduation.analytics.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * {@link OpenAiCompatLlmProvider} 真跑测试：进程内 stub HTTP 服务，**零外网、零真实 key**。
 *
 * <p>标尺 ＝ 设计 V3.0 L552（返回结果显式携带 providerUsed/model/token/耗时/错误；模型超时后模板成功
 * <b>不记</b>真实模型成功）、L581（真实模型试验需正样本、拒绝样本、超时和非法 JSON，
 * «不是只检查 Provider 类存在»）、L771（AI 超时/非法输出/拒绝 ⇒ 标明 template 或 REJECTED）；
 * 指导书阶段6 ④「非法输出/超时回模板并标明」。</p>
 *
 * <p><b>本测试证明的</b>：本类在「HTTP 状态码失败 / 连接或读取失败 / 报文非法（空报文、非 JSON、
 * 缺 choices、content 空白）」三类情形下的<b>行为与错误分类</b>，以及它实际发出的请求（路径、鉴权头、
 * 报文形状）。</p>
 *
 * <p><b>本测试不证明的（不得越界表述）</b>：真实供应商（deepseek 等）可用性、真实 key 生效、
 * 真实模型输出质量、真实网络下的超时阈值是否够用 —— 真实 Provider 调用仍为<b>未测</b>
 * （本机无 key、无外网），只能在真实环境单独取证。stub 服务只复刻 OpenAI 兼容协议的
 * <b>报文形状</b>，不复刻供应商语义。</p>
 */
class OpenAiCompatLlmProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STUB_KEY = "stub-key-not-a-real-credential";

    private HttpServer server;
    private ExecutorService executor;
    private final List<Recorded> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<Response> responder = new AtomicReference<>();
    private String baseUrl;

    /** stub 记下的一条请求（真话来源：被调用方实际收到什么）。 */
    private record Recorded(String method, String path, String body, String auth, String contentType) {
    }

    private record Response(int status, String body, String contentType, long delayMs) {
        static Response json(String body) {
            return new Response(200, body, "application/json", 0L);
        }

        static Response status(int status) {
            return new Response(status, "{\"error\":\"stub\"}", "application/json", 0L);
        }
    }

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stub-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Recorded(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    body,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type")));
            Response r = responder.get();
            if (r.delayMs() > 0) {
                try {
                    Thread.sleep(r.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] out = r.body() == null ? new byte[0] : r.body().getBytes(StandardCharsets.UTF_8);
            if (r.contentType() != null) {
                exchange.getResponseHeaders().add("Content-Type", r.contentType());
            }
            exchange.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        responder.set(Response.json(okBody("真实 stub 摘要")));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private static String okBody(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}";
    }

    /** 与生产默认口径一致的构造（超时走默认值）。 */
    private OpenAiCompatLlmProvider provider() {
        return new OpenAiCompatLlmProvider(baseUrl, STUB_KEY, "test-model",
                OpenAiCompatLlmProvider.DEFAULT_TIMEOUT_MS);
    }

    /** 指定 baseUrl/key/model 的构造（超时走默认值）。 */
    private static OpenAiCompatLlmProvider provider(String base, String key, String model) {
        return new OpenAiCompatLlmProvider(base, key, model, OpenAiCompatLlmProvider.DEFAULT_TIMEOUT_MS);
    }

    private static LlmProvider.AiRequest request() {
        return new LlmProvider.AiRequest("系统提示", "用户内容", 0.2);
    }

    private static LlmProvider.LlmException failureOf(ThrowingCallable call) {
        Throwable t = catchThrowable(call);
        assertThat(t).as("失败必须以 LlmException 形式抛出（携带稳定错误类型）")
                .isInstanceOf(LlmProvider.LlmException.class);
        return (LlmProvider.LlmException) t;
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    // ── 配置面 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("baseUrl 归一化：容忍带/不带 /v1 与尾斜杠，避免 /v1/v1 双重前缀")
    void normalizesBaseUrl() {
        assertThat(OpenAiCompatLlmProvider.normalizeBaseUrl(null)).isEmpty();
        assertThat(OpenAiCompatLlmProvider.normalizeBaseUrl("   ")).isEmpty();
        assertThat(OpenAiCompatLlmProvider.normalizeBaseUrl("https://api.example.com")).isEqualTo("https://api.example.com");
        assertThat(OpenAiCompatLlmProvider.normalizeBaseUrl("https://api.example.com/")).isEqualTo("https://api.example.com");
        assertThat(OpenAiCompatLlmProvider.normalizeBaseUrl("https://api.example.com/v1")).isEqualTo("https://api.example.com");
        assertThat(OpenAiCompatLlmProvider.normalizeBaseUrl("https://api.example.com/v1/")).isEqualTo("https://api.example.com");
        assertThat(OpenAiCompatLlmProvider.normalizeBaseUrl("  http://127.0.0.1:9/v1  ")).isEqualTo("http://127.0.0.1:9");
    }

    @Test
    @DisplayName("healthCheck：key 或 baseUrl 缺一即 false（上层据此走规则回退）")
    void healthCheckNeedsKeyAndBaseUrl() {
        assertThat(provider(baseUrl, "", "m").healthCheck()).isFalse();
        assertThat(provider(baseUrl, "   ", "m").healthCheck()).isFalse();
        assertThat(provider("", STUB_KEY, "m").healthCheck()).isFalse();
        assertThat(provider("   ", STUB_KEY, "m").healthCheck()).isFalse();
        assertThat(provider(baseUrl, STUB_KEY, "m").healthCheck()).isTrue();
    }

    @Test
    @DisplayName("providerName 返回实际模型名；模型未配置退化为协议标识")
    void providerNameIsModel() {
        assertThat(provider(baseUrl, STUB_KEY, "deepseek-chat").providerName())
                .isEqualTo("deepseek-chat");
        assertThat(provider(baseUrl, STUB_KEY, "  ").providerName())
                .isEqualTo("openai-compat");
    }

    @Test
    @DisplayName("未配置 key ⇒ AUTH，且**不发**任何 HTTP 请求（不假称调用过模型）")
    void missingKeyFailsWithoutHttpCall() {
        LlmProvider.LlmException e =
                failureOf(() -> provider(baseUrl, "", "m").complete(request()));
        assertThat(e.type()).isEqualTo("AUTH");
        assertThat(received).as("无 key 时不得发出 HTTP 请求").isEmpty();
    }

    // ── 正样本：请求形状与响应解析 ──────────────────────────────────────────

    @Test
    @DisplayName("正样本：200 合法 JSON ⇒ content/token/model 逐项来自响应报文")
    void parsesSuccessfulResponse() {
        responder.set(Response.json(okBody("sale_amount 上升")));

        LlmProvider.AiResponse resp = provider().complete(request());

        assertThat(resp.content()).isEqualTo("sale_amount 上升");
        assertThat(resp.inputTokens()).isEqualTo(11);
        assertThat(resp.outputTokens()).isEqualTo(7);
        assertThat(resp.model()).isEqualTo("test-model");
    }

    @Test
    @DisplayName("请求侧真话：POST /v1/chat/completions + Bearer key + JSON 报文形状")
    void sendsExpectedHttpRequest() throws Exception {
        provider().complete(request());

        assertThat(received).hasSize(1);
        Recorded r = received.get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/v1/chat/completions");
        assertThat(r.auth()).isEqualTo("Bearer " + STUB_KEY);
        assertThat(r.contentType()).startsWith("application/json");

        JsonNode body = MAPPER.readTree(r.body());
        assertThat(body.path("model").asText()).isEqualTo("test-model");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.path("messages").isArray()).isTrue();
        assertThat(body.path("messages").size()).isEqualTo(2);
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("系统提示");
        assertThat(body.path("messages").path(1).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").path(1).path("content").asText()).isEqualTo("用户内容");
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
    }

    @Test
    @DisplayName("baseUrl 已带 /v1 时，实际路径仍只有一层 /v1（不出现 /v1/v1）")
    void doesNotDoubleV1Prefix() {
        provider(baseUrl + "/v1", STUB_KEY, "test-model").complete(request());

        assertThat(received).hasSize(1);
        assertThat(received.get(0).path()).isEqualTo("/v1/chat/completions");
    }

    // ── 拒绝样本：HTTP 状态失败的分类 ──────────────────────────────────────

    @Test
    @DisplayName("HTTP 401 ⇒ AUTH（鉴权失败不得混入 NETWORK）")
    void mapsUnauthorizedToAuth() {
        responder.set(Response.status(401));

        assertThat(failureOf(() -> provider().complete(request())).type()).isEqualTo("AUTH");
    }

    @Test
    @DisplayName("HTTP 403 ⇒ AUTH")
    void mapsForbiddenToAuth() {
        responder.set(Response.status(403));

        assertThat(failureOf(() -> provider().complete(request())).type()).isEqualTo("AUTH");
    }

    @Test
    @DisplayName("HTTP 429 ⇒ RATE_LIMITED（限流可与网络故障区分）")
    void mapsTooManyRequestsToRateLimited() {
        responder.set(Response.status(429));

        assertThat(failureOf(() -> provider().complete(request())).type()).isEqualTo("RATE_LIMITED");
    }

    @Test
    @DisplayName("HTTP 500 ⇒ NETWORK（服务端错误属传输类失败）")
    void mapsServerErrorToNetwork() {
        responder.set(Response.status(500));

        assertThat(failureOf(() -> provider().complete(request())).type()).isEqualTo("NETWORK");
    }

    @Test
    @DisplayName("连接不可达 ⇒ NETWORK")
    void mapsConnectFailureToNetwork() throws IOException {
        OpenAiCompatLlmProvider p =
                provider("http://127.0.0.1:" + closedPort(), STUB_KEY, "test-model");

        assertThat(failureOf(() -> p.complete(request())).type()).isEqualTo("NETWORK");
    }

    // ── 拒绝样本：报文非法的失败关闭 ───────────────────────────────────────

    @Test
    @DisplayName("200 但响应体非 JSON ⇒ FORMAT（拒绝，不当作成功）")
    void rejectsNonJsonPayload() {
        responder.set(new Response(200, "这不是 JSON", "text/plain", 0L));

        assertThat(failureOf(() -> provider().complete(request())).type()).isEqualTo("FORMAT");
    }

    @Test
    @DisplayName("200 且声明为 JSON 但 JSON 非法 ⇒ FORMAT（真实供应商常见的坏报文）")
    void rejectsMalformedJsonPayload() {
        responder.set(new Response(200, "{\"choices\":[{\"message\":", "application/json", 0L));

        assertThat(failureOf(() -> provider().complete(request())).type()).isEqualTo("FORMAT");
    }

    @Test
    @DisplayName("200 但空响应体 ⇒ FORMAT")
    void rejectsEmptyPayload() {
        responder.set(new Response(200, null, "application/json", 0L));

        assertThat(failureOf(() -> provider().complete(request())).type()).isEqualTo("FORMAT");
    }

    @Test
    @DisplayName("200 但 choices 缺失/为空 ⇒ FORMAT")
    void rejectsMissingChoices() {
        responder.set(Response.json("{\"usage\":{\"prompt_tokens\":3}}"));
        assertThat(failureOf(() -> provider().complete(request())).type()).isEqualTo("FORMAT");

        responder.set(Response.json("{\"choices\":[]}"));
        assertThat(failureOf(() -> provider().complete(request())).type()).isEqualTo("FORMAT");
    }

    @Test
    @DisplayName("200 但 content 为空白 ⇒ FORMAT（空内容不得冒充真实模型成功）")
    void rejectsBlankContent() {
        responder.set(Response.json(okBody("   ")));

        assertThat(failureOf(() -> provider().complete(request())).type()).isEqualTo("FORMAT");
    }

    // ── 请求级超时（设计 L552/L771：超时必须中止并标明，不得无限等待后假称成功） ──

    @Test
    @DisplayName("timeout-ms 归一化：<=0 落回默认 30000ms")
    void normalizesTimeoutMs() {
        assertThat(OpenAiCompatLlmProvider.normalizeTimeoutMs(-1L))
                .isEqualTo(OpenAiCompatLlmProvider.DEFAULT_TIMEOUT_MS);
        assertThat(OpenAiCompatLlmProvider.normalizeTimeoutMs(0L))
                .isEqualTo(OpenAiCompatLlmProvider.DEFAULT_TIMEOUT_MS);
        assertThat(OpenAiCompatLlmProvider.normalizeTimeoutMs(1500L)).isEqualTo(1500L);
        assertThat(OpenAiCompatLlmProvider.DEFAULT_TIMEOUT_MS).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("timeout-ms 极大值被收敛，构造与正常调用不因配置越界失败")
    void clampsHugeTimeout() {
        OpenAiCompatLlmProvider p =
                new OpenAiCompatLlmProvider(baseUrl, STUB_KEY, "test-model", Long.MAX_VALUE);

        assertThat(p.complete(request()).content()).isEqualTo("真实 stub 摘要");
    }

    @Test
    @DisplayName("读取超时：到点即失败为 TIMEOUT，耗时受 timeout-ms 约束（不再无限等待）")
    void timesOutInsteadOfWaitingForever() {
        responder.set(new Response(200, okBody("迟到内容"), "application/json", 1500L));
        OpenAiCompatLlmProvider p =
                new OpenAiCompatLlmProvider(baseUrl, STUB_KEY, "test-model", 200L);

        long start = System.currentTimeMillis();
        LlmProvider.LlmException e = failureOf(() -> p.complete(request()));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(e.type()).isEqualTo("TIMEOUT");
        assertThat(elapsed).as("超时必须真的中止请求（stub 延迟 1500ms，远大于 200ms 超时值）")
                .isLessThan(1200L);
    }
}
