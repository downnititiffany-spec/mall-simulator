package com.graduation.analytics.landing;

import com.graduation.analytics.common.PlatformBizException;

import java.nio.file.Path;

/**
 * Landing 输入布局（S2-04B，设计 §8.2 规则 1/3/4、§8.4）——**该词汇的唯一所有者**。
 *
 * <p>它回答的唯一问题是"采集端到 Landing 根下的**哪一个子目录**、以什么方式枚举输入"：</p>
 * <ul>
 *   <li>{@link #ROLLING_LOG}（默认）：{@code <landing>/events} 下的滚动日志，一层、只认
 *       {@code *.jsonl}，运行中的文件靠断点续读 + 残行等待（既有 LOCAL 语义，设计 §8.2 L268）；</li>
 *   <li>{@link #FLUME_RAW}：{@code <landing>/raw} 下的 Flume 目标区，**递归**（{@code dt=/hour=} 分区
 *       正是目录层级），只枚举**已完成**文件（见 {@link LandingInputScanner}）。</li>
 * </ul>
 *
 * <p>为什么不是"一个布尔量"或"直接给路径"：布局决定了枚举方式（是否递归、认什么后缀、什么算完成），
 * 只给路径会让这两件事分散到调用方；而只给布尔量则无法扩展第三种布局。
 * 为什么未登记值**拒绝**而不是回落默认：把 {@code flume-raw} 这类拼写错误静默当成滚动日志，
 * 后果是"去 {@code events/} 采样、报成功、0 条"，而运维以为在读 {@code raw/}——事后无从察觉。</p>
 */
public enum LandingLayout {

    /** 滚动日志（默认，V2 起的既有一层布局）：{@code events/} + {@code *.jsonl} + 断点续读。 */
    ROLLING_LOG("events", false),

    /** Flume 目标区：{@code raw/} 下按源级 ingest 时间分区的完成文件（递归）。 */
    FLUME_RAW("raw", true);

    private final String dirName;
    private final boolean recursive;

    /**
     * 承载枚举名的列宽（{@code runtime_profile.landing_layout}，V22）——**列宽的唯一所有者**。
     *
     * <p>为什么由代码侧给出而不是让 DDL 手抄一个数字：宽度必须装得下最长登记名，而登记名集合
     * 属于本枚举；DDL 写别的数字就意味着将来加一个更长的布局名会在**写入时**才炸
     * （数据截断），而不是在评审时被门禁挡下。规则：{@code COLUMN_WIDTH >= 最长登记名}
     * 且留出一个布局名的余量（上界 64：再宽就说明有人想往这列里塞描述）。
     * 迁移门禁（{@code RuntimeProfileLandingLayoutMigrationScriptTest}）直接引用本常量。</p>
     */
    public static final int COLUMN_WIDTH = 32;

    LandingLayout(String dirName, boolean recursive) {
        this.dirName = dirName;
        this.recursive = recursive;
    }

    /** 登记名里最长的一个（列宽门禁与"够不够装"的唯一判据）。 */
    public static int longestNameLength() {
        int longest = 0;
        for (LandingLayout layout : values()) {
            longest = Math.max(longest, layout.name().length());
        }
        return longest;
    }

    /** 输入根＝{@code <landing>/<dirName>}（landing 根本身由 {@code RuntimeProfile.landingUri} 决定）。 */
    public Path inputRoot(Path landingRoot) {
        return landingRoot.resolve(dirName);
    }

    public String dirName() {
        return dirName;
    }

    /** 是否递归枚举（{@code FLUME_RAW} 的 {@code dt=/hour=} 是目录层级，必须递归）。 */
    public boolean recursive() {
        return recursive;
    }

    /**
     * 读取侧：把列值变成布局。空值（null/空白）＝**未配置**，等价于默认滚动日志
     * （V22 之前的存量行没有该列，语义必须与 V2 完全一致）；未登记值抛
     * {@code PARAM_INVALID}，绝不回落默认。
     */
    public static LandingLayout effective(String raw) {
        if (raw == null || raw.isBlank()) {
            return ROLLING_LOG;
        }
        return require(raw);
    }

    /**
     * 写入侧：空值归一为 {@code null}（等价于"没写"，让该列保持空置而不是被写成一个默认值——
     * 空置才说得清"从未配置"，写死默认值会把两者混成一种），已登记值归一为枚举名，未登记值拒绝。
     */
    public static String normalizeForWrite(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return require(raw).name();
    }

    private static LandingLayout require(String raw) {
        String trimmed = raw.trim();
        for (LandingLayout layout : values()) {
            if (layout.name().equals(trimmed)) {
                return layout;
            }
        }
        throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                "landing_layout 未登记: " + raw + "（可选: ROLLING_LOG / FLUME_RAW）");
    }
}
