# BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN — Verification Plan

> 状态：**READY（2026-09-19 总控批准启动 BATCH-U，本计划按治理流程编制钉死；执行待总控对计划检查后放行，未放行不得执行任何 Phase 命令）**
> Exact source/test SHA：`104db41117ea251b5b8e6c1f32c9f61ca4acd659`（= 当前 HEAD = 已验证远端 HEAD，T-R3 PASS 收口提交。本批零仓内代码/配置变更；docs-only 提交造成的 HEAD 前移不触发重钉，仍以本 SHA 代码态执行；若执行中引入仓内变更（如新增 harness 脚本），必须先提交并重钉 Exact SHA，未重钉不得执行）
> Branch：feature/v3-development
> RunId：执行时新生成唯一 RunId，格式 `stage7u_<yyyyMMdd_HHmmss>`（不复用 `stage7q1_20260918_152245`）；恢复子例隔离资源使用 `<RunId>-rec` 后缀
> Predecessor：Batch T-R3 **PASS**（被测 SHA `7850e9b`，结果登记于 `104db41`；T→T-R1→T-R2→T-R3 三提交已按总控授权 fast-forward 推送，远端 HEAD = `104db41` 已验证）
> 批准依据：总控 2026-09-19 裁决——T-R3 PASS 关闭 LocalFile 前置门，批准 BATCH-U 进入 Flume→HDFS 实链；范围窄化钉死为「producer / spool input → Flume source/channel/sink → HDFS 指定 landing 路径 → 文件真实可读、行数/事件数可对账」；明确禁止把 Spark、浏览器、真实 LLM、REMOTE_CLUSTER 塞进本批。
> Git 状态注记（D-001）：本计划与状态文档提交仅本地；push 需总控另行授权（T-R3 三提交推送授权已于 2026-09-19 执行完毕，不延伸到任何新提交）。
> 口令通道注记：本批零 MySQL 依赖（3306 冻结不碰、3307 亦不使用）、零口令需求、不启动 platform（8091 无关）。

## 1. Purpose

- 验证设计 V3.0 §8.2 L259 首版集成链 **Spooling Directory Source + File Channel + HDFS Sink** 六条规则的真实运行。当前证据只有静态门禁（`FlumeSpoolConfigTest` 9 项）与 `ingestion/flume/flume-spooldir.conf` 模板自带「状态：未实测」标注；PROJECT_STATUS L181/L496 登记「Flume 从未运行」。
- 范围窄化（总控钉死）：producer 完成文件（spool input）→ Flume source/channel/sink → HDFS 指定 landing 路径 → 文件真实可读、行数/event_id 可对账。**不含**平台 FLUME_RAW 采集与 ingestion manifest、不含 Spark/LOAD_ODS、不含 3307 meta/metric 双库与 runtime profile 登记、不含 REMOTE_CLUSTER。
- 执行结束二选一：关闭「Flume 从未运行」登记，或以 §6 分类如实登记环境/链路缺口（遵守 CURRENT_BATCH「不再猜测」纪律）。

## 2. Pinned input

- 唯一投放输入 = S-R1 pinned producer 完成文件的只读副本：
  `target/v25-it/stage7q1_20260918_152245/producer/attempt-20260918_195657_077/mall-landing/events/2026091819.jsonl`
  - 1011 行，每行一个 JSON 事件（event-contract v1）；
  - SHA256 = `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`（2026-09-19 复算一致）；
  - 对账主键字段 = `event_id`（snake_case，已核 1011 个值全部唯一 ×1；`order_id` 等业务字段可重复，不参与对账）。
- 投放前 Windows 侧复算一次 SHA256、复制进 WSL 后再算一次，三方一致才允许进入 spool（§4 Phase 3）。
- 不使用 CANONICAL_EVENT_FILE 手工夹具；不重跑 generator/mall；不新产事件。
- 恢复子例输入 = 上文件内容确定性重复 ×20（20,220 行；内容重复是有意的耐久性压载，拼接后 SHA256 执行时登记）。

## 3. Safety and exact runtime

### 3.1 环境事实（2026-09-19 实测登记）

- Windows 侧无 Flume/Hadoop 安装；WSL Ubuntu 26.04，`/opt` 已有 `hadoop-3.3.4`（**出厂空配置**：core-site.xml / hdfs-site.xml 均为空 `<configuration>`、hadoop-env.sh 未设 JAVA_HOME、无守护进程在跑、无已格式化数据）、`hive-3.1.3`、`jdk-8u351`、`jdk-17.0.12`、`spark-3.5.1-bin-hadoop3`。
- WSL 默认 JDK = `/usr/lib/jvm/java-11-openjdk-amd64`（Hadoop 3.3.4 与 Flume 1.11.0 均支持 Java 11 运行时；不使用 jdk-17）。
- WSL 可达 archive.apache.org（HTTP 200，`apache-flume-1.11.0-bin.tar.gz` 与 `.sha512` 均存在）→ 本机无 Flume，前置下载安装 1.11.0。
- 端口 9000/8020/9100/9870/9866/9864 实测空闲。

### 3.2 钉死项

- **Source 类型 = Spooling Directory Source**（设计 §8.2 L259 强制首版；taildir / `flume-taildir.conf` 明确排除，执行中不得切换——总控钉死「不允许执行时临时切」）。
- 配置基线 = 仓内 `ingestion/flume/flume-spooldir.conf`（模板文件不得修改；静态门禁 `FlumeSpoolConfigTest` 9 项不变）。执行用 run conf 生成于 `target/v25-it/<RunId>/flume/flume-spooldir-run.conf`，**仅允许替换以下 4 个路径类属性**：
  1. `ingestion.sources.spool.spoolDir` → `<run>/spool`
  2. `ingestion.channels.fileChannel.checkpointDir` → `<run>/channel/checkpoint`
  3. `ingestion.channels.fileChannel.dataDirs` → `<run>/channel/data`
  4. `ingestion.sinks.landingSink.hdfs.path` → `hdfs://127.0.0.1:<NNRPC>/stage7u_<RunId>/landing/raw/dt=%Y%m%d/hour=%H`

  其余属性（含注释行）与模板逐字一致；生成后与模板 diff 全量登记，出现第 5 处差异即 FAIL_CONF。
- HDFS：WSL 单节点 1NN/1DN、replication=1、无 YARN/SSH（设计 §5.1 L110-113）。HADOOP_CONF_DIR 用 run-scoped 副本（从 `/opt/hadoop-3.3.4/etc/hadoop` 复制到 `target/v25-it/<RunId>/hadoop-conf/`）：`fs.defaultFS=hdfs://127.0.0.1:<NNRPC>`、`dfs.replication=1`、`dfs.namenode.name.dir` / `dfs.datanode.data.dir` 指向 run 目录、`dfs.permissions.enabled=false`（单机测试态）。不触碰 `/opt/hadoop-3.3.4` 原始配置与任何既有数据；NN 格式化只落在 run-scoped name dir。
- 端口：NN RPC 钉 **9100**（有意避开默认 9000/8020）；NN HTTP 9870、DN 9866/9864/9867 取默认值，执行前 `ss` 预检全空闲并登记；真实生效值以 run conf 实读为准登记（设计 L136：不得猜测端口）。冲突时顺延并显式登记，不得静默更换。
- Flume 安装：`apache-flume-1.11.0-bin.tar.gz`（archive.apache.org）下载后 **SHA512 校验通过才解压**（校验值登记），安装目录钉 `/opt/apache-flume-1.11.0-bin`（权限不允许则 `~/flume/` 并登记实际路径）。HDFS Sink 所需 Hadoop client jars 从 `/opt/hadoop-3.3.4/share/hadoop/{common,hdfs}`（含各自 `lib/`）注入 Flume classpath（plugins.d/hdfs-client/lib 或 FLUME_CLASSPATH，执行时登记所选方式）；agent 启动日志预检无 ClassNotFoundException。
- Windows→WSL 交接（§8.2 规则 6，按模板 L97-102 原文流程）：复制成 WSL spool 目录内 `*.jsonl.tmp` → 在 spool 目录内 `mv` 成 `*.jsonl`（同一文件系统内改名 = 原子宣告「完成」）。spoolDir 不落在 `/mnt/d` 上；Windows 与 WSL 路径不互相 resolve。
- 源文件名永不复用（规则 1）：主链投放名钉 `mall-sr1-<RunId>-0001.jsonl`；恢复子例钉 `mall-sr1-<RunId>-rec-0001.jsonl`。
- `deletePolicy=never` + `fileSuffix=.COMPLETED`：Flume 不删 spool 源文件；完成语义 = `.COMPLETED` 改名；includePattern `^[^._].*\.jsonl$` 天然挡住 `.tmp` / 隐藏 / `.COMPLETED` 文件。
- File Channel checkpoint/data 分离且持久（规则 2），均落在 run 目录；`useDualCheckpoints=false` 与模板一致；trackerDir 默认 `.flumespool`（spoolDir 下，平台侧扫描规则已排除，本批无平台侧参与）。
- 本批零 MySQL：3306（冻结）不碰、3307 不使用、不启动 platform、不登记 runtime_profile（V22 `landing_layout=FLUME_RAW` 留待后续平台采集批次）。
- 不改 ingestion 契约：FLUME_RAW 清单 `files[].file` 语义开放问题只登记不擅改（PROJECT_STATUS L483）；不触碰 `flume-taildir.conf`（L484）。
- 本批零仓内代码/配置变更；若执行中必须新增 harness 脚本，先提交并重钉 Exact SHA 再继续。

## 4. Exact execution

> 每个写操作命令先以 DryRun 形态（echo / 预检输出）登记于 run 日志再真实执行（参照 Batch T PLAN §6）。全程产出 `target/v25-it/<RunId>/evidence/`（命令、exit code、SHA、日志摘录、目录树）。

- **Phase 0 预检**：`git rev-parse HEAD` 核对（允许 docs-only 偏移并登记实际值）；WSL 存活与 JDK11 路径确认；端口复检；创建 `target/v25-it/<RunId>/{flume,hadoop-conf,spool,staging,logs,hdfs-nn,hdfs-dn,evidence}`；pinned 输入 SHA256 复算；确认 spool 父目录与 HDFS 目标父路径此前不存在（隔离性前置证据）。
- **Phase 1 HDFS 起动**：run conf 副本 hadoop-env.sh 设 `JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64`；写入 run-scoped core-site/hdfs-site（§3.2）；`hdfs namenode -format`（run-scoped name dir）；启动 NN+DN；`jps` 见 NameNode + DataNode；`hdfs dfsadmin -report` 恰 1 live DN；登记 exit code 与日志路径。
- **Phase 2 Flume 起动**：下载 tar.gz → SHA512 校验（不符即 BLOCKED_ENV，绝不跳过校验）→ 解压 → 注入 Hadoop client jars → 由模板生成 run conf → diff 登记（恰 4 处路径替换）→ `flume-ng agent -n a1` 后台启动 → 日志确认 `fileChannel`、`spool` source、`landingSink` 三组件 started → 记录 PID。
- **Phase 3 主链投放与消费**：
  1. pinned 文件 `cp` 至 WSL `staging/`，WSL 侧 SHA256 与原值一致；
  2. 复制为 `<run>/spool/mall-sr1-<RunId>-0001.jsonl.tmp` → spool 内 `mv` 成 `.jsonl`（原子宣告完成，规则 1/6）；
  3. 轮询 spool 直至 `mall-sr1-<RunId>-0001.jsonl.COMPLETED` 出现；
  4. HDFS `dt=*/hour=*/` 下出现 `events-*` 数据文件；
  5. agent 继续运行 ≥60s：spool 文件集与 HDFS 文件集两次快照不变（无二次摄取、无新文件）。
- **Phase 4 恢复子例（隔离，规则 2/5 证据）**：
  1. 隔离资源：独立 spool 子目录、HDFS 目标 `/stage7u_<RunId>-rec/...`、独立 channel checkpoint/data 目录；
  2. 投入 20,220 行拼接文件 → 约 2–3 秒后 `kill -9` agent → 确认进程消失 → 以同 run conf 重启 agent；
  3. 等待 `.COMPLETED` 出现 + channel 排空 → **优雅停止** agent（使 sink 关闭全部文件，孤儿 `.tmp` 不再新增）；
  4. 对账口径：closed 文件 + 残留孤儿 `.tmp` 文件一并提取 `event_id`；判据 = 1011 个输入 `event_id` 全部出现在投递集合（**不丢**）；总行数 − distinct = 重复量，**如实登记，不判 FAIL、不宣称 exactly-once**（规则 5）；File Channel checkpoint 文件存在性与重启后复用登记（规则 2 持久性）。若重启后 File Channel 损坏不可恢复 → 按规则 2 违规登记 FAIL_CHAIN。
- **Phase 5 主链对账（PASS 判据采集）**：agent 优雅停止后——
  - `hdfs dfs -cat <目标>/dt=*/hour=*/events-*` 全量行数 = **1011**；
  - HDFS 侧与 Windows 侧各执行 `grep -o '"event_id":"[^"]*"'`，两侧集合逐项比对：**1011 unique、无缺失、无新增、无重复**；
  - `.COMPLETED` 源文件 SHA256 == pinned 输入 SHA256（源文件未被修改，`deletePolicy=never` 留档成立）；
  - 登记完整 HDFS 目录树、逐文件字节数、`hdfs dfs -checksum`、NN/DN/agent 日志无 ERROR 级异常。
- **Phase 6 收口**：HDFS NN/DN 依序关闭；全部证据归档 run 目录；产出 evidence JSON/MD。

## 5. PASS criteria（总控钉死 11 项逐条对应）

1. **Flume 进程真实启动，source/channel/sink 均就绪**：agent 日志三组件 started + PID 存活记录（Phase 2）。
2. **输入文件只消费一次，不能重复摄取**：`.COMPLETED` 改名 + Phase 3.5 的 60s 双快照不变 + HDFS 行数恰 1011（若被二次摄取将为 2022）。
3. **HDFS 目标目录由本批次唯一 RunId 隔离**：RunId 新生成；执行前目标父路径不存在；路径含 `stage7u_<RunId>` 字面量。
4. **HDFS 中真实产生 data file，而不只是目录或 `_SUCCESS`**：`events-*` 文件存在且字节数 >0（DataStream fileType 本无 `_SUCCESS` 产物，登记说明；只认数据文件）。
5. **HDFS 实际行数 = 输入行数 = 1011**（Phase 5）。
6. **event_id 不丢、不增、不重复**：两侧提取集合完全相等，1011 unique ×1（Phase 5）。
7. **spooldir/taildir 由计划钉死**：Spooling Directory Source（设计 §8.2），taildir 排除；run conf 与模板 diff 恰 4 处路径替换（§3.2）。
8. **Flume 状态/position/complete 语义与所选 source 一致**：`.COMPLETED` 完成语义 + includePattern / `decodeErrorPolicy=FAIL` / `deletePolicy=never` + File Channel checkpoint/data 分离持久 + Phase 4 恢复子例不丢（重复如实登记）。
9. **失败可按 §6 分类区分**：六类分类各有明确触发与 evidence 映射。
10. **不碰 3306**：全程零 MySQL 访问（3307 亦不使用）、零 platform/Spark 进程启动。
11. **PASS 只证明 Flume→HDFS**：§7 边界随结果文档重申，不外推为整个 Stage 7 完成。

## 6. Failure classification（不得以控制台单行日志分类）

| 分类 | 触发 | 必须登记的 evidence |
|---|---|---|
| FAIL_CONF | run conf 生成/diff 出现第 5 处差异；agent 配置解析错误；组件配置性启动失败 | diff、agent 日志启动段、exit code |
| FAIL_PORT_NET | 端口占用 / bind 失败；NN RPC 连接拒绝等网络性错误 | ss 预检输出、异常栈、端口清单 |
| FAIL_HDFS_UNREACHABLE | NN/DN 未起、dfsadmin 报告无 live DN、HDFS 写失败 | dfsadmin -report、NN/DN 日志、hdfs 命令 exit code |
| FAIL_PERM | WSL 文件系统或 HDFS 权限拒绝 | permission denied 原文与路径 |
| FAIL_RECON | 行数/event_id 对账不符（丢行、多行、主链重复、二次摄取） | 两侧提取集合、计数、SHA |
| FAIL_CHAIN | 链路语义违规：File Channel kill -9 后损坏不可恢复；`.COMPLETED`/includePattern 语义被违反 | agent 日志、spool 文件清单、channel 目录状态 |
| BLOCKED_ENV | Flume 下载/SHA512 校验失败；Hadoop/JDK/WSL 环境缺口 | 下载日志、校验输出、版本清单 |

## 7. PASS boundary（本批不证明）

- 平台 FLUME_RAW 采集、ingestion manifest 与平台侧对账（后续批次）；
- Spark/LOAD_ODS 及下游；REMOTE_CLUSTER 正式集群验收（指导书 L183/L233）；
- 浏览器 E2E；真实 LLM provider；第二来源异构夹具；
- 端到端 exactly-once（§8.2 规则 5 明确不承诺）；正式集群吞吐/物理高可用（§5.1 L112「不能宣称」列）；
- **不外推为整个 Stage 7 完成**（总控原话钉死）。

## 8. After PASS

- 写 `docs/verification/results/BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN-RESULT.md`（证据工件索引 + §5 十一项逐条核对）；
- CURRENT_BATCH.md / PROJECT_STATUS.md 收口；状态矩阵仅 Flume→HDFS 一格置 PASS/CLOSED；
- 结果提交仅本地，push 需总控授权（D-001）。
