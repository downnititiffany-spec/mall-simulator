package com.graduation.analytics.mapper;

/**
 * 映射规则违规码（设计文档 §7.3 Rule-6）。
 *
 * <p>别名仅兼容翻译，不能重写历史错误行（Rule-6）。</p>
 */
public enum MapperViolationCode {
    /** 必填字段缺键/null/空白 */
    EMPTY_FIELD,
    /** 同 eventId 重复出现 */
    DUPLICATE_EVENT,
    /** 枚举值在登记表里不存在 */
    BAD_ENUM,
    /** 金额格式/精度/负值违规 */
    BAD_AMOUNT,
    /** 事件时间超过当前时间（未来时间） */
    FUTURE_TIME,
    /** 时间字段格式无法解析 */
    BAD_TIME_FORMAT,
    /** schema_version 不被支持 */
    UNSUPPORTED_SCHEMA_VERSION,
    /** 画像结构无效（规则 2 冲突等） */
    PROFILE_INVALID,
    /** 画像版本不匹配 */
    PROFILE_VERSION,
    /** 原始 JSON 无法解析 */
    JSON_PARSE_ERROR,
    /** 事件类型不在画像的 eventTypeMapping 中 */
    UNKNOWN_EVENT_TYPE,
    /** 字段类型与预期不符 */
    TYPE_MISMATCH,
    /** 枚举值在画像中登记为 null（观察到但语义未决），产生警告而非隔离 */
    ENUM_UNRESOLVED,
    /** 时间在 DST 跳空/重叠区，无法确定精确时刻 */
    TIME_AMBIGUOUS_LOCAL,
}
