package com.graduation.analytics.source.dto;

/**
 * 源登记修改请求（P1-03）。
 *
 * <p>语义是**部分更新**：{@code null} 字段保持原值（不提供"清空"语义——这些列在 V16 里都是 NOT NULL，
 * 清空没有意义，静默写 null 反而会破坏行）。</p>
 *
 * <ul>
 *   <li>{@code sourceCode} 只要与现值不同（非 null 且不相等）→ {@code SOURCE_CODE_IMMUTABLE}；</li>
 *   <li>{@code status} **不受理**：与现值相同则忽略，不同则 {@code PARAM_INVALID}。
 *       状态只能经 {@code /activate} 与 {@code /pause} 变更——那样才能保证
 *       「ACTIVE 的源一定通过过画像校验」这条全局不变量，也才不会绕过当前源绑定。</li>
 * </ul>
 */
public record SourceRegistryUpdateReq(
        String sourceCode,
        String displayName,
        String ingestMode,
        String profilePath,
        String timezone,
        String currency,
        String profileVersion,
        String status) {
}
