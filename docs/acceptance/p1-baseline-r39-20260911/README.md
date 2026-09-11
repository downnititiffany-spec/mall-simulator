# P1-01 源 A 改造前只读冻结基线（run 39 / 快照 `S20260901_39`）

> 任务：`项目实施进度与任务看板 V2.2.md` §3.4 P1-01；任务包见 `docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md` §4。
> 采集脚本：`scripts/accept-p1-baseline.ps1`（802 行，SHA-256 `E8A9C69F9E485BC697FF07CA712E6EB38FFDE035108A64A03C09668D63D7D2A3`）。
> 采集时间：2026-09-11 17:40–17:43（首次）、17:43–17:46（二次读取比对）。
> 证据文件：`baseline.json`（392,281 B，机器可读全量）、`raw/`（19 个原始响应与证据转储）、`verify/`（二次读取 + `compare-report.json`）。
> 状态：`REVIEW`（证据已落盘，等总控复核口径后转 `DONE`；见 §12）。

---

## 0. 一句话结论

在 run 39 / 快照 `S20260901_39` 的当前状态下，用同一脚本独立采集两次：**19/19 一致性检查通过**，除显式登记的易变字段外 **20 个段逐字节一致**（`verify/compare-report.json` → `identical=true`、`differences=[]`），采集过程对业务库与数仓库**零写入**（前后指纹变化段=0、120 条 SQL 全为 `SELECT`）。本目录可作为 P1–P5 的"改造前"对照基准。

## 1. 任务边界与取证方式

- 允许（已做）：新增 `docs/acceptance/p1-baseline-*/`、只读验收脚本；更新进度看板。**未改任何 Java/Scala/Vue/SQL/DB 数据。**
- 禁止（已守）：**未启动 Spark、未重跑大数据、未改任何业务数据**。所有 Hive/数仓事实来自文件系统清单与 MySQL 元数据/证据表读取，不来自重新执行作业。
- 三个服务保持运行（平台 8091 / 参考商城 8090 / 生成器 8092），脚本只调用只读 `GET` 与一次登录 `POST /api/v1/auth/login`。
- SQL 白名单：`Invoke-Sql*` 三个封装均以 `^\s*(select|show|with)\b` 前置校验并逐条记入 `readonlyProof.sqlStatementCount`；`nonSelectStatements=[]`。

## 2. 基线对象（冻结坐标）

| 项 | 值 |
|---|---|
| 仓库 | `remediation/r1-boundary @ 076f5d211c9c22883281d5746707d3a77d4fda66`，采集时 20 个脏文件 |
| 流水线 run | `analytics_meta.pipeline_run.id=39`，`SUCCESS`，类型 `ODS_TO_ADS`，2026-09-11 15:08:00.283 → 15:12:22.026（271 s） |
| 目标快照 | `S20260901_39`，`metric_snapshot` 1 行，`ACTIVE`，`pipeline_run_id=39`，`business_time=2026-09-01 00:00:00.000`，发布 15:12:22.013 |
| 指标库 ACTIVE 快照 | `analytics_metric.metric_snapshot` 中 ACTIVE 唯一（= `S20260901_39`，version 8，source `spark-ads`） |
| ACTIVE runtime profile | `id=1`、`local-dev`、`ACTIVE`、`version=3`、`landing_uri=file://./landing`、**`hive_database_prefix=NULL`**（`activeProfile.hiveDatabasePrefixNull=true`，P1 要激活的字段） |
| 仓库 URI | `file:///D:/Develop_code/GraduationProject/spark-warehouse` |
| 制品（4 jar） | 平台 33,080,887 B / sha256 `30CC42A6…`、商城 29,852,277 B / `EF1991A0…`、生成器 26,129,801 B / `2943487C…`、spark-jobs 219,588 B / `FF7923B3…`（逐个 mtime + sha256 见 `baseline.json#artifacts`） |
| 服务进程 | 8091 pid 7428 @16:28:31、8090 pid 44280 @15:57:12、8092 pid 39648 @15:57:19 |

## 3. 冻结指标（10 条，`day:2026-09-01`）

| metricCode | value | unit | 定义版本 |
|---|---|---|---|
| `pv` | 5.0000 | 次 | v1 |
| `uv` | 5.0000 | 人 | v1 |
| `dau` | 10.0000 | 人 | v1 |
| `paid_order_cnt` | 1.0000 | 单 | v1 |
| `gmv` | 88.1600 | 元 | v1 |
| `net_sale` | 88.1600 | 元 | v1 |
| `avg_order_value` | 88.1600 | 元 | v1 |
| `refund_rate` | 0.0000 | — | **v2** |
| `full_refund_rate` | 0.0000 | — | v1 |
| `buy_rate` | 0.2000 | — | v1 |

- 指标值行数=10、非 `day:2026-09-01` 口径行数=0（`metric-values-present` / `metric-values-all-day-period`）。
- `analytics_metric` 内本快照的 ADS 镜像行数合计 **30** = `BUILD_ADS` 逻辑输出 30（`ads-mirror-rows-match-stage`）。
- 全库 ADS 镜像表行数（含历史快照）：`ads_behavior_funnel_m` 28、`ads_data_quality_m` 28、`ads_hot_product_m` 33、`ads_product_conversion_m` 33、`ads_user_profile_m` 19、`ads_active_trend_m` 7、`ads_operation_overview_m` 7、`ads_sale_trend_m` 7。

## 4. 两个必须区分的行数口径（本任务新固化）

| 口径 | 定义 | 来源 |
|---|---|---|
| **逻辑写入行数** | 作业真正输出的业务行 | `pipeline_stage_run.records` = `Σ evidence.jobs[].outputRecords` |
| **物理行数** | 物理落盘/落表行数，同一逻辑行可分入两个分区 | `Σ evidence.jobs[].outputPartitions[].rowCount` |

| 阶段 | 逻辑 | 物理 | 说明 |
|---|---|---|---|
| WAIT_LANDING | 1000 | — | 采集输入（batch 31） |
| INIT_SCHEMA | 37 | — | DDL 语句数口径 |
| LOAD_ODS | 1000 | 1000 | 每行只落一个 `dt/hour` 分区，物理=逻辑 |
| BUILD_DWD | **159** | 159 | |
| BUILD_DWS | **10** | **24** | 逻辑行分入 2 个 dt 分区 → 物理翻倍 |
| BUILD_ADS | **30** | **60** | 同上 |
| QUALITY_CHECK | 6 | 30 | 物理数是它**读取**的暂存表行数（非产出） |
| PUBLISH_METRIC | 60 | 60 | ADS 镜像 30 + 指标值 10 + 质量结果 12 + … |

- 8 个阶段中带 jobs 的 7 个阶段，`records` 与 `Σ evidence.outputRecords` **全部一致**（`stage-records-match-evidence`）。
- **口径提示**：任何"行数"结论必须写明是逻辑还是物理。历史证据里 DWS=24 / ADS=60 是**物理**口径，与本基线冻结的 DWS=10 / ADS=30（逻辑）不矛盾。

## 5. 数仓台账（`baseline.json#warehouse`）

| 库 | 表数 | 顶层分区 | parquet | 字节 |
|---|---|---|---|---|
| `dw_ods` | 4 | 111 | 866 | 4,567,412 |
| `dw_dwd` | 3 | 32 | 57 | 317,273 |
| `dw_dws` | 7 | 14 | 14 | 28,547 |
| `dw_dim` | 2 | 3 | 3 | 8,198 |
| `dw_ads` | 16 | 16 | 30 | 54,737 |
| `probe_r613` | 2 | 2 | 2 | 1,278 |
| **合计** | **34** | **178** | **972** | **4,977,445** |

- ODS 明细：`ods_behavior_event` 30 分区/299 文件/1,350,996 B、`ods_product_event` 30/241/1,361,042、`ods_trade_event` 30/245/1,475,234、`ods_user_event` 21/81/380,140（该表只覆盖有用户事件的 21 天）。ODS 行数 365+271+281+83=1000。
- 分区计数口径：`tables[].partitions` 只统计**表目录下的一级子目录**（即 `dt=`）；ODS 的 `hour=` 子目录不计入分区数，体现在 `parquetFiles`（如 behavior 30 分区 / 299 文件）。
- ⚠️ **观察（非缺陷，勿误判）**：`dw_ads` 7 张正式表的物理文件 mtime 均为 **2026-09-10 18:07**（历史残留）；run 39 的 ADS 数据实际写在 `dw_ads.<表>__staging/snapshot_id=S20260901_39/dt=20260901/`，正式分区由 **Hive 元数据指针指向该 staging 路径**——发布阶段证据 `MP_HIVE_PATH_PINNED` 逐表列出 8 条 `表名_m <- …__staging/snapshot_id=S20260901_39/dt=20260901` 即为证。`ads_operation_overview` 正式表 **0 分区/0 文件**是因为它没有 09-10 的历史残留，**不代表数据丢失**（其 staging 有 2 快照/4 文件）。
- `probe_r613.db` 2 表为历史探针残留（`t_formal` 0 文件 / `t_stg` 1,278 B），不参与 P1，保留原状。
- `analytics_meta` 20 张表 + `flyway_schema_history` 14 条迁移全部记录；`analytics_metric` 表级行数连同"本快照行数"一并冻结。

## 6. 来源追溯：严格生成器 vs 旧夹具（不得混写）

| 类别（`landing.files[].class`） | 文件数 | 字节 | 是否进入 `S20260901_39` |
|---|---|---|---|
| `strict-generator`（严格生成器 S3b 1,000 条） | 1 | 363,848 | **是（唯一输入）** |
| `legacy-hourly-load`（09-05～09-07 旧切片） | 38 | 403,570,831 | 否 |
| `legacy-fixture-golden`（旧 golden 夹具 6 份） | 6 | 110,579 | 否 |
| `legacy-fixture-r9`（旧 r9 工艺夹具 4 份） | 4 | 73,720 | 否 |
| `acceptance-fixture`（B-08 门禁、B-11/DEF-13 正路径各 2 条） | 2 | 1,172 | 否 |
| `live-traffic-slice`（`2026091115.jsonl`，本轮真实链路 40 条） | 1 | 19,365 | 否（batch 34） |
| **合计** | **52** | **404,139,515** | 未分类文件数=0 |

**冻结快照的唯一样本来源链**：run 39 ← ODS 输入 = `ingestion_batch.id=31` ← `gen-s3b-1000-20260911.jsonl`（1,000 条完整行、363,848 B、checksum `89b82028`、`acceptedRecords=1000`、`quarantineCount=0`）。该批次对旧夹具 `golden-r615-20260910164859.jsonl` 消费 **0 条**。

- 生成器文件事件分布：`event_time` 2026-09-01T00:07:41+08:00 ～ 2026-09-30T21:06:34+08:00，覆盖 30 个事件日；**2026-09-01 共 42 行**（即冻结指标聚合的那一天），其余行落入各自 `dt` 分区，不参与 `day:2026-09-01` 口径。事件类型直方图：behavior 365 / order_created 138 / stock_reserved 138 / user_registered 83 / order_cancelled 83 / stock_released 83 / order_paid 54 / product_created 50 / refund_created 3 / refund_completed 3。
- **隔离性**：ADS 写入为 `INSERT OVERWRITE TABLE … PARTITION(snapshot_id, dt)`，只重写本 run 处理的分区；旧夹具与验收夹具的行不进入该快照（`provenance.frozenSnapshotLineage.isolation`）。
- **DEF-13 机制在基线中的完整留痕**：`provenance.legacyFixture.duplicateReads` 列出 47 个被多批次读取的文件；其中 batch 29（`\.\` 旧写法，869k 级全量回填）重读了 38 个旧切片 + 6 份 golden + 3 份 r9；单文件重复最多的是 `2026090701.jsonl`（批次 8/9/11/13/14/15/29）。下游以 `(source_system, event_id)` 去重，指标口径不受影响。
- **checkpoint 现状**：102 行 / 102 个不同路径（写法唯一性检查通过），其中 **50 行为历史 `\.\` 写法**，D-024 已裁决"不清理、不作为 P1 前置"，本基线**原样保留并冻结为 50**（`legacy-spelling-residual-untouched` 检查即防止它被无意改动）。
- **采集读数一致性**：文件系统 52 文件 ↔ API `pendingFiles=52 / pendingBytes=404139515`，`checkpointFiles=52`（E3 现场：无新数据，`newFileCount=0`，`latestBatch=batch 39/2 条`）。

## 7. 只读证明（`readonlyProof`）

- 采集前后对 14 个段做指纹（行数 + `max(updated_at)`/`max(id)` 类摘要），**变化段=0**，`mutationDetected=false`。
- SQL 语句 **120 条**，全部 `SELECT`（`nonSelectStatements=[]`），逐条记录在 `raw/`（`Invoke-Sql` 日志）。
- 3 张表**显式排除**在指纹外并写明理由：`analytics_meta.user_session`（脚本自身登录会产生会话行）、`operation_audit_log`、`ai_call_log`（运行时追加型日志，非业务数据）。
- **本脚本的自身副作用（已实测）**：每次运行登录一次 → `user_session` +1 行（153 → 154）。比对时该表行数被归一为 `null`（`volatilePaths.normalizedByTable` + `selfInflicted` 段），既不掩盖也不误判为基线漂移。

## 8. 二次读取一致性（P1-01 的最小测试）

命令：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/accept-p1-baseline.ps1 `
  -OutDir docs/acceptance/p1-baseline-r39-20260911/verify `
  -CompareWith docs/acceptance/p1-baseline-r39-20260911/baseline.json
```

结果：`identical=true`、`comparedKeys=20`、`differences=[]`（`verify/compare-report.json`）。

**首轮比对曾报 3 处不一致，根因与修正如实记录（不是一次就一致）**：

| 差异段 | 根因 | 修正 |
|---|---|---|
| `baselineId` | 取输出目录叶子名，二次读取写到 `verify/` 必然不同 | 列入 `volatilePaths.topLevel`（并写理由） |
| `artifacts` | 普通哈希表枚举顺序随进程随机化，4 个 jar 的数组顺序不稳定 | 改 `[ordered]` 字典 + `Sort-Object role`，两次输出顺序固定 |
| `metadataDb.tables` | `user_session` 因脚本自身登录 153 → 154 | 比对前把该表行数归一为 `null`，并在 JSON 中登记 `selfInflicted` |

归一化清单（其余字段变化即视为基线漂移）：`baselineId`、`generatedAt`、`durationSec`、`rawFiles`、`services`、`repo.dirtyFiles`、`landing.ingestionApiStatus.checkedAt`、`metadataDb.tables[user_session].rows`。

## 9. 一致性检查清单（19/19 PASS）

| id | 观测 |
|---|---|
| `run-status-success` | `pipeline_run 39 status=SUCCESS` |
| `run-target-snapshot` | `target_snapshot_id=S20260901_39` |
| `snapshot-row-exists` | `metric_snapshot` 行数=1 |
| `snapshot-active-and-linked` | `status=ACTIVE pipeline_run_id=39` |
| `snapshot-active-unique` | 指标库 ACTIVE 快照数=1 |
| `metric-values-all-day-period` | 非 `day:2026-09-01` 口径行数=0 |
| `metric-values-present` | 指标值行数=10 |
| `wait-landing-batch` | `WAIT_LANDING batchId=31 acceptedRecords=1000` |
| `strict-generator-single-batch` | `gen-s3b` 消费记录数=1，records=1000 |
| `ods-physical-rows-equal-generator` | `LOAD_ODS` 物理写入行数合计=1000 |
| `stage-records-match-evidence` | 带 jobs 的 7 个阶段 `records` 与 `evidence.outputRecords` 全一致 |
| `downstream-record-counts-frozen` | DWD=159 DWS=10 ADS=30（逻辑） |
| `ads-mirror-rows-match-stage` | 镜像行数 30 = `BUILD_ADS` 逻辑 30 |
| `landing-file-count-matches-api` | 目录 52 文件 = API `pendingFiles=52` |
| `checkpoint-not-exceeding-pending` | `checkpointFiles=52 ≤ pendingFiles=52`（D-024 不变量） |
| `checkpoint-spelling-unique` | 102 行 / 102 个不同路径 |
| `legacy-spelling-residual-untouched` | 历史 `\.\` 写法 50 行保持原样 |
| `warehouse-nonempty` | parquet 972 文件 / 4,977,445 B |
| `landing-files-classified` | 未分类文件数=0 |

## 10. 未采集项（`notCollected`）

1. **Hive 物理全表行数**：P1-01 禁止启动 Spark，只能给出分区/文件/字节级清单；行级物理计数留待 **P1-06** 的 T2 一次性采集。
2. **多源登记状态**：`source_registry` 尚不存在，属 **P1-02**。
3. **50 行历史 `\.\` checkpoint 的合并**：不可逆数据变更，D-024 明确"不授权开发 Agent 擅自删除"，需用户按范围确认后另行执行。

## 11. 命名陷阱（写结论前必须核对）

**"run 39" ≠ "batch 39"**：`analytics_meta.pipeline_run.id=39` 是本基线冻结的 Spark 全链路（15:08→15:12）；`ingestion_batch.id=39` 是 B-11 验收的 2 条夹具批次（16:29）。两者同号不同域，引用时务必带表名。

## 12. 复现与复核

```powershell
# 首次采集（约 150–190 秒；exit 0=通过，2=检查失败，3=致命错误）
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/accept-p1-baseline.ps1 `
  -OutDir docs/acceptance/p1-baseline-r39-20260911

# 二次读取 + 一致性比对（总控复核用；把 -CompareWith 指向待比对的 baseline.json）
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/accept-p1-baseline.ps1 `
  -OutDir <临时目录> -CompareWith <基线>/baseline.json
```

- 只读保证：脚本不执行任何非 `SELECT/SHOW/WITH` 语句；`-CompareWith` 只读基线文件。
- 复核要点（总控）：① 19 项检查是否全绿；② `verify/compare-report.json` 是否 `identical=true`；③ §4 行数口径、§5 分区口径、§6 来源分类是否满足后续阶段引用需要；④ README 与 `baseline.json` 数值是否一致。
- 复核通过后本任务转 `DONE`；若对口径有异议，先改本 README/脚本再进入 P1-02/P1-04。

## 13. 变更记录

| 时间 | 变更 |
|---|---|
| 2026-09-11 17:40 | 首次采集（`baseline.json` + `raw/` 19 文件），19/19 检查通过，耗时 147.1 s |
| 2026-09-11 17:43 | 二次读取比对；首轮报 3 处差异（`baselineId`/`artifacts` 顺序/`user_session` 自增），定位并修正为确定性输出 |
| 2026-09-11 17:46 | 修正后复采 + 复比：`identical=true`、`differences=[]`；本 README 随证据提交，任务登记为 `REVIEW` |
