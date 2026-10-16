package com.graduation.analytics.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SourceProfile (Deliverable 1, Rule-2, Rule-3, Rule-14).
 */
class SourceProfileTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Deliverable 1 & Rule-14: profile model parses JSON, computes SHA-256 content hash, and maintains immutability")
    void profileModel_parsesJsonAndComputesSha256() throws IOException {
        String json = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "user_signup": "user_registered" },
                  "fieldMapping": { "uid": "user_id", "ext_tag": "@keep" },
                  "enumSemantics": {
                    "channel": { "app": "app", "web": null }
                  },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["ISO_OFFSET_DATE_TIME"],
                    "timezone": "Asia/Shanghai"
                  },
                  "sensitiveFields": ["user_id"]
                }
                """;

        Path file = tempDir.resolve("test-profile.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);

        SourceProfile profile = SourceProfile.fromFile(file);

        assertThat(profile.profileVersion()).isEqualTo("1.0");
        assertThat(profile.sourceCode()).isEqualTo("test-mall");
        assertThat(profile.canonicalSchemaVersion()).isEqualTo("1.0");
        assertThat(profile.contentHash()).isNotBlank().hasSize(64); // SHA-256 hex
        assertThat(profile.isValid()).isTrue();
        assertThat(profile.timePolicyField()).isEqualTo("event_time");
        assertThat(profile.timePolicyFormats()).containsExactly("ISO_OFFSET_DATE_TIME");
        assertThat(profile.timePolicyTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(profile.sensitiveFields()).containsExactly("user_id");
        assertThat(profile.keepFields()).containsExactly("ext_tag");

        // Reload and verify identical SHA-256 hash (Rule-14)
        SourceProfile reloaded = SourceProfile.fromFile(file);
        assertThat(reloaded.contentHash()).isEqualTo(profile.contentHash());
    }

    @Test
    @DisplayName("Rule 2: same eventType group, two source paths -> same target = PROFILE_INVALID")
    void rule2_sameEventTypeGroup_twoSourcePathsToSameTarget_profileInvalid() throws IOException {
        String json = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "order_new": "order_created" },
                  "fieldMapping": {
                    "order_created": {
                      "order_no": "order_id",
                      "order_id": "order_id"
                    }
                  }
                }
                """;

        SourceProfile profile = SourceProfile.fromJson(json);

        assertThat(profile.isValid()).isFalse();
        assertThat(profile.validationErrors())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.PROFILE_INVALID);

        MapperViolation violation = profile.validationErrors().get(0);
        assertThat(violation.fieldPath()).isEqualTo("order_created.order_id");
        assertThat(violation.detail()).contains("multiple source paths");
    }

    @Test
    @DisplayName("Rule 2: cross-group field-name reuse is legal")
    void rule2_crossGroup_fieldNameReuse_isLegal() throws IOException {
        String json = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": {
                    "order_new": "order_created",
                    "order_pay": "order_paid"
                  },
                  "fieldMapping": {
                    "order_created": {
                      "ref_no": "order_id"
                    },
                    "order_paid": {
                      "ref_no": "order_id"
                    }
                  }
                }
                """;

        SourceProfile profile = SourceProfile.fromJson(json);

        assertThat(profile.isValid()).isTrue();
        assertThat(profile.validationErrors()).isEmpty();
        assertThat(profile.fieldMappingForGroup("order_created")).containsEntry("ref_no", "order_id");
        assertThat(profile.fieldMappingForGroup("order_paid")).containsEntry("ref_no", "order_id");
    }

    @Test
    @DisplayName("Rule 3: @keep collision with canonical envelope or payload field rejected")
    void rule3_keepCollisionWithCanonicalField_rejected() throws IOException {
        // Colliding with canonical payload field "user_id"
        String jsonPayloadCollision = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "fieldMapping": {
                    "user_id": "@keep"
                  }
                }
                """;

        SourceProfile p1 = SourceProfile.fromJson(jsonPayloadCollision);
        assertThat(p1.isValid()).isFalse();
        assertThat(p1.validationErrors())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.PROFILE_INVALID);
        assertThat(p1.validationErrors().get(0).detail()).contains("collides with canonical field");

        // Colliding with canonical envelope field "event_id"
        String jsonEnvelopeCollision = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "fieldMapping": {
                    "event_id": "@keep"
                  }
                }
                """;

        SourceProfile p2 = SourceProfile.fromJson(jsonEnvelopeCollision);
        assertThat(p2.isValid()).isFalse();
        assertThat(p2.validationErrors())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.PROFILE_INVALID);
    }

    @Test
    @DisplayName("Rule 3: @keep collision with another extension rejected")
    void rule3_keepCollisionWithAnotherExtension_rejected() throws IOException {
        String json = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "fieldMapping": {
                    "order_created": {
                      "ext_coupon": "@keep",
                      "nested.ext_coupon": "@keep"
                    }
                  }
                }
                """;

        SourceProfile profile = SourceProfile.fromJson(json);

        assertThat(profile.isValid()).isFalse();
        assertThat(profile.validationErrors())
                .extracting(MapperViolation::code)
                .contains(MapperViolationCode.PROFILE_INVALID);
        assertThat(profile.validationErrors().get(0).detail()).contains("duplicate @keep extension name");
    }

    @Test
    @DisplayName("Rule 12: enum lookup distinguishes resolved, unresolved (null), and bad")
    void rule12_enumLookupDistinctions() throws IOException {
        String json = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "enumSemantics": {
                    "channel": {
                      "app": "app",
                      "pc": "pc",
                      "web": null
                    }
                  }
                }
                """;

        SourceProfile profile = SourceProfile.fromJson(json);

        // Resolved
        SourceProfile.EnumLookupResult r1 = profile.lookupEnum("channel", "app");
        assertThat(r1).isInstanceOf(SourceProfile.EnumLookupResult.Resolved.class);
        assertThat(((SourceProfile.EnumLookupResult.Resolved) r1).canonicalValue()).isEqualTo("app");

        // Unresolved (Rule-12 explicit null)
        SourceProfile.EnumLookupResult r2 = profile.lookupEnum("channel", "web");
        assertThat(r2).isInstanceOf(SourceProfile.EnumLookupResult.Unresolved.class);
        assertThat(((SourceProfile.EnumLookupResult.Unresolved) r2).rawValue()).isEqualTo("web");

        // Bad (unregistered)
        SourceProfile.EnumLookupResult r3 = profile.lookupEnum("channel", "unknown_channel");
        assertThat(r3).isInstanceOf(SourceProfile.EnumLookupResult.Bad.class);

        // Not an enum field
        SourceProfile.EnumLookupResult r4 = profile.lookupEnum("not_enum", "val");
        assertThat(r4).isInstanceOf(SourceProfile.EnumLookupResult.NotAnEnumField.class);
    }

    @Test
    @DisplayName("Ruling R-2A-1: flat profile (_global_) allows shared target fields without static PROFILE_INVALID")
    void rulingR2A1_flatProfileAllowsSharedTargetsStatically() throws IOException {
        String json = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "order_new": "order_created" },
                  "fieldMapping": {
                    "sale_state": "status",
                    "order_state": "status"
                  }
                }
                """;

        SourceProfile profile = SourceProfile.fromJson(json);
        assertThat(profile.isValid()).isTrue();
        assertThat(profile.validationErrors()).isEmpty();
    }
}
