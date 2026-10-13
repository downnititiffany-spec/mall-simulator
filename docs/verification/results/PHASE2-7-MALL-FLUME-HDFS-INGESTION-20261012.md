# 阶段 2/7：真实 Mall Flume HDFS 文件经平台采集器读取

日期：2026-10-12  
RunId：`cloud_261012_012653_d3b0fc`  
范围：真实 HDFS 输入文件 + 当前工作树 `IngestionService` / `HdfsLandingStorage`；非认证平台 API 或真实元数据库

## 结果

- 输入是本 RunId 前序 Mall API 生成并由 Flume File Channel 落地的文件：`hdfs://127.0.0.1:19000/landing/cloud_261012_012653_d3b0fc/raw/dt=20261012/hour=10/events-.1791772101760.jsonl`。现场核读为 89 行、41,103 字节；文件 SHA-256 与 Mall 滚动 JSONL 源文件一致（`5946695fdf129017ce2e9721d639e52492b4cbccf62f00793f5e6c2e5a732c43`）。
- 将现有 `HdfsFlumeRawIngestionIT` 的固定期望数/事件 ID 改为可选进程环境参数，默认仍断言原两条夹具事件。测试从真实 HDFS 读取本次 89 行，在测试创建的唯一 HDFS 子目录运行 `IngestionService`，通过实际 `FLUME_RAW` 递归扫描和 `EventContractValidator`。
- 当前 Mall 输入结果：`SUCCESS`，recordCount=89、quarantineCount=0、errorCount=0、fileCount=1、acceptedBytes=41,103；测试核对 READY manifest 存在后才提交 checkpoint。随后同一测试实例重跑，结果 `SUCCESS`、recordCount=0、`noNewData=true`。
- 定向 Maven：`mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am -Dtest=HdfsFlumeRawIngestionIT -Dsurefire.failIfNoSpecifiedTests=false test`，1/1 PASS、0 failure/error/skipped，Reactor 的 `platform-common` 与 `connection-ingestion` 均 BUILD SUCCESS。
- Windows JVM 默认身份 `ASUS` 对 owner 为 `asus` 的 HDFS Landing 无写权限，第一次测试在创建测试子目录前被拒绝；未调整权限、未写入。重设本次测试进程 `HADOOP_USER_NAME=asus` 后重跑通过。测试 `finally` 删除自己创建的临时 HDFS 子目录；复查只剩原始 `raw/dt=20261012/hour=10` 文件，源文件未改。

## 证据边界

- 该运行真实读取 HDFS 输入并执行平台当前工作树的采集编排/校验代码；批次、文件账本和 checkpoint mapper 为测试替身，输出的接受区与 READY manifest 写入真实 HDFS 后随测试清理。它不证明 MySQL 批次/checkpoint 持久化、认证控制器、当前平台服务读取、真实 ACTIVE RuntimeProfile 绑定或 API 分页。
- 未执行 Hive ODS、Spark、DWD/DWS/ADS、指标发布/查询、浏览器、AI/决策。HDFS NameNode 仍是复用的旧 RunId 进程，因此不是独立 NameNode 部署验收。3306 未连接。
- 本结果仅闭合“本批 Mall→Flume HDFS 文件可被当前工作树 HDFS `FLUME_RAW` 采集实现读取并校验”这一组件切片；阶段 2/7 其余门槛、阶段 4/8 及项目总验收仍开放。
