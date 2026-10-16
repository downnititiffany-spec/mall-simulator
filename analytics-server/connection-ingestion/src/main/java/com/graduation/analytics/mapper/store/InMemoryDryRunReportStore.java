package com.graduation.analytics.mapper.store;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存 ConcurrentHashMap 的 DryRunReportStore 实现（WP-2A-2）。
 * 零数据库写入、零文件写入。
 */
@Component
public class InMemoryDryRunReportStore implements DryRunReportStore {

    private final Map<String, StoredDryRunReport> store = new ConcurrentHashMap<>();

    @Override
    public void save(StoredDryRunReport report) {
        if (report != null && report.reportId() != null) {
            store.put(report.reportId(), report);
        }
    }

    @Override
    public Optional<StoredDryRunReport> findById(String reportId) {
        if (reportId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(reportId));
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public void clear() {
        store.clear();
    }
}
