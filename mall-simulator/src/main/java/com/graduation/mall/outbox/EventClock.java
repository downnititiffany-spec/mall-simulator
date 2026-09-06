package com.graduation.mall.outbox;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 业务时间源：统一 Asia/Shanghai（§21.2），可注入固定 Clock 保证测试/生成可复现。
 */
public class EventClock {

    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Clock clock;

    public EventClock(Clock clock) {
        this.clock = clock;
    }

    public static EventClock systemDefault() {
        return new EventClock(Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    public Clock underlying() {
        return clock;
    }

    /** 带时区时间（用于 event_time/ingest_time 的 ISO 字符串） */
    public OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /** ISO-8601 带 +08:00 偏移的字符串 */
    public String nowIso() {
        return ISO_OFFSET.format(now());
    }

    public LocalDateTime nowLdt() {
        return LocalDateTime.now(clock);
    }
}