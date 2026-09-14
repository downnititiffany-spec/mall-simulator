package com.graduation.mall.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.mall.domain.entity.EventOutbox;
import com.graduation.mall.domain.mapper.EventOutboxMapper;
import com.graduation.mall.support.MallTestSupport;
import com.graduation.mall.support.SeqEventIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outbox 发布器集成测试（§5.2.4/§5.2.5）：
 * 发布 → 滚动 JSON 日志行数一致；published_at 落库；重复发布零新增；事件可反序列化。
 */
@Transactional
// 分类标记（V25-S02 / K-02）：本类需要真实数据库隔离实例（3307）。
//   * 默认纯测试套件（mvn test）按 pom 的 <excludedGroups>it</excludedGroups> 不选中本类；
//   * 显式集成套件（mvn test -Pisolated-tests）选中本类，缺隔离档案时**硬拒（红）而非 skip**。
@Tag("it")
class OutboxPublisherTest extends MallTestSupport {

    @TestConfiguration
    static class FixedIdConfig {
        @Bean
        @Primary
        EventIdGenerator fixedEventIdGenerator() {
            return new SeqEventIdGenerator();
        }
    }

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private EventOutboxService outboxService;

    @Autowired
    private EventOutboxMapper outboxMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private void appendUserEvents(int count) {
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 1; i <= count; i++) {
            outboxService.append(TraceContext.create(), EventContract.AGG_USER, String.valueOf(i),
                    EventContract.USER_REGISTERED, now,
                    EventPayloadFactory.userRegistered((long) i, "25-34", "tier1", "normal", now));
        }
    }

    @Test
    @DisplayName("发布 N 条事件：日志行数 = N，published_at 已设置，重发零新增")
    void publishWritesEventsOnce() throws IOException {
        appendUserEvents(5);
        assertEquals(5, publisher.pendingCount());

        OutboxPublisher.PublishResult first = publisher.publishOnce();
        assertEquals(5, first.publishedCount());
        assertEquals(0, publisher.pendingCount());

        // 文件按小时滚动：本小时一个 jsonl，行数 = 5
        Path eventsDir = LANDING.get().resolve("events");
        try (Stream<Path> files = Files.list(eventsDir)) {
            List<Path> jsonls = files.filter(f -> f.getFileName().toString().endsWith(".jsonl")).toList();
            assertEquals(1, jsonls.size());
            long lines;
            try (Stream<String> ls = Files.lines(jsonls.get(0), StandardCharsets.UTF_8)) {
                lines = ls.count();
            }
            assertEquals(5, lines);

            // 每行都是合法信封
            try (Stream<String> ls = Files.lines(jsonls.get(0), StandardCharsets.UTF_8)) {
                ls.forEach(json -> {
                    EventEnvelope env = EventEnvelope.fromJson(json, objectMapper);
                    assertNotNull(env.eventId());
                    assertEquals(EventContract.SCHEMA_VERSION, env.schemaVersion());
                    assertEquals(EventContract.SOURCE_SYSTEM, env.sourceSystem());
                    assertTrue(env.payload().containsKey("user_id"));
                });
            }
        }

        // outbox 行已标记 published_at
        List<EventOutbox> rows = outboxMapper.selectList(null);
        assertEquals(5, rows.size());
        rows.forEach(r -> assertNotNull(r.getPublishedAt(), "发布后必须落 published_at"));

        // 再次发布 → 零新增
        OutboxPublisher.PublishResult second = publisher.publishOnce();
        assertEquals(0, second.publishedCount());
    }

    @Test
    @DisplayName("发布失败不吞错：坏 payload 记入 failedEventIds，不影响其余事件")
    void publishSkipsBrokenPayload() {
        appendUserEvents(2);
        // 手工插入一条坏 payload
        EventOutbox broken = new EventOutbox();
        broken.setEventId("broken-001");
        broken.setAggregateType(EventContract.AGG_USER);
        broken.setAggregateId("9");
        broken.setEventType(EventContract.USER_REGISTERED);
        broken.setTraceId("t");
        broken.setPayload("{not-json");
        outboxMapper.insert(broken);

        OutboxPublisher.PublishResult result = publisher.publishOnce();
        assertEquals(2, result.publishedCount());
        assertEquals(List.of("broken-001"), result.failedEventIds());
    }
}
