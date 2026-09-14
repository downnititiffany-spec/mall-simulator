# F88 质量门严重度口径 —— 测试锁死清单（inventory）

- 泳道：`f88-dq-severity-20260912`
- 性质：**静态清单**。只读文件、只记行号；未运行任何测试/编译。
- 权威口径来源：`docs/acceptance/m3-step8-parity-20260912/RULINGS-D142-20260912.md:7-22`（§1 选 A）；
  旧口径（代码现状）＝**只有 `BLOCKING` 阻断**；新口径（人裁决）＝**`BLOCKING` 与 `ERROR` 失败都阻断，`WARN`/`INFO` 记录并展示不阻断**
  （同文件 `:13-14` 的语义表）。
- 配套原始证据：`docs/acceptance/f88-dq-severity-20260912/raw/test-lockin-evidence.txt`
  （该文件含 **两遍** 捕获：第一遍 `21:58:58`，权威快照 `22:02:10`——原因见下）

---

## ★ 必须先读：生产代码在本任务进行期间被改了（本清单的时点事实）

本清单的"现状"以证据文件 **★ 权威快照（`22:02:10`）** 为准。理由：

| 时点 | 事实 |
|---|---|
| 09-12 21:58:58 | 第一遍证据捕获。当时 `DataQualityGate.java` 只有 42 行、内容是 `"BLOCKING".equalsIgnoreCase(...)`，即**纯旧口径** |
| 09-12 22:00:49 | 新增 `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\RuleSeverity.java`（149 行，严重度**唯一所有者**） |
| 09-12 22:01:02 | `DataQualityGate.java` 被改写为 72 行，改用 `RuleSeverity.blocks(...)` |
| 09-12 22:01:30 | `QualityChecker.java` 被改写，`corePassed` 改用 `anyBlockingFailed(...)` |
| 09-12 22:02:10 | 六条命令在**当前树**上重跑（权威快照） |

**测试侧文件在本任务期间一个都没动**，最后修改时间均为 `2026-09-10` / `2026-09-11`
（逐文件时间戳见证据文件 `:540-552`）。因此当前状态是：

> **生产侧已改到新口径（且有 1 处未改完），测试侧仍全部固定在旧口径。**
> 本清单 A 节列的断言，今天已不再是"锁死未来"，而是**当前真实会失败**的断言。

- 差异行号已逐条重读确认；凡不能确认处一律写 `未确认`。
- 文件名以下均相对仓库根 `D:\Develop_code\GraduationProject\`。

---

## A. 锁死旧口径的测试清单

口径：该测试/断言的**结论**依赖"`ERROR` 失败不阻断"或"仅 `BLOCKING` 阻断"。
"新口径下会变成什么"只描述事实差异，**不含任何改码建议**。

### A1. 当前确定会失败（新旧口径结论相反）

| 测试类#方法名 | file:line（断言行） | 现在断言什么（引用原文片段） | 新口径下会变成什么 |
|---|---|---|---|
| `DataQualityGateTest#nonBlockingFailureKeepsPass` | `analytics-server\warehouse-pipeline\src\test\java\com\graduation\analytics\pipeline\DataQualityGateTest.java:49` | `assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.PASS);`<br>输入（`:46-47`）＝`result("ERROR", 0), result("INFO", 0), result("BLOCKING", 1)` | 同一输入下生产代码（`DataQualityGate.java:37,66`）算出 `FAIL`：`ERROR` 未通过 = 阻断失败。断言的期望值必须是 `FAIL`，故该断言失败。**注意**：该 run 的 `INFO` 未通过在新口径下**仍然不阻断**，翻转完全由 `ERROR` 那一行引起 |
| `DataQualityGateTest#nonBlockingFailureKeepsPass` | `DataQualityGateTest.java:44` | `@DisplayName("只有非阻断规则失败（ERROR/INFO）→ PASS，不误报为 FAIL")` | 该标题把 `ERROR` 归入"非阻断规则"，与人裁决 `RULINGS-D142-20260912.md:13-14`（`ERROR` 阻断）直接冲突；标题所述结论不再成立 |
| `DataQualityGateTest#nonBlockingFailureKeepsPass`（类级注释） | `DataQualityGateTest.java:23` | `判定放宽（例如把 ERROR 也当阻断、或把"查不到"当 PASS）会让页面显示错误的质量结论（§16.3/§18.3）。` | 该注释把"把 `ERROR` 当阻断"定义为**判定放宽/错误结论**，与人裁决把 `ERROR` 当阻断定为**正确口径**相反；注释所述判据已反转 |
| `JobResultParserTest#parsesQualityChecksAndBlockingFailures` | `analytics-server\warehouse-pipeline\src\test\java\com\graduation\analytics\pipeline\spark\JobResultParserTest.java:60` | `assertThat(r.checks().get(1).blocking()).isFalse(); // ERROR 不阻断发布` | `CheckInfo.blocking()`（`JobResultParser.java:37-39`）当前仍只认 `BLOCKING`，故该断言**暂时仍通过**；但注释所依据的结论"`ERROR` 不阻断发布"已被 `RULINGS-D142-20260912.md:13` 推翻并已在 `RuleSeverity.java:134-143` 落地为相反口径。这是**唯一把"ERROR 不阻断"写成断言载体**的位置 |
| `JobResultParserTest#parsesQualityChecksAndBlockingFailures` | `JobResultParserTest.java:63-64` | `assertThat(r.blockingFailures()).extracting(...).containsExactly("ADS_STAGING_PRESENT", "LEGACY_NO_SEVERITY");` | fixture 第 2 条（`:48-49`）是 `"severity":"ERROR","passed":false`。若修正按**回传 severity 字面量**判阻断（与 `JobResultParser.java:144` 的"缺 severity 按 BLOCKING"同一保守原则），该列表会多出 `"PUB_DQ_EVENT_ID_UNIQUE"` 而断言失败。若修正走 `RuleSeverity.of("PUB_DQ_EVENT_ID_UNIQUE")`（`RuleSeverity.java:65` 判为 `WARN`），则该列表不变、断言通过。**两种修法结论不同，此处不做预测**；可确定的是该断言是按"只有 `BLOCKING` 算阻断失败"写死的 |
| `JobResultParserTest#parsesQualityChecksAndBlockingFailures` | `JobResultParserTest.java:62` | `// 只有 BLOCKING 且未通过的算阻断失败；PUB_DQ_EVENT_ID_UNIQUE 是观察项` | 与 `RuleSeverity.java:65`（`PUB_DQ_EVENT_ID_UNIQUE` → `WARN`）一致，但"只有 `BLOCKING` 算阻断"这一判据本身已被替换为"`BLOCKING`/`ERROR` 都算" |
| `QualityCheckerAmountReconcileTest#twoArgOverloadKeepsPreviousSemantics` | `analytics-server\warehouse-pipeline\src\test\java\com\graduation\analytics\pipeline\QualityCheckerAmountReconcileTest.java:84-85` | `assertThat(amountRule(checker.check(List.of(paid("O3", "58.00")), 35L).results()).getPassed()).isEqualTo(0);` | 旧口径下该输入唯一未通过规则是 `EVENT_ID_UNIQUE`（切片只有 1 条 `order_paid`，无 `order_created`），而 `EVENT_ID_UNIQUE` 曾被列为 `ERROR` 记录项，故 `AMOUNT_RECONCILE` 失败、`getPassed()==0`。新口径下 `EVENT_ID_UNIQUE` 经 `RuleSeverity.of`（`RuleSeverity.java:65`）归为 **`WARN`**、且该批 `dup==0` 本即 `passed=1`；`AMOUNT_RECONCILE` 仍为 `BLOCKING` 但 `checkCount==0`（无 `order_paid`）故 `errors==0`、`passed=1`（`QualityChecker.java:70-71`）⇒ `getPassed()` 变为 `1`，断言失败。同方法 `:83` 的 `isEqualTo(1)` 不受影响 |
| `QualityCheckerAmountReconcileTest#orphanPaymentStillBlocks` | `QualityCheckerAmountReconcileTest.java:56` | `assertThat(summary.corePassed()).isFalse();` | 输入为单条孤儿支付（`:49`），断言依据是"金额对账失败 ⇒ corePassed=false"（`QualityChecker.java:73-75` 旧实现的 `errors > 0 ⇒ corePassed = false`）。新实现按**每规则严重度**判定（`QualityChecker.java:115,126-129` = `anyBlockingFailed`）：`AMOUNT_RECONCILE` 仍 `BLOCKING`，但该批 `checkCount==0` ⇒ `errors==0` ⇒ `passed=1`（`QualityChecker.java:70-71`），其余规则亦 `passed=1` ⇒ `corePassed()` 变为 `true`，断言失败。`:54-55` 的 `getErrorCount()==1`、`getPassed()==0` 同样失败 |
| `QualityCheckerAmountReconcileTest#amountMismatchStillFails` | `QualityCheckerAmountReconcileTest.java:71` | `assertThat(summary.corePassed()).isFalse();` | 同上：输入为单条 `order_paid`（`:63`），`AMOUNT_RECONCILE` 的 `checkCount` 只统计切片内 `order_paid`（`QualityChecker.java:52-58,70`）；新口径下该规则 `passed=1` ⇒ `corePassed()` 变为 `true`，断言失败。`:69-70` 同样失败 |

### A2. 依赖旧口径但与新口径"恰好同结论"（不断言 `ERROR` 不阻断，但按旧判据书写）

| 测试类#方法名 | file:line（断言行） | 现在断言什么（引用原文片段） | 新口径下会变成什么 |
|---|---|---|---|
| `JobResultParserTest#parsesQualityChecksAndBlockingFailures` | `JobResultParserTest.java:59` | `assertThat(r.checks().get(0).blocking()).isTrue();` | fixture 该条是 `"severity":"BLOCKING"`（`:47`），新口径下仍为阻断 ⇒ **结论不变**；但 `blocking()` 在旧口径里语义是"是否阻断发布"，在新口径里必须收窄为"是否硬门"，含义已变 |
| `JobResultParserTest#parsesQualityChecksAndBlockingFailures` | `JobResultParserTest.java:61` | `assertThat(r.checks().get(2).severity()).isEqualTo("BLOCKING"); // 缺省保守判定` | 缺 `severity` 默认 `BLOCKING`（`JobResultParser.java:158`，注释见 `:144`）；`BLOCKING` 在新口径下仍阻断 ⇒ **结论不变** |
| `DataQualityGateTest#blockingFailureFailsTheGate` | `DataQualityGateTest.java:40` | `assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.FAIL);` | fixture（`:38`）已含 `result("BLOCKING", 0)` ⇒ 新旧口径都是 `FAIL` ⇒ **结论不变**；但该 fixture 同时含 `result("ERROR", 0)`，旧口径下这行是"陪衬"，新口径下它**独立也足以判 FAIL**，测试的因果解释已变 |
| `DataQualityGateTest#allRulesPassed` | `DataQualityGateTest.java:57` | `assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.PASS);` | fixture（`:55`）为 `result("BLOCKING", 1), result("ERROR", 1)`，无未通过项 ⇒ **结论不变** |
| `DataQualityGateTest#nullPassedOnBlockingIsTreatedAsFailed` | `DataQualityGateTest.java:65` | `assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.FAIL);` | `BLOCKING` + `passed=null`；`RuleSeverity.failed`（`RuleSeverity.java:146-148`）把 `null` 视为未通过 ⇒ **结论不变** |
| `DataQualityGateTest#missingResultsAreUnknown` | `DataQualityGateTest.java:72`、`:75` | `assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.UNKNOWN);` | 与严重度无关 ⇒ **不受影响** |
| `DataQualityGateTest#nullRunIdIsUnknown` | `DataQualityGateTest.java:81` | `assertThat(gate.statusForRun(null)).isEqualTo(MetricQualityGate.UNKNOWN);` | 与严重度无关 ⇒ **不受影响** |
| `QualityCheckerAmountReconcileTest#crossDayPaymentReconcilesWithBatchWideIndex` | `QualityCheckerAmountReconcileTest.java:40-43` | `getCheckCount()==1` / `getErrorCount()==0` / `getPassed()==1` / `getDetail()` 含 `"整批无订单 0 笔"` | 该案例对账本就通过 ⇒ 新旧口径都 `passed=1` ⇒ **结论不变**。注意该 fixture 含 1 条 `order_paid`（`:33`），新口径下 `EVENT_ID_UNIQUE` 归 `WARN`、不再是阻断项，但这不改变本案例的任何断言 |

---

## B. 会被新口径连带影响的测试/夹具

这些测试**不直接断言严重度语义**，但其 fixture／期望值把旧结论焊了进去。

| 测试类#方法名 | file:line | 现在断言什么（引用原文片段） | 新口径下会变成什么 |
|---|---|---|---|
| `AnalysisGoldenMySqlIT#overviewMatchesGoldenValues` | `analytics-server\metric-analysis\src\test\java\com\graduation\analytics\analysis\AnalysisGoldenMySqlIT.java:75` | `assertThat(model.qualityStatus()).isEqualTo(MetricQualityGate.PASS);` | `qualityStatus` 由该类**私有的** `MetaQualityGate`（`:219`）计算，其判定体（`:237-240`）仍是 `BLOCKING.equalsIgnoreCase(String.valueOf(row.get("severity"))) && passed != 1`——**纯旧口径**。该断言今天仍为绿，**只因为复刻体还没跟着改**；库里是否真有"`ERROR` 且 `passed<>1`"的行已由证据 `db-d:44-77` 的 D4 段**查实**（如 `run 24 → error_failed=3`、`db-h:15,18,23` 的 run 47 逐行 tally）。⇒ **同一份库里，生产实现 `DataQualityGate.java:66` 会判 `FAIL`、此测试的复刻体会判 `PASS`**：新口径落地后该断言失败。备注：该 IT 连的是 **`127.0.0.1:3306`**（`:247`），不是生产库 `192.168.100.128`（`db-d` 等由 `mysql` CLI 取数的证据）；本机库内容是否与 `db-d` 同构**未确认**，故"今天是否立刻失败"仍未确认——但"复刻体与生产实现必然分叉"这一点已确认 |
| 同上（连带根因） | `AnalysisGoldenMySqlIT.java:219`、`:237-240` | 私有 `private static final class MetaQualityGate implements MetricQualityGate {` ＋ `boolean blockingFailed = rows.stream().anyMatch(row -> BLOCKING.equalsIgnoreCase(...) && !(row.get("passed") instanceof Number number && number.intValue() == 1));` | 这是**测试侧复刻**的生产判定。生产实现 `DataQualityGate.java:66` 已改为 `RuleSeverity.blocks(...)`，此复刻**不会被连带修正**，会与生产结论分叉（同一 run 在生产判 `FAIL`、在此 IT 判 `PASS`）。已在 `AnalysisGoldenMySqlIT.java:63` 注入：`new MetaQualityGate(meta)` |
| `PipelineServiceTest#adsQualityGateFailureBlocksFormalPartitionPublish`（夹具 `qualityBlockedExecution`） | `analytics-server\warehouse-pipeline\src\test\java\com\graduation\analytics\pipeline\PipelineServiceTest.java:190-192` | `new JobResultParser.CheckInfo("PUB_DQ_EVENT_ID_UNIQUE", "PUBLISH", "dw_ads.ads_data_quality__staging", 51L, 3L, "0.0005", "ERROR", false, "观察项"));` | 夹具把一条 `severity="ERROR"`、`passed=false` 的检查标为"观察项"（`detail="观察项"`），即旧口径下的非阻断项。新口径下 `ERROR` 失败即阻断 ⇒ 该夹具的语义标注与口径冲突。**但该测试的断言不受影响**：`PipelineService` 不含 `DataQualityGate` 字段（在该文件中检索 `qualityGate`/`DataQualityGate`/`blockingFailuresForRun` **0 命中**），且 `service` 构造（`PipelineServiceTest.java:138-140`）未传质量门，`qualityMapper.selectList` 亦未打桩 ⇒ 本夹具不流经严重度判定 |
| `PipelineServiceTest`（夹具 `checksFor`） | `PipelineServiceTest.java:176-181` | `new JobResultParser.CheckInfo("ADS_STAGING_PRESENT", ..., "BLOCKING", true, ...)` ＋ `new JobResultParser.CheckInfo("PUB_STAGING_PRUNE", "PUBLISH", "staging", 0L, 0L, "保留被引用快照", "INFO", true, "无待清理历史暂存分区"));` | 两行 fixture 的严重度**结论本身**正确（`INFO` 不阻断）⇒ 无需改；仅"dqc 通过时的检查结果"这一注释（`:171`）需与新口径复核 |
| `AnalysisServiceTest#overviewFillsEnvelopeAndSections` | `analytics-server\metric-analysis\src\test\java\com\graduation\analytics\analysis\AnalysisServiceTest.java:104` | `assertThat(model.qualityStatus()).isEqualTo("PASS");` | 该测试的 `MetricQualityGate` 是 mock（`AnalysisServiceTest.java:293` 只设置快照 `STATUS_ACTIVE`），`qualityStatus` 取自桩定返回值 ⇒ 与严重度口径无关，**不受影响** |
| `AnalysisServiceTest`（ADS 质量 fixture） | `AnalysisServiceTest.java:368-372` | `row("dt","20260901","rule_code","EVENT_ID_UNIQUE","passed",0)` | 这是 ADS 宽表 `ads_data_quality_m` 的规则明细 fixture，`failedRules()` 由"`passed=0`"计算、与 severity 无关；`:134` 的 `containsExactly("EVENT_ID_UNIQUE")` ⇒ **不受影响** |
| `MetricPublishValidatorTest`（全部 12 个方法） | `analytics-server\metric-analysis\src\test\java\com\graduation\analytics\metric\publish\MetricPublishValidatorTest.java:46`、`:50-52`、`:60-62`、`:70`、`:78`、`:125`、`:141` 等 | `assertThat(MetricPublishValidator.blocked(checks)).isFalse();` / `assertThat(MetricPublishValidator.failedRules(checks)).contains("MP_...");` | 该套件**未发现**任何断言 `ERROR` 不阻断的用例（见 D 节）。当前"恰好等价"的原因：`failedRules()` 只回 `BLOCKING` 未过项（`MetricPublishValidator.java:229-231`），而该套件期望的所有失败规则在 fixture 里都由 `"BLOCKING"` 构造函数生成（`MetricPublishValidator.java:213-221`）；唯一 `INFO` 规则 `MP_OLD_ACTIVE_ARCHIVED` 不在任何断言里。⇒ **当前不失败，但结论建立在一个只认 `BLOCKING` 的辅助方法上**，属"未锁死但不设防" |

---

## C. 生产代码中实现旧口径的位置

以下每行都编码或文档化了"**只有 `BLOCKING` 阻断 / `ERROR` 只记录**"。按模块分组，**含注释行**。

### C1. `analytics-server`（Java，在任务明示范围内）—— **仍是旧口径的 4 个文件 / 10 处**

| file:line | 代码片段 |
|---|---|
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\spark\JobResultParser.java:37-39` | `public boolean blocking() {` / `    return "BLOCKING".equalsIgnoreCase(severity);` / `}` |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\spark\JobResultParser.java:32` | `* severity=BLOCKING 的规则未通过即发布阻断（§16.3）；ERROR 只记录。`（**注释，旧口径原文**） |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\spark\JobResultParser.java:52-55` | `/** 严重度为 BLOCKING 且未通过的规则（作业失败时用于定位阻断原因） */` / `public List<CheckInfo> blockingFailures() {` / `    return checks.stream().filter(c -> c.blocking() && !c.passed()).toList();` / `}` |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\PipelineService.java:778-779` | `item.put("blockingFailed", j.checks().stream()` / `        .filter(c -> c.blocking() && !c.passed()).map(JobResultParser.CheckInfo::ruleCode).toList());` |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\PipelineService.java:682` | `r.setSeverity("AMOUNT_RECONCILE".equals(String.valueOf(r.getRuleCode())) ? "BLOCKING" : "ERROR");`（内联映射，**未走** `RuleSeverity.of`；把其余 3 条规则一律写成 `ERROR`） |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\PipelineService.java:673-676` | `/**` / ` * R6-13：落 Landing 层内联质量规则结果…` / ` * 严重度按规则语义标注：AMOUNT_RECONCILE 为阻断项，其余为记录项（与 QualityChecker.corePassed 一致）。` / ` */`（**注释，旧口径原文**；所引 `corePassed` 已于 `22:01:30` 改为新口径，注释已失配） |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\PipelineService.java:697-698` | `* R6-13：落 Spark 作业回传的质量检查结果（dqc 的 ADS_STAGING/PUBLISH 层规则、pub 的发布校验）。` / `* severity=INFO 的是发布操作审计项（切换/清理计数），只进阶段证据，不冒充质量规则写库。` |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\PipelineService.java:705-707` | `if ("INFO".equalsIgnoreCase(c.severity())) {` / `    continue;` / `}`（按字面量丢弃 `INFO` 检查，不经 `RuleSeverity` 归一） |
| `analytics-server\metric-analysis\src\main\java\com\graduation\analytics\metric\publish\MetricPublishValidator.java:223-226` | `/** 是否有 BLOCKING 未通过 */` / `public static boolean blocked(List<Check> checks) {` / `    return checks.stream().anyMatch(c -> "BLOCKING".equals(c.severity()) && !c.passed());` / `}` |
| `analytics-server\metric-analysis\src\main\java\com\graduation\analytics\metric\publish\MetricPublishValidator.java:228-232` | `/** 未通过的规则码 */` / `public static String failedRules(List<Check> checks) {` / `    return checks.stream().filter(c -> "BLOCKING".equals(c.severity()) && !c.passed())` / `            .map(Check::ruleCode).reduce((a, b) -> a + "," + b).orElse("");` / `}` |
| `analytics-server\metric-analysis\src\main\java\com\graduation\analytics\metric\publish\MetricPublishValidator.java:23` | `* <p>凡是 BLOCKING 不通过：不得写指标值、不得切 ACTIVE 指针，旧 ACTIVE 必须保持可用。</p>`（**注释，旧口径原文**） |
| `analytics-server\metric-analysis\src\main\java\com\graduation\analytics\metric\publish\MetricPublisher.java:103`、`:161`、`:198` | `if (MetricPublishValidator.blocked(checks)) {`（3 处调用点，语义随 `blocked()` 口径走）；`:105`、`:162`、`:165`、`:201`、`:203` 使用 `failedRules(checks)` |

> 说明：`MetricPublishValidator.blocked()`/`failedRules()` 用**大小写敏感的** `equals`，而 `RuleSeverity.blocks`（`RuleSeverity.java:134-143`）是大小写不敏感且 `null`/空/未知一律按阻断。两者对 `null`／空串／小写 `"blocking"` 的判定不一致——这是事实差异，此处只记录。

### C2. `analytics-server` 中**已完成**新口径改造的位置（供对照，不是遗留）

| file:line | 代码片段 |
|---|---|
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\RuleSeverity.java:19` | `public final class RuleSeverity {`（严重度唯一所有者，149 行） |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\RuleSeverity.java:134-143` | `public static boolean blocks(String severity) {` … `return !WARN.equals(s) && !INFO.equals(s);`（等价于"`BLOCKING`/`ERROR` 阻断"） |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\RuleSeverity.java:146-148` | `public static boolean failed(Integer passed) {` / `    return passed == null \|\| passed != 1;` / `}` |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\DataQualityGate.java:66` | `.filter(r -> RuleSeverity.blocks(r.getSeverity()) && RuleSeverity.failed(r.getPassed()))` |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\QualityChecker.java:115` | `boolean corePassed = !anyBlockingFailed(results);` |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\QualityChecker.java:126-129` | `static boolean anyBlockingFailed(List<DataQualityResult> results) {` … `RuleSeverity.blocks(r.getSeverity()) && RuleSeverity.failed(r.getPassed()));` |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\QualityChecker.java:137` | `r.setSeverity(RuleSeverity.of(code));` |
| `analytics-server\warehouse-pipeline\src\main\java\com\graduation\analytics\pipeline\DataQualityGate.java:19-23` | 新口径注释：`<b>{@code BLOCKING} 与 {@code ERROR} 未通过都算阻断</b>` … |

**待接线（已定义、尚无调用方）**：
`DataQualityGate.blockingFailuresForRun(Long)`（`DataQualityGate.java:47-49`）的 javadoc 声明用于
"发布前门断言（见 `PipelineService` 的 PUBLISH_METRIC 前置检查）"，但 `PipelineService.java` 中检索
`qualityGate` / `DataQualityGate` / `blockingFailuresForRun` **0 命中**；`PipelineServiceTest`（`PipelineServiceTest.java:138-140`）
构造 `PipelineService` 时也未注入质量门。⇒ 该"发布前阻断级断言"在编排侧目前**不存在**，`blockingFailuresForRun` 是**未接线的死代码**。

### C3. `spark-jobs`（Scala）—— **范围外补充**，但它是 `ERROR` 的源头，且**一行未改**

> 任务把范围限定为 `analytics-server`。以下为执行"blocking 调用点"检索时发现的事实，**明确标注为范围外**，不计入 C1 的遗留数，也不声称已穷尽全部 Scala 侧位置。

| file:line | 代码片段 |
|---|---|
| `spark-jobs\src\main\scala\com\graduation\analytics\job\JobResult.scala:17-18` | `* severity=BLOCKING 的规则不通过时作业必须 FAILED（不得 catch 后继续，§16 末尾）；` / `* severity=ERROR 只记录不阻断（如 gold 夹具中的重复 event_id 观察项）。`（**注释，旧口径原文**） |
| `spark-jobs\src\main\scala\com\graduation\analytics\job\AdsQualityJob.scala:132` | `val blockingFailed = all.filter(c => c.severity == "BLOCKING" && !c.passed)` |
| `spark-jobs\src\main\scala\com\graduation\analytics\job\AdsQualityJob.scala:107-110` | `byRule.get("EVENT_ID_UNIQUE").foreach { r =>` / `  checks += QualityCheck("PUB_DQ_EVENT_ID_UNIQUE", "PUBLISH", dqTable,` / `    r.getLong(1), r.getLong(2), "0.0005", "ERROR", r.getInt(3) == 1,` / `    "观察项：重复 event_id 比率，不阻断发布")`（**`ERROR` 字面量 + 旧口径注释的源头**） |
| `spark-jobs\src\main\scala\com\graduation\analytics\job\AdsQualityJob.scala:57-61` | `// 陈旧暂存分区**不可能**污染正式分区；若把它设为 BLOCKING，则清理只发生在 pub（发布成功后）而` / `parts.size.max(staging.size), foreign.size, "0 个非本次快照分区（观察项）", "ERROR", foreign.isEmpty,`（`ADS_STAGING_SNAPSHOT_ISOLATION` 写成 `ERROR`；`RuleSeverity.java:73` 已改判 `WARN`） |
| `spark-jobs\src\main\scala\com\graduation\analytics\job\AdsPublishJob.scala:118` | `val blockingFailed = all.filter(c => c.severity == "BLOCKING" && !c.passed)` |
| `spark-jobs\src\main\scala\com\graduation\analytics\job\MetricExportJob.scala:87`、`:89`、`:111` | `val exportFailed = checks.exists(c => c.severity == "BLOCKING" && !c.passed)`；`checks.count(c => c.severity == "BLOCKING" && !c.passed)`；`s"ADS 导出校验失败: ${checks.filter(c => c.severity == "BLOCKING" && !c.passed)...` |

**已确认的 `ERROR` 来源（severity 字面量计数，权威快照）**：`"WARN"` = **0 次**；
`"ERROR"` 出现在 `JobResult.scala`（注释）、`AdsQualityJob.scala` ×2（`:61`、`:109`）。
⇒ 全仓**没有任何一行把规则标成 `WARN`**，而 `RuleSeverity.java:65` 与 `:73` 已把两条规则改判为 `WARN`。

### C4. 测试侧复刻的判定（旧口径）

| file:line | 代码片段 |
|---|---|
| `analytics-server\metric-analysis\src\test\java\com\graduation\analytics\analysis\AnalysisGoldenMySqlIT.java:219` | `private static final class MetaQualityGate implements MetricQualityGate {`（`MetricQualityGate` 的全仓实现者检索共 2 处：生产 `DataQualityGate.java:27` + 此测试私有类） |
| `analytics-server\metric-analysis\src\test\java\com\graduation\analytics\analysis\AnalysisGoldenMySqlIT.java:237-240` | `boolean blockingFailed = rows.stream().anyMatch(row ->` / `        BLOCKING.equalsIgnoreCase(String.valueOf(row.get("severity")))` / `                && !(row.get("passed") instanceof Number number && number.intValue() == 1));` / `return blockingFailed ? FAIL : PASS;`（**纯旧口径**） |
| `analytics-server\platform-common\src\main\java\com\graduation\analytics\metric\MetricQualityGate.java:11-13` | `* <p>判定口径（不得臆造）：取该快照 {@code pipeline_run_id} 对应的质量结果，` / `* 有 severity=BLOCKING 且未通过 → {@link #FAIL}；有结果且无阻断失败 → {@link #PASS}；` / `* 查不到结果或没有 run 号 → {@link #UNKNOWN}。</p>`（**接口注释仍写旧口径**；`:26-27` 的常量注释 `/** 阻断发布的严重度（§16.3） */ String BLOCKING = "BLOCKING";` 同样只提 `BLOCKING`） |

---

## D. 未发现项

以下均为**已检索但零命中**，用于证明检索是穷尽的（原始输出见证据文件 `:532` 起的权威快照）：

1. **未找到任何断言 `WARN` 不阻断的测试。** 全仓 `*.java` + `*.scala`（排除 `target\`）中，severity 取值字面量 `"WARN"` 出现 **0 次**——既无生产代码产出 `WARN`，也无测试断言 `WARN` 行为。当前代码库 **0 处 `WARN`**（唯一出现 `WARN` 标识符的是 `RuleSeverity.java:28,142` 的常量定义与判定，以及 `spark-jobs\...\SparkSessionFactory.scala:24` 的 `setLogLevel("WARN")`，两者都**不是**质量规则 severity）。
2. **未找到"`WARN` 失败仍放行"的用例**，原因同上：不存在 `WARN` 输入。
3. **未找到 `DataQualityGateTest` 中任何以 `ERROR` 为主角、断言其不阻断的独立测试。** 该类共 6 个 `@Test`（`:36`、`:45`、`:54`、`:62`、`:70`、`:80`），其中只有 `:45-50` 把 `ERROR` 作为"非阻断"输入，且与 `INFO` 混合在同一 fixture 里。
4. **未找到 D-142 §1.1 第 5 条要求的负向测试。** 人裁决 `RULINGS-D142-20260912.md:30` 要求"必须补负向测试：`ERROR` 失败 ⇒ 不发布新快照 ⇒ 原 `ACTIVE` 不变且可读"；以 `负向`／`原 ACTIVE`／`ACTIVE 不变`／`不发布新快照` 检索 `analytics-server` ＋ `spark-jobs\src`（`*.java`＋`*.scala`）**零命中** ⇒ 该负向测试**尚未实现**。
5. **未找到 `RuleSeverity` 的任何测试。** 以 `RuleSeverity` 检索 `analytics-server` 全部 `*.java`，命中 **7 处全部位于 `src\main`**（`DataQualityGate.java:21,66`、`QualityChecker.java:121,128,136,137`、`RuleSeverity.java:19,39`），`src\test` **0 命中** ⇒ 新增的严重度唯一所有者**无任何单测覆盖**，`blocks(null)`/`blocks("")`/`blocks("blocking")`/`of(未登记码)` 等保守默认分支均未被验证。
6. **未找到 `MetricPublishValidatorTest` 中任何锁死严重度口径的断言。** 该套件对 `blocked()`/`failedRules()` 的调用只覆盖 `"BLOCKING"` 构造出的失败项；唯一 `INFO` 规则 `MP_OLD_ACTIVE_ARCHIVED` **未出现在任何断言中**（`:46`、`:50-52`、`:60-62`、`:70`、`:78`、`:125`、`:141` 处均无）。⇒ 该套件**不属于 A 节**，仅在 B 节记为"不设防"。
7. **未找到除 `MP_OLD_ACTIVE_ARCHIVED` 以外的非 `BLOCKING` 严重度**：`MetricPublishValidator` 的两个私有构造器（`:213-216`、`:218-221`）都把 severity 硬编码为 `"BLOCKING"`；`MP_OLD_ACTIVE_ARCHIVED` 是**唯一**以 `INFO` 构造的规则（`:214-215` 行内 `"INFO"` 字面量），且它不参与任何断言。
8. **未找到 `PipelineService` 中任何发布前的质量门断言**：检索 `qualityGate`／`DataQualityGate`／`blockingFailuresForRun` 于 `PipelineService.java` **0 命中**（见 C2）。
9. **未确认（无法静态确认，非"零命中"）**：
   - `AnalysisGoldenMySqlIT` 连的是 **`127.0.0.1:3306`**（`AnalysisGoldenMySqlIT.java:247`），不是 `db-*` 证据取数所用的库；**本机库内是否同构未确认**——但同一目录 `db-d:44-77` 的 D4 明细列出的正是 `analysis` 库所在的库集合，二者是否同一实例**未确认**。已确认的部分：该 IT 断言的 run 是硬编码的 `S20260901_24`（`:47`）即 **24**，而 `db-d:64` 记 `run_id=24 → blocking_failed=0, error_failed=3`、`db-d:29` 记 `run 24` 的失败 `ERROR` 规则为 `ADS_STAGING_SNAPSHOT_ISOLATION,EVENT_ID_UNIQUE,PUB_DQ_EVENT_ID_UNIQUE`。若本机库同构，则该 IT 在真门（`DataQualityGate.java:66`）下必然得 `FAIL`，与 `:75` 的 `PASS` 冲突。
   - `JobResultParser` 的修正路径（按回传 severity 字面量，还是按 `RuleSeverity.of` 归一）⇒ `JobResultParserTest.java:63-64` 是否失败**未确认**（两种修法结论不同，见 A1）。
   - `QualityCheckerAmountReconcileTest` 三条方法失败的确切首行断言**未确认**（同一方法内 `:54-56`／`:69-71`／`:84-85` 多行会同时失败；本清单只断言"该断言与旧口径不符"，不断言失败顺序）。
10. **未检索/未覆盖**：本清单**未**检索 `docs\backups\*\` 下的历史副本（检索时已显式排除）；该目录存在 `PipelineService.java` 等旧快照（如 `docs\backups\m1-4-s3b-def03-def04-20260911-1420\src\PipelineService.java:779`），**不计入** C 节遗留项。
11. **★ 检索中发现、但不属于"锁死测试"的一项事实（列出以免遗漏）**：**当前正在服务的 `ACTIVE` 快照本身就带着失败的 `ERROR` 行**。
    证据（均非本清单产生，取自同目录既有取数文件）：
    - `db-d:13` —— `metric_snapshot` 中 `status='ACTIVE'` 的唯一一行是 `S20260907_11` / `pipeline_run_id=11`；`db-d:17-18` 计数 `ACTIVE=1, ARCHIVED=8`。
    - `db-i:5`（run 24 的 `QUALITY_CHECK` 阶段证据）与 `db-i:9`（run 47 同项）显示两次运行的 `landingRules` 里 `EVENT_ID_UNIQUE` 均 `"passed":0`，而 `landingCorePassed` 均为 `true`、`blocking` 一栏仍写 `"AMOUNT_RECONCILE（支付金额 vs 订单总额）"`——即**旧口径下"记录项失败不阻断"的现场留痕**。
    - `db-d:29` / `db-d:64` —— run 24 有 3 条 `ERROR` 且未通过（`ADS_STAGING_SNAPSHOT_ISOLATION`、`EVENT_ID_UNIQUE`、`PUB_DQ_EVENT_ID_UNIQUE`），而旧口径 `blocking_failed=0`。
    - `db-h:15,18,23` —— run 47 逐行 tally：`ADS_STAGING_SNAPSHOT_ISOLATION`(ERROR, passed 0/1)、`EVENT_ID_UNIQUE`(ERROR, 0/1)、`PUB_DQ_EVENT_ID_UNIQUE`(ERROR, 0/1)。
    - `db-h:28-30` —— "runs with a WARN row (should be 0 today)" ⇒ `warn_rows = 0`，与 D 节第 1 条的静态计数（`"WARN"` 字面量 0 次）**互相印证**。
    本清单的读法：`RULINGS-D142-20260912.md:26` 的 §1.1 第 1 条（"**新运行失败 ⇒ 不改变原 `ACTIVE`**"）是**面向未来**的——它约束的是"要不要切指针"，**不是**"历史 run 的 `ERROR` 行要不要追溯"。因此上面这些失败 `ERROR` 行**不等于**已发布的 `ACTIVE` 快照失效。
    > 注：`db-d` 与 `db-c` 中 `metric_snapshot` 全表 dump 落在 `db-c:23-24` 之后（该文件在 C2 段被 `business_date` 列报错截断），其 `ACTIVE` 行是 `S20260907_11`/run 11，而 `db-i` 的 `QUALITY_CHECK` 证据与 `AnalysisGoldenMySqlIT.java:47` 的硬编码 `SID` 都指向 run 24 附近的运行。两处取数**是否同一库/同一时点未确认**；本清单不对"哪个才是当前 `ACTIVE`"作断言，只声明两家证据各自的内容。
    但**读侧会被连带改变**：`AnalysisService.java:318-320`（`qualityStatus` 调 `qualityGate.statusForRun(meta.getPipelineRunId())`）是在**读取时**现算结论，`DataQualityGate.java:66` 改为新口径后，**当前 ACTIVE 快照对应 run 的 `qualityStatus` 会变成 `FAIL`**——看板"质量"卡片会由"通过"变"失败"，而 §1.1 第 1 条并未要求这一变化。
    **这是本清单观察到的、范围上比"测试锁死"更靠前的一个事实**，此处只作记录，不判定其应否发生。

---

## E. 未测声明

1. **本次未运行任何测试、未编译、未运行 Maven**（未执行 `mvn`／`mvn test`／`mvn -pl ... test`／`surefire`／`scalatest`）。
   本文件**不含任何运行期观测**：A 节"会变成什么"、B 节"会变成什么"均为**依据源码的静态推理**，
   **不是**实测失败结果，也不代表已观察到任何红/绿。
2. **未触碰任何测试、任何生产代码、任何其他文件**；除本文件与
   `docs\acceptance\f88-dq-severity-20260912\raw\test-lockin-evidence.txt` 外**未写任何文件**；未执行任何 git 操作。
3. **每一处行号都是对当前工作树文件的静态重读结果**，不是估算；但对**正在被其他泳道修改的文件**，
   行号有时效性——见顶部"★ 必须先读"：`RuleSeverity.java`／`DataQualityGate.java`／`QualityChecker.java`
   在 `22:00:49–22:01:30` 之间刚被改写。本清单所有行号对应 **`22:02:10` 的权威快照**。
4. 本机**未安装 ripgrep**（`Get-Command rg` → `NOT FOUND`，见证据文件 `:6`）；
   任务指定的 6 条 `rg` 命令已逐条以等价的 `Select-String` 命令执行，并把**逻辑命令与实际执行命令同时逐字写入**证据文件（含 `[退出码: 0]`）。
5. 因此：**本文件是"锁死位置清单"，不是"测试失败报告"**。要得到失败结论，必须在测试侧同步后再实跑 Maven 并留痕。
