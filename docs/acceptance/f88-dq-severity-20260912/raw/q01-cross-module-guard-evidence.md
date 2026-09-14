# R01 首版缺口 #1 护栏执行证据（跨模块严重度一致性）

- 泳道：**V25-Q01「F-88 质量门严重度口径」**｜仓库 `D:\Develop_code\GraduationProject`｜分支 `remediation/r1-boundary`｜HEAD `8983616`
- 被测类：`analytics-server/platform-app/src/test/java/com/graduation/analytics/guard/RuleSeverityPathConsistencyTest.java`
- 记录时间：2026-09-14
- 口径纪律：**只写实测事实；未测项显式标「未测」。**

---

## 1. 为什么这个护栏只能在 `platform-app`

实测依赖方向（读自各模块 pom）：

```
warehouse-pipeline ──→ metric-analysis ──→ platform-common
platform-app ──→ warehouse-pipeline + metric-analysis + connection-ingestion + ai-decision + platform-common
```

- 读侧质量门 `DataQualityGate` 在 **warehouse-pipeline**
- 发布侧判定 `MetricPublishValidator` 在 **metric-analysis**
- `metric-analysis` 只依赖 `platform-common` ⇒ **它看不见 `DataQualityGate`**

⇒ 两条路径在各自模块内**永不可能出现在同一类路径上**。唯一同时依赖两者的是 `platform-app`
（已核对 `platform-app/pom.xml`：同时含 `warehouse-pipeline` 与 `metric-analysis`）。

**旁证（实测的失败尝试）**：本护栏最初试图放在 `metric-analysis`，编译报
`找不到符号 DataQualityResult / DataQualityGate`，因此移出。这不是猜测，是编译器的判定。

---

## 2. 执行命令与退出码

```powershell
$env:JAVA_HOME='D:\Develop\JAVA17'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o "-Dmaven.repo.local=D:\maven_repository" `
  -f 'D:\Develop_code\GraduationProject\analytics-server\pom.xml' `
  -pl platform-app -am "-Dtest=RuleSeverityPathConsistencyTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

| 项 | 实测值 |
|---|---|
| 反应堆 | `[1/7]`…`[7/7]`，platform-app 为 `[7/7]` |
| **Tests run** | **4** |
| **Failures** | **0** |
| **Errors** | **0** |
| Skipped | 0 |
| **退出码** | **0** |
| 结论 | `BUILD SUCCESS` |

surefire 报告：`analytics-server/platform-app/target/surefire-reports/com.graduation.analytics.guard.RuleSeverityPathConsistencyTest.txt`

---

## 3. 非空转证明（关键：防止「假绿」）

「4 个用例通过」本身不构成证据 —— 若取值域为空，断言会空转通过。故在用例内打印实测规模：

```
[GUARD-EVIDENCE] catalogDefinitions=35 comparisons=70 blockingCodesOnFailure=31
[GUARD-EVIDENCE] rowsCompared=35 blockingCodes=31 => [ADS_DWS_FUNNEL_RECONCILE, ADS_STAGING_KEY_NOT_NULL,
ADS_STAGING_PRESENT, AMOUNT_RECONCILE, DWD_DWS_AMOUNT_RECONCILE, ENUM_WHITELIST, EVENT_ID_UNIQUE,
MP_ACTIVE_SNAPSHOT, MP_ADS_ROWS_DB_MATCH, MP_ADS_ROWS_MATCH, MP_ADS_WRITE_MATCH, MP_EXPORT_FILES,
MP_HIVE_PATH_PINNED, MP_MANIFEST_SNAPSHOT, MP_MANIFEST_TABLES, MP_METRIC_DICT_VERSION,
MP_METRIC_VALUE_COUNT, MP_METRIC_VALUE_DB_MATCH, MP_OVERVIEW_CORE_NOT_NULL, MP_REQUIRED_TABLES_NONEMPTY,
MP_ROW_SHAPE_CONSISTENT, MP_VALUE_MATCH_ADS, MXP_EXPORT_COMPLETE, MXP_EXPORT_ROWS, MXP_SNAPSHOT_PINNED,
ORDER_ITEM_AMOUNT_FORMULA, PUB_DQ_BLOCKING_RULES, PUB_DQ_EVENT_ID_UNIQUE, PUB_FORMAL_PARTITION_MATCH,
PUB_STAGING_READY, REQUIRED_FIELD_NULL_RATE]
```

**读数**：

- 目录共 **35** 条定义（＝33 条已登记码 ＋ `ORDER_ITEM_AMOUNT_FORMULA` ＋ `DWD_DWS_AMOUNT_RECONCILE`）
- 一致性命中 **70** 次比较 ＝ 35 条规则 × `{passed=1, passed=0}` 两个取值 ⇒ **每条规则都真被判过**
- 未通过时有 **31** 个码判为阻断。构成核对：12 个 BLOCKING ＋ 14 个 `MP_*` BLOCKING ＋ `MP_ADS_WRITE_MATCH`（目录独有）＋ **条件观察项 `EVENT_ID_UNIQUE` 与 `PUB_DQ_EVENT_ID_UNIQUE` 超阈值升为阻断** ＝ 31 ✔
  （即：3 个固定 WARN 码中 `ADS_STAGING_SNAPSHOT_ISOLATION` 未通过**不**阻断，另两个重复率码升阻断 —— 与 §7.3.1 line 522 一致）

---

## 4. 判据为何有鉴别力（断言的独立参照物）

一条断言只有在**可能失败**时才算证据。本护栏的参照物**不是两条路径中的任何一条**，而是
`RuleSeverity.resolve(...).blocks()` —— 唯一所有者独立算出的结论：

```java
RuleSeverity.RuleVerdict verdict = RuleSeverity.resolve(RULES, code, passed);
boolean gateBlocks    = decision.blockingRules().contains(code);                        // 路径 A：读侧门禁
boolean publishBlocks = MetricPublishValidator.blocked(List.of(check(...)), RULES);      // 路径 B：发布侧
// 两者都必须 == verdict.blocks()
```

⇒ 若路径 A 或路径 B 仍持有自己的判据（例如读侧改回按库中字面 severity、发布侧改回
`"BLOCKING".equals(...)`），`blockingRules()` / `blocked()` 就会与参照物不符，
`disagreements` 非空、断言**立即变红**。

**未测项（显式声明）**：**未做**「故意改坏生产代码验证护栏变红」的变异测试
（需要修改生产类，超出本泳道允许范围）。因此「本护栏能捕获分叉」是**由构造推出的性质**，
而**不是**实测的变异结果 —— 此处不作超额声称。

---

## 5. 四个用例分别钉住了什么

| # | 用例 | 钉住的判据 |
|---|---|---|
| 1 | `bothPathsAgreeForEveryRegisteredRuleCode` | 同一冻结集下，读侧门禁与发布侧判定**对每条规则同结论**（§7.3.1 line 520 单一口径） |
| 2 | `blockingRuleCodeListsAreTheSameOnBothPaths` | 不只布尔一致：**阻断规则码清单**也必须指向同一集合（防「门禁说没阻断、发布说失败在 X」） |
| 3 | `unregisteredCodeWithHarmlessLiteralStillStopsPublishOnBothPaths` | 未登记码**即使回传 `WARN` 且 `passed=1`**，两边都停止发布；读侧必须**同时**报进 `unregisteredRules` 与 `blockingRules`（§7.3.1 line 524「不可盲信传来的 WARN」＋「报未登记规则」与「真的停止发布」不脱节） |
| 4 | `isKnownSeverityValidatesTheDomainOnly` | 总控点 ② 的边界：`isKnownSeverity` **只**校验 `severity` 取值域（对 `null`/`""`/`UNKNOWN_LEVEL` 为 false，对 `" warn "` 容忍）；并**反证**「合法字面 ≠ 可发布」 |

---

## 6. 阻塞历史（如实记录，不掩盖）

本护栏曾**写成但无法编译、无法执行**，阻塞源**不在本泳道**：

- 文件：`analytics-server/platform-app/src/test/java/com/graduation/analytics/ingestion/IngestionManifestRuntimePatrolTest.java`（**未跟踪 `??`**，属另一泳道）
- 现象：`platform-app` 的 `test-compile` 整体失败，编译错误落在该文件第 134/135 行附近
  （`未报告的异常错误 java.io.IOException`），使 `platform-app` 下**所有**测试类都无法编译 —— 包括本护栏。
- 处置：**未修改**该文件（属另一泳道所有物）；已由总控发函要求该泳道修复。
- 解除：2026-09-14 12:3x 后 `platform-app -am test-compile` 实测 `BUILD SUCCESS / exit 0`；
  本护栏随即执行通过（见 §2）。
- **附带发现（如实记录）**：阻塞期间曾出现「`BUILD SUCCESS` 但 surefire 无本类报告」——
  实测原因为**本护栏源文件当时根本不在磁盘上**（`guard/` 目录不存在，此前的写入未落盘）。
  ⇒ 教训：**`BUILD SUCCESS` 不等于测试跑过**；必须核对 surefire 报告文件存在且 `Tests run` 非空。
  本次证据已按该口径核对。

---

## 7. 与「未做」清单的关系

本证据**只**覆盖「两条路径同严重度」这一项。以下仍**未测/未做**，不得由本文件推断为已完成：

- `quality_rule_definition` 表落地与装载（表不存在）
- `data_quality_result` 增列 `rule_version`/`effective_severity`/`compat_policy_version`/`rule_fingerprint`（列不存在）
- 跨 run 版本可追溯性（无载体）
- 任何连接数据库的集成测试（`-Dmetric.it=true` 未使用）、任何 DDL/DML、任何服务启停
- 变异测试（见 §4「未测项」）

详见同目录 `q01-version-scope.md`。
