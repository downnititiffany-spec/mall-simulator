# F-88 写侧闭环（V20 四列落库）验收报告

- 泳道：**F-88 写侧闭环**（写侧把「本行按哪一版规则、判出什么档位」落进 `data_quality_result`）
- 仓库：`D:\Develop_code\GraduationProject`，分支 `remediation/r1-boundary`
- 起始 HEAD：`00a38e8e0c10bbca80d19d8ddd2d947c8726553e`（工作区干净）
- 环境：Windows + PowerShell 7，`JAVA_HOME=D:\Develop\JAVA17`，Maven `D:\apache-maven-3.9.14`，离线仓库 `D:\maven_repository`
- 时间：2026-09-14
- 提交：`f5a8c1b`（分支 `remediation/r1-boundary`，12 files changed / 1269 insertions / 26 deletions）
  - **短哈希为本报告自身的引用**：本提交在写入本文件后做过一次 `--amend`（仅追加 §9 与提交信息回填，无代码改动），因此完整 40 位哈希由 `git rev-parse HEAD` 现取；短哈希 `f5a8c1b` 与本文件内容互相一致
  - **未推送**（本分支无远端对应分支，`git branch -r` 只有 `origin/main`；推送由总控在本泳道评审后执行）
  - 提交后 `git status --porcelain=v1` 为空
- **本次未连接任何数据库**（3306 / 3307 均未连接；未启动/停止任何服务；未执行迁移）
- 原始日志：`raw/01-mvn-warehouse-pipeline-platform-common-test.log`、`raw/02-mvn-platform-app-test-compile.log`、`raw/03-static-selfcheck-grep-diffstat.log`

---

## 0. 一句话结论

**写侧三个写点的四处契约违例已修复并有实测证据**：`severity` 不再被生效档位顶替，`effectiveSeverity` 成为独立列，
`ruleVersion` 未登记时落 NULL（不是哨兵 0），`compatPolicyVersion`/`ruleFingerprint` 无论是否登记都落库；
`warehouse-pipeline` + `platform-common` 全量单测 **371 passed / 0 failed**（三条命令退出码 0）。
**但「版本化已闭合」仍不能声称**：DDL 未在目标库执行、写侧无端到端落库取证、读侧跨 run 冻结（F-93）未做。

---

## 1. 变更清单（`file:line` 为改动后行号）

| # | 文件 | 位置 | 改了什么 | 为什么 |
|---|------|------|----------|--------|
| 1 | `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/entity/DataQualityResult.java` | 类注释 `:13-24`；`severity` `:37-47`；`effectiveSeverity` `:49-58`；`ruleVersion` `:60-75`；`compatPolicyVersion` `:77-86`；`ruleFingerprint` `:88-...` | 新增 4 字段 `effectiveSeverity`(String)/`ruleVersion`(Integer)/`compatPolicyVersion`(String)/`ruleFingerprint`(String)；重写 Javadoc：`severity`=**声明**档位、`effectiveSeverity`=**生效**档位；四列可空；**NULL 语义 = 「本行为版本化引入前记录，无版本信息，不得解读为 WARN/PASS」**；并说明 MyBatis-Plus 驼峰→下划线自动映射（与既有 `runId`→`run_id` 同机制，未加配置） | 实体是写侧与读侧的共同契约，语义必须写在字段上；`ruleVersion` 用 `Integer` 而非 `int` 是刻意的，否则 NULL 无法表达 |
| 2 | `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/QualityChecker.java` | `rule(...)` `:206-232`（原 `:203` 的 `r.setSeverity(verdict.effectiveSeverity())` 已删）；新增 `applyVersionedSeverity(...)` `:234-280`（方法体 `:263-280`） | 抽出**写侧唯一实现** `applyVersionedSeverity`：`severity←verdict.declaredSeverity()`(`:274`)、`effectiveSeverity←verdict.effectiveSeverity()`(`:275`)、`ruleVersion← registered ? ruleVersion() : null`(`:277`)、`compatPolicyVersion`/`ruleFingerprint` 恒写(`:278-279`)。`verdict==null` 分支保留原有 severity、四个版本化列落 NULL(`:265-270`)，`rules==null` 也已判空 | 写侧有 **3 个**写点，若各自实现这四条约定，任何一处漏写都会产生「只有半套版本信息」的行 —— 而 V20 的全部意义就是让结果行能自证按哪一版判的。抽一处才可能被一次改对、被一次测到 |
| 3 | `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/PipelineService.java` | `persistQuality` `:736-756`（契约注释 `:742-745`，写入仍在 `:755`）；`persistChecks` `:774-813`（`:786-792` 解析+统一写入，`:793-795` 列宽保护）；`rulesFor` Javadoc `:333-347` | 两个写点都改为「解析一次 verdict → `QualityChecker.applyVersionedSeverity(...)`」；`persistChecks` 在统一写入后对两列做 `cap(...,16)`；`persistChecks` 补 V20/F-88 契约注释；`rulesFor` Javadoc 把「② 结果表增列」从待办改为**已由 V19/V20 落地**，并保留「① 改读库仍未做」 | `persistQuality`/`persistChecks` 原先都只写生效档位且写进同一列（`severity`），是 §7.3.1 line 520/524 的实质违例；两列都被 `VARCHAR(16)` 约束，落库前必须限宽（`cap` 是 `private static`，无法从 `QualityChecker` 复用，故在调用点做） |
| 4 | `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/DataQualityGate.java` | 类注释 `:26-49`（F-88 新增段 `:34-39`，缺口段更新 `:45-49`） | 补写「结果行的 `effective_severity` **也不参与**门禁判定」及其理由（写入当时档位 vs 本次规则集判定）；把「写侧增列未做」更新为「写侧已做、读侧按行内指纹重算仍属 F-93、不在 F-88 范围」 | 刚把两列填满后，最容易被后人「顺手」改成读 `row.getEffectiveSeverity()` 当结论 —— 那会立刻制造第二套口径；注释必须先把这条路堵住 |
| 5 | `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/QualityCheckerSeverityTest.java` | 见 §4 | 见 §4 | 见 §4 |
| 6 | `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/PipelineServiceTest.java` | 新增用例 `:424-478`；`adsQualityGateFailureBlocksFormalPartitionPublish` 增断言 `:416-417` | 见 §4 | 见 §4 |
| 7 | `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/DataQualityGateTest.java` | 新增用例 `:286-334`；夹具 Javadoc `:363-370` | 见 §4 | 见 §4 |
| 8 | `analytics-server/platform-app/src/test/java/com/graduation/analytics/guard/RuleSeverityPathConsistencyTest.java` | 夹具 Javadoc `:235-241`（仅注释，无逻辑改动） | 明确夹具「只设 `severity`、其余留空」是刻意的，用于钉住「门禁不看结果行任何档位列」 | 该文件在 `platform-app`，本轮**只编译不运行**（Flyway 会指向 3306），故只做零风险的注释澄清 |

`git diff --stat`（原始输出见 `raw/03-...`）：

```
 .../guard/RuleSeverityPathConsistencyTest.java     |  9 +-
 .../analytics/pipeline/DataQualityGate.java        | 17 ++--
 .../analytics/pipeline/PipelineService.java        | 28 +++++--
 .../analytics/pipeline/QualityChecker.java         | 65 +++++++++++++-
 .../pipeline/entity/DataQualityResult.java         | 69 ++++++++++++++-
 .../analytics/pipeline/DataQualityGateTest.java    | 56 +++++++++++++
 .../analytics/pipeline/PipelineServiceTest.java    | 61 +++++++++++++-
 .../pipeline/QualityCheckerSeverityTest.java       | 98 +++++++++++++++++++---
 8 files changed, 377 insertions(+), 26 deletions(-)
```

**未改动的相关文件（属于刻意不动）**：`db/meta/V19*.sql`、`db/meta/V20*.sql`（R5）、`RuleSeverity.java`、`QualityRuleCatalog.java`（R5，仅调用既有只读方法）、`PipelineService.rulesFor()` 实现（F-93，越界）、`MetricController./quality`（只返回实体，见 §3）。

---

## 2. 三条命令的退出码与原始 `Tests run:` 行

| # | 命令（逐字） | 退出码 | 原始汇总行 |
|---|--------------|--------|------------|
| 1 | `mvn -o -Dmaven.repo.local=D:\maven_repository -f analytics-server/pom.xml -pl warehouse-pipeline,platform-common -am test` | **0** | `[INFO] Tests run: 81, Failures: 0, Errors: 0, Skipped: 0`（platform-common）<br>`[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0`（connection-ingestion）<br>`[INFO] Tests run: 134, Failures: 0, Errors: 0, Skipped: 0`（warehouse-pipeline）<br>`[INFO] BUILD SUCCESS` |
| 2 | `mvn -o -Dmaven.repo.local=D:\maven_repository -f analytics-server/pom.xml -pl platform-app -am test-compile` | **0** | `[INFO] Compiling 20 source files with javac [debug release 17] to target\classes`<br>`[INFO] Compiling 22 source files with javac [debug release 17] to target\test-classes`<br>`[INFO] BUILD SUCCESS` |
| 3 | 静态自检：`git diff --stat` + `grep -rn "setSeverity(\|getSeverity(" analytics-server --include=*.java` | **0** | 见 §3；主代码命中 2 处、`src/test` 命中 15 处，合计 17 处 |

改动涉及的 4 个测试类逐类原始行（命令 1 首次运行**失败 1 例**，见 §5.1）：

```
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.133 s -- in com.graduation.analytics.pipeline.DataQualityGateTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.820 s -- in com.graduation.analytics.pipeline.PipelineServiceTest
[INFO] Tests run:  4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.014 s -- in com.graduation.analytics.pipeline.QualityCheckerAmountReconcileTest
[INFO] Tests run:  6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.021 s -- in com.graduation.analytics.pipeline.QualityCheckerSeverityTest
```

两个日志中对 `3306` / `3307` / `Flyway` / `flyway` 的命中数 = **0**（命令 1 未加载 `platform-app`，故 Flyway 初始化器不在 classpath；
命令 2 只编译不执行测试，因而不触发迁移）。

---

## 3. R3：`getSeverity()` / `setSeverity(` 全量审计（逐点判定「改了什么 / 为什么保留」）

`grep` 逐字结果（`raw/03-static-selfcheck-grep-diffstat.log`；本机 `grep` 不在 PATH，用等价的 `Select-String 'setSeverity\(|getSeverity\('` 全仓 `.java` 扫描，命中 17 处）：

### 3.1 主代码（写点）——命中 2 处，全部已改

| 位置 | 判定 | 改了什么 / 为什么 |
|------|------|-------------------|
| `QualityChecker.java:274` `r.setSeverity(verdict.declaredSeverity())` | **改：写声明档位** | 原为 `r.setSeverity(verdict.effectiveSeverity())`（生效档位顶替声明档位）。改为写**声明**档位，生效档位另落 `effectiveSeverity`(`:275`) |
| `PipelineService.java:794` `r.setSeverity(cap(r.getSeverity(), 16))` | **改：列宽保护，不是重判** | **读的是刚由 `:792` 统一写入的值**（`cap` 只做截断）。写在这里而不是 `QualityChecker` 里的原因：`cap` 是 `PipelineService` 的 `private static`，且 `severity`/`effective_severity` 两列都是 `VARCHAR(16)`，只有真正落库的路径需要限宽。**保留 `cap` 的依赖前提**：`UNREGISTERED` 当前是 `BLOCKING` 别名（8 字符），若将来把该常量改成长字面（如 `"UNREGISTERED"`，12 字符）仍在 16 以内；但若改成更长的哨兵字符串，本行会静默截断 ⇒ 见 §7「须总控裁决-3」 |

### 3.2 主代码（读点）——命中 **0 处**，全部「不改」并给出理由

对 `DataQualityResult` 的全部使用点做了穷举（`grep -rn "DataQualityResult" analytics-server --include=*.java`，75 处），与档位列相关的读取只有下面这些，逐点判定：

| 读点 | 判定 | 为什么 |
|------|------|--------|
| `DataQualityGate.java:94` 起的判定循环（`statusForRun`/`decisionForRun`/`blockingFailuresForRun`） | **不改：不读任何档位列** | 判定输入刻意只有 `(冻结规则集, ruleCode, passed)` 三元组；档位列（两列都）不参与。已新增反证用例 `DataQualityGateTest#gateIgnoresBothSeverityColumns`（`:297-333`），用「两列同时说谎」的夹具证明结论不变 |
| `DataQualityGate.java:171` 查询条件 `DataQualityResult::getRunId` | **不改** | 按 run 过滤，与档位无关 |
| `MetricController.java:67-77`（`GET /quality`，返回 `List<DataQualityResult>`） | **不改：原样透出，不做读时归一化** | 该接口的用途是让运维看到**库里真实存的行**；本次改动正是让这一行里同时有「声明档位」与「生效档位」，读时再去归一化会把这个信息抹掉，且属 F-94 读侧范畴（不在 F-88 范围）。前端 `web` 侧无任何 `severity` 读取（`grep 'severity' --include=*.html` 命中 0；`platform-app/src/main/resources/static/assets/*.js` 中亦无 `effectiveSeverity`/`normalizedSeverity` 命中），因此新增列只增加 JSON 字段，不破坏既有消费方 |
| `PipelineService.java:855` `cm.put("severity", c.severity())`（阶段证据 JSON） | **不改：它读的是作业回传值，不是实体列** | 这里 `c` 是 `JobResultParser.CheckInfo`（作业回传），与 `DataQualityResult.severity` 同名不同物。`:856-857` 另写 `normalizedSeverity` = `RuleSeverity.resolve(...).effectiveSeverity()`，即「作业原值 + 平台生效值」都留痕，改动后语义仍然自洽，不需要动 |
| `QualityChecker.java:168,173` `anyBlockingFailed(...)` | **不改：按规则码判定** | 走 `RuleSeverity.blocks(rules, r.getRuleCode(), r.getPassed())`，不读档位列 |
| `DataQualityGateTest` / `RuleSeverityPathConsistencyTest` / `PipelineServiceTest` / `QualityCheckerSeverityTest` 的夹具与断言 | **见 §4** | 测试侧读取见下 |

> 结论：**主代码写点 2 处，均已按新契约改造（1 处改语义、1 处为列宽保护）；主代码读点 0 处**（没有任何主代码读 `DataQualityResult.getSeverity()`）。
> 这不是"没人用所以不用管"，而是**必须保持没人用**：门禁与发布判定的输入是规则码，档位列只是给人和事后复算用的证据。§4 已用测试把这条纪律钉住。

### 3.3 测试代码（15 处）逐点判定

| 位置 | 判定 |
|------|------|
| `DataQualityGateTest.java:371` `entity.setSeverity(severity)`（夹具） | **保留**：夹具需要能造出「库里字面值与规则目录不符」的行，那正是被测场景 |
| `DataQualityGateTest.java:282` `assertThat(row.getSeverity()).isEqualTo("WARN")` | **保留并扩展**：原断言（`originalSeverityStaysReadable` `:274-286`）证明「门禁结论按未登记码收紧，但**不改写实体原有 severity**」；本轮补 `:283-284` 断言 `getEffectiveSeverity()` 不被读侧回填 |
| `DataQualityGateTest.java:333` `assertThat(historical.getSeverity()).isNull()` | **新增**：历史行（两列 NULL、无版本信息）判定后不得被读侧回填 |
| `PipelineServiceTest.java:575` `row.setSeverity(severity)`（夹具） | **保留**：用于预置「库里已有行」的场景（如 `qualityRow("ADS_STAGING_PRESENT","ERROR",0)`），字面 `ERROR` 正是要被目录覆盖的输入 |
| `PipelineServiceTest.java:416-417 / :543-556 / :438-478` 断言 | **改/新增**：`:416-417` 两列同值断言；`:543-556` 声明 WARN + 生效 BLOCKING + 版本三列；`:438-478` 新增用例见 §4.2 |
| `QualityCheckerSeverityTest.java:58,79,99,100,173,183` | **改/新增**：`:58` 新增「`severity` 必须等于目录声明档位」；`:99-100` 新增「两列不同」；`:173,183` 见 §4(c) |
| `RuleSeverityPathConsistencyTest.java:245` `r.setSeverity(literalSeverity)`（夹具） | **保留**：该类意图就是「字面 severity 不参与判定」；本轮只加夹具 Javadoc(`:235-241`) 说明「其余列留空是刻意的」 |

---

## 4. R4：测试更新（不删断言、不改期望值来"变绿"）

### 4.1 `QualityCheckerSeverityTest.java`（6 例全绿）

| 用例 | 覆盖 | 关键断言（原文摘录） |
|------|------|----------------------|
| `landingRuleSeveritiesAreCatalogDriven` `:40-62` | 内联 4 条规则的档位 | `:49-52` 生效档位 = 目录档位；**新增** `:53-61` 循环断言 `ruleOf(...).getSeverity()` **等于** `QualityRuleCatalog.DEFAULT.find(code, null).orElseThrow().severity()`（即 `severity` = 声明档位） |
| `duplicateEventIdsWithinThresholdDoNotBlock` `:64-82` | R4(c)：已登记 + 未超阈值 | `:79` `severity==WARN`、`:80` `effectiveSeverity==WARN`（两列同值 = 声明档位），`:81` 不阻断 |
| `duplicateEventIdsBeyondThresholdBlock` `:84-110` | **R4(a) 核心**：声明 WARN + 超阈值 | `:99` `severity==WARN`、`:94` `effectiveSeverity==BLOCKING`、`:100` `isNotEqualTo`；`:102-107` `compatPolicyVersion`/`ruleFingerprint`(64 位)/`ruleVersion` **取自** `QualityRuleCatalog.DEFAULT.freeze(null)` |
| `unregisteredRuleCodeWritesNullSeverityAndStillCarriesVersionInfo` `:159-186` | **R4(b)** | `:173` `severity` 为 **null**、`:174` `effectiveSeverity == RuleSeverity.UNREGISTERED`、`:177` `ruleVersion` 为 **null**、`:178-180` `compatPolicyVersion`/`ruleFingerprint` 非空白且 64 位 |
| `missingRequiredFieldBlocks` / `illegalEnumBlocks` | 固定阻断码 | 各自新增 `getEffectiveSeverity()==BLOCKING` 断言（`:120`、`:132`） |

**期望值全部由 `QualityRuleCatalog.DEFAULT.freeze(null)` 推导，未硬编码 64 位十六进制指纹**。

### 4.2 `PipelineServiceTest.java`（16 例全绿，原 15 例 + 新增 1 例）

- `adsQualityGateFailureBlocksFormalPartitionPublish` `:389-421`：新增 `:416-417`（固定阻断码两列同值 `BLOCKING`／`BLOCKING`），原「不发布 + 留检查证据」断言**全部保留**。
- `duplicateRateBeyondThresholdBlocksPublish` `:546-556`：`:546-556` 检查 `severity=WARN`、`effectiveSeverity=BLOCKING`，并与 `QualityRuleCatalog.DEFAULT.freeze(null)` 比 `ruleVersion`/`compatPolicyVersion`/`ruleFingerprint`。
- **新增** `sparkCheckResultsCarryDeclaredAndEffectiveSeverityPlusVersionInfo` `:424-478`：专门钉**第二个写点** `persistChecks`（作业回传 checks 的路径）。夹具 `PUB_DQ_EVENT_ID_UNIQUE` 声明 WARN、作业回传字面 `ERROR`、`passed=false` ⇒ 断言 `severity=WARN`（**不是**回传的 `ERROR`）、`effectiveSeverity=BLOCKING`、三列版本信息与冻结集一致；并断言 `INFO` 审计项仍不落规则表（`:473-476`，原有纪律不松动）。

### 4.3 `DataQualityGateTest.java`（20 例全绿，原 19 例 + 新增 1 例）

- **新增** `gateIgnoresBothSeverityColumns` `:298-334`：三小段反证 —— ①已登记 BLOCKING 码 `passed=0` 但两列都写 `WARN` ⇒ 仍 `FAIL`；②条件观察项 `passed=1` 但两列都写 `BLOCKING` ⇒ 仍 `PASS`；③历史行四列全 NULL ⇒ 仍按规则码判 `FAIL`，且判定后实体未被回填（`:332-334`）。
- `originalSeverityStaysReadable` `:276-286`：原断言保留，**新增** `:283-284` 「`effectiveSeverity` 不被读侧回填」。
- 夹具 `result(...)` `:363-376` 补 Javadoc：`severity` 是可被归一化覆盖的字面标签、`effectiveSeverity` 同样不参与判定。

### 4.4 `RuleSeverityPathConsistencyTest.java`（`platform-app`，仅编译）

只加夹具 Javadoc，无逻辑改动；该类**本轮不运行**（运行会触发 Flyway 指向 3306）。

---

## 5. 执行过程中的两次实测修正（不掩盖）

### 5.1 首次命令 1 失败 1 例，原因是我对新契约的**前提假设错了**

首次运行原始输出：

```
[ERROR]   QualityCheckerSeverityTest.unregisteredRuleCodeWritesNullSeverityAndStillCarriesVersionInfo:170 [UNREGISTERED 不是合法档位字面 ⇒ 不会被误读成 WARN/PASS]
[ERROR] Tests run: 134, Failures: 1, Errors: 0, Skipped: 0
```

我原先断言 `RuleSeverity.isKnownSeverity("UNREGISTERED") == false`。实测：`RuleSeverity.java:62` **`public static final String UNREGISTERED = BLOCKING;`** ——
本仓库的 `UNREGISTERED` 是 `BLOCKING` 的**别名常量**（保守默认，`:52-61` 有明文说明），因此它**是**合法档位字面，该断言为假（`Expecting value to be false but was true`）。

- **修正方式**：把断言换成「实测到的事实」——`r.getEffectiveSeverity()` 落库字面等于 `"BLOCKING"`，并**新增**「区分依据」断言：未登记行 `severity` 与 `ruleVersion` **同时为 NULL**（`:183-185`）。
- **顺带产出一条真实结论**（已写入代码注释 `QualityChecker.java:248-254` 并列入 §7）：**单看 `effective_severity` 一列无法区分「未登记码按保守默认阻断」与「已登记且声明即阻断」**，可靠依据是 `severity IS NULL AND rule_version IS NULL` 的组合。这是本次实测才暴露的口径局限，不是文档上抄来的。

### 5.2 `DataQualityGate.java` 的「已知缺口」注释已过期，本轮更新

原文写「真正按 run 冻结需要写侧把规则版本与指纹落库……本任务**未做**（迁移号由总控分配）」。
V19/V20 与本次写侧改造落地后该句不再成立，已改为：写侧已完成（V20 + 三写点），**仍属未做的是「读侧按行内 `rule_fingerprint` 选版本重算」（F-93）与 `rulesFor()` 改读 DB**。

---

## 6. 未取证清单（**不得**当作已通过）

| # | 未取证项 | 为什么没取证 | 影响 |
|---|----------|--------------|------|
| 1 | **真实落库结果**（`data_quality_result` 四列在 MySQL 里的实际值） | 本泳道**禁止连接任何数据库**（3306 禁连；3307 需另立泳道），且 V20 未在目标库执行 | 「代码写对了」已证；「库里真有这四个值」**未证**。这是完成度的最大缺口 |
| 2 | 端到端 run（启动服务跑一次 ODS_TO_ADS 再查库） | 禁止启停服务 | 三条命令只到单测层；编排层真链路未跑 |
| 3 | `platform-app` 的**测试运行**（含 `RuleSeverityPathConsistencyTest` 的实际执行） | 运行会触发 `MetaFlywayInitializer` → 连 3306 执行迁移，**不可逆**，总控已明令禁止 | 该测试类仅**编译**通过；其断言是否全绿**未证**。这正是「只跑 `test-compile`」这条命令的存在理由 |
| 4 | `metric-analysis` / `ai-decision` 的测试 | 不在指定范围 | 未证；命令 2 只保证其**编译**通过 |
| 5 | 前端页面在新列下的显示 | 未启服务、未开浏览器 | `web`/`static` 源码中无 `severity` 消费点（已 grep），但**页面实际渲染未证** |
| 6 | `spark-jobs`（Scala）是否会**独立**写 `data_quality_result` | 只做静态 grep：`spark-jobs/**/*.scala` 中 `data_quality_result` 仅 1 处命中且在**注释**里（`SurrogateKey.scala:359`），无 INSERT 语句 | 「作业侧不直接写该表」是**静态推断**，未见运行时证据。若作业侧存在旁路写入，它不受本次写侧契约约束 |
| 7 | `VARCHAR(16)` 列宽在**极端值**下的行为 | 未连库、未跑集成 | `cap(...,16)` 逻辑上保证不超长，但未实测 |
| 8 | 离线构建之外的可构建性（在线依赖解析） | 使用 `-o` 离线仓库 | 未证 |
| 9 | Maven 是否**全量重编译**（日志多行 `Nothing to compile - all classes are up to date`） | 增量编译复用已有 class | 命令 1 的通过包含既有 class；本轮改动文件确实被重编译（`platform-app` 日志显示 `Compiling 20 source files ... Changes detected`），但**模块级全量重编译未证** |
| 10 | 编译产物的字节码与源码一致（反编译核对） | 未做 | 未证 |

---

## 7. 须总控裁决

| # | 事项 | 我的建议 / 事实依据 |
|---|------|---------------------|
| 1 | **`UNREGISTERED` 与 `BLOCKING` 同值导致 `effective_severity` 单列不可判**（本轮实测发现） | 现状：`RuleSeverity.java:62` `UNREGISTERED = BLOCKING`，所以未登记新行落库字面就是 `"BLOCKING"`。**可靠区分式**：`severity IS NULL AND rule_version IS NULL`（未登记）vs 两列非空（已登记）。若要单列可判，需新增哨兵字面（如 `"UNREGISTERED"`）——那会改 `RuleSeverity` 语义，R5 明确禁止，故**未做**，请裁决是否立项 |
| 2 | **`MetricController./quality` 是否应读时归一化** | 我判定**不改**（GET 应透出库里真实行，避免抹掉两列差异；归一化属 F-94/F-93）。若总控认为页面必须只显示生效档位，需另立读侧泳道 |
| 3 | **`effective_severity` 列宽 vs `UNREGISTERED` 落库字面** | 当前 `VARCHAR(16)`，字面 ≤ 8 字符，`cap(...,16)` 有冗余。若将来把哨兵改成长字符串，`PipelineService.java:794-795` 会**静默截断**（截断后是无效档位字面而非报错）。是否要改成「超长即抛异常/记日志」而不静默截断？ |
| 4 | **`DataQualityGate` 读侧跨 run 冻结（F-93）** | 未做，越界。当前门禁用「进程内目录默认冻结集」，因此历史 run 的档位仍会随代码发布而变 —— 与 V20 写侧落库的指纹**尚未闭环** |
| 5 | **`rulesFor()` 仍不读库 `quality_rule_definition`** | V19 已建表播种，但 `PipelineService.java:346-348` 仍用进程内目录（已在该方法 Javadoc 显式标注为未做项） |
| 6 | 「版本化已闭合」能否声称 | **不能**。写侧载体已补齐并有三写点契约与单测；缺 §6 的 1、2、3、6 四项证据 |
| 7 | `RuleSeverityPathConsistencyTest` 是否要像 `DataQualityGateTest` 那样补「两列都说谎」的反证 | 本轮只加注释（该文件不运行）。是否需要在读侧泳道补等价用例，请裁决 |
| 8 | 是否需要把 V20 迁移的**执行**排期 | 本泳道禁连库，迁移执行需另立泳道并指定目标库（3306 或 3307-隔离实例） |

---

## 8. 结论（分级）

| 级别 | 判断 | 证据 |
|------|------|------|
| **已实测通过** | R1 四个字段与 Javadoc 契约落地；R2 三个写点全部走 `applyVersionedSeverity` 单实现（`severity`←声明、`effectiveSeverity`←生效、`ruleVersion` 未登记落 NULL、策略版本/指纹恒写）；R3 主代码写点 2 处已改、读点 0 处（逐点判定见 §3）；R4 四类用例（声明 WARN+超阈值、未登记、已登记+未超阈值、读侧两列不参与）全部有断言且**全绿**；R5 未动 V19/V20、未改 `RuleSeverity`/`QualityRuleCatalog` 语义 | 命令 1：`Tests run: 81/156/134, Failures: 0, Errors: 0`，`BUILD SUCCESS`；命令 2：`BUILD SUCCESS`；命令 3：主代码命中 2 处 |
| **已实现但未端到端取证** | 写侧契约在**单测层**成立（含 `persistQuality` 与 `persistChecks` 两条路径各自的断言） | `PipelineServiceTest`（16 例）、`QualityCheckerSeverityTest`（6 例）、`DataQualityGateTest`（20 例） |
| **未取证（不得声称通过）** | 真实落库值、端到端 run、`platform-app` 测试运行、作业侧旁路写入、页面渲染 | §6 |
| **未做（越界或禁做）** | 读侧按行内指纹重算（F-93）、`rulesFor()` 改读库、V20 迁移执行、历史行回填 | §7 |

**给总控的一句话**：F-88 写侧（V20 四列的写侧接入）可以合并；**但不得据此声称「规则版本化已闭合」** —— 闭合还差「迁移在目标库执行 + 一次真实 run 落库取证 + 读侧按指纹重算」三件事。

---

## 9. 提交与文件清单

- 提交：`f5a8c1b`（`remediation/r1-boundary`），**未推送**
- 提交涉及的 8 个源文件 + 4 个本报告文件（`git show --stat HEAD` 原始输出可复现）：
  1. `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/entity/DataQualityResult.java`
  2. `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/QualityChecker.java`
  3. `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/PipelineService.java`
  4. `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/DataQualityGate.java`
  5. `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/QualityCheckerSeverityTest.java`
  6. `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/PipelineServiceTest.java`
  7. `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/DataQualityGateTest.java`
  8. `analytics-server/platform-app/src/test/java/com/graduation/analytics/guard/RuleSeverityPathConsistencyTest.java`
  9. `docs/acceptance/v26-f88-writeside-20260914/REPORT.md`（本文件）
  10. `docs/acceptance/v26-f88-writeside-20260914/raw/01-mvn-warehouse-pipeline-platform-common-test.log`
  11. `docs/acceptance/v26-f88-writeside-20260914/raw/02-mvn-platform-app-test-compile.log`
  12. `docs/acceptance/v26-f88-writeside-20260914/raw/03-static-selfcheck-grep-diffstat.log`
- 报告行号引用基于提交后版本核对（与提交时的行号一致，第 1–8 项在本提交后未再改动）。
- 本报告自身的 hash 引用约定：正文只引用**短哈希 `f5a8c1b`**（40 位完整哈希请用 `git rev-parse HEAD` 现取），避免「改报告 → 哈希变 → 再改报告」的自指循环。
