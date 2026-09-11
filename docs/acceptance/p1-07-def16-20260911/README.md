# P1-07 证据：消除 DEF-16，测试源码定位收归 `RepoRoot`，E2 收敛为单条命令

- 日期：2026-09-11 19:1x–19:3x
- 任务：看板 `P1-07`（依赖 `P1-04`）；缺陷：`docs/开发过程事实与决策记录.md` **DEF-16**
- 范围：**只动测试基础设施与构建配置**（4 个 pom 的依赖声明 + 5 个测试类的定位方式）。未改任何生产逻辑、未改断言口径、未放宽任何守卫、未碰数据库与数仓数据。

## 1. 缺陷与根因（本轮**实测**机制，不是推断）

DEF-16 的现象：`-DforkCount=0`（本机内存受限时唯一跑得动的配置）下，`AnalysisSourcePolicyTest` 3 个用例
`NoSuchFileException: src\main\java\com\graduation\analytics\analysis`、`PlatformMallBoundarySourcePolicyTest`
3 个用例「平台 Java 生产源码必须存在（守卫不可空跑）」失败；同一份代码 fork 跑全绿。

用一个临时探针用例（跑完即删，未提交）在 `warehouse-pipeline` 里打印三类"当前目录"观测量，实测结果：

| 观测量 | `-DforkCount=0`（进程内） | `-DforkCount=1`（fork） |
|---|---|---|
| `System.getProperty("user.dir")` | `…\analytics-server\warehouse-pipeline`（模块目录） | `…\analytics-server\warehouse-pipeline`（模块目录） |
| `Paths.get("").toAbsolutePath()` / `File(".")`（**原生 CWD**） | `D:\Develop_code\GraduationProject`（**启动 mvn 的目录＝仓库根**） | 模块目录 |
| `Files.exists(Path.of("pom.xml"))` | `false` | `true` |
| `Files.exists(Path.of("..","..","spark-jobs","pom.xml"))` | **`false`** | `true` |

结论（精确到 API 行为）：

1. surefire **两种模式下都会把 `user.dir` 系统属性设成模块 basedir**，但**只有 fork 模式会真的切换进程工作目录**；`-DforkCount=0` 时进程 CWD＝启动 `mvn` 的目录。
2. 因此**相对路径的 `java.nio` 文件操作**（`Files.list` / `Files.exists` 等，直接交给原生调用）在进程内模式下按**仓库根**解析 → 落空；而 `Path.of(System.getProperty("user.dir"), …)` 这类**绝对路径拼接**两种模式都对（`AuthResponseMeasurementTest` 的落盘文件因此不受影响，本轮未改）。
3. 这也解释了 `warehouse-pipeline` 的跨语言契约锁 `EventContractTest.odsRoutingMatchesScalaLoadSql`：旧写法是相对路径 + `Assumptions.assumeTrue(Files.exists(...))`，上表第 4 行实测 `false` ⇒ **进程内模式下这条锁被静默跳过**——一个看起来全绿的假信号（守卫没报错，但根本没跑）。

## 2. 改了什么（唯一所有者：`RepoRoot`）

| 文件 | 改动 |
|---|---|
| `analytics-server/platform-common/pom.xml` | 新增 `maven-jar-plugin` 的 `test-jar` 执行：把测试工具（`com.graduation.analytics.testsupport.RepoRoot`）发布给其它模块复用 |
| `analytics-server/{metric-analysis,ai-decision,warehouse-pipeline,platform-app}/pom.xml` | 各加一条 `platform-common` **`test-jar`**（`scope=test`）依赖 |
| `metric-analysis/…/analysis/AnalysisSourcePolicyTest.java` | `Path.of("src",…)` → `RepoRoot.path("analytics-server/metric-analysis/src/main/java/…/analysis")` |
| `platform-app/…/boundary/PlatformMallBoundarySourcePolicyTest.java` | `Path.of("..")` → `RepoRoot.path("analytics-server")` |
| `warehouse-pipeline/…/contracts/EventContractTest.java` | 相对路径 + `assumeTrue(存在)` → `RepoRoot.path("spark-jobs/src/main/scala/…/OdsLoadSql.scala")` + **文件必须存在的硬断言**（消除静默跳过） |
| `ai-decision/…/ai/AiSqlDriftTest.java` | `resolveDdlDir()` 的"两个候选路径依次试" → `RepoRoot.path("analytics-server/platform-app/src/main/resources/db/metric")`（删掉手写的"我在哪个工作目录"判断） |
| `platform-common/…/contracts/CanonicalEventSchemaParityTest.java` | 删除本类私有的第三份"向上找仓库根"实现，改用同模块的 `RepoRoot` |

仓库内"找仓库根"从此只有一处 Java 实现 + 一处 Scala 实现（`spark-jobs` 的 `WarehouseNamespaceSpec`，跨语言测试无法共享 Java 测试工具，与"一份规格、两份薄适配器"同构，属已知且可接受的跨语言重复）。全仓扫描确认：`src/test` 下不再有任何 `Path.of(".."…)` / `Path.of("src"…)` / 多候选兜底式的源码定位。

## 3. 证据

### 3.1 E2 收敛为单条命令（本任务主证据）

```powershell
$env:MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djdk.attach.allowAttachSelf=true'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -XX:+UseSerialGC'
$env:SPARK_DRIVER_MEMORY='512m'
mvn -o test -f analytics-server/pom.xml -pl platform-app -am -DforkCount=0   # -pl platform-app -am ＝ 整反应堆（7 个工程）
```

`BUILD SUCCESS`，反应堆就是全部六模块，**不加 `-Dtest` 过滤、不排除任何测试类**：

| 模块 | 用例 | 失败 | 跳过 |
|---|---|---|---|
| platform-common | 36 | 0 | 0 |
| connection-ingestion | 67 | 0 | 0 |
| warehouse-pipeline | 102（含真实 Spark 冒烟 `SparkStageExecutorSmokeTest` **41.2 s**） | 0 | 0 |
| metric-analysis | 38 | 0 | 0 |
| ai-decision | 91 | 0 | 0 |
| platform-app | 52 | 0 | 0 |
| **合计** | **386** | **0** | **0** |

- 关键对照：改造前同样的 386 用例要**两条命令**（进程内跑模块 1–3、fork 跑模块 5–7），且进程内那条里
  `EventContractTest` 的跨语言契约锁是**被跳过**的（`Skipped ≥ 1`）；现在**一条命令、386 用例、0 跳过**。
- 原始日志（逐用例行 + 反应堆汇总）：`verify/e2-single-command-forkCount0.log`（48,925 B，sha256 `C2538591CFA243B04A6A4C530D2C437B67E397D5AE21FDC51F6CCCDC9F5CEDFA`）。
- 定向复跑（同样 `-DforkCount=0`，只跑受影响的守卫类）：`EventContractTest` 28/28、`AnalysisSourcePolicyTest` 3/3、
  `AiSqlDriftTest` 6/6、`PlatformMallBoundarySourcePolicyTest` 3/3、`WarehouseNameLiteralGateTest` 3/3，全绿。

### 3.2 E1 构建（新增的 test-jar 执行确实产出工件）

```
mvn -o -DskipTests package -f analytics-server/pom.xml -pl platform-common
[INFO] --- jar:3.3.0:jar (default-jar) @ platform-common ---
[INFO] Building jar: …\platform-common-0.1.0-SNAPSHOT.jar
[INFO] --- jar:3.3.0:test-jar (test-jar) @ platform-common ---
[INFO] Building jar: …\platform-common-0.1.0-SNAPSHOT-tests.jar     # 25,613 B / 19:22:17
[INFO] BUILD SUCCESS
```

解包核对：`test-jar` 内含 `com/graduation/analytics/testsupport/RepoRoot.class`（即四个模块测试期实际用的那份）。
只验证 `platform-common` 单模块打包：完整 fat jar 打包需先停掉 8091（Windows 文件锁），属 D-032 推迟的"重建平台 jar"事项，此处未做。

### 3.3 已知限制（实测，不是推测）

`test-jar` 依赖只在**反应堆内**（`-am` 或整反应堆）可解析。不带 `-am` 的单模块跑法实测**直接构建失败**（响亮报错，非静默）：

```
[ERROR] Failed to execute goal on project metric-analysis: Could not resolve dependencies …
[ERROR] dependency: com.graduation.analytics:platform-common:jar:tests:0.1.0-SNAPSHOT (test)
[ERROR] 	Could not find artifact com.graduation.analytics:platform-common:jar:tests:0.1.0-SNAPSHOT
```

即：单模块跑测试必须带 `-am`（本仓库口径本来就是"平台测试必须全反应堆"，见决策记录 §5）。替代方案是把 `RepoRoot`
挪进 `src/main`，但那会把测试工具打进生产 fat jar——不做。

### 3.4 未取证（诚实标注）

- 未在 CI/其它机器上复跑（本机 Windows + JDK17 + Maven 3.9.14 单点验证）。
- 未验证 IDE 内直接跑单类测试（`user.dir` 由 IDE 决定；`RepoRoot` 的"向上走"策略在模块目录与仓库根两种起点下都成立，但未实测）。
- `beeline --hivevar`、集群档、1,000,000 行档与本任务无关，仍未取证（见 P1-04 证据目录）。

## 4. 复核命令

```powershell
# 单条命令复跑全仓 E2（进程内，本机内存受限时的可用配置）
mvn -o test -f analytics-server/pom.xml -pl platform-app -am -DforkCount=0
# 只看受影响守卫（任意模块，注意必须带 -am）
mvn -o test -f analytics-server/pom.xml -pl platform-app -am -DforkCount=0 `
  '-Dtest=AnalysisSourcePolicyTest,PlatformMallBoundarySourcePolicyTest,EventContractTest,AiSqlDriftTest,WarehouseNameLiteralGateTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
```
