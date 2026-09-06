package com.graduation.mall.outbox;

/**
 * 事件 ID 生成器 —— 可替换实现（测试注入固定序列保证确定性/可复现）。
 */
public interface EventIdGenerator {

    String nextId();
}