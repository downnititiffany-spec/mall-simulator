# RuntimeProfile 当前 Hive 配置只读核验（2026-10-09）

## 方法与边界

通过运行中平台进程继承的 `PLATFORM_META_URL`、`PLATFORM_META_USER`、`PLATFORM_META_PASSWORD` 和 `CLOUD_MYSQL_BASE`，在同一 shell 内向明确的 `127.0.0.1:3307` 元数据 schema 发起只读 SELECT。凭据未打印、未写文件或日志；没有连接 3306，也没有执行数据库写操作。平台进程身份为 PID 9245、JDK 17，工作目录为当前 v3-dev 工作树；数据库监听进程位于本 RunId 的 mysql-data 目录。

## 结果

- MySQL 会话回报 `@@port=3307`，schema 为当前 RunId 的 `cloud_261009_053348_d6936a_meta`。
- `runtime_profile` 当前唯一 ACTIVE 为 `local-dev`，类型 `SINGLE_NODE`，版本 2。
- 此 ACTIVE 档案 `hive_jdbc_url` 为 NULL，`credential_ref` 为空；`cloud-single-node.sh` 的 profile bootstrap 明确把 hive_jdbc_url 设为 NULL。
- Metastore 19083 正在监听，但这不等于该 RuntimeProfile 配置了 HiveServer2 JDBC 目标；前次隔离探测结束后 10015 已释放。

## 结论与后续门

当前 ACTIVE profile 没有可供 `RuntimeProfile.checkHive()` 使用的 JDBC URL/凭据引用，因此不能用它产生真实 HS2 探测通过证据。此前独立配置 profile 对象的 `RuntimeProfileHiveServer2IT` `SELECT 1` 仍只证明方法级驱动/服务路径。要验 DB-backed RuntimeProfile 和管理 API，应通过受认证的 RuntimeProfile 管理流程建立候选配置、测试并明确激活；当前任务、WSL及平台进程均无 `GRADUATION_TEST_ADMIN_PASSWORD`，本轮不绕过认证或直接修改档案。V3.0 按需启动 Hive JDBC 路径，故不擅自把当前 NULL 判定为实现缺陷。阶段4保持开放；3306与数据库写入均未涉及。

本报告只描述本次现场只读快照；未来 RunId/profile 变化后需重新核验。
