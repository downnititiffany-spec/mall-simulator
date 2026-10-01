# N31-02 腿 D 结果：共享 Hive Metastore 单节点验证

- 执行日期：2026-09-29（WSL Ubuntu）
- 结论：**PASS_WITH_CLEANUP_CORRECTION**。Hive 与 Spark 成功共用同一个非 Derby Metastore，互相读取对方创建的表，并将托管表写入既有 HDFS。验收功能判据通过；一次性启动/清理包装脚本在退出阶段返回 15，且未自动释放 9083，已核验并定点停止本次 Metastore 进程。此处保留清理修正限定，不把脚本原始退出码改写成全绿。
- 证据根：`target/v25-it/n3102hms_20260929_114700/`（本地 ignored 运行产物；后续按 N31-02 收口计划归档）。

## 1. 隔离范围与环境

- 仅使用新建的隔离 MySQL schema `n3102hms_20260929_114700_metastore`，连接 `127.0.0.1:3307`；未连接正式 `analytics_meta`，未访问 3306。
- Hive Metastore schema 初始化与版本检查均退出码 0，版本为 **3.1.0**；没有使用嵌入式 Derby。
- WSL 运行组件：JDK 8u351、Hadoop 3.3.4、Hive 3.1.3、Spark 3.5.1；Spark 使用 Hive 3.1.3 client jars（258 个）。选择 Spark 3.5.1 是为了避免误用默认 Spark 3.3.2 随附的 Hive 2.3.9 client。
- Hive Thrift Metastore 在测试期间监听 9083；仓库位于 `hdfs://127.0.0.1:19000/n3102hms_20260929_114700/warehouse`。

## 2. 互操作判据

Hive 创建 `hive_owned`，写入两行（值 11、11）；Spark 经同一个 Thrift Metastore 创建 `spark_owned`，写入两行（值 1、2）。交叉查询结果：

| 查询端 | 表 | 行数 | SUM(value) | 结论 |
| --- | --- | ---: | ---: | --- |
| Hive | `spark_owned` | 2 | 3 | Hive 可读取 Spark 创建的表及数据 |
| Spark | `hive_owned` | 2 | 22 | Spark 可读取 Hive 创建的表及数据 |

两张托管表目录均由 HDFS `ls` 确认存在，且都位于本批专属 warehouse 前缀下。运行摘要记录 `SCHEMA_INIT_EXIT=0`、`SCHEMA_INFO_EXIT=0`、`METASTORE_LISTEN=PASS`、`HIVE_DDL_EXIT=0`、`SPARK_CROSSWRITE_EXIT=0`、`HIVE_CROSSREAD_EXIT=0`、`HDFS_VERIFY_EXIT=0`、`LEG_D=PASS`。日志秘密扫描为零命中。

## 3. 清理偏差与处置

- 所有 schema / DDL / Spark 写入 / Hive 交叉读取 / HDFS 路径检查均成功，但外层 PowerShell/WSL 包装命令在收尾时报告退出码 **15**。现有日志不能将该退出码可靠归因到某一条 teardown 命令，故不推断根因，也不把它描述成测试断言失败。
- 收尾时检查发现 9083 仍有本批 Hive Metastore Java 进程。通过 PID 的 Java 命令行确认它是本批启动的 Hive Metastore（`hive-metastore-3.1.3.jar`，仅绑定该测试配置），随后只停止该 PID；复核 9083 已释放。
- 复核后 WSL 常驻 HDFS 19000 与隔离 MySQL 3307 仍正常监听。没有停止它们、没有格式化 HDFS、没有改动全局 Spark/Hive 配置。
- Hive/Hadoop CLI 输出 `log4j.properties is not found` 警告；本次 DDL、交叉查询与 HDFS 目录判据仍均成功。警告作为环境噪声记录，不解释为功能失败。

## 4. 结论边界与后续

- 本结果只证明 **WSL 单节点**上 Hive 和 Spark 可通过同一个 MySQL-backed Hive Metastore 共享表元数据，并能共同读写测试数据到 HDFS。
- 不证明 REMOTE_CLUSTER、多节点/YARN、高可用 HMS、生产元数据服务或大规模性能；也不替代 N31-02 腿 A/B 的连续业务链验收。
- 腿 C 的分类/地区表结构与业务口径仍等待 C3 总控裁决。N31-02 当前不能整体标记完成；下一步应继续推进不受该裁决阻塞的 N31-03 员工可用性验收，并待总控处理腿 C 契约裁决。
