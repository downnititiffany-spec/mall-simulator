# P1-05「源」维度进采集链 · 验收证据

- 证据目录：`docs/acceptance/p1-05-manifest-source-20260911/`
- 实测时 `git HEAD` = `f5eb74ac8349eb817348a9c92e63abd7713bba21`（分支 `remediation/r1-boundary`）
  - 注：本 lane 开工时 HEAD 是 `c214b25`，期间主线有他人提交；本 lane **未做任何 commit / add**，改动全部在工作区。
- 本轮只写两类库：临时副本库 `analytics_meta_p105` / `analytics_meta_p105it` / `analytics_metric_p105`；
  **真库 `analytics_meta`、`analytics_metric` 全程只读**（真库仍是 V16，见 §6.6）。
- 本目录只放 `README.md` 与本文件产出的 `raw/` 原始日志；脚本在仓库外 `D:\p105-e3\`（不污染仓库）。

---

## 1. 一句话结论

「源」维度已经贯通采集链的**四个落点**：断点（`file_checkpoint.source_id`）、批次（`ingestion_batch.source_id`）、
清单（`ingestion-manifest` 新增 4 字段）、失败关闭（未绑定源 → 409 `SOURCE_NOT_BOUND`），
其中"同一物理文件在换源后各自一套断点、互不推进"在真机 MySQL 上被**双向**证明（r1 读方向 + r2 写方向）。

---

## 2. 改动文件清单（逐文件一句话）

### 2.1 生产代码

| 文件 | 一句话 |
| --- | --- |
| `connection-ingestion/.../ingestion/LocalFileIngestor.java` | 断点读写全部带上 `sourceId`：`ingestFile(...)`、`findCheckpoint(runtimeProfileId, sourceId, absPath)`、`checkpointKeys(runtimeProfileId, sourceId)`、`upsertCheckpoint(...)`、`hasConsumableData(file, runtimeProfileId, sourceId)`；类注释里的唯一键由 `runtime_profile_id + file_path + file_identity` 改为 `runtime_profile_id + source_id + file_path + file_identity`。 |
| `connection-ingestion/.../ingestion/IngestionService.java` | `runOne` 先经 P1-03 读口取当前源（`requireBoundSourceId` → `requireSourceView` → `LandingUri.resolve`），未绑定即失败关闭；批次行写 `source_id`，每文件采集前再校验源未变（`requireSourceUnchanged`）；`buildManifest` 追加 4 个源字段；"checkpoint 键含 runtime_profile_id（§9.2）"的注释补上 `source_id`。 |
| `connection-ingestion/.../ingestion/entity/FileCheckpoint.java` | 新增 `sourceId` 字段（与 V17 的 `uk_ckpt_source` 列序一致）。 |
| `connection-ingestion/.../ingestion/entity/IngestionBatch.java` | 新增 `sourceId` 字段；`source` 的 javadoc 明确它仍是**连接器类型**（`local-file`），不是源编码。 |
| `platform-common/.../common/PlatformBizException.java` | **加性**新增常量 `SOURCE_NOT_BOUND`（不动任何既有常量）。 |
| `platform-common/.../common/GlobalExceptionHandler.java` | **加性**把 `SOURCE_NOT_BOUND` 映射为 HTTP 409。 |

> ⚠️ **越界增量披露**：本 lane 的「只动」范围是 `connection-ingestion/src/**` 与 `platform-app/src/**`。
> 上表最后两行位于 `platform-common`，属边界外。两处都是纯加性（一个新常量 + 一个 switch case），
> 断言所有者按 D-035 裁决 6 是 `GlobalExceptionHandlerSourceStatusTest`。

### 2.2 测试代码

| 文件 | 一句话 |
| --- | --- |
| `connection-ingestion/.../LocalFileIngestorSourceIsolationTest.java`（新，10 例） | 断点按源隔离：查询条件里必须出现 `source_id`、同路径两源并存、两源互不推进、`upsert` 写的是自己的源。 |
| `connection-ingestion/.../IngestionSourceNotBoundTest.java`（新，4 例） | 未绑定源必须抛 `SOURCE_NOT_BOUND` 且**零写入**（不落批次、不落清单、不动断点），消息为「运行环境未绑定源，先激活源再采集」。 |
| `connection-ingestion/.../IngestionSourceManifestTest.java`（新，5 例） | 清单 4 字段取值：`sourceCode`/`sourceId`/`profileVersion` 来自登记行（`profile_version` 列，不是画像 JSON），`mappingVersion` 恒为 `null`。 |
| `connection-ingestion/.../LocalFileIngestorCheckpointKeyTest.java`（改，3→4 例） | 键里加 `source_id` 后仍能折叠历史拼写差异；断言改为对 `Key(runtimeProfileId, sourceId, filePath)` 记录取值。 |
| `connection-ingestion/.../LocalFileIngestorConsumableDataTest.java`（改，11 例） | 全部调用点补 `sourceId`（`hasConsumableData(file, 1L, 1L)`）。 |
| `connection-ingestion/.../IngestionRunNoNewDataTest.java`（改，4 例） | 构造器 7 参 + `bindSourceOne()` 夹具。 |
| `connection-ingestion/.../IngestionServiceStatusTest.java`（改，11 例） | `status()` 只读、读口取当前源。 |
| `platform-app/.../ingestion/IngestionManifestSourceSchemaTest.java`（新，8 例） | 清单契约对账：4 字段**不在** `required`（仍是 15 个）、结构/长度/最小值与 schema 一致、历史清单仍可校验（**delta 断言**，见 §7.1）。 |
| `platform-app/.../source/SourceRegistryMigrationMySqlIT.java`（改，6 例） | `EXPECTED_META_SCRIPTS` 纳入 V17；新增「历史含 V16 与 V17 且无失败行」用例；新增 `-Dp1.it.metaDb/metaUser/metaPassword` 覆盖（默认值不变，仍指向真库与 `meta_app`）。 |
| `platform-common/.../GlobalExceptionHandlerSourceStatusTest.java`（改，**5→5 例**） | 在既有方法 `sourceConflictCodesMapTo409`、`codesAreAdditiveAndVerbatim` 内追加断言，未新增用例，故类计数不变。 |

**为什么不新增用例数**：这两个方法本来就是"冲突类错误码集合"与"错误码只增不改"的断言所有者，
新码属于集合成员，加进既有方法才是正确归属；新增一个同类方法会造成两处所有者。

---

## 3. 契约与迁移增量

| 项 | 内容 |
| --- | --- |
| 错误码 | 新增 `SOURCE_NOT_BOUND` → HTTP **409** CONFLICT |
| 消息 | `运行环境未绑定源，先激活源再采集（runtime_profile_id=1，source_id 为空）` |
| 迁移 | `V17__source_dimension_for_checkpoint_and_batch.sql`（本 lane **未改**该脚本，属 P1-04 所有、已冻结、已有他人写的结构测试 `FileCheckpointSourceMigrationScriptTest`） |
| 清单 | `ingestion-manifest` 新增 `sourceCode` / `sourceId` / `profileVersion` / `mappingVersion`，**不进 `required`**（仍是 15 个），`additionalProperties: true` 故向后兼容 |
| 契约版本 | `1.1.0 → 1.2.0`（D-037 裁决 ⑦，属总控/契约所有者） |

**没有做的三件事**（都是刻意的）：不写第二处 `SELECT ... FROM runtime_profile`（源只经 P1-03 读口）；
不给 `source_id` 加列默认值、不做 `1`/`mock-mall` 兜底；不改写任何历史清单、不回滚历史 39 行。

---

## 4. 证据清单（`raw/` → 证明什么）

| 文件 | 内容 |
| --- | --- |
| `e2-00-baseline-prechange.log` | 改动前基线全量单测：**472** 例（41/114/102/38/91/86），08:40 |
| `e2-01..e2-08-postchange.log` | 先红后绿的中间过程（含 `e2-red-detail.log` 的首次红） |
| `e2-09-manifest-schema-focused.log` | 清单契约对账的定向重跑 |
| `e2-10-postchange.log` | 改动后全量单测：**500** 例，0F/0E/0S，08:56 |
| `e2-11-postchange-final.log` | **交付物最终态**全量单测：**500** 例，0F/0E/0S，`BUILD SUCCESS`，48.225 s，09:05 |
| `e3-01-env-provenance.txt` | 环境出处：HEAD、暂存类、依赖 jar 集等价性、作用域账号与"真库 SELECT 被拒"实测、端口纪律 |
| `e3-02-instance-r1-stdout.log` | r1 实例自身 stdout（stderr 为空） |
| `e3-10-scenarios-r1.txt` | **E3 主证据**：迁移结果 + 4 次采集 + 切源 + 失败关闭 + 前后快照（逐字 HTTP 状态码与响应体） |
| `e3-11-scenarios-r2.txt` | E3 第二轮：追加事件后的**写方向**隔离证明 |
| `e3-12-instance-r2-stdout.log` | r2 实例 stdout（stderr 为空） |
| `e3-13-historical-rows-same-columnset-check.txt` | 历史行"同列集"哈希核对（见 §7.2 为什么必须补这一份） |
| `e3-20-migration-it-on-replica.log` | `SourceRegistryMigrationMySqlIT` 在真库 dump 出的字节等价副本库上实跑：**Tests run: 6, 0F/0E/0S** |

---

## 5. E2 关键数字（先红后绿）

```
基线  472 例 = platform-common 41 / connection-ingestion 114 / warehouse-pipeline 102
             / metric-analysis 38 / ai-decision 91 / platform-app 86
最终  500 例 = platform-common 41 / connection-ingestion 134 / warehouse-pipeline 102
             / metric-analysis 38 / ai-decision 91 / platform-app 94     0F 0E 0S
增量 +28 = LocalFileIngestorSourceIsolationTest 10（新）
         + IngestionManifestSourceSchemaTest 8（新）
         + IngestionSourceManifestTest 5（新）
         + IngestionSourceNotBoundTest 4（新）
         + LocalFileIngestorCheckpointKeyTest 3→4（+1）
```

复现命令（`e2-11` 用的就是它）：

```powershell
$env:MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djdk.attach.allowAttachSelf=true'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -XX:+UseSerialGC'
$env:SPARK_DRIVER_MEMORY='512m'
mvn -o test -f analytics-server/pom.xml -pl platform-app -am '-DforkCount=0'
```

---

## 6. E3 真机结果（真 HTTP + 真 MySQL 8.0 副本 + 真 Flyway）

### 6.1 环境

| 项 | 值 |
| --- | --- |
| 实例 | `D:\Develop\JAVA17\bin\javaw.exe`，端口 **8093**，工作目录 `D:\p105-e3\run` |
| 类路径 | 6 个模块 `target/classes`（本轮 `mvn -o compile` 后暂存）+ 59 个依赖 jar |
| 依赖集出处 | 与 `platform-app-0.1.0-SNAPSHOT.jar` 的 `BOOT-INF/lib/*.jar` 逐名比对无差异（59 = 59）；**不用 fat jar 本体**（它是早于本轮改动的旧产物） |
| 库 | `analytics_meta_p105` / `analytics_metric_p105`（真库 dump 载入，载入后 flyway 15 行 max=16、checkpoint 102、batch 39、sources 1，与真库一致） |
| 账号 | `p105_meta@localhost` 只被授权到副本库；实测 `SELECT` 真库报 `ERROR 1142 ... denied` |
| Landing 隔离 | `LandingUri` 对 `file://./landing` 按**进程 cwd** 解析，故实例的 `eventsDir` = `D:\p105-e3\run\landing\events`，仓库 `landing\` 物理不可达 |
| 端口纪律 | 全程只用 8093；8090/8091/8092 四次探测均未监听、未触碰 |

### 6.2 迁移（新代码启动即跑 Flyway，副本库 V16 → V17）

```
flyway: rows=16 max=17 failed=0
V17 row: version=17 script=V17__source_dimension_for_checkpoint_and_batch.sql success=1
file_checkpoint.source_id: bigint nullable=NO default=<none>
index: uk_ckpt_source cols=runtime_profile_id,source_id,file_path,file_identity
backfill: checkpoint rows=102 rows_with_source_id_null=0
```

### 6.3 五项必证结论

| # | 结论 | 证据（`raw/e3-10-scenarios-r1.txt`） |
| --- | --- | --- |
| ① | 断点行真的带 `source_id` | `id=103 source_id=1 next_offset=5089`，`id=104 source_id=2 next_offset=5089` |
| ② | 换源后同一物理路径两套断点并存 | 上两行同 `file_path=D:\p105-e3\run\landing\events\e3-sample.jsonl` |
| ③ | 新批次写对 `source_id`，历史 39 行未被改动 | `batch id=40 source_id=1`、`id=41 source_id=2`；历史行同列集 MD5 与启动前逐字相同（§7.2） |
| ④ | 新清单含 4 字段且 `mappingVersion` 为 `null` | `40.json`: `sourceCode=mock-mall, sourceId=1, profileVersion=1.0, mappingVersion=null`；`41.json`: `sourceCode=p1-03-probe-1, sourceId=2, ...` |
| ⑤ | 旧清单仍可读、未被改写 | 历史 `30.json` 复制进本轮 landing，采集两轮前后 MD5 恒为 `4CC46FEB1356E5FBFAD386F3C0BD2861` |

补充：切源经 P1-03 的写口与读口两端都验了——`POST /api/v1/sources` → `/test`（7 项全 true）→ `/activate`
返回 `current=true`，`runtime_profile.source_id` 变 2，`GET /api/v1/ingestion/status` 立刻回 `sourceId=2`。

### 6.4 失败关闭（未绑定源）

```
（副本库把 runtime_profile.source_id 置 NULL）
POST /api/v1/ingestion/runs -> HTTP 409
{"code":"SOURCE_NOT_BOUND","message":"运行环境未绑定源，先激活源再采集（runtime_profile_id=1，source_id 为空）",...}
写入对比：ingestion_batch 41->41  manifest 3->3  file_checkpoint 104->104
```

即：**报错且零写入**，没有兜底到 1 / `mock-mall`，也没有留下半个批次。

### 6.5 双向隔离证明（r2：写方向）

r1 已证**读方向**：源 1 把该文件读到 EOF 后，源 2 仍读到 12 条（若断点共享就会是 0 条）。

r2 证**写方向**：给同一文件追加真实形状的 5 行（5089 → 7210 字节）后，

```
只用源 1 采集 → batch id=42 source_id=1 records=5
   manifest files[0]: startOffset=5089 endOffset=7210 acceptedRecords=5
   checkpoint: id=103 source_id=1 offset=7210 | id=104 source_id=2 offset=5089   ← 源 2 原地不动
再切源 2 采集 → batch id=43 source_id=2 records=5
   checkpoint: id=103 source_id=1 offset=7210 | id=104 source_id=2 offset=7210   ← 各推各的
```

即一次采集只推进**当前源**那一套断点。

> 读数说明：`raw/e3-11-scenarios-r2.txt` 里几处括号内的期望值写的是"应前进到 5861"，那是脚本作者
> （本 lane）预估追加 5 行约 772 字节的**猜测**；实测追加后文件为 7210 字节（5089 → 7210，增 2121 字节），
> `endOffset` 与断点都是 7210。README 一律引用**实测值**，猜测值仅原样留在原始日志里不作结论。

### 6.6 真库只读证明

启动前 / 两轮 E3 之后，真库快照逐字相同：

```
real meta flyway: rows=15 max=16 failed=0   file_checkpoint=102  ingestion_batch=39
real meta source_registry=1  runtime_profile=1  operation_audit_log=92
real metric metric_snapshot=8  metric_value=70  ads_operation_overview_m=7
真库 file_checkpoint 有没有 source_id 列: 没有      ← 真库仍停在 V16
```

### 6.7 `SourceRegistryMigrationMySqlIT`

本轮把该 IT 的目标库做成可用系统属性覆盖（`-Dp1.it.metaDb`，**默认值仍是真库 `analytics_meta` + `meta_app`**），
于是它能在另一份"真库 dump 的字节等价副本库"`analytics_meta_p105it` 上**实跑**：

```
首次启动执行脚本数=1，schemaVersion=17；第二次启动执行脚本数=0
迁移历史脚本清单=[V1..V5, V7..V17]（16 个，含 V17 且排在最后）
种子源={source_code=mock-mall, ..., status=ACTIVE, profile_version=1.0}
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

它证明的是"V16→V17 的迁移链 + 存量回填 + 二次启动空跑"在真实 MySQL 上成立；
**真库上的本类运行仍留给总控换 jar 之后**（在真库上跑它会把真库迁到 V17，属越界写库）。

### 6.8 实例停机

| 轮次 | pid | 停机时刻 | 端口复检 |
| --- | --- | --- | --- |
| r1 | 25152 | 2026-09-12 09:01:59 | 8093 仍在监听=False |
| r2 | 46484 | 2026-09-12 09:03:08 | 8093 仍在监听=False |

---

## 7. 方法说明：两处"看上去多余"的补证

### 7.1 历史清单回归写成 delta 断言，而不是"零违规"

`IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate` 最初写成"39 份历史清单零违规"，
实跑为**红**。查明原因**不是本 lane 的改动**：`landing/manifests/1.json`–`5.json` 的 `batchId` 是**字符串**，
而 schema 里 `batchId` 是 `integer`——这个漂移早于 P1-05，且 schema 自己的 `batchId.description` 里已经记录了它。
因此断言改成 delta：把 4 个新字段从 schema 的 `properties` 里摘掉后跑校验，
要求"新增违规数 = 0、历史违规全部是那条已知 `batchId` 类型不符、涉及文件恰为 `1.json`–`5.json`"。
**只报不改**：修它属于契约所有者与那 5 份清单的所有者。

### 7.2 历史行哈希必须"同列集"才可比

r1 的启动前快照与结束快照天然不可比：V17 之后才有 `source_id` 列，两次 `MD5(GROUP_CONCAT(...))` 的列集不同。
故在 r2 之后用**与启动前逐字相同的列集**重算一次（`e3-13`）：

```
ingestion_batch id<=39: n=39 md5(同列集无source_id)=15ee7c6a8f7bcd3745025a857e38429b   ← 与启动前相同
file_checkpoint id<=102: n=102 md5(同列集无source_id)=0c35d0ad20560b861dd2fa484f52ce86 ← 与启动前相同
历史行 source_id 分布: batch 39 行全为 1；checkpoint 102 行全为 1
新写入行: batch id>=40 n=4 明细=40:source_id=1,41:source_id=2,42:source_id=1,43:source_id=2
```

结论：4 次新批次没有改动任何历史行；历史行上唯一变化是 V17 按 `runtime_profile` 回填的 `source_id=1`（预期行为）。

---

## 8. 未取证 / 依赖项（明确列出，不含糊）

1. **真库 V17 应用**：真库仍是 V16，V17 何时上真库由总控在"同批次换 jar"窗口决定。本轮的等价证据是副本库实测（§6.2）与 IT 副本实跑（§6.7）。
2. **`SourceRegistryMigrationMySqlIT` 对真库通过**：需总控换 jar 之后跑（命令见 §6.7；直接跑会把真库迁到 V17）。
3. **跨轮时序现象（预期，非缺陷）**：副本库一旦被新代码迁到 V17，旧代码写断点会因 `source_id` 无默认值而失败（严格模式下 `Field 'source_id' doesn't have a default value`）。副本库 `analytics_meta_p105` 现为 **V17**、`analytics_meta_p105it` 为 **V17**，故这两个库**只可用于新代码**。
4. **集群模式运行环境画像**：未取证（本机无集群）。
5. **百万行级画像/大批量**：未取证（E3 样本是从真实 landing 文件取的 12+5 行，真事件形状但非真批次量）。
6. **换源与采集的并发竞争**：未做压测；本轮只证"单线程顺序切换"下读口与落库一致（`requireSourceUnchanged` 的并发语义只由单测覆盖）。
7. **`ingestion_batch` 其它写入方**：未逐一核实；本 lane 只改了采集链写入点（D-037 line 1018 未取证 ③ 仍开放）。
8. **画像 JSON 与登记 `profileVersion` 的一致性**：本 lane 只保证清单取的是**登记列**；两份东西对不对得上是 P3-01 的范围（D-037 裁决 ⑧）。
9. **"旧唯一键不可重新加回"**：一次性测量结论，未重复验证（D-037 line 1018 未取证 ⑤ 仍开放）。
10. **真库 `landing/`**：本轮 landing 全程在 `D:\p105-e3\run\landing`；仓库 `landing/` 只有一次**读**（取 12 行样本与 `30.json` 副本）。

---

## 9. 发现的问题（只报，不自行处理）

1. **`landing/manifests/1.json`–`5.json` 的 `batchId` 是字符串**，与 schema 的 `integer` 不符（§7.1）。早于 P1-05，schema 描述里已记录；修复归属：契约所有者 + 那 5 份清单的所有者。
2. **`ingestMode` / `source` 两个"类型"字段容易混**：`source_registry.ingest_mode` 是 `FILE`，`ingestion_batch.source` 是连接器类型 `local-file`，清单里的 `source` 也恒为 `local-file`（D-037 裁决 ⑤）。本轮按裁决保持不变，但三处命名相近，建议后续在文档里给一张对照表。
3. **`status()` 读口在 `runtime_profile.source_id` 为空时返回 `sourceId=null`**（不报错），而采集入口对同一状态是 409 失败关闭。这是刻意的（只读接口不该因为未绑定源就变红），但两者行为差异值得在接口文档里写清。

---

## 10. 与 D-037 的关系

本轮实现与 D-037 裁决 ①②③④⑤⑥⑦⑧ 逐条一致，**无冲突、无自行修订**。
唯一需要总控知情的两处边界情况：`platform-common` 的加性改动（§2.1）与 `SourceRegistryMigrationMySqlIT` 新增的系统属性覆盖（§6.7，默认值未变）。
