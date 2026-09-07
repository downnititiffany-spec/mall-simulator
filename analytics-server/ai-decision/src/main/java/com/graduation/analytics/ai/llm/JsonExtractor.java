package com.graduation.analytics.ai.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 从模型输出中提取 JSON（§12.2：SQL 从结构化字段读取，不从 Markdown 截取）：
 * 支持裸 JSON、```json ``` 代码块、前缀说明后跟 JSON。
 */
public final class JsonExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonExtractor() {
    }

    public static JsonNode extractJson(String content) {
        if (content == null) {
            throw new IllegalArgumentException("模型输出为空");
        }
        String text = content.trim();
        // 1) 完整 JSON 直解
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException ignored) {
            // 继续尝试剥离
        }
        // 2) 剔除 ```json ... ``` 围栏
        String fenced = text.replaceFirst("(?s)^```(?:json)?\\s*", "")
                .replaceFirst("(?s)\\s*```$", "")
                .trim();
        try {
            return MAPPER.readTree(fenced);
        } catch (JsonProcessingException ignored) {
            // 继续
        }
        // 3) 取第一个 { 到最后一个 } 之间的片段
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return MAPPER.readTree(text.substring(start, end + 1));
            } catch (JsonProcessingException ignored) {
                // 放弃
            }
        }
        throw new IllegalArgumentException("无法从模型输出中提取 JSON");
    }
}