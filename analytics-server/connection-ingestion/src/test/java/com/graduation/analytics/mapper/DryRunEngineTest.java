package com.graduation.analytics.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DryRunEngine and DryRunReport (Deliverable 3, Rule-10, Rule-13, Q4, Q6).
 */
class DryRunEngineTest {

    private final PureMapper mapper = new PureMapper();
    private final DryRunEngine engine = new DryRunEngine(mapper);

    @Test
    @DisplayName("Rule 13: coverage math including denominator 0 => null case")
    void rule13_coverageMath_denominatorZeroReturnsNull() throws IOException {
        String profileJson = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "test-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "view": "behavior" },
                  "fieldMapping": {
                    "event_type": "event_type",
                    "event_time": "event_time"
                  },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["ISO_OFFSET_DATE_TIME"]
                  }
                }
                """;
        SourceProfile profile = SourceProfile.fromJson(profileJson);

        // Empty lines -> totalRows = 0, denominator 0 -> null
        DryRunReport emptyReport = engine.run(profile, List.of());
        assertThat(emptyReport.totalRows()).isEqualTo(0);
        assertThat(emptyReport.requiredCoverage()).isNull();
        assertThat(emptyReport.enumCoverage()).isNull();
        assertThat(emptyReport.reasonCounts()).isEmpty();
        assertThat(emptyReport.unresolvedEnumCounts()).isEmpty();

        // Valid rows with no enum fields present -> enumCoverage is null
        String raw = """
                {"event_type": "view", "event_time": "2026-09-05T20:15:31+08:00",
                 "user_id": "u1", "product_id": "p1", "session_id": "s1", "behavior_type": "view"}
                """;
        DryRunReport reportNoEnums = engine.run(profile, List.of(raw));
        assertThat(reportNoEnums.enumCoverage()).isNull();
        assertThat(reportNoEnums.requiredCoverage()).isNotNull();
    }

    @Test
    @DisplayName("Rule 13: reasonCounts can exceed quarantinedRows when a single row has multiple violations")
    void rule13_reasonCountsMayExceedQuarantinedRows() throws IOException {
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
                    "items": "items"
                  },
                  "amountPolicy": { "total_amount": "FEN" },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["ISO_OFFSET_DATE_TIME"]
                  }
                }
                """;
        SourceProfile profile = SourceProfile.fromJson(profileJson);

        // Row has multiple violations:
        // 1. total_amount is non-integral FEN (BAD_AMOUNT)
        // 2. order_id is blank (EMPTY_FIELD)
        String rawMultiViolation = """
                {"event_type": "order_create", "event_time": "2026-09-05T20:15:31+08:00",
                 "order_id": "   ", "user_id": "u1", "total_amount": 100.5,
                 "items": [{"product_id": "p1", "quantity": 1}]}
                """;

        DryRunReport report = engine.run(profile, List.of(rawMultiViolation));
        assertThat(report.quarantinedRows()).isEqualTo(1);

        // Total count of reasons across violation codes > quarantinedRows (1)
        long totalReasons = report.reasonCounts().values().stream().mapToLong(Long::longValue).sum();
        assertThat(totalReasons).isGreaterThan(report.quarantinedRows());
        assertThat(report.reasonCounts()).containsKey("BAD_AMOUNT");
        assertThat(report.reasonCounts()).containsKey("EMPTY_FIELD");
    }

    @Test
    @DisplayName("Rule 10: preview at most 100 records and desensitization masking")
    void rule10_previewLimit100AndDesensitization() throws IOException {
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
                  "sensitiveFields": ["user_id"],
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["ISO_OFFSET_DATE_TIME"]
                  }
                }
                """;
        SourceProfile profileWithSensitive = SourceProfile.fromJson(profileJson);

        // Generate 120 valid records
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            lines.add(String.format(
                    "{\"event_type\": \"view\", \"event_time\": \"2026-09-05T20:15:31+08:00\", " +
                            "\"user_id\": \"u%d\", \"product_id\": \"p%d\", \"session_id\": \"s%d\", \"behavior_type\": \"view\"}",
                    i, i, i));
        }

        DryRunReport report = engine.run(profileWithSensitive, lines);

        assertThat(report.totalRows()).isEqualTo(120);
        assertThat(report.canonicalRows()).isEqualTo(120);
        assertThat(report.preview()).hasSize(100); // capped at 100

        // Rule-10: user_id is masked in preview
        assertThat(report.preview().get(0).path("payload").get("user_id").asText()).isEqualTo("***MASKED***");
        assertThat(report.previewSensitiveNote()).contains("masked");

        // Profile without sensitive fields: values passed through unchanged
        String profileNoSensitiveJson = profileJson.replace("\"sensitiveFields\": [\"user_id\"],", "");
        SourceProfile profileNoSensitive = SourceProfile.fromJson(profileNoSensitiveJson);
        DryRunReport reportUnmasked = engine.run(profileNoSensitive, lines.subList(0, 5));
        assertThat(reportUnmasked.preview().get(0).path("payload").get("user_id").asText()).isEqualTo("u0");
        assertThat(reportUnmasked.previewSensitiveNote()).contains("No sensitive fields marked");
    }

    @Test
    @DisplayName("Q4 & Q6: itemsPassthroughFlagged, validatorAccepted, and strictContractAccepted counted separately")
    void q4AndQ6_reportingExactSeparation() throws IOException {
        String profileJson = """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "mock-mall",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "order_created": "order_created" },
                  "fieldMapping": {
                    "event_type": "event_type",
                    "event_time": "event_time",
                    "order_id": "order_id",
                    "user_id": "user_id",
                    "total_amount": "total_amount",
                    "items": "items"
                  },
                  "timePolicy": {
                    "field": "event_time",
                    "formats": ["ISO_OFFSET_DATE_TIME"]
                  }
                }
                """;
        SourceProfile profile = SourceProfile.fromJson(profileJson);

        // Record where order_created.items is passed through as a JSON string (Q4)
        String rawWithItemsString = """
                {
                  "event_id": "e1-uuid",
                  "event_type": "order_created",
                  "event_time": "2026-09-05T20:15:31+08:00",
                  "ingest_time": "2026-09-05T20:15:32+08:00",
                  "source_system": "mock-mall",
                  "schema_version": "1.0",
                  "trace_id": "t1-uuid",
                  "order_id": "o100",
                  "user_id": "u200",
                  "total_amount": "99.00",
                  "items": "[{\\"product_id\\":\\"p1\\",\\"quantity\\":1}]",
                  "payload": {
                    "order_id": "o100",
                    "user_id": "u200",
                    "total_amount": "99.00",
                    "items": "[{\\"product_id\\":\\"p1\\",\\"quantity\\":1}]"
                  }
                }
                """;

        DryRunReport report = engine.run(profile, List.of(rawWithItemsString));

        assertThat(report.totalRows()).isEqualTo(1);
        // Q4 flag
        assertThat(report.itemsPassthroughFlagged()).isEqualTo(1);

        // Q6 counts reported separately
        assertThat(report.validatorAccepted()).isEqualTo(1);
        assertThat(report.strictContractAccepted()).isEqualTo(1);
    }
}
