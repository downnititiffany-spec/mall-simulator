package com.graduation.generator.engine;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 一次生成任务的输入（由运行服务从 {@code generation_plan} 的冻结版本 + {@code generation_run} 组装）。
 *
 * <p>刻意只带"计划里的不可变字段"：同一 {@code (scenario, seed, startTime, endTime, eventCount, dirtyProfile)}
 * 必须得到同一产物（V2.1 §4.2 可复现）。{@code runId} 只用于清单标识，不参与随机数派生——
 * 否则两次运行 runId 不同就会"不可复现"。</p>
 *
 * @param runId         运行标识（写入清单 run_id）
 * @param scenario      场景码（{@code ScenarioRegistry} 的键）
 * @param seed          根随机种子（线程子 seed 由它派生，§4.3）
 * @param startTime     模拟窗口起点（业务时间）
 * @param endTime       模拟窗口终点（业务时间）
 * @param eventCount    目标事件总量
 * @param ratePerSecond 负载上限（0 表示不限）
 * @param dirtyProfile  脏数据档位（{@code none} 表示不注入；仅文件模式有效，§3.3 B）
 * @param eventFilter   只允许进入产物的<b>事件类型</b>白名单（{@code null} = 全部允许）。
 *                      MALL_API 模式用它把"商城公开接口根本不存在的动作"（改价、库存预留/释放/入库）
 *                      从计划里<b>摘掉</b>——见 {@link #withEventFilter} 的说明。
 */
public record GenerationRequest(String runId, String scenario, long seed, Instant startTime, Instant endTime,
                                long eventCount, int ratePerSecond, String dirtyProfile,
                                Predicate<String> eventFilter) {

    public static final String DIRTY_NONE = "none";

    /** 不设事件类型白名单（全部 12 类都可产出）——文件模式与既有调用方的入口 */
    public GenerationRequest(String runId, String scenario, long seed, Instant startTime, Instant endTime,
                             long eventCount, int ratePerSecond, String dirtyProfile) {
        this(runId, scenario, seed, startTime, endTime, eventCount, ratePerSecond, dirtyProfile, null);
    }

    public GenerationRequest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 必填（清单 run_id）");
        }
        if (scenario == null || scenario.isBlank()) {
            throw new IllegalArgumentException("scenario 必填（ScenarioRegistry 的键）");
        }
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("模拟窗口 startTime/endTime 必填（§4.2）");
        }
        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("模拟窗口起点必须早于终点：start=%s end=%s".formatted(startTime, endTime));
        }
        if (eventCount < 1) {
            throw new IllegalArgumentException("eventCount 必须 ≥ 1（§4.2 event_count）");
        }
        if (ratePerSecond < 0) {
            throw new IllegalArgumentException("ratePerSecond 不得为负（0 表示不限）");
        }
        if (dirtyProfile == null || dirtyProfile.isBlank()) {
            throw new IllegalArgumentException("dirtyProfile 必填，不注入请显式写 " + DIRTY_NONE);
        }
    }

    public boolean injectsDirtySamples() {
        return !DIRTY_NONE.equalsIgnoreCase(dirtyProfile);
    }

    /** 该事件类型是否允许进入产物（未设白名单时一律允许，向后兼容既有调用方） */
    public boolean acceptsEventType(String eventType) {
        return eventFilter == null || eventFilter.test(eventType);
    }

    /**
     * 派生一个"只产出给定事件类型"的请求（其余字段原样保留，因此 <b>seed 与时间窗不变、场景分布不变</b>）。
     *
     * <p><b>为什么要在计划层摘，而不是在落点层丢</b>：文件引擎的 {@code event_count} 是"事件预算"，
     * 计数器 {@code emitted} 在<b>每次成功写入</b>时自增，而事件时间、抽样权重、订单序号都派生自 {@code emitted}。
     * 如果等事件生成出来再在 {@code EventSink} 处丢弃，{@code emitted} 的推进节奏就变了，后面的整条事件流随之漂移——
     * "两个模式读同一份计划"当场失效。把白名单交给引擎，被摘掉的事件类型<b>从不进入计划</b>，
     * 剩余事件流与文件模式逐字节同构（对账见 {@code MallApiGenerationEngineTest}）。</p>
     */
    public GenerationRequest withEventFilter(Set<String> allowedEventTypes) {
        Set<String> frozen = new LinkedHashSet<>(allowedEventTypes);
        return new GenerationRequest(runId, scenario, seed, startTime, endTime, eventCount, ratePerSecond,
                dirtyProfile, frozen::contains);
    }

    /** 可复现键（不含 runId；用于对账两次运行是否同参） */
    public String reproducibilityKey() {
        return "%s|%d|%s|%s|%d|%s".formatted(scenario, seed, startTime, endTime, eventCount, dirtyProfile);
    }
}
