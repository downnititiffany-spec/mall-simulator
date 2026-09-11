package com.graduation.analytics.warehouse;

/**
 * 「本平台当前读取哪个数仓库名空间」的**唯一**来源（P1-04）。
 *
 * <p>取值链只有一条：{@code runtime_profile.hive_database_prefix}（ACTIVE 档案）→
 * {@link WarehouseNamespace}。实现放在 connection-ingestion
 * （{@code ActiveProfileWarehouseNamespaceProvider}），消费方（AI 证据血缘、P4 页面）
 * 只依赖本接口，不各自拼库名，也不各自读配置。</p>
 *
 * <p>非法前缀**不静默兜底**：实现应抛出带错误码的异常，由调用方如实失败
 * （提交流水线时由 {@code JobCommandBuilder} 在 spark-submit 之前失败）。</p>
 *
 * <p>后续（P2/P3）：库名空间应随被分析快照的 {@code source_id} 解析，而不是"当前 ACTIVE 档案"，
 * 本接口即那时的替换点。</p>
 */
public interface WarehouseNamespaceProvider {

    /** 当前数仓命名空间（不得返回 null） */
    WarehouseNamespace current();
}
