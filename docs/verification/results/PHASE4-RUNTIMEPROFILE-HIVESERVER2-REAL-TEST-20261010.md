# 阶段4 RuntimeProfile→HiveServer2 真实服务流程验证（2026-10-10）

## 授权与隔离

- 用户授权仅在 task-owned RunId `cloud_261010_005249_1c73ec` 创建 DRAFT profile，使用 HiveServer2 `authentication=NONE`（空 `credential_ref`）并调用 test；不激活/切换。
- 使用真实管理员认证 API 创建 profile ID 2：type `SINGLE_NODE`、初始状态 `DRAFT`、`hiveJdbcUrl=jdbc:hive2://127.0.0.1:10015/default`；复用当前任务 HDFS Landing root、Spark submit 路径及 source/Metric 配置引用。正式 API 写入 profile，未直接写数据库。
- 未配置 `CRED_*` 安全环境变量；所以没有借用其他用途的密钥。空 credential reference 与 HiveServer2 `authentication=NONE` 匹配，选择依据来自现有代码受支持配置和用户授权。

## Hive 服务与真实检查

- 新启动的服务专属当前 RunId：Hive 3.1.3，JDK 8，Metastore 19084，HiveServer2 10015；Derby catalog 位于该 RunId `hive-metastore/derby`，HDFS warehouse 为 `/warehouse/cloud_261010_005249_1c73ec/hive-warehouse`。不复用原主栈 Metastore 19083/其 Derby。
- `POST /api/v1/runtime-profiles/2/test` HTTP 200，`allPassed=true`。Landing、Hive、Spark、Metric 四项均 applicable 且 PASS；Hive 项由生产 `RuntimeProfileServiceImpl.test()` 调用真实 Hive JDBC 执行 `SELECT 1`。
- test endpoint 按服务实现写回 profile；ID 2 从 DRAFT 变成 TESTING。原 ID 1 仍为 ACTIVE，活动 profile ID 保持 1；候选未激活、未切换。ID 2 留存供审核，未删除。
- 验证后通过 `scripts/wsl-hive-thrift.sh <RunId> stop` 的 RunId/PID/命令路径身份检查停止本次两个 Hive 服务；19084/10015 均 STOPPED，Derby catalog 和 HDFS warehouse 保留。主单节点栈未停。

## 结论与边界

本证据闭合“RuntimeProfile 管理流程对 Thrift HiveServer2 的真实 JDBC 健康探测”这一具体项。只证明单节点、无认证、同机 loopback、当前隔离 RunId 的 `SELECT 1` 与四项 test 流程；不代表共享 Hive Metastore、多节点/生产安全、Hive 查询业务表、ODS 链路、Spark 全作业或阶段4整体/阶段8最终验收通过。

没有连接 MySQL 3306；没有修改 ID 1 活动档案、没有 profile 激活/切换。结果文件不含 password/token/密钥。
