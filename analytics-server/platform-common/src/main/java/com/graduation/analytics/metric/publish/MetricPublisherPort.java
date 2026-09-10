package com.graduation.analytics.metric.publish;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * R7-3（V2.0 §17.4/§17.5）：指标快照发布端口。
 *
 * <p>契约放在 {@code platform-common}，实现放在 {@code metric-analysis}：
 * {@code warehouse-pipeline} 只依赖 common，因此流水线可以"在 PUBLISH_METRIC 阶段调用发布器"，
 * 而**不需要**反向依赖指标模块（避免模块环）。</p>
 *
 * <p>调用方（PipelineService）负责提供：快照号、业务日、导出目录（由 Spark 侧 {@code mxp} 作业写入）、
 * 以及 {@code metric_definition} 的指标口径版本（字典属 meta 库，只有流水线侧读得到）。</p>
 */
public interface MetricPublisherPort {

    /**
     * 发布一个快照：BUILDING → 写 ADS 宽表与核心指标 → VERIFYING → 对账 → ACTIVE（失败则 FAILED，旧 ACTIVE 保持）。
     * 任何失败都必须以"报告 ok=false"返回，**不允许**抛出后留下 VERIFYING 悬挂状态。
     */
    PublishReport publish(PublishRequest request);

    /**
     * @param runtimeProfileId      运行环境（快照归属，用于"同 profile 唯一 ACTIVE"）
     * @param runtimeProfileVersion §8.1 实际使用的 profile 版本
     * @param snapshotId            本次快照号（Hive 分区 snapshot_id 与之相同）
     * @param businessDate          业务日 yyyyMMdd（Hive ADS 的 dt；写入 MySQL 宽表的 dt 同值）
     * @param businessTime          业务时间（ISO-8601，落 metric_snapshot.business_time）
     * @param pipelineRunId         来源流水线实例
     * @param exportDir             Spark `mxp` 作业写出的导出目录（内含 `_export.json` 与各表 JSONL）
     * @param definitionVersions    metric_code → 口径版本/单位（来自 analytics_meta.metric_definition，
     *                              快照发布必须按字典版本写入并逐码对账，未知指标码直接拒绝）
     */
    record PublishRequest(long runtimeProfileId, Integer runtimeProfileVersion, String snapshotId,
                          String businessDate, String businessTime, Long pipelineRunId,
                          Path exportDir, Map<String, DefinitionRef> definitionVersions) {
    }

    /** 指标字典引用（§17.5「版本对账」所需的最小信息） */
    record DefinitionRef(String definitionVersion, String unit) {
    }

    /** 发布报告（证据随阶段证据落库；check 结构与 Spark 侧 QualityCheck 对齐） */
    record PublishReport(boolean ok, String errorCode, String message, String snapshotId,
                         long adsRows, int metricValues, List<Check> checks,
                         Map<String, Object> evidence) {
    }

    /** 一条发布对账/校验结果 */
    record Check(String ruleCode, String layer, String targetTable, long checkCount, long errorCount,
                 String severity, boolean passed, String detail) {
    }
}
