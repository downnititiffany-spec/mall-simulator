# Q01 · E2 全反应堆复跑 + 一次真实回归的定位与修复（实测留痕）

- 日期：2026-09-14
- 泳道：V25-Q01「F-88 质量门严重度口径」
- HEAD：`8983616`（工作树改动，未提交）
- 命令统一前缀：`mvn -o "-Dmaven.repo.local=D:\maven_repository" -f analytics-server\pom.xml`

---

## 1. 两次 E2 的结果对比（结论：修掉了一个**我自己引入的真回归**）

| 轮次 | 日志 | 全反应堆合计 | 红 | exit |
|---|---|---|---|---|
| q01c（修复前） | `e2-full-reactor-q01c.log` | Tests run **616**, Failures 0, Errors 1 | **PipelineServiceTest 10 个失败** ＋ Spark 冒烟 1 个 error | 0（`failure.ignore=true`） |
| q01d（修复后） | `e2-full-reactor-q01d.log` | Tests run **616**, Failures 0, Errors 1 | 仅 Spark 冒烟 1 个 error（**非本泳道**） | 0（`failure.ignore=true`） |

> **注意**：两次都用 `-Dmaven.test.failure.ignore=true`，**exit 0 不代表全绿**。
> 红绿一律从日志 / surefire 报告读，不看退出码。

### q01d 各模块合计（`e2-full-reactor-q01d.log` 行号可核）

| 模块 | Tests run | Failures | Errors | Skipped |
|---|---|---|---|---|
| platform-common | 81 | 0 | 0 | 0 |
| connection-ingestion | 156 | 0 | 0 | 0 |
| warehouse-pipeline | **132** | 0 | **1** | 0 |
| metric-analysis | 48 | 0 | 0 | 0 |
| ai-decision | 91 | 0 | 0 | 0 |
| platform-app | 108 | 0 | 0 | 0 |
| **合计** | **616** | **0** | **1** | **0** |

Reactor Summary：7 个模块（含 aggregator）全部 `SUCCESS`，`BUILD SUCCESS`，Total time 42.298 s，Finished at `2026-09-14T12:43:21+08:00`。

---

## 2. 本泳道 5 个测试类（q01d 实测，逐类可核）

| 测试类 | 模块 | Tests run | Failures | Errors |
|---|---|---|---|---|
| `metric.RuleSeverityTest` | platform-common | 14 | 0 | 0 |
| `pipeline.DataQualityGateTest` | warehouse-pipeline | 19 | 0 | 0 |
| `pipeline.QualityCheckerSeverityTest` | warehouse-pipeline | 5 | 0 | 0 |
| `metric.publish.MetricPublishValidatorSeverityTest` | metric-analysis | 10 | 0 | 0 |
| `guard.RuleSeverityPathConsistencyTest` | platform-app | 4 | 0 | 0 |
| **合计** | | **52** | **0** | **0** |

---

## 3. 真回归的定位与修复（**本泳道自身缺陷，如实记录**）

### 3.1 现象

q01c 中 `warehouse-pipeline` 的 `PipelineServiceTest` **15 个用例里 10 个失败**（该文件此前 15/15 全绿）。
除 1 个断言 `expected "PIPELINE_QUALITY_FAILED" but was "STAGE_INTERNAL"` 外，其余表现为
`expected "SUCCESS" but was "FAILED"`。

### 3.2 定位过程（不是猜的）

日志里**没有**异常细节，surefire 也没有 `-output.txt`。于是：
1. 用 `-Dtest=PipelineServiceTest#sameIdempotencyKeyReturnsOriginalTask` 单跑复现（exit 1，仍只给行号 279）；
2. 读 `target/surefire-reports/TEST-...PipelineServiceTest.xml` 的 `<system-out>`，拿到原文：

```
PipelineService -- pipeline 1: 规则冻结 ruleFingerprint=6bc272d7324b435d2a3c7d1afabfb5f9ca18310bad03970219b0ef132f4f3ac6 catalog=qrc-1 compatPolicy=compat-v1
PipelineService -- pipeline 1 failed: 阶段 QUALITY_CHECK 内部错误: Cannot invoke "com.graduation.analytics.pipeline.QualityChecker$QualitySummary.results()" because "quality" is null
```

**关键**：冻结日志行**有**打出 ⇒ `rulesFor(run)` 正常，`QualityRuleCatalog` 在运行期可见。真因是 `check(...)` 返回了 **null**。

### 3.3 根因（一句话）

`QualityChecker` 有**三个** `check` 重载（`:34` 两参、`:56` 三参、`:69` 四参带 `FrozenRules`）。
生产代码已改为调用**四参**重载，而 `PipelineServiceTest` 仍 stub **三参**：

```java
when(qualityChecker.check(any(), anyLong(), any()))          // 旧：stub 三参
```

Mockito 对**未被 stub 的四参调用**返回 `null`（默认 answer），`quality.results()` 立即 NPE ⇒ `STAGE_INTERNAL`。
⇒ **这是 stub 签名漂移，不是被测逻辑错误**；但它确实是本泳道迁移遗漏的调用点，属本泳道责任。

### 3.4 修复（4 处，均在 `PipelineServiceTest`）

| 行 | 原 | 改 |
|---|---|---|
| 145 | `check(any(), anyLong(), any())` | `check(any(), anyLong(), any(), any())` |
| 371 | 同上 | 同上 |
| 456 | `thenAnswer(inv -> new QualityChecker().check(a0, a1, a2))` | 追加 `inv.getArgument(3)` |
| 482 | 同上 | 同上 |

并在 145 行处留注释写明「若 stub 三参重载，Mockito 对四参返回 null ⇒ QUALITY_CHECK 抛 STAGE_INTERNAL（实测 10 个用例翻红）」，
避免后来者重犯。

同时全仓复查是否有同类遗漏（`qualityChecker\.check|\.check\(any`）：

- `PipelineServiceTest:145/371/456/482` —— 已全部为四参；
- `PipelineService.java:520` —— 生产调用点，四参；
- 其余 3 处命中（`TextToSqlAuditTest` 的 `guard.check`、`LocalFileIngestorSourceIsolationTest` 的 `validator.check`、`LocalFileIngestorCheckpointKeyTest` 的 `validator.check`）为**无关类**，未动。

### 3.5 复跑验证

```
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.592 s -- in PipelineServiceTest
BUILD SUCCESS / exit code: 0
```

---

## 4. 残留的 1 个 error：**非本泳道**，证据如下

```
SparkStageExecutorSmokeTest.realSparkOdlLoadsGoldenDataset
  MissingConfigurationException: 真实 Spark 冒烟默认关闭：未提供 -Dv25.spark.it=true
  at ...SparkItGuard.requireEnabled(SparkItGuard.java:78)
```

判定为**他人改动**的三条硬证据：

1. `SparkItGuard.java`（**未跟踪新文件**）javadoc 第 16 行自述：**「真实 Spark 冒烟用例的目录写入门禁（V25-S03 R-5）」** ⇒ 归属 **V25-S03**。
2. 时间戳：`SparkItGuard.java` `2026/9/14 12:35:00`、`SparkStageExecutorSmokeTest.java` `2026/9/14 12:35:06`
   —— 落在 q01c 复跑（约 12:38 起）**之前数分钟**，即**在我这一轮运行窗口内由他泳道写入**。
3. 与上次有效 E2（`e2-all-modules-test.log`）对比：那时该用例是
   `Tests run: 1, Failures: 0, Errors: 0 -- in SparkStageExecutorSmokeTest, Time elapsed: 34.77 s`（**真跑了 34.77 秒且通过**）；
   q01d 里 `Time elapsed: 0.023 s` ⇒ 已从「真跑」变成「门禁拦下」。

另需说明：该 error 与 `WarehouseNameLiteralGateTest` 的红**不同命**——后者在 q01d 里已恢复全绿
（`Tests run: 9, Failures: 0`，上轮为 `Tests run: 3, Failures: 1`），说明 platform-common 的既有红已由对应泳道修掉。

**本泳道未改动 `SparkItGuard.java` / `SparkStageExecutorSmokeTest.java` 一行，也不认领此 red。**

---

## 5. 只读边界（本轮全部命令均未触碰）

- 未使用 `-Dmetric.it=true`，未启动/停止任何服务，未连接数据库执行任何写操作；
- 未新建迁移文件、未编号、未执行 DDL/DML；
- 构建槽纪律：Maven 前先 `Get-Process java` 检查；本轮检测到 IntelliJ **JPS/Scala 编译服务器**常驻
  （`D:\Develop\JDK1.8\bin\java.exe … ij.scala.compile.server=true`）与 DataGrip `RemoteJdbcServer` 各 1 个进程。
  **这两者会并发写同一批 `target/classes`**，是历史「瞬时编译失败」的来源；本轮 Maven 均正常结束，未观察到冲突。

## 6. 未测项（显式声明）

- **未做变异测试**：未人为篡改 `RuleSeverity.resolve` 去验证护栏用例会变红，故「护栏具备判别力」仅有 oracle 构造层面的论证，**无变异实测**。
- **未在 `-Dv25.spark.it=true` 下复跑** Spark 冒烟（本泳道禁起外部进程 / 真实 spark-submit）。
- **未跑真库集成测试** `AnalysisGoldenMySqlIT`（需 `-Dmetric.it=true`，DB 冻结）。

---

## 7. 附带发现：`AnalysisGoldenMySqlIT` 的期望值与自身 javadoc 自相矛盾（**未运行期观测到**）

**这是本轮全量自检时读出来的潜在缺陷，不是测试跑出来的**——因为该 IT 本轮禁跑。

### 7.1 现象

同一个文件里两处结论**互相矛盾**：

| 位置 | 内容 |
|---|---|
| 类 javadoc `:53-60` | 「**⚠ F-88 四次修正（2026-09-14，实测）：上面的 PASS 结论已被证伪，不得再引用。** … 因此 run 24/47 在新口径下**合法地翻为 FAIL**。」 |
| 测试体 `:130`（修正前） | `assertThat(model.qualityStatus()).isEqualTo(MetricQualityGate.PASS);` ＋ 上方注释 `:127-129` 仍写着旧结论「这 3 条口径下都是 WARN（不阻断）⇒ 归一化后为 PASS」 |

### 7.2 判定：断言是错误的（依据是源码逻辑，可复算）

`RuleSeverity.resolve`（`platform-common/.../metric/RuleSeverity.java:284-286`）实测逻辑：

```java
String effective = d.severityMode() == SeverityMode.THRESHOLD_OBSERVATION
        ? (failed(passed) ? BLOCKING : d.severity())   // ← 只看 passed，不需要 error_rate
        : d.severity();
```

真库只读实测：run 24/47 的 `EVENT_ID_UNIQUE` 与 `PUB_DQ_EVENT_ID_UNIQUE` 均 `passed=0`
⇒ `failed(passed)=true` ⇒ `effective=BLOCKING` ⇒ `blocks()=true` ⇒ **`qualityStatus` 必为 `FAIL`**。

⇒ 断言 `PASS` 会失败。**推断链完整、可复算，但未执行**（DB 冻结）。
另：IT 的 SQL 只 `SELECT rule_code, severity, passed`，**不查 `error_rate`/`threshold`**——
所以它**无法**自行区分「真超阈值」与「误判」；`THRESHOLD_OBSERVATION` 的升级完全依赖 `passed` 列。
**这不是缺陷**（跨阈值对照不在该 IT 职责内），但解读该 IT 结果时**必须知道这一点**。

### 7.3 处置（已做，仅编译验证）

将 `:130` 期望值改为 `FAIL`，并就地重写 `:127-129` 的过时注释（点名旧结论已证伪、给出 40 倍/143 倍实测值、写明升级依据）。
`assertThat(model.warnings()).isEmpty()`（`:131`）与 `failedRules()`（`:156`）**未改**：
后者是 ADS 规则明细（`passed=1` 的 3 条 + `EVENT_ID_UNIQUE` 1 条），与质量门结论不是同一口径，仍成立。

验证：`mvn -o -pl metric-analysis,warehouse-pipeline,platform-app -am -DskipTests test-compile` ⇒ **BUILD SUCCESS / exit 0**。
**运行期未验证**（本轮禁跑 `-Dmetric.it=true`）。

### 7.4 同类错误已一并更正

同一处错误结论还散落在盘点文档里，已就地更正并留痕：

- `INVENTORY.md` §4 表格 `AnalysisGoldenMySqlIT` 行：原写「**已改**为 `FAIL`」，但**实际最终态是 `PASS`** ⇒ 表述错误，已注明「本条曾被改错，第三次才纠正」。
- `INVENTORY.md` §6 待裁-3：原写「**当前 ACTIVE 快照 `S20260901_47` 的结论不变（仍 PASS）**… 本次改动**不会**让当前线上看板的质量卡片变红」⇒ **已证伪**。实测 run 47 同样超阈值（~40 倍 / ~143 倍）⇒ **ACTIVE 快照结论也翻为 FAIL，线上质量卡会变红**。该后果已升级为 **待裁-11**，须总控裁决。
- `INVENTORY.md` §3.2：原写「把 dqc 的两条 `ERROR` 改判 `WARN` **不改变作业自身行为**，只改变落库标签与平台判定」⇒ 该说法**只在未超阈值时成立**，已加更正块。

> **教训**：一个结论被「二次勘误」覆盖后，如果只改 javadoc 而不改**测试断言与派生文档**，就会留下
> 「文档说 FAIL、代码断 PASS」的分裂状态，而且**测试全绿**（因为该 IT 默认跳过）——缺陷被 skip 掩盖。
> ⇒ 凡涉及「期望值翻转」的更正，必须同时 grep 全部派生文档里的同一结论。
