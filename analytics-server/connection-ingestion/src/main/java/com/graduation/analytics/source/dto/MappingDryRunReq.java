package com.graduation.analytics.source.dto;

import java.util.List;

/**
 * 映射 Dry-Run 请求 DTO（WP-2A-2 / Design V3.0 §7.4）。
 *
 * <p>输入约束：
 * <ul>
 *   <li>{@code sampleRef}：样本文件引用，必填；仅接受平台批准的白名单位置；</li>
 *   <li>{@code profileVersion}：可选，若提供则与源解析出的版本强校验（不匹配报 409）；</li>
 *   <li>{@code profileContentHash} / {@code contentHash}：可选，若提供则与画像内容 SHA-256 校验（不匹配报 409）；</li>
 *   <li>{@code inlineRecords} / {@code records}：不接受内联记录（出现非空则 400）。</li>
 * </ul>
 * </p>
 */
public record MappingDryRunReq(
        String profileVersion,
        String profileContentHash,
        String contentHash,
        String sampleRef,
        List<Object> inlineRecords,
        List<Object> records
) {

    public MappingDryRunReq(String profileVersion, String profileContentHash, String sampleRef) {
        this(profileVersion, profileContentHash, null, sampleRef, null, null);
    }

    public MappingDryRunReq(String sampleRef) {
        this(null, null, null, sampleRef, null, null);
    }

    /** 有效的内容哈希入参（兼容 profileContentHash 与 contentHash 两种键名） */
    public String effectiveContentHash() {
        if (profileContentHash != null && !profileContentHash.isBlank()) {
            return profileContentHash.trim();
        }
        if (contentHash != null && !contentHash.isBlank()) {
            return contentHash.trim();
        }
        return null;
    }

    /** 是否提供了被禁止的内联记录 */
    public boolean hasInlineRecords() {
        return (inlineRecords != null && !inlineRecords.isEmpty())
                || (records != null && !records.isEmpty());
    }
}
