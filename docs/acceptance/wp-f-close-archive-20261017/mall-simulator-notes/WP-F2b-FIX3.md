# WP-F2b 修复轮 3（总控裁定 + 规约，2026-10-16 22:4x 巡检写下，下一巡检执行）

## 验收事实链（均为总控亲跑，RunId wpf2b20261016 / wpf2bfix20261016 / wpf2bfin20261016）
- FIX1（Find-MavenArtifactJar 两处 @() 包裹）与 FIX2（FlyMigrate java 调用加 -Djava.net.preferIPv4Stack=true）均已落码且有效：最终轮供给步实测 **analytics-schema exit=0、meta=37、metric=13**，FlyMigrate 机制本身跑通。
- 但 analytics 套件在 platform-common 即停：`AnalyticsIsolationScriptsContractTest` 13 跑 2 失败，reactor 未达 metric-analysis，三类写 IT 未执行，runner exit=7。两条失败与 IT 本体无关：
  1. `runnerStagesDistinctMetaAndMetricScopesWithoutReplacingExistingAnalyticsGate`：契约钉死原 staging 机制字面量——`MAVEN_ARGS = '-Pisolated-analytics-schema'`、`AnalyticsIsolationFlywayIT`、`analytics 双库 Flyway 未通过：拒绝继续写入型 IT`。WP-F2b 用 FlyMigrate 替换该块即撞契约。
  2. `unifiedRunnerAllowCountDriftBypassesOnlyTheCountComparisonGate`：期望 run-tests.ps1 中 `Test-BaselineComparison -Comparison $cmp` 恰 3 个比较点（split size 4），WP-F3 新增 web 比较点后实为 4 个（size 5）。**WP-F3 关账时只验了 web 档，此断言自 F3 起即为红**——属 F3 验收缺口，本轮一并纠正。

## 总控裁定（复核后定案，不再咨询）
- **WP-F2 的前提是误判**：原运行器本就有 schema 供给步（Maven profile `isolated-analytics-schema` → `AnalyticsIsolationFlywayIT`，在 Maven JVM 内执行，天然带 MAVEN_OPTS 的 IPv4 规避；且含幂等二次 migrate=0、V34/V13 版本钉、D-041 file_checkpoint 证据，强于 FlyMigrate 供给器）。WP-F2 当时是手工直跑三类 IT、未走运行器 staging，才得出「运行器不做供给」的错误结论。
- 因此 WP-F2b 的正确收官形态 = **撤销 FlyMigrate 替换、恢复原 staging**，收编腿以原机制开箱跑通为准；FlyMigrate.java 与 Find-MavenArtifactJar 为误判前提的产物，删除，不留死代码。

## 执行步骤（下一巡检）
### Part A（总控亲手做，非 agy）
1. `git checkout HEAD -- scripts/run-isolated-tests.ps1`（WP-F2b/FIX1/FIX2 对该文件的全部未提交改动一并撤销，回到 4a1e03a 原文）。
2. 删除 `scripts/flyway/FlyMigrate.java` 及空目录 `scripts/flyway/`。
3. 确认 `git status` 仅余未跟踪 `sample-data/`（旧留置物，不动）。

### Part B（派 agy，Flash，文件读写のみ）
仅改一个文件：`analytics-server/platform-common/src/test/java/com/graduation/analytics/testsupport/AnalyticsIsolationScriptsContractTest.java`
- 方法 `unifiedRunnerAllowCountDriftBypassesOnlyTheCountComparisonGate`：断言 `.hasSize(4)` 改为 `.hasSize(5)`；其 `.as(...)` 描述文字由「default / isolated / spark 三个计数比较点必须共用同一放行语义」改为「default / isolated / spark / web 四个计数比较点必须共用同一放行语义」。
- 其他方法、其他断言一律不动；不改 run-tests.ps1（web 比较点共用同一放行语义是 F3 的正确设计，过期的是本断言计数）。
- 完成标记：WPF2BFIX3-DONE

### Part C（总控亲跑验收，关账标准）
1. 全新 RunId：prepare（-IncludeAnalytics，exit 0）→ `run-isolated-tests.ps1 -Module analytics -IncludeAnalyticsWriteIts -Confirm`（本机参数同前几轮：InstanceUuid 9c3bdd51-…、toolchain 的 mysql/mvn/m2repo、-LogDir /tmp/test-logs/<RunId>）。
2. 合格线：analytics-schema 经 AnalyticsIsolationFlywayIT 通过（日志含 `-- in ...AnalyticsIsolationFlywayIT` 且 exit 0）；analytics 计数 **13**（IsolationGuard 6 + MetricPublisher 3 + MetricAds 2 + AdsDataQuality 2）全绿；runner **exit 0**。
3. 附带复核：单独跑 platform-common 该契约类 13/13 PASS（证明默认档此红已消除）。
4. 全过 → FULL-PROGRAM 标记 WP-F2b 已关账（注明前提误判与纠正）、PROJECT_STATUS 登记、提交（Part A 撤销本身无 diff 可提交；实际提交 = Part B 的测试修正 + 登记文件）、推送。
