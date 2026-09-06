package com.graduation.mall.ai.llm;

/**
 * 大模型提供方（§19.3 AiProvider）：可替换（OpenAI 兼容 / Mock / 第二阶段多供应商）。
 */
public interface LlmProvider {

    record AiRequest(String systemPrompt, String userContent, double temperature) {
    }

    record AiResponse(String content, int inputTokens, int outputTokens, String model) {
    }

    /** 完成一次对话；失败抛 LlmException（携带稳定错误类型） */
    AiResponse complete(AiRequest request);

    /** 连通性（无 key → false，调用方走规则回退） */
    boolean healthCheck();

    String providerName();

    class LlmException extends RuntimeException {
        private final String type; // TIMEOUT/RATE_LIMITED/NETWORK/AUTH/FORMAT

        public LlmException(String type, String message) {
            super(message);
            this.type = type;
        }

        public String type() {
            return type;
        }
    }
}