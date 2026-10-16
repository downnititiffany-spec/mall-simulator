package com.graduation.analytics.mapping;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 时间策略（设计 §7.3 规则 4，S2-01A.1 冻结口径）：源字段 + 有序格式列表 + **显式 IANA 时区**。
 *
 * <p>格式令牌有两类，都可出现在 {@code formats} 里且**按声明顺序**尝试：</p>
 * <ul>
 *   <li>符号令牌：{@code ISO_OFFSET_DATE_TIME}（自带偏移）、{@code ISO_LOCAL_DATE_TIME}（本地时间，需时区）、
 *       {@code EPOCH_MILLIS}、{@code EPOCH_SECONDS}（瞬时，需时区渲染）。epoch 必须显式声明，不猜。</li>
 *   <li>任意 {@link DateTimeFormatter} 模式（本地时间，需时区）。</li>
 * </ul>
 *
 * <p><b>时区不再有隐式默认值</b>：只要声明了任何非 {@code ISO_OFFSET_DATE_TIME} 格式
 * （本地时间或 epoch，{@link #requiresZone()} 为 true），画像就必须明文声明 {@code timePolicy.zone}，
 * 装载期缺声明即 {@code PROFILE_INVALID}。契约 {@code $defs.iso8601_time} 里的 Asia/Shanghai
 * 只是**输出值域的口径**，不作为「源侧本地时间属于哪个时区」的推断依据。</p>
 *
 * <p>{@code zoneSource}：{@code PROFILE}（画像明文声明）；{@code null} 表示该画像只声明
 * {@code ISO_OFFSET_DATE_TIME} 这类自带偏移的格式，规范化时保留原文偏移、无需时区。</p>
 */
public record TimePolicySpec(String field, List<Format> formats, String zone, String zoneSource) {

    public static final String ISO_OFFSET_DATE_TIME = "ISO_OFFSET_DATE_TIME";
    public static final String ISO_LOCAL_DATE_TIME = "ISO_LOCAL_DATE_TIME";
    public static final String EPOCH_MILLIS = "EPOCH_MILLIS";
    public static final String EPOCH_SECONDS = "EPOCH_SECONDS";
    public static final String ZONE_FROM_PROFILE = "PROFILE";

    /** 一种时间格式及其类别。 */
    public record Format(String token, Kind kind, DateTimeFormatter formatter) {
        public enum Kind {
            /** 自带偏移的 ISO-8601（instant 精确）。 */
            OFFSET,
            /** 本地时间，必须结合时区判定，存在 DST 跳空/重叠风险。 */
            LOCAL,
            /** epoch 毫秒。 */
            EPOCH_MILLIS,
            /** epoch 秒。 */
            EPOCH_SECONDS
        }
    }

    public TimePolicySpec {
        Objects.requireNonNull(field, "field");
        formats = List.copyOf(formats);
    }

    /** 是否需要画像显式声明时区：除「自带偏移」外的任何格式都需要（本地时间要判定、epoch 要渲染）。 */
    public boolean requiresZone() {
        return formats.stream().anyMatch(f -> f.kind() != Format.Kind.OFFSET);
    }
}
