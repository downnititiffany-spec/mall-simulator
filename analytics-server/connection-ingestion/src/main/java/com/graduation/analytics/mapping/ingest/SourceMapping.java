package com.graduation.analytics.mapping.ingest;

import com.graduation.analytics.mapping.MappingProfile;

import java.util.Objects;

/**
 * 一轮采集「本源的映射绑定」（S2-02）。是**值对象**，不含装载逻辑（装载在 {@link SourceMapper}）。
 *
 * <p>为什么用包装而不是让 {@code MappingProfile} 可空：真实采集路径上「不映射」是一个**正常分支**
 * （v1 只读兼容画像：逐行映射会隔离所有金额事件，见 {@link SourceMapper#prepare}），
 * 直接传 null 会让"没传"和"故意不映射"在下游不可区分。{@link #legacy()} 是这条分支的唯一表达。</p>
 *
 * <p>签名不变式：{@link #applied()} 为 false ⟺ {@link #profileVersion()} 与 {@link #profileChecksum()}
 * 均为 null。批次清单据此写 {@code mappingVersion}/{@code mappingProfileHash}
 * ——「是否映射」只有这一个表示，不再另加布尔键。</p>
 */
public record SourceMapping(MappingProfile profile) {

    /** v1 兼容画像的直通行（只读兼容：不映射，按既有 canonical 校验落盘）。 */
    private static final SourceMapping LEGACY = new SourceMapping(null);

    public static SourceMapping legacy() {
        return LEGACY;
    }

    public static SourceMapping of(MappingProfile profile) {
        return new SourceMapping(Objects.requireNonNull(profile, "profile"));
    }

    /** 本轮是否逐行映射（false ⇒ 原始行直通既有 canonical 校验，行为与 S2-01 之前一致）。 */
    public boolean applied() {
        return profile != null;
    }

    /** 生效画像的自述版本（{@code profileVersion}）；不映射时为 null。 */
    public String profileVersion() {
        return profile == null ? null : profile.profileVersion();
    }

    /** 生效画像原文的 sha256（与 dry-run 报告 profileChecksum 同一算法）；不映射时为 null。 */
    public String profileChecksum() {
        return profile == null ? null : profile.profileChecksum();
    }
}
