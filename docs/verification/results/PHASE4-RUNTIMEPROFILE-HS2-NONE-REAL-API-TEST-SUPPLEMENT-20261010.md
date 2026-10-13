# 阶段 4 RuntimeProfile NONE HiveServer2 API 补充复测（2026-10-10）

## 现场与结果

- 当前 task-owned RunId：`cloud_261010_040051_b2ab54`；分支 `codex/cloud-v3-development`，HEAD `64c93b9d9c19dd9b745422207c4ac7fd739f8d4f`。`origin/codex/cloud-v3-development` 当前仍指向同一 HEAD。
- 10015 HiveServer2 的配置为认证 `NONE`，Thrift 端口 10015，Derby schema 和 HDFS warehouse 均配置在当前 RunId 下；共享 Metastore 19083 未被 RuntimeProfile JDBC URL 复用。Beeline 对 loopback HiveServer2 执行 `SELECT 1` 返回 1 行。
- 使用真实管理员 loopback API，基于 ACTIVE ID 1 的非凭据运行配置创建 DRAFT ID 3，Hive JDBC URL 指向 `jdbc:hive2://127.0.0.1:10015/default`，`credentialRef` 为空。真实 `POST /api/v1/runtime-profiles/3/test` 返回 HTTP 200；Landing、Hive `SELECT 1`、Spark、Metric 四项均适用且通过；回读状态为 `TESTING`。ID 1 仍为唯一 ACTIVE，未激活或切换。
- 事后回读发现 ID 2 已是同一 RunId、指向相同 HiveServer2 的 `TESTING` 候选。本次操作开始前只读取 ACTIVE ID 1，未先读取完整列表，因而额外创建了重复候选 ID 3。ID 2/3 均保留为 TESTING；本次未调用删除/禁用接口。此项为额外复测记录，不替代既有 ID 2 报告。

## 边界

- 管理员口令只在当前 PowerShell 进程环境短暂使用并在调用结束后恢复；未输出或持久化口令、token。通过业务 API 操作，没有直接写数据库。
- 只对 `127.0.0.1` 当前隔离服务发起请求；未连接 MySQL 3306。未激活、切换或修改 ACTIVE profile。
- 此结果验证当前隔离栈的实际 HiveServer2 JDBC 查询和 RuntimeProfile `/test` 业务 API；不证明共享 Metastore 模式、profile 激活/切换、阶段 4 整体或项目最终验收。