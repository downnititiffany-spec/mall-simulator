package com.graduation.analytics.mapping.dryrun;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内 dry-run 报告存储（{@link MappingDryRunReportRepository} 的当前唯一实现）。
 *
 * <p>选 {@code ConcurrentHashMap} 而不是 MyBatis-Plus：本轮明确"报告不落库"（见接口 javadoc）。
 * 选并发容器而不是 {@code HashMap}：dry-run 是只读预览接口，可能被并发调用，容器必须是线程安全的，
 * 否则一张报告表会在并发下丢条目（丢的还是"事实"）。</p>
 *
 * <p>不做容量上限/过期淘汰：单进程内存里最多累积 N 条报告（每条 ≤ 100 行的聚合），是本轮可接受的
 * 权衡；真要长期留存就得走"新表 + 迁移 + 清理策略"，不在本轮的范围内，也不在这里偷偷加 LRU
 * （LRU 会让 {@code GET} 的结果依赖访问顺序，破坏"同一输入 ⇒ 同一事实"的可对账性）。</p>
 */
@Component
public class InMemoryMappingDryRunReportRepository implements MappingDryRunReportRepository {

    private final ConcurrentMap<String, MappingDryRunReport> reports = new ConcurrentHashMap<>();

    @Override
    public void save(MappingDryRunReport report) {
        reports.put(report.reportId(), report);
    }

    @Override
    public Optional<MappingDryRunReport> find(String reportId) {
        return reportId == null ? Optional.empty() : Optional.ofNullable(reports.get(reportId));
    }
}
