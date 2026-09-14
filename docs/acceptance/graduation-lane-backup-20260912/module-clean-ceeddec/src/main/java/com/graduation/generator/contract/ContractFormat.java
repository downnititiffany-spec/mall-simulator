package com.graduation.generator.contract;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 契约格式化与校验（金额、时间、信封常量）——生成器侧**唯一**的格式化出口。
 *
 * <p>为什么要有这个类：契约对金额与时间有硬性形态要求（十进制字符串、ISO-8601 带时区），
 * 一旦用 {@code double} 或本地时区默认格式序列化就会产出"看起来正常但会被判脏"的数据。
 * 把规则收在一处，并用正则常量与契约文件对账（{@code GeneratorContractParityTest}）。</p>
 *
 * <p>来源：{@code contract-specs/schemas/canonical-event.v1.schema.json} 的 {@code $defs/amount} 与
 * {@code $defs/iso8601_time}（契约文件 §4 L168 / §1 L25 原文）。</p>
 */
public final class ContractFormat {

    /** 金额/金额类快照一律十进制字符串，禁止 double 序列化（契约 $defs/amount） */
    public static final Pattern AMOUNT_PATTERN = Pattern.compile("^\\d+(\\.\\d{1,2})?$");

    /** 时间一律 ISO-8601 且显式带时区；业务统一 +08:00（契约 $defs/iso8601_time） */
    public static final Pattern ISO8601_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?([+-]\\d{2}:\\d{2}|Z)$");

    /** 业务时区（契约 $defs/iso8601_time description：业务统一 Asia/Shanghai，+08:00） */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /** 事件契约版本（契约 properties/schema_version 的 const） */
    public static final String SCHEMA_VERSION = "1.0";

    /**
     * 信封 {@code source_system} 取值。
     *
     * <p><b>未冻结项（B-06）</b>：契约当前把它冻结为 const {@code mock-mall}。这意味着文件模式
     * （{@code CANONICAL_EVENT_FILE}）产出的行在信封上仍自称来自 mock-mall，而 §3.3 B 要求该模式"必须显示
     * synthetic=true"——该标记当前落在**制品清单**（{@code ArtifactManifest.synthetic}）而非信封上。
     * 若裁决要求文件模式使用独立 source_system，则契约须把 const 放宽为 enum，本常量是唯一改动点。</p>
     */
    public static final String SOURCE_SYSTEM = "mock-mall";

    /** 契约未规定事件 ID 生成规则；生成器侧统一用 UUID（小写、无连字符以外的分隔） */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(BUSINESS_ZONE);

    private ContractFormat() {
    }

    /** 金额 → 契约字符串（固定 2 位小数，保持精度） */
    public static String amount(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("金额不得为 null（契约 $defs/amount 为必填字符串）");
        }
        String formatted = value.setScale(2, RoundingMode.HALF_UP).toPlainString();
        requireAmount(formatted);
        return formatted;
    }

    /** long 金额（分）→ 契约字符串 */
    public static String amount(long cents) {
        return amount(BigDecimal.valueOf(cents, 2));
    }

    /** 时间 → 契约字符串（带 +08:00 偏移，秒级精度） */
    public static String time(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("时间不得为 null（契约 $defs/iso8601_time 为必填字符串）");
        }
        String formatted = TIME_FORMATTER.format(instant);
        requireTime(formatted);
        return formatted;
    }

    /** 校验金额字符串，不符即抛（不静默接受脏值——脏样本只能由脏数据注入器显式产生） */
    public static String requireAmount(String value) {
        if (value == null || !AMOUNT_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "金额必须匹配 " + AMOUNT_PATTERN.pattern() + "（契约 $defs/amount），实际=" + value);
        }
        return value;
    }

    /** 校验时间字符串，不符即抛 */
    public static String requireTime(String value) {
        if (value == null || !ISO8601_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "时间必须匹配 " + ISO8601_PATTERN.pattern() + "（契约 $defs/iso8601_time），实际=" + value);
        }
        return value;
    }
}
