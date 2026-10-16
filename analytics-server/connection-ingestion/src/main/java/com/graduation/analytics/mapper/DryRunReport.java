package com.graduation.analytics.mapper;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Batch dry-run report value object (Deliverable 3, Rule-10/Rule-13).
 *
 * <p>Immutable. All fields documented with their design rule origin.</p>
 */
public record DryRunReport(

        /** Profile version string (Rule-14) */
        String profileVersion,

        /** SHA-256 hex over profile bytes (Rule-14) */
        String profileContentHash,

        /** Total raw rows processed */
        long totalRows,

        /** Rows that produced Canonical outcomes */
        long canonicalRows,

        /** Rows that produced Quarantined outcomes */
        long quarantinedRows,

        /**
         * Required-field coverage per Rule-13:
         * successful required positions / total required positions.
         * null if denominator is 0.
         */
        Double requiredCoverage,

        /**
         * Enum coverage per Rule-13:
         * resolved enum positions / total observed enum positions.
         * null if denominator is 0.
         */
        Double enumCoverage,

        /**
         * Count per violation code across all quarantined rows.
         * May exceed quarantinedRows because one row can have multiple violations.
         */
        Map<String, Long> reasonCounts,

        /**
         * Unresolved enum occurrences: key = "fieldPath=rawValue", value = count.
         * Rule-12: null enum = ENUM_UNRESOLVED, keep + warn + count.
         */
        Map<String, Long> unresolvedEnumCounts,

        /**
         * Q6: count of rows accepted by the existing EventContractValidator.
         * Report only, no decision (Q6 ruling is project lead's).
         */
        long validatorAccepted,

        /**
         * Q6: count of rows accepted by the strict canonical-event contract check.
         * Report only, no decision (Q6 ruling is project lead's).
         */
        long strictContractAccepted,

        /**
         * Q4: count of order_created events where items was passed through as a string.
         * Report only (Q4 ruling is project lead's).
         */
        long itemsPassthroughFlagged,

        /**
         * Preview of at most 100 successfully transformed records in desensitized form.
         * Rule-10: mask values of fields the profile/contract marks sensitive.
         * If none are marked, values pass through unchanged.
         */
        List<ObjectNode> preview,

        /**
         * Whether any sensitive fields were masked in the preview,
         * or "no sensitive fields marked" if none exist.
         */
        String previewSensitiveNote

) {
}
