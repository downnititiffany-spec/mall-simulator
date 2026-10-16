# BATCH-V-STAGE7-PLATFORM-FLUME-RAW-INGESTION — Verification Plan

> 状态：**READY（2026-09-19 总控裁决二批准编制并连续执行：计划置 READY 后可连续执行至 PASS、明确 FAIL/BLOCKED 或 HARD DECISION，无需第二次放行）**
> Exact source/test SHA：`104db41117ea251b5b8e6c1f32c9f61ca4acd659`（代码基线 = T-R3 PASS 收口提交；当前 HEAD `836faca` 仅在其上 docs-only 前移，工作树干净。本批零仓内代码/配置变更——执行驱动脚本落在 `target/v25-it/<RunId>/`（gitignore 覆盖，不入仓）；docs-only 提交造成的 HEAD 前移不触发重钉；若执行中引入任何仓内变更，必须先提交并重钉 Exact SHA，未重钉不得继续执行）
> Branch：feature/v3-development
> RunId：执行时新生成唯一 RunId，格式 `stage7v_<yyyyMMdd_HHmmss>`（不复用任何历史 RunId）
> Predecessor：BATCH-U **PASS**（RunId `stage7u_20260919_170729`，结果 `docs/verification/results/BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN-RESULT.md`，提交 `836faca` 已按总控授权推送，远端 HEAD 已验证 `836facaa22f1e839d7444f1a08d5ce9984b12ff0`；该推送授权已用尽，任何新提交 push 需总控另行授权）
> 批准依据：总控 2026-09-19 裁决二——范围钉死为「复用 BATCH-U 已验 HDFS landing → platform FLUME_RAW ingestion → manifest/1011 行与 event_id 对账」；**不重跑商城/Flume，不把完整 Spark→publish、浏览器、LLM、REMOTE_CLUSTER 混入本批**。
> Git 状态注记（D-001）：本计划与后续状态文档提交仅本地；push 需总控另行授权。
> 口令通道注记：本批**需要** PowerShell 进程环境 `V25_IT_META_PASSWORD`、`V25_IT_METRIC_PUBLISH_PASSWORD`（platform 连 3307 run-scoped 库）与 `V25IT_ADMIN_PWD`（it-prepare-isolation 建 run-scoped 库/账号，只经 `MYSQL_PWD` 环境变量进入 WSL mysql 进程）。任何口令不落盘、不进命令行参数、不进 git。3306 冻结：零连接、零写入、不切 ACTIVE。

## 1. Purpose

BATCH-U 证明了 Flume 1.11.0 Spooling Directory Source + File Channel + HDFS Sink 实链可把 pinned 1011 行输入无损送达 HDFS landing（`/stage7u_stage7u_20260919_170729/landing/raw/dt=20260919/hour=17/events-.1789810142459`，456,825 B / 1011 行），但**平台侧 FLUME_RAW 采集从未运行**（BATCH-U 全程零 MySQL、零 platform）。本批闭合该缺口：把 BATCH-U 已验的 HDFS landing 数据文件交接到平台 FLUME_RAW 输入区，经真实 HTTP API 驱动 platform（8091、3307 run-scoped meta/metric 库）完成一次 ingestion，并对账：

1. 采集批次 **manifest**（`<landing>/manifests/<batchId>.json`，status=READY，acceptedRecords=1011）；
2. **1011 行 event_id 集合双向对账**（pinned 输入 vs accepted 落盘记录，LOST=0、EXTRA=0、DUPLICATE=0）。

## 2. Pinned input（复用 BATCH-U 已验 HDFS landing，只读）

- HDFS 数据文件：`/stage7u_stage7u_20260919_170729/landing/raw/dt=20260919/hour=17/events-.1789810142459`
  - 456,825 B；1011 行；HDFS checksum `0000020000000000000000002446c8f7ee4f392fb03af2e0a93ec13b`（BATCH-U §4 登记值，执行时复核一致才继续）。
- 内容基准（BATCH-U 钉死）：SHA256 `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`；1011 行、1011 unique event_id；source_system=mock-mall；业务日 2026-09-18（HDFS 分区 `dt=20260919/hour=17` 是 Flume ingest 时刻，与业务日无关）。
- 同目录 3 个 0 字节 sink-retry 残迹文件（BATCH-U 已登记"从未承载数据"）：**一并 copyToLocal 保留**，作为平台所有者规则（`LandingInputScanner`：零字节=候选未完成、不采集）的真实负样本——预期 fileCount=1 而非 4。
- NN/DN 当前停止（BATCH-U 收口态），run-scoped conf/数据目录原样保留：`/home/asus/stage7u_20260919_170729/`（`hadoop-conf`：fs.defaultFS=hdfs://127.0.0.1:9100、replication=1、run-scoped NN/DN 数据目录）。**不 reformat**。

## 3. Safety and exact runtime

### 3.1 环境事实（2026-09-19 代码研读登记，本计划编制时逐条核实过源码）

- 平台 FLUME_RAW 契约（`connection-ingestion` 模块）：
  - `LandingLayout.FLUME_RAW`：输入根 = `<landing>/raw`，**递归**枚举，`inputKey` = 输入根相对路径正斜杠（本批恰为 `dt=20260919/hour=17/events-.1789810142459`）；
  - `LandingInputScanner.scan()` 只收**已完成**文件：排除 `.`/`_` 前缀路径段（含整目录）、`*.tmp`、非普通文件；**零字节是候选但未完成**（不采集、观测可见）；
  - `IngestionService.runOne()`：ACTIVE RuntimeProfile + 绑定源 fail-closed（`SOURCE_NOT_BOUND` 无兜底）；映射三态 fail-closed 于批次行 insert 之前（v1 只读兼容画像 → `SourceMapping.legacy()` 直通，T 批次既有行为）；批次行**总是**插入；`errorCount>0 → FAILED 且不产出 manifest`；`quarantineCount>0 → QUARANTINED`；否则 `SUCCESS` + manifest（schema status 恒 `READY`）；`noNewData = !anyNewBytes`；
  - accepted 落盘：`<landing>/accepted/{batchId}/<源文件名>`，每行 = 映射后 canonical 行 + `\n`；v1 直通时即原始行逐字节（本批预期与 pinned 输入 SHA256 一致）；
  - 断点：uk `(runtime_profile_id, source_id, file_path 绝对规范路径, file_identity=Windows创建时间戳)`；落地成功才推进（本批预期 next_offset=456825）；
  - 清单 `files[].file` 是**文件名**（F-31 已登记契约问题：FLUME_RAW 同名冲突待裁决）——本批单文件不触发，仅登记观测。
- HTTP 驱动面（`platform-app` controllers，admin/admin123 → Bearer token）：
  - `GET /api/v1/metrics/health`（就绪探测）；`POST /api/v1/auth/login`；
  - `GET/PUT /api/v1/runtime-profiles/1`、`POST /api/v1/runtime-profiles/1/test`、`POST /1/activate`、`GET /active`；
  - `POST /api/v1/sources/1/activate`（幂等：源置 ACTIVE + 绑定 ACTIVE profile.source_id）；
  - `POST /api/v1/ingestion/runs`（返回 `RunResult`：batchId/batchNo/status/recordCount/quarantineCount/errorCount/fileCount/acceptedBytes/acceptedDir/quarantineDir/manifestUri/noNewData）；`GET /api/v1/ingestion/status`、`GET /api/v1/ingestion/batches`。
- 种子事实：V7 种子 profile 1（`local-dev`，DRAFT，landing `file://./landing`）；V16 种子源 `mock-mall`（id=1，FILE，画像 `analytics-server/source-profiles/mock-mall.v1.json`）并回填 profile 1 `source_id=1`；V22 加列 `landing_layout`（可空，空值等价 ROLLING_LOG；本批显式置 `FLUME_RAW`）。
- `LandingUri.resolve` 接受 `file://D:/...` 与 `file:///D:/...`（Windows 盘符已核实）；拒绝 `hdfs://` 等非 file 协议——landing 根必须落**本地盘**，故 HDFS→本地交接是本批必经步骤（平台以 java.nio 读本地 landing）。
- 隔离面：`scripts/it-prepare-isolation.ps1 -RunId <RunId> -Confirm -AllowRootOnIsolated -IncludeAnalytics`（幂等 IF NOT EXISTS）在 3307 建 `<RunId>_analytics_meta` / `<RunId>_analytics_metric` + metaapp/metricapp 受限账号；root 口令只经 `V25IT_ADMIN_PWD`（进程环境）→ `MYSQL_PWD`（WSL mysql 进程环境）。
- platform 启动模式钉死自 `scripts/stage7-http-isolated.ps1`（T 批次既有，本批不改它、不执行它）：env `PLATFORM_META_URL/USER/PASSWORD`、`PLATFORM_METRIC_PUBLISH_*`、`PLATFORM_METRIC_READ_*`、`PLATFORM_LANDING_LOCAL_ROOT`、`PLATFORM_SOURCE_PROFILE_ROOT`、`PLATFORM_SPARK_WAREHOUSE_DIR`、`PLATFORM_SPARK_METASTORE_DIR`；`java -Dfile.encoding=UTF-8 -Dplatform.metric.publish.export-dir=<staging> -jar platform-app.jar`；Derby metastore 目录**不得预创建**；8091 已被监听则拒绝。
- profile test 四检（landing 读写 / Hive / Spark / MetricStore）：沿用 T 批次通过配置（`sparkSubmitPath=D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd`、`sparkJobJarUri=<root>\spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar`、`hiveDatabasePrefix=null`）。

### 3.2 钉死项

- **WSL 命令模式**：`wsl -e bash -c "tr -d '\r' < '/mnt/d/.../<script>.sh' > /tmp/x.sh && bash /tmp/x.sh 2>&1 | tail -N"`；HDFS daemon 启动 `setsid nohup hdfs --daemon start namenode|datanode </dev/null >/dev/null 2>&1`（防 SIGHUP，BATCH-U 事件 ② 教训）。
- HDFS 重启**禁止 reformat**；启动后 `hdfs dfsadmin -report` 确认 1 NN + 1 DN live，再复核 §2 数据文件 checksum/行数/字节数与 BATCH-U 登记值一致；不一致 ⇒ 立即 BLOCKED_ENV 停止（不许带着漂移的输入继续）。
- copyToLocal 目标 = `<runroot>/landing/raw/dt=20260919/hour=17/`（保留分区层级与文件名，含 3 个 0 字节文件）；落地后本地 SHA256/行数复核 == §2 钉死值。
- platform jar 执行时从当前 worktree 重建（`mvn -f analytics-server/pom.xml -DskipTests package`），留构建时间戳证据。
- 8091/3307 端口预检；8091 被占 ⇒ 拒绝执行（不误打其它平台实例）。
- 3306：零连接、零写入、不切 ACTIVE（platform 全部 JDBC URL 均 3307，启动 env 留证）。
- 不改任何迁移（V1~V29）、不改质量阈值、不改仓内脚本；`.zcode/` 不入 git。
- 执行中所有证据归档 `target/v25-it/<RunId>/`（含 `evidence-summary.json`）。

## 4. Exact execution

- **Phase 0 预检**：`git status --porcelain` 干净（除 `.zcode/`）且 HEAD=836faca+；`git diff 104db41 --stat -- analytics-server spark-jobs scripts` 为空（代码基线一致）；WSL 可达；3307 监听中；8091 空闲；`/home/asus/stage7u_20260919_170729/` 与 hadoop-conf、run-scoped NN/DN 数据目录在位；三个口令环境变量在 PowerShell 进程环境（**缺失 ⇒ 停止，把精确设置命令交总控执行，不得代填**）；生成 RunId。
- **Phase 1 HDFS 重启与输入复核**：run-scoped conf 启动 NN/DN（setsid nohup，无 reformat）→ dfsadmin report → `hdfs dfs -checksum/-cat | wc -c/-cat | wc -l` 三复核 == §2。
- **Phase 2 HDFS→本地交接**：`hdfs dfs -copyToLocal` 主目录 4 个文件（1 数据 + 3 零字节）→ 本地 `landing/raw/dt=20260919/hour=17/` → SHA256/行数/大小复核；目录树与字节证据归档。
- **Phase 3 平台起航**：重建 platform-app jar → `it-prepare-isolation.ps1`（RunId-scoped 双库双账号）→ 写 run-scoped 驱动脚本 `target/v25-it/<RunId>/flume-raw-ingestion.ps1`（复用 stage7-http-isolated.ps1 的启动/env/登录模式，landingLayout=FLUME_RAW，**不含 pipeline 段**）→ 启动 platform（8091）→ health → login → GET/PUT profile 1（`landingUri=file://<本地 landing 根>`、`landingLayout=FLUME_RAW`、spark 两项、`hiveDatabasePrefix=null`）→ `POST /1/test`（allPassed=true 才继续）→ `POST /1/activate` → `GET /active`（留证 landingLayout=FLUME_RAW）→ `POST /api/v1/sources/1/activate`（幂等绑定留证）。
- **Phase 4 触发采集**：`POST /api/v1/ingestion/runs` → RunResult 留证；`GET /ingestion/status`、`GET /ingestion/batches` 留证；随后**第二次** `POST /runs`（断点语义负样本：预期 `noNewData=true`、recordCount=0、fileCount=0；对账一律以第一批 manifest/accepted 为准）。
- **Phase 5 对账**（manifest + 1011 行 + event_id，§5 V-5~V-10 逐条取证）：manifest JSON 断言；accepted 文件 SHA256/行数/event_id 集合双向比对（排序后 `comm`/PowerShell 集合差，LOST=0、EXTRA=0）；quarantine 目录空；第二批不产生 count>0 manifest。
- **Phase 6 收口**：停 platform（按 PID 树精确清理）与 NN/DN（`hdfs --daemon stop`）；证据归档；`evidence-summary.json`；RESULT 文档；CURRENT_BATCH.md / PROJECT_STATUS.md 更新；本地 docs 提交（D-017 时钟 `date '+%Y-%m-%d %H:%M:%S %z'`，**不 push**）；最终报告呈报总控。

## 5. PASS criteria（钉死 11 项，全部满足才 PASS）

- **V-1 HDFS 保真**：无 reformat 重启；NN/DN live；数据文件 HDFS checksum/字节数/行数 == BATCH-U 登记值（456,825 B / 1011 行 / `…2446c8f7…`）。
- **V-2 交接保真**：本地 `landing/raw/dt=20260919/hour=17/events-.1789810142459` SHA256 == `f32906…bbcc`、1011 行、456,825 B；4 文件齐（3 个 0 字节在位）。
- **V-3 平台隔离**：platform 于 8091 就绪；meta/metric JDBC URL 全部 3307 且带 RunId 前缀（env 留证）；零 3306 URL。
- **V-4 运行环境**：profile test `allPassed=true`；ACTIVE profile `landingLayout=FLUME_RAW`、`landingUri=file://<本地 landing 根>`；源绑定 source_id=1（mock-mall）留证。
- **V-5 采集结果**：`RunResult` = status `SUCCESS`、recordCount **1011**、quarantineCount **0**、errorCount **0**、fileCount **1**、noNewData **false**、manifestUri 非空。
- **V-6 manifest**：`status="READY"`、`acceptedRecords=1011`、`quarantinedRecords=0`、`files[0].file="events-.1789810142459"` 且 `acceptedRecords=1011`、`endOffset=456825`、`source="local-file"`、`sourceCode="mock-mall"`、`sourceId=1`；批次行 status=SUCCESS（`GET /ingestion/batches`）。
- **V-7 event_id 对账**：accepted 文件 event_id 集合 vs pinned 输入集合**双向零差**（LOST=0、EXTRA=0），1011=1011 且 unique=1011；同时登记 accepted 文件整体 SHA256 与输入的关系（v1 直通下预期逐字节一致；若不一致必须给出逐行 diff 定性且 event_id 集合仍双向零差，不得静默放过）。
- **V-8 隔离零污染**：quarantine 目录 0 文件；3 个 0 字节残迹**未被采集**（由 V-5 fileCount=1 与 accepted 目录文件清单证明）。
- **V-9 断点语义**：第二次 `POST /runs` 返回 `noNewData=true`、recordCount=0；第二批 manifest（若产出）recordCount=0 且不满足下游 count>0 口径；第一批 accepted 未被重复写入（1011 不翻倍）。
- **V-10 边界合规**：3306 零连接零写入；仓内代码/脚本零变更（收口时 `git status` 仅 docs 与 `.zcode/`）；口令零落盘零入参零入库。
- **V-11 证据完备**：`target/v25-it/<RunId>/` 含 driver 脚本、platform 日志、RunResult/manifest/status/batches JSON 快照、对账 diff 输出、evidence-summary.json。

## 6. Failure classification（不得以控制台单行日志分类）

- **FAIL_INPUT_DRIFT**：Phase 1/2 任一复核与 §2 钉死值不一致（HDFS checksum 漂移、行数/字节不符、SHA256 不符）。
- **FAIL_PLATFORM**：platform 启动/Flyway 迁移失败、health 不就绪、profile test 存在适用项未通过（对照 T 批次同配置逐项登记差异）。
- **FAIL_INGESTION_RUN**：`POST /runs` 异常或 RunResult.status ≠ SUCCESS（含 `SOURCE_NOT_BOUND`/`MAPPING_PROFILE_*`/`INGEST_BATCH_INPUT_CONFLICT`——逐字登记 error code 与日志上下文）。
- **FAIL_RECON_MISMATCH**：V-5~V-8 任一计数/集合断言不成立（1011≠1011、LOST>0、EXTRA>0、quarantineCount>0、manifest 键值不符）。
- **BLOCKED_ENV**：口令缺失（停止并把精确设置命令交总控）、3307 不可用、8091 被占、WSL 不可达、BATCH-U run root/HDFS 数据缺失或 NN/DN 无法无 reformat 恢复。
- **HARD DECISION**：执行中发现需要仓内代码/契约变更才能继续的事实（如 `LandingUri` 拒绝本批 URI 形态、F-31 同名冲突实际触发、FLUME_RAW 布局缺陷）——登记证据、停止执行、呈报总控，不得现场改码绕过。

## 7. PASS boundary（本批不证明）

Spark→publish 下游（ODS/DWS/ADS、质量门、发布）、浏览器 E2E、真实 LLM、REMOTE_CLUSTER、端到端 exactly-once、Flume→HDFS 的再验证（复用 BATCH-U 结论、不重跑）、整个 Stage 7 完成。V-9 的二次 `noNewData` 仅证明本批单文件断点语义，不证明多文件/追加/重建场景。

## 8. After PASS

结果文档 `docs/verification/results/BATCH-V-STAGE7-PLATFORM-FLUME-RAW-INGESTION-RESULT.md`；CURRENT_BATCH.md / PROJECT_STATUS.md 状态矩阵「Flume→HDFS→平台 FLUME_RAW 采集」一格置 PASS/CLOSED；本地 docs 提交（不 push，push 需总控另行授权）；向总控呈报并给出 Stage 7 剩余面清单。
