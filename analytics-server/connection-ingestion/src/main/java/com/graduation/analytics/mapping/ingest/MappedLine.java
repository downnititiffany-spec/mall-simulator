package com.graduation.analytics.mapping.ingest;

import java.util.Objects;

/**
 * 逐行映射的结果（S2-02）：要么给出**标准化后的 canonical 行文本**，要么给出**隔离原因**，二者互斥。
 *
 * <p>为什么不让 {@code canonicalText} 可空 + 另带 {@code quarantined} 布尔：两个字段各自可空时，
 * 「两个都非空」「两个都空」都是可构造的非法状态。compact 构造器把这条不变式钉死在类型里，
 * 采集器只需要看 {@link #accepted()} 一个判据。</p>
 *
 * <p>隔离时**不产出半成品**：映射器只要有一条违例就不给 canonical（S2-01A 冻结语义），
 * 因此这里也没有"部分转换结果"可言；原文由采集器原样写入隔离目录。</p>
 */
public record MappedLine(String canonicalText, String quarantineReason) {

    public MappedLine {
        if ((canonicalText == null) == (quarantineReason == null)) {
            throw new IllegalArgumentException(
                    "canonicalText 与 quarantineReason 必须恰好有一个非空（互斥且完备）");
        }
        if (quarantineReason != null && quarantineReason.isBlank()) {
            throw new IllegalArgumentException("quarantineReason 不得为空白");
        }
    }

    public static MappedLine accepted(String canonicalText) {
        return new MappedLine(Objects.requireNonNull(canonicalText, "canonicalText"), null);
    }

    public static MappedLine quarantined(String reason) {
        return new MappedLine(null, Objects.requireNonNull(reason, "reason"));
    }

    public boolean accepted() {
        return quarantineReason == null;
    }
}
