package com.graduation.analytics.common;

import java.util.UUID;

/**
 * Trace 上下文（§23.4）：一次业务操作一个 trace_id，贯穿 商城→Outbox→日志→批次→ODS。
 */
public record TraceContext(String traceId) {

    public static TraceContext create() {
        return new TraceContext(UUID.randomUUID().toString());
    }
}