# G31-05 — WSL 单节点 HDFS 整链验证结果（05.1~05.6，2026-09-25 PASS 收口）

- **Batch**：`BATCH-G31-05-WSL-SINGLE-NODE-CHAIN`（计划：`docs/verification/batches/BATCH-G31-05-WSL-SINGLE-NODE-CHAIN-PLAN.md`）
- **RunId / 证据根**：`target/v25-it/g3105_20260925_121316/`（`evidence/`、`scripts/`、`logs/`、`spark-conf/`、`landing/`、`landing-local/`、`metric-export-w/`）
- **被测 jar**：platform `f6d0c3a4…` / spark-jobs `b5554d7e93426a4d8d2658e2a07c9cde898f3f5cac0a20512155ab9e356c1ee8`（05.1 预检 sha256sum 核验，链内每作业前复核）
- **代码边界**：产品源码零改动；工作树既有未提交改动原样保留；未 commit、未 push；3306 全程零接触（F2b 断言）；V25_IT_* 口令零落盘、平台 secret env 不入日志。

## 1. 目的与依据

V3.1 指导书 §7 G31-05 原文已不可恢复（非仓内文件，穷尽检索未留存）；本批按既定裁决从冻结 V3.0 权威文档推导验收项（推导链登记于计划 §1），其中 **V3.0 指导书 L233「环境目标需单独登记」** 由本批第 2 节履行。目标：在 WSL 单节点 HDFS（真实分布式存储 + 真实 Flume 采集）环境档上跑通 摄取→Spark 计算链→发布 全链，并以平台 LOCAL 对照腿证明同 jar 发布链的指标一致性。

## 2. 环境档登记（V3.0 指导书 L233）

本项运行档案 = **WSL 单节点 HDFS 3.3.4**（1 NameNode / 1 DataNode / replication=1 / 无 YARN；fs.defaultFS `hdfs://127.0.0.1:19000`；沿用 2026-09-24 smoke 集群 clusterID `GP-SMOKE-20260924`，**零格式化**，配置取自 `target/fast-dev-20260924/hdfs-smoke/conf/`，PID 目录 `/home/asus/g3105-hdfs-run/pids`）+ **Spark 3.5.1 local[1]**（JDK 8u351；`/opt/spark-3.5.1-bin-hadoop3`）+ **嵌入式 Derby** metastore（`/home/asus/g3105-spark-run/metastore_db`，run-scoped `spark-conf/hive-site.xml`，未改 `/opt` 原配置）+ **Flume 1.11.0**（`flume-ng -Xmx20m`，run root `flume-conf/`）。**非 local[1] 替代声明**：存储与元数据层真实落在 HDFS/Derby，不是把 HDFS 语义用本地盘模拟。

## 3. 演练结果

- **05.1 环境档与预检 PASS**：HDFS NN+DN `setsid nohup` 启动（防 SIGHUP），`dfsadmin -report` 1 live DN，写探针建/删目录 OK；g3103 常驻栈受控停止留证；双 jar SHA 核验在案。
- **05.2 Flume→HDFS raw PASS**：真实 Flume agent 将 golden 夹具（50 行 / 17,100 B）写入 HDFS `/landing/g3105_20260925_121316/raw/dt=…/hour=…/`；`hdfs dfs -cat` 回读 md5 与源文件一致（17,100 B）；agent 状态 COMPLETED 后按台账停止。证据：`evidence/hdfs-landing-tree.txt`、`evidence/hdfs-manifest.json`。
- **05.3 平台 HDFS 摄取 PASS**：profile 1 切 `hdfs://` URI + `FLUME_RAW` 布局（`/test` allPassed 后激活）；平台摄取 batchId=1：**accepted 50 / quarantine 0**，manifest READY（`evidence/ingestion-hdfs.json`、`evidence/profile-test-hdfs.json`）。发现 file_identity 列宽缺陷（§4 / D-041）：批次按 at-least-once 设计照常交付，不影响本批判据。
- **05.4 WSL Spark 全链 PASS（10/10）**：`spark-chain.sh` 依次 sci→odl→dim→bdw→tdw→usw→fna→dqc→pub→mxp 全部 exit 0 且 stdout 有 `status=SUCCESS` JobResult 行（每作业 ~30–50 s，总 ~5 min；`evidence/jobresults/*.json`、`evidence/joblogs/*.log`）。dqc 质量检查 0 BLOCKING 失败。**ADS 与 G31-04 S1 oracle 内容一致性**：8 表 JSONL 中 **7 表字节级全同**，`ads_product_conversion_m` 排序后逐行全同（同行集、行序差）；`_export.json` 语义字段（rowCount/columns/各表 checksum）全等，差异仅为环境字段（generatedAt、hiveTable 前缀 `g3105_` vs `dw_`、hivePath `hdfs://…/spark-g3105/warehouse` vs 本地盘）与上表的行序性 checksum（`71abbc4c`→`d3b2a921`）。HDFS warehouse 树 `g3105_{ods,dwd,dim,dws,ads}.db` 分区目录在位（`spark-g3105/warehouse`）。证据：`evidence/ads-diff.txt`、`evidence/chain-summary.txt`、`evidence/export-filelist.txt`。
- **05.5 LOCAL 对照腿 PASS**：profile 1 切回 `file:///D:/…/g3105_20260925_121316/landing-local` + `ROLLING_LOG`（golden 夹具 50 行摆至 `landing-local/events/g3105-local-s1.jsonl`；`/test` allPassed）。attempt-1 pipeline runId=1 于 INIT_SCHEMA 失败（XBM0J，§4 归因环境/驱动类），删空目录后 attempt-2 **runId=2 SUCCESS 全 8 阶段**（WAIT_LANDING 50 → INIT_SCHEMA 37 → LOAD_ODS 50 → BUILD_DWD 28 → BUILD_DWS 3 → BUILD_ADS 22 → QUALITY_CHECK 10 → PUBLISH_METRIC 44；`evidence/pipeline-local2.json`），发布 ACTIVE 快照 **S20260901_2**；`GET /api/v1/dashboards/overview` 14 指标与 G31-04 S1 oracle **逐项相等（14/14，容差 5e-5）**：pv=7、uv=3、paid_order_cnt=5、fav_cnt=2、cart_add_cnt=3、buy_rate=1.0、cart_rate=0.6667、refund_rate=0.6、full_refund_rate=0.2、dau=3、avg_order_value=408.4、repeat_rate=0.3333、net_sale=1493.0、gmv=2042.0，无多余指标。attempt-2 摄取 `noNewData=true`（batchId=2 的 manifest READY 复用，LOCAL 档 checkpoint 正常持久化——与 §4 file_identity 缺陷仅发生于 HDFS identity 的观察互证）。证据：`evidence/fingerprint-local2.json`、`evidence/metric-compare-local2.json`、`drill-state-local.json`（outcome=PASS）。
- **05.6 收口与恢复 PASS**：HDFS 先 DN 后 NN `--daemon stop`（带本 run 实际 `HADOOP_PID_DIR=/home/asus/g3105-hdfs-run/pids`；脚本 `scripts/hdfs-stop.sh`），jps 复核 `HDFS-PROCS-GONE`；演练平台 PID 53516 身份核验（java.exe，`-jar …platform-app…jar` 且 `-Dplatform.metric.publish.export-dir=…g3105_20260925_121316\metric-staging`）后停止，8091 释放；g3103 常驻栈经 `item3-restore-g3103.ps1 -Confirm` 恢复 **RESTORE PASS**：mini-gate S20260921_20 ACTIVE 唯一、DEC=12（≥10，实际值记录）、USR=3、guard 14/14、F2b 零 :3306、stub LLM 探针 `providerUsed=stub-local`（suggestions=2），wrapper PID 41292。

## 4. 发现与归因（诚实记录）

- **attempt-1 XBM0J（harness/环境类，非平台缺陷）**：预备阶段预建了**空** `derby-metastore-w/` 目录，平台 spark 子进程以 `jdbc:derby:<runroot>\derby-metastore-w;create=true` 实例化 SessionHiveMetaStoreClient 时 Derby 报 `XJ041→XBM0J: Directory already exists` 拒绝建库（与 05.4 WSL 链 `metastore_db` 同一陷阱第二次出现——**Derby `create=true` 不得预建目录**）。删除空目录后 attempt-2 一次通过；attempt-1 证据（`pipeline-local.json`、sci 作业日志、`logs/platform-start1.log`）原样保留。
- **fingerprint 比较驱动缺陷（harness issue，非平台缺陷）**：attempt-2 驱动读 `$fp.data.metrics`，而 overview 载荷实际嵌套于 `$fp.data.data.metrics` → 14/14 NaN 误报 FAIL。pipeline 本身 SUCCESS（FAIL[6] 未触发）；以已存证的 `fingerprint-local2.json` 离线完成修正比较 = 14/14（`evidence/metric-compare-local2.json` 注明修正口径），驱动不再重跑、不重触发管线。
- **mxp exportDir 的 WSL 环境约束（非产品缺陷；D-042）**：`MetricExportJob` 以 Hadoop `new Path(exportDir)` 定位导出目录——Windows 平台管线下无 scheme 的 `D:\…` 由本地文件系统解析、行为正确；WSL Linux 下无 scheme 的 `/mnt/d/…` 会解析到 defaultFS（HDFS）而「文件消失」。05.4 首轮 mxp 即因此导出丢失（不可恢复），重跑以 `--exportDir="file:///mnt/d/…"`（`spark-mxp-rerun.sh`）成功。**WSL 档运行约束：exportDir 必须传 `file:///` URI**，登记 D-042。
- **file_identity 列宽缺陷（平台缺陷；D-041，本批不改码）**：V8 迁移将 `file_checkpoint.file_identity` 定为 `VARCHAR(64)`，而 HDFS 档 identity 实为 `hdfs:MD5-of-0MD5-of-512CRC32C:<64 hex>` ≈ 94 字符 → `MysqlDataTruncation: Data too long for column 'file_identity'`（`FileCheckpointMapper.insert`）。平台日志如实记录「manifest 已发布但文件断点提交失败……本轮数据仍可交付，后续可能重复投递」——批次照常交付（at-least-once 设计），但 HDFS 档的断点续读**失效**（同文件重扫将全量重复投递）。LOCAL 档（本地路径 identity）不受影响（05.5 attempt-2 `noNewData=true` 为证）。修复需改列宽或 identity 格式，属 schema 变更 → 变更请求交总控，登记 D-041。

## 5. 边界（不得越界表述）

本批**不证明**：YARN/多节点/多 DN 容错、真实共享 Hive Metastore 服务（用的是嵌入式 Derby）、REMOTE_CLUSTER 档（另立批次）、跨日多批次/迟到数据语义（F-G4-1 已知边界，D-040）、**指标库 ACTIVE 由 WSL 档产物切换**（05.4 的 ADS 内容一致性只证明「同夹具同 jar 同业务日 ⇒ ADS 同口径」的计算确定性；05.5 的 ACTIVE S20260901_2 由平台 LOCAL 管线发布）、浏览器/AI 腿在本环境档的复验（G31-01/02/03 已在平台档证明，环境无关）。`ads_product_conversion_m` 的行序差属 Spark 同键多行输出顺序不保证，行集合一致即判同——不影响指标语义（该表非 14 指标直传源）。
