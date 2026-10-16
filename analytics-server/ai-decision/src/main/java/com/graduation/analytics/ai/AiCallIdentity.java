package com.graduation.analytics.ai;

import com.graduation.analytics.ai.entity.AiCallLog;
import com.graduation.analytics.ai.llm.LlmProvider.AiRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Local audit identity, computed before calling the provider; no automatic deduplication. */
record AiCallIdentity(Long sourceId, String snapshotId, String fingerprint) {
    static AiCallIdentity of(String useCase, Long sourceId, String snapshotId, String question,
                             String model, String promptVersion, Integer maxOutputTokens, AiRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[] {"ai-request-v1", useCase,
                    sourceId == null ? null : sourceId.toString(), snapshotId, question,
                    model, promptVersion, maxOutputTokens == null ? null : maxOutputTokens.toString(),
                    request.systemPrompt(), request.userContent(),
                    Double.toHexString(request.temperature())}) {
                if (field == null) {
                    digest.update(ByteBuffer.allocate(4).putInt(-1).array());
                } else {
                    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return new AiCallIdentity(sourceId, snapshotId, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for AI request audit", e);
        }
    }

    void record(AiCallLog call) {
        call.setSourceId(sourceId);
        call.setSnapshotId(snapshotId);
        call.setRequestFingerprint(fingerprint);
    }
}
