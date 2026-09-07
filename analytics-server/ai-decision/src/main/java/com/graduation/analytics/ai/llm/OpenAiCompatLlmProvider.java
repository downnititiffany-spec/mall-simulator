package com.graduation.analytics.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容大模型客户端（§8.11）：baseUrl/apiKey/model 经环境变量配置，
 * 无 key 时 healthCheck=false（上层走规则回退，§3.5.5 AI 不阻塞主链路）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.mock-enabled", havingValue = "false", matchIfMissing = true)
public class OpenAiCompatLlmProvider implements LlmProvider {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestClient client;

    public OpenAiCompatLlmProvider(
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:deepseek-chat}") String model) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.client = RestClient.builder()
                .baseUrl(this.baseUrl.isEmpty() ? "http://127.0.0.1:1" : this.baseUrl) // 无配置时不可达
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
            String content = resp.path("choices").path(0).path("message").path("content").asText();
            int in = resp.path("usage").path("prompt_tokens").asInt(0);
            int out = resp.path("usage").path("completion_tokens").asInt(0);
            log.info("llm complete ok in {}ms tokens={}+{}", System.currentTimeMillis() - start, in, out);
            return new AiResponse(content, in, out, model);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String type = e instanceof org.springframework.web.client.HttpClientErrorException
                    ? "AUTH" : "NETWORK";
            log.warn("llm complete failed ({}): {} in {}ms", type, e.getMessage(), elapsed);
            throw new LlmException(type, "模型调用失败: " + e.getMessage());
        }
    }
}