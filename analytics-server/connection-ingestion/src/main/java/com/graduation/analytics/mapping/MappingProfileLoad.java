package com.graduation.analytics.mapping;

import java.util.List;

/**
 * 画像装载结果：{@code profile == null} 即装载失败（fail-closed），{@code issues} 给出全部原因。
 *
 * <p>只要有一条 issue 就不返回 profile——不允许「带病装载 + 运行期再补救」。</p>
 */
public record MappingProfileLoad(MappingProfile profile, List<MappingIssue> issues) {

    public MappingProfileLoad {
        issues = List.copyOf(issues);
    }

    public boolean ok() {
        return profile != null;
    }
}
