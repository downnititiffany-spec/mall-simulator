package com.graduation.analytics.ai;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA-04：一次问数的**查询时间窗口**（请求口径 vs 实际生效区间 vs 结果覆盖天数）。
 *
 * <p>2026-09-22 独立复评 QA-04 的根因是「同一个时间范围有三个所有者」：页面写「近30天」、
 * 模型/规则模板生成的 SQL 只扫 7 天、解释与证据又照抄页面标签，于是同一次回答里三处互相矛盾。
 * 这里把三者收敛成**一个结构化对象**，并且只由数据派生文案：</p>
 *
 * <ul>
 *   <li>{@code from/to/days}：来自 {@code SqlSafetyValidator} 对 WHERE 中 dt 字面量的解析结果
 *       ——SQL 是唯一事实来源，不读请求标签；</li>
 *   <li>{@code coveredDays}：结果行里真实出现过的 dt 去重计数（紧凑 {@code yyyyMMdd} 与 ISO
 *       两种写法归一后计数），没有 dt 列时为 null（未知≠100%）；</li>
 *   <li>{@code notice}：请求口径与生效区间不一致、或结果覆盖不足时给页面的一句话提示，
 *       提示里出现的天数全部来自本对象，不允许调用方自己拼；</li>
 *   <li>{@code requested/requestedDays}：仅用于**对照**展示请求口径，绝不作为生效口径。</li>
 * </ul>
 *
 * <p>解释口径文本（{@link #effectiveRangeText()}）与证据里的 {@code timeRange} 同源，
 * 因此不会再出现「解释说近30天、SQL 只查了 7 天」的口径打架。</p>
 */
public record QueryWindow(String requested, Integer requestedDays, String from, String to, int days,
                          String referenceBusinessDate, Integer coveredDays, String notice) {

    /** 请求标签里的天数：「近30天」「7天」「最近90天」都能取到；取不到就是 null（不猜） */
    private static final Pattern DAYS_IN_LABEL = Pattern.compile("(\\d{1,3})\\s*天");
    private static final Set<String> SINGLE_DAY_LABELS = Set.of("今天", "今日", "本日", "当天");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter BASIC_ISO = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern COMPACT = Pattern.compile("\\d{8}");

    /**
     * 构建窗口。
     *
     * @param requested              请求口径原文（可为 null，仅作对照）
     * @param from                   生效区间下界（来自 SQL 校验器的 dt 解析；未知为 null）
     * @param to                     生效区间上界
     * @param referenceBusinessDate  ACTIVE 快照的业务日
     * @param rows                   本次真实执行返回的行，用于统计覆盖天数
     */
    public static QueryWindow of(String requested, LocalDate from, LocalDate to,
                                 LocalDate referenceBusinessDate, List<Map<String, Object>> rows) {
        String label = blankToNull(requested);
        Integer requestedDays = parseRequestedDays(label);
        int days = (from == null || to == null) ? 0 : (int) (ChronoUnit.DAYS.between(from, to) + 1);
        Integer covered = coveredDays(rows, from, to);
        return new QueryWindow(label, requestedDays,
                from == null ? null : from.format(ISO), to == null ? null : to.format(ISO), days,
                referenceBusinessDate == null ? null : referenceBusinessDate.format(ISO),
                covered, noticeOf(label, requestedDays, from, to, days, covered));
    }

    /**
     * 解释/证据用的口径文本：生效区间 + 覆盖比例；拿不到生效区间时返回 null，
     * 由调用方决定是否回落展示 {@link #requested()}（回落只允许发生在没有生效区间的情况下）。
     */
    public String effectiveRangeText() {
        if (from == null || to == null) {
            return null;
        }
        if (coveredDays == null) {
            return from + " ~ " + to + "（" + days + " 天）";
        }
        return from + " ~ " + to + "（覆盖 " + coveredDays + "/" + days + " 天）";
    }

    private static String noticeOf(String label, Integer requestedDays, LocalDate from, LocalDate to,
                                   int days, Integer covered) {
        List<String> parts = new ArrayList<>();
        if (requestedDays != null && days > 0 && requestedDays != days) {
            parts.add("请求口径为「" + label + "」（约 " + requestedDays + " 天），实际生效区间为 " + days
                    + " 天（" + from + " ~ " + to + "）");
        }
        if (covered != null && covered == 0 && days > 0) {
            parts.add("有效区间 " + days + " 天内结果为空（0 行），不能据此判断趋势");
        } else if (covered != null && days > 1 && covered < days) {
            parts.add("有效区间 " + days + " 天，结果只覆盖 " + covered + " 天（" + (days - covered)
                    + " 天无数据），不能据此判断趋势");
        }
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    /**
     * 结果覆盖天数：只接受严格 yyyy-MM-dd / yyyyMMdd 日期，并且只计入生效闭区间；去重后计数。
     * 行数非空但没有任何 dt 列 ⇒ null（未知，不臆造）；有 dt 列但值均非法/越界则为 0。
     */
    private static Integer coveredDays(List<Map<String, Object>> rows, LocalDate from, LocalDate to) {
        if (rows == null) {
            return null;
        }
        Set<LocalDate> days = new LinkedHashSet<>();
        boolean sawDtColumn = false;
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Object raw = row.get("dt");
            if (raw == null) {
                continue;
            }
            String text = raw.toString().trim();
            if (text.isEmpty()) {
                continue;
            }
            sawDtColumn = true;
            LocalDate date = parseResultDate(text);
            if (date == null) {
                continue;
            }
            if (from != null && date.isBefore(from)) {
                continue;
            }
            if (to != null && date.isAfter(to)) {
                continue;
            }
            days.add(date);
        }
        if (!sawDtColumn) {
            return rows.isEmpty() ? 0 : null;
        }
        return days.size();
    }

    /** 只接受精确 ISO yyyy-MM-dd 或紧凑 yyyyMMdd；解析失败与其他格式均不算覆盖日。 */
    private static LocalDate parseResultDate(String text) {
        try {
            if (COMPACT.matcher(text).matches()) {
                return LocalDate.parse(text, BASIC_ISO);
            }
            if (ISO_DATE.matcher(text).matches()) {
                return LocalDate.parse(text, ISO);
            }
        } catch (RuntimeException ignored) {
            // 字段来自查询结果；非法日历值不应被算作覆盖日。
        }
        return null;
    }

    private static Integer parseRequestedDays(String label) {
        if (label == null) {
            return null;
        }
        Matcher matcher = DAYS_IN_LABEL.matcher(label);
        if (matcher.find()) {
            return Integer.valueOf(matcher.group(1));
        }
        return SINGLE_DAY_LABELS.stream().anyMatch(label::contains) ? 1 : null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
