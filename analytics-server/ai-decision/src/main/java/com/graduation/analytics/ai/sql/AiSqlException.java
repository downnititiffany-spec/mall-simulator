package com.graduation.analytics.ai.sql;

/**
 * AI 问数业务异常：携带**稳定错误码**（规则码），供审计行 {@code ai_query_history.errors}
 * 与接口响应直接使用（R8-2 契约 §2.3「errors=&lt;规则码&gt;」）。
 *
 * <p>只在 {@code ai/sql/**} 内使用：校验拒绝、作用域缺失、EXPLAIN 成本超阈值统一走这里，
 * 避免各处自定义异常导致错误码漂移。</p>
 */
public class AiSqlException extends RuntimeException {

    private final String code;

    public AiSqlException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AiSqlException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 规则码 / 错误码（如 SQL_JOIN、NO_ACTIVE_SNAPSHOT、SQL_COST_TOO_HIGH） */
    public String code() {
        return code;
    }
}
