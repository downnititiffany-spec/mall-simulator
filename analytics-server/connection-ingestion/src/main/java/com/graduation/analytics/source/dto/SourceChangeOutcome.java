package com.graduation.analytics.source.dto;

/**
 * 一次变更的结果（P1-03）：变更后的视图 + 变更前的视图 + 「是否真的改了」。
 *
 * <p>为什么需要 {@code changed}：{@code activate} 在「已是当前源」时**幂等成功**（D-035 裁决 4）。
 * 控制器据此决定要不要写审计行——幂等空操作不写重复的绑定审计，
 * 否则一次重试就会在 {@code operation_audit_log} 里留下一条"其实什么都没发生"的变更记录，
 * 后续按审计回溯"谁把当前源换成谁"会被假记录误导。</p>
 *
 * <p>为什么带 {@code before}：审计的 {@code before_digest/after_digest} 需要变更前后两个状态，
 * 而摘要格式的唯一所有者是 {@code OperationAuditService.digest}（在 ai-decision 模块，
 * connection-ingestion 不依赖它）。把 before 视图带回控制器，就能在控制器里用那个唯一实现拼摘要，
 * 而不是在服务层复制一份摘要格式。</p>
 *
 * @param source  变更后的视图
 * @param before  变更前的视图；{@code null} 表示新建（此前不存在）
 * @param changed 是否发生了真实写入；{@code false} 时调用方不得写审计行
 */
public record SourceChangeOutcome(SourceRegistryView source, SourceRegistryView before, boolean changed) {

    public static SourceChangeOutcome of(SourceRegistryView source, SourceRegistryView before) {
        return new SourceChangeOutcome(source, before, true);
    }

    public static SourceChangeOutcome unchanged(SourceRegistryView source) {
        return new SourceChangeOutcome(source, source, false);
    }
}
