package com.graduation.analytics.ai.llm;

/**
 * 大模型提供方（§19.3 AiProvider）：可替换（OpenAI 兼容 / Mock / 第二阶段多供应商）。
 */
public interface LlmProvider {

    record AiRequest(String systemPrompt, String userContent, double temperature) {
    }

    /** Token counts are null when the provider does not report usage; zero is a reported value. */
    record AiResponse(String content, Integer inputTokens, Integer outputTokens, String model) {
    }

    /** 完成一次对话；失败抛 LlmException（携带稳定错误类型） */
    AiResponse complete(AiRequest request);

    /** 连通性（无 key → false，调用方走规则回退） */
    boolean healthCheck();

    /** Stable provider/protocol identifier, independent of the selected model. */
    String providerName();

    /** Configured model identifier for audit records; null when a provider has no model selection. */
    default String modelName() {
        return null;
    }

    /** Effective output-token cap sent in the provider request, when the provider exposes it. */
    default Integer maxOutputTokens() {
        return null;
    }

    class LlmException extends RuntimeException {
        private final String type; // TIMEOUT/RATE_LIMITED/NETWORK/AUTH/FORMAT
        private final Integer inputTokens;
        private final Integer outputTokens;

        public LlmException(String type, String message) {
            this(type, message, null, null);
        }

        public LlmException(String type, String message, Integer inputTokens, Integer outputTokens) {
            super(message);
            this.type = type;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        public String type() {
            return type;
        }

        public Integer inputTokens() {
            return inputTokens;
        }

        public Integer outputTokens() {
            return outputTokens;
        }
    }
}
