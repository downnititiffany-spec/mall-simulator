# WP-F8b-FIX2 规约：修复 ArchivedSnapshotIsolationTest 严格桩误报（总控亲跑第二轮）

## 总控亲跑证据（2026-10-17 巡检，FIX 后复跑）
四类测试亲跑：QualityCheckerSeverityTest 9/9 PASS、ActiveSnapshotProfileScopingTest 7/7 PASS；ArchivedSnapshotIsolationTest 4 跑 1 Error：

```
mySqlMetricStoreQueryEnsuresSnapshotIsolation -- UnnecessaryStubbingException
Following stubbings are unnecessary:
  1. -> at ArchivedSnapshotIsolationTest.setUp(ArchivedSnapshotIsolationTest.java:78)
```

根因：setUp() L78 的 `when(qualityGate.statusForRun(any())).thenReturn(MetricQualityGate.PASS)` 为 AnalysisService 用例所需，但 `mySqlMetricStoreQueryEnsuresSnapshotIsolation` 直查 MySqlMetricStore、不经 AnalysisService，该桩在此用例未被使用，被 MockitoExtension 严格模式判多余。其余 3 用例的断言本身全过。

## 修复要求（只许这一处改动 + 报告）
1. 仅修改 `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/ArchivedSnapshotIsolationTest.java` 的 setUp()：
   - 将该桩改为 lenient 形式：`lenient().when(qualityGate.statusForRun(any())).thenReturn(MetricQualityGate.PASS);`，并按需补 `import static org.mockito.Mockito.lenient;`（或该类既有风格的等价写法）。
   - 不得改动任何用例、断言或其他桩。
2. 禁止修改生产代码；禁止修改其他交付文件。
3. 新建 `WP-F8b-FIX2-REPORT.md`（mall-simulator-notes/）：写明改动位置与前后文本。

## 边界
- 只用文件读写工具：不跑命令、不连数据库。
- 完成后回复末行输出：WPF8BFIX2-DONE
