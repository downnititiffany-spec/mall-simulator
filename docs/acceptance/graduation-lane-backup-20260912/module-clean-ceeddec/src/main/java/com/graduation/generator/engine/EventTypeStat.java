package com.graduation.generator.engine;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 单个事件类型的运行统计（对应 §4.2 {@code generation_event_stat}：{@code event_count} + {@code amount}）。
 *
 * @param count  该类型事件条数
 * @param amount 该类型金额合计（金额类事件才有值，其余为 0）
 */
public record EventTypeStat(long count, BigDecimal amount) {

    public EventTypeStat {
        if (count < 0) {
            throw new IllegalArgumentException("事件条数不得为负");
        }
        amount = amount == null ? BigDecimal.ZERO : amount;
    }

    public EventTypeStat plus(long addCount, BigDecimal addAmount) {
        return new EventTypeStat(count + addCount,
                amount.add(addAmount == null ? BigDecimal.ZERO : addAmount));
    }

    /** 便于 runs 汇总：按事件类型累加 */
    public static Map<String, EventTypeStat> merge(Map<String, EventTypeStat> target,
                                                   Map<String, EventTypeStat> delta) {
        delta.forEach((type, stat) -> target.merge(type, stat, (left, right) -> left.plus(right.count(), right.amount())));
        return target;
    }
}
