package com.graduation.mall.support;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用事件行生成：快速构造合法/非法 envelope JSON（与事件契约字段一致）。
 */
public final class EventLines {

    private static final AtomicLong SEQ = new AtomicLong(1);

    private EventLines() {
    }

    /** 合法 user_registered 一行 */
    public static String userRegistered(long userId) {
        OffsetDateTime t = OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.ofHours(8));
        return "{\"event_id\":\"evt-" + SEQ.getAndIncrement() + "\","
                + "\"event_type\":\"user_registered\","
                + "\"event_time\":\"" + t + "\","
                + "\"ingest_time\":\"" + t.plusSeconds(1) + "\","
                + "\"source_system\":\"mock-mall\","
                + "\"schema_version\":\"1.0\","
                + "\"trace_id\":\"t-" + userId + "\","
                + "\"payload\":{\"user_id\":\"" + userId + "\",\"age_group\":\"25-34\","
                + "\"city_level\":\"tier1\",\"member_level\":\"gold\","
                + "\"register_time\":\"" + t + "\"}}";
    }

    /** 非法行为枚举一行 */
    public static String badBehaviorType() {
        OffsetDateTime t = OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.ofHours(8));
        return "{\"event_id\":\"evt-bad-1\",\"event_type\":\"behavior\","
                + "\"event_time\":\"" + t + "\",\"ingest_time\":\"" + t + "\","
                + "\"source_system\":\"mock-mall\",\"schema_version\":\"1.0\","
                + "\"trace_id\":\"t\","
                + "\"payload\":{\"user_id\":\"1\",\"product_id\":\"1\",\"session_id\":\"s\","
                + "\"behavior_type\":\"fly\",\"channel\":\"app\"}}";
    }

    /** 未知版本一行 */
    public static String unknownSchemaVersion() {
        OffsetDateTime t = OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.ofHours(8));
        return "{\"event_id\":\"evt-ver-9\",\"event_type\":\"order_paid\","
                + "\"event_time\":\"" + t + "\",\"ingest_time\":\"" + t + "\","
                + "\"source_system\":\"mock-mall\",\"schema_version\":\"9.9\","
                + "\"trace_id\":\"t\",\"payload\":{\"order_id\":\"1\"}}";
    }

    /** 缺 user_id 一行 */
    public static String missingUserId() {
        OffsetDateTime t = OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.ofHours(8));
        return "{\"event_id\":\"evt-miss-1\",\"event_type\":\"behavior\","
                + "\"event_time\":\"" + t + "\",\"ingest_time\":\"" + t + "\","
                + "\"source_system\":\"mock-mall\",\"schema_version\":\"1.0\","
                + "\"trace_id\":\"t\",\"payload\":{\"product_id\":\"1\"}}";
    }

    /** 金额格式非法一行 */
    public static String badAmount() {
        OffsetDateTime t = OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.ofHours(8));
        return "{\"event_id\":\"evt-amt-1\",\"event_type\":\"order_created\","
                + "\"event_time\":\"" + t + "\",\"ingest_time\":\"" + t + "\","
                + "\"source_system\":\"mock-mall\",\"schema_version\":\"1.0\","
                + "\"trace_id\":\"t\","
                + "\"payload\":{\"order_id\":\"1\",\"user_id\":\"1\",\"items\":[],"
                + "\"total_amount\":\"abc\",\"status\":\"CREATED\",\"created_at\":\"" + t + "\"}}";
    }
}