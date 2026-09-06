package com.graduation.mall.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.mall.domain.entity.EventOutbox;
import com.graduation.mall.domain.mapper.EventOutboxMapper;
import com.graduation.mall.outbox.EventEnvelope;
import com.graduation.mall.outbox.EventContract;
import com.graduation.mall.outbox.EventIdGenerator;
import com.graduation.mall.outbox.EventOutboxService;
import com.graduation.mall.outbox.EventPayloadFactory;
import com.graduation.mall.outbox.TraceContext;
import com.graduation.mall.support.MallTestSupport;
import com.graduation.mall.support.SeqEventIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Outbox 写入服务集成测试（§5.2.4）：完整信封落库、event_id 全局唯一。
 * 每个测试方法在事务中执行，测试结束回滚，保证测试库干净。
 */
@Transactional
class EventOutboxServiceTest extends MallTestSupport {

    @TestConfiguration
    static class FixedIdConfig {
        @Bean
        @Primary
        EventIdGenerator fixedEventIdGenerator() {
            return new SeqEventIdGenerator();
        }
    }

    @Autowired
    private EventOutboxService outboxService;

    @Autowired
    private EventOutboxMapper outboxMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventIdGenerator eventIdGenerator;

    @BeforeEach
    void resetSequence() {
        if (eventIdGenerator instanceof SeqEventIdGenerator seq) {
            seq.reset();
        }
    }

    @Test
    @DisplayName("append 写入完整信封：契约字段齐全、published_at 为空、来源与版本正确")
    void appendProducesFullEnvelope() {
        TraceContext trace = TraceContext.create();
        OffsetDateTime now = OffsetDateTime.now();
        String eventId = outboxService.append(trace, EventContract.AGG_USER, "1",
                EventContract.USER_REGISTERED, now,
                EventPayloadFactory.userRegistered(1L, "25-34", "tier1", "gold", now));

        assertEquals("evt-00000001", eventId);

        EventOutbox row = outboxMapper.selectOne(new LambdaQueryWrapper<EventOutbox>()
                .eq(EventOutbox::getEventId, eventId));
        assertNotNull(row);
        assertNull(row.getPublishedAt(), "新事件必须未发布");
        assertEquals(EventContract.USER_REGISTERED, row.getEventType());
        assertEquals(EventContract.AGG_USER, row.getAggregateType());
        assertEquals(trace.traceId(), row.getTraceId());

        // payload 是完整信封 JSON，字段映射与契约一致（snake_case）
        EventEnvelope envelope = EventEnvelope.fromJson(row.getPayload(), objectMapper);
        assertEquals(eventId, envelope.eventId());
        assertEquals(EventContract.SOURCE_SYSTEM, envelope.sourceSystem());
        assertEquals(EventContract.SCHEMA_VERSION, envelope.schemaVersion());
        assertEquals(EventContract.USER_REGISTERED, envelope.eventType());
        assertEquals("25-34", envelope.payload().get("age_group"));
        assertEquals("gold", envelope.payload().get("member_level"));
    }

    @Test
    @DisplayName("相同 event_id 重复追加被唯一键拒绝")
    void duplicateEventIdRejected() {
        // 预置将要生成的相同 event_id（固定序列 → evt-00000001）冲突行
        EventOutbox conflict = new EventOutbox();
        conflict.setEventId("evt-00000001");
        conflict.setAggregateType(EventContract.AGG_USER);
        conflict.setAggregateId("9");
        conflict.setEventType(EventContract.USER_REGISTERED);
        conflict.setTraceId("conflict");
        conflict.setPayload("{}");
        outboxMapper.insert(conflict);

        OffsetDateTime now = OffsetDateTime.now();
        assertThrows(DuplicateKeyException.class, () ->
                outboxService.append(TraceContext.create(), EventContract.AGG_USER, "2",
                        EventContract.USER_REGISTERED, now,
                        EventPayloadFactory.userRegistered(2L, "35-44", "tier2", "silver", now)));
    }
}