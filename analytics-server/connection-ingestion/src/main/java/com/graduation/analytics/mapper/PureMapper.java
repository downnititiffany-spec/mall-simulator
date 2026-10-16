package com.graduation.analytics.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure Mapper (Deliverable 2, Rule-2 through Rule-14).
 *
 * <p>Function: (raw JSON record, frozen SourceProfile) → MapperOutcome.</p>
 * <p>Zero persistence, zero I/O beyond its inputs (Rule-10).</p>
 * <p>Thread-safe: stateless.</p>
 */
public class PureMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Standard amount fields that may be in FEN or YUAN units (Rule-7)
    private static final Set<String> KNOWN_AMOUNT_FIELDS = Set.of(
            "price", "cost", "unit_price", "discount", "amount", "total_amount",
            "line_amount", "paid_amount"
    );

    /**
     * Map a raw JSON line using the given frozen profile.
     *
     * @param rawJson  raw JSON string (one record)
     * @param profile  frozen, validated source profile
     * @return Canonical or Quarantined outcome
     */
    public MapperOutcome map(String rawJson, SourceProfile profile) {
        // Rule-6: profile invalid -> quarantine before anything else
        if (!profile.isValid()) {
            return new MapperOutcome.Quarantined(
                    profile.validationErrors(),
                    rawJson
            );
        }

        // Rule-6: JSON parse error
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(rawJson);
        } catch (JsonProcessingException e) {
            return new MapperOutcome.Quarantined(
                    List.of(MapperViolation.of(MapperViolationCode.JSON_PARSE_ERROR,
                            "$", "JSON parse error: " + e.getOriginalMessage())),
                    rawJson
            );
        }
        if (root == null || !root.isObject()) {
            return new MapperOutcome.Quarantined(
                    List.of(MapperViolation.of(MapperViolationCode.JSON_PARSE_ERROR, "$", "not a JSON object")),
                    rawJson
            );
        }

        List<MapperViolation> violations = new ArrayList<>();
        List<MapperViolation> warnings = new ArrayList<>();

        // Resolve source event type -> canonical event type (Rule-2)
        String rawEventType = findRawEventType(root, profile);
        String canonicalEventType = resolveEventType(rawEventType, profile, violations);

        // Get field mapping for this event type group (or global)
        Map<String, String> fieldMapping = profile.fieldMappingForGroup(
                canonicalEventType != null ? canonicalEventType : "_global_"
        );

        // Rule-2 map-time conflict check (Ruling R-2A-1):
        // If a single input record contains two or more source fields that map to the same target field,
        // quarantine THAT record with a PROFILE_INVALID violation naming the target and competing source fields.
        Map<String, List<String>> targetToPresentSources = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fieldMapping.entrySet()) {
            String sourcePath = entry.getKey();
            String targetField = entry.getValue();
            if (targetField == null || "@keep".equals(targetField)) {
                continue;
            }
            JsonNode node = resolvePath(root, sourcePath);
            if (node != null && !node.isMissingNode()) {
                targetToPresentSources.computeIfAbsent(targetField, k -> new ArrayList<>()).add(sourcePath);
            }
        }
        boolean hasRule2Conflict = false;
        for (Map.Entry<String, List<String>> entry : targetToPresentSources.entrySet()) {
            if (entry.getValue().size() > 1) {
                hasRule2Conflict = true;
                violations.add(MapperViolation.of(
                        MapperViolationCode.PROFILE_INVALID,
                        entry.getKey(),
                        "Rule-2 conflict: multiple source fields " + entry.getValue()
                                + " map to same target [" + entry.getKey() + "]"
                ));
            }
        }
        if (hasRule2Conflict) {
            return new MapperOutcome.Quarantined(violations, rawJson);
        }

        // Build canonical output node and extension node
        ObjectNode canonical = OBJECT_MAPPER.createObjectNode();
        ObjectNode keepExtensions = OBJECT_MAPPER.createObjectNode();

        // Rule-4: Time field handling
        String tpField = profile.timePolicyField() != null ? profile.timePolicyField() : "event_time";
        JsonNode timeNode = resolvePath(root, tpField);
        if (timeNode == null) {
            // Missing time field
            violations.add(MapperViolation.emptyField(tpField, "MISSING"));
        } else {
            String originalTimeText = timeNode.isNull() ? null : timeNode.asText();
            TimeParseResult tpr = parseTime(timeNode, profile, violations);
            if (tpr != null) {
                canonical.put("event_time_original", originalTimeText);
                canonical.put("event_time", tpr.normalizedIso());
            }
        }

        // Apply field mappings (sourcePath -> targetPath)
        for (Map.Entry<String, String> entry : fieldMapping.entrySet()) {
            String sourcePath = entry.getKey();
            String targetField = entry.getValue();

            if (targetField == null) {
                continue;
            }

            JsonNode value = resolvePath(root, sourcePath);

            // Handle @keep extension fields (Rule-3)
            if ("@keep".equals(targetField)) {
                if (value != null && !value.isNull()) {
                    String leafName = sourcePath.contains(".")
                            ? sourcePath.substring(sourcePath.lastIndexOf('.') + 1)
                            : sourcePath;
                    keepExtensions.set(leafName, value);
                }
                continue;
            }

            // Skip event_time as it's handled by timePolicy above
            if ("event_time".equals(targetField) || sourcePath.equals(tpField)) {
                continue;
            }

            // Skip event_type as it's resolved via eventTypeMapping
            if ("event_type".equals(targetField)) {
                continue;
            }

            // If value is null or blank in raw
            if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
                // If required, will be flagged by checkRequiredFields as MISSING, NULL, or BLANK
                continue;
            }

            // Rule-9: items array (order_created.items)
            if ("items".equals(targetField)) {
                if (value.isArray()) {
                    ArrayNode mappedItems = processItemsArray(value, profile, violations);
                    if (mappedItems != null) {
                        canonical.set(targetField, mappedItems);
                    }
                } else if (value.isTextual()) {
                    // String representation passed through (Q4 flagged in report)
                    canonical.put(targetField, value.asText());
                } else {
                    violations.add(MapperViolation.of(MapperViolationCode.TYPE_MISMATCH,
                            targetField, "items must be an array or string representation"));
                }
                continue;
            }

            // Rule-7: Amount fields
            String amountUnit = getAmountUnit(sourcePath, targetField, profile);
            if (amountUnit != null) {
                BigDecimal amount = parseAmount(value, targetField, amountUnit, violations);
                if (amount != null) {
                    canonical.put(targetField, amount.toPlainString());
                }
                continue;
            }

            // Rule-12: Enum fields
            String enumCategory = getEnumFieldName(targetField, canonicalEventType, profile);
            if (enumCategory != null && (value.isTextual() || value.isNumber())) {
                String sourceVal = value.asText();
                SourceProfile.EnumLookupResult lookup = profile.lookupEnum(enumCategory, sourceVal);
                if (lookup instanceof SourceProfile.EnumLookupResult.Resolved r) {
                    canonical.put(targetField, r.canonicalValue());
                } else if (lookup instanceof SourceProfile.EnumLookupResult.Unresolved u) {
                    // ENUM_UNRESOLVED: keep value + warning
                    canonical.put(targetField, sourceVal);
                    warnings.add(MapperViolation.of(
                            MapperViolationCode.ENUM_UNRESOLVED,
                            targetField,
                            "ENUM_UNRESOLVED: value [" + sourceVal + "] observed but undecided",
                            sourceVal
                    ));
                } else if (lookup instanceof SourceProfile.EnumLookupResult.Bad) {
                    violations.add(MapperViolation.of(
                            MapperViolationCode.BAD_ENUM,
                            targetField,
                            "BAD_ENUM: value [" + sourceVal + "] not in profile enum registry",
                            sourceVal
                    ));
                } else {
                    canonical.set(targetField, value);
                }
                continue;
            }

            // Default mapping
            canonical.set(targetField, value);
        }

        // Unknown fields policy (fields present in root but not in mapping)
        if ("KEEP_IN_PAYLOAD".equals(profile.unknownFieldPolicy())) {
            root.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (!fieldMapping.containsKey(key) && !key.equals(tpField)) {
                    if (!canonical.has(key) && !keepExtensions.has(key)) {
                        canonical.set(key, entry.getValue());
                    }
                }
            });
        }

        // Set canonical event_type if resolved
        if (canonicalEventType != null && !canonical.has("event_type")) {
            canonical.put("event_type", canonicalEventType);
        }

        // Rule-5: Check required fields on canonical output
        checkRequiredFields(canonicalEventType, root, fieldMapping, canonical, violations);

        // Add @keep extensions to canonical output (Rule-3)
        keepExtensions.fields().forEachRemaining(e -> canonical.set(e.getKey(), e.getValue()));

        if (!violations.isEmpty()) {
            return new MapperOutcome.Quarantined(violations, rawJson);
        }

        return new MapperOutcome.Canonical(canonical, rawJson, warnings);
    }

    // ---- Event type resolution ----

    private String findRawEventType(JsonNode root, SourceProfile profile) {
        Map<String, String> globalMapping = profile.fieldMappingForGroup("_global_");
        for (Map.Entry<String, String> e : globalMapping.entrySet()) {
            if ("event_type".equals(e.getValue())) {
                JsonNode v = resolvePath(root, e.getKey());
                if (v != null && !v.isNull()) {
                    return v.asText();
                }
            }
        }
        JsonNode v = root.get("event_type");
        return v == null || v.isNull() ? null : v.asText();
    }

    private String resolveEventType(String rawEventType, SourceProfile profile, List<MapperViolation> violations) {
        if (rawEventType == null) {
            violations.add(MapperViolation.emptyField("event_type", "MISSING"));
            return null;
        }
        if (rawEventType.isBlank()) {
            violations.add(MapperViolation.emptyField("event_type", "BLANK"));
            return null;
        }
        Map<String, String> etm = profile.eventTypeMapping();
        if (!etm.containsKey(rawEventType)) {
            if ("QUARANTINE".equals(profile.unknownEventTypePolicy())) {
                violations.add(MapperViolation.of(MapperViolationCode.UNKNOWN_EVENT_TYPE,
                        "event_type", "Unknown event type: " + rawEventType, rawEventType));
                return null;
            }
            return rawEventType;
        }
        String canonical = etm.get(rawEventType);
        if (canonical == null) {
            // null in eventTypeMapping = unmapped/unknown
            violations.add(MapperViolation.of(MapperViolationCode.UNKNOWN_EVENT_TYPE,
                    "event_type", "Event type [" + rawEventType + "] maps to null (unmapped)", rawEventType));
            return null;
        }
        return canonical;
    }

    // ---- Time parsing (Rule-4) ----

    private record TimeParseResult(String normalizedIso) {}

    private TimeParseResult parseTime(JsonNode value, SourceProfile profile, List<MapperViolation> violations) {
        String field = profile.timePolicyField() != null ? profile.timePolicyField() : "event_time";
        if (value == null || value.isNull()) {
            violations.add(MapperViolation.emptyField(field, "NULL"));
            return null;
        }
        String raw = value.asText();
        if (raw == null || raw.isBlank()) {
            violations.add(MapperViolation.emptyField(field, "BLANK"));
            return null;
        }

        List<String> formats = profile.timePolicyFormats();
        if (formats == null || formats.isEmpty()) {
            violations.add(MapperViolation.of(MapperViolationCode.BAD_TIME_FORMAT,
                    field, "No time formats declared in profile", raw));
            return null;
        }

        // Rule-4: epoch unit must be declared (undeclared numeric = violation)
        boolean isNumeric = raw.matches("^-?\\d+$");
        boolean epochFormatDeclared = formats.contains("EPOCH_MILLIS") || formats.contains("EPOCH_SECONDS");
        if (isNumeric && !epochFormatDeclared) {
            violations.add(MapperViolation.of(MapperViolationCode.BAD_TIME_FORMAT,
                    field, "Epoch unit must be declared in timePolicy formats to parse numeric timestamp: " + raw, raw));
            return null;
        }

        ZoneId defaultZone = ZoneId.of(profile.timePolicyTimezone());

        // Rule-4: ordered strict time formats, first success wins
        for (String fmt : formats) {
            TimeParseResult result = tryParseFormat(raw, fmt, field, defaultZone, violations);
            if (result != null) {
                return result;
            }
        }

        // If no format succeeded and no violation was recorded yet for this field
        boolean alreadyReported = violations.stream().anyMatch(v -> field.equals(v.fieldPath()));
        if (!alreadyReported) {
            violations.add(MapperViolation.of(MapperViolationCode.BAD_TIME_FORMAT,
                    field, "Could not parse time [" + raw + "] with any declared format: " + formats, raw));
        }
        return null;
    }

    private TimeParseResult tryParseFormat(String raw, String format,
                                           String fieldPath, ZoneId defaultZone,
                                           List<MapperViolation> violations) {
        try {
            switch (format) {
                case "EPOCH_MILLIS" -> {
                    long millis = Long.parseLong(raw);
                    Instant instant = Instant.ofEpochMilli(millis);
                    if (instant.isAfter(Instant.now().plusSeconds(60))) {
                        violations.add(MapperViolation.of(MapperViolationCode.FUTURE_TIME,
                                fieldPath, "Epoch millis time is in the future: " + raw, raw));
                        return null;
                    }
                    return new TimeParseResult(
                            OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
                                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                }
                case "EPOCH_SECONDS" -> {
                    long secs = Long.parseLong(raw);
                    Instant instant = Instant.ofEpochSecond(secs);
                    if (instant.isAfter(Instant.now().plusSeconds(60))) {
                        violations.add(MapperViolation.of(MapperViolationCode.FUTURE_TIME,
                                fieldPath, "Epoch seconds time is in the future: " + raw, raw));
                        return null;
                    }
                    return new TimeParseResult(
                            OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
                                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                }
                case "ISO_OFFSET_DATE_TIME" -> {
                    OffsetDateTime odt = OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    if (odt.toInstant().isAfter(Instant.now().plusSeconds(60))) {
                        violations.add(MapperViolation.of(MapperViolationCode.FUTURE_TIME,
                                fieldPath, "Event time is in the future: " + raw, raw));
                        return null;
                    }
                    return new TimeParseResult(odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                }
                case "ISO_LOCAL_DATE_TIME" -> {
                    LocalDateTime ldt = LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    return resolveLocalDateTimeWithDstCheck(ldt, defaultZone, fieldPath, raw, violations);
                }
                default -> {
                    DateTimeFormatter dtf = DateTimeFormatter.ofPattern(format);
                    // Try parsing as OffsetDateTime first
                    try {
                        OffsetDateTime odt = OffsetDateTime.parse(raw, dtf);
                        if (odt.toInstant().isAfter(Instant.now().plusSeconds(60))) {
                            violations.add(MapperViolation.of(MapperViolationCode.FUTURE_TIME,
                                    fieldPath, "Event time is in the future: " + raw, raw));
                            return null;
                        }
                        return new TimeParseResult(odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    } catch (DateTimeParseException ignored) {
                    }

                    // Try parsing as ZonedDateTime
                    try {
                        ZonedDateTime zdt = ZonedDateTime.parse(raw, dtf);
                        if (zdt.toInstant().isAfter(Instant.now().plusSeconds(60))) {
                            violations.add(MapperViolation.of(MapperViolationCode.FUTURE_TIME,
                                    fieldPath, "Event time is in the future: " + raw, raw));
                            return null;
                        }
                        return new TimeParseResult(zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    } catch (DateTimeParseException ignored) {
                    }

                    // Try parsing as LocalDateTime and apply DST validation
                    try {
                        LocalDateTime ldt = LocalDateTime.parse(raw, dtf);
                        return resolveLocalDateTimeWithDstCheck(ldt, defaultZone, fieldPath, raw, violations);
                    } catch (DateTimeParseException ignored) {
                    }
                }
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            return null;
        }
        return null;
    }

    /**
     * Rule-4: DST gap/overlap -> TIME_AMBIGUOUS_LOCAL.
     */
    private TimeParseResult resolveLocalDateTimeWithDstCheck(LocalDateTime ldt, ZoneId zone,
                                                             String fieldPath, String raw,
                                                             List<MapperViolation> violations) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> validOffsets = rules.getValidOffsets(ldt);
        if (validOffsets.isEmpty()) {
            // Gap (spring forward)
            violations.add(MapperViolation.of(MapperViolationCode.TIME_AMBIGUOUS_LOCAL,
                    fieldPath, "DST gap at local time: " + ldt + " in zone " + zone, raw));
            return null;
        }
        if (validOffsets.size() > 1) {
            // Overlap (fall back)
            violations.add(MapperViolation.of(MapperViolationCode.TIME_AMBIGUOUS_LOCAL,
                    fieldPath, "DST overlap at local time: " + ldt + " in zone " + zone + " (valid offsets: " + validOffsets + ")", raw));
            return null;
        }
        OffsetDateTime odt = ldt.atOffset(validOffsets.get(0));
        if (odt.toInstant().isAfter(Instant.now().plusSeconds(60))) {
            violations.add(MapperViolation.of(MapperViolationCode.FUTURE_TIME,
                    fieldPath, "Event time is in the future: " + raw, raw));
            return null;
        }
        return new TimeParseResult(odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    // ---- Amount parsing (Rule-7) ----

    private String getAmountUnit(String sourcePath, String targetField, SourceProfile profile) {
        Map<String, String> unitMap = profile.amountUnitByField();
        if (unitMap.containsKey(targetField)) {
            return unitMap.get(targetField);
        }
        if (unitMap.containsKey(sourcePath)) {
            return unitMap.get(sourcePath);
        }
        String leaf = sourcePath.contains(".") ? sourcePath.substring(sourcePath.lastIndexOf('.') + 1) : sourcePath;
        if (leaf.toLowerCase().endsWith("_fen") || leaf.toLowerCase().endsWith("fen")) {
            return SourceProfile.AMOUNT_UNIT_FEN;
        }
        if (KNOWN_AMOUNT_FIELDS.contains(targetField)) {
            return SourceProfile.AMOUNT_UNIT_YUAN;
        }
        return null;
    }

    private BigDecimal parseAmount(JsonNode value, String fieldPath,
                                   String unit, List<MapperViolation> violations) {
        BigDecimal raw;
        try {
            if (value.isNumber()) {
                raw = value.decimalValue();
            } else if (value.isTextual()) {
                String s = value.asText().trim();
                raw = new BigDecimal(s);
            } else {
                violations.add(MapperViolation.of(MapperViolationCode.TYPE_MISMATCH,
                        fieldPath, "TYPE_MISMATCH: expected number or numeric string"));
                return null;
            }
        } catch (NumberFormatException e) {
            violations.add(MapperViolation.of(MapperViolationCode.BAD_AMOUNT,
                    fieldPath, "BAD_AMOUNT: cannot parse as decimal: " + value.asText(), value.asText()));
            return null;
        }

        // Negative check
        if (raw.compareTo(BigDecimal.ZERO) < 0) {
            violations.add(MapperViolation.of(MapperViolationCode.BAD_AMOUNT,
                    fieldPath, "BAD_AMOUNT: negative amount not allowed: " + raw, raw.toPlainString()));
            return null;
        }

        if (SourceProfile.AMOUNT_UNIT_FEN.equalsIgnoreCase(unit)) {
            // Rule-7: FEN -> YUAN /100 exact; non-integral fen rejected
            if (raw.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
                violations.add(MapperViolation.of(MapperViolationCode.BAD_AMOUNT,
                        fieldPath, "BAD_AMOUNT: FEN value must be integral: " + raw, raw.toPlainString()));
                return null;
            }
            // Exact division by 100 (Rule-7: no double path)
            return raw.divide(new BigDecimal("100"), 2, RoundingMode.UNNECESSARY);
        }

        // YUAN - exact 2 decimal places
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    // ---- Enum detection (Rule-12) ----

    private String getEnumFieldName(String targetField, String canonicalEventType, SourceProfile profile) {
        if (profile.enumSemantics().containsKey(targetField)) {
            return targetField;
        }
        return switch (targetField) {
            case "behavior_type" -> "behavior";
            case "channel" -> "channel";
            case "status" -> {
                if (canonicalEventType != null && canonicalEventType.startsWith("order_")) {
                    yield "orderStatus";
                } else if (canonicalEventType != null && canonicalEventType.startsWith("product_")) {
                    yield "productStatus";
                }
                yield "orderStatus";
            }
            case "change_type" -> "changeType";
            case "age_group" -> "ageGroup";
            case "city_level" -> "cityLevel";
            case "member_level" -> "memberLevel";
            default -> null;
        };
    }

    // ---- Path and items array processing (Rule-9) ----

    /**
     * Rule-9: object paths + fixed indices only; ITEM_MAP preserves order+count;
     * one required item failing quarantines the whole event.
     */
    private ArrayNode processItemsArray(JsonNode itemsNode, SourceProfile profile, List<MapperViolation> violations) {
        if (!itemsNode.isArray()) {
            violations.add(MapperViolation.of(MapperViolationCode.TYPE_MISMATCH, "items", "items must be an array"));
            return null;
        }

        ArrayNode result = OBJECT_MAPPER.createArrayNode();
        for (int i = 0; i < itemsNode.size(); i++) {
            JsonNode item = itemsNode.get(i);
            if (item == null || !item.isObject()) {
                violations.add(MapperViolation.of(MapperViolationCode.TYPE_MISMATCH,
                        "items[" + i + "]", "item must be a JSON object"));
                continue;
            }

            ObjectNode mappedItem = OBJECT_MAPPER.createObjectNode();

            // Product ID (required)
            JsonNode prodNode = item.has("product_id") ? item.get("product_id") : item.get("item_sku");
            if (prodNode == null) {
                violations.add(MapperViolation.emptyField("items[" + i + "].product_id", "MISSING"));
            } else if (prodNode.isNull()) {
                violations.add(MapperViolation.emptyField("items[" + i + "].product_id", "NULL"));
            } else if (prodNode.isTextual() && prodNode.asText().isBlank()) {
                violations.add(MapperViolation.emptyField("items[" + i + "].product_id", "BLANK"));
            } else {
                mappedItem.put("product_id", prodNode.asText());
            }

            // Quantity (required positive integer)
            JsonNode qtyNode = item.has("quantity") ? item.get("quantity") : item.get("qty");
            if (qtyNode == null) {
                violations.add(MapperViolation.emptyField("items[" + i + "].quantity", "MISSING"));
            } else if (qtyNode.isNull()) {
                violations.add(MapperViolation.emptyField("items[" + i + "].quantity", "NULL"));
            } else if (!qtyNode.isNumber() || qtyNode.asLong() <= 0) {
                violations.add(MapperViolation.of(MapperViolationCode.BAD_AMOUNT,
                        "items[" + i + "].quantity", "Quantity must be positive integer: " + qtyNode.asText(), qtyNode.asText()));
            } else {
                mappedItem.put("quantity", qtyNode.asLong());
            }

            // Unit price
            JsonNode unitPriceNode = item.has("unit_price") ? item.get("unit_price") : item.get("unit_price_fen");
            if (unitPriceNode != null && !unitPriceNode.isNull()) {
                boolean isFen = item.has("unit_price_fen");
                BigDecimal up = parseAmount(unitPriceNode, "items[" + i + "].unit_price",
                        isFen ? SourceProfile.AMOUNT_UNIT_FEN : SourceProfile.AMOUNT_UNIT_YUAN, violations);
                if (up != null) {
                    mappedItem.put("unit_price", up.toPlainString());
                }
            }

            // Line amount
            JsonNode amtNode = item.has("amount") ? item.get("amount") : item.get("line_amount_fen");
            if (amtNode != null && !amtNode.isNull()) {
                boolean isFen = item.has("line_amount_fen");
                BigDecimal amt = parseAmount(amtNode, "items[" + i + "].amount",
                        isFen ? SourceProfile.AMOUNT_UNIT_FEN : SourceProfile.AMOUNT_UNIT_YUAN, violations);
                if (amt != null) {
                    mappedItem.put("amount", amt.toPlainString());
                }
            }

            // Discount
            JsonNode discNode = item.has("discount") ? item.get("discount") : item.get("discount_fen");
            if (discNode != null && !discNode.isNull()) {
                boolean isFen = item.has("discount_fen");
                BigDecimal disc = parseAmount(discNode, "items[" + i + "].discount",
                        isFen ? SourceProfile.AMOUNT_UNIT_FEN : SourceProfile.AMOUNT_UNIT_YUAN, violations);
                if (disc != null) {
                    mappedItem.put("discount", disc.toPlainString());
                }
            }

            // Copy any other fields as-is
            item.fields().forEachRemaining(e -> {
                String k = e.getKey();
                if (!mappedItem.has(k) && !k.equals("qty") && !k.equals("item_sku")
                        && !k.equals("unit_price_fen") && !k.equals("line_amount_fen") && !k.equals("discount_fen")) {
                    mappedItem.set(k, e.getValue());
                }
            });

            result.add(mappedItem);
        }
        return result;
    }

    /**
     * Rule-9: resolve dot notation (a.b) and fixed bracket indices (items[0].id).
     * Rejects recursion (..) and wildcards (*).
     */
    public static JsonNode resolvePath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        if (!path.contains(".") && !path.contains("[")) {
            return root.get(path);
        }
        if (path.contains("..") || path.contains("*")) {
            throw new IllegalArgumentException("Wildcards and arbitrary recursion not allowed: " + path);
        }
        String[] parts = path.split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null) return null;
            if (part.contains("[")) {
                int open = part.indexOf('[');
                int close = part.indexOf(']');
                String fieldName = part.substring(0, open);
                int index = Integer.parseInt(part.substring(open + 1, close));
                current = fieldName.isEmpty() ? current : current.get(fieldName);
                if (current == null || !current.isArray() || index < 0 || index >= current.size()) {
                    return null;
                }
                current = current.get(index);
            } else {
                current = current.get(part);
            }
        }
        return current;
    }

    // ---- Required field checks (Rule-5) ----

    private void checkRequiredFields(String canonicalEventType, JsonNode root,
                                     Map<String, String> fieldMapping,
                                     ObjectNode canonical, List<MapperViolation> violations) {
        Set<String> requiredFields = getRequiredFieldsForEventType(canonicalEventType);

        // Map target field -> all source fields that map to it
        Map<String, List<String>> targetToSources = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fieldMapping.entrySet()) {
            if (e.getValue() != null && !"@keep".equals(e.getValue())) {
                targetToSources.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            }
        }

        for (String req : requiredFields) {
            if (canonical.has(req) && !canonical.get(req).isNull()
                    && !(canonical.get(req).isTextual() && canonical.get(req).asText().isBlank())) {
                continue;
            }

            // Field is missing/null/blank in canonical. Find what was in raw record to assign detail.
            List<String> srcFields = targetToSources.getOrDefault(req, List.of(req));
            JsonNode foundNode = null;
            for (String srcField : srcFields) {
                JsonNode n = resolvePath(root, srcField);
                if (n != null) {
                    foundNode = n;
                    break;
                }
            }

            if (foundNode == null) {
                violations.add(MapperViolation.emptyField(req, "MISSING"));
            } else if (foundNode.isNull()) {
                violations.add(MapperViolation.emptyField(req, "NULL"));
            } else if (foundNode.isTextual() && foundNode.asText().isBlank()) {
                violations.add(MapperViolation.emptyField(req, "BLANK"));
            } else {
                violations.add(MapperViolation.emptyField(req, "MISSING"));
            }
        }
    }

    public static Set<String> getRequiredFieldsForEventType(String eventType) {
        if (eventType == null) {
            return Set.of();
        }
        return switch (eventType) {
            case "order_created" -> Set.of("order_id", "user_id", "total_amount", "items");
            case "order_paid" -> Set.of("order_id", "user_id", "payment_id", "amount");
            case "order_cancelled" -> Set.of("order_id", "user_id", "reason");
            case "behavior" -> Set.of("user_id", "product_id", "session_id", "behavior_type");
            case "user_registered" -> Set.of("user_id", "age_group", "member_level");
            case "product_created", "product_updated" -> Set.of("product_id", "product_name", "category_id", "price");
            case "refund_created", "refund_completed" -> Set.of("refund_id", "order_id", "user_id", "amount");
            case "stock_reserved", "stock_released", "stock_changed" -> Set.of("product_id", "quantity");
            default -> Set.of();
        };
    }
}
