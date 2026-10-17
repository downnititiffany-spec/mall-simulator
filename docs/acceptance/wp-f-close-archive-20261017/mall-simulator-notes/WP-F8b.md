# WP-F8b 规约：F-88 测试类关账实施（测试代码轮）

## 背景
WP-F8 盘点（`WP-F8-INVENTORY-REPORT.md`，总控已验收）将 F-88 剩余 B 类 12 项拆分；本包只做其中**测试类 4 项**。F-88 正式状态维持**限定验收**，本包不作任何验收结论。

## 任务（逐项先定位既有覆盖，再补缺口；禁止重复造已有测试）
1. **F88-GAP-05 全量映射台账**：先查 `platform-common` 既有 `RuleSeverityTest`、`QualityRuleVersionMigrationScriptTest` 与 `platform-app` 的 `RuleSeverityPathConsistencyTest` 现有覆盖。若尚无「V19 SQL 种子 35 条 INSERT ↔ `QualityRuleCatalog.DEFINITIONS` 35 条逐条（rule_code、version 全为 "v1"、checksum 全等）穷举比对」，则新增一个穷举台账测试（断言两端计数同为 35 且逐条差异为 0）；若已存在等价穷举，在报告中给出文件:行号证据，不重复新增。
2. **F88-INV-01（总控改框后）**：现状事实（总控亲验）：`MySqlMetricStore`/`MetricAdsReader` 均有带 `runtimeProfileId` 的重载，无参 `activeSnapshotId()` 经 `activeProfileId` 上下文委托重载。为两类补定向测试：两个 profile 各有 ACTIVE 快照时，带 profile 的重载各得本 profile 快照、互不串扰；无参方法在上下文给定 profile 时解析到该 profile 的快照。沿用该模块既有测试基建（mock/内存替身），不连真库。
3. **F88-INV-06 ARCHIVED 隔离**：在服务/读侧既有测试层（如 `AnalysisService`/`MetricController` 或 metric-analysis 读侧测试）补测试：默认查询只返回 ACTIVE 快照数据；显式传 ARCHIVED 快照 id 可读到该快照数据且值不与 ACTIVE 渗透。沿用既有 mock 风格，不连真库。
4. **F88-INV-09 阈值边界**：先定位 `THRESHOLD_OBSERVATION` 阈值 0.0005 的比较代码与既有测试（如 `QualityCheckerSeverityTest`），在其既有风格下补两例：errorRate 恰为 0.0005 → 观察/通过（不阻断）；0.0005001 → 阻断。判据以源码实际比较语义为准；若源码语义与此预期不符，**不要改生产代码**，在报告中写明实际语义与行号，留总控裁定。

## 交付
- 上述测试代码（仅测试源文件；生产代码零改动）。
- `WP-F8b-REPORT.md`（写入 mall-simulator-notes/）：逐项写明——既有覆盖定位（文件:行号）、新增文件与用例清单、每个新增测试类可由总控亲跑的完整 `mvn -Dtest=...` 命令。

## 边界
- 只用文件读写工具：不跑命令、不连数据库、不改任何生产源文件（仅 `src/test` 下文件与本报告）。
- 若任何一项在写测试时发现疑似生产缺陷：停该项、报告中详述证据，不擅自修复。
- 不碰 3306/Docker/DeepSeek；不改变 F-88 状态口径。
- 完成后回复末行输出：WPF8B-DONE
