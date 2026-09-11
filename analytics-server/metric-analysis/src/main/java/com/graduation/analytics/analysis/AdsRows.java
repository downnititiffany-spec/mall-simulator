package com.graduation.analytics.analysis;

import com.graduation.analytics.metric.MetricAdsReader;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * ADS 宽表取值工具（分析包内部使用，不对外暴露）。
 *
 * <p>为什么需要它：MetricAdsReader 返回 {@code List<Map<String,Object>>}（ColumnMapRowMapper），
 * BIGINT/DECIMAL/VARCHAR 分别是 Long/BigDecimal/String；两个分析服务都要做同样的类型收敛，
 * 集中一处避免各自写一份"字符串转数字"的散落逻辑。</p>
 *
 * <p>格式差异说明：ADS 的 {@code dt} 实测落库为 {@code yyyyMMdd}（如 20260901），
 * 而契约与页面的日期是 ISO {@code yyyy-MM-dd}。这里只做**展示归一化**（不改变数值口径），
 * 已符合 ISO 的取值原样返回。</p>
 */
final class AdsRows {

    /** ADS dt 的紧凑格式（db/metric V3 的 VARCHAR(16) 列，Spark 写 yyyyMMdd） */
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private AdsRows() {
    }

    static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = asString(value).trim();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    static int asInt(Object value) {
        return (int) asLong(value);
    }

    /** 金额/比率：取不到就是 null（不用 0 冒充"没有值"） */
    static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** dt 归一化为 ISO 日期；无法识别时原样返回（不猜） */
    static String isoDate(String dt) {
        String value = dt == null ? "" : dt.trim();
        if (value.length() == 8 && value.chars().allMatch(Character::isDigit)) {
            try {
                return LocalDate.parse(value, COMPACT_DATE).toString();
            } catch (DateTimeParseException e) {
                return value;
            }
        }
        return value;
    }

    /** 规范顺序下标；不在规范列表里的取值统一排在末尾（保证输出稳定可对账） */
    static int orderIndex(List<String> order, String value) {
        int index = order.indexOf(value);
        return index < 0 ? order.size() : index;
    }

    /**
     * 单日粒度 ADS（漏斗/质量/排行/画像）在一个快照里可能有多天分区，
     * 统一取**最新 dt 分区**，避免把多天行混在一次响应里重复计数。
     */
    static List<Map<String, Object>> latestPartition(MetricAdsReader reader, String table, String snapshotId) {
        List<Map<String, Object>> rows = reader.selectBySnapshot(table, snapshotId, null);
        if (rows.isEmpty()) {
            return rows;
        }
        String latest = rows.stream()
                .map(row -> isoDate(asString(row.get("dt"))))
                .max(Comparator.naturalOrder())
                .orElse("");
        return rows.stream()
                .filter(row -> isoDate(asString(row.get("dt"))).equals(latest))
                .toList();
    }
}
