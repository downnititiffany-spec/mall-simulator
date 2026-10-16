package com.graduation.mall.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.mall.domain.entity.EventOutbox;
import com.graduation.mall.domain.mapper.EventOutboxMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Batch S 实测回归：scheduler 与手工 /outbox/publish 不能同时把同一 event_id 写进 rolling JSONL。
 *
 * <p>2026-09-18 的真实 producer attempt 产生 1182 行，但只有 1011 个唯一 event_id；
 * 重复的 171 行来自两个 publishOnce 调用在「写文件 → markPublished」窗口重叠。
 * DB 的 published_at 条件更新发生在文件写入之后，单靠它无法阻止双写。</p>
 */
class OutboxPublisherConcurrencyTest {

    @Test
    void concurrentPublishersCannotWriteSamePendingEventTwice() throws Exception {
        EventOutboxMapper mapper = mock(EventOutboxMapper.class);
        RollingJsonEventWriter writer = mock(RollingJsonEventWriter.class);
        EventOutboxService service = mock(EventOutboxService.class);
        EventClock clock = mock(EventClock.class);
        ObjectMapper objectMapper = new ObjectMapper();

        EventEnvelope envelope = new EventEnvelope(
                "evt-concurrent-1",
                EventContract.USER_REGISTERED,
                "2026-09-18T19:30:00+08:00",
                "2026-09-18T19:30:00+08:00",
                EventContract.SOURCE_SYSTEM,
                EventContract.SCHEMA_VERSION,
                "trace-concurrent-1",
                Map.of("user_id", "U1", "age_group", "25-34", "member_level", "gold"));
        EventOutbox row = new EventOutbox();
        row.setEventId(envelope.eventId());
        row.setPayload(envelope.toJson(objectMapper));

        AtomicBoolean published = new AtomicBoolean(false);
        when(mapper.selectList(any())).thenAnswer(invocation ->
                published.get() ? List.of() : List.of(row));
        when(service.markPublished(row.getEventId())).thenAnswer(invocation ->
                published.compareAndSet(false, true) ? 1 : 0);

        AtomicInteger writes = new AtomicInteger();
        CountDownLatch firstWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (writes.incrementAndGet() == 1) {
                firstWriteEntered.countDown();
                assertTrue(releaseFirstWrite.await(2, TimeUnit.SECONDS),
                        "测试未及时释放第一个 writer.write");
            }
            return null;
        }).when(writer).write(any(EventEnvelope.class));

        OutboxPublisher publisher = new OutboxPublisher(mapper, writer, service, objectMapper, clock);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OutboxPublisher.PublishResult> first = pool.submit(publisher::publishOnce);
            assertTrue(firstWriteEntered.await(1, TimeUnit.SECONDS), "第一个发布线程未进入 writer.write");

            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<OutboxPublisher.PublishResult> second = pool.submit(() -> {
                secondStarted.countDown();
                return publisher.publishOnce();
            });
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS), "第二个发布线程未启动");

            // 第一线程仍停在 write→markPublished 窗口时，第二线程不得完成 publishOnce。
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));

            releaseFirstWrite.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);

            assertEquals(1, writes.get(), "同一 pending event 在并发发布入口下只能写 rolling log 一次");
        } finally {
            releaseFirstWrite.countDown();
            pool.shutdownNow();
        }
    }
}
