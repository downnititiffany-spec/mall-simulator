package com.graduation.analytics.decision;

import com.graduation.analytics.common.MallBizException;

/**
 * 操作者身份 + 请求上下文（R8-3 契约 §3.1/§3.3）：写审计与落库字段一律来自这里，
 * **不接受**请求头用户名，也没有 demo 回退。
 *
 * <p>由 platform-app 控制层从 {@code CurrentUserHolder} 与 HttpServletRequest 组装；
 * 构造时即校验 userId 非空 —— 缺少可信身份直接失败（UNAUTHORIZED），
 * 保证「创建者/审批人/评价人不可伪造」是服务层强约束而不是调用方约定。</p>
 */
public record AuditActor(String traceId, String userId, String role, String ip) {

    /** 缺少可信身份（与控制层 401 的错误码一致） */
    public static final String UNAUTHORIZED = "UNAUTHORIZED";

    public AuditActor {
        if (userId == null || userId.isBlank()) {
            throw new MallBizException(UNAUTHORIZED, "缺少可信当前用户，拒绝执行（§21.2 禁止回退请求头/demo 用户）");
        }
    }

    public static AuditActor of(String traceId, String userId, String role, String ip) {
        return new AuditActor(traceId, userId, role, ip);
    }
}
