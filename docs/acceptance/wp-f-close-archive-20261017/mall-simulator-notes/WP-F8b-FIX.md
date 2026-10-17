# WP-F8b-FIX 规约：修复 ArchivedSnapshotIsolationTest 编译错误（总控亲跑判不合格后的修复轮）

## 总控亲跑证据（2026-10-17 巡检）
在 analytics-server 反应堆亲跑 `mvn -o -Dmaven.repo.local=~/workspace/toolchain/m2repo -f analytics-server/pom.xml test -pl platform-app -am -Dtest=QualityRuleV19MappingLedgerTest -Dsurefire.failIfNoSpecifiedTests=false` 时，metric-analysis 模块 testCompile 失败（唯一错误）：

```
ArchivedSnapshotIsolationTest.java:[175,48] PUBLISHED_METRIC_VALUES_SQL is not public in com.graduation.analytics.metric.MySqlMetricStore; cannot be accessed from outside package
```

根因：`MySqlMetricStore.PUBLISHED_METRIC_VALUES_SQL` 是包私有常量（`com.graduation.analytics.metric` 包，MySqlMetricStore.java:50 `static final String`），既有测试 `MySqlMetricStoreActiveProfileTest` 与其同包故可访问；新增 `ArchivedSnapshotIsolationTest` 位于 `com.graduation.analytics.analysis` 包，跨包访问不合法。

## 修复要求（只许这一处改动 + 报告）
1. 仅修改 `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/ArchivedSnapshotIsolationTest.java`：
   - 将 `mySqlMetricStoreQueryEnsuresSnapshotIsolation()` 中 stub 处（约 L175）的 `eq(MySqlMetricStore.PUBLISHED_METRIC_VALUES_SQL)` 改为 `anyString()`（该文件下方既有 verify 已用 `anyString()` + 参数 `eq(ARCHIVED_SID), eq(STATUS_ACTIVE), eq(STATUS_ARCHIVED)` 校验绑定，断言意图不丢失）。
   - 若因此产生未用 import，自行清理；不得改动任何其他用例、断言或文件。
2. **禁止修改生产代码**（含不得把常量改 public）；禁止修改其他三个交付文件（QualityRuleV19MappingLedgerTest.java、ActiveSnapshotProfileScopingTest.java、QualityCheckerSeverityTest.java）。
3. 新建 `WP-F8b-FIX-REPORT.md`（写入 mall-simulator-notes/）：写明改动位置、改动前后文本、为何不改生产可见性。

## 边界
- 只用文件读写工具：不跑命令、不连数据库。
- 完成后回复末行输出：WPF8BFIX-DONE
