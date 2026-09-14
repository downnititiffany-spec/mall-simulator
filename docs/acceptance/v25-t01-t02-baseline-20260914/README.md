# V25-T01 ＋ V25-T02 基线失败收口（证据目录）

| 项 | 值 |
|---|---|
| 泳道 | **L2**（`docs/项目实施进度与任务看板 V2.5.md` 登记） |
| 分支 | `remediation/r1-boundary` |
| 任务包 | V25-T01 ＋ V25-T02（全反应堆基线失败中的两处） |
| **证据级别** | **E1（编译/静态）＋ E2（模块自动化测试）**。**不是 E3**：未起停服务、未连库、未跑真实采集/管道、未碰 Spark/Hive/HDFS。**与「限定验收 / 完整验收」无关**，只报「**测试通过**」。 |
| 结论 | **V25-T01 = 测试通过（E1/E2）**；**V25-T02 = 测试通过（E1/E2）** |

原始日志全在 `raw/`，每条命令都带 `### 命令` / `### 开始` / `### EXIT=` / `### 结束`。
汇总见 **`raw/final-31-key-numbers.txt`**（从日志本体自动抽取，避免手抄）。
**踩过的坑**：未加引号的 `-D…` 参数会被 PowerShell 在**点号处切开**，本目录脚本一律写成带引号的单个字符串。

---

## 0. 环境与指纹

| 项 | 值 |
|---|---|
| JDK（analytics-server） | `D:\Develop\JAVA17` |
| JDK（spark-jobs） | `D:\Develop\JDK1.8` |
| Maven | `D:\apache-maven-3.9.14\bin\mvn.cmd`，**离线** `-o`，`-Dmaven.repo.local=D:\maven_repository` |
| 证据窗口内 HEAD（5 次变动，非本泳道提交） | `09d70468` 12:19 → `31b4b65e` 12:19 → `eb08ed6a` 12:20 → `3fcf90e3` 12:23 → `52a0e2ff` 12:30 → `f00462c5` 12:35（当前） |
| 工作树脏指纹 | `raw/final-00-git-status-porcelain.txt`、`raw/final-00-git-diff-stat.txt`、`raw/final-31-key-numbers.txt` 末节。**本泳道全程未 `git add/commit/push`** |

**HEAD 频繁变动的后果**：某一刻的"全反应堆中断"很可能只是别人正在写文件（见 §5）。因此每份日志都记了自己那一刻的 HEAD。

### 0.1 12:08–12:14 首批日志作废（只作环境就绪性记录）

`raw/baseline-*.log` 采于 12:08–12:14，当时 Q01 正在改
`platform-common/.../metric/{QualityRuleDefinition,RuleSeverity,QualityRuleCatalog}.java`，
`platform-common` 的 `testCompile` 被打断（`QualityRuleDefinition.java:[84,26] 找不到符号 isKnownSeverity`，
`RuleSeverityTest.java` 与 `QualityRuleCatalog.FrozenRules` 不兼容）。
**编译失败不是测试失败**，故这批**不作失败证据**，只记"环境在那 6 分钟不可用"。正式红证一律取 12:15 之后。

---

## 1. 两条基线失败与根因

| 编号 | 用例 | 模块 | 基线现象 | 根因 |
|---|---|---|---|---|
| V25-T01 | `WarehouseNameLiteralGateTest#noBareWarehouseNameLiterals` | platform-common | 红：2 处命中 | 门禁逐行跑正则、**不区分注释**；`TradeDwdJob.scala:145`、`SurrogateKey.scala:160` 两条 **scaladoc** 引用的事故原文被当成"第二处所有者" |
| V25-T02 | `IngestionManifestSourceSchemaTest#allOnDiskManifestsStillValidate` | platform-app | 红：`40.json..43.json` | 用例把**生产落盘目录** `landing/manifests` 当夹具，要求"目录里每个 `.json` 都是 15 键"；2026-09-12 真实采集写下的 4 个新格式清单（19 键）被误判成"旧清单被回填" |

两者都是**门禁误报**（不是产品缺陷）。按指导书 §9.5：修法必须保留检出能力、不删数据、不跳文件。

---

## 2. V25-T01：词法排除注释（历史原文一字未删）

### 2.1 改动清单

| 文件 | 改动 |
|---|---|
| `analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralScanner.java` | **新增**。范围收集 ＋ 词法剥离 ＋ 逐行匹配。`CommentSyntax`：java/scala/js=`//`+`/* */`；sql=`--`+`/* */`；yml/yaml/properties/conf/sh=`#`（ps1 另加 `<# #>`）；xml/md=`<!-- -->`；json/txt=NONE。剥离只把注释区间换成空格、**保留 `\n`（行号不变）与字符串内容**；**未登记扩展名 fail-closed 抛异常**；`SKIP_DIRS` 只含 `target/node_modules/.git/landing/static`（构建产物/数据目录），**不跳过任何源文件** |
| `.../warehouse/WarehouseNameLiteralGateTest.java` | 判定改调扫描器；新增「真 SQL 负例（`@TempDir` 夹具仓，走同一条端到端路径，覆盖 Scala 三引号 / Java 文本块 / SQL 文件 / shell 字符串）」「仅注释不报红」「**见证**两条历史注释仍在库内」「与已保留证据逐字核对」「词法表覆盖所有被扫描扩展名」「词法家族」「范围良构」；原「DDL 从共享前缀派生库名」用例原样保留 |

**未改动**：`TradeDwdJob.scala:145`、`SurrogateKey.scala:160` **原样保留**；`spark-jobs/**` 只读。

### 2.2 红证（旧口径在同一次运行里判红这两行）

`raw/final-01-t01-red-legacy-probe.log`（12:16:29，把 **HEAD 原版**门禁用例逐字取出、仅改类名后运行）：

```
### 命令: mvn.cmd -o -Dmaven.repo.local=D:\maven_repository -f analytics-server/pom.xml -pl platform-common -am
###        -Dtest=WarehouseNameLiteralGateLegacyProbeTest -DfailIfNoSpecifiedTests=false test
[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in ...WarehouseNameLiteralGateLegacyProbeTest
Expecting empty but was: ["spark-jobs/src/main/scala/com/graduation/analytics/job/TradeDwdJob.scala:145 → * 建后即删，未碰 `dw_dwd.dwd_order_detail`）：",
                        "spark-jobs/src/main/scala/com/graduation/analytics/sql/SurrogateKey.scala:160 → *    table spark_catalog.dw_dwd.dwd_order_detail: Cannot safely cast user_key \"STRING\" to \"BIGINT\".`"]
### EXIT=1
```

**就是基线那两行、行号一致**，且 `Tests run: 3, Failures: 1` ⇒ 红证成立（不是空跑）。

**红证可复现（不放死用例、也不只剩日志）**：`raw/legacy-gate/`
- `head-original-WarehouseNameLiteralGateTest.java.txt` = `git show HEAD:…` 原文，sha256 `DCAC104D…1568F2`
- `LegacyProbe.java.txt` = 原文 ＋ 4 行来源注释 ＋ 类名加 `LegacyProbe` 后缀，**重建 sha256 `CF8ADD425F9CB2625B90C267EF63726535746D50AE5E00FC9B2DD8ABC7D9B9B0` 与 12:16 实跑时登记的 sha256 完全一致**
- `README-legacy-probe.txt`：重建命令 ＋ "为何不长期保留 `@Disabled` 用例"（理由：门禁能力已由新用例的真 SQL 负例覆盖，留一个永远不再执行的旧实现对后来人是误导；而全文 ＋ sha256 让人**可随时逐字重建并复跑**）
- 源码树副本 12:16:42 已删（`raw/final-01-t01-legacy-probe-provenance.txt` 含 `Test-Path=False` 与 `git status`）

### 2.3 绿证

| 证据 | 命令 | 结果 |
|---|---|---|
| 模块级 | `-pl platform-common -am '-Dtest=Warehouse*' … test`（`raw/final-11-t01-warehouse-gate-green.log`，12:19） | **EXIT=0**，`WarehouseNameLiteralGateTest` **9/0/0**、`WarehouseNamespaceContractTest` 24/0/0，合计 33/0/0 |
| 逐字 T0 | `-pl platform-common -am test`（`raw/final-12-verbatim-platform-common-test.log`，12:19） | **EXIT=0**，`Tests run: 79, Failures: 0, Errors: 0` |
| 全反应堆内 | 收集口径全 reactor（`raw/final-24-full-reactor-collect.log`） | `WarehouseNameLiteralGateTest` **9/0/0**，platform-common 81/0/0 |
| 独立探针 | `raw/probe/T01Probe.java` ＋ `raw/probe/t01-probe.log` | `scanned=234`；RAW（旧口径）2 处；CODE（新口径）**0** 处；夹具真 SQL **4 处仍判红**；`PROBE-OK（0 处失败）` |

**不是空跑**：同一探针在夹具仓里对真正的 SQL 字符串照旧判红（4 个词法家族），未登记扩展名会 fail-closed 抛错。

### 2.4 历史原文留存的**精确**边界（不夸大）

| 侧 | 位置 | 文本 |
|---|---|---|
| 留存证据 | `docs/acceptance/m3-step8-parity-20260912/raw/post/p2-03-regression-evidence.txt:119-121` | ``data for the table `spark_catalog`.`dw_dwd`.`dwd_order_detail`: Cannot safely cast `user_key` `` / ``"STRING" to "BIGINT".`` |
| 源码 | `SurrogateKey.scala:159-160` | `[INCOMPATIBLE_DATA_FOR_TABLE.CANNOT_SAFELY_CAST] …` / `table spark_catalog.dw_dwd.dwd_order_detail: Cannot safely cast user_key "STRING" to "BIGINT".` |

留存侧带 Spark CLI 的反引号并按终端宽度折行；源码侧去掉反引号、加了 scaladoc 前缀。
即「**去掉引用符号与折行后逐字相同**」，**不是字节级相同**（由用例 `historicalErrorTextIsPreservedInEvidence` 钉住）。

---

## 3. V25-T02：冻结契约 ＋ 运行时巡检（真实清单一个字节没动）

### 3.1 改动清单

| 文件 | 改动 |
|---|---|
| `analytics-server/platform-app/src/test/resources/ingestion-manifest-freeze/` | **新增 44 个文件**：`freeze.json`（`frozenAt=2026-09-14`、`legacyFrozenCount=39`、`newFrozenCount=4`、逐条 名字/sha256/键数/字节数）＋ `legacy/1..39.json`（P1-05 前，15 键）＋ `new/40..43.json`（P1-05 后真实采集，19 键）。逐字节 `Copy-Item`，sha256 由 `Get-FileHash -Algorithm SHA256` 生成 |
| `.../ingestion/IngestionManifestSchemaSubset.java` | **新增**：把原用例内部的 schema 子集校验器抽出，**契约测试与巡检共用同一实现**（否则"契约通过"与"巡检通过"不能互相印证） |
| `.../ingestion/IngestionManifestSourceSchemaTest.java` | ② 改为**只验冻结副本**（密闭）：`frozenCopiesMatchFrozenChecksums`（39＋4 条名字/键数/sha256 逐条核对 ⇒ **删夹具无法变绿**）、`frozenLegacyCopiesStillValidateBeforeAndAfterP1_05`（原集合差断言原样保留，并把 1–5.json 的 `batchId` 历史漂移钉桩）、`frozenNewCopiesValidateWithAllFourKeys` |
| `.../ingestion/ManifestFreezePatrol.java` | **新增**：运行时巡检（冻结名单＋sha256；旧格式出现新键 ⇒ "疑似被回填"；缺失/字节改动 ⇒ 判红；**名单外新到文件只登记不判红**；目录整个不在 ⇒ 显式判红） |
| `.../ingestion/IngestionManifestRuntimePatrolTest.java` | **新增**：真实目录巡检（1–43.json 原样留存）＋ 负例（回填 / 等长字节改写 / 缺失 / 目录不在）＋「新到文件不判红」（新格式 19 键 `44.json` 与旧格式 15 键 `45.json` **两种**新到件） |

### 3.2 两组分开跑（**Maven 真实执行，有 `Tests run: N` 且 N>0**）

| 组 | 命令（逐字，`raw/` 内） | EXIT | 用例数 |
|---|---|---|---|
| 冻结契约组 | `-pl platform-app -am '-Dtest=IngestionManifestSourceSchemaTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`（`raw/final-23a-t02-contract-group.log`） | **0** | `Tests run: 9, Failures: 0, Errors: 0`（7/7 模块 SUCCESS） |
| 运行时巡检组 | 同上换 `-Dtest=IngestionManifestRuntimePatrolTest`（`raw/final-23b-t02-runtime-patrol-group.log`） | **0** | `Tests run: 3, Failures: 0, Errors: 0` |
| 等长改写的单用例 | 同上 `-Dtest=IngestionManifestRuntimePatrolTest#patrolSeparatesArrivalsFromRealViolations`（`raw/final-23c-t02-tamper-method.log`） | **0** | `Tests run: 1, Failures: 0, Errors: 0` |
| 独立探针（不依赖 Maven） | `raw/probe/T02Probe.java` ＋ `raw/probe/t02-probe.log` | 0 | 真实目录 **冻结 43、违规 0**；回填/改写/缺失/目录不在四负例全部判红；`PROBE-OK（0 处失败）` |

> 早期 `raw/final-04a/04b/10a/10b` 四条 T02 命令**从未跑到 T02**：我传的 `-DfailIfNoSpecifiedTests=false`
> **不是 surefire 3.1.2 的键**（surefire 自己的报错原文写的就是正确键），上游 `platform-common` 因此
> `No tests matching pattern …` 直接把反应堆打停在 platform-app 之前。**这几条不作证据**，正确键重跑的
> 结果就是上表 `final-23a/23b/23c`（见 §6 缺陷 #3）。

### 3.3 「新到件不判红」＋「等长改写判红」（本次误报的核心）

`raw/t02-tamper-negative.log`（命令与输出逐字，独立于 Maven）：

```
被改写文件: 2.json
  冻结 sha256 : d0dfb5d9af70249561edb437d73e8bcb62b23b33f84f05543dc458a23e12ffed
  盘上 sha256 : 3ea57218ac66aef21e7fe9003c7f33f3d45b890c8e87703eb68870412ec086bd
  冻结 bytes  : 5944   盘上 bytes: 5944   （字节数相同 ⇒ 只有 sha256 能发现）
  差异片段: "local-file" → "local-filf"
[巡检输出] 冻结登记 43 个（全部 43 个）… 违规: 1
  [违规] 2.json 内容被改动：sha256 3ea57218… ≠ 冻结 d0dfb5d9…
新到文件（不判红）: 0
TAMPER-PROBE-OK（0 处失败）
```

- **判红依赖 sha256，不依赖文件名存在性**：字节数不变、内容不同 ⇒ 仍被抓到，且**不会被降级成"新到件"放过**。
- **新到件不判红**（正是 2026-09-12 误报的形态）：19 键 `44.json` 与 15 键 `45.json` 都只被**登记**；真实目录里 40–43.json 同样只登记为"新到件"，违规 0。

### 3.4 真实清单 40–43.json 未被改动（正面证据）

| 证据 | 内容 |
|---|---|
| `raw/final-00-landing-before.txt` | 采集前 43 个文件的 名字/字节数/mtime/sha256 ＋ `git check-ignore -v landing/manifests/40.json` → **`.gitignore:30:landing/`** ＋ `git status --porcelain landing`（空） |
| `raw/final-08-landing-after.txt` | 采集后同一快照 |
| `raw/final-18-landing-rows-verdict.txt` | **数据行 43 = 43，逐行完全相同**（内容、字节数、mtime、sha256 全程未变） |
| `raw/final-27-landing-untouched-final.txt` | 全部运行结束后（12:28:57）再确认一次：`文件数: 43`、`与采集前逐行相同`、`git status --porcelain landing` 空、`.gitignore:30:landing/` |

`landing/` 被 `.gitignore:30` 忽略 ⇒ **删掉它不会在 `git status` 里显形**，这正是巡检必须显式断言
「目录存在 ＋ 文件存在 ＋ 字节未变」而不能只看 git 的原因（`missingDirectoryIsAViolation` 覆盖）。

---

## 4. 官方命令与**真实**退出码

| # | 命令（逐字） | 时间 / HEAD | 日志 | EXIT | 用例统计 | 结论 |
|---|---|---|---|---|---|---|
| 1 | `-f analytics-server/pom.xml -pl platform-common -am test` | 12:19 `31b4b65e` | `final-12` | **0** | 79 / 0F / 0E | ✅ 绿（含 T01 门禁 9/9） |
| 2 | `-f analytics-server/pom.xml -pl platform-app -am test` | 12:19 `31b4b65e` | `final-13` | **1** | common 79/0/0、ingestion 156/0/0、warehouse-pipeline **131 / 1F / 1E** | ❌ **platform-app SKIPPED**（§4.1） |
| 3 | 同上（30 分钟后重跑） | 12:33 `52a0e2ff` | `final-30` | **1** | common 81/0/0、ingestion 156/0/0、warehouse-pipeline **132 / 10F** | ❌ **platform-app SKIPPED** |
| 4 | `-f analytics-server/pom.xml test`（全反应堆逐字） | 12:30 `52a0e2ff` | `final-26` | **1** | 同 #3，停在 warehouse-pipeline | ❌ 中断（外部红） |
| 5 | `… -pl platform-app -am test-compile` | 12:32 `52a0e2ff` | `final-29` | **0** | 7/7 模块 SUCCESS、无编译错误 | ✅ **platform-app test-compile 绿** |
| 6 | `-f spark-jobs/pom.xml test`（JDK8） | 12:22 | `final-16` | **0** | scalatest：`Total number of tests run: 111`、`Suites: completed 15, aborted 0`、`All tests passed.` | ✅ 绿（§4.2） |
| 7 | 收集口径 `… '-Dmaven.test.failure.ignore=true' test` | 12:31 `52a0e2ff` | `final-24` | 0（ignore 生效） | **7/7 模块全部跑到**：81＋156＋**132(10F)**＋48＋91＋104 = **612 例**，**红 10 例全在 warehouse-pipeline** | ✅ 用于**定位**红点，不作"通过"证据 |
| 8 | 定向组 ①a/①b/①c（§3.2 三条） | 12:29 | `final-23a/23b/23c` | **0 / 0 / 0** | 9 / 3 / 1 例，全 0F 0E | ✅ T02 的 T 级证据 |

### 4.1 「`-pl platform-app -am` 看不见 T02」——四次复现，且是**真实发生**的

`raw/final-30-verbatim-platform-app-am-test.log`（12:33）：

```
[INFO] platform-common .................................... SUCCESS [  6.021 s]   81/0/0
[INFO] connection-ingestion ............................... SUCCESS [  7.525 s]  156/0/0
[INFO] warehouse-pipeline ................................. FAILURE [ 42.046 s]  132 例：10F（全在 PipelineServiceTest）
[INFO] metric-analysis .................................... SKIPPED
[INFO] ai-decision ........................................ SKIPPED
[INFO] platform-app ....................................... SKIPPED      ← T02 用例在这里，从未执行
[ERROR] Failed to execute goal …maven-surefire-plugin:3.1.2:test (default-test) on project warehouse-pipeline: There are test failures.
### EXIT=1
```

`platform-app` 依赖 `platform-common` 的 **test-jar**，反应堆默认 fail-fast ⇒ **链上任何一个上游模块红（编译红或测试红），
T02 的结论都不可知**。四次复现：`final-02`（12:16，上游编译红）、`final-13`（12:19，warehouse-pipeline 1F/1E）、
`final-26`（12:30，10F）、`final-30`（12:33，10F）。

**这条命令能证明什么 / 不能证明什么**（V25-T02 要求写清）：
- **能**证明：整条依赖链（analytics-server → platform-common → connection-ingestion → warehouse-pipeline → metric-analysis → ai-decision → platform-app）**从编译到测试**是通的——**前提是它真的以 EXIT=0 结束**。
- **不能**证明：**本轮它没有一次以 EXIT=0 结束**（EXIT=1，platform-app SKIPPED）⇒ 它**对 T02 零证明力**。
  T02 的结论只能来自 §3.2 的 **`-Dtest` 定向组（EXIT=0、N>0）** ＋ §3.3 的负例 ＋ §3.4 的清单未改动证据。

### 4.2 `spark-jobs` 的「111 与 0」不矛盾：两个插件，各自的分工

`raw/final-16-spark-jobs-jdk8-test.log`（JDK8，`-f spark-jobs/pom.xml test`，**EXIT=0**）：

```
[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0 -- in com.graduation.analytics.MetricAdsSpecTest
[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- scalatest:2.2.0:test (test) @ spark-jobs ---
[INFO] ScalaTest report directory: D:\Develop_code\GraduationProject\spark-jobs\target\surefire-reports
Total number of tests run: 111
Suites: completed 15, aborted 0
All tests passed.
[INFO] BUILD SUCCESS
```

- **111 出自 `scalatest-maven-plugin:2.2.0:test`** —— 项目真实测试证据。
- **0 出自 `maven-surefire-plugin`** —— 它把 ScalaTest 套件 `MetricAdsSpecTest` 当测试类列出来，但**跑不到 JUnit 方法**；这个 0 不是项目测试证据。
- 两个插件**都往 `spark-jobs/target/surefire-reports/` 写报告**，所以那个目录名字会误导。
- **第三方独立复算**（直接读报告 XML，不靠日志）：该目录 `TEST-*.xml` 共 17 份，逐份汇总 **suites=17、tests=111、failures=0、errors=0**（14 个真实套件 ＋ 3 个空 `DiscoverySuite`），与「111 通过 / 15 套件」一致。
  ⇒ 09-14 记录的 `spark-jobs` 111/111 基线**可恢复为已取证**，但须注明"111 出自 scalatest 插件"。

---

## 5. 阻断项与归属（**都不是本泳道产物**）

| # | 时间 | 现象 | 归属证据 |
|---|---|---|---|
| 1 | 12:08–12:14 | platform-common testCompile 红（`isKnownSeverity`、`FrozenRules`） | Q01 正在改 `metric/*`（§0.1） |
| 2 | 12:16:48 | `TestIsolationGuard.java:[724,18] 找不到符号 InvalidPathException` ⇒ testCompile 中断 | `raw/final-03`；该文件**不在 HEAD**（`git cat-file -e HEAD` 退出码 128）、不在本泳道改动清单（`raw/final-17-foreign-red-attribution.txt`） |
| 3 | 12:17–12:20 | `TestIsolationGuardTest` 红 1F＋3E（`hdfsRoot` 的 `file://` URI / `/tmp/...` 路径规则） | 同上；12:19 起该泳道修绿（`final-12` 中 18/0/0） |
| 4 | 12:19 / 12:30 / 12:33 | `warehouse-pipeline` 红 ⇒ **掩盖 T02**：12:19 为 `EventContractTest` 1E＋`PipelineServiceTest` 1F（131 例），12:30/12:33 恶化为 `PipelineServiceTest` **10F**（132 例） | `raw/final-13`、`final-26`、`final-30`；该模块 `PipelineService.java`、`DataQualityGate.java`、`QualityChecker.java`、`JobResultParser.java` 此刻在 `git status` 里都是 ` M`（另一泳道在改，**都在本泳道禁改清单上**） |
| 5 | 12:22–12:27 | `RuleSeverityTest.java` 与 `QualityRuleCatalog.FrozenRules` 不兼容 ⇒ platform-common testCompile 再次红 ⇒ 逐字全反应堆与收集口径中断 | `raw/final-14`、`raw/final-15`；该文件 12:27:08 被改后转绿 |
| 6 | 窗口内 | HEAD 被其他泳道推进 5 次（§0） | 各日志的 `### 开始 … HEAD=` |

**本泳道未修改**任何 `main` 产品代码、`contract-specs/**`、`docs/contracts/**`、指导书、看板、既有证据目录；
未做 DB DDL/DML、未启停服务、未跑真实管道、未碰 Spark/Hive/Hadoop。

---

## 6. 本泳道自身暴露并已修复的缺陷（如实记录）

| # | 缺陷 | 表现与后果 | 处理 |
|---|---|---|---|
| 1 | `IngestionManifestRuntimePatrolTest#missingDirectoryIsAViolation` 缺 `throws IOException` | **platform-app `testCompile` 整体失败**，连带卡住别人要在 platform-app 落地的跨模块护栏证据。**被上游红遮住，直到 12:27 上游转绿才暴露**——因为在那之前 platform-app 根本没被编译过 | **已修**（按 JUnit5 允许的方式加 `throws`，未吞异常）；错误原文留档 `raw/final-23a-my-own-testcompile-error.log`；修后 `test-compile` **EXIT=0**（§4 #5） |
| 2 | `IngestionManifestSchemaSubset.fixture()` 无调用者 | 死代码 | 已删除（连同其 import） |
| 3 | `-DfailIfNoSpecifiedTests=false` **属性名错** | surefire 3.1.2 报 `No tests matching pattern … were executed!` ⇒ `final-04a/04b/10a/10b` 四条 T02 命令在 platform-common 就死，**platform-app SKIPPED** ⇒ 那批数字**对 T02 零证明力** | 正确键 **`-Dsurefire.failIfNoSpecifiedTests=false`**；重跑即 `final-23a/23b/23c`（EXIT=0、N>0） |
| 4 | PowerShell 未加引号的 `-Dsurefire.…` / `-Dmaven.test.failure.ignore=true` 被点号切开 | maven 报 `Unknown lifecycle phase ".failIfNoSpecifiedTests=false"` ⇒ `final-20*/21` aggregator 秒失败 | 一律写成带引号的单参数；`final-23*` 起生效 |
| 5 | PowerShell 函数名 `R` 被内置别名 `Invoke-History` 抢占 | `final-23b/23c/26/24` 全部没跑（`找不到接受自变量`） | 改名 `Invoke-Mvn`；`run-final4.ps1` 重跑成功 |
| 6 | `T02TamperProbe` 第一版只冻结 1 个条目 | 探针自己的期望写错（另 42 个按设计被登记为"新到件"），行为本身正确 | 改为冻结全部 43 个；第一版输出留档 `raw/t02-tamper-negative-firstrun.log` |

---

## 7. 未取证 / 未做（明确列出，避免以后论文里出现"测过但没跑"的数字）

- **没有一次"全绿的全反应堆"**：逐字 `-f analytics-server/pom.xml test` 在本窗口三次尝试（12:22/12:30 ＋ 收集口径）**都被 warehouse-pipeline 的外部红挡在 platform-app 之前**。§4 #7 的 612 例只能证明"T01/T02 在真实全反应堆拓扑里跑了且全绿"，**不能**证明整体绿。
- **无 E3**：未起停 8090/8091/8092、未跑真实采集/管道、未连任何库（连只读 SELECT 也没做）、未碰 Spark/Hive/HDFS。`landing/manifests/40–43.json` 落盘四字段的**取值正确性**属 E3，本包只证明"它们未被改动且与冻结契约一致"。
- **未提交**：全程未 `git add/commit/push`（本包禁止）。
- **spark-jobs 未在本轮重跑**：引用的是 12:22 那次 JDK8 实跑（§4.2）。
- 首批 12:08–12:14 日志作废（§0.1）。
- 未做 JDK 版本交叉验证（与基线一致：spark-jobs=JDK8，其余=JDK17）。

---

## 8. `raw/` 索引

| 文件 | 说明 |
|---|---|
| **`final-31-key-numbers.txt`** | **关键数字汇总**（从日志本体自动抽取，含 HEAD 序列与工作树指纹） |
| `run-baseline.ps1` / `baseline-*` | 12:08–12:14 首批（**已作废**，仅环境就绪性）＋ 环境/HEAD/工作树指纹 |
| `run-final.ps1` / `final-00..09-*` | 第一批：T01 红证 ＋ 掩盖复现 ＋ 逐字三条命令 ＋ spark-jobs ＋ landing 前后快照 |
| `run-final2.ps1` / `final-10..19-*` | 第二批：定向组尝试（暴露属性名错误）＋ 逐字命令 ＋ 外部红归属取证 |
| `run-final3.ps1`、`run-final3b.ps1`、`run-final4.ps1`、`make-key-numbers.ps1` / `final-20..31-*` | 第三、四、五批：等长改写负例 ＋ 定向组（正确参数）＋ 逐字全反应堆 ＋ 收集口径 ＋ `test-compile` ＋ 最终 landing 复核 ＋ 汇总 |
| `capture-artifacts.ps1` / `legacy-gate/` | T01 红证原文留档与**重建 sha256 核验** |
| `t02-tamper-negative.log`（＋ `-firstrun.log`） | 等长字节改写负例（命令＋输出） |
| `probe/T01Probe.java`、`probe/T02Probe.java`、`probe/T02TamperProbe.java` ＋各自 `.log` | 不依赖 Maven 的独立复算证据 |
| `final-09-summary.txt`、`final-19-summary.txt`、`final-22-summary.txt` | 各批关键行摘要（`final-25/28-summary.txt` 因 PowerShell `-Filter` 不支持字符类而为空，以 `final-31` 为准） |
