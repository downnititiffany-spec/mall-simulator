package com.graduation.analytics.mapper;

import java.util.List;

/**
 * 字段违规（附带 detail 以区分 MISSING/NULL/BLANK，Rule-5）。
 */
public record MapperViolation(
        MapperViolationCode code,
        String fieldPath,
        String detail,
        String rawValue
) {
    /**
     * 仅含 code 和 fieldPath 的便捷工厂。
     */
    public static MapperViolation of(MapperViolationCode code, String fieldPath) {
        return new MapperViolation(code, fieldPath, null, null);
    }

    public static MapperViolation of(MapperViolationCode code, String fieldPath, String detail) {
        return new MapperViolation(code, fieldPath, detail, null);
    }

    public static MapperViolation of(MapperViolationCode code, String fieldPath, String detail, String rawValue) {
        return new MapperViolation(code, fieldPath, detail, rawValue);
    }

    /**
     * 构建 EMPTY_FIELD 违规（Rule-5 区分 MISSING/NULL/BLANK）。
     */
    public static MapperViolation emptyField(String fieldPath, String emptyKind) {
        return new MapperViolation(MapperViolationCode.EMPTY_FIELD, fieldPath, emptyKind, null);
    }
}
