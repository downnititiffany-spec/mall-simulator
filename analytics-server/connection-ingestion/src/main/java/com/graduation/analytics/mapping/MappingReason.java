package com.graduation.analytics.mapping;

/**
 * 映射违例/告警原因码 —— 设计 §7.3 规则 6 的**全集（14 个）**，本类不新增、不删减、不改名。
 *
 * <p>14 个 reason 已定义；S2-01A/A.1 的纯映射阶段实现其中 12 个，另外 2 个
 * （{@link #DUPLICATE_EVENT}、{@link #FUTURE_TIME}）由 S2-02 负责：前者需要批次内已见 event_id 状态，
 * 后者需要注入时钟与声明的时间容差，都不是「纯转换」。不得据此宣称规则 6 全部落地。</p>
 *
 * <p>{@link #ENUM_UNRESOLVED} 是唯一的**告警**码（规则 12 的第三态：观察到但未裁定 ⇒ 保留 null + warning），
 * 其余码一律导致事件隔离。</p>
 */
public enum MappingReason {

    /** 规则 5：必填缺失（MISSING/NULL/BLANK）。 */
    EMPTY_FIELD,
    /** 规则 6：批次内重复事件（本轮未实现：需批次状态）。 */
    DUPLICATE_EVENT,
    /** 规则 12：未登记枚举值 / 与契约枚举不符 / 常量字段不符。 */
    BAD_ENUM,
    /** 规则 7：金额非法（非数字、FEN 非整数、负值）。 */
    BAD_AMOUNT,
    /** 规则 6：事件时间超出允许的未来窗口（本轮未实现：需注入时钟与容差声明）。 */
    FUTURE_TIME,
    /** 规则 4：所有声明的时间格式都不匹配。 */
    BAD_TIME_FORMAT,
    /** 规则 6：源声明的 schema_version 不受支持。 */
    UNSUPPORTED_SCHEMA_VERSION,
    /** 规则 1/2/3/4/7/14：画像本身非法（含运行期才暴露的「有金额目标却缺 amountPolicy」）。 */
    PROFILE_INVALID,
    /** 规则 6/14：画像声明的契约版本不受支持。 */
    PROFILE_VERSION,
    /** 规则 6：原始事件不是合法 JSON。 */
    JSON_PARSE_ERROR,
    /** 规则 6/12：事件类型未知或未裁定。 */
    UNKNOWN_EVENT_TYPE,
    /** 规则 6：JSON 类型与契约声明不符。 */
    TYPE_MISMATCH,
    /** 规则 12：枚举已声明为 null（未裁定）——告警，不隔离。 */
    ENUM_UNRESOLVED,
    /** 规则 4：本地时间在 IANA 时区下跳空/重叠，无法判定唯一瞬时。 */
    TIME_AMBIGUOUS_LOCAL;

    /** {@code true} 表示该码只产生告警，不隔离事件。 */
    public boolean warningOnly() {
        return this == ENUM_UNRESOLVED;
    }
}
