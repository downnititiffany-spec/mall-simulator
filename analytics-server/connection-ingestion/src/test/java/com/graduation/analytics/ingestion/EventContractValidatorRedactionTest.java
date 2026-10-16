package com.graduation.analytics.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventContractValidatorRedactionTest {

    private final EventContractValidator validator = new EventContractValidator(new ObjectMapper());

    @Test
    void malformedJsonUsesStableReasonWithoutParserDiagnostic() {
        String rawLine = "{\"customer_note\":\"UNTRUSTED_SECRET_MARKER\"";

        EventContractValidator.Violation violation = validator.check(rawLine, 7);

        assertThat(violation).isNotNull();
        assertThat(violation.lineNo()).isEqualTo(7);
        assertThat(violation.reason()).isEqualTo("JSON 解析失败");
        assertThat(violation.reason()).doesNotContain("UNTRUSTED_SECRET_MARKER");
    }

    @Test
    void unknownEventTypeDoesNotEchoSuppliedValue() {
        EventContractValidator.Violation violation = validator.check(event(
                "UNTRUSTED_SECRET_MARKER", "1.0",
                "\"user_id\":\"u1\",\"age_group\":\"18-25\",\"member_level\":\"silver\""), 1);

        assertThat(violation.reason()).isEqualTo("未知事件类型");
        assertThat(violation.reason()).doesNotContain("UNTRUSTED_SECRET_MARKER");
    }

    @Test
    void unsupportedSchemaVersionDoesNotEchoSuppliedValue() {
        EventContractValidator.Violation violation = validator.check(event(
                "user_registered", "UNTRUSTED_SECRET_MARKER",
                "\"user_id\":\"u1\",\"age_group\":\"18-25\",\"member_level\":\"silver\""), 1);

        assertThat(violation.reason()).isEqualTo("未支持 schema_version");
        assertThat(violation.reason()).doesNotContain("UNTRUSTED_SECRET_MARKER");
    }

    @Test
    void invalidBehaviorAndAmountDiagnosticsIdentifyFieldWithoutEchoingValue() {
        EventContractValidator.Violation behavior = validator.check(event(
                "behavior", "1.0", "\"user_id\":\"u1\",\"product_id\":\"p1\","
                        + "\"session_id\":\"s1\",\"behavior_type\":\"UNTRUSTED_SECRET_MARKER\""), 1);
        EventContractValidator.Violation amount = validator.check(event(
                "product_created", "1.0", "\"product_id\":\"p1\",\"product_name\":\"p\","
                        + "\"category_id\":\"c1\",\"price\":\"UNTRUSTED_SECRET_MARKER\""), 1);

        assertThat(behavior.reason()).isEqualTo("非法 behavior_type");
        assertThat(amount.reason()).isEqualTo("金额格式违规: price");
        assertThat(behavior.reason()).doesNotContain("UNTRUSTED_SECRET_MARKER");
        assertThat(amount.reason()).doesNotContain("UNTRUSTED_SECRET_MARKER");
    }

    private static String event(String eventType, String schemaVersion, String payloadFields) {
        return "{\"event_id\":\"evt-1\",\"event_type\":\"" + eventType
                + "\",\"event_time\":\"2026-10-09T10:00:00+08:00\","
                + "\"ingest_time\":\"2026-10-09T10:00:00+08:00\",\"source_system\":\"test\","
                + "\"schema_version\":\"" + schemaVersion + "\",\"trace_id\":\"trace-1\","
                + "\"payload\":{" + payloadFields + "}}";
    }
}
