package com.graduation.generator.engine;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 一次运行的<b>逐类事件账本</b>：谁把一条事件真的写进了规范流，谁就在这里记一笔。
 *
 * <p><b>为什么要有这个对象（D10）</b>：逐类统计的口径唯一权威是"事件真的进了规范流"，但这个账本过去长在
 * 引擎内部的局部对象里（{@code FileModeGenerationEngine.Run.stats}、
 * {@code MallApiGenerationEngine.ForwardingSink.stats}）。而 MALL_API 引擎在"有商城拒绝"时是
 * <b>事件已经写进规范流之后</b>才抛异常的，运行服务在失败路径上根本拿不到 {@code EngineOutcome}，
 * 于是 {@code generation_event_stat} 一条不写、报告里 {@code event_stats: []}——失败样本缺逐类分布，
 * 而这份分布其实一直存在（规范流里逐条写着）。账本改由<b>运行服务持有</b>、引擎往里记账之后，
 * 成功路径与失败路径读到的是同一本账：失败分支不再是"另一条统计路径"，而是<b>同一行代码</b>。</p>
 *
 * <p>与 {@link OperationJournal}（D12）同构：那个对象也是"运行服务持有、引擎往里写"，
 * 所以运行失败时流水一条不少。这里只是把同一个已被真机验证过的形状用在统计上，
 * 不是新增第二套记账逻辑。</p>
 *
 * <p><b>口径归属</b>：这里只负责"累加"，不负责"哪一类事件的金额取哪个字段"——那条规则留在引擎里
 * （{@code MallApiGenerationEngine.ForwardingSink.amountOf} 与 {@code FileModeGenerationEngine} 的写入点），
 * 避免金额口径出现第三个所有者。本对象只保证"记了就一定进过流"。</p>
 *
 * <p>线程安全：引擎是单线程顺序写，本对象只在同一线程内使用；{@link #snapshot()} 返回不可变副本，
 * 调用方拿到之后不会再被后续记账改动。</p>
 */
public final class EventStatsRecorder {

    private final Map<String, EventTypeStat> stats = new TreeMap<>();

    /**
     * 记一条"已经进入规范流"的事件。
     *
     * @param eventType 事件类型（契约 {@code event_type}）
     * @param amount    该事件的金额；无金额含义的类型传 {@code null}（按 0 累加）
     */
    public void record(String eventType, BigDecimal amount) {
        Objects.requireNonNull(eventType, "eventType");
        stats.merge(eventType, new EventTypeStat(1, amount),
                (left, right) -> left.plus(right.count(), right.amount()));
    }

    /** 当前已记账的逐类分布（不可变快照，按事件类型名排序） */
    public Map<String, EventTypeStat> snapshot() {
        return Map.copyOf(stats);
    }

    /** 已记账的事件总条数（失败路径上它必须等于 {@code success_count}：两者是同一本账） */
    public long totalCount() {
        long total = 0;
        for (EventTypeStat stat : stats.values()) {
            total += stat.count();
        }
        return total;
    }

    /** 是否一条都没记下（用于把"运行在写入任何事件之前就失败"与"统计缺失"区分开） */
    public boolean isEmpty() {
        return stats.isEmpty();
    }
}
