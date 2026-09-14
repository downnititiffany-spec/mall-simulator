package com.graduation.analytics.metric;

/**
 * R7-4：快照对应的**质量门结论**查询（契约 docs/contracts/analysis-viewmodel-r7-4.md §2 的 qualityStatus）。
 *
 * <p>为什么放在 platform-common：结论来自 {@code analytics_meta.data_quality_result}，
 * 该表的 mapper/实体属 warehouse-pipeline（§17.2 表所有权）；而分析服务在 metric-analysis，
 * 只能依赖 platform-common。把接口放在这里，由 warehouse-pipeline 提供实现，
 * 分析服务只依赖接口 → 不需要 metric-analysis 反向依赖流水线模块，也不产生模块环。</p>
 *
 * <p>判定口径（D-142 §1 / 指导书 §7.3，不得臆造）：取该快照 {@code pipeline_run_id} 对应的质量结果，
 * 有 {@code BLOCKING} <b>或 {@code ERROR}</b> 且未通过 → {@link #FAIL}；有结果且无阻断级失败 →
 * {@link #PASS}；{@code WARN}/{@code INFO} 未通过只记录、不影响结论；
 * 查不到结果或没有 run 号 → {@link #UNKNOWN}。</p>
 */
public interface MetricQualityGate {

    /** 质量门通过 */
    String PASS = "PASS";

    /** 存在阻断级（BLOCKING/ERROR）未通过的规则 */
    String FAIL = "FAIL";

    /** 取不到质量结果（不臆造为 PASS） */
    String UNKNOWN = "UNKNOWN";

    /** 阻断发布的严重度之一（§16.3）；另一条是 {@code ERROR}（D-142 §1） */
    String BLOCKING = "BLOCKING";

    /**
     * 该流水线 run 的质量门结论。
     *
     * @param pipelineRunId 快照的 pipeline_run_id；为空表示无法定位 run
     * @return {@link #PASS} / {@link #FAIL} / {@link #UNKNOWN}，永不为 null
     */
    String statusForRun(Long pipelineRunId);
}
