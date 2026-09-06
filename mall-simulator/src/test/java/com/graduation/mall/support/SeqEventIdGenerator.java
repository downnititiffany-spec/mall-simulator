package com.graduation.mall.support;

import com.graduation.mall.outbox.EventIdGenerator;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试专用：固定序列 event_id（evt-00000001, evt-00000002, ...），
 * 使 Outbox 事务性测试与发布测试可精确预判/断言 event_id（确定性、可复现）。
 */
public class SeqEventIdGenerator implements EventIdGenerator {

    private final AtomicLong n = new AtomicLong(1);

    @Override
    public String nextId() {
        return "evt-" + String.format("%08d", n.getAndIncrement());
    }

    /** 窥视下一个将返回的 event_id（不消耗序号）——用于预置冲突行精确预测 */
    public String peek() {
        return "evt-" + String.format("%08d", n.get());
    }

    /** 重置序号（@BeforeEach 调用，保证每个测试方法从 evt-00000001 开始，跨方法不串位） */
    public void reset() {
        n.set(1);
    }
}