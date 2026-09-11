package com.graduation.generator.engine;

import com.graduation.generator.core.DirtySample;
import com.graduation.generator.core.GenerationResult;

import java.util.List;
import java.util.Map;

/**
 * 一次生成的完整产出（运行服务据此写 {@code generation_run} 计数、{@code generation_event_stat} 与运行报告）。
 *
 * @param result        场景级汇总（{@link GenerationResult}：用户/商品/行为/订单/退款/金额/样本 ID）
 * @param eventStats    按事件类型的计数与金额（写 {@code generation_event_stat}）
 * @param successCount  成功写入的事件数
 * @param failedCount   失败事件数（§4.3：失败必须记入运行报告，不得吞掉）
 * @param dirtySamples  实际注入的异常样本（仅文件模式 + 非 none 档位；期望隔离数写运行报告）
 * @param notes         运行过程中的可核实说明（如降级、未实现能力），逐条进入运行报告
 */
public record EngineOutcome(GenerationResult result,
                            Map<String, EventTypeStat> eventStats,
                            long successCount,
                            long failedCount,
                            List<DirtySample> dirtySamples,
                            List<String> notes) {

    public EngineOutcome {
        eventStats = Map.copyOf(eventStats);
        dirtySamples = List.copyOf(dirtySamples);
        notes = List.copyOf(notes);
    }

    /** 期望隔离数（§4.3）：文件模式下按注入类型逐条给出，供运行报告与采集侧实际隔离数对账 */
    public Map<String, Long> expectedQuarantineCounts() {
        return dirtySamples.stream().collect(java.util.stream.Collectors.groupingBy(
                DirtySample::type, java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));
    }
}
