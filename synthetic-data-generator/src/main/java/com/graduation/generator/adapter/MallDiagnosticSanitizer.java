package com.graduation.generator.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/** Redacts target credentials and limits endpoint diagnostics to a credential-free origin. */
final class MallDiagnosticSanitizer {

    private static final String REDACTED = "[REDACTED]";

    private MallDiagnosticSanitizer() {
    }

    static String redact(String text, String secret) {
        if (text == null) {
            return "(无)";
        }
        if (secret == null || secret.isEmpty()) {
            return text;
        }
        String redacted = text.replace(secret, REDACTED);
        String trimmed = secret.trim();
        if (!trimmed.isEmpty() && !trimmed.equals(secret)) {
            redacted = redacted.replace(trimmed, REDACTED);
        }
        return redacted;
    }

    static String origin(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return "(目标地址已隐藏)";
        }
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (URISyntaxException ignored) {
            return "(目标地址已隐藏)";
        }
    }

    /** Keep the useful failure class/message without retaining a parser/client exception that may echo a secret. */
    static IOException safeCause(Throwable failure, String secret) {
        String message = failure.getMessage();
        String detail = message == null ? "(无消息)" : redact(message, secret);
        return new IOException(failure.getClass().getSimpleName() + ": " + detail);
    }
}
