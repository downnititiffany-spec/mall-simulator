package com.graduation.mall.ai.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM Base URL 归一化测试：兼容带/不带 /v1 与尾部斜杠的配置，
 * 请求路径固定拼 /v1/chat/completions，防止双重 v1 导致 404。
 */
class OpenAiCompatLlmProviderTest {

    @Test
    @DisplayName("带 /v1 的配置去重（DeepSeek 官方写法）")
    void stripDuplicatedV1() {
        assertEquals("https://api.deepseek.com",
                OpenAiCompatLlmProvider.normalizeBaseUrl("https://api.deepseek.com/v1"));
        assertEquals("https://api.deepseek.com",
                OpenAiCompatLlmProvider.normalizeBaseUrl("https://api.deepseek.com/v1/"));
    }

    @Test
    @DisplayName("不带 /v1 的配置原样保留")
    void keepPlainBaseUrl() {
        assertEquals("https://api.deepseek.com",
                OpenAiCompatLlmProvider.normalizeBaseUrl("https://api.deepseek.com"));
        assertEquals("https://open.bigmodel.cn/api/paas",
                OpenAiCompatLlmProvider.normalizeBaseUrl("https://open.bigmodel.cn/api/paas/"));
    }

    @Test
    @DisplayName("空值与空串安全")
    void blankSafe() {
        assertEquals("", OpenAiCompatLlmProvider.normalizeBaseUrl(null));
        assertEquals("", OpenAiCompatLlmProvider.normalizeBaseUrl("  "));
        assertTrue(OpenAiCompatLlmProvider.normalizeBaseUrl(" https://x.com/v1  ").equals("https://x.com"));
    }
}