package com.graduation.generator.engine;

import com.graduation.generator.contract.CanonicalEvent;
import com.graduation.generator.contract.EventTypes;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MallApiIdempotencyKeyTest {

    @Test
    void keyIsStableForRunUserAndLogicalActionAndSeparatedAcrossInputs() {
        String key = MallApiDispatchSink.idempotencyKey("run-1", "user-2", "Eabc00000003");

        assertEquals(64, key.length());
        assertTrue(key.matches("[0-9a-f]{64}"));
        assertEquals(key, MallApiDispatchSink.idempotencyKey("run-1", "user-2", "Eabc00000003"));
        assertNotEquals(key, MallApiDispatchSink.idempotencyKey("run-2", "user-2", "Eabc00000003"));
        assertNotEquals(key, MallApiDispatchSink.idempotencyKey("run-1", "user-3", "Eabc00000003"));
        assertNotEquals(key, MallApiDispatchSink.idempotencyKey("run-1", "user-2", "Eabc00000004"));
    }

    @Test
    void keyRequiresAllIdentityParts() {
        assertThrows(IllegalArgumentException.class,
                () -> MallApiDispatchSink.idempotencyKey("run-1", "", "action-1"));
    }

    @Test
    void lifecycleEventsForSameAggregateUseDistinctKeysButReplayOfOneEventIsStable() {
        CanonicalEvent created = event("E0001", EventTypes.ORDER_CREATED);
        CanonicalEvent paid = event("E0002", EventTypes.ORDER_PAID);

        String createKey = MallApiDispatchSink.idempotencyKey("run-1", created);
        String payKey = MallApiDispatchSink.idempotencyKey("run-1", paid);

        assertNotEquals(createKey, payKey);
        assertEquals(createKey, MallApiDispatchSink.idempotencyKey("run-1", created));
        assertNotEquals(createKey, MallApiDispatchSink.idempotencyKey("run-2", created));
    }

    @Test
    void eventKeyRequiresAnEventUserIdentity() {
        CanonicalEvent event = new CanonicalEvent("E0001", EventTypes.ORDER_CREATED,
                "2026-10-07T10:00:00Z", "2026-10-07T10:00:00Z", "synthetic", "1.0", "trace-1",
                Map.of("order_id", "O0001"));

        assertThrows(IllegalArgumentException.class,
                () -> MallApiDispatchSink.idempotencyKey("run-1", event));
    }

    private static CanonicalEvent event(String eventId, String eventType) {
        return new CanonicalEvent(eventId, eventType,
                "2026-10-07T10:00:00Z", "2026-10-07T10:00:00Z", "synthetic", "1.0", "trace-1",
                Map.of("user_id", "U0001", "order_id", "O0001"));
    }
}
