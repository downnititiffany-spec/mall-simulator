package com.graduation.analytics.source.dto;

/**
 * 源登记创建请求（P1-03）。
 *
 * <p>{@code status} 只允许 {@code DRAFT}（缺省）或 {@code PAUSED}；传 {@code ACTIVE} 会被拒——
 * ACTIVE 必须经 {@code POST /api/v1/sources/{id}/activate}（唯一带画像校验的入口），
 * 否则会出现"没校验过画像却已激活"的源（D-035 裁决 7）。</p>
 *
 * <p>本模块（connection-ingestion）没有 jakarta.validation 依赖，故不做注解校验：
 * 全部字段在 {@code SourceRegistryServiceImpl} 里显式校验并抛 {@code PARAM_INVALID}，
 * 错误信息带字段名，便于真机验收定位。</p>
 */
public record SourceRegistryCreateReq(
        String sourceCode,
        String displayName,
        String ingestMode,
        String profilePath,
        String timezone,
        String currency,
        String status,
        String profileVersion) {
}
