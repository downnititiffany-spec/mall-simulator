# WP-F8b-FIX 修复报告

## 1. 改动位置
- **目标文件**：`analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/ArchivedSnapshotIsolationTest.java`
- **方法**：`mySqlMetricStoreQueryEnsuresSnapshotIsolation()`
- **行号**：约 L175-L178

## 2. 改动前后文本

### 改动前
```java
        when(readJdbc.query(eq(MySqlMetricStore.PUBLISHED_METRIC_VALUES_SQL),
                any(RowMapper.class), eq(ARCHIVED_SID),
                eq(MetricSnapshot.STATUS_ACTIVE), eq(MetricSnapshot.STATUS_ARCHIVED)))
                .thenReturn(List.of(archivedVal));
```

### 改动后
```java
        when(readJdbc.query(anyString(),
                any(RowMapper.class), eq(ARCHIVED_SID),
                eq(MetricSnapshot.STATUS_ACTIVE), eq(MetricSnapshot.STATUS_ARCHIVED)))
                .thenReturn(List.of(archivedVal));
```

## 3. 为何不改生产可见性
1. **封装边界原则**：`MySqlMetricStore.PUBLISHED_METRIC_VALUES_SQL` 是数据访问层内部的实现细节常量，保持包私有（package-private）可见性能够避免外部调用方或外部包强耦合具体 SQL 文本，维护清晰的模块封装边界。
2. **测试意图与断言完备**：本测试用例的核心目标是验证快照隔离与参数绑定的准确性。在 stub 阶段使用 `anyString()` 匹配 SQL，在 verify 阶段（L191-L192）同样使用 `anyString()` 结合 `eq(ARCHIVED_SID), eq(MetricSnapshot.STATUS_ACTIVE), eq(MetricSnapshot.STATUS_ARCHIVED)` 严格验证占位符绑定，断言意图丝毫不受影响。
3. **契约纪律**：遵循测试适配生产代码契约的工程纪律，坚决不因跨包测试的桩设便利而侵入生产代码或随意放大常量可见性。
