package com.graduation.mall.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.mall.support.EventLines;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约校验器单元测试（采集层第一道闸门）。
 */
class EventContractValidatorTest {

    private final EventContractValidator validator = new EventContractValidator(new ObjectMapper());

    @Test
    @DisplayName("合法事件通过")
    void validPasses() {
        assertNull(validator.check(EventLines.userRegistered(1L), 1));
        assertNull(validator.check(EventLines.userRegistered(2L), 2));
    }

    @Test
    @DisplayName("坏 JSON 拒绝")
    void brokenJsonRejected() {
        EventContractValidator.Violation v = validator.check("{not-json", 1);
        assertNotNull(v);
        assertTrue(v.reason().contains("JSON 解析失败"));
    }

    @Test
    @DisplayName("非法行为枚举拒绝")
    void badBehaviorRejected() {
        EventContractValidator.Violation v = validator.check(EventLines.badBehaviorType(), 1);
        assertNotNull(v);
        assertTrue(v.reason().contains("behavior_type"));
    }

    @Test
    @DisplayName("未知版本隔离")
    void unknownSchemaQuarantined() {
        EventContractValidator.Violation v = validator.check(EventLines.unknownSchemaVersion(), 1);
        assertNotNull(v);
        assertTrue(v.reason().contains("schema_version"));
    }

    @Test
    @DisplayName("缺必要字段拒绝")
    void missingFieldRejected() {
        EventContractValidator.Violation v = validator.check(EventLines.missingUserId(), 1);
        assertNotNull(v);
        assertTrue(v.reason().contains("user_id") || v.reason().contains("payload"));
    }

    @Test
    @DisplayName("金额格式违规拒绝")
    void badAmountRejected() {
        EventContractValidator.Violation v = validator.check(EventLines.badAmount(), 1);
        assertNotNull(v);
        assertTrue(v.reason().contains("金额格式"));
    }
}