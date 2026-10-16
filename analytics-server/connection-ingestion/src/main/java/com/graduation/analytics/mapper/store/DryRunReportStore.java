package com.graduation.analytics.mapper.store;

import java.util.Optional;

/**
 * Dry-Run 报告存储接口（WP-2A-2 交付项 1：保存在接口后的内存存储中，无数据库、无写文件）。
 */
public interface DryRunReportStore {

    /**
     * 保存报告。
     */
    void save(StoredDryRunReport report);

    /**
     * 按 reportId 查找报告。
     */
    Optional<StoredDryRunReport> findById(String reportId);

    /**
     * 当前存储的报告数量。
     */
    int size();

    /**
     * 清空存储（测试重置用）。
     */
    void clear();
}
