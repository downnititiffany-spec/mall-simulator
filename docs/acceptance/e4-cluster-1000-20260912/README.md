# E4 验收：集群真实链 1,000 行（M3 集群前置 → 真实链）

> **结论必须分两段读**（不许合并成"通过了"）：
>
> ① **数据链 9/10 作业 SUCCESS**：1,000 行真实事件 → ODS 4 表 1,000 行 → DWD(3)/DIM(2) → DWS 7 表 → ADS 8 张暂存 → DQ 6 项检查全过 → 8 张**正式分区** 138 行；
> 两端都由**集群侧独立回读**核对（Hive 客户端直读，不经作业自报）：ODS 合计 **1,000**、ADS 正式分区合计 **138**。
> ② `mxp`（指标导出）**FAILED**，根因 **F-80**：jar 里被静默编入了 **Java 11** 的 `String.isBlank()`，而集群计算节点 JDK 是 **1.8.0_351** ⇒ `NoSuchMethodError`。
> 该缺陷已在**本地 JDK 8 上 2.1 秒复现**（§8），修复与集群重跑见 **§10（追加补记，以此为准）**。

| 项 | 值 |
|---|---|
| 证据级别 | **E4**（集群 1,000 行真实链） |
| 运行日 | 2026-09-12（15:49–16:0x） |
| 命名空间前缀 | `dw` ⇒ `dw_ods/dw_dwd/dw_dim/dw_dws/dw_ads` |
| 快照 | `S20260901E4`；业务日 `dt=20260901` |
| 集群 | Spark 3.5.1 / Scala 2.12.18，YARN 3.3.4，`defaultFS=hdfs://node01:8020`，`thrift://node01:9083`，**JDK 1.8.0_351** |
| 原始日志 | `raw/*.log`、`raw/*-yarn-logs.txt`、`raw/chain-summary.jsonl`、`raw/e4-appstatus.txt`、`raw/e4-readback-out.txt` |
| 提交脚本 | `tools/e4-run-chain.ps1`（本地 `.verify/e4-run-chain.ps1` 的归档副本） |

---

## 1. 本次要回答什么

| # | 问题 | 回答位置 |
|---|---|---|
| Q1 | 1,000 行真实事件能否经 Spark on YARN 落进 Hive ODS，且**行数可独立核对**？ | §2 §4 §5 |
| Q2 | 十作业链（ODS→DWD→DIM→DWS→ADS→DQ→发布→导出）能否在真集群跑通？ | §4 |
| Q3 | 发布（`pub`）是否真的把**正式分区**指向本次快照，而不是只写暂存？ | §4 §5 |
| Q4 | 有没有只在真集群 JDK 上才暴露的缺陷？ | §7 §8 |

---

## 2. 输入：生成器 → 文件 → HDFS

| 环节 | 读数 | 证据 |
|---|---|---|
| 计划 `plan_id=e4c1000` v1 | `mode=CANONICAL_EVENT_FILE`、`scenario=normal`、`seed=42`、`event_count=1000`、`2026-09-01 00:00:00`→`23:59:59` | `generator_meta.generation_plan` |
| 运行 `e4c1000-v1-20260912-154851-6e28` | `status=SUCCESS`，`1000/0`，checksum `A586080D…21A6D` | `generator_meta.generation_run` |
| 产物 `events-0001.jsonl` | **1,000 行 / 353,053 B**，sha256 **`A586080D97A83E56C50FB5C09BA9E6AB58934FE2D102A8015663CC4D78221A6D`**（= 生成器自报 checksum，两侧一致） | `generator-output/e4c1000-v1-20260912-154851-6e28/` |
| 信封 | 8 键 `event_id,event_type,event_time,ingest_time,source_system,schema_version,trace_id,payload`；全部 `source_system=mock-mall`、`schema_version=1.0`；`event_time` 全落在 2026-09-01 | 同上 |
| 事件分布（和=1,000） | `behavior=373`、`order_created=134`、`stock_reserved=134`、`order_cancelled=90`、`stock_released=90`、`user_registered=83`、`product_created=50`、`order_paid=44`、`refund_completed=1`、`refund_created=1` | 同上 |
| HDFS 落地 | `/graduation/landing/e4c1000/events-0001.jsonl` **353,053 B**（与本地同尺寸） | `hdfs dfs -ls` |

**只上传 `.jsonl`**：同目录的 `events-0001.manifest.json` **未**上传——`odl` 以「目录内每个文件都是事件文件」为口径，清单文件会被计成一条拒收记录（已避坑）。

---

## 3. 提交口径（逐字，取自 `tools/e4-run-chain.ps1`）

```
JAVA_HOME=D:\Develop\JAVA17（仅提交端；计算节点是 JDK 8）
HADOOP_CONF_DIR=YARN_CONF_DIR=<repo>\docs\acceptance\m3-cluster-probe-20260912\conf
HADOOP_USER_NAME=root
D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd
  --master yarn --deploy-mode cluster
  --conf spark.yarn.jars=hdfs://node01:8020/graduation/jars/spark-3.5.1/*.jar
  --conf spark.eventLog.enabled=true
  --conf spark.eventLog.dir=hdfs://node01:8020/graduation/eventlog
  --conf spark.sql.warehouse.dir=hdfs://node01:8020/graduation/warehouse
  --conf spark.hadoop.hive.metastore.uris=thrift://node01:9083
  --num-executors 1 --executor-cores 1 --executor-memory 1g --driver-memory 1g
  --conf spark.sql.shuffle.partitions=4
  <jar> --runtimeProfileId=1 --businessDate=20260901 --attemptNo=1
        --hiveDatabasePrefix=dw --outputSnapshotId=S20260901E4 --shufflePartitions=4
```
作业专属参数：`odl` 追加 `--landingDir=hdfs://node01:8020/graduation/landing/e4c1000 --sourceSystem=mock-mall --batchId=0`；`mxp` 追加 `--exportDir=hdfs://node01:8020/graduation/export/e4c1000`。
jar：`spark-jobs/target/p2-01-built/spark-jobs-0.1.0-SNAPSHOT-p2-01-e3.jar`（105 个 class，**全部 major=52**，构建于 09-12 14:15:55）。

> **本节的坑（已实测）**：`--master yarn --deploy-mode cluster` 下的**计算节点 JDK 与提交端 JDK 不是同一个**。提交端 JDK 17 只决定"能不能提交"，不保证 jar 里的 API 在 JDK 8 上存在（§7）。

---

## 4. 逐作业结果（含**独立**终态核对）

`yarn application -status` 逐条核对（`raw/e4-appstatus.txt`，与提交脚本的记录相互独立）：

| 作业 | appId | YARN 终态（独立） | JobResult | in | out | rej | 关键 message / 检查原文 | elapsedMs |
|---|---|---|---|---|---|---|---|---|
| `sci` | `…_0005` | FINISHED / **SUCCEEDED** | SUCCESS | 0 | 37 | 0 | `ok`（37 = 5 库 + 32 表，见 §5） | 11,082 |
| `odl` | `…_0006` | FINISHED / **SUCCEEDED** | SUCCESS | **1000** | **1000** | **0** | `accepted=1000 rejectedVersionKeys=0 topics=4 sourceSystem=mock-mall` | 27,490 |
| `bdw` | `…_0007` | FINISHED / **SUCCEEDED** | SUCCESS | 373 | 373 | 0 | `ok`（`dwd_reject_record` 0 行） | 16,780 |
| `dim` | `…_0008` | FINISHED / **SUCCEEDED** | SUCCESS | 357 | 133 | 0 | `user=83->83 product=274->50` | 19,773 |
| `tdw` | `…_0009` | FINISHED / **SUCCEEDED** | SUCCESS | 270 | 134 | 0 | `ok` | 17,278 |
| `usw` | `…_0010` | FINISHED / **SUCCEEDED** | SUCCESS | 373 | 83 | 0 | `ok`（7 张 DWS 表） | 24,835 |
| `fna` | `…_0011` | FINISHED / **SUCCEEDED** | SUCCESS | 1 | **138** | 0 | `ok`（8 张 ADS 暂存分区） | 28,195 |
| `dqc` | `…_0012` | FINISHED / **SUCCEEDED** | SUCCESS | 138 | 6 | 0 | 6 项检查全 `passed=true`（下同） | 19,495 |
| `pub` | `…_0013` | FINISHED / **SUCCEEDED** | SUCCESS | 138 | **138** | 0 | `ok`（8 张正式分区切换） | 27,883 |
| `mxp` | `…_0014` | **FAILED / FAILED** | **NO_RESULT** | — | — | — | `NoSuchMethodError: String.isBlank()`（§7） | — |

`dqc` 6 项检查原文（`message=ok`，逐条 `passed=True`）：
1. `BLOCKING`：8 张暂存表行数均 > 0（合计 **138** 行）
2. `ERROR`：`dt=20260901` 下仅有本快照 `S20260901E4` 的暂存分区
3. `BLOCKING`：138 行关键列全部非空
4. `BLOCKING`：阻断规则结果 `AMOUNT_RECONCILE=passed=1,err=0; ENUM_WHITELIST=passed=1,err=0; REQUIRED_FIELD_NULL_RATE=passed=1,err=0`
5. `ERROR`：观察项（重复 `event_id` 比率，不阻断发布）
6. `BLOCKING`：4 个 stage 汇总与 `dws_behavior_funnel_day` 一致

`pub` 4 项检查原文：`BLOCKING` 8 张暂存就绪（合计 138 行）；`BLOCKING` 8 张**正式**分区行数与暂存一致（合计 138 行）；`INFO` 本次切换 8 张（`ads_operation_overview, ads_active_trend, ads_behavior_funnel, ads_hot_product, ads_product_conversion, ads_sale_trend, ads_user_profile, ads_data_quality`）；`INFO` `dt=20260901` 无待清理历史暂存分区。

---

## 5. 数据链对账（**独立回读**，不经作业自报）

方式：本机 `spark-sql --master local[2]` 只做客户端，读集群 `thrift://node01:9083` + HDFS 数仓（`raw/e4-readback-out.txt`，SQL 见 `tools/e4-readback.sql`）。

| 层 | 表 | 行数（独立读数） |
|---|---|---|
| ODS | `ods_behavior_event` / `ods_product_event` / `ods_trade_event` / `ods_user_event` | **373 / 274 / 270 / 83 = 1,000** ✓ |
| DWD | `dwd_user_behavior_detail` / `dwd_order_detail` / `dwd_reject_record` | 373 / 134 / **0**（合计 507） |
| DIM | `dim_user` / `dim_product` | 83 / 50 |
| DWS | `dws_behavior_funnel_day` / `dws_product_behavior_day` / `dws_product_sale_day` / `dws_region_sale_day` / `dws_trade_day` / `dws_user_behavior_day` / `dws_user_trade_period` | 1 / 45 / 16 / 4 / 1 / 83 / 37（与 `usw` 自报 7 表**逐项一致**） |
| ADS **正式** | `ads_active_trend` / `ads_behavior_funnel` / `ads_data_quality` / `ads_hot_product` / `ads_operation_overview` / `ads_product_conversion` / `ads_sale_trend` / `ads_user_profile` | 1 / 4 / 4 / 45 / 1 / 45 / 1 / 37 = **138** ✓ |

闭合点：
- **入口**：ODS 四表之和 1,000 = `odl` 自报 `outputRecords=1000` = 生成器 `event_count=1000`；且与生成器事件类型分布按映射（`product_created+stock_reserved+stock_released→product`、`order_*+refund_*→trade`、`user_registered→user`、`behavior→behavior`）**逐项相合**：373 / (50+134+90)=274 / (134+90+44+1+1)=270 / 83 ✓
- **出口**：ADS 正式分区合计 138 = `pub` 自报 138 = `dqc` 检查 2 的"合计 138 行" = `fna` 暂存 138 ✓
- **物化**：`hdfs -count -v` 实测 `dw_ods.db` 104 目录 / 99 文件 / 938,239 B = **95 parquet + 4 `_SUCCESS`**（95 = `odl` 自报 95 个分区），`dw_dwd.db` 5 文件、`dw_dim.db` 4、`dw_dws.db` 14、`dw_ads.db` 22 ✓
- **表口径闭合**：`sci` 自报 `outputRecords=37` = **5 条 `CREATE DATABASE` + 32 条 `CREATE TABLE`**；32 张表由 metastore 独立证实：`SHOW TABLES` = ODS 4 / DWD 3 / DIM 2 / DWS 7 / ADS 16（8 正式 + 8 暂存）✓

> **测量陷阱（本次新记）**：`sci` 的语句数**不能**用"数源码里的 `CREATE TABLE` 行"来测——语句清单里既有字面 SQL，也有由 `OdsV2Columns` 派生的模板（`LocalSchemaInitJob.scala:220-234` 的 `odsCreateTable` 一处模板展开成 4 张 ODS 表），还有区域外的对账重建语句。按行数会得出 29/31/35 等互相矛盾的数；**正确做法是拿 metastore 实物反向验证**（5 库 + 32 表 = 37）。

---

## 6. 边界：本次**没有**证明的东西

1. `sci` 的建表语句是**实验自举 DDL**（`USING parquet`、无 `LOCATION`，`LocalSchemaInitJob`），**不是** `warehouse/ddl/` 的生产 DDL 口径——不得据此宣称"生产 DDL 已在集群应用"。
2. `--hiveDatabasePrefix=dw` 是**运行期参数**；源级命名空间在真实仓库中的落地（P2-07-b/P2-07-c、CT 批次）**未完成**，不得据此宣称"多源命名空间已生效"。
3. 集群**非独占**（同期存在 `com.yjxxt.exam.Job01`、Thrift JDBC 服务、`/user/root/.sparkStaging`），本次读数受同集群其他负载影响，未做隔离性证明。
4. 本次只到**数据链**（Hive 侧）。**E5 未做**：8090/8091/8092 页面级"员工可用"验收、MySQL 指标表回填、上传口径的端到端。
5. `mxp` 的导出产物**未产生**（`/graduation/export` 在失败时不存在 ⇒ 失败发生在 validate 阶段、未写出任何半成品）。
6. 十作业的 `--attemptNo=1`、单 executor×1 core ⇒ 本报告**不含**并发/容量/失败重试口径的结论。

---

## 7. 缺陷 **F-80**：jar 编入 Java 11 API，集群 JDK 8 运行期炸

**现象**（`raw/mxp-yarn-logs.txt` 与 `raw/mxp-submit.log`，app `…_0014`）：

```
java.lang.NoSuchMethodError: java.lang.String.isBlank()Z
	at com.graduation.analytics.job.MetricExportJob.$anonfun$validate$1(MetricExportJob.scala:33)
	at com.graduation.analytics.job.MetricExportJob.validate(MetricExportJob.scala:33)
	at com.graduation.analytics.job.JobRunner$.dispatch(JobRunner.scala:45)
	at com.graduation.analytics.job.JobRunner$.main(JobRunner.scala:33)
```
YARN 侧：`State=FAILED / Final-State=FAILED`，AM 两次 attempt 均退出码 13，存活 8.9 s，**没有** JobResult 输出。

**根因链（四环，逐环有证）**：
1. 集群计算节点 JDK = **1.8.0_351**（M3 探针：`java.class.version=52.0`、`/usr/java/jdk1.8.0_351-amd64/jre`）；
2. `spark-jobs/pom.xml` 的 scala-maven-plugin `<args>` 只有 `-deprecation`、`-feature`，**没有** `-release`/`-target`；
3. Scala 2.12 默认目标字节码 = `jvm-1.8`，所以 105 个 class **全部 major=52**——但编译是**对着提交端 JDK 17 的 API**做的 ⇒ JDK 11 才有的 `String.isBlank()` 被静默链进常量池；
4. 运行时在 JDK 8 上解析该方法 ⇒ `NoSuchMethodError`。

**爆炸半径（机械扫描，非抽查）**：对 jar 内**全部 105 个 class** 跑 `javap -p -c` 后按 Java 9+ API 名单检索 ⇒ 真命中**仅此 1 处**（另外 5 处是 `String.isEmpty()`=Java 1.2、`.toList` 是 Scala 集合方法，均为误报）。

> **⚠ 教训（已登记为实测陷阱 #21）**：**class 文件版本 ≤ 52 不等于 API 在 JDK 8 上存在**。凡是"提交端 JDK 高、运行端 JDK 低"的链路，必须让编译器**按目标 JDK 的 API 编译**（`-release 8`），否则这类缺陷只会在真集群上暴露，本地/CI 全绿。

### 7.1 修复路径上的一个**假安全**：`<args>` 里的 `-release:8` 会被静默覆盖

**第一版修法（错的）**：在 scala-maven-plugin 的 `<args>` 里加 `<arg>-release:8</arg>`。
**实测结果**：编译**照样成功**，`isBlank` 照旧被编进去 ⇒ 这条"闸门"根本没合上（正对照日志：worktree `.verify/release8-positive-control.log`）。
若不复核就收工，就会得到一个"看起来修好了、其实没修"的 jar——比不修更危险。

**机理（三重独立取证）**：

| # | 命题 | 取证方式 | 结果 |
|---|---|---|---|
| 1 | scalac 收到**重复 `-release` 时静默取最后一个** | 我另做三格矩阵（`D:\Develop\JAVA17` + scala-compiler 2.12.19，探针里故意用 `isBlank`） | A `-release 8` ⇒ **FAIL**；B `17`→`8` ⇒ **FAIL**；C `8`→`17` ⇒ **OK** ⇒ 后者胜，无任何告警 |
| 2 | 插件会读走 `maven.compiler.target`/`release` 并**自己追加** `-release` | 我对插件 jar 70 个 class 做字节级字符串扫描（Latin1 保 ASCII，含正对照） | `maven.compiler.release`/`maven.compiler.target`/`-release`/`-java-output-version` 全部落在 **`ScalaMojoSupport.class`**；常量片段含 `2.12.0…3.1.2…-java-output-version…-release`（scala≥2.12 走 `-release`）；正对照 `scalaVersion` 命中 13 个 class（证明扫描有效） |
| 3 | 因此 `<args>` 在前、插件追加在后 ⇒ 实际的 `-release 17` 覆盖用户的 `-release:8` | 泳道实测：`<args>` 生效性用 `-zzz-bogus-probe` 验证（插件确实转发 `<args>`）；加了 `<arg>-release:8</arg>` 后编译仍成功 | 与 1+2 的预测完全一致 |

证据文件：`raw/f80-scalac-release-override-evidence.txt`。

**修正后的修法（最终态）**：`spark-jobs` **没有 Java 源码**（`src/main/java`、`src/test/java` 均不存在），故 `maven.compiler.source/target` 只喂 Scala 插件 ⇒ 把它们由 `17` 改为 **`8`**，让插件**按声明**推导出 `-release 8`；并在插件配置里显式写 `<release>8</release>` 作为 API 级别的**唯一所有者**；把第一版那个无效的 `<arg>-release:8</arg>` 去掉。

> 措辞精确化（避免误读为"改历史"）：`<arg>-release:8</arg>` 是**本轮修复实验期临时加入、从未提交**的行，HEAD 里从来没有过 ⇒ 最终 `git diff` 对 `pom.xml` **只有 3 个 hunk、没有任何删除 hunk**（`@@ -18,2 +18,2 @@` 源/目标 17→8、`@@ -61,0 +62,12 @@` 新增 12 行说明、`@@ -75,0 +88 @@` 新增 `<release>8</release>`），`<args>` 块与 HEAD 逐字节相同（仅 `-deprecation`、`-feature`）。

**④ 生效参数的直接读数（两级，互相独立）**：
- 把 `-Xprint-args` 临时经 CLI 注入（`-DaddScalacArgs=…`，pom 未改），scalac 打印出的真实选项向量为
  `-bootclasspath <scala-library> -deprecation -feature `**`-release:8`**` -Wconf:cat=feature:w … -classpath <…>`
  —— **`-release` 只出现一次、值为 8，且不再出现 `-release 17`**（`raw/f80-fix/final-scalac-args-print.txt`）。
- `mvn -o -X compile` 全量日志里插件解析后的参数为 `(f) args = [-deprecation, -feature]`、**`(f) release = 8`**、`(f) source = 8`、`(f) target = 8`，紧随 `compiling 35 Scala sources`（`raw/f80-fix/final-effective-scalac-args.log`）。

> **防误读两则**（泳道实测）：① `-Xprint-args` 会把两 token 形式渲染成冒号形式——直接用 `scalac -release 8 -Xprint-args` 复现，输出同样是 `-release:8`（`raw/f80-fix/twotoken-print.txt`），故该打印件**不能**用来分辨"两 token vs 冒号 token"，只能证明"只有 1 个 release 设置、值为 8"；② `mvn.cmd` 承载不了含 `|` 的 `-D`（cmd.exe 当管道），该探针改用 `java -cp …plexus-classworlds… Launcher` 起**同一 Maven / 同一 pom**（`raw/f80-fix/printargs-run.log` 可查，pom 未动）。

### 7.2 修复产物自证：新 jar 与旧 jar 的**双侧对照**（总控独立执行，非采信泳道自述）

同一套扫描方法同时打在新旧两个 jar 上，做成"能测出旧缺陷 / 新缺陷为真零"的双向对照（单项零命中一律不采信）：

| 判据 | 旧 jar `…-p2-01-e3.jar` | 新 jar `…-jdk8fix.jar` | 结论 |
|---|---|---|---|
| class 数 | 105 | 105 | 结构未变 |
| class 文件 major | **52 × 105** | **52 × 105** | 均 ≤ 52（**注意：这一列本身证明不了 JDK 8 可用**，见 §7 教训） |
| 常量池 `isBlank` 命中 | **1**（`MetricExportJob.class`） | **0** | 缺陷已消除 |
| 正对照：常量池 `trim` 命中 | 8 | 9 | 证明扫描方法有效，新 jar 的 0 是**真零命中** |
| `javap` 该处字节码 | `java/lang/String.isBlank()Z` | `java/lang/String.trim()` + `java/lang/String.isEmpty()` | 语义等价替换，Java 8 可用 |
| 大小 / sha256 | 284,147 B / `2AC3B651…` | 285,286 B / `529541373C8D9590…` | 指纹已变更，E4 旧结论仍绑定旧指纹 |

> 纪律说明：上表每一格都是**执行后读到的输出**；"新 jar 无 `isBlank`"之所以能当结论，是因为同一方法在旧 jar 上**抓到了**它（否则就是又一次"零命中无正对照"的假绿）。

> **教训（新增实测陷阱 #22）**：**构建配置里"写了 flag" ≠ "flag 生效"**。判据必须是「实际生效的编译参数」或「编译期能否拦住一个故意的违规样本」，而不是 pom 里有没有那行——本缺陷正是"pom 里看着有闸门、实际被覆盖"的形态。

### 7.3 同一份源码、两份工作树 ⇒ **两个不同字节的 jar**（Windows 换行符被编进 SQL 字符串）

**发现过程**：主检出的构建件与泳道 worktree 的构建件 sha256 不同。差异只有 **30 B**（285,256 vs 285,286），但**绝不能当成噪声**——逐条目比对后发现 **105 个 class 里有 7 个内容不同**：

| 差异 class | 主检出差 | worktree 差 |
|---|---|---|
| `LocalSchemaInitJob$.class` | — | +109 B |
| `AdsSql$.class` | — | +161 B |
| `DwsSql$.class` | — | +102 B |
| `DimSql$.class` / `DwdSql$.class` / `OdsLoadSql$.class` / `TradeDwdJob$.class` | — | +43 / +37 / +15 / +19 B |

**根因**：这 7 个类的共同点是**含有三引号多行 SQL 字符串**。本仓库 `core.autocrlf=true`（仓库内 `.gitattributes` 不存在），于是：

| 观测 | 主检出（本次上集群用） | 泳道 worktree |
|---|---|---|
| 那 7 个 `.scala` 源里的 CRLF 行数 | **0**（纯 LF） | 56 / 66 / 165 / 169 / 274 / 291 / 343 |
| 三个抽样 class 内 `0x0D` 字节出现次数 | 51 | **357** |
| 结果 | SQL 常量里是 `\n` | SQL 常量里被编进 `\r\n` |

⇒ **同一 commit 的源码，只因为工作树换行符不同，产出的 class 常量池就不同**（SQL 文本真的变了，不只是元数据）。zip 里只差 30 B 是因为 deflate 把重复的 `\r` 压掉了——**这正是"字节差很小所以无所谓"这种直觉最危险的地方**。

**本次采用的判据与选择**：
1. 集群重跑使用**主检出的 LF 构建件**（`4F297355…`，285,256 B），理由有两条可复算的依据：① 仓库 blob 里存的是 LF（`autocrlf` 只在检出时转 CRLF）⇒ **Linux/CI 上的规范构建产物就是 LF 件**；② E4 run-1 那个被 9/10 作业正常执行的 jar 也是从这份 LF 工作树构建的 ⇒ 产物剖面一致，重跑与前次的差异只剩修复本身。
2. **未实测**：CRLF 构建件若真上集群，`\r` 会不会让 Hive/Spark 的 SQL 解析出错——**本次没有测**，因此**既不许说"CRLF 件有问题"，也不许说"两者等价"**。

> **教训（新增实测陷阱 #25）**：**"同一 commit 构建出的 jar 应该一样"在 Windows 上不成立**。构建件的 sha256 只有在**同时记录工作树换行符剖面**时才是可比的；反过来，两次构建 sha256 不同也**不**直接等于源码不同——必须做**条目级**比对定位差异落在哪。另：本次修复的 E2（100/100 绿）在**两种换行符剖面下都通过**（泳道 CRLF 件、主检出 LF 件各自 100/100），这条也算该差异"未影响单测"的实测边界，但**不外推到集群运行期**。

---

## 8. 新门禁：JDK 8 本地 pre-flight（零集群成本）

**做法**（`tools/jdk8-preflight.ps1`）：把 `JAVA_HOME` 指向本机 `D:\Develop\JDK1.8`（1.8.0_202），以 `--master local[2]` 直接跑目标 jar。

实测（**旧 jar**，即集群上失败的那个）：

```
[mxp ] exit=1  兼容性错误 ✗  2.1s
      命中: Exception in thread "main" java.lang.NoSuchMethodError: java.lang.String.isBlank()Z
```

⇒ **集群上要 66 秒提交、8.9 秒失败才看到的缺陷，本地 2.1 秒复现**，且 `validate` 在 `SparkSession` 创建**之前**执行（`JobRunner.scala:45` vs `:50`），所以这一步不需要 Spark 会话、不需要数据、不需要集群。

**同一门禁打在修复后的新 jar 上**（本地 JDK 8，`raw/f80-fix/new-jar-preflight-summary.jsonl` 与 `.verify/jdk8-preflight/new-jar/*.log`）：

| 作业 | exit | 兼容性命中 | JobResult / 原文 | 说明 |
|---|---|---|---|---|
| `mxp` | 1 | **0** ✓ | `status=FAILED`，`message=…未创建/未指向本次快照，拒绝导出: dw_ads.ads_operation_overview,…`，耗时 **9.4 s** | **决定性**：它**走过了 `validate`**（F-80 的爆点）并跑完整条导出前置逻辑；失败是**业务性**的（本地 derby 仓库的 ADS 表未指向本次快照），**不是 JDK 8 兼容性** |
| `sci` | 0 | **0** ✓ | `status=SUCCESS`，`outputRecords=37` | 建库建表在真 JDK 8 上全跑通 |
| `ljp` | 2 | **0** ✓ | `ljp 校验失败: 缺少参数 --landingDir` | 只到参数校验阶段（该作业码**不在** E4 十作业链内） |

> 磁盘侧副作用（诚实申报）：`sci` 这次跑的是本机 gitignored 的 `tests/r6-smoke-warehouse`，它按 `RECONCILE_ODS_V2` 对本地 `dw_ods` 旧 schema 做了对齐（message 里点名 `raw_event_type, raw_source_system, landing_file, payload…` 缺列）。**集群侧证据与仓库文件均未受影响**（本地 smoke 仓库不入库）。
>
> 另一条**未实测**：`ljp` 只验到参数校验；它的真实读写路径在 JDK 8 上**没有**被这次门禁覆盖（E4 链不含该作业码）。

**两条安全措施**（否则会污染集群证据，务必照抄）：
1. pre-flight 必须**清掉 `HADOOP_CONF_DIR`/`YARN_CONF_DIR`**：否则本地 Spark 会读到 `hive-site.xml` 的 `hive.metastore.uris=thrift://node01:9083`，把"本地建表"写进**集群** metastore；
2. 判据只认 `NoSuchMethodError|NoSuchFieldError|AbstractMethodError|UnsupportedClassVersionError`——**数据问题不算兼容性失败**，避免把无关的红当成兼容红。

---

## 9. 未实测 / BLOCKED（诚实清单）

| 项 | 状态 |
|---|---|
| 修复后集群重跑、10/10 | 见 §10（追加补记） |
| `mxp` 导出产物（JSONL + 清单）与 MySQL 侧落库 | **未实测**（被 F-80 阻断） |
| `ljp`（LocalJsonParquetJob）在集群/1,000 行上的表现 | **未实测**（E4 链不含该作业码） |
| JDK 8 与集群 1.8.0_351 的**版本号**差异（本地 1.8.0_202） | 已披露：同为 major 52，但补丁号不同 ⇒ pre-flight 是**必要非充分**门禁 |
| 集群上 8 个正式分区的 `LOCATION` 指向是否 = 快照暂存路径 | **未逐条实测**（`pub` 自报切换 8 张；HDFS 侧见 8 正式 + 8 暂存目录） |
| E5（员工可用）| **未做** |

---

# 10. 附录：F-80 修复后的集群重跑（2026-09-12 16:16–16:24）——**以此为准**

> **本章是追加补记**：§1–§9 是 run-1（appId `_0005`–`_0014`）的原始记录，**原文一字未改**。
> 凡与本章冲突处，**以本章为准**。据此，run-1 的「9/10 SUCCESS + `mxp` FAILED」结论更新为：
> **同一口径下 10/10 SUCCESS**（口径未变，仍是单机 1,000 行、单快照、单业务日）。

## 10.1 结论（两段式，照旧分「已证」与「未证」）

**① 已证（本轮新增）**：F-80 在**真集群**上闭环修复。同一份落地文件、同一业务日 `20260901`、同一快照
`S20260901E4`、同一 10 个作业码、同一组参数，**只把 jar 换成本次修复件**后重跑 ⇒ 10 个作业
**全部 SUCCESS**，并且经**三条相互独立**的证据链交叉确认（§10.3）。run-1 中崩溃的 `mxp` 现在 13 项检查全通过。

**② 未证（务必不要外推）**：本轮**只**消除了"编译目标高于运行期 JDK 8"这一个缺陷。E4 的边界
**一点没变**：单机、1,000 行、`L20` 部分完成 ⇒ 状态仍是 `DONE_LIMITED`。E5（员工可用页面）、
MySQL 侧真实导入、`ljp` 在集群上的行为、1,000 行以外的规模，**全部仍未实测**（§10.6）。

## 10.2 唯一变量：修复件与它的指纹

与 run-1 相比，**其余输入全部逐字不变**（落地文件 sha256 `A586080D…`、快照 `S20260901E4`、
`--batchId=0`、`--runtimeProfileId=1`、`--shufflePartitions=4`、`--hiveDatabasePrefix=dw`、
各作业的 `--landingDir` / `--exportDir`），因此本轮读数的差异**只能**归因于这一个 jar。

> **一处必须说清的例外（参数不变 ≠ 集群状态不变）**：run-1 已经跑过一轮，集群里**留有状态**——
> 8 张正式分区已发布、ODS 已有同快照数据。这解释了两处与 jar 无关的读数差异：
> ① `pub` 本轮「切换 0 张 / 同快照重放 8 张」（run-1 是「切换 8 张」）；
> ② ODS 行数没有翻倍（覆盖写）。
> 但 **`mxp` 的 red→green 不受状态影响**：它 run-1 的失败原因是 `NoSuchMethodError: java.lang.String.isBlank()Z`
> ——一个与数据、与状态完全无关的**类版本错误**，修复后同一作业码直接通过。归因只建立在这一点上。

| 项 | 值 |
|---|---|
| 修复补丁 | `.verify/f80-jdk8fix.patch`，5,131 B，sha256 `F77B9557A7A8374D…`，`git apply` 干净应用 |
| 改动面 | 4 文件 / +18 −5；`spark-jobs/pom.xml`（`maven.compiler.source/target` `17`→`8`、插件 `<release>8</release>`、注释）＋ 3 处 **JDK 9+ API 回退**（`String.isBlank`→`trim.isEmpty`、`ProcessHandle`→`ManagementFactory`、`Files.readString`→`readAllBytes`） |
| **上集群的 jar** | `spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar`，**285,256 B**，sha256 **`4F29735547D6DDEFDE4BF25C8E3157BF316D8F9EE6DBA10436E611A02421AA3A`**（提交前后各测一次，逐字一致） |
| E1（编译，主检出） | `mvn -o -f spark-jobs/pom.xml package -DskipTests` ⇒ `BUILD SUCCESS`，15.509 s（`raw/f80-fix/e1-package-main.log`） |
| E2（单测，主检出） | `mvn -o -f spark-jobs/pom.xml test -DargLine=…--add-opens…` ⇒ **100/100**、14 suites、0 failed、exit 0（`raw/f80-fix/e2-test-main.log`） |
| JDK 8 pre-flight（**同一脚本、同一 JDK 1.8.0_202**） | 旧件：`compatHit=1`，`Exception in thread "main" java.lang.NoSuchMethodError: java.lang.String.isBlank()Z`（`raw/jdk8-preflight-old-jar/summary.jsonl`）<br>新件：`mxp/ljp/sci` **compatHit 全 0**，`sci` 跑到 `status=SUCCESS outputRecords=37`（`raw/f80-fix/main-jar-preflight-*.log`） |
| 构建件同一性说明 | 本次用**主检出的 LF 构建件**（理由与 CRLF 差异见 §7.3 与陷阱 #25） |

**⇒ 这是一次可复算的 red→green**：本地同脚本同 JDK 下「旧件挂 / 新件过」，集群上同一作业码「run-1 挂 / run-2 过」。

## 10.3 集群重跑读数（10 作业）＋ 三条独立证据链

| # | 作业 | appId | spark-submit exit | JobResult | 墙钟 | 关键读数 |
|---|---|---|---|---|---|---|
| 1 | `sci` | `application_1789195359269_0015` | 0 | SUCCESS | 25.8 s | `outputRecords=37`（5 DB / 32 表） |
| 2 | `odl` | `…_0016` | 0 | SUCCESS | 77.8 s | `1000 → 1000`，rej=0，`topics=4 sourceSystem=mock-mall` |
| 3 | `bdw` | `…_0017` | 0 | SUCCESS | 31.4 s | `373 → 373` |
| 4 | `dim` | `…_0018` | 0 | SUCCESS | 31.2 s | `357 → 133`（user 83→83 / product 274→50） |
| 5 | `tdw` | `…_0019` | 0 | SUCCESS | 29.2 s | `270 → 134` |
| 6 | `usw` | `…_0020` | 0 | SUCCESS | 38.4 s | `373 → 83`（7 张 DWS 表） |
| 7 | `fna` | `…_0021` | 0 | SUCCESS | 42.3 s | `1 → 138`（8 张暂存表） |
| 8 | `dqc` | `…_0022` | 0 | SUCCESS | 32.2 s | 6 项检查全 `passed=true` |
| 9 | `pub` | `…_0023` | 0 | SUCCESS | 49 s | `138 → 138` |
| 10 | **`mxp`** | `…_0024` | 0 | **SUCCESS** | 36.3 s | `138 → 138`，**13 项检查全 `passed=true`** |

**独立证据链（都不采信作业自报）**：

1. **集群视角**：`yarn application -status` 逐条独立查询（与 spark-submit 的退出码相互独立），原文逐字为
   `sci application_1789195359269_0015 State=FINISHED Final=SUCCEEDED` … `mxp application_1789195359269_0024 State=FINISHED Final=SUCCEEDED`
   ⇒ **10/10 `State=FINISHED` / `Final=SUCCEEDED`**（`raw-fix/e4-appstatus-fix.txt`，10 行 680 B）。
2. **数仓视角**（本地 `spark-sql` 只读 → 集群 `thrift://node01:9083` → HDFS 真读）。
   **先把本轮的实测边界划清——run-2 的独立回读只覆盖下列四项**：

   | run-2 **重测**了什么 | 读数 | 出处 |
   |---|---|---|
   | DWD 3 表 | `134` / `373` / `0`，合计 **507** | `raw-fix/e4-readback2-fix.txt` |
   | DWS 3 表（trade_day / user_behavior / user_trade_period） | `1` / `83` / `37` | 同上 |
   | **ADS 正式分区合计**（独立 `count(*)`，非清单自报） | **138** | `raw-fix/e4-mxp-readback-out.txt` §3 |
   | ODS `ods_behavior_event` | `373` | `raw-fix/e4-batchid-check-fix.txt` |
   | （另）表清单与 `__staging` 配对表 | 4/3/2/7/16 等 | `raw-fix/e4-readback-fix.txt` |

   **run-2 没有重测、因而只能沿用 run-1 读数的部分**：ODS 的 product/trade/user 三表（`274`/`270`/`83`）、
   DIM 两表（`83`/`50`）、DWS 的 funnel/prod_beh/prod_sale/region 四表（`1`/`45`/`16`/`4`）、
   以及 ADS 逐表 Hive 计数（`1/4/4/45/1/45/1/37`）——这些数字**来自 run-1 的独立回读**
   （`raw/e4-readback-out.txt`、`raw/e4-readback2-out.txt`）。它们在 run-2 侧的支撑只有**作业自报**（见 §10.3 表），
   属于"两轮相互印证"，**不构成本轮的新证据**。
   > 过程留痕：本稿初写时曾把这些 run-1 读数一并写成本轮"逐表回读"，被提交前自检脚本
   > （`.verify/e4-precommit-verify.ps1`）比对原始捕获后**抓出并改正**——这正是"未实测不写结论"的用例。
3. **导出视角**：HDFS 独立回读 ⇒ 8 个 `*_m.jsonl` 行数 `1/4/4/45/1/45/1/37` = **138**，清单 `_export.json` 自报 `totalRows=138`，两者**逐表 8/8 一致**；再与第 2 条的独立 Hive 合计 138 三方相等（`raw-fix/e4-mxp-readback-out.txt`）。

**另外两条本轮才测到的行为**（run-1 未观测，属新增证据而非新结论）：

- **重跑是覆盖，不是追加**：`SELECT count(*), collect_set(ingest_batch_id), collect_set(source_system) FROM dw_ods.ods_behavior_event WHERE dt='20260901'` ⇒ `373 | [0] | ["mock-mall"]`。若当年 `odl` 是追加写，这里会变成 746 与 `[0,0]` ⇒ **重复计数风险被实测排除**（`raw-fix/e4-batchid-check-fix.txt`）。
- **发布幂等**：`pub` 本次自报「本次切换 **0** 张；同快照重放 **8** 张（同 `snapshotId` 不产生第二份数据）」——run-1 是「切换 8 张」。这正是 §14.4 的发布幂等设计在**重放路径**上的表现。
- **指针落点逐条实测（关闭 §9 遗留缺口）**：对 8 张正式 ADS 表逐条 `DESCRIBE FORMATTED … PARTITION (dt='20260901')`，**8/8** 分区 `Location` = `…/dw_ads.db/<t>__staging/snapshot_id=S20260901E4/dt=20260901`（表级 `Location` 仍是 `…/<t>`，换指针只发生在**分区级**）⇒ §9 表中"未逐条实测"一项**现已实测关闭**（`raw-fix/e4-ads-location-check.txt`）。

## 10.4 本轮新登记的两个实测陷阱 ＋ 一次工具自纠

- **陷阱 #25（同一 commit、两份工作树 ⇒ 两个不同字节的 jar）**：详见 §7.3。要点：`core.autocrlf=true` 会把 CRLF 编进三引号 SQL 字符串常量；**字节差小 ≠ 无害**，也比较不出"同源"；构建件 sha256 只有在同时记录工作树换行符剖面时才可比。
- **陷阱 #26（本机内存纪律：三件事不得并发）**：本轮实测到——分页文件分配上限仅 **4,196 MB**，在集群提交链运行期间，连一条只读的 `pwsh` 查询都因 `Allocation error : not enough memory` 起不来。⇒ **「集群提交链（spark-submit）／ 本地 Spark ／ Maven 构建」三者互斥**，本地 Spark 一律用 lean 参数（`local[1]`、`--driver-memory 512m`、`spark.sql.shuffle.partitions=1`、限 metaspace、SerialGC）。
- **陷阱 #27（证据生成脚本自身必须先自检）**：本轮 `e4-mxp-readback.ps1` 有两处缺陷——① 清单文件实际名为 `_export.json`，脚本却按 `manifest|清单|_SUCCESS` 过滤 ⇒ 把 18 行清单当作数据文件，打印出**错误**的「数据合计 = 156」；② 对照 SQL 用的是 **v1** 的 ADS 表名（`ads_gmv_day` 等），当前命名空间无此表 ⇒ 解析失败、无输出。**处理方式**：有缺陷的首版捕获**保留**为 `raw-fix/e4-mxp-readback-out-FLAWED-capture.txt`，修正后的捕获为 `raw-fix/e4-mxp-readback-out.txt`（**以修正件为准**，§10.3 引用的是修正件）。教训：**读数脚本的"分类/口径"本身也会错**，凡脚本产出的结论必须先做一个"应然值"对照（本例：清单 `totalRows` 与逐表 `rowCount` 必须能对上）。

## 10.5 复现步骤（逐字命令）

```powershell
# 0) 应用修复（若工作树尚未含修复）
git apply .verify/f80-jdk8fix.patch

# 1) E1 编译 + 2) E2 单测（主检出；勿与本地 Spark 并发）
$env:MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'
D:\apache-maven-3.9.14\bin\mvn.cmd -o -f spark-jobs/pom.xml package -DskipTests
D:\apache-maven-3.9.14\bin\mvn.cmd -o -f spark-jobs/pom.xml test "-DargLine=-Dfile.encoding=UTF-8 --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"

# 3) JDK 8 pre-flight（真 JDK 1.8.0_202，同一脚本对旧件/新件各跑一次）
pwsh -NoProfile -File .verify/jdk8-preflight.ps1 -Jar spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar -Tag main-jar -Jobs mxp,ljp,sci

# 4) 集群 10 作业重跑（唯一变量 = 上面的 jar）
pwsh -NoProfile -File .verify/e4-run-chain.ps1 -Jobs 'sci,odl,bdw,dim,tdw,usw,fna,dqc,pub,mxp' `
  -Jar 'spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar' `
  -RawDir 'docs\acceptance\e4-cluster-1000-20260912\raw-fix' -KeepGoing

# 5) 独立复核（appstatus + Hive 回读 + 覆盖性 + 导出侧三方对账 + 分区 LOCATION）
pwsh -NoProfile -File .verify/e4-verify-fix.ps1
pwsh -NoProfile -File .verify/e4-mxp-readback.ps1
spark-sql --master 'local[1]' --driver-memory 512m -f .verify/e4-location-check.sql
```

## 10.6 未实测清单（更新版；与 §9 并列，以此为准）

| 项 | 状态 |
|---|---|
| E5（员工可用页面级验收） | **未做** |
| `mxp` 导出物 → **MySQL 真实导入**（`*_m` 表落库） | **未实测**（本轮只证到 HDFS 导出物与清单一致） |
| `ljp`（LocalJsonParquetJob）在集群/1,000 行上 | **未实测**（E4 链不含该作业码） |
| **CRLF 构建件**在集群上的行为（SQL 常量含 `\r`） | **未实测** ⇒ 既不说"有问题"，也不说"等价"（§7.3） |
| `--outputSnapshotId` 传入**纯 Unicode 空白**（如 U+3000）时的语义 | **未实测**：`trim.isEmpty` 与 `String.isBlank()` 语义确有差异，但旧分支在 JDK 8 上**从未执行过**，不构成对既有可用契约的破坏（已披露，不夸大） |
| 1,000 行以外的规模（10 万/百万行、多快照并发、多业务日） | **未实测** |
| 集群**非独占**导致的耗时可比性 | 已披露：本轮与 run-1 的 `elapsedMs` 只可作粗略对照 |
| `sci` 建表 DDL 与生产 `warehouse/ddl/` 口径是否一致 | **未实测**（`sci` 只证"能建出 5 DB / 32 表"） |
| **run-2 未重测的分层回读**：ODS product/trade/user、DIM 两表、DWS 四表（funnel/prod_beh/prod_sale/region）、ADS 逐表 Hive 计数 | **未重测**（沿用的是 run-1 的独立回读读数；run-2 侧仅有作业自报相互印证 ⇒ 不得表述为"本轮逐表实测"） |
| M3 接入（源实例/连接器/映射）／CT 契约批次／T2 55 条黄金链 | **未做**（另见看板） |

## 10.7 状态与登记建议

| 对象 | 原状态 | 建议新状态 | 依据 |
|---|---|---|---|
| **F-80**（集群 jar 编译目标高于运行期 JDK 8） | `REVIEW`（修复待验） | **`DONE`**（范围：本链 10 作业码 + E1/E2 + 本地 JDK 8 pre-flight） | §10.2 的 red→green（本地＋集群双侧） |
| **E4**（集群 1,000 行真实链） | `DONE_LIMITED`（9/10） | **`DONE_LIMITED`（10/10）**——层级不变，仍是单机 1,000 行、无页面级验收 | §10.3 三条独立证据 |
| §9「LOCATION 未逐条实测」 | 缺口 | **已关闭** | §10.3 第 3 条 |
| 「导出侧未验证」 | 缺口 | **已关闭**（本轮首次回读） | §10.3 第 3 条 |

## 10.8 提交流程门禁（本轮新增，可复用）

`docs/acceptance/e4-cluster-1000-20260912/tools/e4-precommit-verify.ps1`（源码同步在 `.verify/`，14.6 KB）

**它做什么**：把本章与看板里出现的**每个数字**压回 `raw/` 与 `raw-fix/` 的原始捕获逐条核对——
jar 指纹与 class 计数、补丁 sha256、四处源码的新旧写法、`appstatus` 逐字、**run-2 真正重测过的分层回读签名**、
导出三方对账、分区 `LOCATION ×8`、`MANIFEST.md` 指纹自洽（12/12）、脱敏复检、工作树白名单、文档落点，
以及一条**自指断言**（看板自述的断言数必须等于脚本本次实际断言数，防止文档与工具漂移）。

**它抓出过什么（诚实留痕）**：本稿初写时，① 把 **run-1** 的分层回读（ODS/DIM/DWS 其余表、ADS 逐表 Hive 计数）
写成了本轮"逐表实测"——已按「未实测不写结论」改为分层边界表述（§10.3 第 2 条）；② 掩码脚本曾**误改已公开历史**
（看板 15:46 行正文）——已**还原为推送时原文**，改为只登记不改写（`MASKING.md` §3）。
两处都是**先被机器抓出、再由人改正**，不是靠自觉。

```powershell
# 提交前必须为全绿（任一项 FAIL ⇒ 不得提交）
pwsh -NoProfile -File .verify\e4-precommit-verify.ps1
# 期望末尾：合计 53 项，PASS 53，FAIL 0   结论: 全部 PASS，可以提交   [exit 0]
```
