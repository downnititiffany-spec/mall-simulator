# 阶段 4 RuntimeProfile 真实 HiveServer2 API 探测（2026-10-10）

## 范围

当前 RunId：`cloud_261010_040051_b2ab54`。在同一 RunId 内使用 Hive 3.1.3 建立专属 Metastore `19084`、HiveServer2 `10015`、embedded Derby catalog `/home/asus/graduationproject-runs/cloud-e2e/cloud_261010_040051_b2ab54/hive-metastore/derby` 与 HDFS warehouse `hdfs://127.0.0.1:19000/warehouse/cloud_261010_040051_b2ab54/hive-warehouse`。`hive.server2.authentication=NONE`，不复用 19083 Metastore。HDFS RPC 19000 与隔离栈现有服务保留运行；3306 未连接。

启动命令使用 `scripts/wsl-hive-thrift.sh <RunId> start`，显式设置该 RunId 的工具、Hadoop 配置与 19084/10015 端口。Derby schema 以 `schematool -info` 只读检查通过，schema version 3.1.0。JDK 17 首次启动未使 HS2 在 120 秒内监听，随后通过同一脚本停止该次 task-owned 服务，Derby/HDFS 数据保留；改用 Hive 工具链 JDK 8 后，19084 与 10015 均成功监听。19083 仍由原进程监听，未触碰。

## RuntimeProfile API 结果

以 loopback 管理员 API 登录后，先只读列出 profile：RunId 专属 profile `id=2` 已存在，`type=SINGLE_NODE`、`status=TESTING`、`hiveJdbcUrl=jdbc:hive2://127.0.0.1:10015/default`、`credentialRef` 为空；唯一 ACTIVE profile 仍为 id 1。由于目标 profile 已存在，本轮未重复创建。

调用 `POST /api/v1/runtime-profiles/2/test`，API 返回成功。四个适用检查均通过：Landing、Hive、Spark、Metric。Hive 检查经当前 `RuntimeProfileServiceImpl.checkHive()` 对 HiveServer2 JDBC 执行 `SELECT 1`。测试后通过 API 回读，id 2 状态仍为 `TESTING`，ACTIVE 数量仍为 1；未激活、未切换 profile。`/test` 经业务 API 更新 profile 状态行（回读仍为 TESTING）；未直连数据库或执行 SQL。

## 证据边界

这是当前隔离 RunId 上真实管理员 HTTP API、DB-backed RuntimeProfile 和真实 Hive JDBC/HS2 `SELECT 1` 的证据。HS2 使用本轮授权的 NONE 认证模式、空凭据引用；不能代表启用认证的 Hive 环境或共享 Metastore 验收。测试还覆盖并通过当前 profile 的 Landing/Spark/Metric 探测，不代表阶段4全部完成，也不证明 profile 激活/切换或最终产品验收。阶段4与项目总体验收保持开放。

证据现场：RunId runtime 与 PID 文件、`hive-logs/schematool-info.log`、`hive-logs/metastore.log`、`hive-logs/hiveserver2.log`；API 结果为本轮终端结构化摘要，不含 token/密码。