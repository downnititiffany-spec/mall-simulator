package com.graduation.analytics.decision;

import com.graduation.analytics.common.MallBizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计操作者（§21.2）：操作者身份不能是匿名/空 —— 没有可信身份就不允许产生审计行。
 */
class AuditActorTest {

    @Test
    @DisplayName("有登录用户时正常构造，字段原样保留")
    void actorKeepsIdentity() {
        AuditActor actor = AuditActor.of("trace-1", "alice", "analyst", "10.0.0.1");
        assertEquals("trace-1", actor.traceId());
        assertEquals("alice", actor.userId());
        assertEquals("analyst", actor.role());
        assertEquals("10.0.0.1", actor.ip());
    }

    @Test
    @DisplayName("user_id 为空/空白 → 抛 UNAUTHORIZED（禁止匿名审计）")
    void blankUserIdRejected() {
        for (String blank : new String[]{null, "", "   "}) {
            MallBizException e = assertThrows(MallBizException.class, () -> new AuditActor("t", blank, "admin", null));
            assertEquals(AuditActor.UNAUTHORIZED, e.getCode());
            assertTrue(e.getMessage().contains("§21.2") || e.getMessage().contains("身份"),
                    "异常信息应说明身份不可缺省: " + e.getMessage());
        }
    }
}
