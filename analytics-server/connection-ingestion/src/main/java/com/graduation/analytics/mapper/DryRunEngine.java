package com.graduation.analytics.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graduation.analytics.contracts.EventContract;
import com.graduation.analytics.ingestion.EventContractValidator;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Batch dry-run core engine (Deliverable 3, Rule-10/Rule-13).
 *
 * <p>Given a frozen SourceProfile and a list of raw JSON lines, produces a DryRunReport.
 * Zero persistence, zero I/O beyond inputs (Rule-10).</p>
 *
 * <p><b>Ruling R-2A-2 Clause 4 definitions:</b>
 * <ul>
 *   <li>{@code validatorAccepted} counts canonical OUTPUT records that pass the real {@link EventContractValidator}.</li>
 *   <li>{@code strictContractAccepted} counts canonical outputs that pass the validator AND whose mapper outcome carried no {@code ENUM_UNRESOLVED} warning.</li>
 *   <li>Inputs are never counted.</li>
 * </ul>
 * </p>
 */
public class DryRunEngine {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PureMapper mapper;
    private final EventContractValidator contractValidator;

    public DryRunEngine(PureMapper mapper, EventContractValidator contractValidator) {
        this.mapper = mapper;
        this.contractValidator = contractValidator;
    }

    public DryRunEngine(PureMapper mapper) {
        this(mapper, new EventContractValidator(new ObjectMapper()));
    }

    /**
     * Run a batch dry-run.
     *
     * @param profile  frozen source profile
     * @param rawLines raw JSON lines (one record per element)
     * @return DryRunReport value object
     */
    public DryRunReport run(SourceProfile profile, List<String> rawLines) {
        long totalRows = 0;
        long canonicalRows = 0;
        long quarantinedRows = 0;

        // Rule-13: required coverage tracking
        long requiredSuccessCount = 0;
        long requiredTotalCount = 0;

        // Rule-13: enum coverage tracking (deduplicated by field path + raw value)
        Set<String> observedEnumPairs = new LinkedHashSet<>();
        Set<String> resolvedEnumPairs = new LinkedHashSet<>();

        // Violation reason counts (may exceed quarantinedRows)
        Map<String, Long> reasonCounts = new LinkedHashMap<>();

        // Unresolved enum counts: "fieldPath=rawValue" -> count
        Map<String, Long> unresolvedEnumCounts = new LinkedHashMap<>();

        // Ruling R-2A-2 Clause 4: Q6 counters (canonical output counting)
        long validatorAccepted = 0;
        long strictContractAccepted = 0;
        long itemsPassthroughFlagged = 0;

        // Preview (at most 100 canonical records)
        List<ObjectNode> preview = new ArrayList<>();
        boolean sensitiveFieldsPresent = false;

        if (rawLines != null) {
            for (String rawLine : rawLines) {
                if (rawLine == null || rawLine.isBlank()) {
                    continue;
                }
                totalRows++;

                // Apply pure mapper
                MapperOutcome outcome = mapper.map(rawLine, profile);

                if (outcome instanceof MapperOutcome.Canonical c) {
                    canonicalRows++;

                    // Ruling R-2A-2 Clause 4:
                    // validatorAccepted counts canonical OUTPUT records that pass the real EventContractValidator.
                    // strictContractAccepted counts canonical outputs that pass the validator AND whose mapper outcome carried no ENUM_UNRESOLVED warning.
                    EventContractValidator.Violation contractViolation =
                            contractValidator.check(c.canonicalRecord().toString(), (int) canonicalRows);
                    if (contractViolation == null) {
                        validatorAccepted++;
                        boolean hasUnresolvedEnum = c.unresolvedEnumWarnings().stream()
                                .anyMatch(w -> w.code() == MapperViolationCode.ENUM_UNRESOLVED);
                        if (!hasUnresolvedEnum) {
                            strictContractAccepted++;
                        }
                    }

                    // Rule-13: count required fields that passed
                    RequiredFieldStats stats = countRequiredFields(c.canonicalRecord(), profile);
                    requiredSuccessCount += stats.successCount();
                    requiredTotalCount += stats.totalCount();

                    // Track enum coverage
                    trackCanonicalEnums(rawLine, c.canonicalRecord(), c.unresolvedEnumWarnings(),
                            profile, observedEnumPairs, resolvedEnumPairs);

                    // Q4: order_created.items passthrough flag
                    if (isItemsPassthrough(rawLine, c.canonicalRecord())) {
                        itemsPassthroughFlagged++;
                    }

                    // ENUM_UNRESOLVED warnings
                    for (MapperViolation w : c.unresolvedEnumWarnings()) {
                        if (w.code() == MapperViolationCode.ENUM_UNRESOLVED && w.rawValue() != null) {
                            String key = w.fieldPath() + "=" + w.rawValue();
                            unresolvedEnumCounts.merge(key, 1L, Long::sum);
                        }
                    }

                    // Preview (up to 100)
                    if (preview.size() < 100) {
                        ObjectNode desensitized = desensitize(c.canonicalRecord(), profile);
                        preview.add(desensitized);
                        if (hasMaskedFields(c.canonicalRecord(), profile)) {
                            sensitiveFieldsPresent = true;
                        }
                    }

                } else if (outcome instanceof MapperOutcome.Quarantined q) {
                    quarantinedRows++;

                    // Rule-13: count required-total even for quarantined
                    RequiredFieldStats stats = countRequiredFieldsFromRaw(rawLine, profile);
                    requiredTotalCount += stats.totalCount();

                    for (MapperViolation v : q.violations()) {
                        String code = v.code().name();
                        reasonCounts.merge(code, 1L, Long::sum);

                        // If BAD_ENUM, track in observed enums
                        if (v.code() == MapperViolationCode.BAD_ENUM && v.rawValue() != null) {
                            observedEnumPairs.add(v.fieldPath() + "=" + v.rawValue());
                        }
                    }
                }
            }
        }

        // Rule-13: coverage math (denominator 0 => null)
        Double requiredCoverage = requiredTotalCount == 0 ? null
                : (double) requiredSuccessCount / requiredTotalCount;
        Double enumCoverage = observedEnumPairs.isEmpty() ? null
                : (double) resolvedEnumPairs.size() / observedEnumPairs.size();

        String sensitiveNote = sensitiveFieldsPresent
                ? "Some sensitive fields were masked in preview"
                : "No sensitive fields marked in profile or contract; values passed through unchanged";

        return new DryRunReport(
                profile.profileVersion(),
                profile.contentHash(),
                totalRows,
                canonicalRows,
                quarantinedRows,
                requiredCoverage,
                enumCoverage,
                reasonCounts,
                unresolvedEnumCounts,
                validatorAccepted,
                strictContractAccepted,
                itemsPassthroughFlagged,
                preview,
                sensitiveNote
        );
    }

    // ---- Q6: strict contract check ----

    private boolean isStrictContractValid(String rawLine) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawLine);
            if (root == null || !root.isObject()) return false;
            for (String req : new String[]{"event_id", "event_type", "event_time",
                    "ingest_time", "source_system", "schema_version", "trace_id"}) {
                JsonNode v = root.get(req);
                if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
                    return false;
                }
            }
            // payload must be an object
            JsonNode payload = root.get("payload");
            if (payload == null || !payload.isObject()) return false;
            // schema_version must be "1.0"
            String sv = root.path("schema_version").asText();
            if (!"1.0".equals(sv)) return false;
            // event_type must be known
            String et = root.path("event_type").asText();
            if (!EventContract.isKnownType(et)) return false;
            // event_time must parse as ISO offset
            OffsetDateTime.parse(root.path("event_time").asText());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Q4: items passthrough ----

    private boolean isItemsPassthrough(String rawLine, ObjectNode canonical) {
        try {
            String eventType = canonical.has("event_type") ? canonical.get("event_type").asText() : null;
            if (!"order_created".equals(eventType)) {
                return false;
            }
            JsonNode items = canonical.get("items");
            if (items == null && canonical.has("payload")) {
                items = canonical.get("payload").get("items");
            }
            return items != null && items.isTextual();
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Rule-13: coverage counting ----

    private record RequiredFieldStats(long successCount, long totalCount) {}

    private RequiredFieldStats countRequiredFields(ObjectNode canonical, SourceProfile profile) {
        String eventType = canonical.has("event_type") ? canonical.get("event_type").asText() : null;
        Set<String> reqFields = PureMapper.getRequiredFieldsForEventType(eventType);

        // Include event_type and event_time as required envelope fields
        Set<String> allReq = new LinkedHashSet<>(reqFields);
        allReq.add("event_type");
        allReq.add("event_time");

        long total = allReq.size();
        long success = 0;
        JsonNode payloadNode = canonical.get("payload");
        for (String field : allReq) {
            JsonNode v = canonical.get(field);
            if (v == null && payloadNode != null && payloadNode.isObject()) {
                v = payloadNode.get(field);
            }
            if (v != null && !v.isNull() && !(v.isTextual() && v.asText().isBlank())) {
                success++;
            }
        }
        return new RequiredFieldStats(success, total);
    }

    private RequiredFieldStats countRequiredFieldsFromRaw(String rawLine, SourceProfile profile) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawLine);
            String rawType = null;
            if (root != null && root.isObject()) {
                JsonNode et = root.get("event_type");
                if (et == null) et = root.get("msg_kind");
                if (et != null && !et.isNull()) rawType = et.asText();
            }
            String canonicalType = rawType != null ? profile.eventTypeMapping().get(rawType) : null;
            Set<String> req = PureMapper.getRequiredFieldsForEventType(canonicalType != null ? canonicalType : rawType);
            return new RequiredFieldStats(0, req.size() + 2); // +2 for event_type, event_time
        } catch (Exception e) {
            return new RequiredFieldStats(0, 2);
        }
    }

    private void trackCanonicalEnums(String rawLine, ObjectNode canonical,
                                     List<MapperViolation> warnings, SourceProfile profile,
                                     Set<String> observedPairs, Set<String> resolvedPairs) {
        JsonNode payloadNode = canonical.get("payload");
        for (Map.Entry<String, Map<String, String>> entry : profile.enumSemantics().entrySet()) {
            String enumCategory = entry.getKey();
            String targetField = mapEnumKeyToField(enumCategory);

            JsonNode valNode = canonical.get(targetField);
            if (valNode == null && payloadNode != null && payloadNode.isObject()) {
                valNode = payloadNode.get(targetField);
            }
            if (valNode != null && (valNode.isTextual() || valNode.isNumber())) {
                String val = valNode.asText();
                String pair = targetField + "=" + val;
                observedPairs.add(pair);

                boolean isUnresolved = warnings.stream().anyMatch(
                        w -> w.code() == MapperViolationCode.ENUM_UNRESOLVED
                                && targetField.equals(w.fieldPath()));
                if (!isUnresolved) {
                    resolvedPairs.add(pair);
                }
            }
        }
    }

    private String mapEnumKeyToField(String enumKey) {
        return switch (enumKey) {
            case "behavior" -> "behavior_type";
            case "channel" -> "channel";
            case "orderStatus", "productStatus" -> "status";
            case "changeType" -> "change_type";
            case "ageGroup" -> "age_group";
            case "cityLevel" -> "city_level";
            case "memberLevel" -> "member_level";
            default -> enumKey;
        };
    }

    // ---- Desensitization (Rule-10) ----

    private ObjectNode desensitize(ObjectNode canonical, SourceProfile profile) {
        ObjectNode copy = canonical.deepCopy();
        Set<String> sensitive = profile.sensitiveFields();
        if (sensitive == null || sensitive.isEmpty()) {
            return copy;
        }
        JsonNode payloadNode = copy.get("payload");
        for (String field : sensitive) {
            if (copy.has(field) && !copy.get(field).isNull()) {
                copy.put(field, "***MASKED***");
            }
            if (payloadNode != null && payloadNode.isObject() && payloadNode.has(field) && !payloadNode.get(field).isNull()) {
                ((ObjectNode) payloadNode).put(field, "***MASKED***");
            }
        }
        return copy;
    }

    private boolean hasMaskedFields(ObjectNode canonical, SourceProfile profile) {
        Set<String> sensitive = profile.sensitiveFields();
        if (sensitive == null || sensitive.isEmpty()) {
            return false;
        }
        JsonNode payloadNode = canonical.get("payload");
        for (String field : sensitive) {
            if (canonical.has(field) && !canonical.get(field).isNull()) {
                return true;
            }
            if (payloadNode != null && payloadNode.isObject() && payloadNode.has(field) && !payloadNode.get(field).isNull()) {
                return true;
            }
        }
        return false;
    }
}
