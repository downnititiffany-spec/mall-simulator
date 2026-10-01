package com.graduation.analytics.ratelimit;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Single-process fixed-window limiter for the V3.0 single-node deployment. */
@Component
public class InMemoryRequestRateLimiter {
    private static final int MAX_KEYS = 10_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    public InMemoryRequestRateLimiter() {
        this(System::nanoTime);
    }

    InMemoryRequestRateLimiter(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public Permit acquire(String key, int limit, int windowSeconds) {
        if (limit < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("rate limit and window must be positive");
        }
        long now = nanoTime.getAsLong();
        long windowNanos = Math.multiplyExact(windowSeconds, 1_000_000_000L);
        Holder result = new Holder();
        windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAtNanos >= windowNanos) {
                result.permit = true;
                return new Window(now, 1);
            }
            if (current.count >= limit) {
                long remaining = windowNanos - Math.max(0, now - current.startedAtNanos);
                result.retryAfterSeconds = Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L);
                return current;
            }
            result.permit = true;
            return new Window(current.startedAtNanos, current.count + 1);
        });
        if (windows.size() > MAX_KEYS) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAtNanos >= windowNanos);
        }
        return new Permit(result.permit, result.retryAfterSeconds);
    }

    public record Permit(boolean allowed, long retryAfterSeconds) {
    }

    private record Window(long startedAtNanos, int count) {
    }

    private static final class Holder {
        private boolean permit;
        private long retryAfterSeconds;
    }
}
