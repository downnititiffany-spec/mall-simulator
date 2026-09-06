package com.graduation.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件 Outbox（§5.2.4）：与业务事务同库同事务提交；event_id 全局唯一；
 * payload 保存完整事件信封 JSON（含 schema_version），published_at 为空表示未发布。
 */
@Data
@TableName("event_outbox")
public class EventOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private String aggregateType;

    private String aggregateId;

    private String eventType;

    private String traceId;

    private String payload;

    private LocalDateTime createdAt;

    private LocalDateTime publishedAt;
}