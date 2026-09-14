# F-88 质量门严重度口径 · 只读盘点（INVENTORY）

- 日期：2026-09-12
- 泳道：F-88「质量门严重度口径」
- 分支：`remediation/r1-boundary`（工作树，未提交；总控统一入库）
- 权威裁决：**人裁决 D-142 §1** —— 指导书 §7.3 保持原文：`BLOCKING` 与 `ERROR` **失败即阻断发布**；`WARN`/`INFO` 记录并展示、**不阻断**。**不采用**「改判据让 ERROR 放行」。
- 本文只写**实测**事实。凡未实测项一律显式标「未测」，不用估计代替测量。
- 原始输出（命令 + 原文）落 `raw/`，文件名逐处标注。

---

## 0. 口径基线（先写清楚判据，后面所有结论都对着它）

| 严重度 | 是否阻断发布 | 出处 |
|---|---|---|
| `BLOCKING` | **阻断** | 指导书 §7.3 原文；D-142 §1 确认 |
| `ERROR` | **阻断** | 同上（D-142 §1 明确**不**改判据） |
| `WARN` | 不阻断，记录并展示 | 同上 |
| `INFO` | 不阻断，记录并展示 | 同上 |

「阻断」在本工程的可观测含义（实测）：该阶段置 `FAILED`、run 置 `FAILED`、**不发布新快照**、原 `ACTIVE` 不变。

> **F-88 的核心矛盾（实测定位）**：严重度是**由 `spark-jobs`（Scala 侧）在质量结果 JSON 里回传**的，而 `spark-jobs` 是本次整改的**禁改范围**。落地前实测：全仓 `"WARN"` 字面量 **0 次**，`ERROR` 的源头全在 Scala 侧。因此口径只能在 **analytics-server 侧的落库/判定环节归一化**，不能靠改作业产出。

---

## 1. 规则码全量盘点（规则码 / 当前严重度 / 是否阻断 / 定义位置）

### 1.1 落库观测到的规则码（权威 = 真库 `analytics_meta.data_quality_result`）

证据：`raw/db-a-rule-severity-inventory.txt`、`raw/db-j-post-impl-severity-state.txt`（2026-09-12 复查）。

严重度分布（全表 tally，实测）：

| severity | 行数 | 其中 passed=0 |
|---|---|---|
| `BLOCKING` | 296 | 16 |
| `ERROR` | 120 | 52 |
| `NULL` | 48 | 4 |
| `INFO` | 3 | 0 |
| `WARN` | **0** | 0 |

规则码清单（run 47 的 22 行全量展开：17 个不同规则码 + `MXP_EXPORT_ROWS` 每表一行 ×8）：

| # | rule_code | layer | 落库 severity（实测） | 是否阻断（**旧**口径：仅 BLOCKING） | 定义位置（实测） |
|---|---|---|---|---|---|
| 1 | `AMOUNT_RECONCILE` | LANDING | BLOCKING | 是 | `analytics-server/warehouse-pipeline/.../pipeline/QualityChecker.java`（Java 内联 SQL/逻辑） |
| 2 | `REQUIRED_FIELD_NULL_RATE` | LANDING | ERROR | 否 | 同上（Java） |
| 3 | `ENUM_WHITELIST` | LANDING | ERROR | 否 | 同上（Java） |
| 4 | `EVENT_ID_UNIQUE` | LANDING | ERROR | 否 | 同上（Java），阈值 0.0005 |
| 5 | `ADS_STAGING_PRESENT` | ADS_STAGING | BLOCKING | 是 | `spark-jobs/.../sql/AdsSql.scala` → `AdsQualityJob` |
| 6 | `ADS_STAGING_KEY_NOT_NULL` | ADS_STAGING | BLOCKING | 是 | 同上 |
| 7 | `ADS_STAGING_SNAPSHOT_ISOLATION` | ADS_STAGING | ERROR | 否 | 同上 |
| 8 | `ADS_DWS_FUNNEL_RECONCILE` | ADS_STAGING | BLOCKING | 是 | 同上 |
| 9 | `PUB_DQ_BLOCKING_RULES` | PUBLISH | BLOCKING | 是 | `spark-jobs/.../job/AdsPublishJob.scala` |
| 10 | `PUB_DQ_EVENT_ID_UNIQUE` | PUBLISH | ERROR | 否 | 同上（Scala 侧 detail 原文写「观察项…不阻断发布」） |
| 11 | `PUB_STAGING_READY` | PUBLISH | BLOCKING | 是 | 同上 |
| 12 | `PUB_FORMAL_PARTITION_MATCH` | PUBLISH | BLOCKING | 是 | 同上 |
| 13 | `PUB_POINTER_SWITCH` | PUBLISH | INFO | 否 | 同上（发布操作审计项） |
| 14 | `PUB_STAGING_PRUNE` | PUBLISH | INFO | 否 | 同上（发布操作审计项） |
| 15 | `MXP_SNAPSHOT_PINNED` | PUBLISH | BLOCKING | 是 | `spark-jobs/.../job/MetricExportJob.scala` |
| 16 | `MXP_EXPORT_ROWS` | PUBLISH | BLOCKING | 是 | 同上（8 张表各一行） |
| 17 | `MXP_EXPORT_COMPLETE` | PUBLISH | BLOCKING | 是 | 同上 |

**未测**：`raw/` 证据里没有逐条记录这 17 个码在 `spark-jobs` 源码里的确切 `file:line`（见 §5 未测清单第 4 条）。上表「定义位置」是按作业归属实测确认的，不是按行号。

### 1.2 落库观测不到、但代码里存在的规则码（Java 侧发布对账）

证据：`analytics-server/metric-analysis/.../metric/publish/MetricPublishValidator.java`（实读）。

| rule_code | severity（代码内字面量） | 是否阻断 | 定义位置 |
|---|---|---|---|
| `MP_MANIFEST_TABLES` | BLOCKING | 是 | `MetricPublishValidator.check(...)` → `new Check(..., "BLOCKING", passed, ...)` |
| `MP_ADS_WRITE_MATCH` | BLOCKING | 是 | 同上 |
| `MP_METRIC_VALUE_COUNT` | BLOCKING | 是 | 同上 |
| `MP_ACTIVE_SNAPSHOT` | BLOCKING | 是 | 同上 |
| `MP_OLD_ACTIVE_ARCHIVED` | **INFO** | 否 | `MetricPublishValidator.java`（显式写死 INFO） |

这五个**不进** `data_quality_result`，只进发布报告/证据，因此不在 §1.1 的 tally 里——这是它们「DB 里查不到」的原因，不是漏盘。

---

## 2. `DataQualityGate` 判定路径（实测：改前 / 改后）

### 2.1 调用链

```
AnalysisService.overview/sales/...（metric-analysis）
  └─ AnalysisService.java:320  qualityGate.statusForRun(meta.getPipelineRunId())   ← 读取时现算
       └─ MetricQualityGate（platform-common 接口，:35）
            └─ DataQualityGate（warehouse-pipeline 实现）
                 └─ DataQualityResultMapper.selectList(run_id = ?)
                      └─ analytics_meta.data_quality_result
```

- 接口在 `platform-common`（避免 metric-analysis 反向依赖 warehouse-pipeline 形成模块环，接口注释实测如此写）。
- **`qualityStatus` 是读取时现算的**（`AnalysisService.java:320`），不是快照发布时冻结的值。⇒ 口径一改，**历史快照在页面上的质量结论会跟着变**。这是本次改动的可观测副作用，见 `IMPL-REPORT.md` §5。
- 全仓装配点实测：`PipelineServiceTest.java:153` 是唯一 `new PipelineService(...)` 处；生产装配在 `platform-app`。

### 2.2 判定逻辑（改前）

```java
// 改前 DataQualityGate（42 行）
boolean blockingFailed = rows.stream().anyMatch(row ->
        BLOCKING.equalsIgnoreCase(String.valueOf(row.get("severity")))
                && !(passed == 1));
return blockingFailed ? FAIL : PASS;
```

即：**只有字面量 `BLOCKING` 未过才 FAIL；`ERROR` 未过 = PASS**，与 §7.3 原文冲突。这就是 F-88 要修的口径。

### 2.3 判定逻辑（改后）

> **⚠ 本节已于 2026-09-14 按实测重写。** 原文案有三处与最终代码不符，逐条更正并保留痕迹：
> ① 严重度所有者**不在** `warehouse-pipeline/.../pipeline/RuleSeverity.java`，而在
> `platform-common/.../metric/RuleSeverity.java`（`warehouse-pipeline` 通过依赖 `metric-analysis`
> 间接可见）——**归属写错模块会让后来者去错地方找**；
> ② 登记码不是 **21** 个而是 **33** 个（目录 `DEFINITIONS` 共 **35** 条，多出
> `ORDER_ITEM_AMOUNT_FORMULA` ＋ `DWD_DWS_AMOUNT_RECONCILE` 两个本泳道新登记码）；
> ③ `blocks(severity)` 这个**单参重载已删除**（它会让「按字面 severity 判」重新变成第二个所有者）。

- 严重度**唯一所有者**：`platform-common/src/main/java/com/graduation/analytics/metric/RuleSeverity.java`
  - **版本化判据（两条路径现均走此入口）**：
    - `resolve(FrozenRules, ruleCode, passed)` → `RuleVerdict(registered, ruleCode, ruleVersion, declaredSeverity, effectiveSeverity, blocks, explanation)`。**`blocks` 由 `effectiveSeverity` 决定，与调用方传来的任何字面 severity 无关。**
    - `blocks(FrozenRules, ruleCode, passed)`：`resolve(...).blocks()` 的便捷入口；未登记码与未通过均按阻断（保守默认）。
    - `isKnownSeverity(String)`：**只**校验严重度取值域（供 `quality_rule_definition.severity` 合法性检查）；**绝不可**当发布判据 —— 见 raw/ 护栏用例 4。
  - `@Deprecated of(ruleCode)`：过渡期的旧入口，保留但**已无生产调用点**。
  - `failed(passed)`：`null` 或 `!= 1` → true（**缺字段按失败处理**，不按通过）。
  - `rationale(ruleCode)`：逐码中文依据（阈值来源、去重是否确定性、哪条业务语义）。
- **冻结规则集来源**：`QualityRuleCatalog`（同目录）——`CATALOG_VERSION="qrc-1"`、`COMPAT_POLICY_VERSION="compat-v1"`、`DEFINITIONS` **35** 条、`freeze(sourceScope)`、`fingerprint()`。一次 run 在 `PipelineService.execute` 入口取一次并贯穿全程（run 内恒定）。**跨 run 版本可追溯尚未闭合**（表未落地），见 `raw/q01-version-scope.md`。
- `DataQualityGate.decisionForRun(Long, FrozenRules)` → 返回 `GateDecision(status, blockingRules, unregisteredRules, rulesFingerprint, reason)`；空/null → `UNKNOWN`（**不冒充 PASS**）。**未登记码同时进 `unregisteredRules` 与 `blockingRules`**（否则「报未登记」与「停止发布」脱节）。
- `DataQualityGate.blockingFailuresForRun(Long, FrozenRules)`：返回未通过的阻断级规则码列表（供发布前断言复用**同一判据**）。
- 兼容解释对**每条**规则留痕：`ruleCode(declared→effective): explanation`（§7.3.1 line 524「接口同时展示兼容解释」）。

---

## 3. 严重度逐条审核结论（依据：去重是否确定性 / 阈值来自哪里 / 哪条业务语义）

**阈值批准出处（实测原文）**：`docs/design/...项目设计文稿 V2.2.md:581-587` §5.4.2「默认阈值」——必要字段空值率 ≤0.1%、**主键重复率不超过 0.05%（=0.0005）**、非法行为类型比例 0、层间金额误差 <0.01；同一张表亦见 `V2.1:477-485`、`docs/项目完整实施指导书 V2.0.md:827-832` §16.3、`docs/项目整改实施指导书 V1.0.md:702-707`（含「超阈值阻断」）。

| rule_code | 旧 | 新 | 依据 |
|---|---|---|---|
| `EVENT_ID_UNIQUE` | ERROR（不阻断） | **条件观察项**（`THRESHOLD_OBSERVATION`：未超阈值＝WARN 不阻断；**超阈值＝BLOCKING**） | ① 下游**确定性去重**实测：`DwdSql.behaviorClean` 用 `ROW_NUMBER() OVER (PARTITION BY event_id ORDER BY ingest_time)` + `WHERE rn.rn = 1`；`duplicateReject` 落 `dwd_reject_record`（reason `DUPLICATE_EVENT`）；`TradeDwdJob` `dropDuplicates("event_id")`。② 重复率阈值 0.0005 直接来自设计文稿 §5.4.2 已批准「主键重复率不超过 0.05%」，**未放宽**。两条同时成立才允许降级。**⚠ 本行原文案写「新＝WARN」是错的，已于 2026-09-14 按实测更正**：§7.3.1 line 522 要求「超过阈值阻断」，「固定 WARN」会把高重复率直接放行；实测 run 24/47 该码 `error_rate=0.020408` 对阈值 `0.0005`（约 40 倍）**确实超阈值**，故必须升阻断。 |
| `PUB_DQ_EVENT_ID_UNIQUE` | ERROR（不阻断） | **条件观察项**（同上） | 同一条业务语义的发布侧复检；下游同样确定性去重。**⚠ 原文案「新＝WARN」同样已更正**：实测 run 24/47 `error_rate=0.071429` 对阈值 `0.0005`（约 143 倍）真实超阈值。Scala 侧 detail 写的「观察项…不阻断发布」对应的是**未超阈值**那一支，不构成「无条件放行」的依据。 |
| `ADS_STAGING_SNAPSHOT_ISOLATION` | ERROR（不阻断） | **WARN** | D-142：**历史 staging 快照存在本身不是错误**。若判 BLOCKING 会造成发布死锁：`AdsQualityJob.scala:55-59` 的清理在 dqc **之后**跑，本次运行必然看得到上一轮的 staging。真正「当前发布读取或混入其他快照数据」的阻断场景由 `MXP_SNAPSHOT_PINNED`（BLOCKING，快照钉住校验）覆盖。 |
| `REQUIRED_FIELD_NULL_RATE` | ERROR（不阻断） | **BLOCKING** | 必需字段缺失 ⇒ **必须阻断**。实测：`DwdSql.behaviorClean` 还过滤 `payload_user_id IS NOT NULL`、`payload_product_id IS NOT NULL`——这些行被**静默丢弃且不写 `dwd_reject_record`**，即「不阻断就没人知道丢了数据」。阈值 ≤0.1% 来自 §5.4.2。 |
| `ENUM_WHITELIST` | ERROR（不阻断） | **BLOCKING** | 非法行为类型 ⇒ **必须阻断**。实测：`behaviorClean` 过滤 `payload_behavior_type IN ('view','favorite','cart_add','cart_remove','search')`，同样**静默丢弃**；§5.4.2 批准阈值为 0（比例必须为 0），不允许超。 |
| `AMOUNT_RECONCILE` | BLOCKING | BLOCKING（不变） | 金额对账不一致 ⇒ **必须阻断**（§5.4.2「层间金额误差 <0.01」）。 |
| `ADS_STAGING_PRESENT` / `ADS_STAGING_KEY_NOT_NULL` / `ADS_DWS_FUNNEL_RECONCILE` / `PUB_DQ_BLOCKING_RULES` / `PUB_STAGING_READY` / `PUB_FORMAL_PARTITION_MATCH` / `MXP_SNAPSHOT_PINNED` / `MXP_EXPORT_ROWS` / `MXP_EXPORT_COMPLETE` | BLOCKING | BLOCKING（不变） | 结构性/完整性/对账类：表缺失、主键空、漏斗对账、正式分区不匹配、快照未钉住、导出行数不符、导出不完整。 |
| `PUB_POINTER_SWITCH` / `PUB_STAGING_PRUNE` | INFO | INFO（不变） | 发布操作审计项（切换/清理计数），不是质量规则。 |
| `MP_MANIFEST_TABLES` / `MP_ADS_WRITE_MATCH` / `MP_METRIC_VALUE_COUNT` / `MP_ACTIVE_SNAPSHOT` | BLOCKING | BLOCKING（登记） | 指标库发布对账；不通过则不得写指标值、不得切指针（§17.5）。**登记只增可读性**：它们不进 `data_quality_result`，且未登记码兜底本来就是 BLOCKING，行为不变。 |
| 48 行 legacy `NULL` severity（run 1–15） | NULL | **待裁项**，不静默改判 | 见 §6。 |

### 3.1 阈值：一条都没动

`QualityChecker.java` 的 `NULL_RATE_MAX = 0.001`、`DUP_RATE_MAX = 0.0005` **保持原值**。本次**没有**为了让当前数据通过而放宽任何阈值。工作树里阈值常量与改动前逐字一致（`git diff` 可验）。

### 3.2 口径一致性交叉验证（实测）

作业侧 dqc 的阻断集合是目录阻断集合的**子集**：

- `AdsQualityJob.scala:132` 只在 `severity == "BLOCKING" && !passed` 时判失败；
- 其 BLOCKING 规则 = `ADS_STAGING_PRESENT, ADS_STAGING_KEY_NOT_NULL, PUB_DQ_BLOCKING_RULES, ADS_DWS_FUNNEL_RECONCILE`；
- 这 4 个在 `RuleSeverity` 里全部仍是 BLOCKING。

⇒ 把 dqc 的两条 `ERROR` 改判 `WARN` **不改变作业自身行为**，只改变落库标签与平台判定。这是「不改 spark-jobs 也能落地新口径」的依据。

> **⚠ 2026-09-14 更正（重要）**：上面这段的依据是**旧口径**（「按规则码映射到固定档位」）。
> 新口径下 `EVENT_ID_UNIQUE` 与 `PUB_DQ_EVENT_ID_UNIQUE` 是 **`THRESHOLD_OBSERVATION`（条件观察项）**，
> 不是固定 `WARN`：**未超批准阈值才是观察项，超阈值升为阻断**（§7.3.1 line 522）。
> 实测 run 24/47 中这两码**均已超阈值**（约 40 倍 / 143 倍）⇒ 新口径下它们**阻断**。
> 因此「改判 `WARN` 不改变平台判定」这句话**只在未超阈值时成立**，不得当作无条件结论引用。
> 作业侧（`spark-jobs`）本轮仍**一行未改**，落库 `severity` 列仍是作业回传的 `ERROR`——
> 平台判定与列值的**漂移仍在**，由读侧归一化承担（F-94）。

---

## 4. 固定旧行为的测试清单（锁死点，`file:line`）

证据：`raw/test-lockin-inventory.md`、`raw/test-lockin-evidence.txt`（905 行，含两份时序快照：21:58:58 与 22:02:10 权威版）。

> **时序说明（重要）**：盘点过程中发现**生产代码已被本泳道改动**（`RuleSeverity.java` 22:00:49 新建、`DataQualityGate.java` 22:01:02 改写、`QualityChecker.java` 22:01:30 改写），而测试文件当时仍是 09-10/09-11 的旧时间戳。所以当时的真实状态不是「测试锁死未来」，而是「**生产已走新口径、测试仍钉在旧口径**」。下表按处理结果标注。
>
> **⚠ 2026-09-14 复核实测**：下表的 `file:line` 与「处理」列**多数已过时**（测试文件被重写后行号已移位、用例数已变）。
> 现以 `IMPL-REPORT.md` §3.1 的**实测表格**为准（5 个测试类共 **52** 例全绿）；
> 本表仅作**历史盘点留痕**保留。其中已确认**表述错误**的一条见下方 `AnalysisGoldenMySqlIT` 行。

| 测试 | `file:line` | 旧断言锁死的行为 | 处理 |
|---|---|---|---|
| `DataQualityGateTest.nonBlockingFailureKeepsPass` | `DataQualityGateTest.java:49` | fixture 含 `result("ERROR", 0)`，断言门为 PASS | **已按新口径重写**（ERROR 未过 ⇒ FAIL） |
| `DataQualityGateTest` 注释 | `:23`、`:44` | 注释把「ERROR 也算阻断」定义为错误结论 | **已改** |
| `QualityCheckerAmountReconcileTest.orphanPaymentStillBlocks` | `:56` | `corePassed()==false` | **无需改**：实测 4/4 通过（`AMOUNT_RECONCILE` 被 check 到且 `passed=0`，新口径下仍阻断） |
| `QualityCheckerAmountReconcileTest.amountMismatchStillFails` | `:71` | 同上 | 同上（实测通过） |
| `QualityCheckerAmountReconcileTest.twoArgOverloadKeepsPreviousSemantics` | `:84-85` | `AMOUNT_RECONCILE.getPassed()==0` | 同上（实测通过） |
| `JobResultParserTest` | `:60`、`:63-64` | 注释「ERROR 不阻断发布」；`blockingFailures()` 期望 `[ADS_STAGING_PRESENT, LEGACY_NO_SEVERITY]` | **注释已改**；断言**保持不变**（实测 12/12 通过）——理由见下 |
| `PipelineServiceTest.qualityFailureBlocksPublish` | `:376` | 期望阻断错误码 `PIPELINE_QUALITY_FAILED` | 断言保留；**夹具修为非空 checkCount/errorCount**（见 `IMPL-REPORT.md` §4） |
| `AnalysisGoldenMySqlIT`（私有复刻门） | `:219`、`:237-240` | 私有 `MetaQualityGate` 复刻旧口径（只判 BLOCKING） | **已按新口径改**（否则同一份 run 24 数据出现两个结论） |
| `AnalysisGoldenMySqlIT` 断言 | `:130`（原 `:75`） | `qualityStatus()` 期望值 | **⚠ 本条曾被改错，第三次才纠正**：一度写成「**已改**为 `FAIL`」，但**实际最终态是 `PASS`**（旧口径把 `EVENT_ID_UNIQUE`/`PUB_DQ_EVENT_ID_UNIQUE` 无条件当 WARN）⇒ **该表述是错的**。2026-09-14 按 `THRESHOLD_OBSERVATION` 重新实测（超阈值 ~40 倍 / ~143 倍）⇒ 期望值**现为 `FAIL`**，与类 javadoc 第 53–60 行「run 24/47 合法地翻为 FAIL」一致 |

### 4.1 `JobResultParserTest` 为什么保持原断言（而不是二选一）

盘点的 A 节指出：若把 `blockingFailures()` 改成按**目录归一化**（当时写作「走 `RuleSeverity.of`」；**现口径为 `resolve(rules, code, passed)`，`of` 仅剩 `@Deprecated` 兼容且已无生产调用点**），`PUB_DQ_EVENT_ID_UNIQUE` 会变观察项，断言不变；若改成按**回传字面量**，列表会多出该码而失败。

**F-88 的定论**：`JobResultParser` 是**作业 JSON 的忠实适配器**，不是平台口径的所有者。它的 `CheckInfo.blocking()`/`blockingFailures()` 表达的是「**作业自己为什么 FAILED**」，必须忠实反映回传的 severity 字面量。平台口径统一在 `RuleSeverity` 拥有、由 `PipelineService.persistChecks` 落库时归一化。两者职责分离后，该测试的 12 条断言**全部天然成立**，只改注释。为避免证据里「blocking 有两个不同值」造成误读，作业证据新增了 `normalizedSeverity` 字段，并把作业侧判据显式改名为 `blockingFailedRawJobSeverity`。

---

## 5. 快照语义（D-142 §1.1）逐条核实

证据：`raw/db-k-metric-snapshot-after-impl.txt`（2026-09-12 复查，权威）、`raw/db-f-metric-db-snapshots.txt`、`raw/db-e-snapshot-scope.txt`。

真库 `analytics_metric.metric_snapshot` 实测（真值来源；`analytics_meta.metric_snapshot` 是 9 行 stale legacy 表，**不是**真值来源）：

| snapshot_id | run | version | status | active_flag | published_at |
|---|---|---|---|---|---|
| `S20260901_47` | 47 | 12 | **ACTIVE** | 1 | 2026-09-12 21:29:27.321 |
| `S20260901_43` | 43 | 11 | ARCHIVED | NULL | 2026-09-12 17:39:51.953 |
| `S20260901_42` | 42 | 10 | ARCHIVED | NULL | 2026-09-12 17:28:12.779 |
| `S20260901_41` | 41 | 9 | ARCHIVED | NULL | 2026-09-12 09:31:13.388 |
| `S20260901_39` | 39 | 8 | ARCHIVED | NULL | 2026-09-11 15:12:22.013 |
| `S20260901_38` | 38 | 7 | **FAILED** | NULL | **NULL** |
| `S20260901_30` | 30 | 6 | ARCHIVED | NULL | 2026-09-11 11:09:19.753 |
| `S20260901_29` | 29 | 5 | ARCHIVED | NULL | 2026-09-11 11:02:25.364 |
| `S20260901_25` | 25 | 4 | ARCHIVED | NULL | 2026-09-11 10:41:49.858 |
| `S20260901_22` | 22 | 3 | ARCHIVED | NULL | 2026-09-11 09:50:54.425 |
| `S20260901_24` | 24 | 2 | ARCHIVED | NULL | 2026-09-10 20:44:11.886 |
| `S20260901_23` | 23 | 1 | ARCHIVED | NULL | 2026-09-10 20:38:54.103 |

| # | §1.1 条款 | 核实结论 | 证据 |
|---|---|---|---|
| 1 | 新运行失败 ⇒ 不改变原 `ACTIVE`，员工仍可读上一份成功结果 | ✅ **成立**。`MySqlMetricStore.publish()` `@Transactional`：DELETE+INSERT 指标值 → 归档旧 ACTIVE → 激活新快照，三步同事务；任一步失败全回滚，旧 ACTIVE 保持。质量门/QUALITY_CHECK 失败时 `publish()` **根本不会被调用**；指标发布器另有补偿。本次改动在 `PUBLISH_METRIC` 增加了**发布前断言**，用同一判据再查一次，不通过直接抛 `PIPELINE_QUALITY_FAILED` 且 `published=false`。 | `MySqlMetricStore.java:114` 附近 `publish()` 全文实读；`PipelineService.java:566-576` |
| 2 | 成功且**原子发布完成后**旧 ACTIVE 才转 ARCHIVED | ✅ **成立**。归档 UPDATE 与激活 UPDATE 在同一 `@Transactional` 方法内，`switched==0` 时只 `log.warn` 不动 ACTIVE。 | `MySqlMetricStore.java` `publish()` 实读 |
| 3 | 归档失败（publish 中途失败）⇒ 不产生新 ACTIVE | ✅ **成立（有真库负向证据）**。`S20260901_38`：`status=FAILED`、`active_flag=NULL`、`published_at=NULL`，`failure_reason=MP_ADS_WRITE_FAILED: ... Column 'favorite_category' cannot be null` —— 这是一次**真实失败运行**，它没有变成 ACTIVE，指针留在了当时的旧快照上。 | `raw/db-k-metric-snapshot-after-impl.txt` |
| 4 | `ACTIVE` 唯一性按**发布作用域**约束（多源场景不得靠全库一个「最新」） | ✅ **DB 约束成立** / ❌ **查询侧不成立（待裁项，未修）**。真库实测：按 `runtime_profile_id` 分组，每个 profile 只有 1 行 ACTIVE（`active_cnt=1`）。DDL 唯一键为 `uk_active_profile(runtime_profile_id, active_flag)`。**但** `MySqlMetricStore.activeSnapshotId()` 与 `MetricAdsReader.activeSnapshotId()` 是**全库**查询（`ORDER BY id DESC LIMIT 1`，**不带** `runtime_profile_id`）——单源场景下结论正确，多源场景下会取到别的 profile 的快照。`MetricAdsReader` 另有 profile 版重载 `:55-61`。**本泳道未修**，列入待裁项。 | `db-k`（ACTIVE per profile）；源码实读 |
| 5 | 负向测试：`ERROR` 失败 ⇒ 不发布新快照 ⇒ 原 ACTIVE 不变**且可读** | ✅ **已补（单测层）**。`PipelineServiceTest.errorSeverityFailureBlocksPublishAndLeavesActiveUntouched`：预置 `PUB_DQ_EVENT_ID_UNIQUE / ERROR / passed=0` ⇒ 断言 run `FAILED`、错误码 `PIPELINE_QUALITY_FAILED`、`PUBLISH_METRIC` 阶段 `FAILED`、`verify(publisherPort, never()).publish(any())`、阶段证据含 `prePublishGate` 与该规则码（即「不发布」被观测到）。对应正向对照：`warnSeverityFailureStillPublishes` 断言 `WARN` 未过仍发布、`verify(publisherPort).publish(any())`。**未测**：真库级端到端（需起服务/跑集群作业，本次禁做），故「原 ACTIVE 可读」只有 DB 只读观测（`S20260901_47` 仍 `ACTIVE/active_flag=1`）+ 事务语义实读支撑，**没有**跑过一次真失败发布再读。 | `PipelineServiceTest.java:420-470` |
| 6 | `ARCHIVED` 不可变、只能经明确历史快照入口按权限读取、不混入默认查询 | ⚠️ **未验证（本次未改、未测）**。实测：`AnalysisService` 走 `findSnapshot(snapshotId)` 读指定快照（无权限校验）；`listSnapshots(int)` 返回**混合状态**列表；默认查询路径是否会把 ARCHIVED 混入未做端到端验证。**未测**，列入待裁项。 | 源码实读；**运行期未测** |

---

## 6. 待裁项（不猜，逐条列出求裁决）

### 待裁-1：48 行 legacy `NULL` severity（run 1–15）

- 实测：`data_quality_result` 有 48 行 `severity IS NULL`，其中 4 行 `passed=0`，全部集中在 run 1–15（本泳道改动前的历史数据）。
- `RuleSeverity.blocks(null)` 的兜底是 **true**（按阻断处理，保守）。
- **未做**：没有写任何迁移 SQL 去回填这 48 行。因为「历史 NULL 该按阻断还是按记录」属于口径外延，且回填会改变历史 run 的质量结论，**需要裁决**。
- 影响面实测：这 4 行未过的 run 都不在当前 ACTIVE 快照（run 47）上，因此**不影响当前看板结论**。

### 待裁-2：`activeSnapshotId()` 全库查询 vs §1.1 第 4 条（发布作用域）

- 见 §5 第 4 行。DB 层唯一性已按 `runtime_profile_id` 约束，**查询层**没有。
- 本次未修：属于发布/读取路径的既有缺陷，改动它会触及多源（P5）语义边界，超出 F-88 质量门口径范围。
- 建议：要么把这两个方法收敛到 profile 版重载，要么明确「当前只支持单 profile」并加断言。

### 待裁-3：`qualityStatus` 读取时现算 ⇒ 历史快照结论会随口径变化

- 实测：`AnalysisService.java:320` 每次读取现算。改口径后，**归档快照 `S20260901_24` 的质量结论由 PASS 变 FAIL**。
- **⚠ 2026-09-14 对下述「ACTIVE 不受影响」的更正**：原文案写「当前 ACTIVE 快照 `S20260901_47` 的结论不变（新口径下仍 PASS）——它那 3 条未过规则改判 `WARN`（不阻断）」。**该结论已证伪、不得再引用。**
  实测 run 47 的 `EVENT_ID_UNIQUE` `error_rate=0.020408` 对阈值 `<=0.0005`（约 **40 倍**）、
  `PUB_DQ_EVENT_ID_UNIQUE` `error_rate=0.071429`（约 **143 倍**），**两行 `passed=0` 都是真实超阈值**，
  不是误判。这两个码现为 `THRESHOLD_OBSERVATION`：未超阈值＝观察项，**超阈值＝升为阻断**（§7.3.1 line 522）。
- ⇒ **更正后的结论：本次改动会让当前 ACTIVE 快照 `S20260901_47` 的质量结论也翻为 FAIL，即线上看板质量卡会变红**
  （run 24 与 run 47 同样翻）。这是**收紧一个原本过松的契约**的必然结果，不是缺陷；
  但它**改变了线上可见结论**，故列为 **待裁-11**，须总控显式裁决后才能落地，本泳道不静默应用。
- 未裁：是否要把质量结论在发布时**冻结**进快照，而不是读取时现算。若裁决要冻结，需要新快照字段 + 迁移，超出本泳道。

### 待裁-4：`MetricPublishValidator` 的 severity 比较是大小写敏感的

- 实测：`MetricPublishValidator.blocked()` / `failedRules()` 用 `"BLOCKING".equals(c.severity())`（大小写敏感）；`RuleSeverity.blocks()` 是大小写不敏感 + null 即阻断。
- 实测风险：**当前无实际影响**——`MP_*` 的 severity 全部由 `MetricPublishValidator.check(...)` 自己写死字面量 `"BLOCKING"`/`"INFO"`，不会出现别的大小写。
- 未改：属潜在不一致，改了会动发布对账的判定代码，无实测触发点，留待裁决。

### 待裁-5：`spark-jobs` 侧 severity 字面量（禁改范围）

- 实测：`AdsQualityJob.scala:107-110` 仍把 `PUB_DQ_EVENT_ID_UNIQUE` 写成 `"ERROR"` + detail「观察项…不阻断发布」；`:57-61` 把 `ADS_STAGING_SNAPSHOT_ISOLATION` 写成 `"ERROR"`；`AdsPublishJob.scala:118`、`MetricExportJob.scala:87/:89/:111` 全是 `severity == "BLOCKING" && !c.passed`；全仓 `"WARN"` 字面量 **0 次**。
- 后果：**落库的 `severity` 列永远是作业回传值**（`ERROR`），`WARN` 这一档在 DB 里永远查不到。本次通过 `RuleSeverity` 在**落库归一化**解决判定问题，但**列里存的仍是 ERROR**——按 severity 列直接过滤的运维查询会得到与平台判定不一致的结果。
- 需要裁决：是否要求后续在 `spark-jobs` 侧把这两条改成 `WARN`（本次禁改，未做）。

---

## 7. 未测清单（显式声明）

1. **未做**真库端到端失败发布实验（需起 809x 服务 / 跑集群作业，本次明令禁止）。所有「不发布新快照」的结论来自：单测（mock 发布器）+ 真库只读观测（`S20260901_38` FAILED/从未 ACTIVE）+ 事务语义源码实读。
2. **未测** `ARCHIVED` 不可变性与「历史快照入口按权限读取、不混入默认查询」（§5 第 6 行）。
3. **未测**多 profile 场景下 `activeSnapshotId()` 的实际取错行为——真库当前只有 1 个 profile 有 ACTIVE 行，**无法构造**反例。
4. **未逐行记录** 17 个规则码在 `spark-jobs` 源码里的确切 `file:line`（`raw/` 证据只有按作业归属的确认，没有逐码行号）。
5. **未跑** `@EnabledIfSystemProperty(named="metric.it")` 的真库 IT（`AnalysisGoldenMySqlIT`、`MetricAdsMySqlIT`、`MetricPublisherMySqlIT` 等）：默认跳过。**2026-09-14 本轮因 DB 硬冻结明确禁跑**（禁 `-Dmetric.it=true`、禁连库 IT）。⇒ `AnalysisGoldenMySqlIT` 改后的 `qualityStatus()==FAIL` 期望是**按真库只读实测数据 + `resolve()` 源码逻辑静态推算**的，**不是运行期观测**；该改动**只验到 test-compile（exit 0）**。
6. **未做变异测试**：未人为篡改 `RuleSeverity.resolve` 验证跨模块护栏会变红 ⇒ 护栏判别力**无实测支撑**（仅有 oracle 构造论证）。
7. **未落库版本化载体**：`quality_rule_definition` 表不存在（全仓 0 命中）⇒ 「跨 run 版本化」**无法测**，且**不能声称已闭合**。DDL 仅以草案形式存于 `raw/`（**未编号、未申请迁移号**）。
8. **未测阈值边界值**：`THRESHOLD_OBSERVATION` 只测了 `0.000000`（不阻断）与 `0.5`（阻断）两侧；**恰好等于阈值 `0.0005` 的边界未测**。
9. **未改**任何数据库业务数据：所有 SQL 都是 `SELECT`。
10. **未提交** git：工作树改动全部保留未入库，由总控统一处理。

---

## 8. 原始证据索引（`raw/`）

| 文件 | 内容 |
|---|---|
| `db-a-rule-severity-inventory.txt` | 规则码 × severity 全量 tally |
| `db-b-run47-and-snapshots.txt` | run 47 质量行 + 快照 |
| `db-c-metric-snapshot.txt` | 指标快照 |
| `db-d-run24-and-impact.txt` | run 24 质量行（含 `blocking_failed=0 / error_failed=3`） |
| `db-e-snapshot-scope.txt` | 快照作用域查询 |
| `db-f-metric-db-snapshots.txt` | `analytics_metric` 快照全量 |
| `db-g-ads-quality.txt` | ADS 质量证据 |
| `db-h-impact-and-warn.txt` | run 47 逐行 tally + `warn_rows=0` |
| `db-i-quality-stage-evidence.txt` | run 24 / run 47 `QUALITY_CHECK` 阶段证据 JSON 全文 |
| `db-j-post-impl-severity-state.txt` | **改后复查**：run 24/47 质量行 + severity tally |
| `db-k-metric-snapshot-after-impl.txt` | **改后复查**：快照状态 + ACTIVE per profile + FAILED 行 |
| `db-l-golden-values-24-vs-47.txt` | 两快照黄金指标值逐项对比（证明 `S20260901_24` 仍可作黄金数据集） |
| `test-lockin-inventory.md` / `test-lockin-evidence.txt` | 固定旧行为测试清单（含两份时序快照，905 行原始输出） |
| `e0-baseline-HEAD-platform-common.log` | HEAD 基线复现（证明既有红与本次改动无关） |
| `e1-compile.log` | E1 编译 |
| `e2-warehouse-pipeline-test.log` | E2 warehouse-pipeline 单测 |
| `e2-metric-analysis-test.log` | E2 metric-analysis + ai-decision 单测 |
| `e2-all-modules-test.log` | E2 全 reactor 单测 |
| `e3-diagnosis-fixture-npe.txt` | 夹具 NPE 定位过程（编译错误原文） |
