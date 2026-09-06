package com.graduation.mall.ai.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 可编程 Mock Provider（测试/演示）：按 System Prompt 中的标记返回预置 JSON；
 * MOCK_ENABLED=true 时替换真实 Provider（application-test.yml 启用）。
 */
@Component
@ConditionalOnProperty(name = "llm.mock-enabled", havingValue = "true")
public class MockLlmProvider implements LlmProvider {

    /** 请求特征 → 响应内容（由测试设置） */
    public static final Map<String, Function<String, String>> BEHAVIOR = new ConcurrentHashMap<>();

    /** 兜底分发器（评测器按问题文本动态分类生成响应） */
    public static volatile Function<String, String> DEFAULT_HANDLER = null;

    @Override
    public boolean healthCheck() {
        return true;
    }

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public AiResponse complete(AiRequest request) {
        String content = request.userContent();
        // 前缀匹配：测试可用短标记注册行为
        Function<String, String> fn = BEHAVIOR.entrySet().stream()
                .filter(e -> content.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(DEFAULT_HANDLER);
        String result = fn != null ? fn.apply(content) : defaultSqlResponse();
        return new AiResponse(result, 100, 50, "mock-model");
    }

    private String defaultSqlResponse() {
        return """
                {"intent":"查询销售额","metrics":["sale_amount"],
                 "sql":"SELECT dt, sale_amount FROM ads_sale_trend_m ORDER BY dt DESC LIMIT 7",
                 "assumptions":["默认取最新快照"]}""";
    }
}