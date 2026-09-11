package com.graduation.generator.engine;

import java.time.Instant;

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
 */
public record GenerationRequest(String runId, String scenario, long seed, Instant startTime, Instant endTime,
                                long eventCount, int ratePerSecond, String dirtyProfile) {

    public static final String DIRTY_NONE = "none";

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

    /** 可复现键（不含 runId；用于对账两次运行是否同参） */
    public String reproducibilityKey() {
        return "%s|%d|%s|%s|%d|%s".formatted(scenario, seed, startTime, endTime, eventCount, dirtyProfile);
    }
}
