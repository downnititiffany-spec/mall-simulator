package com.graduation.analytics.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRequestRateLimiterTest {

    @Test
    void enforcesPerKeyBudgetAndResetsAtWindowBoundary() {
        AtomicLong now = new AtomicLong();
        InMemoryRequestRateLimiter limiter = new InMemoryRequestRateLimiter(now::get);

        assertThat(limiter.acquire("user-1:pipeline", 2, 60).allowed()).isTrue();
        assertThat(limiter.acquire("user-1:pipeline", 2, 60).allowed()).isTrue();
        var rejected = limiter.acquire("user-1:pipeline", 2, 60);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(60);
        assertThat(limiter.acquire("user-2:pipeline", 2, 60).allowed()).isTrue();
        assertThat(limiter.acquire("user-1:ingestion", 2, 60).allowed()).isTrue();

        now.set(59_500_000_000L);
        assertThat(limiter.acquire("user-1:pipeline", 2, 60).retryAfterSeconds()).isEqualTo(1);
        now.set(60_000_000_000L);
        assertThat(limiter.acquire("user-1:pipeline", 2, 60).allowed()).isTrue();
    }

    @Test
    void concurrentCallsCannotExceedTheWindowBudget() throws Exception {
        InMemoryRequestRateLimiter limiter = new InMemoryRequestRateLimiter(System::nanoTime);
        List<Thread> workers = new ArrayList<>();
        AtomicLong accepted = new AtomicLong();
        for (int i = 0; i < 32; i++) {
            Thread worker = new Thread(() -> {
                if (limiter.acquire("same-user:pipeline", 5, 60).allowed()) {
                    accepted.incrementAndGet();
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        assertThat(accepted.get()).isEqualTo(5);
    }

    @Test
    void capacityRejectsNewKeysAndReportsEarliestWindowExpiry() {
        AtomicLong now = new AtomicLong();
        InMemoryRequestRateLimiter limiter = new InMemoryRequestRateLimiter(now::get);
        for (int i = 0; i < 10_000; i++) {
            assertThat(limiter.acquire("key-" + i, 1, 60).allowed()).isTrue();
        }

        now.set(59_500_000_000L);
        var rejected = limiter.acquire("new-key", 1, 60);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1);

        now.set(60_000_000_000L);
        assertThat(limiter.acquire("new-key", 1, 60).allowed()).isTrue();
    }

    @Test
    void capacityReclaimsOnlyWindowsThatHaveExpired() {
        AtomicLong now = new AtomicLong();
        InMemoryRequestRateLimiter limiter = new InMemoryRequestRateLimiter(now::get);
        assertThat(limiter.acquire("short-window", 1, 1).allowed()).isTrue();
        for (int i = 0; i < 9_999; i++) {
            assertThat(limiter.acquire("long-window-" + i, 1, 60).allowed()).isTrue();
        }

        now.set(1_000_000_000L);
        assertThat(limiter.acquire("replacement", 1, 60).allowed()).isTrue();
        assertThat(limiter.acquire("long-window-0", 1, 60).allowed()).isFalse();
    }

    @Test
    void concurrentNewKeysCannotExceedTheGlobalCapacity() throws Exception {
        InMemoryRequestRateLimiter limiter = new InMemoryRequestRateLimiter(System::nanoTime);
        for (int i = 0; i < 9_990; i++) {
            assertThat(limiter.acquire("existing-" + i, 1, 60).allowed()).isTrue();
        }

        List<Thread> workers = new ArrayList<>();
        AtomicLong accepted = new AtomicLong();
        for (int i = 0; i < 32; i++) {
            int key = i;
            Thread worker = new Thread(() -> {
                if (limiter.acquire("concurrent-" + key, 1, 60).allowed()) {
                    accepted.incrementAndGet();
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertThat(accepted.get()).isEqualTo(10);
        assertThat(limiter.acquire("over-capacity", 1, 60).allowed()).isFalse();
    }
}
