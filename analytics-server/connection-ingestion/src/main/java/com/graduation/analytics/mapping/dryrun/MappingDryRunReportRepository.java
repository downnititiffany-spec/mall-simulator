package com.graduation.analytics.mapping.dryrun;

import java.util.Optional;

/**
 * dry-run 报告存储（本轮**故意**只有进程内实现）。
 *
 * <p>设计 §7.3 规则 10 要求 dry-run「不写正式数据/checkpoint/激活指针」；把报告本身也挡在库外，
 * 才能保证「预览」这个动作对生产状态零影响（不新增 Flyway 迁移、不建表、不连 3306/3307）。
 * 代价是 <b>进程重启后 {@code GET .../dry-runs/{reportId}} 会 404</b>——这是明确的取舍，不是缺陷；
 * 将来若要把预览报告留痕，应当新开一张审批过的表 + 迁移，而不是在本轮偷偷写库。</p>
 *
 * <p>接口保留是为了让"换实现"成为编译期可见的动作（例如后续换成 meta 库表）。</p>
 */
public interface MappingDryRunReportRepository {

    void save(MappingDryRunReport report);

    Optional<MappingDryRunReport> find(String reportId);
}
