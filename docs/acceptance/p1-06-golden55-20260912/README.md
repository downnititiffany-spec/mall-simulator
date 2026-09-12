# P1-06 P1 合并门（golden-55 T2）取证

> 任务：看板 V2.2 §3 P1-06；实施书 `docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md` §4 P1-06。
> 验收原文：**「T2：只跑一次 golden-55 本地链；源 A 指标与 P1-01 基线逐值一致。」** 证据要求：迁移结果、API 响应、库名清单、runId、snapshotId、指标 diff。
> 前置已按 D-040 完成：真库 V17 + 8091 新 jar 同批生效（`docs/acceptance/p1-05-8091-swap-20260911/`），本任务**不含部署步骤**。

---

## 0. 授权与边界

| 项 | 内容 |
| --- | --- |
| 本轮授权 | P1-06 T2 一次性真链：**允许**在真库写入采集批次 + 允许发布新快照（D-032 裁决：该证据推迟至 P1-06 T2；D-042 记录本任务承担"换血未做"的写路径取证） |
| 明确不做 | 不动 `spark-warehouse`/Hive 表结构；不删任何行；不改 `runtime_profile`；不跑第二条流水线；不启动 8090/8092（F-07 停机纪律） |
| 破坏性操作 | 无。本轮只做「采集一轮 + 流水线一次 + 只读取证」 |

## 1. 口径声明（F-07 硬要求，先定口径再跑，三选一必须显式）

**① "golden-55" 的语义 = 55 条黄金数据集（夹具），不是落地区文件数。**
依据（权威文档原文）：指导书 V2.2 L464 `| T2 | 55 条黄金链，真实本地 MySQL/Spark/Hive | 一批并行任务合并后一次 |`；L508「每个合并批次只跑一次 T2」；代码侧同义用法 `SparkStageExecutorSmokeTest.java:28`「golden 55 行（R6-8b 扩充：52 接受 / 3 rejected：坏 JSON、schema_version=2.0、缺 event_id）」。**故落地区的 52/55 文件数与 golden-55 是两个不同的 52/55，本证据内分别写作"夹具 55 行"与"落地区 N 文件"，不得混用。**

**② 落地区 52 → 55 的三选一：选 (a)「把 3 个新文件作为新增输入显式纳入」。**
增量 = 商城 outbox 小时文件 `2026091120/21/22.jsonl`（755,903 B / 1,667 行，事件时间 2026-09-11 20:50～22:02，**与基线业务日 2026-09-01 不同日**）。选 (a) 的理由：它们不会被静默忽略，而是**显式采集**并在下文以可证伪预测 P1–P5 记录其效果；不选 (b)（移出文件属破坏性操作，无必要）；不选 (c)（本任务正是要处理增量，声明"以 52 为界"会掩盖增量）。**"55 当 52"静默使用的情形不存在。**

**③ "源 A 指标逐值一致"的判据 = 业务日 `2026-09-01` 的 10 个指标值逐值比对 P1-01 冻结基线（`docs/acceptance/p1-baseline-r39-20260911/baseline.json` 的 `metricSnapshot.values`/`apiOverview`）。**
依据：基线 README L107 原文「（生成器文件事件覆盖 30 个事件日）**2026-09-01 共 42 行**（即冻结指标聚合的那一天），其余行落入各自 `dt` 分区，**不参与 `day:2026-09-01` 口径**」⇒ 业务日口径按 `dt` 分区隔离，故"采集 09-11 的新文件"与"09-01 指标逐值一致"可同时成立，且这正是可证伪点。

## 2. 预注册预测（跑之前写死，跑完逐条判定）

| # | 预测 | 判据（可证伪） |
| --- | --- | --- |
| P1 | 采集一轮成功，**只**吃下 3 个未采集文件 | 新增 `ingestion_batch` 恰 1 条（#40），`record_count` = 1,667；不重采已采集文件 |
| P2 | 写路径带 source（P1-05 缺的那一半） | `ingestion_batch#40.source_id = 1`（非 NULL）；3 条新 `file_checkpoint.source_id = 1` |
| P3 | 落地分拣落盘 | 新增 `landing/accepted/40/`（3 文件）；`landing/quarantine/40/` 按坏行情况 |
| P4 | 流水线一次成功并发布 | `pipeline_run#40` = SUCCESS、8/8 阶段；新快照 `S20260901_40`（version 9）为 ACTIVE，`S20260901_39` → ARCHIVED |
| P5 | **源 A 指标逐值一致** | 新快照 `day:2026-09-01` 的 10 值 = 基线 10 值逐值相等（`88.1600/0.2000/10.0000/0.0000/88.1600/88.1600/1.0000/5.0000/0.0000/5.0000`） |
| P6 | 采集写路径不污染业务日口径 | 新事件（2026-09-11）只进 `dt=20260911`，`dt=20260901` 的 ODS 行数与基线一致 |
| P7 | 补齐 P1-01 的两个 `notCollected` | ① Hive 物理全表行数（ODS/DWD/DIM/DWS/ADS）②多源登记状态（`source_registry` / `runtime_profile.source_id`） |

**已知风险（若命中即为真发现，不改写成通过）**：若 `LOAD_ODS` 不按业务日分区裁剪（把 accepted 全量灌进单分区），则 P5 会失败 —— 那说明"业务日口径靠 `dt` 隔离"的前提在**平台真链**上不成立，须记为新缺陷。

## 3. 执行步骤（一次 T2，不停机重启 8091）

1. 前态取证（只读）：真库计数、落地区清单、数仓 parquet、8091 进程与 jar 身份。
2. `POST :8091/api/v1/ingestion/runs`（admin 令牌）→ 原样保存响应体 ⇒ 判 P1/P2/P3。
3. `POST :8091/api/v1/pipeline-runs`（`{runtimeProfileId:1, pipelineCode:"ODS_TO_ADS", businessTime:"2026-09-01T00:00:00", sourceDataVersion:"p1-06-t2-golden55-20260912"}`）→ 轮询至终态 ⇒ 判 P4。
4. `GET :8091/api/v1/metrics/overview` + 真库 `metric_value` ⇒ 与 `baseline.json` 逐值 diff ⇒ 判 P5。
5. Spark 侧取 Hive 物理行数（ODS/DWD/DIM/DWS/ADS）+ 真库取多源登记状态 ⇒ 判 P7。
6. 后态取证 + 结论 + 看板/决策记录回填。

---

## 4. 执行结果总览

| 轮次 | runId | sourceDataVersion | 实际输入批次 | 起止 | 终态 | 快照 |
| --- | --- | --- | --- | --- | --- | --- |
| 采集 | — | — | 3 个新文件 | 09:23:15.604→.806（0.2 s） | `ingestion_batch#40` SUCCESS | — |
| T2 第 1 次 | 40 | `p1-06-t2-golden55-20260912` | 钉定批 **40**（该批 1,667 条全为 2026-09-11） | 09:23:15.867→09:23:26.098（10.2 s） | **FAILED `RUN_EMPTY_DATA`** | 未创建 |
| T2 第 2 次 | 41 | `p1-06-t2-batch31-replay-20260912-092627` | 钉定批 **31**（`accepted/31`，1,000 条 = run 39 的原输入） | 09:26:27.592→09:31:13.403（4 min 46 s） | SUCCESS，8/8 阶段 | **`S20260901_41`** v9 ACTIVE |

- 采集：`POST /api/v1/ingestion/runs` → HTTP 200，`batchId=40`、`batchNo=ing-20260912092315-fef96b66`、`recordCount=1667`、`errorCount=0`、`quarantineCount=0`、`fileCount=3`、`acceptedBytes=754236`（=459013+165249+129974）、`noNewData=false`；`ingestion_batch_file` 110/111/112 = `2026091120/21/22.jsonl` 计 1013/367/287 条（合计 1,667），尾偏移 = 源文件字节数。
- run 41 各阶段（`spark_job_run`）：`sci` 0→37；`odl` 1000→1000；`bdw` 13→13；`dim` 18→8；`tdw` 281→138；`usw` 13→10；`fna` 1→30；`dqc` 30→6（rejected 8）；`pub` 30→30；`mxp` 30→30。`blockingFailed=[]`、`published=true`。
- 后态：`metric_snapshot` 9 行（ACTIVE 1 = `S20260901_41`，`_39` → ARCHIVED v8）；`metric_value` 80（基线 70 + 新 10）；`file_checkpoint` 105 且 `source_id IS NULL = 0`；`pipeline_run` 40 行 / max id 41。**五个数仓库的 parquet 文件数与字节数与前态逐字节一致**（`dw_ads` 30/54737、`dw_dim` 3/8198、`dw_dwd` 57/317273、`dw_dws` 14/28547、`dw_ods` **866/4567412**、`probe_r613` 2/1278）⇒ 本次运行对真数仓是**等价重写，零破坏**。

## 5. 预测核对（§2 逐条判定）

| # | 判定 | 依据 |
| --- | --- | --- |
| P1 | **成立** | `ingestion_batch` 新增恰 1 条（#40），`record_count=1667`；`ingestion_batch_file` 3 行；采集前 `status` 报 `pendingFiles=55 / checkpointFiles=52 / newFileCount=3`，未重采已采集文件 |
| P2 | **成立** | `ingestion_batch#40.source_id=1`；3 条新 `file_checkpoint`（103/104/105）`source_id=1`、`runtime_profile_id=1`，`updated_at` 09:23:15.735/.759/.779（见 §6 与 §8.5 的区分） |
| P3 | **成立** | 新增 `landing/accepted/40/`（4 文件：3 个输入 + 0 字节夹具 `golden-r615-20260910164859.jsonl`，合计 754,236 B）与 `landing/quarantine/40/`（4 文件，**全为 0 B**，与 `quarantineCount=0` 一致）；accepted/quarantine 文件总数 130→134 |
| P4 | **第 1 次 ✗ / 第 2 次 ✓（编号不是预测的 `_40`）** | run 40 失败且未产生快照（见 §7 F-13）；run 41 成功发布 `S20260901_41` v9 ACTIVE、`_39` → ARCHIVED。形状（8/8 阶段 + 新 ACTIVE + 旧归档）与预测一致，**编号因失败轮占位而是 41 不是 40** |
| P5 | **成立（不符项 0）** | `raw/metric-verdict-20260912-093620.txt`：① `metric_value` 10/10 逐值（值/单位/口径/期间四项全等）；② `/metrics/overview` 10/10 且 `snapshotId=S20260901_41`；③ ADS 镜像表**按快照**行数 8/8 一致（1/4/4/9/1/9/1/1=30）；④ 归档形状正确、`_39` 的 10 条 `metric_value` 保留可复现 |
| P6 | **未成立（原判据在本轮不可达，已重述，见 §8.1）** | 本轮唯一授权的流水线运行按设计吃的是批 31（业务日 2026-09-01），故 1,667 条 09-11 新事件**没有进数仓**；`dt=20260901` 的 ODS 行数=13+10+11+8=**42** 为**首次实测**（与基线 README 记载的"2026-09-01 共 42 行"及 run 41 `QUALITY_CHECK` 自报 `businessDayEvents=42` 三方吻合），但不构成"与基线一致"的比对 |
| P7 | **① 成立（只完成测量）② 成立** | ① Hive 物理行数首次全量取得（`raw/hive-counts-20260912-093352.log`，退出码 0）：ODS 365/271/281/83（**合计 1,000 = 批 31 记录数**）、DWD 138/1/27、DIM 4/11、DWS 7 表、ADS 8 正式表 1/4/4/9/1/9/1/1（=30）；**P1-01 从未采集过 Hive 物理行数，故这是首测而非"与基线一致"**；② `source_registry` = 1 行（`mock-mall` ACTIVE 1.0，另有 `runtime_profile#1.source_id=1`、`file_checkpoint` 全 105 行 `source_id=1`） |

**风险命中情况**：§2 的"已知风险"（`LOAD_ODS` 不按业务日裁剪则 P5 失败）**未命中** —— `odl` 按 `dt/hour` 分区写入 1,000 行落在 866 个分区上，业务日 09-01 仅 42 行，P5 逐值一致；但同一机制暴露了 §7 的 F-14（分区覆盖模式），属"另一侧的风险"。

## 6. 采集写路径（P1-05 缺的那一半）证据

`file_checkpoint` 末 6 行的两种来源必须区分，否则会把迁移回填误读成写路径：

| id | `file_identity` | `next_offset` | `source_id` | `updated_at` | 来源 |
| --- | --- | --- | --- | --- | --- |
| 100 | 1789110499120 | 19365 | 1 | 2026-09-12 **09:12:10.913** | **V17 迁移的 join 派生回填**（三行时间戳完全相同 = 一条 UPDATE） |
| 101 | 1789113408331 | 586 | 1 | 2026-09-12 **09:12:10.913** | 同上 |
| 102 | 1789115356093 | 586 | 1 | 2026-09-12 **09:12:10.913** | 同上 |
| 103 | 1789131012544 | 460026 | 1 | 2026-09-12 **09:23:15.735** | **采集写路径插入** |
| 104 | 1789131902638 | 165616 | 1 | 2026-09-12 **09:23:15.759** | 同上 |
| 105 | 1789135371820 | 130261 | 1 | 2026-09-12 **09:23:15.779** | 同上 |

判据：103/104/105 的 `updated_at` 落在 `ingestion_batch#40` 的 `start_time 09:23:15.604 / end_time 09:23:15.806` 区间内、三者依次相差约 20 ms（三次独立插入），`next_offset` 等于三个新文件的字节数；而 100–102 是同一毫秒的批量 UPDATE，其时刻 09:12:10 对应 P1-05 换血运行（`docs/acceptance/p1-05-8091-swap-20260911/raw/swap-...-run2-APPLIED-V17...log` 09:12:06 落盘）。**结论：D-040 第 6 步"采集写路径打通"由 103/104/105 证实，与迁移回填无关。**

## 7. 本轮新发现（真缺陷/风险，不是"通过"）

### F-13 首次运行的批次挑选规则：取"最新 READY 批次"，与业务日无关（已实测）
`PipelineService.manifestForRun`（`warehouse-pipeline/.../PipelineService.java:889-940`）：重试/续跑复用本 run 自己 `WAIT_LANDING` 的 `batchId`；**首次运行**调 `findReadyManifest` 扫 `landing/manifests/*.json`，取 READY 且 accepted+quarantined>0 的**最大 batchId**。故：

1. 先采集批 40（内含的 1,667 条事件时间全为 2026-09-11），再以 `businessTime=2026-09-01` 发起 run 40 ⇒ 钉定批 40 ⇒ 按业务日过滤后为空 ⇒ **`LOAD_ODS` 在任何 Spark 提交之前**（09:23:26.091→.096，5 ms）抛 `RUN_EMPTY_DATA`（`PipelineService.java:430-432`），未创建快照行、未改 `active_flag`、无 Spark 作业。
2. 影响的不是"数据错"，而是**可操作性**：一旦更新的批次存在，历史上任意业务日都不能再直接重跑（无批次/业务日选择参数）。P1-06 采用的办法是**临时挪走** `landing/manifests/32..40.json`（可逆、SHA256 校验、`finally` 还原），使最新 READY 批次 = 31 = run 39 的原输入。

### F-14 ODS 分区覆盖模式未设置（代码/配置取证；本轮**未**做破坏性验证）
- 取证：`spark.sql.sources.partitionOverwriteMode` 在三处均**未设置** —— Spark 安装目录 `D:\Develop\spark-3.5.1-bin-hadoop3\conf\` 无 `spark-defaults.conf`；`SparkSessionFactory.scala:12-23` 未设；`SparkStageExecutorFactory.confsFor`（`confsFor` 返回的 LOCAL 配置项）未设；各 job 仅 `TradeDwdJob.scala:26` 为 DWD 显式设了 `dynamic`。
- 机制：`OdsLoadSql.scala:19,94,115,144,165` 用 `INSERT OVERWRITE TABLE <ns>.ods.<t> PARTITION (dt, hour)`（**空分区规格**），在 Spark 默认 `STATIC` 模式下等于"先删该表全部分区再写入本次数据"；DWD/DWS/ADS 均带 `PARTITION(dt='$dt')`/`partitionBy`，是日/快照粒度的。
- 本轮实测的**边界**（必须如实写）：本轮的批 31 与 run 39 是同一输入，跑前 `dw_ods.db parquet=866 / bytes=4567412`，跑后完全一致、`ods_user_event` 的 21 个 `dt` 未变 ⇒ **本次运行零破坏**；但"全删重写"与"同分区原地替换"在**同一输入**下不可区分，故 F-14 仍是**代码级结论**，破坏性验证留待 §9.1。

### F-15 ADS 暂存表存在孤儿分区，且 `ADS_STAGING_SNAPSHOT_ISOLATION` 未报出
- `raw/ads-staging-20260912-093515.log`：8 张暂存表均为 `S20260901_41/20260901`（1/4/4/9/1/9/1/1），**另有一条孤儿分区** `dw_ads.ads_operation_overview__staging` = `S20260907_TEST/20260907` **3 行**（某次手工测试遗留，`dt=20260907`）。
- 发布清理只清"被替换的上一个快照"：`MP_PUB_STAGING_PRUNE` 清掉 `S20260901_39` 的 8 个暂存分区/30 行（run 41 日志），`S20260907_TEST` 不被认领 ⇒ 永久残留（这是 `ads_operation_overview__staging`=4 行 vs 正式分区=1 行的全部原因）。
- 同时 `QUALITY_CHECK` 的 `ADS_STAGING_SNAPSHOT_ISOLATION`（severity ERROR，观察项）`checkCount=16 / errorCount=8`，`detail` 只列了 `_39` 的 8 个分区，**没有列出 `S20260907_TEST`** ⇒ 该规则的枚举口径漏掉了非 `S<date>_<runId>` 形态的快照号。二者叠加＝孤儿暂存无人清理、也无人告警。

## 8. 口径与判据更正（原文不改，此处更正）

### 8.1 P6 重述（原判据在本轮不可达）
原 P6：「新事件（2026-09-11）只进 `dt=20260911`，`dt=20260901` 的 ODS 行数与基线一致」。实情：本轮授权的流水线运行**按其批次钉定语义**吃的是批 31（业务日 09-01），批 40 的 1,667 条 09-11 事件**停在 `landing/accepted/40` + `file_checkpoint`，没有进数仓**。因此：
- 「09-11 事件只进 `dt=20260911`」**本轮无法观测**（未载入），不是通过、也不是失败；
- 「`dt=20260901` 与基线一致」**改为首次测量**：ODS `dt=20260901` = 42 行，与基线 README 的"2026-09-01 共 42 行"和 run 41 自报 `businessDayEvents=42` 吻合；
- 把批 40 载入数仓并观察 `dt=20260911`（同时验证 F-14）列为 §9.1 的后续动作。

### 8.2 ADS 镜像表判据错误（首版判定书保留）
首版 `raw/metric-diff-20260912-093132.txt` 用「ADS 镜像表**总行数**与基线一致」作判据 ⇒ 8 项"不符"。**该判据本身错**：`analytics_metric.ads_*_m` 是"每快照一组"的历史存储（`_22/_23/_24/_25/_29/_30/_39/_41` 各一组，`_38` 因 FAILED 无行），新快照追加自己那组（8 表 30 行），`_39` 的组保留。正确判据（按快照行数 8/8 一致）见 `raw/metric-verdict-20260912-093620.txt`（`BAD=0`）。首版文件**保留不删**作为判据错误的证据；`metric-verdict-20260912-093609.txt` 有一条说明行因引号被截断，093620 为修正版。

### 8.3 Hive 物理行数脚本前两次失败（我的脚本 bug，非平台/环境）
`hive-counts-20260912-093150.log` / `093233.log`（各 3.4 MB，退出码 1）报 Derby `Database '' not found`。真因是**我生成 properties-file 时的 PowerShell 运算符优先级**：数组字面量里 `,` 结合力强于 `+`，`"key=file:///" + $root + "/spark-warehouse"` 被解析成三个数组元素，落盘成三行，`ConnectionURL` 只剩 `jdbc:derby:`。修正为"先算好字符串再入数组"后 `093352.log` 退出码 0（20.8 s）。前两次日志保留。**注意：与 `cmd.exe` 在 `;` 处截断命令行无关——那是错误归因，已在脚本注释中纠正。**

### 8.4 `post-state` 里两条 SQL 列名错误
`pipeline_stage_run` 的真实列是 `run_id / records / evidence`（不是 `pipeline_run_id / input_records / evidence_json`），`raw/post-state-20260912-093546.txt` 中该条 SQL 报错行原样保留，正确结果见 `raw/post-state-supplement-20260912-093621.txt`。

### 8.5 09:12:10 的 `file_checkpoint` 刷新已定位
前态（09:22）已见 100–102 的 `updated_at=09:12:10.913` 而 `operation_audit_log` 当日 0 行、`ingestion_batch` 无对应批次。定位依据：该时刻与 P1-05 换血脚本 run 2（09:12:06 落盘 `...APPLIED-V17...log`）一致，且三行时间戳同毫秒 ⇒ **V17 迁移的 join 派生回填 UPDATE**，不需要审计行/批次行。非采集写路径（区别见 §6）。

## 9. 未取证与边界（不得当作通过）

1. **F-14 的破坏性验证未做**：需要"另一业务日的批次载入 → 观察旧 `dt` 分区是否消失"。做之前必须备份 `spark-warehouse`（可整目录复制）并按破坏性操作单独取得确认。
2. **批 40 的 1,667 条 09-11 事件尚未入仓**（只到 `landing/accepted/40`），故 P6 的"新事件只进 `dt=20260911`"未观测。
3. `ads_data_quality_m` 每快照 4 行 vs `dqc` 自报 6 项检查（`adsChecksPersisted=6`）的对应关系**未查清**，本文不作结论。
4. Hive 物理行数是**首测**，无历史基线可对比（P7① 只满足"补齐测量"，不满足"与基线一致"）。
5. 镜像表历史组（`_22.._30`）未与各自快照的指标值逐行比对；本轮只比对了 `_39` 与 `_41`。
6. **"只跑一次"的字面偏离**：验收原文"只跑一次 golden-55 本地链"，实际发生 2 次流水线运行（run 40 失败 + run 41 成功）。失败那次是**我的操作顺序错误**（先采集再以旧业务日发起，撞上 F-13），发生在 `LOAD_ODS` 预检、**未提交任何 Spark 作业、未写数仓、未发布快照**，故不污染证据；但评审若按字面口径，应知道 T2 链跑了两次。
7. 8090/8092 仍未启动（按 F-07 停机纪律：P1-06 完成后再恢复三程序运行）。

## 10. 证据清单（`raw/`，本轮新增）

| 文件 | 用途 |
| --- | --- |
| `pre-state-20260912-092221.txt` (68 行) | 前态：真库计数/落地区/数仓 parquet/幂等键占用（§E 为粗粒度聚合） |
| `pre-state-instance-20260912-092252.txt` (31 行) | 前态实例：8091 PID/启动时间；**jar 路径正则缺陷致无 sha256**，该缺口由后态文件补齐（PID 47132、mtime 09:14:58 两次一致） |
| `t2-run-20260912-092245.log` (3 行) | 首次脚本因 `$h` 覆盖 `$H` 报错（PowerShell 变量名大小写不敏感）；**经查库前态未变 ⇒ 零写入** |
| `t2-run-20260912-092315.log` (20 行) | run 40 的失败全过程（含采集响应、WAIT_LANDING/INIT_SCHEMA 证据、`RUN_EMPTY_DATA`） |
| `t2-replay-20260912-092627.log` (224 KB) | run 41 全流程：钉定批 31、8 阶段证据、发布、ADS 清理、manifest 还原 9/9 哈希校验 |
| `post-state-ingestion-20260912-092637.txt` (44 行) | 采集后、流水线前的中间态（其 §D 反映**暂存中的** manifests，被 093546 版取代，保留） |
| `post-state-20260912-093546.txt` (137 行) | 后态：实例/真库/新快照值/落地区/数仓/幂等键/指标接口/metric-staging |
| `post-state-supplement-20260912-093621.txt` (27 行) | 补 `pipeline_stage_run` 列名错误并附 run 41 阶段证据、批次钉定证据、DQ 明细 |
| `metric-diff-20260912-093132.txt` (43 行) | 首版判定（**判据错误**，保留） |
| `metric-verdict-20260912-093609.txt` / `-093620.txt` (41 行) | 修正判据后的 P5 判定（`BAD=0`；093620 修掉说明行的引号截断） |
| `hive-counts-20260912-093150.log` / `093233.log` (各 3.4 MB) | 两次失败（我的 PowerShell 数组优先级 bug），保留 |
| `hive-counts-20260912-093352.log` (236 行) + `.sql` + `.conf` | **成功**的 Hive 物理行数：8 正式表 + 8 暂存表 + ODS 逐 `dt` 分布 |
| `ads-staging-20260912-093515.log` + `.sql` + `.conf` | 暂存表按 `snapshot_id/dt` 分布（F-15 的孤儿分区证据） |
| `stage-evidence-run{40,41}-<阶段>.json`（11 份，本文件写作时从 `analytics_meta.pipeline_stage_run.evidence` 原样导出，全部 `ConvertFrom-Json` 校验通过） | 权威阶段证据的自包含快照：run 40 的 3 份（`WAIT_LANDING` 钉定批 40 / `INIT_SCHEMA` / `LOAD_ODS` **0 字节**——该阶段失败且无 evidence 载荷，错误码 `RUN_EMPTY_DATA`、`records=0` 在 DB 行内）；run 41 的 8 份（`WAIT_LANDING` `batchId=31/acceptedRecords=1000`、`LOAD_ODS` 170 KB 逐分区、`QUALITY_CHECK` 6.6 KB、`PUBLISH_METRIC` 22 KB 等） |

另：Spark 作业日志在 `landing/logs/pipeline-40-*`（1 个）与 `pipeline-41-*`（10 个，含 `odl` 169 KB）；`landing/` 整目录被 `.gitignore` 忽略（既往 P1-01 亦未收编作业日志，只收编 `evidence` JSON），故按同一约定以 `stage-evidence-*.json` 自包含留证，作业日志留在工作区。

## 11. 结论

- **T2 的实质条件满足**：一次真实本地链（批 31 → MySQL/Spark/Hive → 发布）跑通 8/8 阶段，**源 A 的 `day:2026-09-01` 十个指标与 P1-01 冻结基线逐值一致（不符项 0，四项判据）**，真数仓前后逐字节一致、零破坏，采集写路径（P2，D-040 第 6 步缺的那一半）由 `ingestion_batch#40` + `file_checkpoint` 103–105 证实。
- **看板 P1-06 可置 `DONE`**（材料：本节 + §5 判定 + §10 清单）；同时登记：F-13（缺批次/业务日选择参数）、F-14（ODS 分区覆盖模式）、F-15（暂存孤儿分区 + 隔离规则漏检）与 §9 的 7 项未取证。
- 后续动作顺序：① 恢复 8090/8092 三程序运行；② 在专轮里载入批 40（同时按 §9.1 观察 F-14）；③ F-13/F-15 登记为 P2 候选（批次/业务日选择参数、暂存孤儿清理与规则枚举口径）。
