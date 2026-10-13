package com.graduation.generator.adapter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Locale;

/** Bounded retries for safe mall-client reads. Business writes are deliberately sent once. */
final class SafeHttpRetry {

    static final int MAX_ATTEMPTS = 2;
    private static final long BACKOFF_MILLIS = 100L;

    private SafeHttpRetry() {
    }

    static <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                    HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        return send(client, request, responseBodyHandler, false);
    }

    /**
     * Retries a POST only when the caller has verified that the target honors the
     * Idempotency-Key protocol. Merely attaching the header is not proof that a
     * remote implementation deduplicates writes.
     */
    static <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                    HttpResponse.BodyHandler<T> responseBodyHandler,
                                    boolean targetSupportsIdempotencyKey)
            throws IOException, InterruptedException {
        if (!isRetrySafe(request, targetSupportsIdempotencyKey)) {
            return client.send(request, responseBodyHandler);
        }

        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<T> response = client.send(request, responseBodyHandler);
                if (attempt >= MAX_ATTEMPTS || !isTransientStatus(response.statusCode())) {
                    return response;
                }
            } catch (IOException e) {
                if (attempt >= MAX_ATTEMPTS || !isTransientNetworkFailure(e)) {
                    throw e;
                }
            }
            Thread.sleep(BACKOFF_MILLIS * attempt);
        }
    }

    private static boolean isRetrySafe(HttpRequest request, boolean targetSupportsIdempotencyKey) {
        String method = request.method();
        String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);
        return "GET".equals(normalized) || "HEAD".equals(normalized) || "OPTIONS".equals(normalized)
                || targetSupportsIdempotencyKey && "POST".equals(normalized)
                && request.headers().firstValue("Idempotency-Key")
                .filter(key -> !key.isBlank()).isPresent();
    }

    private static boolean isTransientStatus(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private static boolean isTransientNetworkFailure(IOException failure) {
        return failure instanceof HttpTimeoutException
                || failure instanceof ConnectException
                || failure instanceof SocketException;
    }
}
