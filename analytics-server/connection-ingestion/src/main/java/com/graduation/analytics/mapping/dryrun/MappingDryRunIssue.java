package com.graduation.analytics.mapping.dryrun;

import com.graduation.analytics.mapping.MappingIssue;
import com.graduation.analytics.mapping.MappingReason;

import java.util.Objects;

/**
 * dry-run 报告里的一条映射问题：在 {@link MappingIssue}（原因码 + canonical 路径 + detail）之上
 * 增加**样本行号**。
 *
 * <p>为什么要加行号：{@link MappingIssue} 是单事件视角，没有行身份；而预览工具的使用者要拿着报告
 * 回样本文件改数据，"第几条"是这个场景的第一需求。行号由编排层（dry-run 服务）逐行执行时贴上来，
 * 不需要修改 S2-01A 已冻结的执行器核心。</p>
 *
 * <p>{@code lineNo} 取值约定：<b>&gt;0</b> = 样本文件的**物理行号**（1 起，空行也占号，
 * 便于直接打开文件定位）；<b>0</b> = 不针对任何样本行的问题（候选画像装载失败的画像级问题）。</p>
 */
public record MappingDryRunIssue(int lineNo, MappingReason reason, String path, String detail) {

    public MappingDryRunIssue {
        if (lineNo < 0) {
            throw new IllegalArgumentException("lineNo 不允许为负（0 = 画像级问题）: " + lineNo);
        }
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(path, "path");
    }

    public static MappingDryRunIssue of(int lineNo, MappingIssue issue) {
        return new MappingDryRunIssue(lineNo, issue.reason(), issue.path(), issue.detail());
    }

    @Override
    public String toString() {
        return (lineNo == 0 ? "profile" : "line " + lineNo) + ": " + reason + "@" + path
                + (detail == null ? "" : "(" + detail + ")");
    }
}
