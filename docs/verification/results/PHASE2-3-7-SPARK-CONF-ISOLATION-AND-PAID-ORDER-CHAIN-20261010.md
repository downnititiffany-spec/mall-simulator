# 阶段2/3/7 Spark 配置隔离修复与 Mall 支付订单真实链（2026-10-10）

## 根因、修复与验证

- 旧 RunId `cloud_261010_040051_b2ab54` 的正式 `ODS_TO_ADS` run 1 在 `INIT_SCHEMA` 失败。Spark 进程环境实测 `HADOOP_CONF_DIR` 指向前一 RunId `cloud_261009_135713_996499/platform-spark-conf`，`SPARK_CONF_DIR` 未设置；Spark 因而从 `$SPARK_HOME/conf/hive-site.xml` 加载全局 Hive 配置，Hive 执行 2.3.9 与实际 Metastore schema 3.1.3 冲突。
- 最小修复在 `scripts/cloud-single-node.sh` 平台启动之前：在创建当前 RunId `platform-spark-conf` 并写入 `core-site.xml`、`hdfs-site.xml`、RunId 专属 embedded Derby `hive-site.xml` 后，同时导出 `HADOOP_CONF_DIR` 与 `SPARK_CONF_DIR` 指向该目录。`bash -n scripts/cloud-single-node.sh` 通过；按统一脚本停止/启动后读取新平台 `/proc` 环境，两个变量和 `PLATFORM_SPARK_METASTORE_DIR` 均指向当前 RunId。Spark `sci` 日志明确记录加载当前 RunId 下的 `platform-spark-conf/hive-site.xml`，记录 embedded metastore schema 2.3.0，无 2.3.9/3.1.3 冲突。
- 首次修复复测 RunId `cloud_261010_060630_f7d6bd` 通过 `INIT_SCHEMA`/ODS/DWD/DWS/ADS，但单条 `user_registered` 样本因没有支付订单在质量门失败；没有发布快照。该 RunId 及失败数据完整保留。

## 成功链路 RunId `cloud_261010_061315_23e7b3`

- ACTIVE profile 1/source 1。通过隔离 Mall 正式管理员 API 产生一名合成用户、一笔商品 1001（分类 11、单价 29.90）的订单并支付；幂等键分别用于注册、下单、支付。Outbox 正式发布 4 个业务事件（注册、下单/库存预留、支付）；token/密码未落盘或记入证据。
- Outbox JSONL：4 行；当前 RunId 独立 Flume File Channel → HDFS Sink 单次投递 PASS。源文件与 HDFS 输出各 4 行、SHA-256 均为 `2fd1e7eb8e91c90b782ee7508fc1d8b2f73b17b09f2ce26b140333d4b0c6cbe7`。HDFS：`hdfs://127.0.0.1:19000/landing/cloud_261010_061315_23e7b3/raw/dt=20261010/hour=14/events-pagination-synthetic.1791612913980.jsonl`。
- 平台正式 `POST /api/v1/ingestion/runs` 从当前 profile 的 HDFS FLUME_RAW 输入创建批次 1，SUCCESS，recordCount=4、errorCount=0、quarantineCount=0；READY manifest 为 `hdfs://127.0.0.1:19000/landing/cloud_261010_061315_23e7b3/manifests/1.json`。
- 平台正式 `POST /api/v1/pipeline-runs` 创建 run 1，幂等键/sourceDataVersion 唯一；8/8 阶段成功：WAIT_LANDING 4，INIT_SCHEMA 41，LOAD_ODS 4，BUILD_DWD 2，BUILD_DWS 0（阶段结果计数；作业证据含分区输出），BUILD_ADS 14，QUALITY_CHECK 12，PUBLISH_METRIC 28。无重试。
- run 1 状态 SUCCESS，目标快照 `S20261010_1`。管理员只读 API 显示该快照 ACTIVE/sourceId=1；`GET /metrics/overview` 返回 12 项且均引用该快照，包括 `gmv=29.9`、`net_sale=29.9`、`paid_order_cnt=1`、`avg_order_value=29.9`。`GET /metrics/quality?runId=1&limit=100` 返回 30 条，全部 passed=1，覆盖 21 个唯一 ruleCode。

## 证据边界

- 本结果证明当前源码/当前 RunId 内单一 `mock-mall` 来源的一条可追溯注册+支付业务链，经 Outbox → 隔离 File Channel Flume → HDFS → 平台 HDFS 采集/manifest → Spark ODS/DWD/DWS/ADS → 质量门 → MySQL 指标发布 → 真实只读指标 API 成功。
- Flume 投递使用仓库 `scripts/cloud-flume-submit-landing-file.sh` 的 task-scoped 独立恢复/投递 agent；不代表默认在线 Flume agent 的崩溃恢复或商城 Outbox 自动监听链已验收。只覆盖 1 个支付订单、1 个用户、1 个 sourceId；不覆盖异构第二来源、更多业务日/负例、历史重算、并发/恢复完整矩阵、真实员工浏览器工作流或产品最终验收。
- HiveServer2 RuntimeProfile 探测为另一 RunId 的独立证据；本次 Spark Pipeline 使用当前 RunId embedded Derby，并未连接 HiveServer2/3306。阶段2/3/7整体、其它阶段门及总控验收仍开放。
