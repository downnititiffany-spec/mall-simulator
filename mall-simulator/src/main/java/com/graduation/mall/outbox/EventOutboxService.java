package com.graduation.mall.outbox;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.mall.domain.entity.EventOutbox;
import com.graduation.mall.domain.mapper.EventOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Outbox 写入服务（§5.2.4）：与业务事务同库同事务（由调用方 @Transactional 保证）。
 * ingest_time 在写入时生成，业务时间 event_time 由调用方传入。
 */
@Service
@RequiredArgsConstructor
public class EventOutboxService {

    private final EventOutboxMapper outboxMapper;
    private final EventIdGenerator eventIdGenerator;
    private final EventClock eventClock;
    private final ObjectMapper objectMapper;

    /**
     * 在事务内追加一条未发布事件。
     *
     * @return event_id
     */
    public String append(TraceContext trace, String aggregateType, String aggregateId,
                         String eventType, OffsetDateTime eventTime, Map<String, Object> payload) {
        OffsetDateTime ingest = eventClock.now();
        EventEnvelope envelope = new EventEnvelope(
                eventIdGenerator.nextId(),
                eventType,
                DateTimeSupport.toIso(eventTime),
                DateTimeSupport.toIso(ingest),
                EventContract.SOURCE_SYSTEM,
                EventContract.SCHEMA_VERSION,
                trace.traceId(),
                payload);

        EventOutbox row = new EventOutbox();
        row.setEventId(envelope.eventId());
        row.setAggregateType(aggregateType);
        row.setAggregateId(aggregateId);
        row.setEventType(eventType);
        row.setTraceId(trace.traceId());
        row.setPayload(envelope.toJson(objectMapper));
        row.setCreatedAt(ingest.toLocalDateTime());
        outboxMapper.insert(row);
        return envelope.eventId();
    }

    /**
     * 标记已发布（幂等：仅当 published_at 为空时生效）。
     *
     * @return 更新行数（0=已被其他发布者处理）
     */
    public int markPublished(String eventId) {
        return outboxMapper.update(null, new LambdaUpdateWrapper<EventOutbox>()
                .set(EventOutbox::getPublishedAt, eventClock.nowLdt())
                .eq(EventOutbox::getEventId, eventId)
                .isNull(EventOutbox::getPublishedAt));
    }
}