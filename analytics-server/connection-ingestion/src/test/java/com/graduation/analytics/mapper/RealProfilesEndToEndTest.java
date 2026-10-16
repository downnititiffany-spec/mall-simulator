package com.graduation.analytics.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for both real profiles (Deliverable 2, Rule-1, Rule-12, Rule-14):
 * - mock-mall.v1.json
 * - fixture-b.v1.json
 */
class RealProfilesEndToEndTest {

    private static SourceProfile mockMallProfile;
    private static SourceProfile fixtureBProfile;
    private final PureMapper mapper = new PureMapper();
    private final DryRunEngine engine = new DryRunEngine(mapper);

    @BeforeAll
    static void loadProfiles() throws IOException {
        Path mockMallPath = findProfile("mock-mall.v1.json");
        Path fixtureBPath = findProfile("fixture-b.v1.json");

        mockMallProfile = SourceProfile.fromFile(mockMallPath);
        fixtureBProfile = SourceProfile.fromFile(fixtureBPath);
    }

    private static Path findProfile(String filename) {
        List<Path> candidates = List.of(
                Path.of("analytics-server/source-profiles", filename),
                Path.of("../source-profiles", filename),
                Path.of("../../analytics-server/source-profiles", filename),
                Path.of("source-profiles", filename)
        );
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException("Could not find profile file: " + filename);
    }

    @Test
    @DisplayName("Real profile mock-mall: channel=web shows up as unresolved, not defaulted or rejected")
    void mockMall_channelWebShowsUpAsUnresolved() {
        assertThat(mockMallProfile.isValid()).isTrue();
        assertThat(mockMallProfile.sourceCode()).isEqualTo("mock-mall");

        // Record with channel: "web" (mock-mall enumSemantics.channel.web is null)
        String rawWeb = """
                {
                  "event_type": "behavior",
                  "event_time": "2026-09-05T20:15:31+08:00",
                  "user_id": "u1001",
                  "product_id": "p2001",
                  "session_id": "s3001",
                  "behavior_type": "view",
                  "channel": "web",
                  "event_time_utc": "2026-09-05T12:15:31Z"
                }
                """;

        MapperOutcome outcome = mapper.map(rawWeb, mockMallProfile);
        assertThat(outcome).isInstanceOf(MapperOutcome.Canonical.class);
        MapperOutcome.Canonical c = (MapperOutcome.Canonical) outcome;

        // Channel value kept as "web" (not defaulted, not null) inside payload
        assertThat(c.canonicalRecord().path("payload").get("channel").asText()).isEqualTo("web");
        assertThat(c.canonicalRecord().has("event_type")).isTrue();

        // ENUM_UNRESOLVED warning present
        assertThat(c.unresolvedEnumWarnings()).hasSize(1);
        MapperViolation warning = c.unresolvedEnumWarnings().get(0);
        assertThat(warning.code()).isEqualTo(MapperViolationCode.ENUM_UNRESOLVED);
        assertThat(warning.fieldPath()).isEqualTo("channel");
        assertThat(warning.rawValue()).isEqualTo("web");

        // DryRunEngine counts unresolvedEnumCounts as "channel=web"
        DryRunReport report = engine.run(mockMallProfile, List.of(rawWeb));
        assertThat(report.canonicalRows()).isEqualTo(1);
        assertThat(report.quarantinedRows()).isEqualTo(0);
        assertThat(report.unresolvedEnumCounts()).containsEntry("channel=web", 1L);

        // Record with channel: "app" resolves cleanly
        String rawApp = rawWeb.replace("\"web\"", "\"app\"");
        MapperOutcome oApp = mapper.map(rawApp, mockMallProfile);
        assertThat(oApp).isInstanceOf(MapperOutcome.Canonical.class);
        assertThat(((MapperOutcome.Canonical) oApp).canonicalRecord().path("payload").get("channel").asText()).isEqualTo("app");
        assertThat(((MapperOutcome.Canonical) oApp).unresolvedEnumWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Real profile mock-mall: processes order_created and order_paid events")
    void mockMall_orderEventsEndToEnd() {
        String rawOrderCreated = """
                {
                  "event_type": "order_created",
                  "event_time": "2026-09-05T20:15:31+08:00",
                  "order_id": "ord-101",
                  "user_id": "u1001",
                  "total_amount": "2042.00",
                  "status": "CREATED",
                  "items": [
                    { "product_id": "p2001", "quantity": 2, "unit_price": "1021.00", "amount": "2042.00" }
                  ]
                }
                """;
        MapperOutcome o1 = mapper.map(rawOrderCreated, mockMallProfile);
        assertThat(o1).isInstanceOf(MapperOutcome.Canonical.class);
        JsonNode rec1 = ((MapperOutcome.Canonical) o1).canonicalRecord();
        assertThat(rec1.get("event_type").asText()).isEqualTo("order_created");
        assertThat(rec1.path("payload").get("order_id").asText()).isEqualTo("ord-101");
        assertThat(rec1.path("payload").get("total_amount").asText()).isEqualTo("2042.00");
        assertThat(rec1.path("payload").get("items")).hasSize(1);

        String rawOrderPaid = """
                {
                  "event_type": "order_paid",
                  "event_time": "2026-09-05T20:15:32+08:00",
                  "order_id": "ord-101",
                  "user_id": "u1001",
                  "payment_id": "pay-501",
                  "amount": "2042.00"
                }
                """;
        MapperOutcome o2 = mapper.map(rawOrderPaid, mockMallProfile);
        assertThat(o2).isInstanceOf(MapperOutcome.Canonical.class);
        JsonNode rec2 = ((MapperOutcome.Canonical) o2).canonicalRecord();
        assertThat(rec2.get("event_type").asText()).isEqualTo("order_paid");
        assertThat(rec2.path("payload").get("payment_id").asText()).isEqualTo("pay-501");
        assertThat(rec2.path("payload").get("amount").asText()).isEqualTo("2042.00");
    }

    @Test
    @DisplayName("Real profile fixture-b: processes heterogeneous field shapes, epoch millis, and FEN amounts")
    void fixtureB_heterogeneousFieldShapesEndToEnd() {
        assertThat(fixtureBProfile.isValid()).isTrue();
        assertThat(fixtureBProfile.sourceCode()).isEqualTo("fixture-b");

        // item_view event -> mapped to behavior
        String rawItemView = """
                {
                  "msg_id": "msg-001",
                  "msg_kind": "item_view",
                  "occurred_ms": 1725538531000,
                  "received_ms": 1725538532000,
                  "buyer_uid": "buyer-123",
                  "item_sku": "sku-456",
                  "visit_id": "sess-789",
                  "view_action": "BROWSE",
                  "terminal": "APP",
                  "ext_coupon_code": "PROMO_FIXTURE_B"
                }
                """;

        MapperOutcome o1 = mapper.map(rawItemView, fixtureBProfile);
        assertThat(o1).isInstanceOf(MapperOutcome.Canonical.class);
        JsonNode rec1 = ((MapperOutcome.Canonical) o1).canonicalRecord();

        // Mappings verified: envelope at root, payload fields in payload container
        assertThat(rec1.get("event_type").asText()).isEqualTo("behavior");
        assertThat(rec1.path("payload").get("user_id").asText()).isEqualTo("buyer-123");
        assertThat(rec1.path("payload").get("product_id").asText()).isEqualTo("sku-456");
        assertThat(rec1.path("payload").get("session_id").asText()).isEqualTo("sess-789");
        assertThat(rec1.path("payload").get("behavior_type").asText()).isEqualTo("view");
        assertThat(rec1.path("payload").get("channel").asText()).isEqualTo("app");

        // Epoch millis normalized to ISO-8601 string, original retained in payload
        assertThat(rec1.path("payload").get("event_time_original").asText()).isEqualTo("1725538531000");
        assertThat(rec1.get("event_time").asText()).contains("2024-09-05T");

        // @keep field preserved by leaf name in payload
        assertThat(rec1.has("ext_coupon_code")).isFalse();
        assertThat(rec1.path("payload").has("ext_coupon_code")).isTrue();
        assertThat(rec1.path("payload").get("ext_coupon_code").asText()).isEqualTo("PROMO_FIXTURE_B");

        // order_new event -> mapped to order_created with FEN /100 conversion
        String rawOrderNew = """
                {
                  "msg_id": "msg-002",
                  "msg_kind": "order_new",
                  "occurred_ms": 1725538531000,
                  "order_ref": "ord-fb-100",
                  "buyer_uid": "buyer-123",
                  "order_total_fen": 204200,
                  "order_state": "CREATED",
                  "order_lines": [
                    { "item_sku": "sku-456", "qty": 2, "unit_price_fen": 102100, "line_amount_fen": 204200 }
                  ]
                }
                """;

        MapperOutcome o2 = mapper.map(rawOrderNew, fixtureBProfile);
        assertThat(o2).isInstanceOf(MapperOutcome.Canonical.class);
        JsonNode rec2 = ((MapperOutcome.Canonical) o2).canonicalRecord();

        assertThat(rec2.get("event_type").asText()).isEqualTo("order_created");
        assertThat(rec2.path("payload").get("order_id").asText()).isEqualTo("ord-fb-100");
        assertThat(rec2.path("payload").get("user_id").asText()).isEqualTo("buyer-123");
        assertThat(rec2.path("payload").get("total_amount").asText()).isEqualTo("2042.00"); // 204200 FEN / 100

        JsonNode items = rec2.path("payload").get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("product_id").asText()).isEqualTo("sku-456");
        assertThat(items.get(0).get("quantity").asLong()).isEqualTo(2);
        assertThat(items.get(0).get("unit_price").asText()).isEqualTo("1021.00");
        assertThat(items.get(0).get("amount").asText()).isEqualTo("2042.00");

        // Event type mapping to null in fixture-b: coupon_redeemed
        String rawUnmappedEvent = """
                {
                  "msg_id": "msg-003",
                  "msg_kind": "coupon_redeemed",
                  "occurred_ms": 1725538531000
                }
                """;
        MapperOutcome o3 = mapper.map(rawUnmappedEvent, fixtureBProfile);
        assertThat(o3).isInstanceOf(MapperOutcome.Quarantined.class);
        assertThat(((MapperOutcome.Quarantined) o3).violations())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.UNKNOWN_EVENT_TYPE);
    }
}
