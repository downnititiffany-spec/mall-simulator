package com.graduation.analytics.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

import java.net.SocketTimeoutException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI 兼容大模型客户端（§8.11）：baseUrl/apiKey/model/timeout-ms 经配置注入，
 * 无 key 时 healthCheck=false（上层走规则回退，§3.5.5 AI 不阻塞主链路）。
 *
 * <p><b>请求级超时</b>：连接与读取超时都取自 {@code llm.timeout-ms}（默认 30 秒，<=0 落回默认）。
 * 超时到点抛 {@link LlmException}（type=TIMEOUT），由上层回退模板并标明 —— 不无限等待、
 * 也不把超时后的模板成功记作真实模型成功（设计 V3.0 L552/L771）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.mock-enabled", havingValue = "false", matchIfMissing = true)
public class OpenAiCompatLlmProvider implements LlmProvider {

    private static final ObjectMapper REQUEST_MAPPER = new ObjectMapper();

    /** 默认请求级超时（毫秒）：连接与读取共用同一数值。 */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;
    public static final int DEFAULT_MAX_REQUEST_BYTES = 262_144;
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 1_048_576;
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 1_024;
    public static final int DEFAULT_MAX_CONCURRENT_CALLS = 2;
    public static final int DEFAULT_CIRCUIT_FAILURE_THRESHOLD = 5;
    public static final long DEFAULT_CIRCUIT_OPEN_MS = 60_000L;
    public static final long DEFAULT_DAILY_TOKEN_BUDGET = 1_000_000L;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final int maxRequestBytes;
    private final int maxOutputTokens;
    private final int circuitFailureThreshold;
    private final long circuitOpenNanos;
    private final Semaphore concurrentCalls;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenedAtNanos = new AtomicLong();
    private final long dailyTokenBudget;
    private final AtomicReference<DailyBudgetState> dailyBudgetState =
            new AtomicReference<>(new DailyBudgetState(LocalDate.now(ZoneOffset.UTC)));

    /** Compatibility constructor for focused tests and non-Spring callers. */
    public OpenAiCompatLlmProvider(
            String baseUrl,
            String apiKey,
            String model,
            long timeoutMs) {
        this(baseUrl, apiKey, model, timeoutMs, DEFAULT_MAX_REQUEST_BYTES,
                DEFAULT_MAX_OUTPUT_TOKENS, DEFAULT_MAX_CONCURRENT_CALLS,
                DEFAULT_CIRCUIT_FAILURE_THRESHOLD, DEFAULT_CIRCUIT_OPEN_MS,
                DEFAULT_DAILY_TOKEN_BUDGET);
    }

    /** Compatibility constructor for tests with explicit per-call governance values. */
    public OpenAiCompatLlmProvider(
            String baseUrl,
            String apiKey,
            String model,
            long timeoutMs,
            int maxRequestBytes,
            int maxOutputTokens,
            int maxConcurrentCalls,
            int circuitFailureThreshold,
            long circuitOpenMs) {
        this(baseUrl, apiKey, model, timeoutMs, maxRequestBytes, maxOutputTokens,
                maxConcurrentCalls, circuitFailureThreshold, circuitOpenMs,
                DEFAULT_DAILY_TOKEN_BUDGET);
    }

    /** Compatibility constructor for callers with an explicit daily reservation budget. */
    public OpenAiCompatLlmProvider(
            String baseUrl, String apiKey, String model, long timeoutMs,
            int maxRequestBytes, int maxOutputTokens, int maxConcurrentCalls,
            int circuitFailureThreshold, long circuitOpenMs, long dailyTokenBudget) {
        this(baseUrl, apiKey, model, timeoutMs, maxRequestBytes, maxOutputTokens,
                maxConcurrentCalls, circuitFailureThreshold, circuitOpenMs,
                dailyTokenBudget, DEFAULT_MAX_RESPONSE_BYTES);
    }

    @Autowired
    public OpenAiCompatLlmProvider(
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:deepseek-chat}") String model,
            @Value("${llm.timeout-ms:30000}") long timeoutMs,
            @Value("${llm.max-request-bytes:262144}") int maxRequestBytes,
            @Value("${llm.max-output-tokens:1024}") int maxOutputTokens,
            @Value("${llm.max-concurrent-calls:2}") int maxConcurrentCalls,
            @Value("${llm.circuit-failure-threshold:5}") int circuitFailureThreshold,
            @Value("${llm.circuit-open-ms:60000}") long circuitOpenMs,
            @Value("${llm.max-daily-token-budget:1000000}") long dailyTokenBudget,
            @Value("${llm.max-response-bytes:1048576}") int maxResponseBytes) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.maxRequestBytes = maxRequestBytes > 0 ? maxRequestBytes : DEFAULT_MAX_REQUEST_BYTES;
        this.maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;
        this.concurrentCalls = new Semaphore(maxConcurrentCalls > 0
                ? maxConcurrentCalls : DEFAULT_MAX_CONCURRENT_CALLS);
        this.circuitFailureThreshold = circuitFailureThreshold > 0
                ? circuitFailureThreshold : DEFAULT_CIRCUIT_FAILURE_THRESHOLD;
        long normalizedCircuitOpenMs = circuitOpenMs > 0 ? circuitOpenMs : DEFAULT_CIRCUIT_OPEN_MS;
        this.circuitOpenNanos = normalizedCircuitOpenMs > Long.MAX_VALUE / 1_000_000L
                ? Long.MAX_VALUE : normalizedCircuitOpenMs * 1_000_000L;
        this.dailyTokenBudget = dailyTokenBudget > 0 ? dailyTokenBudget : DEFAULT_DAILY_TOKEN_BUDGET;
        int responseLimit = maxResponseBytes > 0 ? maxResponseBytes : DEFAULT_MAX_RESPONSE_BYTES;
        this.client = RestClient.builder()
                .baseUrl(this.baseUrl.isEmpty() ? "http://127.0.0.1:1" : this.baseUrl) // 无配置时不可达
                .requestFactory(requestFactory(timeoutMs))
                .requestInterceptor((request, body, execution) ->
                        boundResponse(execution.execute(request, body), responseLimit))
                .build();
    }

    /** Bound the actual stream before JSON conversion or HTTP error-body buffering. */
    private static ClientHttpResponse boundResponse(ClientHttpResponse response, int limit) {
        return new ClientHttpResponse() {
            private InputStream body;

            @Override
            public HttpStatusCode getStatusCode() throws IOException {
                return response.getStatusCode();
            }

            @Override
            public String getStatusText() throws IOException {
                return response.getStatusText();
            }

            @Override
            public HttpHeaders getHeaders() {
                return response.getHeaders();
            }

            @Override
            public InputStream getBody() throws IOException {
                if (body == null) {
                    body = new LimitedResponseBody(response.getBody(), limit);
                }
                return body;
            }

            @Override
            public void close() {
                try {
                    if (body != null) body.close();
                } catch (IOException ignored) {
                    // Still close the owning response; never log upstream body details.
                } finally {
                    response.close();
                }
            }
        };
    }

    private static final class LimitedResponseBody extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private LimitedResponseBody(InputStream delegate, int limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                if (remaining == 0) throw new ResponseTooLargeException();
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            // Read at most one byte beyond the limit to distinguish exact-size EOF.
            int count = delegate.read(bytes, offset, (int) Math.min(length, remaining + 1));
            if (count > remaining) throw new ResponseTooLargeException();
            if (count > 0) remaining -= count;
            return count;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class ResponseTooLargeException extends IOException {
        private ResponseTooLargeException() {
            super("模型响应超过配置字节上限");
        }
    }

    /**
     * URL 归一化：容忍用户配置带或不带 /v1 / 尾部斜杠
     * （请求路径固定为 /v1/chat/completions，避免 /v1/v1 双重前缀导致 404）。
     */
    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String b = baseUrl.trim().replaceAll("/+$", "");
        if (b.endsWith("/v1")) {
            b = b.substring(0, b.length() - 3);
        }
        return b;
    }

    /** 超时归一化：<=0（未配置/非法）落回 {@link #DEFAULT_TIMEOUT_MS}。 */
    static long normalizeTimeoutMs(long timeoutMs) {
        return timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    /**
     * 请求工厂：连接与读取超时同源设置。
     * 只设读取超时仍可能在建连阶段无限等待，故两者都设。
     */
    static ClientHttpRequestFactory requestFactory(long timeoutMs) {
        int timeout = (int) Math.min(normalizeTimeoutMs(timeoutMs), Integer.MAX_VALUE);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }

    @Override
    public boolean healthCheck() {
        return apiKey != null && !apiKey.isBlank() && !baseUrl.isBlank();
    }

    @Override
    public String providerName() {
        return "openai-compat";
    }

    @Override
    public String modelName() {
        return (model == null || model.isBlank()) ? null : model.trim();
    }

    @Override
    public Integer maxOutputTokens() {
        return maxOutputTokens;
    }

    @Override
    public AiResponse complete(AiRequest request) {
        if (!healthCheck()) {
            throw new LlmException("AUTH", "未配置 LLM_API_KEY/LLM_BASE_URL");
        }
        if (request == null || request.systemPrompt() == null || request.userContent() == null) {
            throw new LlmException("FORMAT", "模型请求内容缺失");
        }
        Map<String, Object> body = requestBody(request);
        long requestBytes;
        try {
            requestBytes = REQUEST_MAPPER.writeValueAsBytes(body).length;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new LlmException("FORMAT", "模型请求无法序列化");
        }
        if (requestBytes > maxRequestBytes) {
            throw new LlmException("REQUEST_TOO_LARGE", "模型请求超过配置字节上限");
        }
        ensureCircuitClosed();
        if (!concurrentCalls.tryAcquire()) {
            throw new LlmException("RATE_LIMITED", "模型并发调用达到配置上限");
        }
        try {
            reserveDailyBudget(requestBytes);
            return completeWithinPermit(body);
        } finally {
            concurrentCalls.release();
        }
    }

    private void reserveDailyBudget(long requestBytes) {
        long reservation = requestBytes + maxOutputTokens;
        while (true) {
            LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
            DailyBudgetState state = dailyBudgetState.get();
            if (!todayUtc.equals(state.day)) {
                DailyBudgetState fresh = new DailyBudgetState(todayUtc);
                if (!dailyBudgetState.compareAndSet(state, fresh)) {
                    continue;
                }
                state = fresh;
            }
            long used = state.reservedTokens.get();
            if (reservation > dailyTokenBudget - used) {
                throw new LlmException("DAILY_BUDGET_EXCEEDED", "模型每日 token 预留预算已用尽");
            }
            if (state.reservedTokens.compareAndSet(used, used + reservation)) {
                return;
            }
        }
    }

    private static final class DailyBudgetState {
        private final LocalDate day;
        private final AtomicLong reservedTokens = new AtomicLong();

        private DailyBudgetState(LocalDate day) {
            this.day = day;
        }
    }

    private Map<String, Object> requestBody(AiRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxOutputTokens);
        body.put("temperature", request.temperature());
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userContent())));
        body.put("response_format", Map.of("type", "json_object"));
        return body;
    }

    private AiResponse completeWithinPermit(Map<String, Object> body) {
        long start = System.currentTimeMillis();
        try {
            JsonNode resp = client.post()
                    .uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            AiResponse response = parseSuccess(resp);
            if (response.outputTokens() != null && response.outputTokens() > maxOutputTokens) {
                throw new LlmException("OUTPUT_BUDGET_EXCEEDED", "模型响应超过配置输出 token 上限",
                        response.inputTokens(), response.outputTokens());
            }
            consecutiveFailures.set(0);
            circuitOpenedAtNanos.set(0L);
            log.info("llm complete ok in {}ms tokens={}+{}", System.currentTimeMillis() - start,
                    response.inputTokens(), response.outputTokens());
            return response;
        } catch (LlmException e) {
            recordFailure(e.type());
            log.warn("llm complete rejected ({}) in {}ms", e.type(),
                    System.currentTimeMillis() - start);
            throw e;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String type = classify(e);
            recordFailure(type);
            log.warn("llm complete failed ({}; {}) in {}ms", type,
                    e.getClass().getSimpleName(), elapsed);
            // Provider error bodies can echo prompt content or connection details. Keep only
            // the stable failure class on the service boundary; never persist the raw message.
            throw new LlmException(type, "模型调用失败 (" + type + ")");
        }
    }

    private void ensureCircuitClosed() {
        long openedAt = circuitOpenedAtNanos.get();
        if (openedAt == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - openedAt;
        if (elapsed >= 0L && elapsed < circuitOpenNanos) {
            throw new LlmException("CIRCUIT_OPEN", "模型调用熔断窗口尚未结束");
        }
        if (circuitOpenedAtNanos.compareAndSet(openedAt, 0L)) {
            consecutiveFailures.set(0);
        }
    }

    private void recordFailure(String type) {
        if ("AUTH".equals(type) || "RATE_LIMITED".equals(type)) {
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= circuitFailureThreshold) {
            circuitOpenedAtNanos.compareAndSet(0L, System.nanoTime());
        }
    }

    /**
     * 解析成功响应：报文结构不合法（空报文 / 缺 choices / content 空白）一律<b>失败关闭</b>，
     * 不得把空内容当成「真实模型成功」（设计 L552/L581、L771）。
     */
    private AiResponse parseSuccess(JsonNode resp) {
        if (resp == null) {
            throw new LlmException("FORMAT", "模型响应为空报文");
        }
        JsonNode choices = resp.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmException("FORMAT", "模型响应缺少 choices 或 choices 为空");
        }
        String content = choices.path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new LlmException("FORMAT", "模型响应 content 为空");
        }
        JsonNode usage = resp.path("usage");
        if (!usage.isMissingNode() && !usage.isNull() && !usage.isObject()) {
            throw new LlmException("FORMAT", "模型响应 usage 格式不可用");
        }
        return new AiResponse(content, usageTokens(usage, "prompt_tokens"),
                usageTokens(usage, "completion_tokens"), model);
    }

    private static Integer usageTokens(JsonNode usage, String name) {
        JsonNode count = usage.path(name);
        if (count.isMissingNode() || count.isNull()) return null;
        if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 0) {
            throw new LlmException("FORMAT", "模型响应 token 用量格式不可用");
        }
        return count.intValue();
    }

    /**
     * 失败分类（本类唯一属主）：已知类型原样透传 → HTTP 状态码 → 超时 → 传输故障 → 报文格式。
     * 429 是限流而非鉴权失败；401/403 才是 AUTH。判据只用 RestClient 的异常类型层次，
     * **不**依赖 message 文本。
     *
     * <p>判据来自本类实测到的异常面（stub 服务器 + 本机探针）：429＝
     * {@code HttpClientErrorException$TooManyRequests}；5xx＝{@code HttpServerErrorException}
     * （属 {@code RestClientResponseException}）；连接/读取故障＝{@code ResourceAccessException}
     * （超时以其 cause 形式出现）；无法选定转换器＝{@code UnknownContentTypeException}；
     * 声明为 JSON 的坏报文＝裸 {@code RestClientException}，cause 为
     * {@code HttpMessageNotReadableException}（属 {@code HttpMessageConversionException}）。</p>
     *
     * <p>未实测覆盖的分支不保留：裸 {@code RestClientException} 不再兜底成 FORMAT ——
     * 未识别的 HTTP 客户端故障保守归 NETWORK，避免把传输问题伪装成报文格式问题。</p>
     */
    static String classify(Throwable e) {
        if (e instanceof LlmException known) {
            return known.type();
        }
        if (hasCause(e, ResponseTooLargeException.class)) {
            return "RESPONSE_TOO_LARGE";
        }
        if (e instanceof HttpClientErrorException clientError) {
            int code = clientError.getStatusCode().value();
            if (code == 429) {
                return "RATE_LIMITED";
            }
            if (code == 401 || code == 403) {
                return "AUTH";
            }
            return "NETWORK";
        }
        if (e instanceof RestClientResponseException) {
            // 其余**带 HTTP 状态码**的响应（含 5xx）＝传输类失败，不得混入报文格式类
            return "NETWORK";
        }
        if (hasCause(e, HttpTimeoutException.class, SocketTimeoutException.class, TimeoutException.class)) {
            return "TIMEOUT";
        }
        if (e instanceof ResourceAccessException) {
            return "NETWORK";
        }
        if (e instanceof UnknownContentTypeException
                || hasCause(e, HttpMessageConversionException.class)) {
            return "FORMAT";
        }
        return "NETWORK";
    }

    private static boolean hasCause(Throwable e, Class<?>... types) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            for (Class<?> type : types) {
                if (type.isInstance(t)) {
                    return true;
                }
            }
        }
        return false;
    }
}
