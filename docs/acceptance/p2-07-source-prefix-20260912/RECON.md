# P2-07 源级数仓前缀所有权迁移 —— 只读取证报告（RECON）

> **泳道性质**：只读取证（**不写任何生产代码、不跑 Maven、不启停任何进程、DB 只读 SELECT**）。
> **唯一写入物**：本文件 `docs/acceptance/p2-07-source-prefix-20260912/RECON.md`（本目录由本泳道新建）。
> **派发依据**：`docs/项目实施进度与任务看板 V2.2.md:437`（末行）——「本轮据此转入 **M2 的 P2-07（源级数仓前缀所有权）**：先开只读取证泳道（出口 `docs/acceptance/p2-07-source-prefix-20260912/RECON.md`），**契约变更决策与 V18 号位在取证之后再下**（迁移号只由总控分配；真库最高版本实测 17 ⇒ 下号为 V18）」。
> **任务在册行**：`docs/项目实施进度与任务看板 V2.2.md:226`（P2-07，`TODO`，泳道 B，前置 P1-03）。
> **读窗口**：`2026-09-12 13:06:35.116`（早）→ `2026-09-12 13:09:30.221`（晚），**全部取证命令均在此窗口内执行**。
> **分支**：`remediation/r1-boundary`。
>
> **本文纪律**：只写**实测**事实。凡属推断一律显式标注 `推断（未取证）` 并附依据；凡 grep 命中一律给 `path:line`；凡结论依赖的文件给 `sha256` 前 16 位与 `mtime`；无输出即写「（无输出）」，不含任何「应该没问题」类措辞。

---

## 0. 一句话结论（实测）

**「源级数仓前缀」在今天的代码、数据库、契约三层里都不存在任何载体**：

1. **代码层**：`WarehouseNamespace`（Java 与 Scala 两份）的**唯一生产读取点**是 `runtime_profile` 派生的快照 —— `JobCommandBuilder.java:59` 读 `profile.hiveDatabasePrefix()`；`source_registry` 侧 `…/source/` 包内 `git grep -E "WarehouseNamespace|warehousePrefix|hive_database_prefix|hiveDatabasePrefix|validationError"` **零命中**（退出码 1）。
2. **数据库层**：`information_schema.COLUMNS WHERE column_name LIKE '%prefix%'` 全库**只命中 `runtime_profile.hive_database_prefix`**（5 个 schema，含 4 个测试 schema），`source_registry` 11 列里**没有**任何前缀列（实测列清单见 §3.1）；真库 `runtime_profile.hive_database_prefix = NULL`（ACTIVE 唯一行 id=1）。
3. **契约层**：`contract-specs/specs/warehouse-namespace.v1.json:30` 的 `sourceOfTruth` 明写「后续由 `source_registry` 的源级配置接管（P2）」——**即冻结契约今天仍然把 `runtime_profile` 定为事实来源，源级接管是"待办"而非"已实现"**。

⇒ **P2-07 的出口证据（四类错误码真实拒绝 + 源 B/源 A 落不同库）今天一条都不成立**，且**没有可复用的现成载体**：需要新迁移（号位 V18，**只能由总控分配**）、新列、新 DTO 字段、新校验调用点与既有断言改写（`docs/acceptance/p1-03-source-registry-api-20260911/e3-verify.ps1:96` 现有一条**反向**断言，见 §3.2）。

---

## 1. 证据卫生

### 1.1 窗口前快照（early）

```powershell
Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"; git rev-parse HEAD; git rev-parse --abbrev-ref HEAD; git status --porcelain
```

```text
2026-09-12 13:06:35.116
84de1792654bf8bfe542fd91b65707df677bef42
remediation/r1-boundary
 M docs/acceptance/p1-05-8091-swap-20260911/8091-stdout.log
 M spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala
 M spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala
 M spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala
 M spark-jobs/src/test/scala/com/graduation/analytics/SqlTemplateSpec.scala
 M warehouse/ddl/00-ods.sql
?? docs/acceptance/m1-9-second-adapter-20260912/evidence/
?? docs/acceptance/p2-03-surrogate-key-20260912/
?? spark-jobs/src/main/scala/com/graduation/analytics/sql/JsonObjectSlicer.scala
?? spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsV2Columns.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/OdsV2ByteFidelitySpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/OdsV2EdgeCaseSpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/OdsV2SchemaOwnerSpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/OdsV2SqlContractSpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/P2Probe2Spec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/P2ProbeSpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/P2TestSupport.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/sql/
```

### 1.2 窗口后快照（late）

```powershell
Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"; git rev-parse HEAD; git status --porcelain
```

```text
2026-09-12 13:08:26.639
d3fd74edb72844a8c917e1134991fcc5fe3adf4b
 M docs/acceptance/p1-05-8091-swap-20260911/8091-stdout.log
 M spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala
 M spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala
 M spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala
 M spark-jobs/src/test/scala/com/graduation/analytics/IdCodecSpec.scala
 M spark-jobs/src/test/scala/com/graduation/analytics/SqlTemplateSpec.scala
 M warehouse/ddl/00-ods.sql
?? docs/acceptance/m1-9-second-adapter-20260912/evidence/
?? docs/acceptance/p2-03-surrogate-key-20260912/
?? spark-jobs/src/main/scala/com/graduation/analytics/sql/JsonObjectSlicer.scala
?? spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsV2Columns.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/OdsV2ByteFidelitySpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/OdsV2EdgeCaseSpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/OdsV2SchemaOwnerSpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/OdsV2SqlContractSpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/P2Probe2Spec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/P2ProbeSpec.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/P2TestSupport.scala
?? spark-jobs/src/test/scala/com/graduation/analytics/sql/
```

收尾时间戳复核：

```text
NOW=2026-09-12 13:09:30.221
HEAD=d3fd74edb72844a8c917e1134991fcc5fe3adf4b
BRANCH=remediation/r1-boundary
KANBAN=2026-09-12 13:07:47 E10ED4F8EE413EA6 199115
LSIJ=2026-09-12 13:01:06 4C5327204768B1F0 18324
DIR-P2-07-EXISTS=False
```

### 1.3 窗口内发生的真实变化（**必须记账**）

| 事实 | 值 | 影响 |
|---|---|---|
| **HEAD 前进 1 次** | `84de179…` → `d3fd74e…` | 并发写者（总控）在我取证期间提交。提交信息：`d3fd74e M1/P1 状态复核（实测闭合两处过期措辞：真库 V17 已在 Flyway 历史、P1-05 代码在 HEAD）＋ M1 里程碑补记；转 M2 P2-07 只读取证`，时间 `2026-09-12 13:07:48 +0800` |
| **工作树新增 1 个 ` M`** | `spark-jobs/src/test/scala/com/graduation/analytics/IdCodecSpec.scala` | 属 **P2-01 实施泳道**的活动文件，与 P2-07 无关；本轮**未引用**该文件 |
| **`docs/项目实施进度与任务看板 V2.2.md` 在窗口内被改写** | early `13:04:37 / 4565CA08FED86A06 / 195641 B` → late `13:07:47 / E10ED4F8EE413EA6 / 199115 B` | ⇒ 本报告对该文件的**全部引用已按新版本重新取证**（下 §1.4）；行号 `226`/`248`/`437` 在新版本下复核一致 |
| `spark-jobs/…/LocalSchemaInitJob.scala` | mtime `13:01:06`（窗口**前**）`4C5327204768B1F0`，窗口内**未变** | 但它是**未提交的工作树改动**（` M`，属 P2-01 泳道），见 §4「未取证」 |

工作树未提交改动规模（实测）：

```text
git diff --stat HEAD
 .../p1-05-8091-swap-20260911/8091-stdout.log       |  34 +++
 .../graduation/analytics/job/EventOdsLoadJob.scala | 108 +++++++--
 .../analytics/job/LocalSchemaInitJob.scala         | 113 ++++++---
 .../com/graduation/analytics/sql/OdsLoadSql.scala  | 269 +++++++++++++--------
 .../com/graduation/analytics/IdCodecSpec.scala     |   4 +-
 .../com/graduation/analytics/SqlTemplateSpec.scala |  19 +-
 warehouse/ddl/00-ods.sql                           |  35 ++-
 7 files changed, 412 insertions(+), 170 deletions(-)
（`??` 未跟踪项 19 条）
```

### 1.4 本报告引用文件的指纹（除看板外**均窗口内实测稳定**）

采集命令（对每个路径）：`(Get-Item $f).LastWriteTime` + `(Get-FileHash $f -Algorithm SHA256).Hash.Substring(0,16)` + `Length`。

| 文件 | mtime | sha256(16) | B |
|---|---|---|---|
| `contract-specs/specs/warehouse-namespace.v1.json` | 2026-09-11 20:06:44 | `463D9DC3503D563D` | 5238 |
| `contract-specs/README.md` | 2026-09-12 12:50:34 | `478E526EA044CC4B` | 38515 |
| `contract-specs/VERSION` | 2026-09-12 11:39:01 | `B6BAB8E0547C6BC0` | 21 |
| `analytics-server/platform-common/…/warehouse/WarehouseNamespace.java` | 2026-09-11 17:55:17 | `9C6BBB78077CD141` | 6316 |
| `analytics-server/platform-common/…/warehouse/WarehouseNamespaceProvider.java` | 2026-09-11 17:55:21 | `D3131FCD604761D2` | 995 |
| `analytics-server/connection-ingestion/…/runtime/ActiveProfileWarehouseNamespaceProvider.java` | 2026-09-11 17:55:26 | `C9236270CD73779D` | 1291 |
| `analytics-server/connection-ingestion/…/runtime/RuntimeProfileSnapshot.java` | 2026-09-10 15:20:51 | `F2ADE5E6EBB9A9FE` | 1980 |
| `analytics-server/connection-ingestion/…/runtime/RuntimeProfileServiceImpl.java` | 2026-09-11 16:01:40 | `4EDA7444B8204EF2` | 12843 |
| `analytics-server/warehouse-pipeline/…/pipeline/spark/JobCommandBuilder.java` | 2026-09-11 17:56:07 | `68FF7E49B33E68D4` | 6110 |
| `analytics-server/warehouse-pipeline/…/pipeline/spark/SparkStageExecutor.java` | 2026-09-10 20:17:19 | `97D782922D3C06C4` | 12186 |
| `analytics-server/warehouse-pipeline/…/pipeline/PipelineService.java` | 2026-09-11 14:08:48 | `76386EB9FAF2AB7C` | 63431 |
| `analytics-server/connection-ingestion/…/source/SourceRegistryServiceImpl.java` | 2026-09-11 20:48:38 | `30B84E803792681E` | 20592 |
| `analytics-server/connection-ingestion/…/source/entity/SourceRegistry.java` | 2026-09-11 20:13:27 | `AD3EF74851C9C6F4` | 4249 |
| `analytics-server/connection-ingestion/…/source/dto/SourceRegistryCreateReq.java` | 2026-09-11 20:13:45 | `3877F430968CE629` | 917 |
| `analytics-server/connection-ingestion/…/source/dto/SourceRegistryUpdateReq.java` | 2026-09-11 20:13:49 | `28260360F0611836` | 1011 |
| `analytics-server/connection-ingestion/…/source/dto/SourceRegistryView.java` | 2026-09-11 20:13:41 | `1916D3C2C1336A64` | 1734 |
| `analytics-server/platform-app/…/controller/SourceRegistryController.java` | 2026-09-11 20:51:04 | `E39DB5A497DAEB5A` | 9388 |
| `analytics-server/platform-app/src/main/resources/db/meta/V16__source_registry.sql` | 2026-09-11 19:56:17 | `D3E1C98D4AFC5B48` | 4512 |
| `analytics-server/platform-app/src/main/resources/db/meta/V17__source_dimension_for_checkpoint_and_batch.sql` | 2026-09-11 20:09:59 | `9B251B62BFE8EF53` | 4381 |
| `analytics-server/ai-decision/…/ai/evidence/EvidenceBuilder.java` | 2026-09-11 17:59:52 | `C38547CBAADCFE4C` | 21862 |
| `analytics-server/ai-decision/…/ai/evidence/MetricLineage.java` | 2026-09-11 17:59:12 | `3C8237477B315E60` | 4922 |
| `analytics-server/platform-common/src/test/…/warehouse/WarehouseNamespaceContractTest.java` | 2026-09-11 18:01:03 | `5F40A35AF4A05039` | 10373 |
| `analytics-server/platform-common/src/test/…/warehouse/WarehouseNameLiteralGateTest.java` | 2026-09-11 18:08:53 | `74169FCA3353B931` | 10161 |
| `analytics-server/warehouse-pipeline/src/test/…/spark/JobCommandBuilderTest.java` | 2026-09-11 18:22:40 | `816BA9451A9F044B` | 7850 |
| `spark-jobs/src/main/scala/…/warehouse/WarehouseNamespace.scala` | 2026-09-11 17:55:06 | `8D7B25BBDBD28865` | 5644 |
| `spark-jobs/src/main/scala/…/job/JobRunner.scala` | 2026-09-11 17:56:21 | `4E2868A6CE66B6FB` | 4407 |
| `spark-jobs/src/main/scala/…/job/JobArgs.scala` | 2026-09-06 12:04:10 | `91E30E2BBF08377A` | 1764 |
| `spark-jobs/src/main/scala/…/job/LocalSchemaInitJob.scala` | **2026-09-12 13:01:06** | `4C5327204768B1F0` | 18324 |
| `spark-jobs/src/main/scala/…/job/SparkSessionFactory.scala` | 2026-09-06 11:55:18 | `5D3595508B27E9B1` | 991 |
| `spark-jobs/src/test/scala/…/WarehouseNamespaceSpec.scala` | 2026-09-11 18:06:40 | `3E1280BFFF79A8A2` | 12968 |
| **`docs/项目实施进度与任务看板 V2.2.md`** | **2026-09-12 13:07:47** | **`E10ED4F8EE413EA6`** | **199115** |
| `docs/deployment.md` | 2026-09-11 18:07:19 | `7D98B57E679F5746` | 13975 |
| `docs/acceptance/p2-01-ods-v2-spec-draft-20260912/RULINGS.md` | 2026-09-12 12:46:20 | `60A14ACD60960A03` | 18967 |
| `docs/acceptance/p1-04-namespace-20260911/README.md` | 2026-09-11 20:14:21 | `311E1B1C2B3446ED` | 16551 |
| `docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md` | 2026-09-11 17:13:29 | `528E648E97E4F714` | 18114 |
| `docs/superpowers/specs/2026-09-11-ai-assisted-warehouse-onboarding-design-v1.0.md` | 2026-09-11 17:36:08 | `335CED315D017CF7` | 14875 |

**本泳道自身写入**：本文件（窗口内新建，路径在 late 快照时为 `DIR-P2-07-EXISTS=False`，即唯一写入发生在收尾之后）。除此之外**无任何文件被创建/修改/删除/移动**；**无 git 写操作**；**未运行 Maven**；**未启停任何进程**（8090/8091/8092 三个用户在场进程全程未触碰）。

---

## 2. 八问逐条回答

> 说明：以下 8 问按派发单顺序复述，**每题自含题干**，不依赖编号约定。

### 问 1：今天「库名前缀」的事实来源在哪？——把**所有**读取点（代码 / DDL / 脚本 / 文档 / 数据库）逐一点名。

**命令（全仓，含未跟踪外的一切已跟踪文件）：**

```powershell
git grep -n "hiveDatabasePrefix"
git grep -n "hive_database_prefix"
git grep -n "WAREHOUSE_PREFIX"
git grep -ln "WarehouseNamespace" -- "analytics-server/*/src/main/*" "spark-jobs/src/main/*"
```

**实测输出（生产代码部分，逐字）：**

`git grep -n "hiveDatabasePrefix"`（Java 生产代码命中 5 处，逐字）：

```text
analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/runtime/ActiveProfileWarehouseNamespaceProvider.java:28:                .map(RuntimeProfile::getHiveDatabasePrefix)
analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/runtime/RuntimeProfileSnapshot.java:20:        String hiveDatabasePrefix,
analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/runtime/RuntimeProfileSnapshot.java:39:                p.getHiveJdbcUrl(), p.getHiveDatabasePrefix(),
analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/runtime/entity/RuntimeProfile.java:39:    private String hiveDatabasePrefix;
analytics-server/platform-common/src/main/java/com/graduation/analytics/warehouse/WarehouseNamespace.java:50:    public static final String ARG_KEY = "hiveDatabasePrefix";
analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/spark/JobCommandBuilder.java:59:        WarehouseNamespace namespace = WarehouseNamespace.ofNullable(profile.hiveDatabasePrefix());
```

`git grep -ln "WarehouseNamespace"` 的**生产代码命中全集（25 个文件，逐字）**：

```text
analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/EvidenceBuilder.java
analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/MetricLineage.java
analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/runtime/ActiveProfileWarehouseNamespaceProvider.java
analytics-server/platform-common/src/main/java/com/graduation/analytics/warehouse/WarehouseNamespace.java
analytics-server/platform-common/src/main/java/com/graduation/analytics/warehouse/WarehouseNamespaceProvider.java
analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/spark/JobCommandBuilder.java
spark-jobs/src/main/scala/com/graduation/analytics/job/AdsPublishJob.scala
spark-jobs/src/main/scala/com/graduation/analytics/job/AdsQualityJob.scala
spark-jobs/src/main/scala/com/graduation/analytics/job/BehaviorDwdJob.scala
spark-jobs/src/main/scala/com/graduation/analytics/job/DimensionBuildJob.scala
spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala
spark-jobs/src/main/scala/com/graduation/analytics/job/FunnelAdsJob.scala
spark-jobs/src/main/scala/com/graduation/analytics/job/JobRunner.scala
spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala
spark-jobs/src/main/scala/com/graduation/analytics/job/MetricExportJob.scala
spark-jobs/src/main/scala/com/graduation/analytics/job/PartitionEvidence.scala
spark-jobs/src/main/scala/com/graduation/analytics/job/TradeDwdJob.scala
spark-jobs/src/main/scala/com/graduation/analytics/job/UserProductDwsJob.scala
spark-jobs/src/main/scala/com/graduation/analytics/metric/MetricAdsSpec.scala
spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala
spark-jobs/src/main/scala/com/graduation/analytics/sql/DimSql.scala
spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala
spark-jobs/src/main/scala/com/graduation/analytics/sql/DwsSql.scala
spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala
spark-jobs/src/main/scala/com/graduation/analytics/warehouse/WarehouseNamespace.scala
```

**结论（实测）：运行期前缀只有一条取值链**

```text
runtime_profile.hive_database_prefix (DB 列)
   ├─ ActiveProfileWarehouseNamespaceProvider.current()      ← 只读 ACTIVE profile
   │     └─ EvidenceBuilder.java:316  MetricLineage.hiveTables(..., namespaceProvider.current())   【第二读取端口，见问 8】
   └─ RuntimeProfileSnapshot.hiveDatabasePrefix (record)
         └─ JobCommandBuilder.java:59  WarehouseNamespace.ofNullable(profile.hiveDatabasePrefix())
               └─ JobCommandBuilder.java:97  cmd.add("--" + WarehouseNamespace.ARG_KEY + "=" + namespace.prefix())
                     └─ spark-jobs JobRunner.scala:25-30  WarehouseNamespace.parse(...) → 非法则 exit(64)
                           └─ 各作业 JobArgs.extra("hiveDatabasePrefix") → WarehouseNamespace.fromArgs(args)
```

**契约（逐字）**：`contract-specs/specs/warehouse-namespace.v1.json:30`

```json
"sourceOfTruth": "运行期取值来自 runtime_profile.hive_database_prefix（NULL/空串 → defaultPrefix）；后续由 source_registry 的源级配置接管（P2）"
```

**其它读取点（非运行期，但同属"前缀事实"的扩散面）**：

| 类别 | 位置 | 事实 |
|---|---|---|
| DDL（集群手工路径） | `warehouse/ddl/00-ods.sql:15,18,42,45,74,77,101,104,133`；`01-dwd.sql:8,11,28,31,55,58,68`；`02-dims.sql:6,8,20,22,38,40,52,54,61,63,74`；`03-dws.sql:6,9,20,23,37,40,51,54,63,66,76,79,89,92,100`；`04-ads.sql:7,10,23,26,34,37,43,46,58,61,69,72,80,83,92,95,102,105,121,124,134` | 库名一律写 `${WAREHOUSE_PREFIX}_<层>`，**与 Java/Scala 取值链是两条独立通道**（Hive 变量 vs spark-submit 参数） |
| DDL 用法文档 | `warehouse/README.md:47,51-55,58,64`；`docs/deployment.md:150-156` | `beeline --hivevar WAREHOUSE_PREFIX=dw -f …`；`deployment.md:154` 逐字：「说明：平台自身跑 INIT_SCHEMA 阶段时用 `runtime_profile.hive_database_prefix` 派生同一套库名（`LocalSchemaInitJob`）」 |
| 只读验收脚本 | `scripts/accept-p1-baseline.ps1:373,637` | 读 `hive_database_prefix` 并断言 NULL（P1-01 冻结基线口径） |
| 前端 | `grep` `*.{ts,js,vue,tsx,jsx,html}` 全仓 **0 命中** | 前端不感知前缀（**无前端改动面**） |
| 论文素材 | `docs/thesis-materials/thesis-outline.md:31` | 明写「前缀由 `runtime_profile.hive_database_prefix` 决定」⇒ **P2-07 落地后此处会过期** |
| 决策记录 | `docs/开发过程事实与决策记录.md:835,837,839,848,929,967`（该文件名含中文，`git grep` 输出为转义路径） | `:929` 逐字「**裁决 7（本任务不碰库名前缀）**：`hive_database_prefix` 仍为 `NULL`，V16 里不出现该列。库名前缀的唯一所有者是 P1-04 的 `WarehouseNamespace`；把"前缀生效"塞进 P1-02 会造出第二个 owner。」 |

### 问 2：`source_registry` 今天有没有前缀列？——数据库事实来源三连（列 / 行 / 迁移历史）

**命令：**

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' --default-character-set=utf8mb4 -uroot -p123456 -N -B -e "…"
```

**实测输出（逐字）：**

```text
### flyway max
17
### source_registry columns
1	id	bigint	NO	NULL
2	source_code	varchar(64)	NO	NULL
3	display_name	varchar(128)	NO	NULL
4	ingest_mode	varchar(16)	NO	NULL
5	profile_path	varchar(255)	NO	NULL
6	timezone	varchar(64)	NO	NULL
7	currency	char(3)	NO	NULL
8	status	varchar(16)	NO	NULL
9	profile_version	varchar(32)	NO	NULL
10	created_at	datetime(3)	NO	CURRENT_TIMESTAMP(3)
11	updated_at	datetime(3)	NO	CURRENT_TIMESTAMP(3)
### does any table have a warehouse prefix column?
analytics_meta	runtime_profile	hive_database_prefix
analytics_meta_v17probe	runtime_profile	hive_database_prefix
analytics_meta_p103	runtime_profile	hive_database_prefix
analytics_meta_p105	runtime_profile	hive_database_prefix
analytics_meta_p105it	runtime_profile	hive_database_prefix
### source_registry rows
1	mock-mall	ACTIVE	1.0
### runtime_profile prefix + source_id
1	local-dev	ACTIVE	3	1	NULL
```

（末尾一行 `mysql: [Warning] Using a password on the command line interface can be insecure.` 为客户端告警，无害。）

**由此实测确定的 5 条事实：**

1. **`source_registry` 11 列，无任何前缀列**；`information_schema` 全库扫描 `column_name LIKE '%prefix%'` **只命中 `runtime_profile.hive_database_prefix`**。
2. 真库 `flyway_schema_history` 最高版本 = **17**（⇒ 下一个可用号位 **V18**；**号位只能由总控分配**）。
3. `source_registry` 全表**只有 1 行**：`id=1 / source_code=mock-mall / status=ACTIVE / profile_version=1.0`。
4. `runtime_profile` **只有 1 行**：`id=1 / local-dev / ACTIVE / version=3 / source_id=1 / hive_database_prefix=NULL`。
5. 另有 4 个测试 schema（`analytics_meta_v17probe` / `_p103` / `_p105` / `_p105it`）同样只有 `runtime_profile` 带前缀列 ⇒ **任何"源级前缀列"的落点今天在所有 schema 里都不存在**。

**代码实体侧一致（实测）**：`analytics-server/connection-ingestion/…/source/entity/SourceRegistry.java`（`AD3EF74851C9C6F4`，89 行）的 11 个映射字段与上表列一一对应，**无前缀字段**；三个 DTO（`SourceRegistryCreateReq` `3877F430968CE629` / `SourceRegistryUpdateReq` `28260360F0611836` / `SourceRegistryView` `1916D3C2C1336A64`，12 个组件）**均无前缀字段**。

**迁移边界来源（V16 头部逐字引用）**：`analytics-server/platform-app/src/main/resources/db/meta/V16__source_registry.sql`（`D3E1C98D4AFC5B48`）明确**不激活库名前缀**（原文：「不激活库名前缀（库名前缀属 P1-04 边界）」）；`V17__source_dimension_for_checkpoint_and_batch.sql`（`9B251B62BFE8EF53`）另明写「当前活动源 = `runtime_profile(ACTIVE).source_id`」且**禁止**另造 `is_current` 列/单例表（会生成第二个所有者）。

### 问 3：若所有权迁到源级，改动面在哪？——按模块分组、逐文件

> 判定依据：以问 1 的**读取点全集**为起点做传递闭包（谁持有 `RuntimeProfileSnapshot`、谁构造它、谁校验、谁断言）。

**A. `analytics-server`（必须改）**

| # | 文件 | 现状（`path:line`） | 为何要动 |
|---|---|---|---|
| A1 | `platform-app/src/main/resources/db/meta/V18__*.sql` | **不存在**（最高 V17） | 新列唯一合法落点；**号位 V18 待总控分配** |
| A2 | `connection-ingestion/…/source/entity/SourceRegistry.java` | 11 字段，无前缀 | 承载源级前缀 |
| A3 | `connection-ingestion/…/source/dto/SourceRegistryCreateReq.java` | 无前缀 | 保存路径入参 |
| A4 | `connection-ingestion/…/source/dto/SourceRegistryUpdateReq.java` | 无前缀 | 编辑路径入参 |
| A5 | `connection-ingestion/…/source/dto/SourceRegistryView.java` | 12 组件，无前缀 | 读回/审计可见性 |
| A6 | `connection-ingestion/…/source/SourceRegistryServiceImpl.java` | `create` L88-129 / `update` L133+ / `activate` L246-265 **零个 `WarehouseNamespace` 引用** | ①② 校验挂载点（问 4） |
| A7 | `platform-common/…/warehouse/WarehouseNamespace.java` | `ARG_KEY="hiveDatabasePrefix"` L50；`validationError` L118-137；`of` L140-146；`ofNullable` L149-151 | 可能需**新增**"源级前缀 → 命名空间"的取用形式（若保留 `ofNullable` 语义可不动规则本体） |
| A8 | `platform-common/…/warehouse/WarehouseNamespaceProvider.java` | 单方法 `current()` L20；javadoc L14-15 **已自我声明**是 P2/P3 的替换点 | 端口签名需从"当前 ACTIVE profile"改为"按快照/源解析" |
| A9 | `connection-ingestion/…/runtime/ActiveProfileWarehouseNamespaceProvider.java` | `current()` = `profiles.findActive().map(RuntimeProfile::getHiveDatabasePrefix).map(WarehouseNamespace::ofNullable).orElseGet(WarehouseNamespace::defaultNamespace)`（L26-31） | 实现改为走 `source_registry` |
| A10 | `connection-ingestion/…/runtime/RuntimeProfileSnapshot.java` | record 含 `hiveDatabasePrefix`（L20），**不含 `sourceId`** | 提交路径要拿到源级前缀，快照必须携带源身份或已解析前缀 |
| A11 | `warehouse-pipeline/…/pipeline/spark/JobCommandBuilder.java` | L59 `WarehouseNamespace.ofNullable(profile.hiveDatabasePrefix())`；L97 注入 `--hiveDatabasePrefix=` | 注入点改取值来源（**参数名可保持不变**，见 §3.4 的串行化结论） |
| A12 | `warehouse-pipeline/…/pipeline/PipelineService.java` | L334-335 `RuntimeProfile profile = …get(run.getRuntimeProfileId()); RuntimeProfileSnapshot snapshot = RuntimeProfileSnapshot.from(profile);` —— **全程未读 `source_id`** | 快照构造处需带上源 |
| A13 | `ai-decision/…/ai/evidence/EvidenceBuilder.java` | L56 字段 `private final WarehouseNamespaceProvider namespaceProvider;`；L316 `MetricLineage.hiveTables(mysql, namespaceProvider.current())` | **第二读取端口**：血缘按快照、命名空间却取"当前 ACTIVE profile"⇒ 多所有者缺陷（问 8） |
| A14 | `connection-ingestion/…/runtime/entity/RuntimeProfile.java` | L39 `private String hiveDatabasePrefix;` | 迁移后该字段的**去留语义**必须由总控裁决（保留=双所有者；删除=破坏性变更） |
| A15 | `connection-ingestion/…/runtime/RuntimeProfileServiceImpl.java` | `create` L52-78 / `update` L81-99 / `activate` L215-251 **零前缀校验**（实测：该类无 `WarehouseNamespace` 引用） | 若 A14 保留字段，则此处也要补校验；否则需明确"profile 侧不再拥有前缀" |

**B. 测试（必须改或新增，实测现状）**

| # | 文件 | 现状 |
|---|---|---|
| B1 | `platform-common/src/test/…/WarehouseNamespaceContractTest.java`（`5F40A35AF4A05039`） | `constantsMatchSpec` L48-81 + `@TestFactory` 向量循环 L85+；断言 `SPEC.at("/parity/javaTest")` |
| B2 | `platform-common/src/test/…/WarehouseNameLiteralGateTest.java`（`74169FCA3353B931`） | `OWNER_FILES` = 2 个 `WarehouseNamespace*` 文件；`SCOPES` = `spark-jobs/src/main/`、`warehouse/ddl/`、`scripts/`、`analytics-server/*/src/main/`；`BARE_LITERAL = dw_(?:ods\|dwd\|dim\|dws\|ads)\b` |
| B3 | `warehouse-pipeline/src/test/…/spark/JobCommandBuilderTest.java`（`816BA9451A9F044B`） | `hiveDatabasePrefixComesFromProfileOrDefaultsToDw` L124-135（**方法名与断言都写死"来自 profile"**）；`invalidPrefixFailsBeforeSubmit` L137-147（6 个非法值 `dw_ods/DW/" dw"/dw__b/default/dw_` 断言 `WAREHOUSE_PREFIX_*`） |
| B4 | `spark-jobs/src/test/scala/…/WarehouseNamespaceSpec.scala`（`3E1280BFFF79A8A2`） | 62 用例；含 `fromArgs` 读 `--hiveDatabasePrefix`、`dw_b` 换前缀后 SQL 无 `dw_*` 残留 |
| B5 | `platform-app/src/test/…/source/` 下 6 个类 | `SourceRegistryMigrationScriptTest` / `SourceRegistryMigrationMySqlIT` / `SourceRegistryControllerAuditTest` / `SourceRegistryServiceTest` / `SourceRegistryConcurrencyTest` / `SourceRegistryTestSupport` —— **新增 V18 与新增校验都会命中 B5**（迁移门禁 + 审计摘要 + 服务用例） |
| B6 | **`docs/acceptance/p1-03-source-registry-api-20260911/e3-verify.ps1:96`** | 现有一条**反向断言**，逐字：`Check 'S12b 未写 hive_database_prefix（源级命名空间接管属 P2，本次未做）' (Has 'hive_database_prefix=<NULL>') 'S12b 行'` ⇒ **P2-07 落地后此断言必然变红**，属"落地即需同步改写的既有证据"，必须由总控决定是"改写并登记"还是"另立新证据目录" |

**C. `spark-jobs`（**与 P2-01 冲突区**，见 §3.5）**

- 实测生产命中：`WarehouseNamespace.scala:68,82-85,88,96-103,106-116,126-127`、`JobRunner.scala:25-30`、`JobArgs.scala`（所有 `--k=v` 落入 `extra: Map[String,String]`，**新参数零改动即可透传**）、以及 §2 问 1 列出的 17 个作业/SQL 模板文件。
- **关键实测结论（决定串行化范围）**：`JobArgs` 把任意 `--k=v` 收进 `extra`，因此**只要保持 `--hiveDatabasePrefix` 这一个参数名与"值为前缀字符串"的语义不变，`spark-jobs` 侧不需要任何改动**（`WarehouseNamespace.fromArgs(JobArgs)` 已经在读它）。
- 但 `spark-jobs/**` 当前**正被 P2-01 实施泳道写入**（`git diff --stat HEAD`：`EventOdsLoadJob.scala +108/-…`、`LocalSchemaInitJob.scala +113/-…`、`OdsLoadSql.scala +269/-…`、`SqlTemplateSpec.scala`、`IdCodecSpec.scala`，另有 10 个 `??` 新文件）⇒ **任何 `spark-jobs/**` 改动都必须与 P2-01 串行，不得并行**。

**D. `contract-specs`（要不要动由总控裁决）**

- `contract-specs/specs/warehouse-namespace.v1.json:30` 的 `sourceOfTruth` 是**已冻结**条款（`status: "FROZEN-2026-09-11"`，`owner: "P1-04（数仓库名单点所有者）"`）；`:33` 为 `compatibility.note`；共 **22 条向量**（L37-73）。
- `contract-specs/README.md:42` §3 版本规则逐字：「**版本号**：沿用 `event-contract.md` L4 的自身规则——新增字段 → `schema_version` 升 `1.1` 起；破坏性变更 → 新主版本并增加转换器。… **不原地改语义**。目录级版本见 `VERSION`：**加法变更 → minor 递增**（`1.0.0 → 1.1.0 → 1.2.0 …`）；破坏性变更 → major 递增（`2.0.0`）并新建 `v2` 文件。」
- `contract-specs/VERSION` 现为 `contract-specs 1.3.0`（21 B，`B6BAB8E0547C6BC0`）。
- ⇒ **`sourceOfTruth` 由 `runtime_profile` 改为 `source_registry` 属"改语义"**（不是加法），按上引规则**不能原地改**：要么出 `v2` 文件（破坏性），要么由总控另行裁决豁免路径。**这是 P2-07 的第一个前置裁决**（见 §5-Q1）。

**E. 明确**不需要**改动的面（实测为"零命中"）**

- 前端：`hiveDatabasePrefix` 在 `*.{ts,js,vue,tsx,jsx,html}` 中 **0 命中**。
- `warehouse/ddl/*.sql`：只需在执行时换 `--hivevar WAREHOUSE_PREFIX=<源前缀>`，**文件内容无需改**（已全部变量化；`WarehouseNameLiteralGateTest` 亦以 `hasSize(5)` + 每文件含 `${WAREHOUSE_PREFIX}_` 看守）。

### 问 4：源保存/激活路径今天有没有前缀校验？四类错误码在源路径上可达吗？

**命令（否定式取证）：**

```powershell
git grep -n -E "WarehouseNamespace|warehousePrefix|hive_database_prefix|hiveDatabasePrefix|validationError" -- "analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/"
```

**实测输出：**

```text
（无输出；git grep 退出码 = 1）
```

**结论（实测，非推断）**：`source` 包（`SourceRegistryServiceImpl` + `entity` + `dto` + `mapper`）**没有任何一个字符**指向 `WarehouseNamespace` 或前缀。因此：

1. **`WarehouseNamespace.validationError(String)`（`WarehouseNamespace.java:118-137`）在源路径上零调用点**；四类错误码 `WAREHOUSE_PREFIX_PATTERN`(`:44`) / `_UNDERSCORE`(`:45`) / `_RESERVED`(`:46`) / `_LAYER_SUFFIX`(`:47`) 在 `SourceRegistryServiceImpl` / `SourceRegistryController` 上**完全不可达**。
2. `SourceRegistryServiceImpl.create`（L88-129）实测校验项为：`sourceCode` / `displayName` / `ingestMode` / `profilePath` / `timezone` / `currency` / `profileVersion` / `status` —— **不含前缀**。
3. `SourceRegistryServiceImpl.activate`（约 L246-265）实测流程为：`requireLifecycleMutable` → `SourceProfileValidator.check` → 幂等判定 → `row.setStatus(ACTIVE)` —— **不写前缀**（与看板 `:248` 的复核结论一致）。
4. `RuntimeProfileServiceImpl.activate`（L215-251）实测流程为：`checkLanding` / `checkHive` / `checkSpark` / `checkMetric` 四项前置检查 → 旧 ACTIVE 降级 → `setStatus(ACTIVE)` + `version+1` —— **无前缀校验**。

**⇒ 问 4 的答案是「今天一个校验点都没有，四类错误码在源路径上 0 可达」**，正是看板 `:248` 所列"P2 必做两件事"的第 ② 项（「在源保存/激活路径上校验前缀，禁止绕过」）尚未动工的直接实测证据。

### 问 5：迁移号位与迁移脚本边界

| 项 | 实测值 | 取证 |
|---|---|---|
| 真库最高迁移 | **V17** | `SELECT MAX(CAST(version AS UNSIGNED)) FROM analytics_meta.flyway_schema_history WHERE success=1;` → `17` |
| 下一个可用号位 | **V18**（**只能由总控分配**） | 同上 + 看板 `:437` 逐字：「迁移号只由总控分配；真库最高版本实测 17 ⇒ 下号为 V18」 |
| `db/meta` 目录现状 | `…/db/meta/V16__source_registry.sql`(`D3E1C98D4AFC5B48`) + `…/V17__source_dimension_for_checkpoint_and_batch.sql`(`9B251B62BFE8EF53`) | `analytics-server/platform-app/src/main/resources/db/meta/` |
| V16 的边界声明 | **不激活库名前缀**（原文：「不激活库名前缀（库名前缀属 P1-04 边界）」） | `V16__source_registry.sql` 头部注释 |
| V17 的边界声明 | "当前活动源" = `runtime_profile(ACTIVE).source_id`；**禁止**另造 `is_current` 列/单例表（会生成第二个所有者） | `V17__source_dimension_for_checkpoint_and_batch.sql` 头部注释 |
| 迁移号纪律 | 看板 `:246`：已应用脚本冻结（checksum），号位只由总控分配 | `docs/项目实施进度与任务看板 V2.2.md:246` |
| `analytics_metric` 侧 | flyway `1 metric store / 2 metric ads materialized / 3 metric ads r7`，**全部 success=1**；本任务与之无关 | `SELECT version, description, success FROM analytics_metric.flyway_schema_history ORDER BY installed_rank;` |

**V18 脚本草案（**仅为文本草案，未执行、未落盘、未申请号位**）** 见 §3.1。

### 问 6：存量源回填等价性——`mock-mall` 从"profile NULL ⇒ `dw`"迁到源级后，库名能否逐字不变？

**已被前序证据证明的事实（可直接复用，非本泳道新测）**：`docs/acceptance/p1-04-namespace-20260911/README.md:18` 逐字：「不传前缀（= 源 A 存量档，`hive_database_prefix` 为 `NULL`）→ 实建 `dw_ods / dw_dwd / dw_dim / dw_dws / dw_ads`，表清单与上面**逐字一致** ⇒ **零数据迁移**（既有 `dw_*` 数据一行都不用搬）」；同文件 `:52` 记录非法前缀两次实测 `exit 64`，`:41` 记录 DDL 63 处变量化，`:70` 记录门禁 3 条。

**本泳道实测的现状底座**：

```text
runtime_profile: 1  local-dev  ACTIVE  3  1  NULL      ← 唯一行；source_id=1；前缀 NULL
source_registry: 1  mock-mall  ACTIVE  1.0            ← 唯一行
```

**等价性论证（三段论，全部以实测为前提）**：

1. **前缀解析规则**（`WarehouseNamespace.java:140-156` + `WarehouseNamespaceSpec` 62 用例）：`ofNullable(null) → defaultNamespace()`，`DEFAULT_PREFIX = "dw"`（`:30`）；契约 `blankIsDefault: true` 且**不做归一化**（trim/大小写折叠/自动纠正全无）。
2. **库名映射规则**（`WarehouseNamespace.scala:113-116` + DDL）：`库名 = <prefix>_<layer>`，`layer ∈ {ods,dwd,dim,dws,ads}`。
3. ⇒ **只要迁移后 `mock-mall` 解析出的 prefix 仍为字符串 `dw`，五个库名字节级不变**（`dw_ods/dw_dwd/dw_dim/dw_dws/dw_ads`），因为两侧都是同一个 `WarehouseNamespace` 实现、同一条 `<prefix>_<layer>` 规则、同一个缺省常量。

**"源级 NULL 该解释成什么"的判据（本泳道给出可判定标准，最终由总控裁决）**：

| 方案 | 语义 | 对 `mock-mall` 的结果 | 反熵评价（实测支撑） |
|---|---|---|---|
| **甲：NULL = 继承 profile** | 源级 NULL → 回落 `runtime_profile.hive_database_prefix`（再 NULL → `dw`） | 字节级等价 ✅ | ❌ **保留两个所有者**（源列 + profile 列同时可决定库名），与"单点所有者"原则冲突；且 `WarehouseNamespaceProvider` javadoc（L14-15）明写 P2/P3 应由 `source_id` 解析 ⇒ 甲与既定方向相反 |
| **乙：NULL = 源级未设置，一律缺省 `dw`** | 源级列即唯一所有者；NULL 视同 `""` → `dw` | 字节级等价 ✅ | ⚠️ 单所有者达成，但**源 B 忘记填前缀会静默落进源 A 的 `dw_*` 库**（多商城接入的核心风险恰恰是这个），且与 fail-closed 取向相反的"静默缺省" |
| **丙（建议）：列 `NOT NULL` + 存量回填 `'dw'`** | 库里不存在 NULL；"未设置"在 DDL 层不可能 | 字节级等价 ✅（回填值恒为 `'dw'`） | ✅ 单所有者 + fail-closed；**且是仓库既有先例**：V16 回填 `runtime_profile.source_id` 用「显式 `updated_at=updated_at`，让回填不伪装成人工编辑」；V17 用「先回填、再 `MODIFY … NOT NULL`」把**收紧语句本身当作断言**（回填遗漏即迁移失败）。两条先例均取此形态 |

**⇒ 建议采纳方案丙**，理由链完整落在仓库既有先例上；**但"NULL 是否合法"属语义裁决，本泳道不自行决定**（见 §5-Q2）。

**零迁移的可证伪判据（落地后必须复测）**：

```text
① 迁移前：SELECT source_code, warehouse_prefix FROM source_registry;         -- 期望 mock-mall → 'dw'
② 迁移后：SELECT COUNT(*) FROM source_registry WHERE warehouse_prefix IS NULL; -- 期望 0（列 NOT NULL 时为结构性 0）
③ 库名复算：WarehouseNamespace.of(源前缀).ods/dwd/dim/dws/ads
            逐字等于迁移前 WarehouseNamespace.ofNullable(NULL) 的同一组值
④ 物理比对：spark-warehouse/ 下 dw_*.db 五个目录的 mtime/文件数/parquet 数在迁移前后不变
            （P1-01 冻结口径：6 库/34 表/178 分区/972 parquet/4,977,445 B）
```

### 问 7：出口证据今天能不能取？——"四类错误码真实拒绝"+「源 B/源 A 落不同库」

**分四块给结论（每块都区分"已具备/缺什么/是否会破坏冻结基线"）：**

| 块 | 今天状态 | 缺什么 | 风险 |
|---|---|---|---|
| ① **四类错误码在作业入口的真实拒绝** | ✅ **已有可用证据**（P1-04 探针实测 2/4 类：`WAREHOUSE_PREFIX_LAYER_SUFFIX` 与 `WAREHOUSE_PREFIX_PATTERN`，两次均 `exit 64`、warehouse 下 0 个库）。脚本 `docs/acceptance/p1-04-namespace-20260911/probe-namespace-prefix.ps1:121-129` 可直接复跑 | `_UNDERSCORE`(`dw__b`) 与 `_RESERVED`(`default`) 两类**未见实测记录**（`probe-namespace-prefix.ps1` 只跑了 2 个非法值） | 低（隔离数仓，不碰真实库） |
| ② **四类错误码在"源保存/激活路径"的真实拒绝** | ❌ **0 可达**（问 4 实测） | 需要 P2-07 实现（A2-A6 + A1）之后才有可拒绝的入口 | — |
| ③ **「源 B 与源 A 落不同库」——隔离数仓口径** | ✅ **已有可用证据**（P1-04 探针场景 A：`--hiveDatabasePrefix=dw_b` → 实建 `dw_b_ods…dw_b_ads` 且**不建** `dw_ods`；仓库落在 `.gitignore` 忽略的 `analytics-server/warehouse-pipeline/tests/r6-smoke-warehouse/p1-04-ns-probe/`） | 与①同理，可直接复跑加强（补 4 类非法值 + 两源并存） | 低 |
| ④ **「源 B 与源 A 落不同库」——平台真链路（8091 发起 + 真实 `spark-warehouse/`）** | ❌ **未取证，且有硬阻碍**（见下） | 见下三条 | **高**：会新建 `pipeline_run` 并把 ACTIVE 快照前移，且可能改写真实数仓 |

**块 ④ 的三条硬阻碍（实测/在册）：**

1. **必须重建并重启 8091**：在场 jar 为 `platform-app-0.1.0-SNAPSHOT.jar`（`33,129,288 B`，mtime `09-12 09:14:58`，sha256 `6742197B…`，进程 pid `47132` 起于 `09:14:59`）。而 P2-07 的改动在 `connection-ingestion` / `warehouse-pipeline` / `platform-app` 三个模块 ⇒ **不重启就拿不到新行为**。同时 `runtime_profile.spark_job_jar_uri` 实测指向 `D:/Develop_code/GraduationProject/spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar` —— **是数据库值而非配置项**（P2-01 ORDER-1 §36 已实测记录），重建 jar 会改变下一次真实运行行为（在册风险 **R1**）。
2. **会前移冻结基线**：看板 `:216` 记录用户裁决 `D-032`（2026-09-11 19:1x）：「**暂不跑，P1-01 冻结基线保持原样**」；理由逐字：「不做"平台发起 + 非缺省前缀"的新流水线（**会新建 run 40 并把 ACTIVE 从 `S20260901_39` 前移**）」。实测今日 ACTIVE 快照为 `S20260901_41`（`analytics_metric.metric_snapshot`，`active_flag=1`，`runtime_profile_id=1`）⇒ 基线已前移到 41，**任何新的真实运行都会再前移一位**。
3. **真实数仓有被改写的物理风险**：真实 `spark-warehouse/` 下已有源 A 的五个 `dw_*.db`（论文素材 `thesis-outline.md:31` 实测记录）。若在真实库上跑源 B，会**新建** `dw_b_*.db`（新增而非覆盖，风险相对可控），但一旦前缀填错就会**写进源 A 的库**（这正是 P2-07 要防的缺陷）。

**⇒ 本泳道判定**：出口证据的**可无风险取得部分**是①②③的隔离口径；块 ④（平台真链路 + 真实数仓）**必须由总控/用户另行批准**，理由=需重启 8091 + 重建在产 jar + 前移 ACTIVE 快照 + 触碰真实数仓，四项均超出只读泳道权限（见 §5-Q4）。

### 问 8：还有哪些"第二读取端口"/潜在多所有者？

| # | 位置（`path:line`） | 实测事实 | 反熵判定 |
|---|---|---|---|
| 1 | `ai-decision/…/ai/evidence/EvidenceBuilder.java:56` + `:316` | 字段 `private final WarehouseNamespaceProvider namespaceProvider;`；`MetricLineage.hiveTables(mysql, namespaceProvider.current())` —— `current()` 实测 = **当前 ACTIVE profile** 的前缀 | ⚠️ **真缺陷（第二读取端口）**：血缘挂在**快照**上（`:317` 同时传入 `snapshotId`），但库名取"**当前** ACTIVE profile"。切源后回看历史快照的血缘，会显示**新源**的库名 ⇒ 同一份证据里"快照身份"与"库名身份"来源不一致。`WarehouseNamespaceProvider` javadoc（`L14-15`）已自我声明此接口应在 P2/P3 改为按快照的 `source_id` 解析 |
| 2 | `warehouse/ddl/*.sql` + `beeline --hivevar WAREHOUSE_PREFIX=<prefix>` | 与 Java/Scala 取值链**完全独立**的第二条注入通道（Hive 变量），缺省值 `dw` 只写在注释/文档里（`00-ods.sql:6`、`warehouse/README.md:58`、`docs/deployment.md:151`） | ⚠️ **潜在第二所有者**：手工/集群部署路径的前缀可被任意传值，**不经 `WarehouseNamespace` 校验**（Hive 只会在漏传时报语法错，fail-closed 但**不校验形状/保留字/层后缀**）。P2-07 需明确该通道是否纳入"同规则"约束（`deployment.md:155` 已声明"同规则"，但**无机器可读执行体**） |
| 3 | `?` `source_registry` 新增源级前缀列 **后**，`runtime_profile.hive_database_prefix`（DB 列 + `RuntimeProfile.java:39` + `RuntimeProfileSnapshot.java:20`）若不删除 | 迁移后两列同时存在 | ⚠️ 若按方案"甲"（NULL 继承）则**明确是双所有者**；若按方案"丙"则必须同时裁决 profile 列的**退役路径**（delete-first：先断读，再删列）。**这是 P2-07 最需要裁决的反熵点**（§5-Q1/Q2） |
| 4 | `docs/acceptance/p1-03-source-registry-api-20260911/e3-verify.ps1:96` | 一条**断言"源激活不写前缀"**的既有验收脚本（`[PASS]` 已落盘于 `raw/e3-06-verify-run5-final.txt:37`） | ⚠️ 不是代码所有者，但**是"旧语义"的可执行遗嘱**：P2-07 落地后它会红。必须显式登记"该断言随 P2-07 退役"（delete-first），否则会变成"两套真相互相打脸" |
| 5 | `docs/thesis-materials/thesis-outline.md:31` | 论文素材逐字写「前缀由 `runtime_profile.hive_database_prefix` 决定」并给了 `WarehouseNamespace.scala:17,67-68` 锚点 | ⚠️ P2-07 落地后**文档过期**（纯文档面，无执行体，但论文会引用） |
| 6 | `runtime_profile` 之外**无**第三个读取端口 | 实测：`git grep -ln "WarehouseNamespace"` 生产命中 25 文件，除上列 6 个 Java 文件外**全是 `spark-jobs`**（消费 `--hiveDatabasePrefix` 参数，不是"读取端口"）；`metric_snapshot` 表 15 列（`id/snapshot_id/runtime_profile_id/runtime_profile_version/business_time/pipeline_run_id/status/version/definition_version/data_updated_at/published_at/source/failure_reason/active_flag/created_at`）**无 `source_id`**，其 `source` 列实测取值**只有 `spark-ads` 一种**（9 行）⇒ 不构成前缀读取端口，但**"快照 → 源"的绑定今天只能经 `runtime_profile_id → runtime_profile.source_id` 两跳**，AI 血缘若要按源解析前缀需走这两跳 |

---

## 3. 迁移草案（**全部为文本草案：未执行、未落盘、未申请号位**）

### 3.1 V18 迁移草案（源级前缀列）

> **前置条件**：号位 V18 由总控分配；列名/语义由总控裁决（§5-Q1/Q2）。以下为**建议稿**，未写入任何 `.sql` 文件。

```sql
-- V18__source_warehouse_prefix.sql   ← 文件名与号位待总控分配
-- 目的：把"数仓库名前缀"的所有权从 runtime_profile 级迁到 source_registry 级（P2-07）。
-- 边界：本脚本只加列 + 回填 + 收紧；不改 runtime_profile 既有列（profile 侧退役另立裁决）。
-- 反熵：列 NOT NULL ⇒ 库里不存在"未设置"状态 ⇒ 不存在"源级缺省 vs profile 缺省"的双缺省歧义。

ALTER TABLE source_registry
  ADD COLUMN warehouse_prefix VARCHAR(24) NULL
    COMMENT '数仓库名前缀（库名 = <prefix>_<layer>，layer ∈ ods/dwd/dim/dws/ads）；规则见 contract-specs/specs/warehouse-namespace.v1.json';

-- 回填：存量唯一源沿用"profile NULL ⇒ 缺省 dw"的既有解析结果，保证库名字节级不变（零迁移）。
-- 显式 updated_at = updated_at：让回填不伪装成人工编辑（同 V16 先例）。
UPDATE source_registry
   SET warehouse_prefix = 'dw',
       updated_at = updated_at
 WHERE warehouse_prefix IS NULL;

-- 收紧：本语句即断言——上面漏回填任何一行，本迁移直接失败（同 V17 先例）。
ALTER TABLE source_registry
  MODIFY COLUMN warehouse_prefix VARCHAR(24) NOT NULL
    COMMENT '数仓库名前缀（库名 = <prefix>_<layer>，layer ∈ ods/dwd/dim/dws/ads）；规则见 contract-specs/specs/warehouse-namespace.v1.json';
```

**列名 / 类型 / 语义建议（逐项给理由）：**

| 项 | 建议 | 理由 |
|---|---|---|
| 列名 | `warehouse_prefix` | 与既有 Java 侧命名 `warehouse` 包 + `WarehouseNamespace` 同词根；不沿用 `hive_database_prefix`（那是 profile 侧的名字，沿用会掩盖"所有者已搬迁"这一事实） |
| 类型 | `VARCHAR(24)` | 契约前缀正则 `^[a-z][a-z0-9_]{0,23}$` ⇒ 最长 24 字符；**与 profile 侧现列 `varchar(64)` 不同宽**，需总控确认是否要求两侧同宽 |
| 默认值 | **不设 `DEFAULT`** | 设 `DEFAULT 'dw'` 会让"忘记填"静默落进源 A 的库（正是要防的场景） |
| NULL 语义 | **不允许 NULL**（回填 + `MODIFY … NOT NULL`） | 见问 6 方案丙；把"未设置"变成 DDL 层不可能 |
| 校验时机 | `SourceRegistryServiceImpl.create` / `update` 与 `activate` **三处都过** `WarehouseNamespace` | 看板 `:248` 明写"源保存/激活路径"（复数）；只挂 `create` 会被 `update` 绕过 |
| 校验载体 | 复用现有 `validationError()`（`WarehouseNamespace.java:118-137`），**不新写一份规则** | 新写一份即造第二个所有者；契约 `errorCodes`（`:23-28`）已冻结四个码 |

### 3.2 需同步改写的既有断言（delete-first 清单）

- `docs/acceptance/p1-03-source-registry-api-20260911/e3-verify.ps1:96`（`[PASS] S12b 未写 hive_database_prefix…`）——**P2-07 落地即失效**。
- `analytics-server/warehouse-pipeline/src/test/…/JobCommandBuilderTest.java:125`（方法名 `hiveDatabasePrefixComesFromProfileOrDefaultsToDw`，逐字注释「库名前缀由 runtime_profile 派生」）——**方法名与注释都会过期**（断言值 `--hiveDatabasePrefix=dw` / `dw_b` 本身仍成立）。

### 3.3 "源 B 与源 A 落不同库"最小实测计划（**仅计划，未执行**）

**可无风险执行部分（隔离口径，不需批准）**：

```powershell
# 复用 P1-04 探针（自带 spark.sql.warehouse.dir + Derby ConnectionURL，落在 .gitignore 忽略目录）
pwsh -NoProfile -ExecutionPolicy Bypass -File docs/acceptance/p1-04-namespace-20260911/probe-namespace-prefix.ps1
# 加强：补测四类错误码的缺项 —— WAREHOUSE_PREFIX_UNDERSCORE(dw__b) / WAREHOUSE_PREFIX_RESERVED(default)
#       （现脚本只跑了 LAYER_SUFFIX 与 PATTERN 两类，见 probe-namespace-prefix.ps1:121-129）
```

**需总控/用户批准部分（**本泳道不执行**）**：

| 步 | 动作 | 为何需批准 |
|---|---|---|
| S1 | 备份在产 jar（`spark-jobs-0.1.0-SNAPSHOT.jar`）并记录三项指纹 | 在册风险 R1（重建 jar 会改变下一次真实运行）；P2-01 ORDER-1 §3.1 已有「R1 暴露窗口最小化协议 (a)-(f)」可复用 |
| S2 | `mvn -f spark-jobs/pom.xml package` + `clean package` 平台 fat jar | **本泳道禁跑 Maven**；且与在飞泳道存在**同模块并发**风险 |
| S3 | 重启 8091（pid 47132 属用户在场进程） | **禁止启停任何进程** |
| S4 | 注册源 B（`POST /api/v1/sources`）+ 激活 + 触发一次流水线 | **会新建 run 并把 ACTIVE 快照从 `S20260901_41` 前移**（`D-032` 明确暂不做） |
| S5 | 在真实 `spark-warehouse/` 上确认 `dw_b_*.db` 新建、`dw_*.db` 未被写 | 触碰真实数仓；需先有备份与"限定 namespace + 失败保旧 ACTIVE"守卫（在册 **P2-05**） |

### 3.4 串行化边界（哪些改动与在飞泳道冲突）

| 模块 | 在飞泳道 | 是否冲突 | 结论 |
|---|---|---|---|
| `spark-jobs/**` | **P2-01 实施泳道**（`513877e0-…`；实测工作树已改 5 个文件 + 10 个新文件） | **冲突（文件面重叠）** | **必须串行**。**缓解事实（实测）**：`JobArgs` 把任意 `--k=v` 收进 `extra`，故**保持 `--hiveDatabasePrefix` 参数名与语义不变 ⇒ `spark-jobs/**` 零改动**，P2-07 可完全避开该模块 |
| `analytics-server/**` | P2-01 的 R1 暴露窗口涉及 `spark-jobs` jar（`packaging`），**非** `analytics-server` 源码 | 不冲突 | 可并行（但仍受"同模块禁并发 Maven"约束） |
| `synthetic-data-generator/**` | M1-9-R2 修复泳道（`16a5eca0-…`） | 不冲突 | 无关 |
| `contract-specs/**` | 无在飞写者（F-32 的"11:53 被改写"仍未归因） | 待裁决 | 见 §5-Q1 |
| `docs/**` | 多个泳道在写各自 acceptance 目录 | 本泳道只写本目录 | OK |
| **Maven** | 多泳道在跑 | **冲突** | **本泳道全程未跑 Maven**（读窗口内） |

### 3.5 契约侧最小改动面（建议，待裁决）

若总控裁决"改 `sourceOfTruth`"：按 `contract-specs/README.md:42` 的规则，**"改语义"不能原地改**，最小合规路径是**新增 `warehouse-namespace.v2.json`**（破坏性变更）+ 目录 `VERSION` major 递增（`1.3.0 → 2.0.0`）+ 增加转换器说明；若判定为"加法"（仅新增 `sourceOfTruth` 的候选来源字段而不改运行期语义），则 minor 递增（`1.3.0 → 1.4.0`）。**两侧实现的契约对账测试**（`WarehouseNamespaceContractTest` / `WarehouseNamespaceSpec`，22 向量）随新版本同步。

---

## 4. 未取证清单（诚实标注）

1. **`spark-jobs/**` 的一切"当前内容"结论**：工作树有 5 个 ` M` + 10 个 `??`（P2-01 泳道活动写入）。⇒ 本报告对 `spark-jobs` 的引用中，`WarehouseNamespace.scala`（`8D7B25BBDBD28865`）、`JobRunner.scala`（`4E2868A6CE66B6FB`）、`JobArgs.scala`（`91E30E2BBF08377A`）窗口内哈希稳定且与 HEAD 一致（未被改动）；但 **`LocalSchemaInitJob.scala`（`4C5327204768B1F0`）是未提交改动版**，其"`ns` 传递路径"的细节**未按 HEAD 版本单独复核**——仅复核了 HEAD 版本同样含 `import …WarehouseNamespace`(L3) / `val ns = WarehouseNamespace.fromArgs(args)`(L18) / `statements(ns)`(L36) 三处，故"该文件消费 `ns`"这一结论在 HEAD 与工作树两侧都成立；**其余细节未取证**。
2. **`EventOdsLoadJob.scala` / `OdsLoadSql.scala` / `SqlTemplateSpec.scala` / `IdCodecSpec.scala` / `warehouse/ddl/00-ods.sql` 的当前内容**：全部处于未提交改动态（P2-01），**未逐一读取**。`00-ods.sql` 的"库名变量化"结论引自 P1-04 证据（`README.md:41`）与 `git grep` 结果，**未按工作树当前内容复核 `${WAREHOUSE_PREFIX}` 的出现次数（P1-04 记录为 63 处）**。
3. **`beeline --hivevar WAREHOUSE_PREFIX=…` 的实际变量替换行为**：本机无 HiveServer2 ⇒ **未实测**（P1-04 `README.md:79` 已同样标注，留 M3/集群 T4）。
4. **集群档 / 1,000,000 行档 / 远端提交路径（`SshSparkSubmitter`）下的前缀行为**：**未取证**。
5. **8090 / 8092 两个进程的实际行为**：本泳道**未触碰、未查询、未读取其日志**（仅知 8091 pid 47132 的 jar 指纹引自看板 `:437`，未独立复核该 pid）。
6. **`SourceProfileValidator` 的完整校验项**：本泳道只从 `SourceRegistryServiceImpl` 的调用点确认它在 `activate` 路径上被调用，**未逐行读取该类的校验清单**，故"它是否已含任何前缀相关内容"**未取证**（但问 4 的否定式 `git grep` 已证明 `source` 包内无前缀命中 ⇒ 若该类在 `source` 包内则必无前缀逻辑；若在别包则未覆盖）。
7. **`source_registry.warehouse_prefix` 的最终列名/长度/默认值/NULL 语义**：**待总控裁决**，本报告仅给建议。
8. **`RuntimeProfile.hiveDatabasePrefix` 的未来归属（保留/只读/删除）**：**待总控裁决**；退役路径与 `WarehouseNamespaceProvider` 的接口签名变更**未取证/未设计**。
9. **`SourceRegistryServiceImpl` 中 `create`/`update`/`activate` 的完整行号**：本泳道读到 `create` 起始 L88、`update` 起始 L133、`activate` 约 L246-265，**中间部分未逐行读完**（文件 406 行），故上述行号为"起始/区间"而非逐行确认。
10. **`p1-03` 的 S12b 断言退役后的证据归属**（改写旧脚本 vs 另立新目录）：**待总控裁决**。
11. **F-32（`contract-specs/README.md` 于 2026-09-12 11:53 被未归因改写）**：本泳道只读取了 `:42` 一行并记指纹 `478E526EA044CC4B`，**未追查改写者**。
12. **前端/HTTP 接口层面的前缀透出**：`git grep` 在 `*.{ts,js,vue,tsx,jsx,html}` 上 0 命中 ⇒ 结论"前端不感知"成立，但**未实跑前端构建或抓取页面**验证。

---

## 5. 需要总控裁决的问题

| # | 问题 | 本泳道实测依据 | 建议 |
|---|---|---|---|
| **Q1** | **契约 `sourceOfTruth` 怎么改？**「`runtime_profile.hive_database_prefix` → 改为 `source_registry`」按 `README.md:42` 属**改语义**（不能原地改）⇒ 是出 `warehouse-namespace.v2.json` + `VERSION` major 递增，还是判为加法走 minor？ | `contract-specs/specs/warehouse-namespace.v1.json:30`（`FROZEN-2026-09-11`）；`contract-specs/README.md:42`；`VERSION = contract-specs 1.3.0` | 出 `v2` 文件（诚实反映破坏性变更）；若总控倾向 minor，需**明示豁免理由**并登记 |
| **Q2** | **源级 NULL 的语义**：① 继承 profile（双所有者）② 视同缺省 `dw`（静默缺省）③ 列 `NOT NULL` + 存量回填 `'dw'`（fail-closed）？ | 问 6 三方案表；V16/V17 的"回填 + 收紧即断言"先例；`mock-mall` 唯一源、profile 前缀 NULL | **采纳 ③** |
| **Q3** | **`runtime_profile.hive_database_prefix` 的退役路径**：立即 delete-first（先断读再删列），还是先并存一个版本（则**明确是双所有者**，需登记为过渡期例外）？ | `RuntimeProfile.java:39`、`RuntimeProfileSnapshot.java:20`、`JobCommandBuilder.java:59` 三个读取点；`RuntimeProfileServiceImpl` 无校验 | 若采 ③ 则**必须**同时排"profile 列退役"任务；并存期需一个**唯一的解析优先级**（建议：源级列优先、profile 列随即标 `@Deprecated` 且不再被 `JobCommandBuilder` 读取） |
| **Q4** | **块 ④ 的平台真链路出口证据是否批准？**（需重建在产 jar + 重启 8091 + 前移 ACTIVE 快照 `S20260901_41` + 在真实 `spark-warehouse/` 新建 `dw_b_*.db`） | 问 7 三条硬阻碍；`D-032`（看板 `:216`）；P2-01 在册风险 R1（ORDER-1 §36） | 建议**不**在本轮做；先以隔离口径（①②③）出证据，块 ④ 与 **P2-05/P2-06 T2** 合并一次跑 |
| **Q5** | **V18 号位与脚本命名**（总控专属权限） | 真库 `MAX(version)=17`；看板 `:437` 已预告下号 V18 | 请总控下发号位与文件名 |
| **Q6** | **`source_registry.warehouse_prefix` 的列宽**：`VARCHAR(24)`（贴契约正则上限）还是 `VARCHAR(64)`（与 profile 侧同宽）？ | 契约 `PREFIX_PATTERN = ^[a-z][a-z0-9_]{0,23}$`；profile 现列 `varchar(64)` | 建议 24（同宽会让"库里能存、代码拒收"的不一致窗口变大） |
| **Q7** | **`p1-03` 的 S12b 反向断言如何退役**（delete-first 的载体选择） | `docs/acceptance/p1-03-source-registry-api-20260911/e3-verify.ps1:96` + `raw/e3-06-verify-run5-final.txt:37` | 在本目录另立新证据，并在 P2-07 交付物中显式登记"该断言随本任务退役" |
| **Q8** | **`warehouse/ddl` + `beeline --hivevar WAREHOUSE_PREFIX` 这条手工通道是否纳入"同规则"强约束？** | `deployment.md:155` 已声明"同规则"但**无机器可读执行体**；Hive 只对漏传报错、**不校验形状/保留字/层后缀** | 建议至少登记为"第二注入通道"风险项；若要强约束需另立任务（脚本化 `--hivevar` 前先过 `WarehouseNamespace`） |
| **Q9** | **`EvidenceBuilder.java:316` 这个第二读取端口本轮是否一并修？**（血缘按快照、库名取"当前 ACTIVE profile"） | `EvidenceBuilder.java:56,316-317`；`WarehouseNamespaceProvider.java:14-15` javadoc 已自我声明为 P2/P3 替换点；看板 `:216` P1-04 遗留项逐字含「AI 血缘按快照 `source_id`（均为总控/P2 侧）」 | 建议**同轮修**（否则 P2-07 交付后仍存在"每源命名空间已生效"的反例），但需排契约/接口变更的先后序 |
| **Q10** | **文档过期面是否本轮同步**：`docs/thesis-materials/thesis-outline.md:31`、`docs/deployment.md:154`、`docs/开发过程事实与决策记录.md:929` | 三处均逐字把前缀归给 `runtime_profile` | 建议随 P2-07 一次性勘误（纯文档，无执行体） |

---

## 6. 复跑指引（本报告全部结论的最小复现命令集）

```powershell
# ── 0) 环境与窗口
Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"; git rev-parse HEAD; git rev-parse --abbrev-ref HEAD; git status --porcelain

# ── 1) 代码侧读取点全集
git grep -n "hiveDatabasePrefix"
git grep -n "hive_database_prefix"
git grep -n "WAREHOUSE_PREFIX"
git grep -ln "WarehouseNamespace" -- "analytics-server/*/src/main/*" "spark-jobs/src/main/*"
# ── 1b) 否定式取证：源路径无前缀校验（期望：无输出，退出码 1）
git grep -n -E "WarehouseNamespace|warehousePrefix|hive_database_prefix|hiveDatabasePrefix|validationError" -- "analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/"

# ── 2) 数据库侧（只读 SELECT）
$OutputEncoding = [System.Text.Encoding]::UTF8; [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' --default-character-set=utf8mb4 -uroot -p123456 -N -B -e @"
SELECT MAX(CAST(version AS UNSIGNED)) FROM analytics_meta.flyway_schema_history WHERE success=1;
SELECT ordinal_position, column_name, column_type, is_nullable, column_default
  FROM information_schema.COLUMNS
 WHERE table_schema='analytics_meta' AND table_name='source_registry' ORDER BY ordinal_position;
SELECT table_schema, table_name, column_name FROM information_schema.COLUMNS WHERE column_name LIKE '%prefix%';
SELECT id, source_code, status, profile_version FROM analytics_meta.source_registry;
SELECT id, profile_code, status, version, source_id, hive_database_prefix FROM analytics_meta.runtime_profile;
"@

# ── 3) 隔离数仓口径的既有出口证据（P1-04 探针，可复跑；不碰真实数仓）
pwsh -NoProfile -ExecutionPolicy Bypass -File docs/acceptance/p1-04-namespace-20260911/probe-namespace-prefix.ps1
```

---

*本文件由只读取证泳道（P2-07 前置）于读窗口 `2026-09-12 13:06:35 → 13:09:30` 内产出；除本文件外未创建/修改/删除/移动任何文件，未执行 git 写操作，未运行 Maven，未启停任何进程，DB 访问全部为 `SELECT`。*
