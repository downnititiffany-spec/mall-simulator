package com.graduation.analytics.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

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

    /** 默认请求级超时（毫秒）：连接与读取共用同一数值。 */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestClient client;

    public OpenAiCompatLlmProvider(
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:deepseek-chat}") String model,
            @Value("${llm.timeout-ms:30000}") long timeoutMs) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.client = RestClient.builder()
                .baseUrl(this.baseUrl.isEmpty() ? "http://127.0.0.1:1" : this.baseUrl) // 无配置时不可达
                .requestFactory(requestFactory(timeoutMs))
                .build();
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
        // 返回实际模型名（deepseek-chat 等），供页面/审计直接展示；
        // 未配置模型时退化为协议标识
        return (model == null || model.isBlank()) ? "openai-compat" : model;
    }

    @Override
    public AiResponse complete(AiRequest request) {
        if (!healthCheck()) {
            throw new LlmException("AUTH", "未配置 LLM_API_KEY/LLM_BASE_URL");
        }
        long start = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", request.temperature());
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userContent())));
        body.put("response_format", Map.of("type", "json_object"));
        try {
            JsonNode resp = client.post()
                    .uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            AiResponse response = parseSuccess(resp);
            log.info("llm complete ok in {}ms tokens={}+{}", System.currentTimeMillis() - start,
                    response.inputTokens(), response.outputTokens());
            return response;
        } catch (LlmException e) {
            log.warn("llm complete rejected ({}): {} in {}ms", e.type(), e.getMessage(),
                    System.currentTimeMillis() - start);
            throw e;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String type = classify(e);
            log.warn("llm complete failed ({}): {} in {}ms", type, e.getMessage(), elapsed);
            throw new LlmException(type, "模型调用失败: " + e.getMessage());
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
        return new AiResponse(content,
                resp.path("usage").path("prompt_tokens").asInt(0),
                resp.path("usage").path("completion_tokens").asInt(0),
                model);
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