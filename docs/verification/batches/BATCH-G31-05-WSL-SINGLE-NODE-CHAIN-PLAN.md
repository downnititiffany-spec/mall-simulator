# BATCH-G31-05 — WSL 单节点 HDFS 整链验证计划（05.1~05.6）

- **批次**：G31-05（V3.1 指导书 §7 批次序列第 6 批）
- **日期**：2026-09-25（机器证据时钟）
- **状态**：已批准执行（按 2026-09-19 用户指令「连续执行 G31-00~07 不停止，自主决策记录」推进）
- **被测对象**：当前工作树（未提交改动保留，不 commit 不 push）；jar 钉死 G31-04 同版：platform `f6d0c3a4…` / spark-jobs `b5554d7e…`（执行前 SHA256 复核）

## 0. 计划依据与诚实登记（必须先读）

**V3.1 §7 G31-05 的验收原文（05.1~05.6 阈值表述）已不可恢复**：V3.1 指导书是用户 2026-09-19 会话消息、非仓内文件；已穷尽仓内 plans/`.zcode/plans`/会话上下文/PROJECT_STATUS 历史段落，均未留存 G31-05 原文。本计划按既定裁决（与 G31-04 的 04.1~04.4 同模式）**从冻结的 V3.0 权威文档推导**验收项，并在此登记推导链：

1. **V3.0 指导书 Stage 7 任务清单 L177–L183** 第 ② 项「小规模串通采集→数仓→Spark→发布→页面→AI/决策」与第 ⑤ 项「HDFS/Hive/集群验证按环境目标单独登记，**不用 local[1] 测试替代**」。
2. **V3.0 指导书 L233**：环境目标需单独登记（本批登记 WSL 单节点档）。
3. **V3.0 设计 §5.1 L110–L115**：WSL2 单节点 Hadoop（1 NN / 1 DN，replication=1，不要求 YARN/SSH）。
4. **`docs/verification/STAGE7-REMAINING-SCOPE-20260919.md`** 条目 #2/#5：HDFS/Hive 档验证映射到本批；REMOTE_CLUSTER 另立批次。
5. **2026-09-23 预检约束**：Spark/Hive 共享配置带明文 root 凭据不得直接启动复用 → 本批全部使用 run-scoped 配置副本；不改 `/opt`；不格式化既有 NameNode 数据。
6. **2026-09-24 冒烟先例**（`target/fast-dev-20260924/` + PROJECT_STATUS 0924 段落）：WSL Spark 3.5.1 over HDFS 全链 + mxp 导出（`fods0924_2228h`）、Flume→HDFS raw、平台 HDFS 摄取 IT（item5 / HdfsFlumeRawIngestionIT）均已单腿验证过；本批把它们串成**一条整链**并登记。

## 1. 链形（冻结代码约束下的诚实形状）

平台与 Spark 的职责边界由冻结代码决定，本批**不改任何生产代码**：

- **平台摄取侧原生支持 HDFS**：`LandingStorageResolver.forProfile` 对 `hdfs://` 前缀选 `HdfsLandingStorage`；摄取走 ACTIVE profile；`FLUME_RAW` 布局递归枚举 `raw/**/events-*`（`LandingLayout.FLUME_RAW("raw", true)`）；accepted/quarantine/manifests 全部落 HDFS。profile `/test` 对 hdfs 档走 `HdfsLandingStorage.healthCheck()`。
- **平台流水线侧不支持 hdfs 档**：`PipelineService` 每次 run 调 `LandingUri.resolve(profile.getLandingUri())`，该实现**显式拒绝 `hdfs://`**（「暂不支持的 landingUri 协议」）；且 `SparkStageExecutorFactory.confsFor` 对 LOCAL/SINGLE_NODE 档强制 `file:///` warehouse。⇒ 指标库 ACTIVE 切换（`MetricPublisher.publish` 只在 `PUBLISH_METRIC` 阶段内被调用，无独立 HTTP 入口）本批由 **LOCAL 档对照腿**完成。
- **WSL Spark 数仓链**（与平台同 jar、同 JobRunner CLI 契约、不同环境档）：Spark 3.5.1 + JDK8u351（WSL /opt），warehouse 在 HDFS，嵌入式 Derby 在 WSL run 目录，`--hiveDatabasePrefix=g3105_<ts>`，`--sourceSystem=mock-mall`。

```
[WSL] golden-20260901-positive.jsonl(50 行)
  → Flume 1.11.0 spool→HDFS sink
  → hdfs://127.0.0.1:19000/landing/g3105_<ts>/raw/dt=*/hour=*/events-*   （FLUME_RAW）
  → [Win 8091] 平台 HDFS 摄取（hdfs profile）：accepted/<batchId> + manifests/<batchId>.json 落 HDFS
  → [WSL] spark-submit 全链 sci→odl(--landingDir=hdfs accepted)→dim→bdw→tdw→usw→fna→dqc→pub→mxp
       warehouse=hdfs://…/warehouse/g3105_<ts>，Derby=WSL run 目录
  → mxp 导出 8 表 JSONL + _export.json → /mnt/d/…/target/v25-it/g3105_<ts>/metric-export/（Windows 可见）
  → [Win 8091] LOCAL 档对照腿：golden 夹具本地摄取 → pipeline ODS_TO_ADS → ACTIVE S → overview API 14 指标 == G31-04 S1 oracle
```

## 2. 执行项与 PASS 判据

### 05.1 环境档准备与预检
- 起保留冒烟 HDFS 集群（conf `target/fast-dev-20260924/hdfs-smoke/conf/`，name/data `/home/asus/.cache/graduation-hdfs-smoke-20260924/`，fs.defaultFS=`hdfs://127.0.0.1:19000`）：`setsid nohup hdfs --daemon start namenode|datanode`（防 SIGHUP）；**不格式化**；JDK8u351；replication=1；无 YARN（设计 §5.1）。
- 预检：8091 上 g3103 常驻栈按身份核验后停止（g3104 先例）；无残留平台 JVM / SparkSubmit 子 JVM；双 jar SHA256 == 钉死值；3307 存活；golden 50 行校验；`HADOOP_USER_NAME=asus` 供给平台 JVM（Windows 客户端 HDFS 属主匹配，item5 先例）。
- **PASS**：NN/DN 存活（jps）、`hdfs dfsadmin -report` 1 live datanode、读写探针过、SHA 台账落盘、g3103 停止前身份一致。

### 05.2 真实 Flume → HDFS raw
- Flume 1.11.0（`/home/asus/stage7u_20260919_170729/tools/apache-flume-1.11.0-bin`）run-scoped conf 副本：spooldir source（golden 夹具 staging 目录）→ HDFS sink `hdfs.path=hdfs://127.0.0.1:19000/landing/g3105_<ts>/raw/dt=%Y%m%d/hour=%H`，filePrefix=events；`flume-env.sh` 内覆写内存（官方 `-c` conf 目录方式，绕 L229 -Xmx20m 硬编码）；JAVA_HOME=jdk8u351；agent 跑完后**主动停止**保证所有 events-* 文件关闭（.tmp 不参与摄取）。
- **PASS**：HDFS 上出现 closed `events-*` ≥1 个且字节量==夹具字节数（`wc -c` 前后对比，WSL 会话间变量不存活）；无残留 `.tmp`；spool 目录 COMPLETED 标记齐全。

### 05.3 平台 HDFS 摄取腿（hdfs profile）
- 新 profile（run-scoped meta DB `g3105_<ts>` 域内）：`landingUri=hdfs://127.0.0.1:19000/landing/g3105_<ts>`，`landingLayout=FLUME_RAW`；POST `/test` 全过（含 HDFS healthCheck）→ activate → POST `/api/v1/ingestion/runs`。
- **PASS**：摄取响应 accepted==50、quarantine==0；HDFS 读回 `manifests/<batchId>.json`（`hdfs dfs -cat`）：状态 READY、checksum 与响应一致、`acceptedUri` 指向本批 batchId；HDFS `accepted/<batchId>/` 下文件字节量与 raw 一致；Windows 平台 JVM 全程零 `:3306` 连接（F2b 同口径日志断言）。

### 05.4 WSL Spark 数仓全链（HDFS warehouse 档）
- 10 作业顺序 `sci→odl→dim→bdw→tdw→usw→fna→dqc→pub→mxp`（JobRegistry 拓扑序 odl/dim 先行、bdw 前置含 dim）；每作业独立 spark-submit local[1]，conf 镜像 `confsFor` LOCAL 语义但 warehouse/defaultFS 指向 HDFS；Derby 固定 WSL run 目录；`--businessDate=20260901`；`--outputSnapshotId=S20260901_G3105`（pub/mxp）；mxp `--exportDir=/mnt/d/Develop_code/GraduationProject-wt/v3-dev/target/v25-it/g3105_<ts>/metric-export`。
- **PASS**：10/10 作业 exit 0 且 stdout 有 JSON JobResult 行（成功态）；dqc JobResult 质量检查全过（0 BLOCKING 失败）；mxp `_export.json` 8 表 `rowCount` 与 hive 侧一致（mxp 自带 BLOCKING 行数一致性检查）；**8 表 JSONL 行内容与 G31-04 S1 发布导出（`target/v25-it/g3104b_20260925_104346/metric-staging/S20260901_1/`）排序后逐行一致**（同夹具同 jar 同业务日 ⇒ ADS 同口径）；HDFS warehouse 树 `g3105_<ts>_*.db` 分区目录在位。
- 环境登记（L233）：本项运行档案 = WSL 单节点 HDFS 3.3.4（1 NN/1 DN/replication=1/无 YARN）+ Spark 3.5.1 local[1] + JDK8u351 + 嵌入式 Derby；**非 local[1] 替代**——存储与元数据层真实落在 HDFS/Derby。

### 05.5 平台对照腿（LOCAL 档：发布→指标库→API）
- profile 切回 LOCAL（`file://<landingRoot>` + Windows spark-submit.cmd + `hiveDatabasePrefix` 空）：golden 夹具入本地 landing → 摄取 → POST `/api/v1/pipeline-runs`（ODS_TO_ADS，businessTime 2026-09-01）→ 终态 SUCCESS。
- **PASS**：8 阶段全 SUCCESS；overview API（`GET /api/v1/dashboards/overview`）ACTIVE 快照 14 指标 == G31-04 S1 oracle 逐项相等（pv=7, uv=3, paid_order_cnt=5, fav_cnt=2, cart_add_cnt=3, buy_rate=1.0, cart_rate=0.6667, refund_rate=0.6, full_refund_rate=0.2, dau=3, avg_order_value=408.4, repeat_rate=0.3333, net_sale=1493.0, gmv=2042.0）。
- 边界登记：本腿证明同 jar 发布链在该批次环境内仍然成立；指标库 ACTIVE 切换属平台流水线职责（§1 冻结约束），**不声称** WSL 档 mxp 导出被发布进指标库。

### 05.6 收口与恢复
- 停 Flume/HDFS（`hdfs --daemon stop` 带 `HADOOP_PID_DIR=/home/asus/.cache/graduation-hdfs-smoke-20260924/logs-item5`，NN/DN 顺序）；平台按身份核验停止；g3103 常驻栈恢复（`item3-restore-g3103.ps1 -Confirm`）+ RESTORE PASS mini-gate（S20260921_20 ACTIVE 唯一、stub 探针 OK）。
- 登记结果 `docs/verification/results/G31-05-WSL-SINGLE-NODE-CHAIN-RESULT.md`；PROJECT_STATUS.md 新段（备份先行）；CURRENT_BATCH.md 刷新。
- **PASS**：恢复 mini-gate 全过；文档三件套落盘；证据边界如实。

## 3. 证据边界（不得越界表述）

本批**不证明**：YARN/多节点/多 DN 容错、真实共享 Hive Metastore 服务（用的是嵌入式 Derby）、REMOTE_CLUSTER 档（另立批次）、跨日多批次/迟到数据语义（F-G4-1 已知边界）、指标库 ACTIVE 由 WSL 档产物切换、浏览器/AI 腿在本环境档的复验（G31-01/02/03 已在平台档证明，环境无关）。

## 4. 失败分类（沿用 G31-04 §6 口径）

production FAIL（代码/被测 jar 缺陷）／BLOCKED_ENV（WSL/HDFS/Flume 环境故障，附诊断）／harness issue（驱动脚本缺陷，attempt 档案保留后修正重跑）。每次失败落证据后分类，不猜。

## 5. 安全边界（全程有效）

3306 永久冻结零接触（含只读、不探测）；V25_IT_* 口令仅进程内；`V25IT_ADMIN_PWD` 仅 WSL mysql 进程 env；平台 secret env 不入日志；不格式化既有 NN 数据；不改 `/opt`；不 commit/push；`.zcode/` 不提交；不误写 `D:\Develop_code\GraduationProject`；冻结 V3.0 文档不可原地修改。
