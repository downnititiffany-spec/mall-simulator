package com.graduation.mall.outbox;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ISO-8601 时间格式化小工具（统一 Asia/Shanghai 偏移输出）。
 */
final class DateTimeSupport {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private DateTimeSupport() {
    }

    static String toIso(OffsetDateTime t) {
        return ISO.format(t);
    }
}