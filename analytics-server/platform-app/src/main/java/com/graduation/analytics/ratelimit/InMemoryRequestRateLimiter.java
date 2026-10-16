package com.graduation.analytics.ratelimit;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Single-process fixed-window limiter for the V3.0 single-node deployment. */
@Component
public class InMemoryRequestRateLimiter {
    private static final int MAX_KEYS = 10_000;

    private final Map<String, Window> windows = new HashMap<>();
    private final LongSupplier nanoTime;

    public InMemoryRequestRateLimiter() {
        this(System::nanoTime);
    }

    InMemoryRequestRateLimiter(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public synchronized Permit acquire(String key, int limit, int windowSeconds) {
        if (limit < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("rate limit and window must be positive");
        }
        long now = nanoTime.getAsLong();
        long windowNanos = Math.multiplyExact(windowSeconds, 1_000_000_000L);
        Window current = windows.get(key);
        if (current == null) {
            if (windows.size() >= MAX_KEYS) {
                removeExpiredWindows(now);
                if (windows.size() >= MAX_KEYS) {
                    return new Permit(false, secondsUntilCapacity(now));
                }
            }
            windows.put(key, new Window(now, 1, windowNanos));
            return new Permit(true, 0);
        }

        long elapsed = Math.max(0, now - current.startedAtNanos);
        if (elapsed >= current.windowNanos) {
            windows.put(key, new Window(now, 1, windowNanos));
            return new Permit(true, 0);
        }
        if (current.count >= limit) {
            long remaining = current.windowNanos - elapsed;
            return new Permit(false, ceilSeconds(remaining));
        }
        windows.put(key, new Window(current.startedAtNanos, current.count + 1, current.windowNanos));
        return new Permit(true, 0);
    }

    private void removeExpiredWindows(long now) {
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAtNanos >= entry.getValue().windowNanos);
    }

    private long secondsUntilCapacity(long now) {
        long earliestRemaining = windows.values().stream()
                .mapToLong(window -> window.windowNanos - Math.max(0, now - window.startedAtNanos))
                .min()
                .orElse(1_000_000_000L);
        return ceilSeconds(earliestRemaining);
    }

    private static long ceilSeconds(long nanos) {
        return Math.max(1, nanos / 1_000_000_000L + (nanos % 1_000_000_000L == 0 ? 0 : 1));
    }

    public record Permit(boolean allowed, long retryAfterSeconds) {
    }

    private record Window(long startedAtNanos, int count, long windowNanos) {
    }
}
