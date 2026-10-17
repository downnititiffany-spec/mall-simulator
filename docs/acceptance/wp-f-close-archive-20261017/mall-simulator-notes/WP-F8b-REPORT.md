# WP-F8b 交付报告：F-88 测试类关账实施（测试代码轮）

> **报告日期**：2026-10-17  
> **执行规约**：`~/workspace/projects/mall-simulator-notes/WP-F8b.md`  
> **实施性质**：测试代码交付与定位报告（纯静态代码编写，零外部命令执行，零数据库连接，生产源文件零修改）  
> **状态边界（总控裁定受控口径）**：**F-88 正式状态维持「限定验收」**；严禁任何「F-88 已通过完整验收」的同义表述。本包交付成果供总控亲跑验证与裁决。

---

## 1. 交付总览 (Executive Summary)

本包根据 `WP-F8b.md` 规约，聚焦 WP-F8 盘点报告（`WP-F8-INVENTORY-REPORT.md`）中已拆分的 B 类遗留项中**测试类 4 项**进行精准落地。

### 核心合规与执行指标
- **只用文件读写工具**：未运行任何 shell/maven/git 命令；未启动或连接任何真库（3306/3307/Docker/DeepSeek 全程零接触）。
- **生产代码零修改**：`analytics-server/*/src/main/**` 生产源文件变更数为 **0**。
- **纯测试资产交付**：所有补丁代码均严格位于 `src/test/java/**`。
- **4 项任务 100% 覆盖**：逐项给出既有覆盖定位（`file:line`）、缺口分析、新增文件/用例及总控亲跑命令。

---

## 2. 逐项既有覆盖定位、缺口分析与交付清单

### 2.1 F88-GAP-05：全量映射台账（V19 种子 ↔ QualityRuleCatalog.DEFINITIONS）

#### 1) 既有覆盖定位 (`file:line`)
1. `analytics-server/platform-common/src/test/java/com/graduation/analytics/metric/RuleSeverityTest.java`:
   - L71–L84：`ALL_REGISTERED_CODES` 静态集合（40 项规则码常量）；
   - L87–L115：`blockingCodesBlock()`, `warnCodesDoNotBlock()`, `infoCodesDoNotBlock()` 校验档位常量，**未涉及任何 SQL 种子解析**。
2. `analytics-server/platform-app/src/test/java/com/graduation/analytics/guard/RuleSeverityPathConsistencyTest.java`:
   - L76–L123：`bothPathsAgreeForEveryRegisteredRuleCode()` 对比 `DataQualityGate` 与 `MetricPublishValidator`，**未涉及 SQL 种子解析**。
3. `analytics-server/platform-app/src/test/java/com/graduation/analytics/migration/QualityRuleVersionMigrationScriptTest.java`:
   - L416–L455：`v19SeedMatchesTheJavaCatalogExactly()` 对 `V19, V23, V25, V26, V27, V28, V29, V34` 共 8 个加性脚本的**并集**（45 行）与 `QualityRuleCatalog.DEFAULT.definitions()`（45 条）做零漂移校验。
   - **现有缺口**：缺少单独针对「V19 种子 35 条 INSERT ↔ `QualityRuleCatalog` 35 条初始 version=1 定义」的独立穷举台账与两端计数 35 且差异为 0 的孤立断言。

#### 2) 新增交付文件与用例
- **新建测试类**：`analytics-server/platform-app/src/test/java/com/graduation/analytics/migration/QualityRuleV19MappingLedgerTest.java`
- **新增用例清单**：
  1. `v19SqlSeedsAndJavaCatalogDefinitionsMatchExhaustively()`：
     - 从 `V19__quality_rule_definition.sql` 解析 35 条 INSERT 种子；
     - 从 `QualityRuleCatalog.DEFAULT.definitions()` 提取对应的 35 条 version=1 契约；
     - 穷举断言：两端计数同为 35、逐条 `rule_code`、`version=1`、`checksum` 全等，且 `stage`、`severity`、`severityMode`、`thresholdJson` 差异全为 0；
     - 显式断言集合差集 `onlyInSql.isEmpty()` 与 `onlyInCatalog.isEmpty()`。
  2. `v19RuleCodesMatchStrictExpectedList()`：
     - 断言 V19 种子解析出的规则码集合与 35 个规范规则码字母排序全量清单精确一致。
  3. `v19SeedsSatisfyGlobalBaselineInvariants()`：
     - 断言 V19 所有 35 条规则定义不变量：`version == 1`、`source_scope == "*"`、`enabled == true`、`checksum.length() == 64`。

#### 3) 总控亲跑命令
```bash
mvn test -pl platform-app -Dtest=QualityRuleV19MappingLedgerTest
```

---

### 2.2 F88-INV-01：MySqlMetricStore / MetricAdsReader profile 隔离与委托

#### 1) 既有覆盖定位 (`file:line`)
1. 生产代码事实（带 `runtimeProfileId` 重载与无参委托）：
   - `analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MySqlMetricStore.java`:
     - L189–L192：无参 `activeSnapshotId()` 委托 `activeProfileIdProvider.activeRuntimeProfileId()`；
     - L195–L204：重载 `activeSnapshotId(long runtimeProfileId)` 绑定 `WHERE status = 'ACTIVE' AND runtime_profile_id = ? ORDER BY id DESC LIMIT 1`。
   - `analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MetricAdsReader.java`:
     - L57–L61：无参 `activeSnapshotId()` 委托 `activeProfileIdProvider`；
     - L69–L75：重载 `activeSnapshotId(long runtimeProfileId)` 执行相同 profile 过滤 SQL。
2. **现有缺口**：`metric-analysis` 模块缺少定向隔离单测验证「两个 profile 各有 ACTIVE 快照时互不串扰」以及「无参方法按上下文 provider 解析 profile」。

#### 2) 新增交付文件与用例
- **新建测试类**：`analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/ActiveSnapshotProfileScopingTest.java`
- **新增用例清单**：
  1. `mySqlMetricStoreOverloadReturnsRespectiveSnapshotWithoutInterference()`：
     - 配置 Profile 101L 对应 `S_ALPHA_ACTIVE_101`，Profile 202L 对应 `S_BETA_ACTIVE_202`；
     - 断言 `metricStore.activeSnapshotId(101L)` 得到 `S_ALPHA_ACTIVE_101` 且不等于 `S_BETA_ACTIVE_202`；
     - 断言 `metricStore.activeSnapshotId(202L)` 得到 `S_BETA_ACTIVE_202` 且不等于 `S_ALPHA_ACTIVE_101`（双向互不串扰）。
  2. `mySqlMetricStoreParameterlessMethodResolvesFromContextProvider()`：
     - 当上下文 provider 提供 Profile 101L 时，无参方法解析为 `S_ALPHA_ACTIVE_101`；
     - 当上下文 provider 切换到 Profile 202L 时，无参方法解析为 `S_BETA_ACTIVE_202`。
  3. `mySqlMetricStoreReturnsNullWhenNoActiveProfileOrNoSnapshot()`：
     - 上下文返回 `Optional.empty()` 时安全返回 `null`；
     - 指定无快照的 profile 或非法 profileId（`<= 0`）时安全返回 `null`，不触碰 SQL。
  4. `mySqlMetricStoreWithNullProviderFailsClosedForParameterlessAndWorksForOverload()`：
     - 兼容构造函数（provider 为 null）时，无参方法 fail-closed 返回 `null`（不抛 NPE），重载仍可传参工作。
  5. `metricAdsReaderOverloadReturnsRespectiveSnapshotWithoutInterference()`：
     - 验证 `MetricAdsReader` 的重载方法在双 profile 共存时的精确隔离与非串扰。
  6. `metricAdsReaderParameterlessMethodResolvesFromContextProvider()`：
     - 验证 `MetricAdsReader` 无参方法随上下文 provider 正确动态解析。
  7. `metricAdsReaderReturnsNullWhenNoActiveProfileOrNullProvider()`：
     - 验证上下文无 profile 或 provider 为 null 时的安全空态保护。

#### 3) 总控亲跑命令
```bash
mvn test -pl metric-analysis -Dtest=ActiveSnapshotProfileScopingTest
```

---

### 2.3 F88-INV-06：ARCHIVED 历史快照读隔离与防污染

#### 1) 既有覆盖定位 (`file:line`)
1. 生产代码事实：
   - `analytics-server/metric-analysis/src/main/java/com/graduation/analytics/analysis/AnalysisService.java`:
     - L650–L666：`pin(String requestedSnapshotId)` 方法。当入参为空时取 `adsReader.activeSnapshotId()`（仅 ACTIVE）；显式传参时调用 `metricStore.findSnapshot(snapshotId)`，允许状态为 `ACTIVE` 或 `ARCHIVED`。
   - `analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MySqlMetricStore.java`:
     - L50–L53：`PUBLISHED_METRIC_VALUES_SQL` 显式限定 `WHERE mv.snapshot_id = ? AND ms.status IN (?, ?)`（绑定 ACTIVE 与 ARCHIVED）。
2. 既有测试定位：
   - `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/AnalysisServiceTest.java`:
     - L829–L845：`explicitArchivedSnapshotFromAnotherProfileIsReadableById()` 仅断言显式传归档快照时不调用 `activeSnapshotId()`，**未断言指标数值在两代快照间的绝对防污染与数据正交隔离**。

#### 2) 新增交付文件与用例
- **新建测试类**：`analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/ArchivedSnapshotIsolationTest.java`
- **新增用例清单**：
  1. `defaultQueryReturnsOnlyActiveSnapshotDataWithoutArchivedPollution()`：
     - 默认查询（入参 `snapshotId=null`）严格返回当前 ACTIVE 快照 `S_20261016_ACTIVE`；
     - GMV 严格为 ACTIVE 的 8888.00，绝不渗透 ARCHIVED 的 3333.00；
     - 确认 `adsReader.activeSnapshotId()` 被调用，且底层未向 ARCHIVED 快照发起查询。
  2. `explicitArchivedSnapshotQueryReturnsArchivedDataWithoutActivePollution()`：
     - 显式查询传入 `S_20261015_ARCHIVED`，精确返回历史归档快照；
     - GMV 严格为 ARCHIVED 的 3333.00，绝不渗透 ACTIVE 的 8888.00；
     - 确认 `adsReader.activeSnapshotId()` **绝未被调用**（不回退 ACTIVE）。
  3. `defaultAndExplicitQueriesRemainOrthogonalAndIsolated()`：
     - 交叉执行默认查询与显式归档查询，断言在同一进程内多次调用无任何缓存残留或参数串值。
  4. `mySqlMetricStoreQueryEnsuresSnapshotIsolation()`：
     - 验证 `MySqlMetricStore.query()` 在显式请求 ARCHIVED 快照时，SQL 参数第一个占位符严格绑定目标快照 ID。

#### 3) 总控亲跑命令
```bash
mvn test -pl metric-analysis -Dtest=ArchivedSnapshotIsolationTest
```

---

### 2.4 F88-INV-09：THRESHOLD_OBSERVATION 阈值边界

#### 1) 既有覆盖定位 (`file:line`)
1. 生产代码事实：
   - `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/QualityChecker.java`:
     - L29：`private static final BigDecimal DUP_RATE_MAX = new BigDecimal("0.0005"); // 0.05%`
     - L127–L129：
       ```java
       BigDecimal rate = checks == 0 ? BigDecimal.ZERO
               : BigDecimal.valueOf(dup).divide(BigDecimal.valueOf(checks), 6, java.math.RoundingMode.HALF_UP);
       boolean withinThreshold = rate.compareTo(DUP_RATE_MAX) <= 0;
       ```
   - `analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/RuleSeverity.java`:
     - L369–L377：`resolve` 针对 `THRESHOLD_OBSERVATION` 模式：未通过（`failed(passed)`）升为 `BLOCKING` 并 `blocks = true`；通过则保持 `WARN` 且 `blocks = false`。
2. 既有测试定位：
   - `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/QualityCheckerSeverityTest.java`:
     - L65–L82：`duplicateEventIdsWithinThresholdDoNotBlock()`（100 个事件 0 重复，重复率 0.000000 <= 0.0005）；
     - L85–L110：`duplicateEventIdsBeyondThresholdBlock()`（2 个事件 1 重复，重复率 0.5 > 0.0005）。
     - **现有缺口**：缺少恰好等于 0.0005 与微超 0.0005 的精确边界用例。

#### 2) 比较语义与精度事实分析（⚠️ 详述证据留总控裁定）
在编写用例时，深度核对 `QualityChecker.java:128` 的具体实现：
- 源码计算公式为：
  `BigDecimal.valueOf(dup).divide(BigDecimal.valueOf(checks), 6, java.math.RoundingMode.HALF_UP)`
- **精度事实**：该处除法指定了 **`scale = 6` 与 `HALF_UP` 舍入模式**。
  - 当重复率恰为 0.0005 时（例如 2000 个事件中 1 个重复，1 / 2000 = 0.000500），`0.000500.compareTo(0.0005) == 0 <= 0` 成立，判定为通过（观察项 WARN，不阻断）。
  - 若输入数据在数学上的理论重复率为 `0.0005001`（7 位小数，如 5001 / 10,000,000）：
    因为 `scale = 6` 且第 7 位为 `1 < 5`，在 `HALF_UP` 规则下**被截断舍入为 `0.000500`**！
    因此在 `QualityChecker` 实际运行中，数学上的 `0.0005001` 在当前 6 位精度下会落在通过侧（`withinThreshold = true`）。
  - 要在 6 位精度下产生大于 0.0005 的判定，重复率必须在第 6 位进位（如 1 / 1996 ≈ 0.000501，或 5005 / 10,000,000 舍入为 0.000501）。
  - 在直接 `BigDecimal.compareTo` 层面：`new BigDecimal("0.0005001").compareTo(new BigDecimal("0.0005")) > 0` 严格大于 0（阻断）。
- **依据规约指示**：“判据以源码实际比较语义为准；若源码语义与此预期不符，不要改生产代码，在报告中写明实际语义与行号，留总控裁定。”
  本轮严格遵守铁律：**生产代码零改动**，并在测试与本报告中将这一精度现象完整取证留存。

#### 3) 新增用例清单（修改 `QualityCheckerSeverityTest.java`）
- **修改文件**：`analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/QualityCheckerSeverityTest.java`
- **新增用例**：
  1. `duplicateRateExactlyAtApprovedThreshold00005IsObservedAndDoesNotBlock()`：
     - 构造 2000 个事件，其中 1 个重复（1999 个唯一 ID），重复率恰为 `0.000500`；
     - 断言 `passed = 1`、`severity = WARN`、`effectiveSeverity = WARN`、`corePassed = true`，详情包含“未超批准阈值，观察项”。
  2. `duplicateRateExceedingApprovedThresholdBlocks()`：
     - 构造 1996 个事件，其中 1 个重复，重复率经 scale=6 算得 `0.000501 > 0.0005`；
     - 断言 `passed = 0`、`severity = WARN`、`effectiveSeverity = BLOCKING`、`corePassed = false`，详情包含“超批准阈值，阻断”。
  3. `thresholdComparisonBoundaryContractAndScaleSemantic()`：
     - 断言 `0.0005.compareTo(0.0005) <= 0` 成立，`0.0005001.compareTo(0.0005) > 0` 成立；
     - 断言 `RuleSeverity.resolve` 在 `passed=1` 时给 WARN（不阻断）、在 `passed=0` 时给 BLOCKING（阻断）；
     - 机器化验证 `QualityChecker:128` scale=6 HALF_UP 舍入语义，固定证据留总控裁定。

#### 4) 总控亲跑命令
```bash
mvn test -pl warehouse-pipeline -Dtest=QualityCheckerSeverityTest
```

---

## 3. 总控一键验收验证命令集

总控可在环境就绪后逐一或批量亲跑以下 4 条测试命令，全部脱离真库（纯内存替身/只读资源解析）：

```bash
# 1. 验证 V19 种子 ↔ QualityRuleCatalog.DEFINITIONS 35 条全量台账（GAP-05）
mvn test -pl platform-app -Dtest=QualityRuleV19MappingLedgerTest

# 2. 验证 MySqlMetricStore / MetricAdsReader profile 隔离与委托解析（INV-01）
mvn test -pl metric-analysis -Dtest=ActiveSnapshotProfileScopingTest

# 3. 验证 ARCHIVED 历史快照读隔离与防污染（INV-06）
mvn test -pl metric-analysis -Dtest=ArchivedSnapshotIsolationTest

# 4. 验证 EVENT_ID_UNIQUE 0.0005 临界阈值与精度边界（INV-09）
mvn test -pl warehouse-pipeline -Dtest=QualityCheckerSeverityTest
```

---

## 4. 状态结论与声明

1. **F-88 状态口径不变**：本包交付后，**F-88 正式状态严格保持「限定验收」**，不作任何升级或同义完整验收断言。
2. **零命令零真库执行证明**：本次执行全过程仅调用文件读写接口，未调用任何 bash 终端工具，未访问 3306/3307 数据库实例，未改动任何生产源文件。
3. **交付完毕**：所有测试代码与本报告均已就绪。
