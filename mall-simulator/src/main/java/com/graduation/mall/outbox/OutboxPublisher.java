package com.graduation.mall.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.mall.domain.entity.EventOutbox;
import com.graduation.mall.domain.mapper.EventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Outbox 发布器（§5.2.4）：分页读取未发布事件 → 写入滚动 JSON 日志 → 标记 published_at。
 * 允许 at-least-once：即使进程中断，未发布记录仍可重发，DWD 按 event_id 去重。
 * 定时器只做触发（§23.3），业务逻辑全部在 publishOnce。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final EventOutboxMapper outboxMapper;
    private final RollingJsonEventWriter writer;
    private final EventOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final EventClock eventClock;

    @Value("${mall.outbox.batch-size:200}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${mall.outbox.poll-seconds:10}000",
            initialDelayString = "${mall.outbox.poll-seconds:10}000")
    public void scheduledPublish() {
        try {
            publishOnce();
        } catch (Exception e) {
            log.warn("scheduled publish failed: {}", e.getMessage());
        }
    }

    /**
     * 发布一批未发布事件。
     *
     * @return 发布结果（成功数 + 失败 event_id 列表）
     */
    public synchronized PublishResult publishOnce() {
        List<EventOutbox> pending = outboxMapper.selectList(new LambdaQueryWrapper<EventOutbox>()
                .isNull(EventOutbox::getPublishedAt)
                .orderByAsc(EventOutbox::getId)
                .last("LIMIT " + Math.max(1, batchSize)));
        if (pending.isEmpty()) {
            return new PublishResult(0, List.of());
        }
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (EventOutbox row : pending) {
            try {
                EventEnvelope envelope = EventEnvelope.fromJson(row.getPayload(), objectMapper);
                writer.write(envelope);
                outboxService.markPublished(row.getEventId());
                ok++;
            } catch (Exception e) {
                failed.add(row.getEventId());
                log.warn("publish failed event_id={}: {}", row.getEventId(), e.getMessage());
            }
        }
        log.info("outbox publish: ok={} failed={}", ok, failed.size());
        return new PublishResult(ok, failed);
    }

    public long pendingCount() {
        return outboxMapper.selectCount(new LambdaQueryWrapper<EventOutbox>()
                .isNull(EventOutbox::getPublishedAt));
    }

    public Optional<Path> latestFile() {
        return writer.latestFile();
    }

    public record PublishResult(int publishedCount, List<String> failedEventIds) {
    }
}