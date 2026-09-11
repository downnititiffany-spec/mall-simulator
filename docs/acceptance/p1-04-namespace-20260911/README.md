# P1-04 数仓库名空间（按源隔离）交付证据（2026-09-11）

> 任务：`项目实施进度与任务看板 V2.2.md` §3.4 **P1-04**（"库名前缀参数化"）。
> 冻结契约：`contract-specs/specs/warehouse-namespace.v1.json`（规则 + 22 条向量）。
> 证据脚本：本目录 `probe-namespace-prefix.ps1`（自检 **18/18 PASS**）；机器可读结果 `probe-summary.json`；目录清单 `probe-dir-facts.txt`。
> 状态：`REVIEW`。E1 + E2（模块自动化，**含真实 Spark 冒烟**）+ E3（本机真实链，隔离数仓）已取证，全仓 **386 用例 0 失败**（两条命令，均不加 `-Dtest` 过滤）；早前两次冒烟超时经实测判为**本机提交内存**环境问题，换配置后复跑通过，全过程见 §5。

---

## 0. 一句话结论

数仓库名（`ods/dwd/dim/dws/ads` 五个库）从"代码里到处硬编码 `dw_*`"收归**唯一所有者**：库名前缀来自 `runtime_profile.hive_database_prefix`，规则冻结在契约里，Java（`platform-common`）与 Scala（`spark-jobs`）两份实现**同为一薄适配器**、由同一组向量驱动。

在**隔离的临时数仓 + 临时 Derby 元数据库**里真跑 spark-submit（不是 Mock）：

1. 同一套作业带前缀 `dw_b` → 实建 `dw_b_ods / dw_b_dwd / dw_b_dim / dw_b_dws / dw_b_ads`，`dw_b_ods` 下 21 个 `part-*.parquet`，**且不建 `dw_ods`**（两个源的库名互不干扰）；
2. 不传前缀（= 源 A 存量档，`hive_database_prefix` 为 `NULL`）→ 实建 `dw_ods / dw_dwd / dw_dim / dw_dws / dw_ads`，表清单与上面**逐字一致** ⇒ **零数据迁移**（既有 `dw_*` 数据一行都不用搬）；
3. 非法前缀（层后缀 `dw_ods`、大写 `DW`）→ 作业在**创建 SparkSession 之前**以退出码 **64** 失败、打印错误码，**一个库都不建**（fail-closed）。

## 1. 任务边界与取证方式

- **允许（已做）**：新增 `contract-specs/specs/warehouse-namespace.v1.json`、两端命名空间实现与门禁测试、`warehouse/ddl/*.sql` 变量化、探针脚本与本目录证据；更新进度看板。
- **隔离原则（已守）**：探针自带 `spark.sql.warehouse.dir` 与 Derby `ConnectionURL`，全部落在 `analytics-server/warehouse-pipeline/tests/r6-smoke-warehouse/p1-04-ns-probe/`（`.gitignore` 已忽略）。**未碰真实数仓 `spark-warehouse/`，未碰 `analytics_meta` / `analytics_metric` / `mall_simulator` 任何一行数据。**
- **只跑两个作业**：`sci`（建库建表）+ `odl`（落 ODS）。**未重跑全链路、未发布指标、未新增快照**，故 run 39 / 快照 `S20260901_39` 仍是唯一 ACTIVE 基线（P1-01）。
- 三个服务（8090/8091/8092）保持运行，探针不调用任何 HTTP 接口。

## 2. 冻结契约与唯一所有者

| 项 | 值 |
|---|---|
| 契约 | `contract-specs/specs/warehouse-namespace.v1.json`（`status=DRAFT-待总控冻结`，改动需总控出 v2，见 V2.2 §2.1.1） |
| 规则 | `库名 = <prefix>_<layer>`，`layer ∈ {ods,dwd,dim,dws,ads}`；前缀 `^[a-z][a-z0-9_]{0,23}$`；保留字 `default/sys/system/information_schema/hive_metastore`；**不做归一化** |
| 检查顺序 | `PATTERN → UNDERSCORE → RESERVED → LAYER_SUFFIX`（错误码 `WAREHOUSE_PREFIX_PATTERN/UNDERSCORE/RESERVED/LAYER_SUFFIX`） |
| 空值语义 | 仅 `NULL` / `""` 取缺省前缀 `dw`（`blankIsDefault=true`，不做 trim） |
| 事实来源 | `runtime_profile.hive_database_prefix`（P1 之前是死字段，本轮激活） |
| Java 所有者 | `analytics-server/platform-common/src/main/java/com/graduation/analytics/warehouse/WarehouseNamespace.java`（+ `WarehouseNamespaceProvider`） |
| Scala 所有者 | `spark-jobs/src/main/scala/com/graduation/analytics/warehouse/WarehouseNamespace.scala` |
| 注入点（平台→作业） | `analytics-server/warehouse-pipeline/.../spark/JobCommandBuilder.java`：`WarehouseNamespace.ofNullable(profile.hiveDatabasePrefix())` → `--hiveDatabasePrefix=<prefix>`；**校验发生在 spark-submit 之前** |
| 作业入口校验 | `spark-jobs/.../job/JobRunner.scala`：`WarehouseNamespace.parse(...)` 在 `SparkSessionFactory.create` **之前**，非法即 `exit(64)` |
| DDL | `warehouse/ddl/0{0..4}-*.sql` 库名全部写成 `${WAREHOUSE_PREFIX}_<层>`（63 处替换） |

**两侧一致性的取证方式**：契约里的 22 条向量由两端各自读同一份 JSON 驱动（`WarehouseNamespaceContractTest` 24 用例 / `WarehouseNamespaceSpec` 62 用例），**不靠人眼比对**。

## 3. E3 真实链证据（探针自检 18/18 PASS）

脚本：`probe-namespace-prefix.ps1`；jar：`spark-jobs-0.1.0-SNAPSHOT.jar`（2026-09-11 18:19:03，234,038 B，含 P1-04 改动）；Spark 3.5.1 local[2]；`--driver-memory 512m`（本机提交内存吃紧，贴 Spark 硬下限 450MB 之上，见 §5）。

| 场景 | 命令要点 | 实测观测（原始记录见 `probe-summary.json`） |
|---|---|---|
| A 非缺省前缀（第二个源） | `sci` + `odl`，`--hiveDatabasePrefix=dw_b` | 两作业 exit 0 / `status=SUCCESS`；`odl` 输入 **55** 行 → 输出 **52**（黄金数据集 52 通过 / 3 拒绝）；数仓实建 5 个库 `dw_b_ods,dw_b_dwd,dw_b_dim,dw_b_dws,dw_b_ads`；`dw_b_ods` = 4 张表 `ods_behavior_event/ods_product_event/ods_trade_event/ods_user_event`，分区 `dt=20260901,dt=20260902`，**21 个 `part-*.parquet`**；**无 `dw_ods` 库**；stderr `[spark-jobs] 数仓库名空间: dw_b_ods, dw_b_dwd, dw_b_dim, dw_b_dws, dw_b_ads` |
| B 非法前缀（fail-closed） | `sci`，`--hiveDatabasePrefix=dw_ods` / `DW` | 两次均 **exit 64**；错误码分别 `WAREHOUSE_PREFIX_LAYER_SUFFIX` / `WAREHOUSE_PREFIX_PATTERN`；stderr 无任何 Spark 会话日志；stdout **无 JobResult**；warehouse 目录下 **0 个库**（Spark 从未启动） |
| C 缺省前缀（源 A 存量档） | `sci` + `odl`，**不传** `--hiveDatabasePrefix` | 两作业 exit 0 / `SUCCESS`；实建 `dw_ods,dw_dwd,dw_dim,dw_dws,dw_ads`；`dw_ods` 表清单/分区/**21 个 parquet** 与场景 A **逐项一致**；⇒ 库名与改造前**逐字相同**，零迁移 |

`probe-dir-facts.txt` 是 A/C 两个场景的库-表-分区清单原文：两处除**库名前缀**外完全同构（各 5 库、4+3+2+7+16 张表）。

### 3.1 平台路径（profile → 命令行）的部分证据

`SparkStageExecutorSmokeTest` 的失败留在子进程崩溃转储 `hs_err_pid8280.log` 里的**命令行**可作证：`--runtimeProfileId=7 --jobCode=sci --businessDate=20260901 --attemptNo=1 --hiveDatabasePrefix=dw`——即平台侧 `JobCommandBuilder` 真的把 ACTIVE profile 的 `hive_database_prefix`（当前为 `NULL`）按契约取成缺省 `dw` 并注入命令行；同一子进程 stderr 已打印 `[spark-jobs] 数仓库名空间: dw_ods, dw_dwd, dw_dim, dw_dws, dw_ads`（命名空间校验通过），随后 JVM 因本机提交内存耗尽原生崩溃（§5）。（补充：该用例随后在 §4/§5 记录的两组配置下**都已通过**，命令行注入的结论不变。）

## 4. E1 / E2 证据

| 层 | 命令 | 结果 |
|---|---|---|
| E1 编译 | `mvn -o -f spark-jobs/pom.xml package -DskipTests` | BUILD SUCCESS；jar 234,038 B / 18:19:03（内 `warehouse/WarehouseNamespace*.class`） |
| E2 Java（模块 1–3，进程内） | `mvn -o test -f analytics-server/pom.xml -pl platform-common,connection-ingestion,warehouse-pipeline -DforkCount=0`（Maven 堆 896m，`SPARK_DRIVER_MEMORY=512m`） | platform-common **36/36**（含 `WarehouseNamespaceContractTest` 24 + `WarehouseNameLiteralGateTest` 3）、connection-ingestion **67/67**、warehouse-pipeline **102 用例 / 0 失败 / 0 错误**（**真实 Spark 冒烟 `SparkStageExecutorSmokeTest` 29.5 s 通过**；`JobCommandBuilderTest` **9/9**，其中 P1-04 新增 2 条）—— BUILD SUCCESS，01:00 min |
| E2 Java（模块 5–7，fork） | `mvn -o test -f analytics-server/pom.xml -pl metric-analysis,ai-decision,platform-app -am -DforkCount=1 -DreuseForks=true '-DargLine=-Xmx384m -XX:+UseSerialGC -Djdk.attach.allowAttachSelf=true'`（Maven 堆 640m） | warehouse-pipeline 102 + metric-analysis **38/38** + ai-decision **91/91** + platform-app **52/52**（含 `AnalysisSourcePolicyTest` **3/3**、`PlatformMallBoundarySourcePolicyTest` **3/3**）—— BUILD SUCCESS，54 s |
| E2 合计 | 上面两条**都不加 `-Dtest` 过滤、不排除任何测试类** | **386 用例 0 失败**。为何必须分两条命令（两个源码策略类用相对路径定位本模块源码，隐含"工作目录 = 模块 basedir"，该假设只在 surefire fork 时成立）见 `docs/开发过程事实与决策记录.md` **DEF-16**；smoke 需要进程内配置见 §5 |
| E2 Scala | `mvn -o -f spark-jobs/pom.xml test-compile scalatest:test` | `WarehouseNamespaceSpec` 等：**Suites 全绿 / Tests 62 / 0 失败**（含 22 条向量、29 个 producer 的跨前缀断言、`LocalSchemaInitJob.statements(ns)` 37 条 DDL） |
| 门禁（P1-04 DoD） | `WarehouseNameLiteralGateTest`（3 条） | ① `noBareWarehouseNameLiterals`：扫描 `spark-jobs/src/main/`、`analytics-server/<module>/src/main/`、`warehouse/ddl/`、`scripts/`（≥60 文件）→ 裸库名字面量 **0 命中**（仅 2 个所有者文件在白名单）；② 扫描范围自身合法性；③ `ddlDerivesDatabaseNamesFromSharedPrefix`：`warehouse/ddl/*.sql` 恰好 5 个、每个含 `${WAREHOUSE_PREFIX}_`、层集合 = `{ods,dwd,dim,dws,ads}`、变量集合 = `{WAREHOUSE_PREFIX}` |

## 5. 未通过 / 未取证项（诚实标注）

1. **`SparkStageExecutorSmokeTest`（warehouse-pipeline 唯一真实 Spark 自动化用例）：早前三次失败全部是"子 JVM 起不来/活不下去"的本机内存环境问题，已换配置复跑通过（非 P1-04 回归）。**
   - 失败形态（原始日志在案）：前两次 `os::commit_memory(...) failed; error='页面文件太小，无法完成操作' (DOS error/errno=1455)`、`Native memory allocation (mmap) failed to map 532676608 bytes for G1 virtual space`、`Could not reserve enough space for object heap`（`tests/r6-smoke-warehouse/logs/smoke-sci__lp-1789121322584-591def.log`、`…-189059.log`）；第三次 `-Xmx512m` 已生效仍 `Out of Memory Error (arena.cpp:189)` / "The system is out of physical RAM or swap space"（`hs_err_pid8280.log`，进程工作集 445 MB）。三次都**没有**断言层面的业务失败。
   - 本机实测提交内存：`Committed Bytes 36.65 GB / Commit Limit 38.36 GB`（余量 **1.70 GB**），大额占用来自用户 IDE（`idea64` ≈ 3.0 GB、`datagrip64` ≈ 1.9 GB 私有提交，不动）与三个常驻服务。**DEF-15 家族的同一环境问题**，登记为环境阻塞，不计入代码缺陷。
   - **实测可用的两组配置（都跑通了真实 Spark）**：① 进程内 —— `MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'` + `-DforkCount=0` + `SPARK_DRIVER_MEMORY=512m` → 冒烟 **29.5 s 通过**（§4 命令 A）；② fork —— `MAVEN_OPTS=-Xmx640m` + `-DforkCount=1 -DargLine='-Xmx384m …'` → 冒烟 **29.51 s 通过**（§4 命令 B 顺带跑）。反例（在案，供他人避坑）：`MAVEN_OPTS=-Xmx1280m`（Maven 自己太大）+ 默认 1g 驱动 → 必然超时；`-DforkCount=1 -DargLine='-Xmx384m'` 与 Maven 640m **同日 18:35 还失败过一次**（240.7 s 超时），18:46 同配置又通过 ⇒ 该档位**贴着内存余量、不稳定**，优先用配置 ①。
   - `SPARK_DRIVER_MEMORY` 被采纳的判别法：设 384m 时驱动报 `[INVALID_DRIVER_MEMORY] System memory 389283840 must be at least 471859200`（Spark 3.5 硬底线 450 MB），设 512m 即通过。
2. **`beeline --hivevar WAREHOUSE_PREFIX=...` 未实跑**：`warehouse/ddl/*.sql` 的 `${WAREHOUSE_PREFIX}` 替换只有 Hive 3.1.3 源码/JIRA 的读证（替换先于解析、引号无关、未定义变量保持字面量 ⇒ 漏传前缀会**报错而非静默建错库**），本机无 HiveServer2，故 `docs/deployment.md` §8 该步骤已标"本机尚未实跑"，留待 **M3/集群（E4/T4）**。
3. 1,000,000 行与集群档（E4/T4）未跑；本轮不涉集群参数。

## 6. 改动清单（本轮新增/修改）

- 契约：`contract-specs/specs/warehouse-namespace.v1.json`（新增）
- Java：`platform-common/.../warehouse/WarehouseNamespace.java`、`WarehouseNamespaceProvider.java`（新增）；`connection-ingestion/.../runtime/ActiveProfileWarehouseNamespaceProvider.java`（新增）；`warehouse-pipeline/.../spark/JobCommandBuilder.java`（改）；`ai-decision/.../ai/evidence/{MetricLineage,EvidenceBuilder}.java`（改，血缘按库名命名空间）
- Scala：`spark-jobs/.../warehouse/WarehouseNamespace.scala`（新增）；`sql/{OdsLoadSql,DimSql,DwsSql,AdsSql,DwdSql}.scala`、`metric/MetricAdsSpec.scala`、`job/*.scala`（11 个作业 + `PartitionEvidence`）、`job/JobRunner.scala`、`job/LocalSchemaInitJob.scala`（改）
- DDL/文档：`warehouse/ddl/0{0..4}-*.sql`（63 处）、`warehouse/README.md`（追加执行方式）、`docs/deployment.md` §8
- 测试：`WarehouseNamespaceContractTest`、`WarehouseNameLiteralGateTest`（1→3 条）、`WarehouseNamespaceSpec`、`JobCommandBuilderTest`（+2 条）、`EvidenceBuilderTest`、`SqlTemplateSpec`/`IdCodecSpec`/`MetricAdsSpecTest`（改）
- 证据：本目录（`README.md`、`probe-namespace-prefix.ps1`、`probe-summary.json`、`probe-dir-facts.txt`）

## 7. 复现命令

```powershell
# 1) 构建含 P1-04 的作业 jar
mvn -o -f spark-jobs/pom.xml package -DskipTests

# 2) 隔离数仓真实链探针（自动重跑 A/B/C 三场景并自检 18 项）
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -XX:+UseSerialGC'
pwsh -NoProfile -ExecutionPolicy Bypass -File docs/acceptance/p1-04-namespace-20260911/probe-namespace-prefix.ps1

# 3) 模块自动化（Java；两条命令都不加 -Dtest 过滤，合计 386 用例）
$env:SPARK_DRIVER_MEMORY='512m'   # 被 spark-submit 采纳，实测判别法见 §5
$env:MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djdk.attach.allowAttachSelf=true'
mvn -o test -f analytics-server/pom.xml -pl platform-common,connection-ingestion,warehouse-pipeline -DforkCount=0
$env:MAVEN_OPTS='-Xmx640m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djdk.attach.allowAttachSelf=true'
mvn -o test -f analytics-server/pom.xml -pl metric-analysis,ai-decision,platform-app -am -DforkCount=1 -DreuseForks=true '-DargLine=-Xmx384m -XX:+UseSerialGC -Djdk.attach.allowAttachSelf=true'

# 4) Scala 侧契约向量
mvn -o -f spark-jobs/pom.xml test-compile scalatest:test
```

## 8. 验收对照（P1-04 完成定义）

| 要求 | 取证 |
|---|---|
| 库名前缀可配、来源是 `runtime_profile.hive_database_prefix` | §2 注入点 + §3.1 子进程命令行实测 `--hiveDatabasePrefix=dw` |
| 不同源互不干扰（多商城接入的前提） | §3 场景 A：`dw_b_*` 实建、`dw_*` 不建 |
| 存量源行为不变（零迁移） | §3 场景 C：缺省 → `dw_*` 逐字一致；`hive_database_prefix=NULL` 的 ACTIVE profile 无需改数据 |
| 非法前缀不产生"半个数仓" | §3 场景 B：Spark 启动前 exit 64、0 个库 |
| 库名只有一处所有者（不再硬编码） | §4 门禁：主源码/DDL/脚本裸字面量 0 命中；DDL 只用 `${WAREHOUSE_PREFIX}` |
| 两端实现不分叉 | §2：同一契约 22 条向量驱动 Java 24 + Scala 62 用例 |
