package com.graduation.analytics.mapping;

import java.util.Objects;

/**
 * 一条映射问题：原因码 + canonical 侧路径 + 机器可读 detail。
 *
 * <p>{@code path} 用 canonical 视角的路径（信封字段名如 {@code event_time}，载荷字段如
 * {@code payload.items[1].unit_price}），便于对账时直接定位；{@code detail} 取值不是错误码，
 * 只是同一原因码下的细分（例如 {@code EMPTY_FIELD} 的 MISSING/NULL/BLANK、{@code BAD_AMOUNT} 的
 * FEN_NOT_INTEGRAL/NEGATIVE/NOT_NUMERIC）。</p>
 */
public record MappingIssue(MappingReason reason, String path, String detail) {

    public MappingIssue {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(path, "path");
    }

    public static MappingIssue of(MappingReason reason, String path, String detail) {
        return new MappingIssue(reason, path, detail);
    }

    @Override
    public String toString() {
        return reason + "@" + path + (detail == null ? "" : "(" + detail + ")");
    }
}
