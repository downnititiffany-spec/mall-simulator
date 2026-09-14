# F-88 质量门严重度口径 · 实施报告（IMPL-REPORT）

- 日期：2026-09-12（**2026-09-14 按实测收窄重写**）
- 泳道：**V25-Q01**「F-88 质量门严重度口径」（V2.5 看板重编号；Owner＝总控泳道）
- 分支：`remediation/r1-boundary`，HEAD `8983616`（**工作树改动，未 git add/commit/push**，由总控统一入库）
- 权威裁决：**`docs/项目完整实施指导书 V2.5.md` §7.3.1（line 518–528）** —— 逐字要求见 §2.4 与 §8 的对应表。
- 盘点明细见同目录 `INVENTORY.md`；版本作用域与过渡形态见 `raw/q01-version-scope.md`；跨模块护栏证据见 `raw/q01-cross-module-guard-evidence.md`；本文只写**改了什么 ＋ 实测命令/退出码 ＋ 未做未测**。

> **⚠ 本轮收窄重写说明（§5.1 要求二次自我更正）**
> 本报告 2026-09-12 首版基于**当时**的实测结论；2026-09-14 的独立只读 SELECT 证伪了其中一条关键结论
> （原文案：读侧归一化后 run 24/47 质量结论为 PASS）。**错在把「无条件映射 WARN」当成了「安全降级」**：
> 实测 run 24/47 的 `EVENT_ID_UNIQUE` `error_rate=0.020408` 对阈值 `0.0005` 约 **40 倍**、
> `PUB_DQ_EVENT_ID_UNIQUE` `0.071429` 约 **143 倍**，是真超阈值。旧口径等于把高重复率**直接放行**，
> 违反 §7.3.1 line 522。故本报告以下内容一律以**收窄后**实现为准，被证伪的旧表述就地标注、不删除（留痕）。
> **另有两处表述错误一并更正**：① `RuleSeverity` 的归属模块写错；② `PipelineService` 被列为 Q01 范围内，
> 但它属 **V25-Q02**（见 §1.4）。

---

## 1. 改动摘要

### 1.1 新增：`RuleSeverity`（严重度的唯一所有者）

`analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/RuleSeverity.java`

> **⚠ 位置更正**：首版写的是 `warehouse-pipeline/.../pipeline/RuleSeverity.java`，**归属模块写错**。
> 实际在 **`platform-common`**（`warehouse-pipeline` 经 `metric-analysis → platform-common` 间接可见）。
> 写错会让后来者去错模块找「唯一所有者」，故显著标注。

为什么必须新增：**严重度原本由 `spark-jobs`（Scala，本泳道禁改）在质量 JSON 里回传**，同一档 `ERROR` 既被作业当作「不阻断」、又被指导书 §7.3 要求「阻断」。要落地 §7.3 又不改作业，就必须有一个地方把「作业回传的标签」翻译成「平台口径」，且只能有一处拥有这个映射。

- **版本化判据（两条路径现均走此入口）**：
  - `resolve(FrozenRules, ruleCode, passed)` → `RuleVerdict(registered, ruleCode, ruleVersion, declaredSeverity, effectiveSeverity, blocks, explanation)`。**`blocks` 由 `effectiveSeverity` 决定，与调用方传来的任何字面 severity 无关。**
  - `blocks(FrozenRules, ruleCode, passed)`：`resolve(...).blocks()` 的便捷入口。
  - `isKnownSeverity(String)`：**只**校验严重度取值域（供 `quality_rule_definition.severity` 合法性检查），**绝不可**当发布判据。
  - `UnknownRuleException`；嵌套 `record RuleVerdict` ＋ `RuleVerdict.unregistered(String)`。
- **登记码 33 个**（首版写「21 个」是过时数字，已更正）：12 BLOCKING ＋ 14 `MP_*` BLOCKING ＋ 3 WARN ＋ 3 INFO ＋ catalog-only `MP_ADS_WRITE_MATCH`。未登记码 → `UNREGISTERED`（= `BLOCKING`，保守兜底）并**报未登记**。
- `failed(passed)`：`null` 或 `!= 1` → true（缺字段按失败处理）。
- `rationale(ruleCode)`：逐码中文依据（去重是否确定性 / 阈值来自哪里 / 哪条业务语义）。
- **已删除**：`blocks(String severity)`、`blocks(String severity, String ruleCode)`、`blocks(String severity, String ruleCode, Integer passed)` 三个**会静默丢弃第一个实参**的重载。首版把它们当作「过渡兼容」保留，实为**双所有者缺陷**：调用方以为传了 severity 就能决定结论，实际参数被忽略。删除后测试侧 14+ 处调用点改为 `resolve(rules, code, passed)`。
- **仍保留但已无生产调用点**：`@Deprecated of(String)`、`@Deprecated registered(String)`。

### 1.2 改写：`DataQualityGate`（42 行 → 新口径）

- `decisionForRun(Long)`（兼容旧签名，转调默认冻结集）与 `decisionForRun(Long, FrozenRules)`（新）。
- 返回 `GateDecision(status, blockingRules, unregisteredRules, rulesFingerprint, reason)`；空/null → `UNKNOWN`（**不冒充 PASS**）。
- **未登记码同时进 `unregisteredRules` 与 `blockingRules`**：若只记在 `unregisteredRules`，发布前门断言读 `blockingFailuresForRun` 时会漏掉它，于是「报未登记规则」与「真的停止发布」脱节（§7.3.1 line 524 要求两者同时成立）。
- 兼容解释对**每条**规则留痕：`ruleCode(declared→effective): explanation`（§7.3.1 line 524「接口同时展示兼容解释」）。
- `blockingFailuresForRun(Long)` ＋ `blockingFailuresForRun(Long, FrozenRules)`：返回未通过的阻断级规则码列表，供**发布前断言复用同一判据**。
- `resultsOf(null)` 直接返回 `List.of()`，不查库。

### 1.3 改写：`QualityChecker`

- `rule(...)` 现在的 `severity` 一律取 `RuleSeverity.resolve(rules, code, passed).effectiveSeverity()`（不再由调用点写死，也不按库中字面值）。
- 阻断判据改用 `RuleSeverity.blocks(rules, code, passed)`，与 `DataQualityGate` 同源。
- **阈值常量未动**：`NULL_RATE_MAX = 0.001`、`DUP_RATE_MAX = 0.0005` 与改动前逐字一致（`git diff` 可验）。

### 1.4 改写：`PipelineService`（落库归一化 + 发布前断言）

> **⚠ 范围更正（重要）**：首版把本项列为 Q01 交付。经总控界定，**`PipelineService.java` 属 `V25-Q02`，不在 Q01 验收范围**。
> 但 R01 审计明确要求迁移其 3 处 `RuleSeverity.of` 调用点，**该迁移已完成并编译通过**，
> 故此处记录为 **Q02 前置件**：**已实施、不由 Q01 覆盖、未验收**。请总控在 Q02 中正式验收。

1. **新增 `rulesFor(PipelineRun run)`**：`QualityRuleCatalog.DEFAULT.freeze(run.getPipelineCode())`，在 `execute` 进入七阶段**之前取一次**，此后 run 内全程复用同一引用（run 内恒定）。javadoc 显式声明这只是**过渡形态**、只保证 run 内一致、**不保证跨 run 版本化**，并列出闭合所需的两项前提。
2. `persistQuality(..., FrozenRules)`：severity 由旧的内联 `"AMOUNT_RECONCILE".equals(...) ? "BLOCKING" : "ERROR"` 改为 `RuleSeverity.resolve(rules, code, passed).effectiveSeverity()`。
3. `persistChecks(..., FrozenRules)`：`RuleSeverity.resolve(...)` 归一化后落库（不再按字面值）。⇒ **落库的 severity 从此是平台口径**。
4. `QUALITY_CHECK`：阻断判据改为 `RuleSeverity.blocks(rules, ...)`；证据新增 `"blocking"` 说明项；失败消息改为「阻断级 Landing 质量规则未通过，正式分区未发布」。
5. `PUBLISH_METRIC`：在 `metricPublisher.publish(...)` **之前**插入发布前断言（改用冻结集版本）：

```java
List<String> gateFailures = qualityGate.blockingFailuresForRun(run.getId(), rules);
evidence.put("prePublishGate", Map.of("rule", "RuleSeverity.blocks：按冻结规则集判定",
        "blockingFailures", gateFailures, "passed", gateFailures.isEmpty()));
if (!gateFailures.isEmpty()) {
    evidence.put("published", false);
    updateStageEvidence(run.getId(), "PUBLISH_METRIC", evidence);
    throw new PipelineStageException("PIPELINE_QUALITY_FAILED",
            "阻断级质量规则未通过，不发布新快照（旧 ACTIVE 不变）: " + String.join(", ", gateFailures));
}
```

这是 §1.1 第 5 条要求的负向语义在**编排侧**的落点：不通过 ⇒ `publish()` 根本不被调用 ⇒ 旧 ACTIVE 不变。

6. `jobEvidence(StageExecution, FrozenRules)`：新增 `normalizedSeverity`（平台归一化值），作业侧的 `blockingFailed` 显式改名为 `blockingFailedRawJobSeverity`，并新增按冻结集判定的 `blockingFailed`。理由：留给证据里两个都叫 `blockingFailed` 的字段会让人误读「到底阻断了没有」。
7. 新增日志：`规则冻结 ruleFingerprint=… catalog=… compatPolicy=…`（使「这次 run 用了哪一版」至少进日志）。

### 1.5 注释口径纠正（发现即修的文档漂移）

- `JobResultParser.CheckInfo.blocking()` / `blockingFailures()`：javadoc 原文写「ERROR 只记录」，与 §7.3 冲突。**行为不变**（该适配器只做作业 JSON 的忠实透传），只改注释并写明职责分离：作业侧判据 vs 平台口径。
- `MetricQualityGate`（platform-common 接口）：javadoc 原文写「severity=BLOCKING 且未通过 → FAIL」，是旧口径。改为「按冻结规则集解析出的有效严重度为 `BLOCKING`/`ERROR` 且未通过 → FAIL；`WARN`/`INFO` 只记录」。
- `PipelineService.persistQuality`：javadoc 原文「其余为记录项」已改。
- `MetricPublishValidator`：`blocked(List<Check>)` / `failedRules(List<Check>)` 的旧实现在 javadoc 与实现里都写死 `"BLOCKING".equals(c.severity())`，既漏检 `"error"`（大小写）／`""`／`null`／未登记码，又会把目录判为 `WARN`/`INFO` 的观察项误当阻断。已改为走 `RuleSeverity.blocks(rules, ...)`，并**不再回读** `Check.severity()` 做第二次判定（否则重新引入第二个所有者）。
- `MetricPublishValidator` 新增 `@Deprecated` 单参重载 `blocked(List<Check>)` / `failedRules(List<Check>)`，内部转调 `DEFAULT.freeze(null)`。**这是过渡兼容**：总控已指出「内部转调默认冻结集」若没有移除条件，就会与版本化路径并列为第二个所有者 ⇒ **移除条件见 `raw/q01-version-scope.md`**（须待表落地后调用方一律传冻结集）。

**没有改**：`spark-jobs`（一行未动）、指导书正文、看板、冻结契约、任何数据库业务数据。

---

## 2. 每条规则的新旧严重度对照表

「落库 severity」= 实测 `analytics_meta.data_quality_result.severity` 的当前值（改前=改后，因为**作业侧未改**）。
「平台判定」= `RuleSeverity.of(ruleCode)` 的结论（改动后生效）。

| # | rule_code | 落库 severity（作业回传，实测） | 平台旧判定 | 平台新判定 | 是否阻断（新） | 依据摘要 |
|---|---|---|---|---|---|---|
| 1 | `AMOUNT_RECONCILE` | BLOCKING | BLOCKING | BLOCKING | **是** | 金额对账（§5.4.2 误差 <0.01） |
| 2 | `REQUIRED_FIELD_NULL_RATE` | ERROR | 记录 | **BLOCKING** | **是（新阻断）** | 必需字段缺失被下游**静默丢弃**（`DwdSql.behaviorClean` 过滤 `payload_user_id/product_id IS NOT NULL` 且不写 reject） |
| 3 | `ENUM_WHITELIST` | ERROR | 记录 | **BLOCKING** | **是（新阻断）** | 非法行为类型同样**静默丢弃**；§5.4.2 批准阈值 = 0 |
| 4 | `EVENT_ID_UNIQUE` | ERROR | 记录 | **WARN** | 否 | 下游确定性去重（`ROW_NUMBER()` + `rn=1`）+ 阈值 0.0005 来自 §5.4.2 已批准值，**未放宽** |
| 5 | `ADS_STAGING_PRESENT` | BLOCKING | BLOCKING | BLOCKING | 是 | 暂存表缺失 |
| 6 | `ADS_STAGING_KEY_NOT_NULL` | BLOCKING | BLOCKING | BLOCKING | 是 | 暂存主键空 |
| 7 | `ADS_STAGING_SNAPSHOT_ISOLATION` | ERROR | 记录 | **WARN** | 否 | D-142：历史 staging 快照存在本身不是错误；判阻断会与「清理在 dqc 之后」形成死锁 |
| 8 | `ADS_DWS_FUNNEL_RECONCILE` | BLOCKING | BLOCKING | BLOCKING | 是 | 漏斗层间对账 |
| 9 | `PUB_DQ_BLOCKING_RULES` | BLOCKING | BLOCKING | BLOCKING | 是 | 发布侧阻断规则汇总 |
| 10 | `PUB_DQ_EVENT_ID_UNIQUE` | ERROR | 记录 | **WARN** | 否 | 同 4（发布侧复检） |
| 11 | `PUB_STAGING_READY` | BLOCKING | BLOCKING | BLOCKING | 是 | 暂存就绪 |
| 12 | `PUB_FORMAL_PARTITION_MATCH` | BLOCKING | BLOCKING | BLOCKING | 是 | 正式分区匹配 |
| 13 | `PUB_POINTER_SWITCH` | INFO | 记录 | INFO | 否 | 发布操作审计项 |
| 14 | `PUB_STAGING_PRUNE` | INFO | 记录 | INFO | 否 | 发布操作审计项 |
| 15 | `MXP_SNAPSHOT_PINNED` | BLOCKING | BLOCKING | BLOCKING | 是 | 快照钉住（覆盖「混入其他快照数据」） |
| 16 | `MXP_EXPORT_ROWS` | BLOCKING | BLOCKING | BLOCKING | 是 | 导出行数 = 分区行数 |
| 17 | `MXP_EXPORT_COMPLETE` | BLOCKING | BLOCKING | BLOCKING | 是 | 8 张表导出完整 |
| 18 | `MP_MANIFEST_TABLES` | （不进库） | BLOCKING | BLOCKING | 是 | 发布对账；本次仅**登记**，行为不变 |
| 19 | `MP_ADS_WRITE_MATCH` | （不进库） | BLOCKING | BLOCKING | 是 | 同上 |
| 20 | `MP_METRIC_VALUE_COUNT` | （不进库） | BLOCKING | BLOCKING | 是 | 同上 |
| 21 | `MP_ACTIVE_SNAPSHOT` | （不进库） | BLOCKING | BLOCKING | 是 | 同上 |
| — | `MP_OLD_ACTIVE_ARCHIVED` | （不进库） | INFO | INFO（未登记） | 否 | 旧 ACTIVE 归档情况说明 |
| — | 48 行 legacy `NULL` severity | NULL | 记录 | **兜底按阻断** | （兜底） | **待裁项**，未回填、未改判 |

**变红的只有 2 条从「记录」升为「阻断」**（`REQUIRED_FIELD_NULL_RATE`、`ENUM_WHITELIST`），**降级的只有 3 条**（`EVENT_ID_UNIQUE`、`PUB_DQ_EVENT_ID_UNIQUE`、`ADS_STAGING_SNAPSHOT_ISOLATION` → WARN）。

### 2.1 当前线上影响（实测，非推算）

| run | 未过的**阻断级**规则（读侧按 `ruleCode` 归一化后，即 F-94 正解） | 旧结论 | 新结论 | 说明 |
|---|---|---|---|---|
| **47**（当前 `ACTIVE`，`S20260901_47`） | 无（3 条未过规则在目录里都是 `WARN`，不阻断） | PASS | **PASS（不变）** | **当前看板质量卡片不会变红**；若照库中字面 `ERROR` 判则会误染红（§2.2） |
| 24（`ARCHIVED`，`S20260901_24`） | 同上：`EVENT_ID_UNIQUE`(WARN)、`ADS_STAGING_SNAPSHOT_ISOLATION`(WARN)、`PUB_DQ_EVENT_ID_UNIQUE`(WARN) ⇒ **无阻断项** | PASS | **PASS（不变）** | 真库实跑见 §3.5（`-Dmetric.it=true`） |

> **对盘点阶段一处推算的更正（重要，实测推翻了静态推断）**：盘点期曾推断「run 24 会被新门判 FAIL，`AnalysisGoldenMySqlIT` 的 `qualityStatus()==PASS` 必翻」。**这个推断是错的**——错在把「DB 里的 `severity` 字面量」当成了「平台判定」。DB 里那 3 行确实是 `ERROR`，但 `ERROR` 在新口径下被 `RuleSeverity` 归一化为 **`WARN`（不阻断）**，而 `AMOUNT_RECONCILE` 等所有真正的 `BLOCKING` 规则都 `passed=1`。⇒ **run 24 与 run 47 在新口径下都是 PASS**。
> 因此 `AnalysisGoldenMySqlIT` 的 `qualityStatus()==PASS` **断言无需修改**；实际只改了：① 私有 `MetaQualityGate` 复刻体按新口径重写（消除「同一数据两个结论」的结构性风险）；② 过时注释（它把 `S20260901_24` 称作「线上真实 ACTIVE 快照」，实测该快照自 2026-09-10 起就是 `ARCHIVED`，当前 ACTIVE 是 `S20260901_47`）。
> 教训已写进该 IT 的类注释：**测试里复刻生产判定的私有类，不会跟着生产改口径**——这是 F-88 真正踩到的坑。

补充实测（`raw/db-l-golden-values-24-vs-47.txt`）：`S20260901_24` 与 `S20260901_47` 两快照的黄金指标值**逐项相同**（gmv 2042.0000 / net_sale 1493.0000 / paid_order_cnt 5.0000 / pv 7.0000 / refund_rate 0.6000），两快照的质量行也逐行相同。因此该 IT 断言的是「历史黄金数据集」而非「当前 ACTIVE」，把它当作归档数据读是**成立**的，只是原注释把它误称为「线上真实 ACTIVE 快照」——已更正。

---

### 2.2 二次勘误（2026-09-12，独立验证提出，**前述 §2.1 的"更正"本身是错的，以本节为准**）

> 上文 §2.1 那段「对盘点阶段一处推算的更正」**作废**。它把「落库归一化（只对**未来写入**生效）」错当成「读侧会按 `ruleCode` 重算」，因此推出「run 24 与 run 47 在新口径下都是 PASS」。**这个结论是错的**——读侧当时并没有归一化。

**事实（代码 + DB 双向实测，独立验证方提出，本人逐条复核采信）：**

1. **代码**：读侧链路是 `AnalysisService.java:320` → `DataQualityGate.blockingFailuresForRun` → `RuleSeverity.blocks(库中字面 severity)`；`RuleSeverity.blocks("ERROR")` 返回 **`true`** ⇒ 库里的字面 `ERROR` 被当作阻断。
2. **DB**：run 24 的 3 条未过规则在 `analytics_meta.data_quality_result` 里是**字面 `severity='ERROR', passed=0`**（`EVENT_ID_UNIQUE` / `ADS_STAGING_SNAPSHOT_ISOLATION` / `PUB_DQ_EVENT_ID_UNIQUE`）。本轮实测（`raw/db-m-f94-severity-drift.txt`，`SELECT rule_code, severity, passed, COUNT(*) ... WHERE run_id IN (24,47) GROUP BY ...`）：
   - `ADS_STAGING_SNAPSHOT_ISOLATION | ERROR | 0 | 2`、`EVENT_ID_UNIQUE | ERROR | 0 | 2`、`PUB_DQ_EVENT_ID_UNIQUE | ERROR | 0 | 2`（**每个 run 各 3 行**）
   - 所有真正 `BLOCKING` 的规则（`AMOUNT_RECONCILE`/`ADS_STAGING_PRESENT`/`ADS_DWS_FUNNEL_RECONCILE`/`PUB_DQ_BLOCKING_RULES`/`PUB_STAGING_READY`/`PUB_FORMAL_PARTITION_MATCH`/`MXP_*` …）全部 `passed=1`
3. **连带后果（§2.1 完全没提）**：当前 `ACTIVE` 快照 `S20260901_47` 同样有这 3 条 `ERROR/passed=0` ⇒ 若读侧照字面判，**用户可见的看板质量卡片会由 PASS 变 FAIL**。

**前一结论作废。** 正解与本次落地（裁决：**读侧必须按 `ruleCode` 归一化**）：

- `RuleSeverity` 新增读侧判据 `blocks(String severity, String ruleCode, Integer passed)`：**先按 `ruleCode` 查目录**，只有规则码为空/未登记时才回退字面 severity 再兜底阻断（保守默认不松）。
- `DataQualityGate.blockingFailures(...)`、`QualityChecker.anyBlockingFailed(...)` 全部改走该判据 ⇒ 「阻断与否」只由规则码决定。
- ⚠️ **本条已于 2026-09-14 被推翻（见 §2.3 顶部横幅与 §2.5）**：原文写「**故 run 24 与 run 47 在归一化后都是 PASS**：那 3 条规则码在新口径下都是 `WARN`（不阻断）… 看板质量卡片**维持 PASS，不染红**」。实测这两个重复率规则码是 **`THRESHOLD_OBSERVATION`** 且**已超阈值约 40 倍 / 143 倍** ⇒ 升为阻断 ⇒ **两者均为 `FAIL`，看板质量卡片会染红**。该后果已列为 **待裁-11**（须总控显式裁决，本泳道不静默应用）。
- ⚠️ 与 §2.1 的关键差别：`PASS` 不再来自「静态推算」，而是来自**归一化实现 + 真库实跑**（见 §3.5 的 `-Dmetric.it=true` 实跑记录）。断言方向回到 `PASS` 是**归一化的结果**，不是为让 IT 变绿而放宽——阈值一个字未动（`NULL_RATE_MAX=0.001`、`DUP_RATE_MAX=0.0005`）。
  > **【整条作废，2026-09-14】** 这里为 `PASS` 做的辩护**不成立**：那次实跑确实通过，但通过的原因是目录把
  > `EVENT_ID_UNIQUE` / `PUB_DQ_EVENT_ID_UNIQUE` 写成**固定 `WARN`**，即**无条件放行超阈值的高重复率**。
  > 「阈值一个字未动」是真的，但**登记档位**被改错了 ⇒ 结论仍错。现口径见 **§2.5**：两者为
  > `THRESHOLD_OBSERVATION`，超阈值升为阻断 ⇒ run 24/47 均为 `FAIL`。**「为变绿而放宽」的辩解在本条上不适用，
  > 因为当时绿的本身就是一个错误结论**——这是本轮最值得留痕的教训。
- 这也正是 **F-94**（落库字面 severity 与平台判定漂移）的读侧正解，见 §5.1。

---

### 2.3 二次勘误（2026-09-12，第二次；**上述 §2.2 的表述本身也不准确，以本节为准**）

> **🚫 本节下方结论已于 2026-09-14 被推翻，不得再引用（四次修正）。**
> 本节（及 §2.2）的核心结论是「run 24 / run 47 归一化后 = **PASS**，看板质量卡片维持 PASS 不染红」。
> **实测推翻**：`EVENT_ID_UNIQUE` 与 `PUB_DQ_EVENT_ID_UNIQUE` 并非固定 `WARN`，而是
> **`THRESHOLD_OBSERVATION`（条件观察项）**——未超批准阈值才是观察项，**超阈值升为阻断**（§7.3.1 line 522）。
> 真库只读实测 run 24/47 的 `EVENT_ID_UNIQUE` `error_rate=0.020408` 对 `threshold='<=0.0005'`（约 **40 倍**）、
> `PUB_DQ_EVENT_ID_UNIQUE` `error_rate=0.071429`（约 **143 倍**），**两行 `passed=0` 都是真实超阈值**。
> ⇒ **run 24 与 run 47 在新口径下均为 `FAIL`；`AnalysisGoldenMySqlIT` 的期望值已由 `PASS` 改为 `FAIL`。**
> 本节保留原文仅作留痕（第二次勘误的推理链本身没错——错在**目录当时把那两个码写成了固定 `WARN`**，
> 等于把超阈值的高重复率无条件放行；该映射已改为 `THRESHOLD_OBSERVATION`）。
> §2.2 的「归一化后仍判 FAIL 是误读」这句话**同样失效**。**以 §2.5 为准。**

> 说明：本节**不删除** §2.1 / §2.2 的原文（留痕要求），只在其后追加更正。**§2.2 的「前一结论作废」仍然有效**（即 §2.1 那段"两者都是 PASS"的静态推算确实是错的，理由也对：读侧当时并未归一化）；但 §2.2 的**措辞**把「修复前读侧照字面 severity 判会得到什么」与「按 `ruleCode` 归一化之后得到什么」混在了一起，容易被读成"归一化之后仍然 FAIL"。**实测不是这样。**

**实测事实（真库实跑，非推算；证据见 §3.5 与 `raw/db-m-f94-severity-drift.txt`、`raw/e6/e7`）：**

1. run 24 与 run 47 各只有 3 条未过规则，**且两者完全相同**：`ADS_STAGING_SNAPSHOT_ISOLATION` / `EVENT_ID_UNIQUE` / `PUB_DQ_EVENT_ID_UNIQUE`，字面 `severity='ERROR'`、`passed=0`。
2. ~~这 3 个规则码在 `RuleSeverity` 目录里**全部登记为 `WARN`** ⇒ `RuleSeverity.blocks(...)` 对它们一律返回 `false`。~~ **【已证伪】** 实测：只有 `ADS_STAGING_SNAPSHOT_ISOLATION` 是固定 `WARN`（且它**确实不阻断**，见 §6.3）；`EVENT_ID_UNIQUE` / `PUB_DQ_EVENT_ID_UNIQUE` 是 **`THRESHOLD_OBSERVATION`**，`passed=0` ⇒ `resolve()` 升为 `BLOCKING` ⇒ `blocks(...)=true`。
3. 其余所有真正阻断级的规则（`AMOUNT_RECONCILE` / `ADS_STAGING_PRESENT` / `PUB_DQ_BLOCKING_RULES` / `PUB_STAGING_READY` / `MXP_*` …）**全部 `passed=1`**；实测对照查询"除这 3 条外还有没有未过的规则"返回 **0 行**。**（本条仍然成立，未变）**
4. ~~⇒ **`qualityStatus` 归一化后 = `PASS`**（run 24 与 run 47 都是），`AnalysisGoldenMySqlIT` 的 `qualityStatus()==PASS` 断言**实测通过**。~~ **【已证伪】** ⇒ **`qualityStatus` 归一化后 = `FAIL`**（run 24 与 run 47 都是）；`AnalysisGoldenMySqlIT` 的期望值**已改为 `FAIL`**（改后**只验到 test-compile exit 0，运行期未测**——该 IT 本轮因 DB 冻结禁跑）。

**因此结论精确表述为（下表已于 2026-09-14 重写）：**

| 读侧实现 | run 24 / run 47 的 `qualityStatus` | 用户可见后果 |
|---|---|---|
| **未归一化**（照库中字面 `severity` 判） | `FAIL` | 结论**恰好正确**，但**理由错**：它把 `ADS_STAGING_SNAPSHOT_ISOLATION`（真 WARN）也算成阻断 —— **这是缺陷**（判据是字面量而非规则码，换个作业回传值就翻） |
| ~~**按 `ruleCode` 归一化**~~（当时的目录把所有 3 码写成固定 `WARN`） | ~~`PASS`~~ | ~~行为正确~~ **【已证伪】**：把超阈值约 40 倍 / 143 倍的高重复率**无条件放行**，违反 §7.3.1 line 522 |
| **按冻结规则集解析**（`resolve`，本轮最终落地） | **`FAIL`** | 与「两个重复率规则**真实超阈值**」的事实一致 —— **行为正确** |

⚠️ **旧版此处写过「`FAIL` 是修复前的缺陷、`PASS` 是修复后的正确行为」，该判断整体作废**：实际恰好相反——`PASS` 那一版才是把超阈值放行的错误行为。**判据的正确性不看结论是不是 `FAIL`，而看它是否按 `(source_scope, rule_code, version)` 的版本化定义解析**（§7.3.1 line 520/524）。

**另一个实测到的独立缺陷（与上面无关，见 §2.4）**：该 IT 的 6 处读调用原本都传 `null`（取 `ACTIVE`），于是信封断言**跟着 ACTIVE 指针漂移**，实测报 `expected: "S20260901_24" but was: "S20260901_47"`——这不影响质量口径结论，但会让该 IT 随指针推进而无谓变红。

---

### 2.4 附带修正：`AnalysisGoldenMySqlIT` 的信封断言与 `ACTIVE` 指针解耦（实测）

**现象（实测）**：首次实跑该 IT 报 3 个失败，全部是同一个断言：

```
AnalysisGoldenMySqlIT.overviewMatchesGoldenValues:85
expected: "S20260901_24"
 but was: "S20260901_47"
```

`salesMatchesGoldenValues`、`rfmComesFromProfileAggregation` 同样（后者是 `filters` 断言）。同时 **`qualityStatus()` 与质量卡断言都没有失败**——即当时红的只是快照号，不是质量口径。
（**注**：这句话写于 2026-09-12，当时 `qualityStatus` 期望值是 `PASS`。该期望值已按 §2.5 的实测改为 `FAIL`，故「质量卡断言不失败」**仅是当时那次运行的观测**，不代表最终态。）

**根因（代码实测）**：该 IT 的类注释一直声称 `{@value #SID}`「只把它当历史黄金数据集读，故断言本身不受 ACTIVE 指针变化影响」，但 6 处读调用**全部传 `null`**：

- `AnalysisService.pin()`（`AnalysisService.java:287-290`）：`explicit = requested != null && !requested.isEmpty()`，**传 `null` ⇒ `explicit=false` ⇒ 走 `adsReader.activeSnapshotId()`**。
- 实测当前 `ACTIVE` 是 `S20260901_47`（`analytics_metric.metric_snapshot`，version 12），而断言期望 `S20260901_24`（`ARCHIVED`）⇒ **注释承诺的"不受指针影响"从未成立**，断言反而**跟着指针漂移**。

**修正**：6 处读调用改为**显式点名 `SID`**（`pin(explicit)` 按快照号直查 `metricStore.findSnapshot()`，**不要求 `ACTIVE`**，`AnalysisService.java:294`；另有既有用例 `unknownSnapshotDoesNotFallBackToActive` 证明显式号不存在时返回空信封而不回退）。`rfm` 的 `filters` 断言相应改为引用常量，不再硬编码字符串。

**为什么这不是"为变绿而改松断言"**：
- **期望值一个没改**（仍是 `S20260901_24`、黄金值仍是 2042.00/5/7/0.6000 等），改的是**调用命中哪个快照**，让被测对象与断言指向同一数据集。
- 若按"改期望为 `S20260901_47`"的捷径，断言就变成"服务返回当前指针"的同义反复，**反而更松**。故未采用。
- 该 IT 是**只读**的（`MySqlMetricRead`/`meta_app` 两个只读账号 + 全部 `SELECT`；`metric_read` 同账号传读写两个位置，写路径在本类用不到），符合新版 IT 纪律。

**跑前/跑后库未变（实测，`raw/e6-IT-before-db-state.txt` vs `raw/e7-IT-after-db-state.txt`）**：

| 项 | 跑前 | 跑后 | 判定 |
|---|---|---|---|
| `analytics_metric.metric_snapshot` `COUNT(*)` | 12 | 12 | 未变 |
| `MAX(id)` | 27 | 27 | 未变 |
| `ACTIVE` 行数 / 快照号 / version / `active_flag` | 1 / `S20260901_47` / 12 / 1 | 1 / `S20260901_47` / 12 / 1 | 未变 |
| `published_at` | `2026-09-12 21:29:27.321` | `2026-09-12 21:29:27.321` | 未变 |
| `analytics_metric.metric_value` `COUNT(*)`（全表 / ACTIVE 快照） | 110 / 10 | 110 / 10 | 未变 |
| `analytics_meta.data_quality_result` run 47 行数 / 未过数 | 22 / 3 | 22 / 3 | 未变 |

---

### 2.5 四次修正（2026-09-14）：条件观察项必须按阈值升级 ⇒ run 24/47 均为 `FAIL`

> **本节是质量口径的最终结论。§2.1 / §2.2 / §2.3 里所有「归一化后 = PASS」的表述一律作废，只作留痕。**

**被推翻的是什么**：§2.2 / §2.3 把 `EVENT_ID_UNIQUE` 与 `PUB_DQ_EVENT_ID_UNIQUE` 当成**固定 `WARN`（无条件不阻断）**。
这个映射是**错的**：它使「高重复率」与「未超阈值」在判定上不可区分，等于**把超阈值的高重复率直接放行**，
违反 §7.3.1 line 522「原始重复事件仅在确定性去重已证**且重复率不超批准阈值**时为观察项；**超过阈值阻断**」。

**实测事实（只读 `SELECT analytics_meta.data_quality_result`，见 `raw/db-m-f94-severity-drift.txt`）：**

| run | rule_code | `error_rate` | `threshold` | `passed` | 倍数 |
|---|---|---|---|---|---|
| 24 | `EVENT_ID_UNIQUE` | `0.020408` | `<=0.0005` | `0` | 约 **40×** |
| 24 | `PUB_DQ_EVENT_ID_UNIQUE` | `0.071429` | `0.0005` | `0` | 约 **143×** |
| 24 | `ADS_STAGING_SNAPSHOT_ISOLATION` | `0.500000` | `0 个非本次快照分区（观察项）` | `0` | —— |
| 47 | `EVENT_ID_UNIQUE` | `0.020408` | `<=0.0005` | `0` | 约 **40×** |
| 47 | `PUB_DQ_EVENT_ID_UNIQUE` | `0.071429` | `0.0005` | `0` | 约 **143×** |

⇒ 前两行的 `passed=0` 是**真实超阈值**，不是误判。只有 `ADS_STAGING_SNAPSHOT_ISOLATION` 是固定 `WARN` 且**确实不阻断**
（其阈值字面量本身标注「观察项」）。

**落地改动**：`QualityRuleCatalog` 中这两个码的 `severityMode` 由固定档位改为
**`THRESHOLD_OBSERVATION`**：未超批准阈值＝观察项，超阈值＝升为阻断。
`RuleSeverity.resolve`（`RuleSeverity.java:284-286`）据此**只依据 `passed`** 升级
（不重算 `error_rate`——阈值比较发生在产生 `passed` 的上游，判定侧只消费结论，避免第二处阈值所有者）。

**直接后果（必须显式报告，不得淡化）**：

1. run 24 与 run 47 的 `qualityStatus` **均为 `FAIL`**（不再是 `PASS`）。
2. **当前 `ACTIVE` 快照 `S20260901_47` 也翻为 `FAIL` ⇒ 线上看板质量卡片会变红**（不只是归档的 run 24）。
3. `AnalysisGoldenMySqlIT.overviewMatchesGoldenValues` 的期望值已由 `PASS` 改为 `FAIL`。
4. 这是**收紧一个原本过松的契约**的必然结果，**不是缺陷**；但它**改变线上可见结论**，
   故列为 **待裁-11**，须总控**显式裁决**后方可落地，本泳道**不静默应用**。

**改动前后对比：**

| # | 项 | 结论 |
|---|---|---|
| 1 | 阈值常量 | **一个字未动**（`NULL_RATE_MAX=0.001`、`DUP_RATE_MAX=0.0005`） |
| 2 | 两个码的登记档位 | `WARN`（固定） ⇒ `THRESHOLD_OBSERVATION`（条件） |
| 3 | run 24 / 47 结论 | `PASS` ⇒ **`FAIL`** |
| 4 | 线上看板质量卡 | 绿 ⇒ **红** |
| 5 | `AnalysisGoldenMySqlIT:130` 期望 | `PASS` ⇒ **`FAIL`** |

**验证边界**：改动经 E2 全反应堆复跑（**616 run / 0 failure / 1 error**，唯一 error 属他泳道 V25-S03），
本泳道 5 个测试类 **52 run / 0 failure / 0 error**；`AnalysisGoldenMySqlIT` 改后
**只验到 test-compile（exit 0），运行期未测**（DB 硬冻结禁跑 `-Dmetric.it=true`）。

**同类更正**：`INVENTORY.md` §3.2（「改判 WARN 不改变平台判定」）、§4 表格（`AnalysisGoldenMySqlIT` 行）、
§6 待裁-3（「ACTIVE 快照结论不变、看板不染红」）三处同一错误结论**已一并更正并留痕**；
`raw/q01-e2-regression-and-rerun.md` §7 记录了该潜在缺陷的定位过程与教训。

---

## 3. 测试：命令 / 数量 / 退出码（实测）

环境（固定值）：`JAVA_HOME=D:\Develop\JAVA17`，Maven `D:\apache-maven-3.9.14\bin\mvn.cmd -o`，本地仓库 `D:\maven_repository`。

### E1 编译

```powershell
$env:JAVA_HOME='D:\Develop\JAVA17'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o "-Dmaven.repo.local=D:\maven_repository" `
  -f 'D:\Develop_code\GraduationProject\analytics-server\pom.xml' `
  -pl platform-common,warehouse-pipeline,metric-analysis -am -DskipTests compile
```

- **退出码：0**，`BUILD SUCCESS`
- 日志：`raw/e1-compile.log`

### E2 全 reactor 单测

```powershell
$env:JAVA_HOME='D:\Develop\JAVA17'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o "-Dmaven.repo.local=D:\maven_repository" `
  -f 'D:\Develop_code\GraduationProject\analytics-server\pom.xml' `
  "-Dmaven.test.failure.ignore=true" test
```

- **退出码：0**（因 `maven.test.failure.ignore=true`，退出码不反映测试红绿；红绿**只**从日志/surefire 报告读）
- `BUILD SUCCESS`，7 个模块（含 aggregator）全 `SUCCESS`，Total time 42.298 s
- 日志：**`raw/e2-full-reactor-q01d.log`**（2026-09-14T12:43:21+08:00）

**① 2026-09-14 实测（最新，以此为准）**

| 模块 | Tests run | Failures | Errors | Skipped |
|---|---|---|---|---|
| platform-common | 81 | 0 | 0 | 0 |
| connection-ingestion | 156 | 0 | 0 | 0 |
| warehouse-pipeline | 132 | 0 | **1** | 0 |
| metric-analysis | 48 | 0 | 0 | 0 |
| ai-decision | 91 | 0 | 0 | 0 |
| platform-app | 108 | 0 | 0 | 0 |
| **合计** | **616** | **0** | **1** | 0 |

**唯一的 1 个 error 不是本泳道**：`SparkStageExecutorSmokeTest.realSparkOdlLoadsGoldenDataset`
⇒ `MissingConfigurationException: 真实 Spark 冒烟默认关闭：未提供 -Dv25.spark.it=true`
⇒ 由 `SparkItGuard.requireEnabled` 拦下。三条硬证据：
① `SparkItGuard.java`（未跟踪新文件）javadoc 自述归属 **「V25-S03 R-5」**；
② 时间戳 `2026/9/14 12:35` —— **落在本轮运行窗口内、由他泳道写入**；
③ 与 §② 上次有效 E2 对比，该用例彼时为 `Errors: 0 / Time elapsed: 34.77 s`（真跑并通过），
现为 `0.023 s` ⇒ 由「真跑」变「门禁拦下」。**本泳道未改该文件一行，不认领此 red。**

> **② 2026-09-12 23:02 记录（保留留痕，其两个红现已由对应泳道修掉）**
>
> | 模块 | Tests run | Failures | Errors |
> |---|---|---|---|
> | platform-common | 49 | **1** | 0 |
> | connection-ingestion | 156 | 0 | 0 |
> | warehouse-pipeline | 124 | 0 | 0 |
> | metric-analysis | 47 | 0 | 0 |
> | ai-decision | 91 | 0 | 0 |
> | platform-app | 100 | **1** | 0 |
> | **合计** | **567** | **2** | 0 |
>
> 当时的两个红：`WarehouseNameLiteralGateTest.noBareWarehouseNameLiterals`（§9.5 → V25-T01）
> ＋ `IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate`（F-96 → V25-T02）。
> **2026-09-14 复跑两者均已转绿**（前者 `Tests run: 9, Failures: 0`，后者 `Tests run: 9, Failures: 0`），
> 证明它们确与本泳道无关、且已由相应泳道修掉。

### E2-d 本轮抓到并修掉的一个**真回归**（本泳道自身缺陷，如实记录）

复跑时 `PipelineServiceTest` **15 个用例里 10 个失败**（该文件此前 15/15 全绿）。完整定位过程与证据见
**`raw/q01-e2-regression-and-rerun.md`**，此处只记结论：

- **现象**：1 个断言 `PIPELINE_QUALITY_FAILED` 却得到 `STAGE_INTERNAL`，其余为 `SUCCESS` 却得到 `FAILED`。
- **定位**：surefire 无 `-output.txt`，于是单跑复现后从 `TEST-*.xml` 的 `<system-out>` 取到原文——
  `阶段 QUALITY_CHECK 内部错误: Cannot invoke "QualityChecker$QualitySummary.results()" because "quality" is null`。
  同段还打出了 `规则冻结 ruleFingerprint=6bc2…c6 catalog=qrc-1 compatPolicy=compat-v1`
  ⇒ **`rulesFor(run)` 本身正常**，真因是 `check(...)` 返回 null。
- **根因**：`QualityChecker` 有三个 `check` 重载（两参/三参/四参带 `FrozenRules`），生产已改调**四参**，
  而测试仍 stub **三参**；Mockito 对未 stub 的四参调用返回 `null` ⇒ NPE。
  **属 stub 签名漂移，是本泳道迁移遗漏的调用点，责任在本泳道。**
- **修复**：`PipelineServiceTest` 4 处改为四参（`:145/:371` 直接 stub，`:456/:482` 的 `thenAnswer` 追加 `getArgument(3)`），
  并在 `:145` 处留注释说明重犯后果。全仓复查 `qualityChecker\.check|\.check\(any` 确认无同类遗漏。
- **复跑**：`Tests run: 15, Failures: 0, Errors: 0` / `BUILD SUCCESS` / `exit 0`。

#### `platform-common` 的既有红（**与本次改动无关，已用基线复现证明**；2026-09-14 已由他泳道转绿）

```
WarehouseNameLiteralGateTest.noBareWarehouseNameLiterals
→ spark-jobs/src/main/scala/.../TradeDwdJob.scala:145 → * 建后即删，未碰 `dw_dwd.dwd_order_detail`）
→ spark-jobs/src/main/scala/.../SurrogateKey.scala:160 → *    table spark_catalog.dw_dwd.dwd_order_detail: ...
```

复现基线的命令（把 HEAD 检出到独立 worktree，不碰当前工作树）：

```powershell
git -C D:\Develop_code\GraduationProject worktree add --detach D:\Develop_code\GraduationProject\.f88-baseline-wt HEAD
$env:JAVA_HOME='D:\Develop\JAVA17'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o "-Dmaven.repo.local=D:\maven_repository" `
  -f 'D:\Develop_code\GraduationProject\.f88-baseline-wt\analytics-server\pom.xml' `
  -pl platform-common -am "-Dtest=WarehouseNameLiteralGateTest" "-DfailIfNoSpecifiedTests=false" test
```

- **退出码：1**，同样的 1 处失败、**同样的两个 `spark-jobs` 文件**（基线 HEAD 为 `dba4381`）
- 日志：`raw/e0-baseline-HEAD-platform-common.log`
- ⇒ 该红是 **HEAD 既有**，成因在 `spark-jobs` 的**注释**里出现了裸库名，而 `spark-jobs` 是本次禁改范围。**未修，留给总控裁决**（改一行注释即可转绿，但它不是本泳道的文件）。

#### `platform-app` 的那 1 个红（2026-09-12 已由独立验证方定位，**非本泳道造成，记为 F-96**；2026-09-14 已转绿）

`platform-app` 模块 100 run / 1 failure：`IngestionManifestSourceSchemaTest:168` 的**前置断言**失败。

- **根因（验证方实测，本人未复跑）**：**未跟踪数据漂移** —— `landing/manifests` 实有 **43** 个，用例硬编码期望 **39** 个。
- **为什么不是本泳道造成**：`landing/**` 被 `.gitignore:30` 忽略；F-88 的改动**未触碰 `platform-app`** 任何文件（`git status --porcelain` 可验，见 §7）。
- **处置**：登记为新条目 **F-96**（用例硬编码清单数期望、依赖未跟踪数据，脆弱）⇒ 后续由独立泳道改成「按实际清单推导期望」或显式固定夹具。**本泳道不改该用例**。
- **2026-09-14 复跑状态**：`platform-app` 108 run / **0 failure / 0 error**，该用例连同 `IngestionManifestSourceSchemaTest` 均已转绿。
- 完整输出见 `raw/e2-all-modules-test.log`（2026-09-12）与 `raw/e2-full-reactor-q01d.log`（2026-09-14）。

### 3.3 实现过程中实测踩到并修掉的第二个坑（**生产代码缺陷**，留痕）

> **⚠ 时态说明（2026-09-14 补）**：本节记录的缺陷**当轮已修复**，但**修复形态后来被推翻**：
> 当时引入的 `registered(String)` 查询与 `blocks(severity, ruleCode)` 三参重载，**现已全部删除**，
> 改由 `resolve(FrozenRules, code, passed)` / `blocks(FrozenRules, code, passed)` 承担。
> 理由见 §1.1：那些重载会**静默丢弃传入的 severity 实参**，是**双所有者缺陷**。
> 本节保留原文作为**演进留痕**，但其中的 API 名**不代表最终形态**，不得据此引用。

**症状**：E2 首轮红 6 个，全部集中在本次改动涉及的类上：

```
RuleSeverityTest.readSideNormalizationByRuleCode:117         expected true but was false
DataQualityGateTest.blockingFailureFailsTheGate:41           expected "FAIL" but was "PASS"
DataQualityGateTest.errorFailureAlsoFailsTheGate:50          expected "FAIL" but was "PASS"
DataQualityGateTest.errorFailureIsListedAsBlocking:60        [] 里找不到 "ADS_STAGING_SNAPSHOT_ISOLATION"
DataQualityGateTest.nullPassedOnBlockingIsTreatedAsFailed:86  expected "FAIL" but was "PASS"
DataQualityGateTest.unknownSeverityIsTreatedAsBlocking:93     expected "FAIL" but was "PASS"
PipelineServiceTest.errorSeverityFailureBlocksPublishAndLeavesActiveUntouched:432  expected FAILED but was SUCCESS
```

**根因（实测定位，非推测）**：`UNREGISTERED = BLOCKING`（两者**取值相同**），而 `blocks(severity, ruleCode)` 当时用

```java
String normalized = of(code);
if (!UNREGISTERED.equals(normalized)) { ... }   // ← 恒为 false
```

判断「是否已登记」。已登记的阻断规则返回的也正是 `"BLOCKING"` ⇒ `UNREGISTERED.equals("BLOCKING")` 恒真 ⇒ **恒走回退分支，规则目录完全没生效**。两个方向的后果都真实出现：

- 已登记为**阻断**的规则被当成未登记 ⇒ 回退到库里过时的字面 `WARN` ⇒ **该阻断的放行了**；
- **未登记**的新规则若库里恰好写着字面 `WARN` ⇒ 也被放行 ⇒ **兜底保守默认失效**（`RuleSeverityTest:124` 正是这条）。

现场证据（`raw/e2b-diagnosis-ruleseverity.log`；临时加一行 `System.out` 打印后实跑）：

```
[F88-DIAG] of(AMOUNT_RECONCILE)=BLOCKING blocks(WARN,AMOUNT_RECONCILE)=false   ← 错：应 true
[F88-DIAG] blocks(WARN,BRAND_NEW_RULE)=false  src=.../platform-common/target/classes/
```

**修法**：新增显式注册表与查询方法，判据不再依赖「比较 `of()` 的返回值」：

```java
private static final java.util.Set<String> REGISTERED = java.util.Set.of(/* 33 个码 */);
public static boolean registered(String ruleCode) { ... }
// blocks(severity, ruleCode)：registered(ruleCode) ? blocks(of(ruleCode)) : blocks(severity)
```

并静态交叉核对「`of()` 的 switch 里的码」与「`REGISTERED` 表」：**各 33 个，双向差集均为空**。修后 `RuleSeverityTest` 8/8 绿，`-Dtest=RuleSeverityTest` 退出码 0。

> **这是本泳道唯一一次生产代码缺陷**，且**不是**被断言逼出来的：是新写的负向测试 + 线下真库形状（字面 `ERROR` 的 WARN 规则）把它照出来的。
> 教训：**别名常量不能用来判「存在性」**（`UNREGISTERED` 就是 `BLOCKING` 的别名）。

### 3.4 夹具缺陷：用错了规则码（**测试侧的实质发现**）

除上面 1 个生产缺陷，另有 6 个红是**夹具写错规则码**；修夹具（**不动断言方向、不动任何阈值**）：

| 测试类 | 原夹具 | 问题 | 修法 |
|---|---|---|---|
| `DataQualityGateTest` | `result(severity, passed)` 把 `ruleCode` **写死成 `EVENT_ID_UNIQUE`** | 该码是已登记的 **WARN** ⇒ 「BLOCKING 未通过 ⇒ FAIL」等 5 条用例其实一条都没测到 | 改为 `result(ruleCode, severity, passed)`，每个用例显式给出与语义相符的码 |
| `PipelineServiceTest.errorSeverityFailureBlocksPublish…` | `qualityRow("PUB_DQ_EVENT_ID_UNIQUE","ERROR",0)` | 同上是 WARN 码 ⇒ 该用例实际测的是「WARN 失败 ⇒ 仍发布」，断言由 FAILED 变 SUCCESS | 换成阻断码 `ADS_STAGING_PRESENT`（字面仍写 `ERROR`，正好证明目录覆盖字面） |

`DataQualityGateTest` 顺带增强为 **13** 个用例，新增两条 F-94 正/反向对照：

- `registeredBlockingCodeOverridesStaleWarnLiteral`：库里字面 `WARN` + 阻断码 ⇒ **FAIL**（该阻断的必须阻断）
- `registeredWarnCodeOverridesStaleErrorLiteral`：库里字面 `ERROR` + 三个 WARN 码 ⇒ **PASS**（就是线上 run 24/47 的真实形状）

并把「未知 severity」拆成两条、写清回退语义：未登记码 + 空/未知 severity ⇒ FAIL（保守默认）；未登记码 + 字面 `WARN` ⇒ PASS（回退采用更具体的信息）。

> 与 §3.2 的区别：§3.2 是**夹具自身构造错误**（`Map.of` 不收 null）导致测试自己崩；本节是**夹具语义错误**（用错规则码）导致测试测了另一件事 —— 后者更危险，因为它是**静默**的：用例名与断言都还在，只是不再覆盖目标行为。
> **与 E2-d 的区别**：E2-d 是**测试没跟上生产签名变更**（stub 三参 vs 生产四参）⇒ Mockito 返回 `null` ⇒ 同样表现为 `STAGE_INTERNAL`。三者症状相同、根因不同，是本泳道最值得留痕的一条经验（见 §3.2 末的判读启发式）。
> 这也解释了为什么「测试全绿」在本轮不能作为口径正确的证据：**夹具里的规则码本身就是被归一化的输入**。

### E2 补充：目标模块单独跑（`failure.ignore` 关闭，退出码可判）

```powershell
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o "-Dmaven.repo.local=D:\maven_repository" `
  -f 'D:\Develop_code\GraduationProject\analytics-server\pom.xml' -pl warehouse-pipeline -am test
```

- **退出码：1** —— 唯一原因是上游 `platform-common` 的**既有红**（同上）导致 reactor 在该模块中止；`warehouse-pipeline` 本身在此之前已跑完并全绿（`raw/e2-warehouse-pipeline-test.log`）。

```powershell
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o "-Dmaven.repo.local=D:\maven_repository" `
  -f 'D:\Develop_code\GraduationProject\analytics-server\pom.xml' -pl metric-analysis,ai-decision -am `
  -Dmaven.test.failure.ignore=true test
```

- **退出码：0**；`metric-analysis` 47/47 绿、`ai-decision` 91/91 绿
- 日志：`raw/e2-metric-analysis-test.log`

### 3.5 真库集成测试 `AnalysisGoldenMySqlIT`（`-Dmetric.it=true`，实测）

> **⚠ 2026-09-14 状态：本轮「未做」。** 依总控 DB 硬冻结纪律，本轮**禁止** `-Dmetric.it=true`、
> 禁止任何连库集成测试、禁止启停服务，只允许只读 `SELECT`。故下面这段是 **2026-09-12 的历史实测**，
> **不是本轮结论**；该 IT 在收窄后**尚未复跑**，其 6/6 通过**不得**当作收窄后已验收。
> 收窄改动（`AnalysisGoldenMySqlIT` 参数化 + `RuleSeverity.blocks(rules, …)`）仅**验证到 test-compile 通过（exit 0）**，
> **运行期行为未测**。

**（以下为 2026-09-12 历史实测）**

```powershell
$env:JAVA_HOME='D:\Develop\JAVA17'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o "-Dmaven.repo.local=D:\maven_repository" `
  -f 'D:\Develop_code\GraduationProject\analytics-server\pom.xml' `
  -pl metric-analysis -am "-Dmetric.it=true" `
  "-Dtest=AnalysisGoldenMySqlIT" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

- **退出码：0**，`BUILD SUCCESS`
- **`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`**（6 个用例全绿）
- 日志：`raw/e2-it-analysis-golden-mysql.log`
- 跑前/跑后库状态：`raw/e6-IT-before-db-state.txt` / `raw/e7-IT-after-db-state.txt`，逐项一致（见 §2.4 对比表）

**这次实跑给出的两个结论（都是运行期观测，不是推算）——⚠ 第 1 条已于 2026-09-14 作废：**

1. ~~`qualityStatus()==PASS` 断言**真的通过** ⇒ F-94 读侧归一化的正解在真库上成立（§2.3）。~~
   **【作废】** 那次通过是因为目录当时把两个重复率码写成**固定 `WARN`**（无条件放行超阈值）。
   按 §2.5 的 `THRESHOLD_OBSERVATION` 口径，该断言**现期望 `FAIL`**，且**本轮未复跑**。
2. 该 IT 记录的真库质量卡实测值同时通过：`ruleCount=4`、`passedCount=3`、`failedRules=["EVENT_ID_UNIQUE"]`
   ——这条**仍然成立且与本轮改动无关**：它是 **ADS 规则明细**（4 条里 3 条过），
   与**质量门阻断结论**是两个口径，故改用 `THRESHOLD_OBSERVATION` 后**这三项期望值不变**。

> 纪律说明：按新版 IT 纪律，本轮**只跑了 `AnalysisGoldenMySqlIT`**（只读、`metric_read`/`meta_app` 两个只读账号、全部 `SELECT`）。`MetricAdsMySqlIT` / `MetricPublisherMySqlIT` **一律未跑**——两者 JDBC 直连生产指标库且用写账号 `metric_pub`，`cleanup()` 含 `DELETE FROM metric_snapshot WHERE runtime_profile_id = ?`（已登记 **F-100**）。本轮亦未跑任何 `failsafe`/`verify`/`clean`/Flyway goal。

### 本次 Maven reactor 覆盖范围（重要，避免误读为「全仓绿」）

`analytics-server/pom.xml` 的 `<modules>` 实测为：`platform-common, connection-ingestion, warehouse-pipeline, metric-analysis, ai-decision, platform-app`。**`source-profiles` 不在 reactor 里**（有源码目录与 `fixture-b.v1.json`，但不是被构建的模块）。⇒ 上面 **616** 个用例（2026-09-14 实测）是**这 6 个模块**的总数；另有 `AnalysisGoldenMySqlIT` 6 个用例需显式开 `-Dmetric.it=true` 才跑（默认跳过，不计入 616，且本轮因 DB 冻结**未跑**）。

### 3.1 测试改动清单（**2026-09-14 实测数量**）

| 文件 | 模块 | 实测 Tests run | 动作 |
|---|---|---|---|
| `metric/RuleSeverityTest.java` | platform-common | **14** | **重写扩充**（原 7）。`ALL_REGISTERED_CODES` **33 个**逐码断言；`catalogCoversEveryRegisteredCode` 断言目录 **35** 条 = 33 + 2；`everyCodeHasRationale` 断言 33 条非空中文依据；3 处原三参 `blocks(...)` 调用改为 `resolve(rules, code, passed)`（旧重载已删） |
| `pipeline/DataQualityGateTest.java` | warehouse-pipeline | **19** | **重写扩充**（原 10）。新增按冻结集判定、未登记码同时进 `unregisteredRules` 与 `blockingRules`、每条规则留兼容解释等用例 |
| `pipeline/QualityCheckerSeverityTest.java` | warehouse-pipeline | **5** | **新增**。Landing 规则严重度由目录驱动（AMOUNT/REQUIRED/ENUM = BLOCKING，EVENT_ID_UNIQUE = 条件观察项）；超阈值的重复率必须升为阻断 |
| `metric/publish/MetricPublishValidatorSeverityTest.java` | metric-analysis | **10** | **新增**。含 `decisionAlwaysEqualsRuleSeverityOwnerWhateverLiteralIsGiven`：6 个码 × `passed∈{0,1,null}` × 9 种字面 severity（含 `"error"`/`" warn "`/`""`/`null`/未登记码）⇒ 结论恒等于所有者口径 |
| `guard/RuleSeverityPathConsistencyTest.java` | **platform-app** | **4** | **新增跨模块护栏**。必须放 platform-app：只有它同时可见 `DataQualityGate` 与 `MetricPublishValidator`（实测：放 metric-analysis 编译失败 `找不到符号 DataQualityResult/DataQualityGate`）。两条路径 × 35 条目录 × `passed{1,0}` = **70 次比对**，实跑一致；失败时阻断码 **31** 个。详见 `raw/q01-cross-module-guard-evidence.md` |
| `pipeline/PipelineServiceTest.java` | warehouse-pipeline | **15** | 4 处 `check(...)` stub 由三参改四参（见上文 E2-d）；`qualityFailureBlocksPublish` 断言保留 |
| `JobResultParserTest.java` | warehouse-pipeline | — | **仅改注释**，12 条断言全部保持（理由见 `INVENTORY.md` §4.1） |
| `metric/analysis/AnalysisGoldenMySqlIT.java` | metric-analysis | 6（**需 `-Dmetric.it=true`**） | 改为**参数化**并消除明文口令：库名/账号从 `TestIsolationGuard.requiredProperty` 取；私有 `MetaQualityGate` 复刻体走 `QualityRuleCatalog.DEFAULT.freeze(null)` + `RuleSeverity.blocks(rules, …)`;类注释新增「§F-88 四次修正」并把旧的 24/47 PASS 结论标注**已证伪、不得再引用** |

#### 新增的负向测试（核心交付，§7.3.1 line 522 / line 528）

- `PipelineServiceTest.errorSeverityFailureBlocksPublishAndLeavesActiveUntouched`
  ⇒ 断言 run `FAILED`、错误码 `PIPELINE_QUALITY_FAILED`、`PUBLISH_METRIC` 阶段 `FAILED`、
  **`verify(publisherPort, never()).publish(any())`**、阶段证据含 `prePublishGate` 与规则码。
- `PipelineServiceTest.duplicateRateWithinThresholdIsObservationAndStillPublishes`
  ⇒ 重复率 `0.000000 ≤ 0.0005` ⇒ 观察项，**不阻断**、正常发布。
- `PipelineServiceTest.duplicateRateBeyondThresholdBlocksPublish`
  ⇒ 两个重复 `event_id`（重复率 `0.5 ≫ 0.0005`）⇒ `EVENT_ID_UNIQUE` 由 WARN **升为阻断** ⇒ 必须阻断发布。
  （**此用例是对旧用例的实质更正**：旧版名为 `warnSeverityFailureStillPublishes`，主张「重复率 0.5 仍发布成功」，
  与 §7.3.1 line 522「测试不得为通过把高重复率直接放行」冲突；已按指导书改写。）

### 3.2 实现过程中实测踩到并修掉的一个坑（`STAGE_INTERNAL` 陷阱，留痕）

`qualityFailureBlocksPublish` 一度报 `expected: "PIPELINE_QUALITY_FAILED" but was: "STAGE_INTERNAL"`。

根因（实测定位）：该测试的**新夹具** `amountRuleFailed()` 只设了 `ruleCode/layer/severity/passed`，`checkCount`/`errorCount` 为 `null`；而 `QUALITY_CHECK` 阶段证据里的 `landingRules` 用 `Map.of(...)` 构造，`Map.of` **不接受 null 值** ⇒ 抛 NPE ⇒ 被阶段框架的 `catch (Exception)` 兜成 `STAGE_INTERNAL`。修法：夹具补齐 `checkCount/errorCount/threshold/detail`（并把「为什么必须非空」写进该夹具的 javadoc）。诊断过程留痕 `raw/e3-diagnosis-fixture-npe.txt`。

> 这是**测试夹具**的问题，不是生产逻辑的问题——但它说明「断言错误码」比「断言异常消息」更能直接暴露口径问题。
>
> **同类坑的第二次出现（2026-09-14）**：`PipelineServiceTest` 10 个用例因 stub 三参重载而拿到 `null`，
> 症状同样是 `STAGE_INTERNAL`。⇒ **`STAGE_INTERNAL` 在本模块几乎总是「NPE 被兜住」，首选排查方向是「某个 mock 返回了 null」**，而不是去读被判定的业务逻辑。此判读经验已写入 `raw/q01-e2-regression-and-rerun.md` §3。

---

## 4. 快照语义（§1.1）落地情况

| # | 条款 | 状态 | 落点 / 证据 |
|---|---|---|---|
| 1 | 新运行失败 ⇒ 原 `ACTIVE` 不变、仍可读 | ✅ 既有实现成立 + 本次加发布前断言 | `MySqlMetricStore.publish()` 同事务；`PipelineService.java` `PUBLISH_METRIC` 前置 `prePublishGate` |
| 2 | 成功且原子发布完成后旧 `ACTIVE` 才 `ARCHIVED` | ✅ 既有实现成立（未改） | 归档与激活同 `@Transactional` |
| 3 | `ARCHIVED` 不可变、经明确历史入口按权限读、不混入默认查询 | ⚠️ **未验证** | 见 `INVENTORY.md` §5 第 6 行；**未测** |
| 4 | `ACTIVE` 唯一性按发布作用域 | ✅ DB 约束成立 / ❌ 查询侧全库取最新（**待裁项，未修**） | `uk_active_profile(runtime_profile_id, active_flag)`；`db-k` 实测 `active_cnt=1`；`MySqlMetricStore.activeSnapshotId()` 与 `MetricAdsReader.activeSnapshotId()` 无 profile 条件 |
| 5 | 负向测试：`ERROR` 失败 ⇒ 不发布新快照 ⇒ 原 ACTIVE 不变且可读 | ✅ **已补单测**；真库端到端**未测** | §3.1 两个新用例；`raw/db-k-metric-snapshot-after-impl.txt` |

真库复核（`raw/db-k-metric-snapshot-after-impl.txt`，2026-09-12）：

- `ACTIVE`：`S20260901_47`（run 47，version 12，`active_flag=1`，published 2026-09-12 21:29:27.321）
- 按 `runtime_profile_id` 分组：`1 → active_cnt=1, actives=S20260901_47`（**唯一性成立**）
- `FAILED`：`S20260901_38`（run 38，`active_flag=NULL`，`published_at=NULL`，`failure_reason=MP_ADS_WRITE_FAILED: ... Column 'favorite_category' cannot be null`）——**真实失败运行从未变成 ACTIVE**，这是 §1.1 第 1/3 条的真库负向证据
- `S20260901_24`：`ARCHIVED`，`active_flag=NULL`，published 2026-09-10 20:44:11.886

---

## 5. 未做 / 未测清单（显式声明）

### 未做（有意不做）

1. **未改 `spark-jobs`**（一行未动，本次禁改范围）。后果：落库 `severity` 列仍是作业回传的 `ERROR`；全仓 `"WARN"` 字面量仍为 **0 次**，因此 DB 里查不到任何 WARN 行。口径靠**落库归一化**落地，不靠作业产出。
2. **未改指导书正文**（§7.3 保持原文，按 D-142 §1）。
3. **未动数据库业务数据**：全部 SQL 为 `SELECT`。
4. **未起停 809x 服务、未跑集群作业**。
5. **未 git add / commit / push**：改动全部留在工作树。
6. **未回填 48 行 legacy `NULL` severity**（裁决 1：**不回填、历史 run 结论不改、兜底按阻断**）。⇒ 这是**已知且有意保留的口径漂移**，不是漏做：`analytics_meta.data_quality_result` 实测 `total_rows=467`、`warn_rows=**0**`、`null_rows=48`、`info_rows=3`（`raw/db-m-f94-severity-drift.txt`）。原因：`severity` 列存的是**作业回传的字面量**，而全仓 `"WARN"` 字面量为 0 次（作业侧未改，见第 1 项），故库中永远查不到 WARN；**口径由读侧 `RuleSeverity` 归一化承担**，不依赖列值被回填。副作用是「直接查库看 severity 会得到与平台判定不同的结论」——这正是 F-94，复现方式见 §5.1。
7. **未改 `spark-jobs` 注释**：`TradeDwdJob.scala` / `SurrogateKey.scala` 中的裸库名字面量（会导致既有 `WarehouseNameLiteralGateTest` 红）**继续不做**，归属与执行等裁决（见 §5.1 与 §7）。
8. **未修**（全部登记为待裁项或独立泳道）：`activeSnapshotId()` 全库查询（**F-92**）、`qualityStatus` 读取时现算（**F-93**）、`MetricPublishValidator` 大小写敏感、`MetricAdsMySqlIT`/`MetricPublisherMySqlIT` 直连生产库写账号（**F-100**）。
9. **未建迁移文件、未编号**（**2026-09-14 新增**）：迁移/DDL 版本号**只由总控分配**。故 `quality_rule_definition` 的 DDL **只以草案形式**放在 `raw/q01-quality-rule-definition-ddl-draft.sql`（实测全仓 `quality_rule_definition` 命中 **0** 次），**未创建任何 `migration/` 文件、未申请版本号**。
10. **未给 `data_quality_result` 增列**（**2026-09-14 新增**）：该表实测**没有** `rule_version` / `effective_severity` / `compat_policy_version` / `rule_fingerprint` 任一列 ⇒ **没有持久化载体能证明某个 run 用的是哪一版规则**。已提出增列方案（待裁-9），**未执行**。
11. **未改他泳道文件**：`SparkStageExecutorSmokeTest.java`、`SparkItGuard.java`（**V25-S03**）本轮**一行未动**，也不认领。

### 未测（做了但没有运行期观测）

1. **`AnalysisGoldenMySqlIT`（真库读侧）—— 2026-09-14 本轮未跑**：受 DB 硬冻结限制（禁 `-Dmetric.it=true`、禁连库 IT）。收窄后的参数化改动**只验到 test-compile（exit 0）**，**运行期行为未测**。§3.5 的 6/6 属 2026-09-12 历史结论，**不得引用为本轮验收依据**。
2. **真库端到端失败发布实验**：需起服务 / 跑集群作业 ⇒ 未做。所有「不发布新快照」的证据是**单测 + 真库只读观测 + 事务语义源码实读**三者，不是一次真失败发布。
3. **变异测试（护栏判别力）**：**未做**。未人为篡改 `RuleSeverity.resolve` 去验证 `RuleSeverityPathConsistencyTest` 会变红 ⇒ 其判别力只有 oracle 构造层面的论证，**无实测支撑**。
4. **另外 3 个受 `-Dmetric.it=true` 控制的真库 IT**：`MetricAdsMySqlIT`、`MetricPublisherMySqlIT`（二者写生产库，**F-100 禁止跑**）——**未测且不应在本轮测**。
5. **跨 run 版本化行为**：`quality_rule_definition` 表未落地 ⇒ 「同一规则在不同 run 采用不同版本时结论如何」**无法测**，因为**没有存储载体**。
6. **§1.1 第 6 条（ARCHIVED 不可变 / 历史入口权限 / 不混入默认查询）**：源码实读但**无运行期验证**。
7. **多 profile 取错 ACTIVE 的实际行为（F-92 反例）**：真库当前只有 1 个 profile（`runtime_profile_id=1`）有 ACTIVE 行，**本轮无法构造两 profile 各有一个 ACTIVE 的反例** ⇒ 未测。复现入口见 §5.1。
8. **`platform-app` 那 1 个红的具体根因**：未逐条定位（不在本泳道范围；验证方已登记 **F-96**；2026-09-14 已转绿）。
9. **17 个规则码在 `spark-jobs` 里的确切逐码 `file:line`**：未逐行记录。
10. **`THRESHOLD_OBSERVATION` 的实际阈值边界**：只测了 `0.000000`（不阻断）与 `0.5`（阻断）两侧，**恰好等于阈值 `0.0005` 的边界值未测**。

---

## 5.1 复现条目：F-92 / F-93 / F-94（只读，可独立复跑）

> 三条都是**本轮实测确认存在、但按裁决不由本泳道修改**的缺陷/漂移。以下给出**最小复现**，便于验证方独立复核。所有 SQL 均为 `SELECT`；引用表一律带 schema 名。

### F-94 —— 落库字面 `severity` 与平台判定漂移（读侧已修，列值不回填）

- **实测事实**：`analytics_meta.data_quality_result` 全表 `COUNT(*)=467`，其中 `severity='WARN'` 行数 **0**、`IS NULL` **48**、`'INFO'` **3**、`'ERROR'` **120**、`'BLOCKING'` **296**；run 24 与 run 47 各有 3 条 `severity='ERROR', passed=0` 的行，规则码为 `ADS_STAGING_SNAPSHOT_ISOLATION` / `EVENT_ID_UNIQUE` / `PUB_DQ_EVENT_ID_UNIQUE`，而这三码在 `RuleSeverity` 目录里**登记为 `WARN`**。
- **只读复现 SQL**（输出留痕 `raw/db-m-f94-severity-drift.txt`）：

```sql
SELECT rule_code, severity, passed, COUNT(*) AS rows_cnt
FROM analytics_meta.data_quality_result
WHERE run_id IN (24,47) AND (passed IS NULL OR passed <> 1)
GROUP BY rule_code, severity, passed ORDER BY rule_code;

SELECT COUNT(*) AS total_rows, SUM(severity='WARN') AS warn_rows,
       SUM(severity IS NULL) AS null_rows, SUM(severity='INFO') AS info_rows
FROM analytics_meta.data_quality_result;
```

- **旧/新判据差异的量化**（`raw/db-o-old-predicate-vs-new.txt`）：

```sql
SELECT run_id,
       SUM(CASE WHEN UPPER(COALESCE(TRIM(severity),'')) NOT IN ('WARN','INFO')
                 AND (passed IS NULL OR passed <> 1) THEN 1 ELSE 0 END) AS old_predicate_blocking_rows,
       SUM(CASE WHEN rule_code IN ('EVENT_ID_UNIQUE','PUB_DQ_EVENT_ID_UNIQUE',
                                   'ADS_STAGING_SNAPSHOT_ISOLATION','PUB_POINTER_SWITCH',
                                   'PUB_STAGING_PRUNE','MP_OLD_ACTIVE_ARCHIVED') THEN 0
                WHEN passed IS NULL OR passed <> 1 THEN 1 ELSE 0 END) AS new_predicate_blocking_rows
FROM analytics_meta.data_quality_result WHERE run_id IN (24,47) GROUP BY run_id;
```

  实测：run 24 与 run 47 均为 `old=3 / new=0` ⇒ **旧判据把质量卡片读成 FAIL，新判据读成 PASS**。对照查询（"除这 3 码外是否还有未过的规则"）返回 **0 行**，即新判据的 PASS 不是靠放宽阈值得到的。

### F-92 —— `activeSnapshotId()` 不带 `runtime_profile_id`（全库取最新，未修）

- **位置**：`MySqlMetricStore.activeSnapshotId()` `:113-119`（SQL `:116`：`SELECT snapshot_id FROM metric_snapshot WHERE status = ? ORDER BY id DESC LIMIT 1`）、`MetricAdsReader.activeSnapshotId()` `:41-47`（SQL `:44`）。两处**都没有** `runtime_profile_id` 条件；正确重载在 `MetricAdsReader.java:55-61`。
- **影响面（实读源码）**：消费点 `MetricAdsReader.selectActive` `:64`、`selectOneActive` `:73`、`MySqlMetricStore.query` `:102-104` ⇒ 多 profile 部署下会读到**别的 profile 的 ACTIVE**。
- **复现条件（本轮未构造）**：需要两个 `runtime_profile_id` 各有一个 `status='ACTIVE'` 的快照行。真库实测只有 `runtime_profile_id=1` 有 ACTIVE ⇒ **本轮无法构造反例**，故仅登记位置与反例构造法。只读核对 SQL：

```sql
SELECT runtime_profile_id, status, COUNT(*) AS cnt, GROUP_CONCAT(snapshot_id) AS sids
FROM analytics_metric.metric_snapshot GROUP BY runtime_profile_id, status;
```

  实测：`1/ACTIVE/1/S20260901_47`，其余 profile 无 ACTIVE 行。

### F-93 —— `qualityStatus` 在读取时现算（历史快照结论可被追溯改写，未修）

- **位置**：`AnalysisService.java:318` `qualityStatus(MetricSnapshot, List<String>)` → `:320` `qualityGate.statusForRun(meta.getPipelineRunId())`。
- **后果（实测推得）**：快照行**不冻结**质量结论 ⇒ 只要 `data_quality_result` 或判定口径变化，历史快照的 PASS/FAIL 会**被追溯改写**（本轮 F-94 归一化正是这种变化：同一份 run 24 数据，口径切换前后结论不同）。
- **实测状态**：run 24（`ARCHIVED`）在归一化口径下实测 `PASS`（§3.5），与当前 ACTIVE `S20260901_47` 一致；即"历史结论会被改写"这一性质成立，但**本轮没有观测到负面的改写结果**。

### `spark-jobs` 归属（本泳道未改动一行，见 §5 未做第 7 项）

- **本泳道对 `spark-jobs/**` 一行未改**，也不认领、不暂存该路径；`git add` 一律按 §7 清单执行（其中不含 `spark-jobs`）。
- **工作树现状（实测，HEAD=`8983616`）**：`git status --porcelain` 中**已无任何 `spark-jobs` 或 `source-profiles` 条目**——相关改动已由总控入库于 **`fc53f62`**（提交信息含「TradeDwdJob.scala:176 叙述修正（M3 所有，1 行注释）」）。⇒ 任务书中的表述「工作树存在 1 行注释改动」**已过时**，现为：**该改动已入库，归属 M3**。
- **实测行内容**（`spark-jobs/src/main/scala/com/graduation/analytics/job/TradeDwdJob.scala:176`；注意路径**无** `spark/` 段）：

  ```scala
  |  t.dt                                  -- 必须与 DDL 列序一致：分区列落在最末位（见方法上方说明）
  ```

- **与 F-88 无关**：它是叙述/注释修正，不改任何 severity 字面量 ⇒ 「全仓 `"WARN"` 字面量为 0 次」这一结论**不受影响**。
- **未做的另一处**：`SurrogateKey.scala` 的裸库名注释（`WarehouseNameLiteralGateTest` 红的另一个来源）本轮**同样未动**。

---

## 6. 状态收口（四级制：提交完成 / 测试通过 / 限定验收 / 完整验收）

> **口径**（严格区分，不得混用）：
> **提交完成** ＝ 已 `git add` + `commit` + `push` 入库；
> **测试通过** ＝ 有本轮实跑命令 + 退出码 + 用例数与失败数；
> **限定验收** ＝ 只在明确声明的范围内验收过；
> **完整验收** ＝ 全链路 + 真库 + 端到端 + 生产进程内均验过。

### 6.1 四级状态（本泳道）

| 级别 | 结论 | 依据 |
|---|---|---|
| **提交完成** | ❌ **未完成** | **一次 `git add` 都没执行**（总控统一入库，泳道禁令）。工作树仍是 ` M` / `??` 状态。故**不可声称「已提交」** |
| **测试通过** | ⚠ **部分通过** | E1 编译 **exit 0**；E2 全反应堆 **exit 0**、**616 run / 0 failure / 1 error**，其中唯一 error 为他泳道 `V25-S03` 的 Spark 门禁（已举证）；本泳道 5 个测试类 **52 run / 0 failure / 0 error**。**收尾复跑**（2026-09-14T12:53，文档更正后）：选定用例 **82 run / 0 failure / 0 error**、exit 0（= 本泳道 52 + 同模块既有 30；`platform-common` 14、`warehouse-pipeline` 39、`metric-analysis` 10、`platform-app` 4）。**但** `AnalysisGoldenMySqlIT` 因 DB 冻结**未跑** ⇒ 真库路径**无本轮证据** |
| **限定验收** | ⚠ **仅限「单进程内、默认冻结集、无真库」范围** | 已在：目录口径一致性（70 次比对）、未登记码兜底、超阈值升阻断、发布前门短路（`never()).publish`）。**未**在：真实服务进程内、跨 run 版本化、真库读侧运行期 |
| **完整验收** | ❌ **未达成** | 缺口四项：① 无持久化载体 ⇒ 不能声称「版本化已闭合」；② `AnalysisGoldenMySqlIT` 未跑；③ 端到端失败发布（起服务 + 集群作业）未验；④ 未做变异测试 ⇒ 护栏判别力无实测支撑 |

### 6.2 逐项对照 `docs/项目完整实施指导书 V2.5.md` §7.3.1（line 518–528）

| 行 | 指导书要求（摘） | 本泳道落点 | 状态 |
|---|---|---|---|
| `:520` | 严重度口径全文保持 `BLOCKING`/`ERROR` 失败即阻断，`WARN`/`INFO` 展示不阻断 | `RuleSeverity.resolve().blocks()` 为唯一判据；`spark-jobs` 一行未改 | ✅ 代码闭合；测试 52 例绿 |
| `:522` | 超阈值必须阻断；**测试不得为通过把高重复率直接放行** | 旧「0.5 重复率仍发布」用例**已改写**为 `duplicateRateBeyondThresholdBlocksPublish`；`THRESHOLD_OBSERVATION` 使超阈值项升为阻断 | ✅ 已改；**但**导致 run 24/47 由 PASS 翻 FAIL ⇒ **待总控裁决** |
| `:524` | 未登记规则码必须**同时**报未登记 **且** 停止发布；接口同时展示兼容解释 | 未登记码同时进 `unregisteredRules` 与 `blockingRules`；每条规则留 `ruleCode(declared→effective): explanation` | ✅ 已实现并测试 |
| `:526` | 三项金额校验各自独立验证 | ① 支付 vs 订单总额 = `AMOUNT_RECONCILE`（已实现）；② `ORDER_ITEM_AMOUNT_FORMULA`（DWD，**未实现**）；③ `DWD_DWS_AMOUNT_RECONCILE`（DWS，**未实现**） | ⚠ **仅 ①** 有实现；②③ 已在目录中登记但**无校验代码** ⇒ **不得声称三项已验** |
| `:528` | 负向：不通过 ⇒ 不发布，旧 `ACTIVE` 不变 | `prePublishGate` 断言 + `never()).publish(any())`；`ACTIVE` 指针仍只有 1 行且未被改写（只读实测） | ✅ 单进程内闭合；端到端未验 |

### 6.3 逐项状态（含未验项，不得略去）

| # | 事项 | 状态 | 依据 / 缺口 |
|---|---|---|---|
| 1 | 严重度目录 `RuleSeverity` 单一所有者（置于 `platform-common`） | **测试通过** | `RuleSeverityTest` **14** 例绿 |
| 2 | 落库归一化（`PipelineService`） | **测试通过**（**属 V25-Q02 前置件，不由 Q01 覆盖**） | `PipelineServiceTest` **15** 例绿；迁移已完成并编译通过 |
| 3 | 读侧按目录口径归一化 | **限定验收** | `DataQualityGateTest` **19** 例 + 跨模块护栏 **4** 例；**未**在生产服务进程内观测；真库 IT 本轮未跑 |
| 4 | 发布对账统一走 `RuleSeverity`（裁决 4） | **测试通过** | `MetricPublishValidatorSeverityTest` **10** 例绿（含 6 码 × 3 passed × 9 字面 severity） |
| 5 | `WARN` 不阻断 / `INFO` 不阻断 | **测试通过** | `duplicateRateWithinThresholdIsObservationAndStillPublishes` 等 |
| 6 | 未登记规则码兜底 + 报未登记 | **测试通过** | `DataQualityGateTest` + 护栏 `unregisteredCodeWithHarmlessLiteralStillStopsPublishOnBothPaths` |
| 7 | 跨模块两路径一致性护栏 | **测试通过** | `RuleSeverityPathConsistencyTest` 4 例、70 次比对、31 个阻断码；**未做变异测试** |
| 8 | 真库 IT `AnalysisGoldenMySqlIT` | **未做（本轮禁跑）** | DB 硬冻结；仅 test-compile 通过，运行期未测 |
| 9 | 真库端到端失败发布（负向） | **未验** | 需起服务 + 跑集群作业，本轮禁止 |
| 10 | 版本化冻结集持久化 | **未做（待总控分配迁移号）** | `quality_rule_definition` 全仓 0 命中；DDL 草案仅在 `raw/` |
| 11 | 变异测试（护栏判别力实测） | **未做** | 明示未测 |
| 12 | `platform-common.WarehouseNameLiteralGateTest` 红 | **已由他泳道转绿** | 上轮 3 run/1 fail ⇒ 现 9 run/0 fail；非本泳道 |
| 13 | `platform-app.IngestionManifestSourceSchemaTest` 红（F-96） | **已由他泳道转绿** | 上轮 100 run/1 fail ⇒ 现 108 run/0 fail；非本泳道 |
| 14 | `SparkStageExecutorSmokeTest` error | **未验（非本泳道）** | 归属 **V25-S03 R-5**（`SparkItGuard`），需 `-Dv25.spark.it=true`；本泳道未改该文件 |
| 15 | F-92 / F-93 / F-100 三项独立缺陷 | **仅登记，未修** | 复现条目见 §5.1 |
| 16 | `spark-jobs` 两条规则改判（若需） | **未做（禁改）** | 见 §5 未做第 1/7 项 |
| 17 | `AnalysisGoldenMySqlIT:130` 期望值与自身 javadoc 自相矛盾（潜在缺陷） | **已修（仅编译验证）** | 该 IT 本轮禁跑 ⇒ **缺陷被 `skip` 掩盖**：类 javadoc 已写「run 24/47 翻为 FAIL」，测试体却仍断 `PASS`。期望值已改 `FAIL`；`test-compile` exit 0；**运行期未测**。定位过程见 `raw/q01-e2-regression-and-rerun.md` §7 |
| 18 | 「条件观察项」登记档位（`EVENT_ID_UNIQUE` / `PUB_DQ_EVENT_ID_UNIQUE`） | **测试通过** | 由固定 `WARN` 改 `THRESHOLD_OBSERVATION`；超阈值升为阻断（§2.5）。**未测阈值恰等于 `0.0005` 的边界值** |

### 6.4 待裁项（原表保留，编号不变；已裁决者标注结论）

| 编号 | 待裁事项 | 现状 |
|---|---|---|
| 待裁-1 | 48 行 legacy `NULL` severity 的处置 | **已裁决（裁决 1）**：不回填、历史结论不改、兜底按阻断 ⇒ 见 §5 未做第 6 项 |
| 待裁-2 | `activeSnapshotId()` 全库查询 vs §1.1 第 4 条发布作用域 | **已裁决（裁决 2）**：确认为真实缺陷 ⇒ 登记 **F-92**，未修 |
| 待裁-3 | `qualityStatus` 是否应改为发布时冻结 | **已裁决（裁决 3）**：确认为缺陷 ⇒ 登记 **F-93**，未修 |
| 待裁-4 | `MetricPublishValidator` severity 比较大小写敏感 | **已裁决（裁决 4）**：统一走 `RuleSeverity`，大小写敏感性已消除 ⇒ 可关闭 |
| 待裁-5 | 是否要求后续在 `spark-jobs` 侧把两条规则改判 `WARN` | 未裁；口径唯一所有者已在 `RuleSeverity`，优先级降低 |
| 待裁-6 | `platform-common` 既有红（裸库名字面量） | **已裁决（裁决 6）**：授权修注释但暂缓；2026-09-14 实测已由他泳道转绿 |
| 待裁-7 | 落库/读侧归一化的**版本适用范围** | **本轮实测暴露**，详见 `raw/q01-version-scope.md` |
| **待裁-8（新）** | **迁移版本号分配** | **本轮阻塞项**：无迁移号 ⇒ 不能新建 `quality_rule_definition` 迁移文件。DDL 草案已就绪（`raw/q01-quality-rule-definition-ddl-draft.sql`） |
| **待裁-9（新）** | **`data_quality_result` 增列方案** | 建议增 `rule_version` / `effective_severity` / `compat_policy_version` / `rule_fingerprint` 四列，否则无法**证明**某 run 用的哪一版规则 |
| **待裁-10（新）** | **13 个「库中有、目录中无」的规则码按 version=1 登记** | 已依 `:524` 兜底为阻断，并登记为 version 1 待裁 |
| **待裁-11（新）** | **接受 run 24/47 由 PASS 翻 FAIL / ACTIVE 卡转红** | 这是**收紧过松契约**的必然结果，非缺陷；**须总控显式裁决**，本泳道不静默应用 |

---

## 7. 改动文件清单（工作树，未提交）

> 依据：`git status --porcelain`（HEAD = `8983616`）实测。**本泳道不改 `spark-jobs/**`**；下表中不含任何 `spark-jobs` 或 `source-profiles` 路径。

### 7.1 新增（9 项，**2026-09-14 实测清单**）

| 路径 | 说明 |
|---|---|
| `analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/RuleSeverity.java` | **严重度唯一所有者**（裁决 4 迁入；包 `com.graduation.analytics.metric`）。`resolve(FrozenRules,code,passed)` → `RuleVerdict` 为唯一判据入口；`blocks(FrozenRules,code,passed)`；`isKnownSeverity()` **仅**用于校验严重度取值域；`rationale()` 逐码中文依据；`REGISTERED` **33** 码；`UNREGISTERED = BLOCKING` 保守兜底；`UnknownRuleException`。**三个会静默丢弃 severity 实参的 `blocks` 重载已删除** |
| `analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/QualityRuleDefinition.java` | **新增**：`record (ruleCode, version, sourceScope, stage, severity, severityMode, thresholdJson, enabled, effectiveFrom, effectiveTo, rationale)`；`SCOPE_ALL="*"`；`enum SeverityMode { FIXED, THRESHOLD_OBSERVATION }`；`checksum()`（SHA-256，**排除 rationale**）；`key()`；`appliesToSource()` |
| `analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/QualityRuleCatalog.java` | **新增**：常量目录，`CATALOG_VERSION="qrc-1"`、`COMPAT_POLICY_VERSION="compat-v1"`、**35** 条定义（33 注册码 + `ORDER_ITEM_AMOUNT_FORMULA` + `DWD_DWS_AMOUNT_RECONCILE`）；`freeze(sourceId)`、`fingerprint()`、`find()`、`registered()`、`DEFAULT`；嵌套 `FrozenRules.of()` |
| `analytics-server/platform-common/src/test/java/com/graduation/analytics/metric/RuleSeverityTest.java` | **14 例**（中文 `@DisplayName`）。`catalogCoversEveryRegisteredCode` 断言 `hasSize(35)`；`everyCodeHasRationale` 断言 `hasSize(33)`；3 处旧三参 `blocks` 调用改为 `resolve(rules,…)` |
| `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/QualityCheckerSeverityTest.java` | **5 例**：Landing 规则严重度由目录驱动；超阈值重复率必须升为阻断 |
| `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/publish/MetricPublishValidatorSeverityTest.java` | **10 例**（裁决 4 新增）：含 6 码 × `passed{0,1,null}` × 9 种字面 severity 的等价性用例（**注意：字面量列表含 `null`，必须用 `Arrays.asList`，`List.of` 会 NPE**——实测踩过） |
| `analytics-server/platform-app/src/test/java/com/graduation/analytics/guard/RuleSeverityPathConsistencyTest.java` | **4 例跨模块护栏**：`DataQualityGate` 与 `MetricPublishValidator` 两条路径 × 35 条目录 × `passed{1,0}` = **70 次比对**，实跑一致；失败时阻断码 **31** 个。**必须放 platform-app**（实测放 metric-analysis 编译失败） |
| `docs/acceptance/f88-dq-severity-20260912/INVENTORY.md` | 规则码/读写点盘点 |
| `docs/acceptance/f88-dq-severity-20260912/raw/` | 证据卷（见 §7.4） |

> **注**：`platform-common/src/test/java/com/graduation/analytics/metric/` 在 `git status` 中作为**整目录未跟踪**显示，该目录下同时含他泳道的 `SparkItGuard.java`（属 **V25-S03**）。总控按**文件路径**入库，勿整目录 `git add`。

### 7.2 修改（均为 ` M`）

| 路径 | 改了什么 |
|---|---|
| `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/DataQualityGate.java` | 新增 `decisionForRun(Long, FrozenRules)`、`blockingFailuresForRun(Long, FrozenRules)`；未登记码**同时**进 `unregisteredRules` 与 `blockingRules`；每条规则留兼容解释；空/null → `UNKNOWN`（不冒充 PASS） |
| `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/QualityChecker.java` | 新增四参 `check(events, runId, batchOrderTotals, FrozenRules)`；`severity` 取 `resolve(...).effectiveSeverity()`；阻断判据走 `RuleSeverity.blocks(rules, …)`；**阈值未动**（`NULL_RATE_MAX=0.001`、`DUP_RATE_MAX=0.0005`） |
| `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/PipelineService.java` | **属 V25-Q02 前置件**：新增 `rulesFor(run)`（run 内取一次冻结集）、四参串联七个阶段、`jobEvidence` 增 `normalizedSeverity` 与冻结集口径 `blockingFailed`、`PUBLISH_METRIC` 前置 `prePublishGate`、新增冻结日志。**迁移已完成并编译通过，但不由 Q01 验收** |
| `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/spark/JobResultParser.java` | **仅 javadoc**（12 条断言未动，理由见 `INVENTORY.md` §4.1） |
| `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/DataQualityGateTest.java` | **重写为 19 例**；夹具 `result(ruleCode, severity, passed)` 显式传规则码；新增未登记码兜底、冻结节一致性、每条规则解释等用例 |
| `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/PipelineServiceTest.java` | **15 例**；4 处 `check(...)` stub 由三参改四参（本轮真回归修复，见 E2-d）；新增 `duplicateRateWithinThresholdIsObservationAndStillPublishes` / `duplicateRateBeyondThresholdBlocksPublish` |
| `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/spark/JobResultParserTest.java` | **仅注释** |
| `analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/publish/MetricPublishValidator.java` | 判定统一走 `RuleSeverity.blocks/failed`（新增 `severityOf(FrozenRules,…)` 私有助手）；新增 `blocked(List, FrozenRules)` / `failedRules(List, FrozenRules)`；旧单参重载标 `@Deprecated` 并转调默认冻结集；**不再回读 `Check.severity()` 做二次判定** |
| `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/AnalysisGoldenMySqlIT.java` | **参数化**；库名/账号改由 `TestIsolationGuard.requiredProperty` 取（**消除明文口令**）；私有 `MetaQualityGate` 走 `DEFAULT.freeze(null)` + `RuleSeverity.blocks(rules, …)`；类注释新增「§F-88 四次修正」并把旧 24/47 PASS 结论标为**已证伪**。**本轮只验到 test-compile（exit 0），运行期未测** |
| `analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/MetricQualityGate.java` | **仅 javadoc**（改为「按冻结规则集解析出的有效严重度」口径） |

> **注**：`spark-jobs/**`、指导书正文、看板、`docs/acceptance/` 下**他泳道**目录（`guideline-v24-coverage-20260912/`、`p3-02-mapping-executor-20260912/`）本泳道**一律未改、不认领、不暂存**。
> 另：`SparkStageExecutorSmokeTest.java`（` M`）与 `SparkItGuard.java`（`??`）**属 V25-S03，不是本泳道产物**，总控按路径入库时请勿归入 Q01。

### 7.3 删除 / 移动（重要：**无 git 层面的删除或移动**）

本泳道确实做过一次搬迁：`RuleSeverity` 由 `warehouse-pipeline` 上移到 `platform-common`（裁决 4）。但**两个原始文件从未进入 HEAD**（实测 `git cat-file -e HEAD:<path>` 对两者均失败），它们只存在于本会话的工作树中 ⇒ 删除后 `git status` **不显示为删除**，也**不构成重命名**。

- **已在工作树删除**（HEAD 中本就不存在）：
  - `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/RuleSeverity.java`
  - `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/RuleSeverityTest.java`
- 当前位置：`analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/RuleSeverity.java`（§7.1）
- 已实测确认 `analytics-server/warehouse-pipeline` 下**不再有**任何 `RuleSeverity*` 文件。
- ⇒ 总控按路径 `git add` 时，**只需加 §7.1 与 §7.2 的路径**；无需 `git rm`，也**不要**对 `warehouse-pipeline` 下的旧路径执行任何删除操作（已不存在）。

### 7.4 `raw/` 证据卷

**本轮（2026-09-14）新增：**

| 文件 | 内容 |
|---|---|
| `q01-version-scope.md` | **版本作用域一页结论**：表未落地 ⇒ **不能声称「版本化已闭合」**，只能声称「run 内一致」；冻结集来源与 run 内恒定性；不保证什么；`rule_version=1` 依据；批次范围被明确否决的理由；13 个「库中有、目录中无」码的登记；`compat-v1` 三点语义；`EVENT_ID_UNIQUE` 收紧的真实倍数；直接后果（run 24/47 翻 FAIL）；迁移路径；未测项 |
| `q01-cross-module-guard-evidence.md` | 跨模块护栏：为何必须落 platform-app；命令 + **exit 0** + `Tests run: 4, Failures: 0, Errors: 0`；两条 `[GUARD-EVIDENCE]` 原始行；非空泛性解读（35×2=70、31 个阻断码的构成）；oracle 判别力论证；**未做变异测试**显式声明；4 用例表；历史踩坑教训（「`BUILD SUCCESS` 但无 surefire 报告＝文件根本不在盘上」） |
| `q01-e2-regression-and-rerun.md` | **本泳道自身真回归**的定位与修复：两次 E2 对比；`STAGE_INTERNAL` 真因（stub 三参重载 ⇒ Mockito 返回 null）；4 处修复；全仓同类复查；Spark 冒烟 error 归属 **V25-S03** 的三条硬证据；构建槽检查（IntelliJ JPS 编译服务器） |
| `e2-full-reactor-q01d.log` | **最新 E2**：616 run / 0 failure / **1 error**（他泳道 Spark 门禁）；`BUILD SUCCESS`；exit 0 |
| `q01-quality-rule-definition-ddl-draft.sql` | `quality_rule_definition` DDL **草案**（**未编号、未执行**；版本号只由总控分配） |
| `q01-ddl-draft-notes.md` | 该 DDL 草案的设计说明与待裁点 |

**沿用的上一轮证据：**

| 文件 | 内容 |
|---|---|
| `db-m-f94-severity-drift.txt` | **F-94 主证据**：run 24/47 各 3 条 `ERROR/passed=0`；全表 `total=467 / warn=0 / null=48 / info=3 / error=120 / blocking=296` |
| `db-o-old-predicate-vs-new.txt` | 旧判据 vs 新判据量化：run 24/47 均 `old=3 / new=0` |
| `db-p-pre-state-24-47.txt` | IT 前 `analytics_metric` 状态（12 行 / max_id=27 / 1 ACTIVE） |
| `db-q-quality-rows-24-47.txt` | run 24/47 全部质量行逐行 |
| `e6-IT-before-db-state.txt` / `e7-IT-after-db-state.txt` | 历史 IT 跑前/跑后只读快照，逐项一致 |
| `e2-it-analysis-golden-mysql.log` | **历史**（2026-09-12）`-Dmetric.it=true` 实跑：6/6；**本轮因 DB 冻结未复跑** |
| `e5-baseline-IT-analysis-golden.log` | 基线 worktree 对照，证明历史失败与本次改动无关 |
| `e2-all-modules-test.log` | 历史 E2：567 run / 2 red（两个红现均已由他泳道转绿） |
| `e2-full-reactor-q01b.log`、`e2-full-reactor-q01c.log` | 中间轮次（q01b 已失效；q01c 含本泳道 10 个红，作为回归留痕保留） |
| `e1-compile.log` | E1 编译：exit 0 |
| `e0-baseline-HEAD-platform-common.log` | HEAD 基线复现既有红 |
| 其余 `db-a`…`db-l`、`e2b`、`e3`、`e4`、`test-lockin-*` | 沿用上一轮，未改动 |

**辅助产物（非代码，非本泳道创建）**：`D:/Develop/GraduationProject/.f88-baseline-wt`（detached HEAD `dba4381`，用于证明既有红与本次改动无关）。本轮在该 worktree 内跑过一次 `mvn test` ⇒ 其 `target/` 被写入，**源码未改动**。总控确认后可 `git worktree remove`。
