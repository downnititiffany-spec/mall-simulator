package com.graduation.generator.engine;

import com.graduation.generator.contract.EventSink;

import java.util.function.BooleanSupplier;

/**
 * 生成引擎（V2.1 §4.3 的算法落点；与落点解耦，因此文件模式与 MALL_API 模式共用同一套场景逻辑）。
 *
 * <p>实现方必须满足：</p>
 * <ol>
 *   <li><b>可复现</b>：同一 {@link GenerationRequest#reproducibilityKey()} 必须产出逐字节相同的 JSONL
 *       （随机源只能来自 {@code request.seed()} 派生的确定性子 seed，禁用 {@code Math.random()} 与
 *       与时钟/线程调度相关的取值，§4.2 + §4.3）。</li>
 *   <li><b>不留后门落库</b>：只通过传入的 {@link EventSink} 产出，不直接写 ODS/DWD/ADS/指标库，
 *       也不得写分析平台 landing（§3.3 B）。</li>
 *   <li><b>可取消</b>：循环中轮询 {@code cancelled}，取消时尽快返回已完成的计数，
 *       由调用方把运行置为 CANCELLED（§4.4 幂等取消）。</li>
 *   <li><b>失败不隐藏</b>：单条事件失败计入 {@code failedCount} 并在 {@code notes} 说明；
 *       不可恢复的错误直接抛出，由运行服务置 FAILED 并写 error_code。</li>
 *   <li><b>逐类账本交给调用方</b>：每写进规范流一条事件，就往调用方传入的 {@link EventStatsRecorder}
 *       记一笔；引擎自己的汇总数只能来自这本账。理由（D10）：引擎"事件已进流之后"抛异常时，
 *       调用方仍要能报出失败样本的逐类分布——账本若留在引擎内部，失败样本就只剩 {@code event_stats: []}。</li>
 * </ol>
 */
public interface GenerationEngine {

    /**
     * 执行一次生成，逐类事件账本由调用方持有。
     *
     * @param eventStats 逐类事件账本（调用方创建；引擎只往里记"真的写进规范流"的事件）
     */
    EngineOutcome run(GenerationRequest request, EventSink sink, BooleanSupplier cancelled,
                      EventStatsRecorder eventStats);

    /** 便捷重载：账本由引擎自己新建，只用于"调用方不关心失败样本分布"的场合（测试、单次试算）。 */
    default EngineOutcome run(GenerationRequest request, EventSink sink, BooleanSupplier cancelled) {
        return run(request, sink, cancelled, new EventStatsRecorder());
    }
}
