package com.graduation.mall.outbox;

import java.util.UUID;

/**
 * 默认实现：UUID。
 */
public class UuidEventIdGenerator implements EventIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}