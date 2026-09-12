# P2-01 实施报告 —— ODS v2 加法扩列 + payload 保真（本地真实链已复跑）

- **车道**：续跑泳道（接管前任泳道留下的工作树；前任用尽上下文、未交付报告，工作树改动全部保留、未删任何文件）
- **分支 / HEAD**：`remediation/r1-boundary` @ `0df3d1ce0909a6b6a79dd12aa306ef73c3a5a486`（本泳道**未做任何 git 写操作**，提交由总控执行）
- **日期**：2026-09-12（末次真链运行 `20260912-141554`）
- **证据分级**：E1 = 模块内 `mvn package -DskipTests`；E2 = 模块内单测（`spark.sql.catalogImplementation=in-memory`，**不是** Hive 证据）；E3 = 本地真实链（真 `spark-submit` 进程 + 真 Hive metastore(Derby) + 真 `spark-sql` 回读）

> ## 越界声明（第一个文件即声明）
> 本任务改了**一个声明模块之外的文件**：`warehouse/ddl/00-ods.sql`（ODS v2 静态 DDL 侧列集所有者）。
> 它是 `ORDER-1.md` 中已登记的**唯一**模块外例外。除此之外，本任务的全部写入都在
> `spark-jobs/**` 与 `docs/acceptance/p2-01-ods-v2-20260912/**` 之内；未改 `pom.xml`、契约、看板、`RULINGS.md`、
> `analytics-server/**`、`synthetic-data-generator/**`、`contract-specs/**`、任何 dashboard。

---

## 0. 结论速览

| 级别 | 状态 | 关键读数（原始输出） | 原始日志 |
|---|---|---|---|
| **E1** 编译打包 | ✅（前任态） | `mvn -o package -DskipTests` BUILD SUCCESS，`Building jar: spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar`，Finished `2026-09-12T13:34:35+08:00` | `evidence/PARENT-e1e2-20260912-1345.log` |
| **E2** 单测 | ✅ 全绿 | `Total number of tests run: 100`／`Suites: completed 14, aborted 0`／`Tests: succeeded 100, failed 0, canceled 0, ignored 0, pending 0`／`All tests passed.`／`BUILD SUCCESS`／`Total time: 28.598 s` | `evidence/e2-full-20260912-run5.log` |
| **E3** 本地真实链 | ✅ **PASS** | `E3_RESULT=PASS`，**51/51 检查全绿**，harness `HARNESS_EXIT=0`；`E3_00` jar `284147 B sha256=2ac3b651…4069e5`，`classesContentSha256=d390b529…b00e8b1`（105/105 类） | `evidence/e3-20260912-141554-checks.tsv`、`-summary.txt`、`-01…-05` 原始日志 |

三条硬结论（均为**实测读数**，非"应该"）：

1. **5 个新列正确**：21 列列序实测（`E3_40`）、`A4_newcol_nulls=0`、`A5_raw_event_type_mismatch=0`、
   `A6_landing_file=file:/D:/Develop_code/GraduationProject/tests/golden-dataset/events/golden-20260901.jsonl`
   （即 D-057 要求的 `_metadata.file_path` 通道）、`A7_source_file_ne_landing_file=0`（`source_file` 已改为真实值，
   常量 `'landing'` 消失）。
2. **`payload_json` 字节保真**：52/52 行与**源行那一段逐字节相同**（`B1_payload_ne_source_slice=0`）；
   `payload_hash` 自洽 `B2=0`，且与**独立的源侧切片 oracle** 一致 `B3=0`；`B5_payload_braced=52/52`；
   `B6_payload_hash_lowerhex64=0`；长度读数 avg/min/max = `107/56/286`。
3. **加法双写、v1 不受影响**：v1 十四列**名字与物理序号逐格等于 v1**（`E3_40` DESCRIBE 前 14 列 + `E3_41` 类型位串
   `string×13,bigint`），新列紧随 `ingest_batch_id` 之后；v1 值经**两个独立 oracle** 零失配
   （`C1=0`、`C2=0`、`C4=0`、`C7=0`），v1 `payload_*` 非空行数 `4/14/16/18`（`C5`，**缺陷期为全 0**，见 §2.3）。

---

## 1. 变更清单（逐文件 before/after sha256）

口径：`before` = `git show HEAD:<path>` 的**字节** sha256（7 个 in-HEAD 文件已导出到
`spark-jobs\target\p2-01-baseline\HEAD-*` 并逐字节核对）；`after` = 交付态工作树文件 sha256。
机器可读清单：`evidence/p2-01-change-manifest.tsv`（16 行，含 15 个源文件）。

### 1.1 产线代码（5 文件：2 新增 + 3 修改）

| 文件 | before(HEAD) | after | bytes | 状态 |
|---|---|---|---|---|
| `spark-jobs/.../sql/OdsLoadSql.scala` | `061a0cf65a85140fab325f403b40a656e06c9b0e2b4996c51aa82c9d680c2dd8` | `5206dd69203e4db242d7c3a3bbcd18af7c4182768109289d089802b03c6b993e` | 15639 | modified |
| `spark-jobs/.../job/EventOdsLoadJob.scala` | `dc93c1c3fd163dfed937bdb1c620659a762dcd99eb83992e1ab0ddb9351b1d15` | `60224645f8c25e1b6dd98d2abe8ce29e6f9520b84766568f7c4aebc3c8438804` | 10456 | modified |
| `spark-jobs/.../job/LocalSchemaInitJob.scala` | `ee4bcfeacc23cf52431c198c9b1bbe7bdb92e18e78fe479a8c25d30863ed57cd` | `e61f518917db7e16916f301f9d1b793c410a845df66166506ff202b2aeb08eac` | 19056 | modified |
| `spark-jobs/.../sql/OdsV2Columns.scala` | absent | `7b223766fd746050c8ee1f8bc0ebc8e1c19a7a53e13a142f1c11941e73934c05` | 8116 | **new**（列集单一所有者） |
| `spark-jobs/.../sql/JsonObjectSlicer.scala` | absent | `bf73aa71f6320d2673570bb44f7bafbef72359388006aa804e3a8c6891e1f6cc` | 10852 | **new** |

（表内即 5 个产线文件：`OdsLoadSql`/`EventOdsLoadJob`/`LocalSchemaInitJob` 修改，`OdsV2Columns`/`JsonObjectSlicer` 新增。）

### 1.2 模块外文件（**声明例外**，1 文件）

| 文件 | before(HEAD) | after | bytes |
|---|---|---|---|
| `warehouse/ddl/00-ods.sql` | `36edbf7929d9aa6441f898a6d5cf3346c99e7bb09e13f0f2914907bfa1161693` | `2bebe55ff09580443937f945a31dacf856bbbd898fb7c1b953dc6c7465816863` | 6178 |

### 1.3 测试（9 文件：6 新增 + 3 既有被改）

| 文件 | before(HEAD) | after | bytes | 状态 |
|---|---|---|---|---|
| `.../P2TestSupport.scala` | absent | `d9344779aa3a8a83928a289efcde2db85f7cb6466b4159039b01894832e06195` | 5716 | new |
| `.../OdsV2ByteFidelitySpec.scala` | absent | `237442c8b0458e0ced9b7d13391310b07de1592a74929ac5ed9b82d84f2c74b6` | 10680 | new |
| `.../OdsV2EdgeCaseSpec.scala` | absent | `4c73f36f55d23331d67982a4ebc0d33e4858a145fd0d5afa92f2e7d30d39a6e7` | 8908 | new |
| `.../OdsV2SchemaOwnerSpec.scala` | absent | `ebbb5998a0d8249be383b8e90f1081b15f3cf3cf75bc34598d467c62ea4e3e2f` | 16231 | new |
| `.../OdsV2SqlContractSpec.scala` | absent | `c9736f7b1de7e01069bc5c3064ba99303b145396b080320bc5efba74b736a926` | 15106 | new |
| `.../sql/JsonObjectSlicerSpec.scala` | absent | `6da0d02c175909248cc0fcf344b4d2263dfb24bfbf39eef50d0ccba406609553` | 6266 | new |
| `.../SqlTemplateSpec.scala` | `0755ce5b77bd2a97c3d45f3bf3110533ead70b111f858990aeb5f48408810b5f` | `41431bd308ae13524d243854e42bdcbe880e886d5fd89e49cbfc8c063ffb9dba` | 12966 | modified |
| `.../IdCodecSpec.scala` | `cb83a0b75dbf8244c1de8a272785d9c3bc4368694ae2a07bfca1e79f2356e143` | `f16fe4cf88ca30e095615415cac3a8eff1609b5d0609364eef66a9fa531c868c` | 5786 | modified |
| `.../WarehouseNamespaceSpec.scala` | `3e1280bfff79a8a23d521b3765b0e0bdad7058df4be0bc0218848edf43b023f1` | `a430c2c23847490eb67bfc674f701eebfebccdc41ed4a4d2a1406d5e35d0950f` | 13133 | modified |

**既有 suite 改动的性质（防"改测试换绿"质疑，逐个人工核对过 diff）**：

- `OdsV2SchemaOwnerSpec`（新增文件，但下面 §2.5 记录了它的两次 red）：A11/A11b/A11c/A12 的 red 判决为**测试侧缺陷** ——
  DDL 表名正则 `(\w+)\s*\(` 不认**限定名**（静态 DDL 写 `${WAREHOUSE_PREFIX}_ods.ods_user_event`、派生 DDL 写 `dw_ods.ods_user_event`），
  12 处断言全部零匹配 ⇒ 抛 `DDL 未解析到表 ods_user_event`。修的是**正则与裸表名提取**（`bareTable`），**断言文本一字未改**。
- `SqlTemplateSpec:44-49`：red 判决为**测试侧**，但方向是**收紧**：原断言要求模板里出现 `_metadata.file_path as source_file/landing_file`，
  而改造后模板的 `FROM` 是作业层建的临时视图 `landing_valid`（视图上没有 `_metadata`）⇒ 改为
  「模板把视图列 `landing_file` 透传成 `source_file`」且追加 `should not include "_metadata.file_path"`（禁止回退），
  值级事实由 E3 `A6/A7` 与 `OdsV2ByteFidelitySpec A8` 钉住。
- `IdCodecSpec`（6+/2−）、`WarehouseNamespaceSpec`（6+/4−）：**只是调用点适配**（ODS 模板新增第二参 `sourceSystem`，D-056），
  既有断言原样保留、无一处放宽。
- `OdsV2EdgeCaseSpec`（11+/4−，新增文件的后续修订）：见 §2.5 的 A6 归属裁定。

### 1.4 证据与工装（全部在 `docs/acceptance/p2-01-ods-v2-20260912/`）

| 文件 | sha256 | bytes |
|---|---|---|
| `harness/e3-real-chain.ps1` | `bdb56d43b8227f13776c807d4d6320108c97f5345c5392409b34dfe64ac3b6a3` | 26473 |
| `harness/e3-readback.sql` | `40e44395d3bd4efbd0e7edc4a26bed00b084b5a53cbfe57531796c2454e6a010` | 22943 |
| `harness/e3-probe-ddl-clause-order.ps1` | `f62c2d58e112a995b1d58b2fa039f21358e78da343f6b3bd357db87a5933111d` | 5885 |
| `evidence/p2-01-change-manifest.tsv` | （见文件内自带清单） | — |
| `evidence/p2-01-evidence-manifest.tsv` | **73 个文件**的 path/sha256/bytes 全量清单 | — |

其余 70 个证据文件（原始日志、探针、TSV、pre-fix 备份）的**逐个 sha256** 见 `evidence/p2-01-evidence-manifest.tsv`。

### 1.5 我**没有**改的东西（逐项声明）

- `spark-jobs/pom.xml`：**字节未变**（`git diff --stat -- spark-jobs/pom.xml` 空；工作树 sha256
  `0ece9823df791768475b3d08eca361dedc6d53764e5065c01a278dfad2185bf4`，`HEAD:spark-jobs/pom.xml` blob `e2d7580dcd7b91fd9a00b887710558e98005ba50`）。
  JDK17 测试 JVM 的 `--add-opens` 走**逐次调用**的 CLI `-DargLine`，未落 pom（依据 D-060「禁止为此改 pom 依赖」）。
- 冻结的**在产 jar** `spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar`：交付时复核三点全等 ——
  `234038 B` / mtime `2026-09-11 18:19:03` / sha256 `f9e879aadea71c9301b079fc70d714c3def6de30902db39ff92f428c596318a8`（与 D-060② 冻结值一致）。
  **我刻意没有重跑 `mvn package`**：那会覆盖这个在产 jar（这正是 R1 要防的）。
- 真仓库 `D:\Develop_code\GraduationProject\spark-warehouse`：E3 前后 fingerprint 同一配方两次读数**完全相同**
  （`FILES=2006 SHA256=3c7e3deaf99072de920124501ca742a71f6490246e47ad2da7af7acb25ea20ae`，见 `E3_62`）；未触碰任何 `dw_*` 库。
- 契约 / 看板 / `RULINGS.md` / `analytics-server/**` / `synthetic-data-generator/**` / `contract-specs/**`：零写入。

---

## 2. 证据

### 2.1 E1（编译打包）

```
mvn -o -q -DskipTests package        # 工作目录 D:\Develop_code\GraduationProject\spark-jobs
```
- 原始日志：`evidence/PARENT-e1e2-20260912-1345.log`（L1-L56 为 E1 段）。
- 读数：BUILD SUCCESS；`Building jar: …\spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar`；`Finished at: 2026-09-12T13:34:35+08:00`。
- 该日志 L152-L172 同时是 **E2 abort #1** 的原始证据：`java.lang.IllegalAccessError: … sun.nio.ch.DirectBuffer`
  （JDK17 模块封装），修复方式见 §2.4。
- **诚实边界**：E1 产物是**修复前**的代码态（`predecessor-live-jar-20260912-133435.jar`，283,971 B，
  sha256 `463af1d36882f911012fef467a121e5b8f104fa83c90df9bbecbe23dd7ab5e88`），**不能**代表最终修复态。
  最终修复态的编译证据 = E2 run5 的 compile 阶段全绿 + E3 对 `target\classes` 的内容指纹
  （`105/105` 个类，`classesContentSha256=d390b5291bbb817db77c5153be3e3008921ab70271ec76257a94645c4b00e8b1`）。
  另记一条平台事实：E1 日志出现 `Using platform encoding (GBK actually)` ⇒ 本机 `mvn` 默认非 UTF-8，
  这也是 E2 必须显式 `-Dfile.encoding=UTF-8` 的原因之一。

### 2.2 E2（模块内单测；含既有 62 用例）

```
mvn test -DargLine="-Dfile.encoding=UTF-8 --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
```
- 生效证据（日志逐字）：`[INFO] Forking ScalaTest via: cmd.exe /X /C "D:\Develop\JAVA17\bin\java -Dbasedir=… -Dfile.encoding=UTF-8 --add-opens …`
  ⇒ `argLine` 确实进入了被 fork 的测试 JVM（这就是 E2 abort #1 的修复点，pom 未动）。
- 最终日志：`evidence/e2-full-20260912-run5.log`（13,242 B，sha256 `13459a16788d0744e18d6d856171791c93a4d207170befb79a6e1cc372087a13`）。
- 读数（逐字）：L45 `Total number of tests run: 100`；L183-L186 `Suites: completed 14, aborted 0` /
  `Tests: succeeded 100, failed 0, canceled 0, ignored 0, pending 0` / `All tests passed.`；L188 `BUILD SUCCESS`；
  L190 `Total time: 28.598 s`；L191 `Finished at: 2026-09-12T14:06:20+08:00`。
- **既有 62 用例（8 个 suite）全部绿**（逐 suite 计数由日志汇总）：
  `SqlTemplateSpec 22`、`QuartileStatsRfmSpec 5`、`OrderTradeCompilerSpec 7`、`IdCodecSpec 7`、
  `FunnelHeatAnomalySpec 7`、`WarehouseNamespaceSpec 6`、`MetricAdsSpecTest 5`、`JobArgsRegistrySpec 3` = **62**。
  其中 `WarehouseNamespaceSpec`/`SqlTemplateSpec`/`IdCodecSpec` 三个 suite 的改动性质见 §1.3（调用点适配 + 一处收紧）。
- 本任务新增 5 个 suite 共 **38** 用例：`OdsV2SchemaOwnerSpec 10`、`OdsV2ByteFidelitySpec 9`、
  `OdsV2SqlContractSpec 8`、`JsonObjectSlicerSpec 6`、`OdsV2EdgeCaseSpec 5`。
- 第 14 个 suite 是 `DiscoverySuite`（0 用例），故 `completed 14` 与上表 13 个具名 suite 不矛盾。
- 上表逐 suite 计数由该日志的 suite 分块**机械统计**得到（13 个具名 suite 的用例数之和 = `100`，其中既有 8 个 suite 之和 = `62`）；
  ScalaTest 自报 `Run completed in 22 seconds, 258 milliseconds.`（L182），Maven 自报 `Total time: 28.598 s`（L190），两者口径不同、都记录在案。
- **证据级别边界**：`P2TestSupport.spark()` 用 `spark.sql.catalogImplementation=in-memory` ⇒
  **E2 全程不建真 Hive 表**，E2 的任何"列/写入"结论都**不能**冒充真实链结论（因此才有 E3）。

### 2.3 E3（本地真实链；含一次真实缺陷的发现→根因→修复→复跑）

命令（逐字；`$jar` = `spark-jobs\target\p2-01-built\spark-jobs-0.1.0-SNAPSHOT-p2-01-e3.jar`，
由 `target\classes` 现场 `jar cf` 得到，避开在产 jar）：

```
D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd --master local[2] ^
  --class com.graduation.analytics.job.JobRunner ^
  --conf spark.hadoop.javax.jdo.option.ConnectionURL=jdbc:derby:<work>\derby-metastore;create=true ^
  --conf spark.hadoop.javax.jdo.option.ConnectionDriverName=org.apache.derby.jdbc.EmbeddedDriver ^
  --conf spark.sql.hive.metastore.jars=builtin ^
  --conf spark.hadoop.datanucleus.schema.autoCreateTables=true ^
  --conf spark.sql.warehouse.dir=file:///<work>/warehouse ^
  <jar> --runtimeProfileId=7 --jobCode=sci --businessDate=20260901 --attemptNo=1 --hiveDatabasePrefix=p201v2
# odl 追加： --landingDir=file:///…/tests/golden-dataset/events --sourceSystem=mock-mall --batchId=2026091201
# 第二套前缀 p201v2b 用 --sourceSystem=probe-inj-2026 重跑同一夹具（双通道对照）
# 回读：D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-sql.cmd --conf …(同上 5 条)… -f harness\e3-readback.sql
```
- 隔离（D-060①）：每次运行 `work = spark-jobs\target\p2-01-e3\<runId>`，harness 第 45-49 行**响亮断言**
  `work` 必须在 `spark-jobs\target\p2-01-e3` 之下、两个前缀都不得以 `dw` 开头、两前缀必须不同，否则 `throw` 中止。
- 夹具：`tests/golden-dataset/events/golden-20260901.jsonl`，`18,430 B / 55 行 /
  sha256 2351bcc35e04ccd278638f07247bc37e9c4d402cc4736cd2a4d4b70f4232b11c`（`E3_03` 现场复核一致）。
- 四个 `spark-submit` 全部 `exit=0`（`E3_01/E3_02`；B 套 `E3_05/E3_07`）。
  `odl` JobResult：`{"jobCode":"odl","inputRecords":55,"outputRecords":52,"rejectedRecords":3,…,
  "message":"accepted=52 rejectedVersionKeys=3 topics=4 sourceSystem=mock-mall"}`（`E3_10/E3_11/E3_12/E3_17`）。
- 最终 run：`runId=20260912-141554`，`E3_RESULT=PASS`，**51 个检查全 PASS、零 FAIL**，
  完整逐条读数见 `evidence/e3-20260912-141554-checks.tsv`（含 detail 原文；下表只摘关键行，长 detail 见 TSV）。

| 检查 | 读数 |
|---|---|
| `E3_13_sql_total_rows` | `A1_total_accepted=52` |
| `E3_14_sql_tables_4` | 4 表：`ods_behavior_event,ods_product_event,ods_trade_event,ods_user_event` |
| `E3_15_table_row_sum_52` | `user=4 product=14 behavior=16 trade=18`（和=52） |
| `E3_20_newcols_present` | `A3=raw_event_type,raw_source_system,landing_file,payload_json,payload_hash` |
| `E3_21/22/23/24` | `A4_newcol_nulls=0`；`A5_raw_event_type_mismatch=0`；`A6_landing_file=file:/…/golden-20260901.jsonl`；`A7_source_file_ne_landing_file=0` |
| `E3_30…E3_38` | `B0=52/52`（四表 ⋈ 源行）；`B1=0`；`B2=0`；`B3=0`；长度 `107/56/286`；`B5=52/52`；`B6=0`；`B7=4`；`B8=18` |
| `E3_40/41` | DESCRIBE 21 列：`event_id,event_type,event_time,ingest_time,source_system,schema_version,trace_id,payload_user_id,payload_age_group,payload_city_level,payload_member_level,payload_register_time,source_file,ingest_batch_id,raw_event_type,raw_source_system,landing_file,payload_json,payload_hash,dt,hour`；类型 `string×13,bigint` |
| `E3_42…E3_47` | `C1=0`；`C2=0`（独立 oracle `get_json_object`）；`C3=2026091201`；`C4 product/behavior/trade=0`；`C5=4/14/16/18`；`C7 user=0 trade=0`（v1 闭合 schema 投影 oracle） |
| `E3_50…E3_54` | `D1_probe_source_system=probe-inj-2026`；`D2_probe_raw_source_system=mock-mall`；`D3_main_source_pair=mock-mall#mock-mall`；探针库 JobResult 含 `sourceSystem=probe-inj-2026`；探针库同样 `55/52/3` |
| `E3_60/61/62/63` | 11 个库 = `default` + 2 前缀 × 5 层；`E1_dw_databases=0`；真仓库前后指纹同一；`E3_other_databases=0` |
| `E3_70` | 分区文件数 `2/8/4/6`（两套前缀各 4 表相同） |

**发现的真实缺陷（E3 的唯一目的就是发现它）**：`20260912-140109` 那轮，v1 `payload_*` 列**全为 NULL**，
而 `payload_json` 字节正确 ⇒ 存量消费方（DWD 读派生列）会**静默拿到空值**。

- 缺陷期原始读数 1：`evidence/e3-20260912-140109-05-readback.sql.log`
  `C1_v1_envelope_mismatch=0`、`B1_payload_ne_source_slice=0`（新列好）、**`C2_v1_payload_mismatch=4`**（user 表 4/4 行失配）。
- 缺陷期原始读数 2：`evidence/e3-probe-v1payload-diff.log`（对同一 warehouse 的只读探针）
  每行形如 `P_user  golden-evt-001  NULL  1  NULL  25-34  NULL  tier1  NULL  gold  NULL  2026-09-01T09:00:00+08:00`
  （ODS 列 NULL ↔ 源值 `1/25-34/tier1/gold/…` 在位），同批 `P_payload` 行显示 `payload_json` 是**完整正确的对象文本**。
- 根因探针（只读，`harness` 外的独立工件）：`evidence/e3-probe-payload-struct-null.sql` / `.log`，实测两条 Spark 语义：
  ① `from_json(value, '… landing_payload_text STRING')` 给**对象**时返回 **NULL**（PERMISSIVE），
  ② `from_json(get_json_object(value,'$.payload'), 'user_id STRING, age_group STRING')` 正确解析。
  ⇒ 前任实现里的"两段式解析"（先把 `payload` 对象取成 STRING 列、再二次 `from_json`）**在真实链上必然全 NULL**；
  而且它让**切片器**与 `landingSchema` 都成了 `payload_json` 的候选所有者。
- 修复（实现侧，`EventOdsLoadJob.scala` + `OdsLoadSql.scala`）：回到 v1 的**单次闭合 schema 解析**
  （`from_json(raw_line, landingSchema)` 一次拿到 v1 全部 `payload_*` 字段，`payload_json` 仍由 `JsonObjectSlicer`
  对 `raw_line` 做**偏移切片**产生，保持"切片器唯一所有者"），并删掉无人使用、语义错误的 `ColPayloadText` 常量。
  修复前后备份：`evidence/pre-fix3/EventOdsLoadJob.scala`（`8cd5be39…c342f04a`，9,389 B）、
  `evidence/pre-fix3/OdsLoadSql.scala`（`28724ad1…fe7aef3b`，14,829 B）。
- 修复后**复跑**：E2 run5 全绿（§2.2）+ E3 run6 全绿（本节）⇒ 缺陷闭环。

### 2.4 我自己工装/oracle 的缺陷与修正（**不是**断言放宽，逐条披露）

1. **E2 abort #1（JDK17）**：`IllegalAccessError … sun.nio.ch.DirectBuffer`。修法 = CLI
   `-DargLine="-Dfile.encoding=UTF-8 --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"`（`pom.xml` 字节未动，见 §1.5）。
2. **E2 abort #2（真 Hive 建表）**：`[PARSE_SYNTAX_ERROR] … at or near 'USING'`。根因 = `LocalSchemaInitJob.odsCreateTable`
   把 `USING` 子句放错位置（先 `PARTITIONED BY` 后 `USING`）。用真 `spark-sql` 做了**正反对照**
   （`harness/e3-probe-ddl-clause-order.ps1`）后改为正确子句顺序；属**实现侧**修复。
3. **E2 run3 两个 suite ABORT**：`[LOCATION_ALREADY_EXISTS] … dw_edge_ods.ods_user_event`。根因 = 测试落地区路径跨轮复用。
   修法 = `P2TestSupport` 每次运行使用唯一 `runId` 仓库根；属**测试支撑**修复（断言未动）。
4. **E3 run2**：回读 SQL `[PARSE_SYNTAX_ERROR] Syntax error at or near 'SELECT'.(line 92, pos 0)` ⇒ 缺 `;`
   （spark-sql `-f` 的语句分隔符规则是"行尾 `;`"）；已记入 `harness/e3-readback.sql` 头部说明。
5. **E3 run3**：回读 `exit=0` 但 harness 解析出 **0 行** ⇒ 我原先按 `|` 分隔假设解析；非交互 `spark-sql` 输出实为 **TAB 分隔**且每个语句带表头。
   修法 = TAB 感知解析 + 跳过表头单元（`marker`/`chk`/`col_name`/`namespace`）+ 大小写敏感键正则。
6. **E3 run4 四处工装缺陷**（全部判决为**工装侧**，实现未动）：
   - 回读 `exit=1`、`[UNRESOLVED_COLUMN.WITH_SUGGESTION] … 'landing_file' … line 19 pos 30` ⇒ `A6` 读了一个没有该列的视图，改为直接读 `p201v2_ods.ods_user_event`；
   - `E3_40`/`E3_61`/`E3_63` 红的原因是**表头单元被当成数据** ⇒ 补表头过滤；
   - `A1/B0 = 54` 与 `52` 不一致 ⇒ 实测夹具属性：55 源行中 53 行带 `event_id`、**52 个不同** `event_id`、
     **1 个 `event_id` 在源里出现 2 次**（故 ODS 侧同 id 行数>1 的组有 2 个）⇒ 回读 SQL 引入按 `event_id` 规范化的 `src` 视图，
     并把 `A10–A14` 记为**夹具属性读数**（`E3_49` 为信息性检查，恒 PASS，只打印读数）；
   - `C1=2` 与 `C7_user=4`/`C7_trade=18` ⇒ `C1` 改为**指纹式**"任一源行匹配"（更严：不再假设唯一行），
     `C7` 的 oracle 输入由**整行**改为 `get_json_object(value,'$.payload')`（正是 §2.3 实测的那条语义）。
7. **harness/oracle 的字面量修正**（同一事实、非放宽）：`E3_52` 期望串的分隔符 `|`→`#`（SQL 用 `concat(a,'#',b)`）；
   `E2_prefix_databases` 期望 `2`→`10`（2 前缀 × 5 层）并加 `_` 前缀锚定，同时**新增** `E3_63_no_unexpected_databases`
   做"除 `default` 与本任务两前缀外零库"的独立断言；`DESCRIBE` 解析在 `# Partition Information` 处停止（否则分区列会被重复计入 v1 列序）；
   `E3_16` 的取值语义与 D-056 双通道无关，替换为 `F3_probe_dual_channel_ok=52/52`（探针库两通道各归其位），
   并由 `E3_50/E3_51/E3_53` 三点独立钉住。

### 2.5 断言归属裁定（红 → 判决 → 去向；**没有一条是为迁就实现而放宽的**）

| 红（原始失败消息，逐字） | 位置 | 判决 | 去向 |
|---|---|---|---|
| `Right(""not-an-object"") was not equal to Left("PAYLOAD_NOT_OBJECT")` | `OdsV2EdgeCaseSpec.scala:86` | **实现侧**（D-054 要求 `payload_json` 是**对象**原文，非对象必须 fail-closed） | 修 `JsonObjectSlicer.scala`（80+/31−，新增 8 类错误分支） |
| `Right("{"a":1}") was not equal to Left("PAYLOAD_UNBALANCED_OBJECT")` | `JsonObjectSlicerSpec.scala:40`（同案 `OdsV2ByteFidelitySpec.scala:58`） | **实现侧** | 同上（该 spec 与 ByteFidelity spec 的**断言文本零改动**，diff 可证） |
| `Left("PAYLOAD_KEY_NESTED") was not equal to Left("PAYLOAD_COLON_MISSING")` | `JsonObjectSlicerSpec.scala:67` | **实现侧**（错误分类顺序错） | 同上 |
| `2 was not equal to 1` | `OdsV2EdgeCaseSpec.scala:123`（A6b"同一 payload 的 hash 相同但两行都在"） | **实现侧**（同一 payload 切出的 hash 不一致；hash 只做诊断、**不得**参与去重） | 修实现（该断言文本未改，见 `pre-fix2` 与现文件 diff 无该 hunk） |
| `Array("edge-1"…) was not equal to Array(… "edge-5")` | `OdsV2EdgeCaseSpec.scala:98` | **测试侧**（`edge-5` 是"整行少一个右括号"的**非法 JSON 行**，既有 `valid` 闸本来就拒；与夹具第 53 行同通道，`OdsV2ByteFidelitySpec A12c` 的 55/52/3 是独立同证） | 修测试：钉"4 行入库 + 残缺行走既有闸拒绝"，并保留 A5c 纯函数断言钉"值括号不配平 ⇒ 切不出 payload"；方向为**收紧** |
| `IllegalArgumentException: DDL 未解析到表 ods_user_event`（A11/A11b/A11c/A12 四条） | `OdsV2SchemaOwnerSpec.scala`（静态 DDL 解析器） | **测试侧**（正则不认 `db.table` 限定名，与本对象自己的 KDoc 自相矛盾） | 修正则 + `bareTable()`，断言零改动 |
| `"…" was not equal to …`（`_metadata.file_path as source_file`） | `SqlTemplateSpec.scala:47-48` | **测试侧**（模板 `FROM` 是无 `_metadata` 的临时视图） | 修测试并**追加**禁止回退断言（收紧），值级事实移到 E3 `A6/A7` |

`OdsV2EdgeCaseSpec` 中"坏 payload 不丢行"的正面证据（A6 第二段）在改动后**保留且更强**：
`edge-2/edge-3` 两行 payload 双 NULL 但其余列 `=user_registered`（行没丢），`edge-1/edge-4` 两行真的有值（防"一律 NULL 假绿"）。

---

## 3. 与裁决/契约的点对点映射（D-052…D-060）

| 裁决 | 要求（要点） | 实现位置 | 证据（实测） | 状态 |
|---|---|---|---|---|
| **D-052** | ODS v2 = 公共列 + 主题 payload 列 + 审计列；三部分都有可退出证据 | `OdsV2Columns.scala`（单一所有者，13 公共列 + 主题列 + 审计列）；`LocalSchemaInitJob`（派生 DDL）；`warehouse/ddl/00-ods.sql`（静态 DDL） | `A3/A4/E3_40/E3_41`（列集与列序）、`A1=52/A2=4`、`F1=4/14/16/18`、`B0=52/52` | ✅ |
| **D-053** | `event_time` 保持 STRING（不因 v2 改类型） | `OdsV2Columns.V1Shared`（类型位串） | `E3_41_v1_types=string×13,bigint`（`event_time` 第 3 位 = `string`） | ✅ |
| **D-054** | 加法**双写**：v1 `payload_*` 与 v2 `payload_json` 并存；`payload_json` 唯一所有者＝切片器；`payload_hash=SHA-256(UTF-8(payload_json))` 小写十六进制、**只做诊断不参与去重** | `JsonObjectSlicer.scala`（唯一所有者）；`OdsLoadSql`/`EventOdsLoadJob`（双写） | `B1=0`（52/52 字节相同）、`B2=0`、`B3=0`（源侧独立 oracle）、`B6=0`（小写 hex64）、`C2=0`（v1 侧同源值，双写互不覆盖）、A6b（同 payload 两行都在） | ✅ |
| **D-055** | 业务键定义权归契约；**本轮不改 DWD 去重键**；混源守卫在册 | 本轮未触碰 `DwdSql.scala`（零写入） | `git status` 无 `DwdSql.scala`；E3 只跑到 ODS | ✅（未越界） |
| **D-056** | `source_system` 只能来自**平台注入通道**（`--sourceSystem`），**不得**信行内值；行内值只保留在 `raw_source_system`；缺省/空值 fail-closed | `OdsLoadSql.ArgSourceSystem` + 模板第二参；`EventOdsLoadJob` 取参数 | `D1=probe-inj-2026`（注入值）／`D2=mock-mall`（行内值）／`D3=mock-mall#mock-mall`（主库两通道）／探针库 JobResult `sourceSystem=probe-inj-2026`；`F3=52/52`、`F4=52/52` | ✅ |
| **D-057** | `items` 只登记不改表示；新增 `landing_file`；`source_file` 必须改真实值（杀掉常量 `'landing'`） | `OdsLoadSql.itemsArrayType` 保留；`EventOdsLoadJob` 由 `_metadata.file_path` 投影 `landing_file` 并透传为 `source_file` | `A6=file:/…/golden-20260901.jsonl`、`A7=0`（`source_file == landing_file`）、`C5_trade_items_ods` 原始数组文本读数、`SqlTemplateSpec` 已钉"不许回退到 `_metadata`" | ✅ |
| **D-058** | 只在**现有单源库**上做加法扩列，不建 per-source 表/库 | 前缀仍由 `--hiveDatabasePrefix` 运行期注入；未新增 per-source 结构 | `E3_60/E3_61/E3_63`（11 库 = default + 2 前缀 × 5 层，零意外库，零 `dw_*`） | ✅ |
| **D-059** | v2 新增列**末尾追加**；v1 十四列名字与物理序号**冻结**；① 列序单一所有者注释 ② 一条**机械结构断言** | `OdsV2Columns.scala`（注释写明"末尾追加、单一所有者"）；`OdsV2SchemaOwnerSpec A11b`（与改造前快照逐位比对）；`OdsV2SqlContractSpec` | `E3_40`（前 14 列 + 新列 5 个紧随 `ingest_batch_id`）、`E3_41`、`A11b` 绿 | ✅ |
| **D-060** | 端到端只走**真 `spark-submit`**；**禁止**改 pom；① 临时 warehouse/metastore 必须在 `target\` 下并**响亮断言** ② 在产 jar 冻结/还原三点核对 ③ 每轮全量命令行+conf+stdout/stderr+退出码落盘 ④ 证据级别标 E3 | `harness/e3-real-chain.ps1`（第 45-49 行隔离断言；`jar cf` 独立路径；每轮 5 个日志全量落盘） | §2.3 全文；`E3_62`（真仓库指纹前后同一）；冻结 jar 三点核对见 §1.5；`pom.xml` 字节未变 | ✅ |

**契约侧未做（D-055/§8 CT 清单）**：CT-1/CT-2/CT-3 属契约变更批次（`VERSION 1.3.0 → 1.4.0`），
本任务按边界**未触碰** `contract-specs/**` 与 `docs/contracts/**`；因此**不得声称**"业务键已按 source namespace 冻结"或"payload 保真已闭环到 DWD"。

---

## 4. 未取证 / 未做（明确列出，不用"应该"顶替）

1. **E1 未覆盖最终修复态**：最终代码态的 `mvn package` 未重跑（重跑会覆盖冻结的在产 jar）。
   最终态的编译证据来自 `mvn test` 的 compile 阶段与 `target\classes` 内容指纹，**不是**一次独立的 package 运行。
2. **jar 的 sha256 不可复现**：zip 时间戳导致每次 `jar cf`/`mvn package` 的 jar 字节不同
   （E3 run6 = `2ac3b651…`，run5 与 run4 各不同）⇒ 可复现指纹用 `classesContentSha256`。
3. **无 v1 基线复跑**：没有在 v1 代码态跑同一夹具，因此**历史 v1 的 `payload_items` 取值**未被实测
   （本轮只有交付态的实测值：`C5_trade_items_ods` 的数组文本）。
4. **下游未跑**：DWD/DIM/DWS/ADS 零执行 ⇒ 本轮不能声称"派生列消费方已恢复正常"，只能声称 ODS 侧 v1 列已恢复有值。
5. **跨模块缺口（需平台泳道处理）**：E3 是**我在 CLI 上传** `--sourceSystem=mock-mall` 才通的；
   平台侧是否已把该参数注入 `spark-submit`，本任务**未验证**（只读看到 P2-07 泳道在改 `JobCommandBuilder`，
   未做任何写入、也未做透传实测）。⇒ D-056 的"平台注入通道"在真实作业链路里的落地点仍是**未取证**项。
6. **`P2TestSupport.scala` 的改造前 sha256 未留档**（该文件是新增文件，但我在本泳道内又改过一次；
   改造前快照只在 `git status` 层面可追溯，未单独落 sha256）。
7. **`--add-opens` 的等价替代**（`MAVEN_OPTS`/`surefire.argLine` 注入）未逐一实测；
   本任务只用 CLI `-DargLine` 这一条路径取得绿证据。
8. **`input_file_name()`**：D-060 已记"同读法下返回空串"，本任务据此走 `_metadata.file_path`，
   未再重复该对照实验（沿用总控的只读事实）。

---

## 5. 反熵声明（遗留物清单；**未删任何文件**）

本任务遵守"不删除"约束，下列冗余/遗留物**故意保留并在此登记**，供总控裁决：

**代码层**

1. `JsonObjectSlicer.slicePayloadValue`：无人调用的别名入口（保留，避免动已绿的契约面）。
2. `JsonObjectSlicer.sliceString`：**已删除**（唯一所有者收敛）。—— 这是本任务唯一的"删除"，
   删除动作发生在**新增文件内部**（`pre-fix2/JsonObjectSlicer.scala` 留档可比对），不涉及任何既有文件/证据。
3. `EventOdsLoadJob.sliceColumns`：与 `JsonObjectSlicer` 重复的 UDF 所有者，现无调用方（保留待裁决）。
4. `OdsLoadSql.landingPayloadStruct`：修复后**无调用方**（保留：它是 v1 语义的存档，且被 KDoc 引用）。
5. `EventLandingSchema` 类型：仅为 KDoc 引用而 import（保留）。
6. `OdsLoadSql.itemsArrayType`：按 D-057 明确"保留现状"。
7. `OdsV2Columns.CommonColumns` 的 KDoc 写"12 列"而实际 13 条（D-052 的"12 列"是**逻辑分组**叙述，
   物理列集含审计列）⇒ 文档漂移，已在代码注释与 §3 记录，未擅自改注释措辞（避免与裁决文字打架）。

**证据层（全部保留）**：`evidence/pre-fix/`、`evidence/pre-fix2/`、`evidence/pre-fix3/`（修复前快照）；
`evidence/e3-probe-*`（探针 SQL+日志，只读）；`evidence/e3-iter-readback*.log`（回读 SQL 迭代）；
`evidence/e3-20260912-135558/135810/140109/140902/141345-*`（含失败轮次的**全部**原始日志与 TSV）；
`evidence/e2-*`（含 run2/run3 的失败轮）；`spark-jobs\target\p2-01-*`（基线、jar、E3 每轮工作区）。

**工作树杂项**

8. 仓库根下 `.p2-01-tmp-plugin.xml`（我用于探针的一次性 Maven 临时文件，**保留**并在此声明；未删）。
9. `spark-jobs\target\surefire-reports\` 里的 `P2ProbeSpec`/`P2Probe2Spec` 陈旧 XML：前任泳道的探针残留（未删）。
10. `P2TestSupport` 的临时仓库根写在 `D:/Develop/tmp` 下（模块外临时目录，非仓库内；未清理）。

---

## 6. 停止条件、权威冲突与边界声明

### 6.1 已满足的停止条件

- E1（编译打包）✅、E2（100/100，含既有 62）✅、E3（真链 51/51 PASS）✅；
- 三份交付物齐备：本报告 + `evidence/p2-01-change-manifest.tsv` + `evidence/p2-01-evidence-manifest.tsv`（73 文件指纹）；
- 冻结物未被污染：在产 jar 三点核对一致、真 `spark-warehouse` 指纹前后同一、未碰任何 `dw_*` 库。

### 6.2 权威冲突（**须由总控裁决，我不自行修改任何权威文件**）

1. **E3 是否在本泳道范围内**：`ORDER-1.md` §1/§2 把本泳道级别定为 E1+E2，并把"真实链/E3/E4"列在**不做**项；
   而续跑指令与 `RULINGS.md:197`（D-060）明确要求端到端断言走真 `spark-submit`。
   本泳道的处置：**按 D-060 执行 E3**，并严格满足其四条硬条件，同时在证据级别上标注 E3（**不是** T2 权威 55 条链复跑，后者归 M1-11）。
2. **`--add-opens` 放在哪里**：任务文字提到改 `spark-jobs/pom.xml`，`RULINGS.md:198`（D-060 禁止项）禁止为此改 pom 依赖。
   本泳道的处置：pom **字节未动**，`--add-opens` 以 CLI `-DargLine` 逐次传入。
3. **日志落盘位置**：`ORDER-1.md:70` 要求把绿证据放进 `graduation-lane-backup\p2-01\`（对本泳道**只读**）。
   本泳道的处置：全部日志落在 `docs/acceptance/p2-01-ods-v2-20260912/evidence/`，并在本报告登记该偏离。
   R1(c) 的"构建产物副本"位置同样偏离为 `spark-jobs\target\p2-01-built\`（模块内），一并登记。
4. **权威沉默处的 fail-closed 处置**（本泳道自行裁定并留证，若总控另有裁决以总控为准）：
   非对象 payload ⇒ `PAYLOAD_NOT_OBJECT` ⇒ 双 NULL；整行 JSON 残缺 ⇒ 走既有 `valid` 闸拒绝（不进 ODS）；
   `{"payload":}` ⇒ `VALUE_MISSING`；缺冒号 ⇒ `COLON_MISSING`；未终止字符串 ⇒ `UNTERMINATED_STRING`；
   字符串内出现转义的 `"payload"` ⇒ `KEY_NESTED`。

### 6.3 边界与协议声明

- **未做任何 git 写操作**（无 `add/commit/checkout/restore/clean`），提交留给总控。
- **未使用任何删除命令**（`Remove-Item`/`rm`/`del`/`ri`/`git clean`/`git rm`/`mvn clean`/`robocopy /MIR` 全零使用）。
- **未停/未启 8090/8091/8092**；未触碰 `analytics-server/**`、`synthetic-data-generator/**`、看板、决策记录、`RULINGS.md`。
- **未把"应该"当"实测"**：本报告每条结论都指向原始日志/TSV 的具体行或检查名；E2（in-memory）与 E3（真链）的证据级别已逐处标注，未互相冒充。
- Aegis `long-task-continuation` 协议：本泳道为 Planless Slice Lane，**未创建** `docs/aegis/work/**`（越界），
  本报告即该泳道的持久工作记录；`docs/acceptance/p2-01-ods-v2-20260912/` 之外的记录未新增。
