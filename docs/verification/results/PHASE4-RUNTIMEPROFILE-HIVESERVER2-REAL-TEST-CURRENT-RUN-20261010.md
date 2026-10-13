# 阶段4 RuntimeProfile→HiveServer2 当前 RunId 真实服务流程验证（2026-10-10）

## 隔离范围

- RunId：`cloud_261010_040051_b2ab54`；平台 task-owned MySQL 仅经应用 API 使用 3307 schema，未连接 3306，也未直接改业务数据库。
- Hive 3.1.3/JDK 8，Metastore Thrift 19084，HiveServer2 10015，authentication=NONE、空 `credential_ref`；独立 Derby 位于 RunId `hive-metastore/derby`。不复用主栈 Metastore 19083/主 Derby。
- 配置 HDFS `hdfs://127.0.0.1:19000`，专属 warehouse `/warehouse/cloud_261010_040051_b2ab54/hive-warehouse`。停止服务后只读确认 19084/10015 均 STOPPED，Derby 目录及 HDFS warehouse 仍存在。

## 真实 RuntimeProfile 流程

- 通过隔离管理员的正式登录/API 流程读取当前档案，确认唯一 ACTIVE 为 ID 1；经正式 `POST /api/v1/runtime-profiles` 新建候选 ID 2，`profileCode=hs2-none-cloud_261010_040051_b2ab54`、初态 DRAFT，沿用当前档案的 Landing/Spark/Metric 配置并只设置本任务 HS2 JDBC URL。
- 调用 `POST /api/v1/runtime-profiles/2/test`，HTTP 200 / `code=OK` / `allPassed=true`。Landing、Hive（Hive JDBC `SELECT 1`）、Spark、Metric 四项均 applicable 且 PASS。test endpoint 将候选状态写回为 TESTING。
- 复读 profile 列表确认 ID 2 为 TESTING，ID 1 仍为唯一 ACTIVE；没有激活、切换、修改 ID 1 或直写数据库。认证使用仓库演示账号，本次未重置 3307 管理员密码；口令/token 未写入日志、文件或报告。

## 与商城请求的区分

对 Mall API 使用此前同一 idempotency key 重试 `POST /api/v1/mall/users`，返回 HTTP 401；因此当前 RunId 没有由该请求创建商城用户，也没有商城 Outbox/平台采集事件。File Channel 的单行 HDFS 证据仍仅是 transport fixture，见阶段2报告。

## 结论与边界

本报告证明当前 RunId 的无认证、同机 loopback HiveServer2 配置下，真实 DB-backed RuntimeProfile DRAFT 经正式 test API 完成 JDBC 探测及四项运行检查；不代表共享 Hive Metastore、认证 Hive、ODS 查询链、阶段4整体或项目最终验收。原 RuntimeProfile ID 1 始终 ACTIVE，测试 profile ID 2 留在 TESTING 供复核。
