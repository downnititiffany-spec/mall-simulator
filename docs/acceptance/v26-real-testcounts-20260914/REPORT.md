# 真实测试数取证报告（v26 · 2026-09-14）

- **仓库**：`D:\Develop_code\GraduationProject`
- **分支**：`remediation/r1-boundary`
- **基线 HEAD**：`064b733b78a02d72c7150f6ac21d686ef8fdc114`（预检与收尾均实测为该值；本报告不新增提交）
- **证据目录**：`docs/acceptance/v26-real-testcounts-20260914/`
- **可写实例**：WSL2 隔离 MySQL `127.0.0.1:3307`，`@@server_uuid=de8ebbea-aff4-11f1-8037-00155d5dba47`、`@@port=3307`、`@@hostname=dahaishui`、`VERSION()=8.0.41`（每次运行前实测校验）
- **口径纪律**：**未实测不写结论**。凡未实际跑动/观测到的，一律在《6. 未取证 / BLOCKED 清单》中标明「未取证/BLOCKED」，不与已实测结论混写。
- **3306 纪律**：本报告全程**未连接、未读取、未写入 3306**，未启动 `platform-app`，未设置任何指向 3306 的环境变量。3306 的 before/after 指纹对比属控制方职责，不在本报告范围内（见 §6）。

---

## 1. 时间线、命令与退出码

所有命令均为**实测跑动**，日志落盘于 `raw/`。时间取自各日志内 Maven 的 `Finished at:`（`run-isolated-tests.ps1` 段取自脚本自身时间戳）。

| # | 时间(2026-09-14) | 目标 | 命令（要点） | 退出码 | 日志 |
|---|---|---|---|---|---|
| G1 | 16:52:34 | `analytics-server` 默认档全反应堆 | `cd analytics-server; mvn -o -Dmaven.repo.local=D:\maven_repository test` | **0** | `raw/g1-01-default-reactor-test.log` |
| G1b-01 | 16:53:07 | `SourceRegistryMigrationMySqlIT` 点名执行（无隔离配置） | `-pl platform-app -am -Dtest=SourceRegistryMigrationMySqlIT` | **0** | `raw/g1b-01-...log` |
| G1b-02 | 16:53:11 | `MetricAdsMySqlIT` 点名执行（无隔离配置） | `-pl metric-analysis -am -Dtest=MetricAdsMySqlIT` | **0** | `raw/g1b-02-...log` |
| G1b-03 | 16:53:15 | `SparkStageExecutorSmokeIT` 点名执行 | `-pl warehouse-pipeline -am -Dtest=SparkStageExecutorSmokeIT` | **1** | `raw/g1b-03-...log` |
| G1b-04 | 16:53:39 | `MetricPublisherMySqlIT` 点名执行 | `-pl metric-analysis -am -Dtest=MetricPublisherMySqlIT` | **0** | `raw/g1b-04-...log` |
| G1b-05 | 16:53:42 | `AnalysisGoldenMySqlIT` 点名执行 | `-pl metric-analysis -am -Dtest=AnalysisGoldenMySqlIT` | **0** | `raw/g1b-05-...log` |
| G3-01 | 16:54:03 | `mall-simulator` 默认档 | `mvn -o -f mall-simulator/pom.xml test` | **0** | `raw/g3-01-default-mall-simulator.log` |
| G3-01 | 16:54:17 | `synthetic-data-generator` 默认档 | `mvn -o -f synthetic-data-generator/pom.xml test` | **0** | `raw/g3-01-default-synthetic-data-generator.log` |
| G3-02 | — | 隔离准备 | `scripts/it-prepare-isolation.ps1`（脚本自身用法） | **0** | `raw/g3-02-prepare-isolation.log` |
| G3-03 | — | **隔离档唯一入口** | `scripts/run-isolated-tests.ps1`（脚本自身用法，未改脚本/POM） | **7** | `raw/g3-03-run-isolated-tests.log` + `raw/g3-03-isolated/` |
| G2-01a | 16:59:14 | metric 两个写入型 IT（**参数被脚本语法打散，无效运行**） | 见 §1.2 | 0 | `raw/g2-01-...-MANGLEDARGS.log` |
| G2-02a | 16:59:15 | `SourceRegistryMigrationMySqlIT`（**无效运行**） | 见 §1.2 | 1 | `raw/g2-02-...-MANGLEDARGS.log` |
| G2-03a | 16:59:19 | `AnalysisGoldenMySqlIT`（**无效运行**） | 见 §1.2 | 1 | `raw/g2-03-...-MANGLEDARGS.log` |
| G2-01 | 17:00:42 | `MetricAdsMySqlIT` + `MetricPublisherMySqlIT`（`-Dv25.it.*` 修正后） | `-pl metric-analysis -am -Dtest=MetricAdsMySqlIT,MetricPublisherMySqlIT -Dsurefire.failIfNoSpecifiedTests=false` + 15 个 `-Dv25.it.*` | **1** | `raw/g2-01-metric-analysis-ITs.log` |
| G2-02 | 17:00:51 | `SourceRegistryMigrationMySqlIT`（`-Dp1.it.*`） | `-pl platform-app -am -Dtest=SourceRegistryMigrationMySqlIT` + 7 个 `-Dp1.it.*` | **1** | `raw/g2-02-...log` |
| G2-03 | 17:00:56 | `AnalysisGoldenMySqlIT`（键名 `.username` 错误，暴露键名不一致） | 同 G2-01 + `-Dmetric.it=true` | **1** | `raw/g2-03-...log` |
| G2-03b | 17:01:48 | `AnalysisGoldenMySqlIT`（键名修正为 `metric.read.user`） | 同 G2-03 + 16 个属性 | **1** | `raw/g2-03b-...log` |
| G3-04 | — | 指纹探针（**仅改注入的环境变量值**） | 保持 runner 其余不变，`IT_GUARD_SERVERFINGERPRINT=3307` + generator 的 `SPRING_DATASOURCE_*` | mall **0** / gen **1** | `raw/g3-04-fingerprint-probe/` |
| G4 | 17:02:45 | `spark-jobs` **当前主工作树** | `cd spark-jobs; JAVA_HOME=D:\Develop\JDK1.8; mvn -o -Dmaven.repo.local=D:\maven_repository test` | **0** | `raw/g4-01-spark-jobs-main-tree-tests.log` |

### 1.1 隔离档退出码契约（`scripts/run-isolated-tests.ps1`）

脚本自身契约：`0`=通过、`1`=参数/环境错误、`5`=门禁拒绝、`6`=只读探针失败、`7`=Maven 套件失败。
**实测**：本脚本**先通过全部 6 道门禁**（含 `[门禁6] mall/generator 探针 OK：port=3307 uuid=de8ebbea-… hist=dahaishui 库存在`），随后两个模块的 Maven 套件均非 0 退出，脚本按契约以 **`[FAIL exit=7] 2 个模块套件非 0 退出`** 结束。
→ 即：**失败点不在参数(1)/门禁(5)/只读探针(6)，而在 Maven 套件(7)**。这一区分是本报告后文根因定位的基础。

### 1.2 证据完整性事故与更正（必须披露）

G2 的**首次**尝试（记为 `*-MANGLEDARGS.log`）是**无效运行**，原因在**取证脚本自身**，不在被测代码：

- 脚本写成 `@('-Dv25.it.testRunId='+$run,'-Dv25.it.metaDb='+$metaDb, …)`。PowerShell 中 `@()` 内的逗号运算符优先级**低于** `+`，于是被解析为 `'A' + ($run,'B') + $metaDb + …` 的**数组拼接**。
- 实测证据：本应 7 个元素的数组被解析成 **10 个**元素，`-Dp1.it.metaDb=` 与 `v26it_20260914_1700_meta` 被拆成两个 argv；Maven 报 `Unknown lifecycle phase "v26it_20260914_1700_meta"`。
- 更正方式：改为逐个字符串插值 `"-Dv25.it.metaDb=$metaDb"`，并断言 `$base.Count=15`、`$p1.Count=7` 后再运行。
- **首轮结论作废、二轮为准**；两轮日志**均**留档，便于控制方复核。

> 附带澄清（易误判）：`MissingConfigurationException` 文本里出现的
> `C:\Users\ASUS\AppData\Local\Temp\v25-it-system-properties.marker`
> **不是被读取的文件**。源码 `TestIsolationGuard.systemPropertiesMarker()`（L220-222）仅用它作**来源标记**：
> `fromSystemProperties()`（L201-211）先从 `System.getProperty("v25.it.<key>")` 读 8 个键，再把该路径当 label 传给 `fromProperties()` 做报错定位。实测该文件**不存在**（`ABSENT`），与上述结论一致。

---

## 2. 真实测试数表

单位说明：**静态**＝源码计数（类文件数 / 行首 `@Test` 数）；**实际**＝Maven 报告实测（`Tests run:` 汇总行 / ScalaTest `Total number of tests run:`）。

### 2.1 `analytics-server` 默认档（G1 实测，退出码 0）

| 模块 | 静态 `src/test/java` 文件 | 其中匹配 surefire 默认 include | 静态 `@Test` | **实测 Tests run** | 未执行的 `*IT` 数 |
|---|---|---|---|---|---|
| platform-common | 13 | 9 | 59 | **81** | 0 |
| connection-ingestion | 18 | 17 | 150 | **156** | 0 |
| warehouse-pipeline | 15 | 13 | 111 | **134** | 1 |
| metric-analysis | 10 | 7 | 59 | **48** | 3 |
| ai-decision | 10 | 10 | 91 | **91** | 0 |
| platform-app | 22 | 18 | 121 | **114** | 1 |
| **合计** | **88** | **74** | **591** | **624** | **5** |

实测汇总行：`Tests run: 624, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，`Total time: 40.092 s`。

安全证据（同一次运行日志全文计数，`raw/g1-03-safety-jdbc-port-grep.txt`）：
`jdbc:mysql`=**0**、`3306`=**0**、`3307`=**0**、`Connection refused`=**0**、`Communications link failure`=**0**、`CommunicationsException`=**0**。
`SourceRegistryMigration` 命中 2 次，**均**为 `SourceRegistryMigrationScriptTest`（单元测试），非 IT。

### 2.2 5 个 `*IT.java` 的逐类真实数

| IT 类（模块） | 默认档点名执行 | 隔离档点名执行 | 结论 |
|---|---|---|---|
| `SourceRegistryMigrationMySqlIT`（platform-app） | `Tests run: 7, Skipped: 7`，退出 **0** | `Tests run: 1, Errors: 1`，退出 **1** | 默认档**静默跳过**；隔离档**结构性必败**（§3.3） |
| `MetricAdsMySqlIT`（metric-analysis） | `Tests run: 2, Skipped: 2`，退出 **0** | `Tests run: 1, Errors: 1`，退出 **1** | 默认档静默跳过；隔离档被 `@@version_major` 卡死（§3.2） |
| `MetricPublisherMySqlIT`（metric-analysis） | `Tests run: 3, Skipped: 3`，退出 **0** | `Tests run: 1, Errors: 1`，退出 **1** | 同上 |
| `AnalysisGoldenMySqlIT`（metric-analysis） | `Tests run: 6, Skipped: 6`，退出 **0** | `Tests run: 1, Errors: 1`，退出 **1** | 默认档静默跳过；隔离档**环境性不满足**（§3.5） |
| `SparkStageExecutorSmokeIT`（warehouse-pipeline） | `Tests run: 1, Errors: 1`，退出 **1** | — | **唯一**在无配置时硬失败的 IT（§3.1） |

**5 个 IT 中通过数为 0。**

### 2.3 `mall-simulator` / `synthetic-data-generator`（G3 实测）

| 项目 | 静态类数 | 静态 `@Test` | `@Tag("it")` 类数 / `@Test` 数 | 默认档实测 | 隔离档实测 |
|---|---|---|---|---|---|
| `mall-simulator` | 16 | 38 | 9 / 30 | **8**（退出 0） | runner as-run **30 run / 0F / 30E / 0S**（退出 1）<br>探针 G3-04 **30 run / 0F / 0E / 0S**（退出 **0**） |
| `synthetic-data-generator` | 23 | 120 | 3 / 19 | **101**（退出 0） | runner as-run **19 run / 5F / 14E / 0S**（退出 1）<br>探针 G3-04 **19 run / 0F / 5E / 0S**（退出 1） |

默认档合计 **109** 全绿；隔离档 49 个 `@Tag("it")` 用例在 runner 下 **44 个 error / 5 个 failure / 0 个通过**。

### 2.4 `spark-jobs` 当前主工作树（G4 实测，JDK8，退出码 0）

```
Run completed in 20 seconds, 970 milliseconds.
Total number of tests run: 111
Suites: completed 15, aborted 0
Tests: succeeded 111, failed 0, canceled 0, ignored 0, pending 0
All tests passed.
```

| # | 套件 | 实测 tests |
|---|---|---|
| 1 | `com.graduation.analytics.OdsV2SchemaOwnerSpec` | 10 |
| 2 | `com.graduation.analytics.sql.SurrogateKeySpec` | 11 |
| 3 | `com.graduation.analytics.SqlTemplateSpec` | 22 |
| 4 | `com.graduation.analytics.OdsV2ByteFidelitySpec` | 9 |
| 5 | `com.graduation.analytics.OdsV2SqlContractSpec` | 8 |
| 6 | `com.graduation.analytics.IdCodecSpec` | 7 |
| 7 | `com.graduation.analytics.algorithm.FunnelHeatAnomalySpec` | 7 |
| 8 | `com.graduation.analytics.OrderTradeCompilerSpec` | 7 |
| 9 | `com.graduation.analytics.sql.JsonObjectSlicerSpec` | 6 |
| 10 | `com.graduation.analytics.WarehouseNamespaceSpec` | 6 |
| 11 | `com.graduation.analytics.algorithm.QuartileStatsRfmSpec` | 5 |
| 12 | `com.graduation.analytics.MetricAdsSpecTest` | 5 |
| 13 | `com.graduation.analytics.OdsV2EdgeCaseSpec` | 5 |
| 14 | `com.graduation.analytics.JobArgsRegistrySpec` | 3 |
| 15 | `org.scalatest.tools.DiscoverySuite-045c6261-…` | 0（发现套件，非用例） |
| | **XML 逐套件求和** | **111** |

**JDK 选用理由（实测依据，非偏好）**：`spark-jobs/pom.xml` 的 JDK8 字节码闸门唯一所有者是 `scala-maven-plugin` 的 `<release>8</release>`（L90），且 `maven.compiler.source/target=8`（L18-19）；本模块在 `spark-jobs` 内以 **JDK 1.8.0_202** 运行。跑后报告内实测 `java.home=D:\Develop\JDK1.8\jre`、`java.version=1.8.0_202`、`java.class.version=52.0`，与既存主树可比证据同一 JDK，故两者可直接对照。
**JDK17 下的表现未取证**（§6）。

### 2.5 G4 来源陷阱（三要素必须同时声明）

`100 / 14` 与 `111 / 15` 这两个历史读数的**证据目录日期 ≠ 跑动日期 ≠ 工作树**，三者互不相同：

| 读数 | 证据文件（**目录日期**） | 日志内 `Finished at`（**跑动日期**） | `java.class.path` 指向（**工作树**） |
|---|---|---|---|
| **100 tests / 14 suites** | `docs/acceptance/m3-jdk8fix-evidence-20260914/verify/test-run-e2.log` | `2026-09-12T16:12:17+08:00` | `D:\Develop_code\GraduationProject-wt\m3-jdk8fix\spark-jobs\...`（**附属工作树**） |
| **111 tests / 15 suites** | `docs/acceptance/m3-step8-parity-20260912/raw/post/p2-03-m-e2-tests.log` | `2026-09-12T21:15:55+08:00` | 主树 `D:\Develop_code\GraduationProject\spark-jobs\...` |
| **111 tests / 15 suites** | `spark-jobs/target/surefire-reports/`（被 G4 跑前清理，快照见 `raw/g4-00-preRun-stale-spark-surefire-snapshot.txt`） | `2026-09-14T12:21:38` | 主树（**由另一条线跑出，非本次**） |
| **111 tests / 15 suites** | `raw/g4-01-...log` + `raw/g4-surefire/` | `2026-09-14T17:02:45+08:00` | 主树，HEAD `064b733`（**本次实测**） |

结论：`100/14` 是**附属工作树 `GraduationProject-wt\m3-jdk8fix`** 在 09-12 的读数，**不可当作当前主树值**；当前主树 `spark-jobs/src/test/scala` 有 16 个文件（14 个 Spec + 2 个 support），本次实测为 **111 / 15**。附属工作树按纪律**只读、未删除未修改**。

---

## 3. 门禁硬失败证据

### 3.1 `SparkStageExecutorSmokeIT`：真实门禁拒绝（点名执行，无隔离配置，退出码 1）

```
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0 ... -- in ...SparkStageExecutorSmokeIT
com.graduation.analytics.testsupport.TestIsolationGuard$MissingConfigurationException:
  [...realSparkOdlLoadsGoldenDataset] 真实 Spark 冒烟默认关闭：未提供 -Dv25.spark.it=true。
  本用例会启动真实 spark-submit（外部进程）并递归删除工作目录，必须显式声明后才允许运行（不提供 skip 形态的通过）。
	at com.graduation.analytics.testsupport.SparkItGuard.requireEnabled(SparkItGuard.java:79)
	at com.graduation.analytics.warehouse.pipeline.spark.SparkStageExecutorSmokeIT.<clinit>(SparkStageExecutorSmokeIT.java:74)
```
UTF-8 权威文本：`raw/g1b-06-sparkit-utf8-error.txt`（控制台重定向对中文为乱码，故从 surefire `.txt` 报告转存）。

### 3.2 【新】门禁自身在 MySQL 8.0 上必败：`SELECT @@version_major`

`TestIsolationGuard.verifyBeforeWrite`（L506-543）在写前校验中执行：

```java
int major = scalarInt(connection, "SELECT @@version_major");   // L511
```

**实测（3307，直接查询）**：
```
mysql> SELECT @@version_major;
ERROR 1193 (HY000): Unknown system variable 'version_major'
```
`@@version_major` 并非 MySQL 8.0 的系统变量。后果（G2-01 实测，退出码 1）：

```
java.lang.IllegalStateException: 写前校验无法完成（连接/查询失败）：Unknown system variable 'version_major'
	at com.graduation.analytics.testsupport.TestIsolationGuard.verifyBeforeWrite(TestIsolationGuard.java:543)
	at com.graduation.analytics.metric.MetricAdsMySqlIT.setUp(MetricAdsMySqlIT.java:104)
```
`MetricPublisherMySqlIT` 同因失败（`MetricPublisherMySqlIT.setUp:102`）。
`raw/g2-surefire-utf8/g2-01-MetricAdsMySqlIT.report.txt`、`...MetricPublisherMySqlIT.report.txt`。

- 分类：**门禁拒绝（门禁自身缺陷）**——门禁无法在本实例上完成，写入型 IT 连一次断言都到不了。
- 重要边界：该异常发生在 **L511**，**早于** `collectPrivileges`（L531）与 `fingerprintMatches`（L534）。因此**不能**据此断言权限/指纹检查的通过与否（见 §6）。
- 旁证（同时证明 §4 的 fork 问题）：异常类型是 `IllegalStateException` 而非连接/认证异常，说明 **JDBC 连接已成功建立**——即 `-Dv25.it.mysql.host=127.0.0.1:3307` 与新建账号口令**确实到达了 fork 出的测试 JVM 并被使用**。

### 3.3 `SourceRegistryMigrationMySqlIT`：类自身缺陷，任何配置都必败

```
com.graduation.analytics.testsupport.TestIsolationGuard$IsolationViolationException: metaDb 与 metricDb 不得是同一个库：v26it_20260914_1700_meta
	at com.graduation.analytics.testsupport.TestIsolationGuard$TestRunContext.<init>(TestIsolationGuard.java:395)
	at com.graduation.analytics.source.SourceRegistryMigrationMySqlIT.context(SourceRegistryMigrationMySqlIT.java:138)
	at com.graduation.analytics.source.SourceRegistryMigrationMySqlIT.verifyBeforeWrite(SourceRegistryMigrationMySqlIT.java:126)
```
静态复核（与实测一致）：该 IT 的 `context()`（L130-139）把 `META_DB, META_DB` 作为 `metaDb` 与 `metricDb` 传入，而 `TestRunContext` 的紧凑构造器（L395）显式拒绝两者相等。
→ 这是**代码缺陷**，不是环境缺失：**该 IT 在当前代码下不可能通过**，与 3307/3306 无关。
另有**静态发现（未实测到，因先被上述异常挡住）**：`EXPECTED_META_SCRIPTS`（L148-165）只登记到 `V18`，而 `db/meta/` 实际存在 `V19`、`V20`，即使修好 `context()`，迁移脚本清单断言也会因多出 2 个脚本而失败。**该后续失败未取证**（§6）。

### 3.4 G3 隔离档：门禁拒绝，且**不是**环境缺失

`run-isolated-tests.ps1` 6/6 门禁通过后，两个模块套件全红，根因是**同一份隔离登记指纹被两种词汇表解释**：

```
（mall）
Caused by: com.graduation.mall.support.MallIsolationGuard$MallIsolationException:
  [flyway-before-migrate] 实例指纹不匹配：登记为 de8ebbea-aff4-11f1-8037-00155d5dba47，
  实际 hostname=dahaishui port=3307；两者不符，实例也被拒绝。
（generator）
Caused by: com.graduation.itguard.IsolationGuard$GuardViolation:
  [GeneratorMetaStoreTest.setUp] 实例指纹不匹配：登记为 de8ebbea-aff4-11f1-8037-00155d5dba47，实际 hostname=dahaishui port=3307…
Caused by: …GuardViolation: [spring-datasource:dataSource] 禁止 createDatabaseIfNotExist=true：不得用此参数建库。
```
> 注：`raw/g3-03-isolated/*.log` 为**控制台重定向**，中文显示为乱码；ASCII 锚点（异常类名、`de8ebbea-…`、`hostname=dahaishui port=3307`、`createDatabaseIfNotExist=true`）逐字可靠。

**根因（词汇表不对称，已源码复核）**：唯一入口 `scripts/run-isolated-tests.ps1`（L218-233）注入
`$env:IT_GUARD_SERVERFINGERPRINT = $InstanceUuid`（默认 `de8ebbea-aff4-11f1-8037-00155d5dba47`，即 **server_uuid**），但两个消费方对同一变量名的解释不同：

| 消费方 | 代码位置 | 接受形态 | 是否接受 `server_uuid` |
|---|---|---|---|
| `analytics-server` `TestIsolationGuard.fingerprintMatches(expected, serverUuid, hostname)` | platform-common `…/testsupport/TestIsolationGuard.java:689` | `server_uuid` **或** `hostname` **或** `hostname:...` | **接受** |
| `mall-simulator` `IsolationGuard.fingerprintMatches(expected, hostname, port)` | `mall-simulator/src/test/java/com/graduation/itguard/IsolationGuard.java:396` | `hostname` / `hostname:port` / 纯端口 / `127.0.0.1:port` / `localhost:port` | **不接受** |

→ **一个登记值无法同时满足两个消费方**；`$InstanceUuid` 恰好是 mall 侧唯一不认的形态。

### 3.5 G3-04 探针：仅改环境变量值即可让 30/30 mall 用例全绿（一等发现）

为把「环境缺失」与「门禁拒绝」切开，做了**只改注入值、不改代码/POM/runner** 的探针：
`IT_GUARD_SERVERFINGERPRINT: de8ebbea-… → 3307`，并为 generator 补 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` 指向 3307 的 `_generator` 库。

| 模块 | runner as-run（登记 server_uuid） | G3-04 探针（登记 `3307`） |
|---|---|---|
| mall-simulator | `Tests run: 30, Failures: 0, Errors: 30, Skipped: 0`，退出 1 | **`Tests run: 30, Failures: 0, Errors: 0, Skipped: 0`，退出 0** |
| synthetic-data-generator | `Tests run: 19, Failures: 5, Errors: 14, Skipped: 0`，退出 1 | `Tests run: 19, Failures: 0, Errors: 5, Skipped: 0`，退出 1 |

结论：**runner 注入的指纹值单独一项，就把 30/30 个本来可全绿的 mall 用例全部变红**——这是**门禁配置缺陷**，不是被测功能缺陷。
generator 的 14 个 Spring 冒烟 error（6×`createDatabaseIfNotExist=true` + 表不存在级联）也由环境注入缺失造成，补 `SPRING_DATASOURCE_*` 后**全部转绿**；残下 5 个 error 是 `GeneratorMetaStoreTest` 的
`Table 'v26it_20260914_1700_generator.generation_run' / 'generation_event_stat' / 'generator_target' / 'generator_plan' doesn't exist`——该路径下 Flyway 迁移从未被应用（**环境/夹具缺失，非断言失败**）。
证据：`raw/g3-04-fingerprint-probe/probe-summary.txt`、`probe-mall.log`、`probe-generator.log`。

### 3.6 G2-03b：`AnalysisGoldenMySqlIT` 的数据库硬编码导致不可指向隔离实例

修正键名后（`metric.read.user` / `meta.app.user`），数据源成功构造，推进到 meta 查询后失败：

```
org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection
	at …AnalysisGoldenMySqlIT.dictionaryFromMeta(AnalysisGoldenMySqlIT.java:259)
	at …AnalysisGoldenMySqlIT.setUp(AnalysisGoldenMySqlIT.java:113)
Caused by: java.sql.SQLSyntaxErrorException: Access denied for user 'v26it_20260914_1700_metaapp'@'%' to database 'analytics_meta'
```
根因：该类把库名写死为常量 `METRIC_DB="analytics_metric"`、`META_DB="analytics_meta"`（L95-96），只有 `mysql.host` 可被 `-Dv25.it.mysql.host` 覆盖（L332 注释自陈：「库名不是凭据，且本类必须读正式库里的历史快照 `S20260901_24`」）。
而 3307 上：`analytics_meta` 为**空库**（`SHOW TABLES` 无输出），`analytics_metric` 只有一张 `__v25_w03_probe`、**无** `metric_snapshot`（实测 `SELECT snapshot_id FROM analytics_metric.metric_snapshot` → `ERROR 1146 Table 'analytics_metric.metric_snapshot' doesn't exist`）。
→ 分类：**BLOCKED / 环境性不满足（需要只在 3306 才有的历史黄金数据）**，并叠加「库名硬编码，无法重定向到隔离库」的结构限制。**按纪律绝未把该 IT 指向 3306。**

---

## 4. 静态测算 vs 实际执行差异解释

### 4.1 `analytics-server` 默认档：`591` 静态 `@Test` → `624` 实测，逐模块**完全对齐**

| 模块 | 静态 `@Test` | 减去「不被默认拾取的 IT 的 `@Test`」 | 小计 | 参数化/动态扩增 | 实测 |
|---|---|---|---|---|---|
| platform-common | 59 | 0 | 59 | **+22** | 81 |
| connection-ingestion | 150 | 0 | 150 | **+6** | 156 |
| warehouse-pipeline | 111 | −1（`SparkStageExecutorSmokeIT`） | 110 | **+24** | 134 |
| metric-analysis | 59 | −11（`MetricAds` 2 + `MetricPublisher` 3 + `AnalysisGolden` 6） | 48 | 0 | 48 |
| ai-decision | 91 | 0 | 91 | 0 | 91 |
| platform-app | 121 | −7（`SourceRegistryMigrationMySqlIT`） | 114 | 0 | 114 |
| **合计** | **591** | **−19** | **572** | **+52** | **624** |

- **减项 −19**：全部来自 5 个 `*IT.java`。surefire 默认 include 仅 `**/Test*.java`、`**/Test.java`、`**/*Tests.java`、`**/*TestCase.java`，**`*IT.java` 一个都不匹配**。实测：G1 共 72 个类打出 `[INFO] Running`，5 个 `*IT` 类出现 **0** 次。
- **加项 +52**：来自 `@ParameterizedTest`（connection-ingestion 1、warehouse-pipeline 2）与 `@TestFactory`（platform-common 1）的动态用例扩增；三个模块之外扩增为 0。
- 静态 glob 命中 74 个文件、实际 72 个打出 `Running`，差额 2 为 `TestIsolationGuard.java`、`TestRunDigest.java`：它们匹配 `Test*.java`，但类内无测试方法，surefire 不产生用例也不报错。
- 全反应堆 `@Disabled` 总数 = **0**（实测），故默认档无「静默禁用」项。

### 4.2 【必须分开写的两条结论】

控制方 G1 更正意见要求把两件事**严格分开**，本报告照办：

1. **`*IT.java` 未被默认拾取** —— 这是 **surefire include 模式**问题：5 个 IT 类在默认档**根本没进入执行计划**（72 个 `Running` 中 0 次）。属于「**没跑**」。
2. **门禁拒绝** —— 这是**执行后**被门禁/前置条件挡下：`SparkStageExecutorSmokeIT` 抛 `MissingConfigurationException` 退出 1；另外 4 个 IT 被 `@EnabledIfSystemProperty`（`AnalysisGolden`、`SourceRegistry`）或 `@ExtendWith(IsolationProfileCondition.class)`（`MetricAds`、`MetricPublisher`）判为 **disabled → 记为 Skipped 且退出码 0**。属于「**跑了／被跳过**」。

由此得到一条独立于「没跑」的严重形态：**点名执行这 4 个 IT 时，构建是绿的（`BUILD SUCCESS`、退出码 0），但执行用例数为 0**（`Skipped: 7 / 2 / 3 / 6`）。即
`IsolationProfileCondition.evaluateExecutionCondition` 只把 `MissingConfigurationException` 转成 `disabled(...)`，于是**未配置隔离 = 静默跳过**，与「测试通过」在退出码上不可区分。这必须与 IT 缺失 include 分开记账。

### 4.3 K-08「伪装计数」的精确机制（G4 实测复现）

- `com.graduation.analytics.MetricAdsSpecTest` 是 **ScalaTest 规格**，但其类名以 `Test` 结尾 → 同时匹配 surefire 默认 include `**/*Test.java`。
- 于是 surefire 把它当 JUnit 类跑一遍、**一个用例也没找到**，生成：
  `com.graduation.analytics.MetricAdsSpecTest.txt : Tests run: 0, Failures: 0, Errors: 0, Skipped: 0 … -- in com.graduation.analytics.MetricAdsSpecTest`
- 而同一套件由 ScalaTest runner 实际执行了 **5** 个用例（`TEST-com.graduation.analytics.MetricAdsSpecTest.xml` 的 `tests=5`）。
- 真实总数只出现在 `TestSuite.txt` 的 `Total number of tests run: 111` 与 14 个 `TEST-*.xml` 求和中。
- 本次实测确认：`spark-jobs/target/surefire-reports/` 中唯一含 `Tests run:` 字样的文件是上述**误导性** `MetricAdsSpecTest.txt`（值 0）与 `TestSuite.txt`（值 111，但格式为 `Total number of tests run:`）。
  → **任何以 `Tests run:` 行汇总 spark-jobs 真实测试数的做法都会得到 0 或漏算**；必须以 `TestSuite.txt` 的 `Total number of tests run:` + 逐套件 XML 为准。

---

## 5. 分级结论

| 等级 | 判定 | 依据（均为实测） |
|---|---|---|
| **提交完成** | ✅ **是** | `REPORT.md` 与 `raw/` 全部证据落盘；`git status` 仅本证据目录为新增；基线 HEAD 未被改写。 |
| **测试通过** | ⚠️ **仅默认档通过，隔离档不通过** | 默认档四棵树全绿：`analytics-server` **624**、`mall-simulator` **8**、`synthetic-data-generator` **101**、`spark-jobs` **111**，退出码均 0。隔离档：`run-isolated-tests.ps1` 退出 **7**，49 个 `@Tag("it")` 用例 0 通过。 |
| **限定验收** | ✅ **可给出** | 限定范围为：默认档测试数结论（§2.1/2.3/2.4）+ §4 的静态-实测对齐 + §3 的门禁失败根因定位 + §6 的未取证清单。 |
| **完整验收** | ❌ **不通过** | 五条硬理由：① 5 个 `*IT.java` 在默认档 0 执行（include 缺口）；② 4 个 IT 点名执行时**绿构建、零执行**（静默 skip），1 个硬失败；③ 隔离档 runner 退出 7，根因是**门禁指纹词汇表不对称**导致 30/30 mall 用例假红；④ 写入型 IT 被门禁自身缺陷 `@@version_major` 卡死，**0 个真实断言被执行**；⑤ `SourceRegistryMigrationMySqlIT` 存在**结构性必败**缺陷，与配置无关。 |

**给控制方的三条改动建议（均属「改动建议 / 未取证」，本报告未实施、未改任何 POM/代码）**：
1. surefire/failsafe 增补 `*IT.java` 的 include 或引入 failsafe（当前 7 个 POM 内 surefire 配置命中数为 **0**）。
2. 统一隔离指纹登记语义：让 `run-isolated-tests.ps1` 同时可满足 `server_uuid`（analytics-server 侧）与 `hostname:port`（mall 侧），或让 mall 侧 `IsolationGuard.fingerprintMatches` 接受 `server_uuid`。
3. 修复 `TestIsolationGuard` L511 的 `@@version_major`（改为 `SELECT VERSION()` 解析，或对未知变量做降级），并修正 `SourceRegistryMigrationMySqlIT.context()` 的 `metaDb/metricDb` 同值传参。

---

## 6. 未取证 / BLOCKED 清单

### 6.1 BLOCKED（有明确阻断条件）

| 项 | 阻断条件（实测） | 分类 |
|---|---|---|
| `AnalysisGoldenMySqlIT` 的 6 个黄金断言 | 需正式库历史快照 `S20260901_24`；3307 上 `analytics_metric` 无 `metric_snapshot`，`analytics_meta` 为空库；且库名硬编码不可重定向 | 环境性不满足（需要只在 3306 才有的历史数据） |
| `SparkStageExecutorSmokeIT` | 需 `-Dv25.spark.it=true` 且会真实 `spark-submit` + 递归删除工作目录；门禁按设计拒绝 | 门禁拒绝（未尝试开启） |
| `GeneratorMetaStoreTest` 5 个用例 | `Table 'v26it_20260914_1700_generator.generation_run' / 'generation_event_stat' / 'generator_target' / 'generator_plan' doesn't exist`——该路径 Flyway 未应用 | 环境/夹具缺失 |
| 写入型 IT 的**全部真实断言** | 被 §3.2 的 `@@version_major` 挡在 `setUp`，**0 个 `@Test` 体被执行** | 门禁自身缺陷 |

### 6.2 未取证（未实测，禁止当作结论）

| 项 | 为何未取证 |
|---|---|
| **`GRANT USAGE ON *.*` 是否真会被 `collectPrivileges` 判为「全局非只读权限」而拒绝** | 实测 3307 上每个账号的 `SHOW GRANTS` 首行均为 `GRANT USAGE ON *.* TO …`，而 `collectPrivileges`（L553/L591-594）把 schema 为 `*` 的非只读权限视为违规。**但执行在 L511 即中断，从未到达 L531**，故该拒绝**未被观测到**。仅登记为「静态推断，未取证」。 |
| `runtime_profile` 应落在 `metricDb` 还是 `metaDb` 的实测后果 | 同上，未执行到任何 DML。静态事实：`db/metric` V1-V3 内**无** `runtime_profile`；其所有者是 `db/meta/V7`；但 `MetricAdsMySqlIT.registerOwnProfile()`(/L184) 与 `cleanup()` 在 **metricDb** 上写入/删除 `runtime_profile`。冲突是否真实发生**未取证**。 |
| 3306 的任何状态（含 `SELECT @@version_major` 是否可用、before/after 指纹） | 铁律禁止接入 3306；属控制方职责 |
| `spark-jobs` 在 **JDK17** 下的测试数 | 本次仅以 JDK8 实测（§2.4 已给理由） |
| `spark-jobs` 附属工作树 `GraduationProject-wt\m3-jdk8fix` 的当前值 | 按纪律只读，未重跑 |
| `*IT.java` include 缺口、指纹语义、`@@version_major` 的**修复效果** | 属他线改动；本报告只给建议，未改 POM/代码 |
| `mall-simulator`/`synthetic-data-generator` 在**默认档**下 8 / 101 之外是否还有未覆盖用例 | 默认档实测数与静态预测一致（38−30=8、120−19=101），无差额需解释 |
| `TestRunDigest` / `TestIsolationGuard` 这两个「匹配但零用例」类的意图 | 未追查其设计用途 |

### 6.3 未覆盖的测试 / 场景（明确列出）

1. **5 个 `*IT.java` 的真实业务断言：0 个通过、0 个执行**（`MetricAdsMySqlIT` 2、`MetricPublisherMySqlIT` 3、`AnalysisGoldenMySqlIT` 6、`SourceRegistryMigrationMySqlIT` 7、`SparkStageExecutorSmokeIT` 1 共 19 个用例）。
2. 默认档下这 5 个类**完全未进入执行计划**（不在 624 之内）。
3. `synthetic-data-generator` 隔离档 19 个用例中 **19 个未通过**（as-run：5F+14E；探针：5E）。
4. `mall-simulator` 隔离档在 runner 下 **30 个全部未通过**（探针证明其中 30 个本可全绿）。
5. `TestIsolationGuard.verifyBeforeWrite` 的**后半段**（库名一致性 L516、禁止账号 L524、权限收集 L531、指纹比对 L534、`LiveFacts`）在写入型 IT 上**从未被执行**。
6. `MetricPublisherMySqlIT.productionDatabaseIsRejectedBeforeAnyWrite` 这一**负向用例**（本应证明「正式库在写入前被拒绝」）**未取证**——它正是被 §3.2 挡住的断言之一。
7. `spark-jobs` 中受 `SparkItGuard` 管控的真实 spark-submit 场景**未取证**。

---

## 7. 新增文件完整路径清单

新增文件全部位于 `docs/acceptance/v26-real-testcounts-20260914/`（本目录此前不存在，`git status` 显示为唯一未跟踪项）。以下均为**仓库相对路径**。

### 7.1 本报告
- `docs/acceptance/v26-real-testcounts-20260914/REPORT.md`

### 7.2 控制方基线（**只读，本次未修改**）
- `docs/acceptance/v26-real-testcounts-20260914/scripts/00-3306-readonly-fingerprint.sql`
- `docs/acceptance/v26-real-testcounts-20260914/raw/00-3306-before-fingerprint.txt`

### 7.3 G1 / G1b
- `docs/acceptance/v26-real-testcounts-20260914/raw/g1-00-preturn-surefire-inventory.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g1-01-default-reactor-test.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g1-02-surefire-module-lines.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g1-03-safety-jdbc-port-grep.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g1-04-running-classes-and-it-absence.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g1b-00-gate-hardfailure-proof.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g1b-01-platform-app-SourceRegistryMigrationMySqlIT.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g1b-02-metric-analysis-MetricAdsMySqlIT.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g1b-03-warehouse-pipeline-SparkStageExecutorSmokeIT.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g1b-04-metric-analysis-MetricPublisherMySqlIT.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g1b-05-metric-analysis-AnalysisGoldenMySqlIT.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g1b-06-sparkit-utf8-error.txt`

### 7.4 G2（隔离档 analytics IT）
- `docs/acceptance/v26-real-testcounts-20260914/raw/g2-00-summary.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g2-01-metric-analysis-ITs.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g2-02-platform-app-SourceRegistryMigrationMySqlIT.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g2-03-metric-analysis-AnalysisGoldenMySqlIT.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g2-03b-metric-analysis-AnalysisGoldenMySqlIT-correctedKeys.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g2-01-metric-analysis-ITs-MANGLEDARGS.log`（§1.2 无效运行留档）
- `docs/acceptance/v26-real-testcounts-20260914/raw/g2-02-platform-app-SourceRegistryMigrationMySqlIT-MANGLEDARGS.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g2-03-metric-analysis-AnalysisGoldenMySqlIT-MANGLEDARGS.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g2-surefire-utf8/g2-01-MetricAdsMySqlIT.report.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g2-surefire-utf8/g2-01-MetricPublisherMySqlIT.report.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g2-surefire-utf8/g2-02-SourceRegistryMigrationMySqlIT.report.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g2-surefire-utf8/g2-03b-AnalysisGoldenMySqlIT.report.txt`

### 7.5 G3（隔离档 mall / generator）
- `docs/acceptance/v26-real-testcounts-20260914/raw/g3-01-default-mall-simulator.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g3-01-default-synthetic-data-generator.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g3-02-prepare-isolation.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g3-03-run-isolated-tests.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g3-03-isolated/isolated-mall.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g3-03-isolated/isolated-generator.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g3-04-fingerprint-probe/probe-summary.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g3-04-fingerprint-probe/probe-mall.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g3-04-fingerprint-probe/probe-generator.log`

### 7.6 G4（spark-jobs 当前主树）
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-00-preRun-stale-spark-surefire-snapshot.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-01-runmeta.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-01-spark-jobs-main-tree-tests.log`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TestSuite.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/com.graduation.analytics.MetricAdsSpecTest.txt`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.algorithm.FunnelHeatAnomalySpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.algorithm.QuartileStatsRfmSpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.IdCodecSpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.JobArgsRegistrySpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.MetricAdsSpecTest.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.OdsV2ByteFidelitySpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.OdsV2EdgeCaseSpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.OdsV2SchemaOwnerSpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.OdsV2SqlContractSpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.OrderTradeCompilerSpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.sql.JsonObjectSlicerSpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.sql.SurrogateKeySpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.SqlTemplateSpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-com.graduation.analytics.WarehouseNamespaceSpec.xml`
- `docs/acceptance/v26-real-testcounts-20260914/raw/g4-surefire/TEST-org.scalatest.tools.DiscoverySuite-045c6261-435d-4cbf-954e-0601e6394c07.xml`

---

## 附录 A：3307 对象的新建、使用与清理（已收尾）

**本次新建（仅存在于 3307）**

| 用途 | 对象 | 说明 |
|---|---|---|
| G2 | 库 `v26it_20260914_1700_metric` | 已应用 `db/metric` V1-V3，并按 `db/meta/V7` 的 `runtime_profile` DDL 单独建表（**仅为消除「表不存在」这一干扰项**，不影响任何结论） |
| G2 | 库 `v26it_20260914_1700_meta` | 置空，供 Flyway 自行迁移（该 IT 从未推进到 migrate，故至今为空） |
| G2 | 账号 `…_metricapp`（`_metric` 全权）、`…_metricro`（`_metric` SELECT）、`…_metaapp`（`_meta` 全权） | 最小权限、非 root、非正式账号 |
| G3 | 库 `…_mall`、`…_generator`；账号 `…_mallapp`、`…_genapp` | 由 `scripts/it-prepare-isolation.ps1 -RunId v26it_20260914_1700` 幂等创建（`raw/g3-02-prepare-isolation.log`，退出码 0） |

口令均为随机 24 字符，仅存放于 `%TEMP%`（`v26it-g2cred-…/g2-credentials.properties`、`v26it-cred-…/credref-…-{mall,generator}.properties`），**未写入仓库、未写入本报告**。

**收尾清理（实测完成）**

- 已删除上述**全部 5 个库与 5 个账号**，并 `FLUSH PRIVILEGES`。
- 收尾实测：`SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'v26it%'` → **0**；`SELECT COUNT(*) FROM mysql.user WHERE user LIKE 'v26it%'` → **0**。
- 3307 现有库仅剩既存项，**未做任何修改**：`analytics_meta`、`analytics_metric`、`analytics_meta_f88v20probe_20260914_1632`、`analytics_meta_v25f88_20260914_1624`、`analytics_meta_v25it_20260914_1358_l4e3`、`analytics_metric_v25f88_20260914_1624`、`analytics_metric_v25it_20260914_1358_l4e3`、`mall_simulator` 及系统库。
- **再跑须知**：清理后若要复现隔离档，必须先执行
  `pwsh -File scripts/it-prepare-isolation.ps1 -RunId v26it_20260914_1700`
  （幂等），否则 `run-isolated-tests.ps1` 会在其「库存在」门禁处失败。

**未清理项（有意保留，且非本次产生）**

- 仓库根 `generator-output/`（7 个文件）与 `synthetic-data-generator/generator-output/`（148 个文件）**不是本次产物**：其内 run id 为 `m1-4-*` / `cli-smoke-*`，时间戳为 **09-11 ~ 09-12**，早于本次（09-14 16:54）两天，属其他线的证据产物；两者均被 `.gitignore` 覆盖。按「不销毁他线证据」原则**未删除**。

## 附录 B：本次未能使 `analytics-server/integration.local.properties` 出现

G2 全程按控制方要求**只用 `-D` 系统属性**，运行前后 `Test-Path analytics-server\integration.local.properties` 均为 **`False`**；G2 结束后 `git status --porcelain` 仍只有本证据目录。即「以系统属性驱动隔离配置」这条路径已实测可用（§3.2 的连接成功即为证据），无需也不应落盘该档案。
