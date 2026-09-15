# DEV-003c —— 项目级统一测试入口（`scripts/run-tests.ps1`）实施与 fresh 验收报告

- 泳道：`docs/acceptance/dev003c-unified-test-entry-20260915/`
- 采集时间：2026-09-15 11:43–11:51 +0800
- 采集者：代码 Agent（本轮唯一动作 DEV-003c）
- 授权边界（总控 2026-09-15 裁决）：只实现 `default-tests` / `isolated-tests` / `spark-tests` / `all-tests` 四档；
  **禁止新增根 `pom.xml`**、**禁止创建 GitHub Actions**；架构＝「Maven 负责模块内测试选择；PowerShell 负责跨模块、跨 JDK、环境与汇总」。
- 本轮**未**修改：`scripts/run-isolated-tests.ps1`（逐字节未动，见 §7）、六个 `*IT.java`、README、`contract-specs/**`、其他泳道 `raw/**`、指导书 V2.8、设计 V2.5。
- 唯一隔离实例：`127.0.0.1:3307`（canonical `dahaishui:3307`，uuid `de8ebbea-aff4-11f1-8037-00155d5dba47`）；宿主 `3306` 全程只读、未触碰。

---

## 1 交付物

| 文件 | 性质 | 规模 |
|---|---|---|
| `scripts/run-tests.ps1` | **新增**（本轮唯一代码产物） | 412 行 / 27,091 B / sha256 `373333c7cc570fe51446ad06457ec70901a50c5838b390cbd7731f86d80956b7` |

`-Suite default|isolated|spark|all`，默认 `default`；`-RunId` 缺省 `dev003c_<yyyyMMdd_HHmm>`；
`-LogDir` 缺省 `$env:TEMP\v25tests-<runId>`；另有 `-Confirm`（隔离/all 档必需）与 `-AllowCountDrift`（仅在总控批准口径更新后使用）。

**没有**新增根 `pom.xml`（仓库仍无根聚合 POM，`docs/PROJECT_STATUS.md:37` 记载的既有事实不变）；**没有**创建任何 `.github/**`。

## 2 四档设计与分层职责

| 档位 | 做什么 | 关键成功判据 |
|---|---|---|
| `default` | 顺序执行既有默认入口：`mvn -f analytics-server/pom.xml test` → `mall-simulator` → `synthetic-data-generator`（JDK17，命令语义逐字不变） | 逐模块汇总行 `Tests run` > 0 且 F = 0 且 E = 0 且模块 exit 0 |
| `isolated` | **直接调度**既有 `scripts/run-isolated-tests.ps1 -RunId <runId> -Module all -Confirm`（不重实现任何隔离逻辑），只复核计数 | 子入口 exit 0；mall 30 / generator 19 / metric-analysis IT 6 逐项 > 0、F/E = 0；`IsolationGuardMySqlIT` 类行存在 |
| `spark` | `mvn -f spark-jobs/pom.xml test`，**显式 JDK8**（`JAVA_HOME` + `PATH` 前置）＋ 注入本轮 `-Dp2.test.runId=<runId>` | **只认** `spark-jobs/target/surefire-reports/TestSuite.txt`：`Total number of tests run` > 0、`failed = 0`、`aborted = 0`、`All tests passed.`，且该文件 **mtime ≥ 本轮启动时刻** |
| `all` | `default` → `isolated` → `spark` 顺序执行，三档独立摘要 | 任一档失败／零用例／F/E 非 0 ⇒ 非 0 退出；本轮实测 **exit 0** |

分层（总控裁决口径）：

- **Maven 侧**（模块内测试选择）：surefire `include/groups/excludedGroups` 与 `isolated-tests` profile 全部留在各模块自己的 `pom.xml`（`metric-analysis`、mall、generator）。本脚本**不使用** `-Dtest=` 点名任何用例（DEV-003b 纪律：点名会让「入口自动收集」退化成人工记忆类名）。
- **PowerShell 侧**（跨模块／跨 JDK／环境／汇总）：`run-isolated-tests.ps1`（隔离环境门禁 1–6 与真链）+ 本脚本（档位编排、JDK 切换、计数归集、退出码）。

退出码契约（与既有 runner 对齐，便于总控统一判读）：`0` 通过；`1` 参数/环境错误；`5` 执行前被拒（缺 `-Confirm`／缺口令／RunId 形状非法，隔离档门禁 5/6 原样透传）；`6` 隔离探针失败（透传）；`7` 套件失败（非 0 退出／零用例／F 或 E 非 0／spark 产物非本轮新写／计数与登记基线漂移）。

## 3 fresh 验收结果（三档 + `all` 退出码）

### 3.1 `-Suite all`（runId `dev003c_20260915_1205`，全新 runId 首跑）

```
default   PASS （751 个用例）
isolated  PASS （55 个用例；runner exit=0）
spark     PASS （Total number of tests run=111；新写=True；JDK8=True）
[PASS exit=0]
```

控制台全量：`raw/20-all-console.txt`（143,077 B）。执行窗口 11:49:0x–11:50:27。

### 3.2 `default-tests`（本轮 fresh）

| 目标 | `Tests run` | F / E / S | exit | 逐模块拆解 | 证据 |
|---|---|---|---|---|---|
| `analytics-server` | **632** | 0 / 0 / 0 | 0 | 89（platform-common）＋156＋134＋48（metric-analysis）＋91＋114 | `raw/20-default-analytics-reactor.log` |
| `mall-simulator` | **13** | 0 / 0 / 0 | 0 | 5（`IsolationGuardFingerprintTest`）＋2（`OrderStateMachineTest`）＋6（`GoldenDatasetTest`） | `raw/21-default-mall.log` |
| `synthetic-data-generator` | **106** | 0 / 0 / 0 | 0 | 17 个测试类，含 `IsolationGuardFingerprintTest 5` | `raw/22-default-generator.log` |
| **合计（不含 spark-jobs）** | **751** | 0 / 0 / 0 | 0 | — | 同上 |

- reactor **没有** reactor 级汇总行，「632」由 6 条模块汇总行相加得到（逐行归属已在上表拆解）。
- `spark-jobs` **不在** `default-tests` 内（总控 §四要求）；四棵树完整口径见 §4。

### 3.3 `isolated-tests`（本轮 fresh，全新 runId `dev003c_20260915_1205`）

| 目标 | 用例 | F / E / S | 关键类行 | 证据 |
|---|---|---|---|---|
| `mall-simulator` | **30** | 0 / 0 / 0 | 9 个类，含 `AuthHttpTest 5`、`MallBusinessServiceTest 6` | `raw/24-isolated-mall.log` |
| `synthetic-data-generator` | **19** | 0 / 0 / 0 | `GeneratorMetaStoreTest 5`（**全新 runId 首跑即 5/0/0/0**）、`GeneratorApiSmokeTest 5`、`MallApiGenerationSmokeTest 9` | `raw/24-isolated-generator.log` |
| `metric-analysis`（IT） | **6** | 0 / 0 / 0 | `IsolationGuardMySqlIT 6`（读数行 `-- in com.graduation.analytics.metric.IsolationGuardMySqlIT`） | `raw/24-isolated-analytics.log` |
| **合计** | **55** | 0 / 0 / 0 | runner `exit 0` | `raw/23-isolated-console.txt` |

- `-pl metric-analysis -am` 的日志里**同时**含依赖模块 `platform-common` 的 89 个默认档用例（依赖构建）。调度器按「当前构建模块」归集，只计 `metric-analysis` 的 6，并在摘要里显式打印被排除项（`platform-common=89`）。
- 「generator 19 例须靠同库二次跑才全绿」的旧口径在本轮**再次**被证伪：这是该 runId 的**第一次**执行，`GeneratorMetaStoreTest` 5 例全绿（DEV-003a 的 Flyway 前置已生效，与 `docs/PROJECT_STATUS.md:147` ①一致）。

### 3.4 `spark-tests`（本轮 fresh；两处独立取证）

| 运行 | runId | 结果 | `TestSuite.txt` 取证 |
|---|---|---|---|
| 独立 `-Suite spark`（§九要求） | `dev003c_20260915_1155s` | `[PASS exit=0]`，111 | mtime `2026-09-15 11:44:45`（启动 11:44:18 ⇒ 本轮新写），sha256 `c1cfc9730fd3c695305aea6c93266344c1fb78aa6fd6d62ebd6bed2e3420927f`（`raw/13-ScalaTest-TestSuite.txt`） |
| `-Suite all` 内的 spark 档 | `dev003c_20260915_1205` | PASS，111 | mtime `2026-09-15 11:50:26`（启动 11:50:00 ⇒ **本轮新写**），sha256 `b4caec3667c59211c797b16b38dbbff028d5d9351444cb9d4d2d8ab87a3c3ad1`（`raw/27-all-ScalaTest-TestSuite.txt`） |

ScalaTest 权威读数（两次一致）：`Total number of tests run: 111`、`Suites: completed 15, aborted 0`、`Tests: succeeded 111, failed 0, canceled 0, ignored 0, pending 0`、`All tests passed.`

**JDK8 取证（本轮 fresh，非继承）**：`raw/26-spark-jdk-version.log`（spark 档运行时以 `JAVA_HOME=D:\Develop\JDK1.8` 调用 `mvn -version`）—

```
Java version: 1.8.0_202, vendor: Oracle Corporation, runtime: D:\Develop\JDK1.8\jre
```

脚本据此刻意断言 `Java version: 1.8`（`jdkOk=True`），并把 JDK8 作为**硬判据**之一；日志同时打印 `JAVA_HOME` 与 `PATH` 前置路径。

**「surefire 假绿」的 fresh 实证（本轮同一份日志内）**：同一次 JDK8 运行的 surefire 汇总行为

```
[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
```

而真实结果是 111 全过。原因是 `spark-jobs` 的 ScalaTest 结果**不走 surefire 汇总**：surefire 依默认 include（`*Test`）只捞到 `MetricAdsSpecTest`（JUnit3 provider 误配 ⇒ 0 用例），ScalaTest 段落另计 111。该项已被 `docs/acceptance/v25-r01-coverage-20260914/VERIFY-T01-T02.md:60-67` 记为「矛盾·未取证」，本轮在**同一份 fresh 日志**里把它钉成事实——这也是本脚本**拒绝**把 surefire 汇总当成功依据、只认 `TestSuite.txt` 且要求文件新鲜的直接原因。

## 4 四棵树完整口径（本轮 fresh 后）

```
862 = 632  analytics-server（本轮 fresh，逐模块 89+156+134+48+91+114）
    +  13  mall-simulator（本轮 fresh）
    + 106  synthetic-data-generator（本轮 fresh）
    + 111  spark-jobs（本轮 fresh：TestSuite.txt 本轮新写，非沿用旧证据）
```

与 `docs/PROJECT_STATUS.md:113`「语义一致性收口轮」口径逐项一致（632／13／106／111），差异仅在**取证时点**：本轮 **111 是本轮 fresh 实测**，不再沿用 2026-09-14 的旧产物。

**证明边界（不得越界表述）**：`spark-tests` 只证明「Scala `local[1]` ＋ `catalogImplementation=in-memory`」（`com.graduation.analytics.P2TestSupport`）下 111 个 ScalaTest 通过；**不等于**在产 Hive／Spark 集群通过（`spark-hive` 为 `provided`、无 hive-exec，`enableHiveSupport()` 不可用）。

## 5 自查发现并修复的自身缺陷（3 项，均在验收跑动中被真实触发）

| # | 现象（真实命令输出） | 归因 | 修法 | 留档 |
|---|---|---|---|---|
| 1 | `无法覆盖变量 home，因为它是只读变量或常量。`（脚本 `:107`），`[spark-solo exit=1]` | 辅助函数参数名用了 PowerShell 只读自动变量 `$home` | 改名 `$jdkHome` | 控制台日志**未留档**（被第 2 项同名文件覆盖，仅本报告引用其原文；未重新制造该失败） |
| 2 | `在此对象上找不到属性"Count"`（脚本 `:374`），`[spark-solo exit=1]`（spark 档本身已判 PASS） | `bad = $(if (...) { @() } else { ... })`：空数组经子表达式被展开成 0 个对象 ⇒ 属性成 `$null`，StrictMode 下取 `.Count` 抛错 | 预习变量 + 摘要处 `@($r.bad).Count` | `raw/09-spark-console-SELFBUG-rbad-count.txt` |
| 3 | `isolated FAIL （144 个用例）失败项：analytics`，`[all exit=7]` | 隔离 analytics 目标是 `-pl metric-analysis -am`，日志含依赖模块 `platform-common` 的 89 个默认档用例；调度器原按全文相加（89+6=95）⇒ 与基线 6 不符判 **DRIFT** | 计数改为按「当前构建模块」归集，只计目标模块，并打印被排除项 | `raw/19-all-console-SELFBUG-attempt1.txt`、`raw/19-isolated-analytics-SELFBUG-attempt1.log` |

第 3 项的关键事实：**被调度的入口本身是绿的**（该次 `isolated` 子 runner `exit 0`，mall 30 / generator 19 / 6 全绿），红只出在调度器的复核判据上。修复后同一 fresh 结构复核为 `isolated PASS （55 个用例）`。

三项均为**本脚本自身缺陷**，与业务代码、与 `run-isolated-tests.ps1`、与任何既有测试无关；修复后全部重跑取证（§3.1）。

## 6 未收编测试（不计入通过总数；总控 2026-09-15 裁决）

| 项 | 处置 | 本轮是否执行 |
|---|---|---|
| `MetricAdsMySqlIT`（2）、`MetricPublisherMySqlIT`（3） | 后续测试完善 backlog | 否 |
| `SourceRegistryMigrationMySqlIT`（7） | DEV-004（结构性双必败：`metaDb==metricDb` 记录检查 + `EXPECTED_META_SCRIPTS` 止于 V18） | 否（本轮未修改） |
| `SparkStageExecutorSmokeIT`（1） | Spark 专项冒烟 backlog（Windows 硬编码 `spark-submit` 路径，真实提交） | 否 |
| `AnalysisGoldenMySqlIT`（6） | **D 类**：历史黄金值只读复验，**永久排除** unified isolated-tests，保留手工/专项能力 | 否 |
| 前端 `web` 项目级入口 | 整理阶段范围外（后续 CI 阶段处理） | 否 |
| 其余未打标 `*IT`（19 个） | **仍无任何自动入口**（本轮不声称已自动化） | 否 |

因此本报告**不声称**「所有历史 IT 已自动化」；`0` 个 `*IT` 进入 `default-tests`，`1` 个（`IsolationGuardMySqlIT`）进入 `isolated-tests`。

## 7 未改动与边界确认

- `scripts/run-isolated-tests.ps1`：`git diff --stat` **空**（未修改、未重实现）。
- 六个 `*IT.java`、`README.md`、`docs/PROJECT_STATUS.md` 之外的文档：本轮未改。
- **README 陈旧数字登记**（总控 §八：本轮只登记不重写）：`README.md:35-44` 的测试矩阵仍是 2026-09-11 口径 —— `analytics-server 303/303`（`:37`，2026-09-11）、`spark-jobs 46/46`（`:38`，命令写作 `mvn -f spark-jobs/pom.xml package`，与现行 `test` 相位口径不同）、`web 74/74`（`:39`）、`mall-simulator 54/54`（`:40`）＋ 4 条 `.verify/*` 真机/DOM 套件数字（`:41-44`）；与现行 632／111／13／106 **全部不一致**，正式收口放到下一轮「指导书重写／文档发布」。
- `-Suite all` 会在隔离实例上按 runId 新建库与受限账号（本轮 `dev003c_20260915_1205`、`dev003c_20260915_1150` ⇒ 3307 上现有 **4** 个 `dev003c%` 库）：**未清理**（清理仍按 `docs/PROJECT_STATUS.md:60` 走 F-93 后的 `-AllowedCleanDbs` 白名单，禁止手写 `DROP`）。这是本轮新增的、需要总控排期的**累积事实**。
- 敏感串扫描（对新增脚本）：`Select-String -Pattern '123456|password\s*=\s*[''"]|secret|token'` ⇒ **0 命中**；脚本不提供 `-Password`、不读 `credref` 文件，口令一律经环境变量传入子进程。
- 登记局限（沿用并新增）：
  - L-1 隔离 profile 仍只在 `metric-analysis`；同 reactor 的 `platform-common` 89 为依赖构建（本轮已改为按模块归集，不再被误计）。
  - L-2 零用例硬门禁依赖 surefire 的 `-- in <class>` 文本（调度器对该文本做独立复核）。
  - L-3 runner 无自动化测试（本脚本同样无自测；本轮以 3 次真实缺陷驱动修正）。
  - L-4 `spark` 档的「新写」判据＝`TestSuite.txt` mtime ≥ 本轮启动时刻；若时钟被回拨会失效（未做防护）。
  - L-5 计数基线（632/13/106/111 与 30/19/6）**硬编码**在脚本内，漂移即失败，除非显式 `-AllowCountDrift`（口径更新须总控批准）。

## 8 复现命令

```powershell
# 默认档
pwsh -NoProfile -File scripts/run-tests.ps1 -Suite default
# spark 档（脚本内部显式切 JDK8，并注入本轮 -Dp2.test.runId）
pwsh -NoProfile -File scripts/run-tests.ps1 -Suite spark
# 隔离档（口令只经环境变量；库/账号须先用 scripts/it-prepare-isolation.ps1 准备）
$env:IT_GUARD_PASSWORD_MALL        = '<credref-dev003c_...-mall.properties 的 password>'
$env:IT_GUARD_PASSWORD_GENERATOR   = '<credref-dev003c_...-generator.properties 的 password>'
pwsh -NoProfile -File scripts/run-tests.ps1 -Suite isolated -RunId dev003c_20260915_1205 -Confirm
# 四档全量（本轮验收跑动）
pwsh -NoProfile -File scripts/run-tests.ps1 -Suite all -RunId dev003c_20260915_1205 -Confirm   # => exit 0
```

## 9 本轮未做 / 未测（显式标注）

- 未新增根 `pom.xml`、未创建 GitHub Actions、未改动 README 正文、未重构 `run-isolated-tests.ps1`。
- 未运行 `MetricAdsMySqlIT`／`MetricPublisherMySqlIT`／`SourceRegistryMigrationMySqlIT`／`SparkStageExecutorSmokeIT`／`AnalysisGoldenMySqlIT`（按总控裁决）。
- 未做 JDK17 反向对照实验（不在裁决范围；JDK8 已由 `mvn -version` 直接取证）。
- 未清理 3307 上累积的 runId 库/账号（见 §7）。
- 未提交、未 push（总控要求先复核）。
- 完整验收仍 **❌**；F-88 仍为**限定验收**，本轮不因 DEV-003c 而升级。
