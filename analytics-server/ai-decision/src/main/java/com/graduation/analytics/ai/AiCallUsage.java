package com.graduation.analytics.ai;

import com.graduation.analytics.ai.entity.AiCallLog;
import com.graduation.analytics.ai.llm.LlmProvider.AiResponse;
import com.graduation.analytics.ai.llm.LlmProvider.LlmException;

/** Persist only reported usage, including a completed response rejected by a local guard. */
final class AiCallUsage {
    private AiCallUsage() { }

    static void record(AiCallLog call, AiResponse response, Throwable failure) {
        if (response != null) {
            call.setInputTokens(response.inputTokens());
            call.setOutputTokens(response.outputTokens());
        } else if (failure instanceof LlmException llmFailure) {
            call.setInputTokens(llmFailure.inputTokens());
            call.setOutputTokens(llmFailure.outputTokens());
        }
    }
}
