package com.graduation.analytics.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PureMapper (Deliverable 2, Rules 2-9, 12).
 */
class PureMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PureMapper pureMapper = new PureMapper();

    @Test
    @DisplayName("Rule 2: profile invalid quarantines record immediately")
    void rule2_profileInvalid_quarantinesImmediately() throws IOException {
        String profileJson = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "fieldMapping": {
                    "order_created": {
                      "src1": "order_id",
                      "src2": "order_id"
                    }
                  }
                }
                """;
        SourceProfile profile = SourceProfile.fromJson(profileJson);
        String rawJson = "{\"src1\": \"123\", \"src2\": \"456\"}";

        MapperOutcome outcome = pureMapper.map(rawJson, profile);

        assertThat(outcome).isInstanceOf(MapperOutcome.Quarantined.class);
        MapperOutcome.Quarantined q = (MapperOutcome.Quarantined) outcome;
        assertThat(q.violations()).extracting(MapperViolation::code)
                .contains(MapperViolationCode.PROFILE_INVALID);
        assertThat(q.originalJson()).isEqualTo(rawJson);
    }

    @Test
    @DisplayName("Rule 3: @keep extension preserved by leaf name; original JSON preserved separately in outcome")
    void rule3_keepExtensionAndOriginalJsonPreserved() throws IOException {
        String profileJson = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "view": "behavior" },
                  "fieldMapping": {
                    "event_type": "event_type",
                    "event_time": "event_time",
                    "user_id": "user_id",
                    "product_id": "product_id",
                    "session_id": "session_id",
                    "behavior_type": "behavior_type",
                    "nested.ext_coupon": "@keep"
                  },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["ISO_OFFSET_DATE_TIME"]
                  }
                }
                """;
        SourceProfile profile = SourceProfile.fromJson(profileJson);
        String rawJson = """
                {
                  "event_type": "view",
                  "event_time": "2026-09-05T20:15:31+08:00",
                  "user_id": "u100",
                  "product_id": "p200",
                  "session_id": "s300",
                  "behavior_type": "view",
                  "nested": { "ext_coupon": "PROMO99" }
                }
                """;

        MapperOutcome outcome = pureMapper.map(rawJson, profile);

        assertThat(outcome).isInstanceOf(MapperOutcome.Canonical.class);
        MapperOutcome.Canonical c = (MapperOutcome.Canonical) outcome;

        // Leaf name preserved (Rule-3)
        assertThat(c.canonicalRecord().has("ext_coupon")).isTrue();
        assertThat(c.canonicalRecord().get("ext_coupon").asText()).isEqualTo("PROMO99");

        // Rule-3 & Rule-8: original JSON preserved separately
        assertThat(c.originalJson()).isEqualTo(rawJson);
    }

    @Test
    @DisplayName("Rule 4: ordered strict time formats, first success wins")
    void rule4_orderedStrictTimeFormats_firstSuccessWins() throws IOException {
        String profileJson = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "view": "behavior" },
                  "fieldMapping": {
                    "event_type": "event_type",
                    "event_time": "event_time",
                    "user_id": "user_id",
                    "product_id": "product_id",
                    "session_id": "session_id",
                    "behavior_type": "behavior_type"
                  },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss"]
                  }
                }
                """;
        SourceProfile profile = SourceProfile.fromJson(profileJson);

        // Matches first format
        String raw1 = """
                {"event_type": "view", "event_time": "2026/09/05 20:15:31", "user_id": "u1", "product_id": "p1", "session_id": "s1", "behavior_type": "view"}
                """;
        MapperOutcome o1 = pureMapper.map(raw1, profile);
        assertThat(o1).isInstanceOf(MapperOutcome.Canonical.class);
        assertThat(((MapperOutcome.Canonical) o1).canonicalRecord().get("event_time_original").asText())
                .isEqualTo("2026/09/05 20:15:31");
        assertThat(((MapperOutcome.Canonical) o1).canonicalRecord().get("event_time").asText())
                .contains("2026-09-05T20:15:31");

        // Matches second format
        String raw2 = """
                {"event_type": "view", "event_time": "2026-09-05 20:15:31", "user_id": "u1", "product_id": "p1", "session_id": "s1", "behavior_type": "view"}
                """;
        MapperOutcome o2 = pureMapper.map(raw2, profile);
        assertThat(o2).isInstanceOf(MapperOutcome.Canonical.class);
        assertThat(((MapperOutcome.Canonical) o2).canonicalRecord().get("event_time_original").asText())
                .isEqualTo("2026-09-05 20:15:31");
        assertThat(((MapperOutcome.Canonical) o2).canonicalRecord().get("event_time").asText())
                .contains("2026-09-05T20:15:31");
    }

    @Test
    @DisplayName("Rule 4: epoch unit must be declared; undeclared = violation; declared = success")
    void rule4_epochUnitDeclaredRequirement() throws IOException {
        // Profile WITHOUT epoch format declared
        String profileNoEpoch = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "view": "behavior" },
                  "fieldMapping": {
                    "event_type": "event_type",
                    "event_time": "event_time",
                    "user_id": "user_id", "product_id": "product_id", "session_id": "session_id", "behavior_type": "behavior_type"
                  },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["ISO_OFFSET_DATE_TIME"]
                  }
                }
                """;
        SourceProfile pNoEpoch = SourceProfile.fromJson(profileNoEpoch);
        String rawEpoch = """
                {"event_type": "view", "event_time": "1725538531000", "user_id": "u1", "product_id": "p1", "session_id": "s1", "behavior_type": "view"}
                """;

        MapperOutcome o1 = pureMapper.map(rawEpoch, pNoEpoch);
        assertThat(o1).isInstanceOf(MapperOutcome.Quarantined.class);
        assertThat(((MapperOutcome.Quarantined) o1).violations())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.BAD_TIME_FORMAT);

        // Profile WITH EPOCH_MILLIS declared
        String profileEpoch = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "view": "behavior" },
                  "fieldMapping": {
                    "event_type": "event_type",
                    "event_time": "event_time",
                    "user_id": "user_id", "product_id": "product_id", "session_id": "session_id", "behavior_type": "behavior_type"
                  },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["EPOCH_MILLIS"]
                  }
                }
                """;
        SourceProfile pEpoch = SourceProfile.fromJson(profileEpoch);
        MapperOutcome o2 = pureMapper.map(rawEpoch, pEpoch);
        assertThat(o2).isInstanceOf(MapperOutcome.Canonical.class);
        assertThat(((MapperOutcome.Canonical) o2).canonicalRecord().get("event_time_original").asText())
                .isEqualTo("1725538531000");
        assertThat(((MapperOutcome.Canonical) o2).canonicalRecord().get("event_time").asText())
                .isNotBlank();
    }

    @Test
    @DisplayName("Rule 4: DST gap and overlap -> TIME_AMBIGUOUS_LOCAL")
    void rule4_dstGapAndOverlap_timeAmbiguousLocal() throws IOException {
        String profileJson = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "view": "behavior" },
                  "fieldMapping": {
                    "event_type": "event_type",
                    "event_time": "event_time",
                    "user_id": "user_id", "product_id": "product_id", "session_id": "session_id", "behavior_type": "behavior_type"
                  },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["yyyy-MM-dd HH:mm:ss"],
                    "timezone": "America/New_York"
                  }
                }
                """;
        SourceProfile profile = SourceProfile.fromJson(profileJson);

        // Gap in New York: 2021-03-14 02:30:00 does not exist (clocks spring from 2:00 to 3:00)
        String rawGap = """
                {"event_type": "view", "event_time": "2021-03-14 02:30:00", "user_id": "u1", "product_id": "p1", "session_id": "s1", "behavior_type": "view"}
                """;
        MapperOutcome oGap = pureMapper.map(rawGap, profile);
        assertThat(oGap).isInstanceOf(MapperOutcome.Quarantined.class);
        assertThat(((MapperOutcome.Quarantined) oGap).violations())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.TIME_AMBIGUOUS_LOCAL);
        assertThat(((MapperOutcome.Quarantined) oGap).violations().get(0).detail()).contains("gap");

        // Overlap in New York: 2021-11-07 01:30:00 occurs twice (clocks fall back from 2:00 to 1:00)
        String rawOverlap = """
                {"event_type": "view", "event_time": "2021-11-07 01:30:00", "user_id": "u1", "product_id": "p1", "session_id": "s1", "behavior_type": "view"}
                """;
        MapperOutcome oOverlap = pureMapper.map(rawOverlap, profile);
        assertThat(oOverlap).isInstanceOf(MapperOutcome.Quarantined.class);
        assertThat(((MapperOutcome.Quarantined) oOverlap).violations())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.TIME_AMBIGUOUS_LOCAL);
        assertThat(((MapperOutcome.Quarantined) oOverlap).violations().get(0).detail()).contains("overlap");
    }

    @Test
    @DisplayName("Rule 5: missing, null, and blank distinguished as MISSING, NULL, BLANK details of EMPTY_FIELD; optional omitted, no zero-fill")
    void rule5_emptyFieldDistinctionsAndNoZeroFill() throws IOException {
        String profileJson = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "order_create": "order_created" },
                  "fieldMapping": {
                    "event_type": "event_type",
                    "event_time": "event_time",
                    "order_id": "order_id",
                    "user_id": "user_id",
                    "total_amount": "total_amount",
                    "items": "items",
                    "optional_note": "note"
                  },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["ISO_OFFSET_DATE_TIME"]
                  }
                }
                """;
        SourceProfile profile = SourceProfile.fromJson(profileJson);

        // Case 1: required field order_id is MISSING
        String rawMissing = """
                {"event_type": "order_create", "event_time": "2026-09-05T20:15:31+08:00",
                 "user_id": "u1", "total_amount": "100.00",
                 "items": [{"product_id": "p1", "quantity": 1}]}
                """;
        MapperOutcome o1 = pureMapper.map(rawMissing, profile);
        assertThat(o1).isInstanceOf(MapperOutcome.Quarantined.class);
        MapperViolation v1 = ((MapperOutcome.Quarantined) o1).violations().stream()
                .filter(v -> "order_id".equals(v.fieldPath()))
                .findFirst().orElseThrow();
        assertThat(v1.code()).isEqualTo(MapperViolationCode.EMPTY_FIELD);
        assertThat(v1.detail()).isEqualTo("MISSING");

        // Case 2: required field order_id is NULL
        String rawNull = """
                {"event_type": "order_create", "event_time": "2026-09-05T20:15:31+08:00",
                 "order_id": null, "user_id": "u1", "total_amount": "100.00",
                 "items": [{"product_id": "p1", "quantity": 1}]}
                """;
        MapperOutcome o2 = pureMapper.map(rawNull, profile);
        assertThat(o2).isInstanceOf(MapperOutcome.Quarantined.class);
        MapperViolation v2 = ((MapperOutcome.Quarantined) o2).violations().stream()
                .filter(v -> "order_id".equals(v.fieldPath()))
                .findFirst().orElseThrow();
        assertThat(v2.code()).isEqualTo(MapperViolationCode.EMPTY_FIELD);
        assertThat(v2.detail()).isEqualTo("NULL");

        // Case 3: required field order_id is BLANK
        String rawBlank = """
                {"event_type": "order_create", "event_time": "2026-09-05T20:15:31+08:00",
                 "order_id": "   ", "user_id": "u1", "total_amount": "100.00",
                 "items": [{"product_id": "p1", "quantity": 1}]}
                """;
        MapperOutcome o3 = pureMapper.map(rawBlank, profile);
        assertThat(o3).isInstanceOf(MapperOutcome.Quarantined.class);
        MapperViolation v3 = ((MapperOutcome.Quarantined) o3).violations().stream()
                .filter(v -> "order_id".equals(v.fieldPath()))
                .findFirst().orElseThrow();
        assertThat(v3.code()).isEqualTo(MapperViolationCode.EMPTY_FIELD);
        assertThat(v3.detail()).isEqualTo("BLANK");

        // Case 4: optional field omitted -> not in canonical, no zero fill
        String rawValidWithoutOptional = """
                {"event_type": "order_create", "event_time": "2026-09-05T20:15:31+08:00",
                 "order_id": "ord123", "user_id": "u1", "total_amount": "100.00",
                 "items": [{"product_id": "p1", "quantity": 1}]}
                """;
        MapperOutcome o4 = pureMapper.map(rawValidWithoutOptional, profile);
        assertThat(o4).isInstanceOf(MapperOutcome.Canonical.class);
        JsonNode rec = ((MapperOutcome.Canonical) o4).canonicalRecord();
        assertThat(rec.has("note")).isFalse(); // omitted
        assertThat(rec.path("note").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("Rule 7: BigDecimal exact parsing, FEN->yuan /100, non-integral fen rejected, no double path, negative rejected")
    void rule7_amountPolicyAndPrecision() throws IOException {
        String profileJson = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "order_paid": "order_paid" },
                  "fieldMapping": {
                    "event_type": "event_type",
                    "event_time": "event_time",
                    "order_id": "order_id",
                    "user_id": "user_id",
                    "payment_id": "payment_id",
                    "paid_fen": "amount"
                  },
                  "amountPolicy": {
                    "paid_fen": "FEN"
                  },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["ISO_OFFSET_DATE_TIME"]
                  }
                }
                """;
        SourceProfile profile = SourceProfile.fromJson(profileJson);

        // Exact FEN /100 conversion
        String rawValid = """
                {"event_type": "order_paid", "event_time": "2026-09-05T20:15:31+08:00",
                 "order_id": "o1", "user_id": "u1", "payment_id": "p1", "paid_fen": 204200}
                """;
        MapperOutcome o1 = pureMapper.map(rawValid, profile);
        assertThat(o1).isInstanceOf(MapperOutcome.Canonical.class);
        assertThat(((MapperOutcome.Canonical) o1).canonicalRecord().get("amount").asText())
                .isEqualTo("2042.00");

        // Non-integral FEN rejected
        String rawNonIntegral = """
                {"event_type": "order_paid", "event_time": "2026-09-05T20:15:31+08:00",
                 "order_id": "o1", "user_id": "u1", "payment_id": "p1", "paid_fen": 204200.5}
                """;
        MapperOutcome o2 = pureMapper.map(rawNonIntegral, profile);
        assertThat(o2).isInstanceOf(MapperOutcome.Quarantined.class);
        assertThat(((MapperOutcome.Quarantined) o2).violations())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.BAD_AMOUNT);
        assertThat(((MapperOutcome.Quarantined) o2).violations().get(0).detail()).contains("integral");

        // Negative amount rejected
        String rawNegative = """
                {"event_type": "order_paid", "event_time": "2026-09-05T20:15:31+08:00",
                 "order_id": "o1", "user_id": "u1", "payment_id": "p1", "paid_fen": -100}
                """;
        MapperOutcome o3 = pureMapper.map(rawNegative, profile);
        assertThat(o3).isInstanceOf(MapperOutcome.Quarantined.class);
        assertThat(((MapperOutcome.Quarantined) o3).violations())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.BAD_AMOUNT);
    }

    @Test
    @DisplayName("Rule 9: object paths + fixed indices; ITEM_MAP preserves order+count; one failing item quarantines whole event")
    void rule9_pathsAndItemMapPreservesOrderAndQuarantinesOnFailure() throws IOException {
        String profileJson = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "order_create": "order_created" },
                  "fieldMapping": {
                    "event_type": "event_type",
                    "event_time": "event_time",
                    "data.order.id": "order_id",
                    "data.customer.id": "user_id",
                    "total_amount": "total_amount",
                    "order_lines": "items"
                  },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["ISO_OFFSET_DATE_TIME"]
                  }
                }
                """;
        SourceProfile profile = SourceProfile.fromJson(profileJson);

        // Success: object paths resolved, ITEM_MAP preserves order and count
        String rawValid = """
                {
                  "event_type": "order_create",
                  "event_time": "2026-09-05T20:15:31+08:00",
                  "data": {
                    "order": { "id": "ORD-999" },
                    "customer": { "id": "CUST-111" }
                  },
                  "total_amount": "150.00",
                  "order_lines": [
                    { "product_id": "p1", "quantity": 2, "unit_price": "50.00" },
                    { "product_id": "p2", "quantity": 1, "unit_price": "50.00" }
                  ]
                }
                """;
        MapperOutcome o1 = pureMapper.map(rawValid, profile);
        assertThat(o1).isInstanceOf(MapperOutcome.Canonical.class);
        JsonNode rec = ((MapperOutcome.Canonical) o1).canonicalRecord();
        assertThat(rec.get("order_id").asText()).isEqualTo("ORD-999");
        assertThat(rec.get("user_id").asText()).isEqualTo("CUST-111");

        JsonNode items = rec.get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("product_id").asText()).isEqualTo("p1");
        assertThat(items.get(1).get("product_id").asText()).isEqualTo("p2");

        // Failure: one item invalid (quantity <= 0) quarantines the whole event
        String rawFailingItem = """
                {
                  "event_type": "order_create",
                  "event_time": "2026-09-05T20:15:31+08:00",
                  "data": {
                    "order": { "id": "ORD-999" },
                    "customer": { "id": "CUST-111" }
                  },
                  "total_amount": "150.00",
                  "order_lines": [
                    { "product_id": "p1", "quantity": 2, "unit_price": "50.00" },
                    { "product_id": "p2", "quantity": -5, "unit_price": "50.00" }
                  ]
                }
                """;
        MapperOutcome o2 = pureMapper.map(rawFailingItem, profile);
        assertThat(o2).isInstanceOf(MapperOutcome.Quarantined.class);
        assertThat(((MapperOutcome.Quarantined) o2).violations())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.BAD_AMOUNT);
        assertThat(((MapperOutcome.Quarantined) o2).violations().get(0).fieldPath())
                .isEqualTo("items[1].quantity");

        // Disallow wildcard or arbitrary recursion
        assertThatThrownBy(() -> PureMapper.resolvePath(rec, "items[*].product_id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rule 12: profile null enum -> value kept + ENUM_UNRESOLVED warning; unregistered value -> BAD_ENUM")
    void rule12_nullEnumKeptWithWarning_unregisteredIsBadEnum() throws IOException {
        String profileJson = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "view": "behavior" },
                  "fieldMapping": {
                    "event_type": "event_type",
                    "event_time": "event_time",
                    "user_id": "user_id",
                    "product_id": "product_id",
                    "session_id": "session_id",
                    "behavior_type": "behavior_type",
                    "channel": "channel"
                  },
                  "enumSemantics": {
                    "channel": {
                      "app": "app",
                      "pc": "pc",
                      "web": null
                    }
                  },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["ISO_OFFSET_DATE_TIME"]
                  }
                }
                """;
        SourceProfile profile = SourceProfile.fromJson(profileJson);

        // Case 1: "web" is explicit null in enumSemantics -> value kept + ENUM_UNRESOLVED warning
        String rawWeb = """
                {"event_type": "view", "event_time": "2026-09-05T20:15:31+08:00",
                 "user_id": "u1", "product_id": "p1", "session_id": "s1",
                 "behavior_type": "view", "channel": "web"}
                """;
        MapperOutcome o1 = pureMapper.map(rawWeb, profile);
        assertThat(o1).isInstanceOf(MapperOutcome.Canonical.class);
        MapperOutcome.Canonical c = (MapperOutcome.Canonical) o1;
        assertThat(c.canonicalRecord().get("channel").asText()).isEqualTo("web");
        assertThat(c.unresolvedEnumWarnings()).hasSize(1);
        assertThat(c.unresolvedEnumWarnings().get(0).code()).isEqualTo(MapperViolationCode.ENUM_UNRESOLVED);
        assertThat(c.unresolvedEnumWarnings().get(0).rawValue()).isEqualTo("web");

        // Case 2: "tiktok" is unregistered -> BAD_ENUM, quarantined
        String rawUnregistered = """
                {"event_type": "view", "event_time": "2026-09-05T20:15:31+08:00",
                 "user_id": "u1", "product_id": "p1", "session_id": "s1",
                 "behavior_type": "view", "channel": "tiktok"}
                """;
        MapperOutcome o2 = pureMapper.map(rawUnregistered, profile);
        assertThat(o2).isInstanceOf(MapperOutcome.Quarantined.class);
        assertThat(((MapperOutcome.Quarantined) o2).violations())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.BAD_ENUM);
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
    @DisplayName("Ruling R-2A-1: fixture-b flat profile loads with isValid() == true")
    void rulingR2A1_fixtureBProfileLoadsAsValid() throws IOException {
        Path fixtureBPath = findProfile("fixture-b.v1.json");
        SourceProfile profile = SourceProfile.fromFile(fixtureBPath);
        assertThat(profile.isValid()).isTrue();
        assertThat(profile.validationErrors()).isEmpty();
    }

    @Test
    @DisplayName("Ruling R-2A-1: single record carrying both sale_state and order_state is quarantined with PROFILE_INVALID naming status")
    void rulingR2A1_recordCarryingBothSaleStateAndOrderState_quarantinedWithProfileInvalidNamingStatus() throws IOException {
        Path fixtureBPath = findProfile("fixture-b.v1.json");
        SourceProfile profile = SourceProfile.fromFile(fixtureBPath);
        assertThat(profile.isValid()).isTrue();

        // Carrying both sale_state and order_state (different values)
        String rawBothStates = """
                {
                  "msg_id": "msg-conflict-1",
                  "msg_kind": "order_new",
                  "occurred_ms": 1725538531000,
                  "order_ref": "ord-conflict-1",
                  "buyer_uid": "buyer-123",
                  "order_total_fen": 10000,
                  "order_state": "CREATED",
                  "sale_state": "ON_SHELF",
                  "order_lines": [
                    { "item_sku": "sku-1", "qty": 1, "unit_price_fen": 10000, "line_amount_fen": 10000 }
                  ]
                }
                """;

        MapperOutcome outcome = pureMapper.map(rawBothStates, profile);
        assertThat(outcome).isInstanceOf(MapperOutcome.Quarantined.class);
        MapperOutcome.Quarantined q = (MapperOutcome.Quarantined) outcome;
        assertThat(q.violations())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.PROFILE_INVALID);

        MapperViolation violation = q.violations().stream()
                .filter(v -> v.code() == MapperViolationCode.PROFILE_INVALID)
                .findFirst()
                .orElseThrow();
        assertThat(violation.fieldPath()).isEqualTo("status");
        assertThat(violation.detail()).contains("sale_state", "order_state");

        // Regardless of whether the values are equal (Ruling R-2A-1 point 2)
        String rawEqualValues = rawBothStates.replace("\"sale_state\": \"ON_SHELF\"", "\"sale_state\": \"CREATED\"");
        MapperOutcome oEqual = pureMapper.map(rawEqualValues, profile);
        assertThat(oEqual).isInstanceOf(MapperOutcome.Quarantined.class);
        MapperOutcome.Quarantined qEqual = (MapperOutcome.Quarantined) oEqual;
        assertThat(qEqual.violations())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.PROFILE_INVALID);
    }

    @Test
    @DisplayName("Ruling R-2A-1: record carrying only one of sale_state or order_state maps normally to status")
    void rulingR2A1_recordCarryingOnlyOneOfSaleStateOrOrderState_mapsNormallyToStatus() throws IOException {
        Path fixtureBPath = findProfile("fixture-b.v1.json");
        SourceProfile profile = SourceProfile.fromFile(fixtureBPath);
        assertThat(profile.isValid()).isTrue();

        // 1. Record carrying only order_state -> status maps to "CREATED"
        String rawOrderStateOnly = """
                {
                  "msg_id": "msg-order-state-only",
                  "msg_kind": "order_new",
                  "occurred_ms": 1725538531000,
                  "order_ref": "ord-single-1",
                  "buyer_uid": "buyer-123",
                  "order_total_fen": 10000,
                  "order_state": "CREATED",
                  "order_lines": [
                    { "item_sku": "sku-1", "qty": 1, "unit_price_fen": 10000, "line_amount_fen": 10000 }
                  ]
                }
                """;

        MapperOutcome o1 = pureMapper.map(rawOrderStateOnly, profile);
        assertThat(o1).isInstanceOf(MapperOutcome.Canonical.class);
        JsonNode rec1 = ((MapperOutcome.Canonical) o1).canonicalRecord();
        assertThat(rec1.get("status").asText()).isEqualTo("CREATED");

        // 2. Record carrying only sale_state -> status maps to "on_sale" (item_new -> product_created)
        String rawSaleStateOnly = """
                {
                  "msg_id": "msg-sale-state-only",
                  "msg_kind": "item_new",
                  "occurred_ms": 1725538531000,
                  "item_sku": "sku-item-1",
                  "item_title": "Test Product",
                  "category_ref": "cat-1",
                  "list_price_fen": 5000,
                  "sale_state": "ON_SHELF"
                }
                """;

        MapperOutcome o2 = pureMapper.map(rawSaleStateOnly, profile);
        assertThat(o2).isInstanceOf(MapperOutcome.Canonical.class);
        JsonNode rec2 = ((MapperOutcome.Canonical) o2).canonicalRecord();
        assertThat(rec2.get("status").asText()).isEqualTo("on_sale");
    }
}
