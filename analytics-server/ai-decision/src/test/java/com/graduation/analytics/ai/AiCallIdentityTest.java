package com.graduation.analytics.ai;

import com.graduation.analytics.ai.llm.LlmProvider.AiRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiCallIdentityTest {
    private static String fingerprint(String useCase, Long source, String snapshot, String question,
                                      String model, String version, AiRequest request) {
        return fingerprint(useCase, source, snapshot, question, model, version, 1024, request);
    }

    private static String fingerprint(String useCase, Long source, String snapshot, String question,
                                      String model, String version, Integer maxOutputTokens, AiRequest request) {
        return AiCallIdentity.of(useCase, source, snapshot, question, model, version,
                maxOutputTokens, request).fingerprint();
    }

    @Test
    void identicalRequestsHaveStableDigestAndEveryIdentityFieldMatters() {
        AiRequest request = new AiRequest("system", "user", 0);
        String base = fingerprint("generate", 1L, "S1", "question", "model", "v1", request);
        assertThat(base).matches("[0-9a-f]{64}");
        assertThat(fingerprint("generate", 1L, "S1", "question", "model", "v1", request)).isEqualTo(base);
        assertThat(fingerprint("generate", 1L, "S1", "question", "model", "v1", 2048, request))
                .isNotEqualTo(base);
        assertThat(fingerprint("repair", 1L, "S1", "question", "model", "v1", request)).isNotEqualTo(base);
        assertThat(fingerprint("generate", 2L, "S1", "question", "model", "v1", request)).isNotEqualTo(base);
        assertThat(fingerprint("generate", 1L, "S2", "question", "model", "v1", request)).isNotEqualTo(base);
        assertThat(fingerprint("generate", 1L, "S1", "other", "model", "v1", request)).isNotEqualTo(base);
        assertThat(fingerprint("generate", 1L, "S1", "question", "other", "v1", request)).isNotEqualTo(base);
        assertThat(fingerprint("generate", 1L, "S1", "question", "model", "v2", request)).isNotEqualTo(base);
        assertThat(fingerprint("generate", 1L, "S1", "question", "model", "v1", new AiRequest("other", "user", 0))).isNotEqualTo(base);
        assertThat(fingerprint("generate", 1L, "S1", "question", "model", "v1", new AiRequest("system", "other", 0))).isNotEqualTo(base);
        assertThat(fingerprint("generate", 1L, "S1", "question", "model", "v1", new AiRequest("system", "user", .2))).isNotEqualTo(base);
    }

    @Test
    void framingDistinguishesNullEmptyAndAmbiguousConcatenations() {
        AiRequest request = new AiRequest("中文", "内容", 0);
        assertThat(fingerprint("g", null, "s", null, "m", "v", request))
                .isNotEqualTo(fingerprint("g", null, "s", "", "m", "v", request));
        assertThat(fingerprint("g", 1L, "ab", "c", "m", "v", request))
                .isNotEqualTo(fingerprint("g", 1L, "a", "bc", "m", "v", request));
    }
}
