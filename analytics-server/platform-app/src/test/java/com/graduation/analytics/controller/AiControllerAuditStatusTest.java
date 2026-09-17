package com.graduation.analytics.controller;

import com.graduation.analytics.ai.TextToSqlService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S3-59：operation_audit_log 必须把 FAILED/REJECTED/ERROR 记失败，不能把失败问数写成 success。 */
class AiControllerAuditStatusTest {

    private static TextToSqlService.QueryResult result(String status) {
        return new TextToSqlService.QueryResult(status, null, List.of(), 0, 1L,
                List.of(), List.of(), null, null, "rule-based");
    }

    @Test
    void failedRejectedError都必须进入失败审计() {
        assertTrue(AiController.queryAuditFailed(result("FAILED")));
        assertTrue(AiController.queryAuditFailed(result("REJECTED")));
        assertTrue(AiController.queryAuditFailed(result("ERROR")));
        assertTrue(AiController.queryAuditFailed(result("failed_timeout")));
    }

    @Test
    void 成功与修复成功不能误记失败() {
        assertFalse(AiController.queryAuditFailed(result("EXECUTED")));
        assertFalse(AiController.queryAuditFailed(result("REPAIRED")));
        assertFalse(AiController.queryAuditFailed(result("GENERATED")));
    }

    @Test
    void 空结果或空状态必须failClosed() {
        assertTrue(AiController.queryAuditFailed(null));
        assertTrue(AiController.queryAuditFailed(result(null)));
    }
}
