package com.graduation.analytics.contracts;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 业务时间源：统一 Asia/Shanghai（§21.2），可注入固定 Clock 保证测试/生成可复现。
 * 模拟时钟（§20.3："不把系统当前时间直接当业务时间"）：生成器把业务事件推进到
 * 模拟时刻（ThreadLocal 栈），生成结束后自动回退真实时钟。
 */
public class EventClock {

    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Clock clock;
    private final ThreadLocal<Deque<OffsetDateTime>> simulated = ThreadLocal.withInitial(ArrayDeque::new);

    public EventClock(Clock clock) {
        this.clock = clock;
    }

    public static EventClock systemDefault() {
        return new EventClock(Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    public Clock underlying() {
        return clock;
    }

    /** 压入一段模拟业务时间（生成器调用业务 Service 前），务必 finally pop */
    public void pushSimulated(OffsetDateTime t) {
        simulated.get().push(t);
    }

    /** 弹出模拟时间；栈空后恢复真实时钟 */
    public void popSimulated() {
        Deque<OffsetDateTime> stack = simulated.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            simulated.remove();
        }
    }

    /** 带时区时间（event_time/ingest_time）：模拟栈非空时返回模拟业务时间 */
    public OffsetDateTime now() {
        Deque<OffsetDateTime> stack = simulated.get();
        if (!stack.isEmpty()) {
            return stack.peek();
        }
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