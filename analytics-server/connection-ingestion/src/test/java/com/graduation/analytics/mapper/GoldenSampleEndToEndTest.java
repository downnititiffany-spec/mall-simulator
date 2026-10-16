package com.graduation.analytics.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Permanent regression test for Golden Dataset end-to-end execution (WP-2A-2-FIX2 / Ruling R-2A-2).
 *
 * <p>Verifies mock-mall.v1 profile processing the real 55-line golden dataset sample
 * (tests/golden-dataset/events/golden-20260901.jsonl), asserting envelope+payload canonical shape,
 * quarantine isolation, and engine counting semantics.</p>
 */
class GoldenSampleEndToEndTest {

    private final PureMapper mapper = new PureMapper();
    private final DryRunEngine engine = new DryRunEngine(mapper);

    private static Path findFile(String relativePath) {
        Path repoRoot = RepoPathLocator.locateRepoRoot(".");
        List<Path> candidates = List.of(
                repoRoot.resolve(relativePath),
                Path.of(relativePath),
                Path.of("../" + relativePath),
                Path.of("../../" + relativePath)
        );
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("Could not find file: " + relativePath + " (repoRoot=" + repoRoot + ")");
    }

    @Test
    @DisplayName("Golden sample end-to-end: mock-mall.v1 profile over real golden-20260901.jsonl (55 lines)")
    void mockMall_goldenDatasetSampleEndToEnd() throws IOException {
        Path profilePath = findFile("analytics-server/source-profiles/mock-mall.v1.json");
        SourceProfile profile = SourceProfile.fromFile(profilePath);
        assertThat(profile.isValid()).isTrue();

        Path samplePath = findFile("tests/golden-dataset/events/golden-20260901.jsonl");
        List<String> lines = Files.readAllLines(samplePath, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank())
                .toList();

        DryRunReport report = engine.run(profile, lines);

        // Required assertions per WP-2A-2-FIX2:
        // 1. totalRows = 55
        assertThat(report.totalRows()).isEqualTo(55);

        // 2. reasonCounts contains JSON_PARSE_ERROR = 1 (from line 53 malformed JSON)
        assertThat(report.reasonCounts()).containsEntry("JSON_PARSE_ERROR", 1L);

        // 3. canonicalRows > 0
        assertThat(report.canonicalRows()).isGreaterThan(0);

        // 4. canonicalRows + quarantinedRows = 55
        assertThat(report.canonicalRows() + report.quarantinedRows()).isEqualTo(55);

        // 5. Every preview record has a payload object and NO business fields at root
        assertThat(report.preview()).isNotEmpty();
        for (JsonNode rec : report.preview()) {
            assertThat(rec.has("payload"))
                    .as("Canonical record must have 'payload' container")
                    .isTrue();
            assertThat(rec.path("payload").isObject())
                    .as("'payload' must be an ObjectNode")
                    .isTrue();

            // None of the standard or mapped business fields should sit at the canonical root
            for (String businessField : List.of(
                    "user_id", "order_id", "product_id", "total_amount",
                    "amount", "channel", "status", "behavior_type",
                    "session_id", "payment_id", "items", "event_time_original")) {
                assertThat(rec.has(businessField))
                        .as("Business field '%s' must not sit at canonical root", businessField)
                        .isFalse();
            }

            // Envelope fields sit at canonical root.
            // NOTE (project lead, acceptance round 3): event_id is NOT asserted here.
            // Golden line 55 deliberately lacks event_id: the mapper still produces a
            // Canonical outcome for it (the mapper enforces declared mappings; contract
            // enforcement is the validator's gate — see the validatorAccepted figure and
            // open question Q6). event_type and event_time are present on all 54.
            assertThat(rec.has("event_type")).isTrue();
            assertThat(rec.has("event_time")).isTrue();
        }

        // =========================================================================
        // Exact counts produced by PureMapper / DryRunEngine per Ruling R-2A-2:
        //
        // - canonicalRows == 54 (54 valid JSON lines mapped successfully)
        // - quarantinedRows == 1 (line 53 has unparseable JSON line)
        // - validatorAccepted == 51:
        //     out of 54 canonical outputs, 51 pass legacy EventContractValidator.
        //     The 3 failing outputs are:
        //       * line 38: behavior_type="purchase" (not in validator's behavior whitelist)
        //       * line 54: schema_version="2.0" (validator requires "1.0")
        //       * line 55: lacks event_id
        // - strictContractAccepted == 44:
        //     among the 51 validator-accepted canonical outputs, 7 carry ENUM_UNRESOLVED warnings
        //     (lines 12, 13, 14, 34, 35, 36 have channel="web"; line 52 has change_type="restock"),
        //     so 51 - 7 = 44 pass validator with zero unresolved enum warnings.
        // =========================================================================
        assertThat(report.canonicalRows()).isEqualTo(54);
        assertThat(report.quarantinedRows()).isEqualTo(1);
        assertThat(report.validatorAccepted()).isEqualTo(51);
        assertThat(report.strictContractAccepted()).isEqualTo(44);
    }

    @Test
    @DisplayName("Golden sample loaded via MappingSampleResolver matches engine execution")
    void mockMall_goldenDatasetViaResolverEndToEnd() throws IOException {
        Path profilePath = findFile("analytics-server/source-profiles/mock-mall.v1.json");
        SourceProfile profile = SourceProfile.fromFile(profilePath);

        MappingSampleResolver resolver = new MappingSampleResolver(".");
        List<String> lines = resolver.resolveAndLoad("golden-20260901.jsonl");

        DryRunReport report = engine.run(profile, lines);

        assertThat(report.totalRows()).isEqualTo(55);
        assertThat(report.canonicalRows()).isEqualTo(54);
        assertThat(report.quarantinedRows()).isEqualTo(1);
        assertThat(report.validatorAccepted()).isEqualTo(51);
        assertThat(report.strictContractAccepted()).isEqualTo(44);
    }
}
