# 阶段4 当前 ACTIVE RuntimeProfile test API 真实结果（2026-10-10）

## 操作范围

- 用户明确授权对当前 ACTIVE profile 调用 `POST /api/v1/runtime-profiles/{id}/test`。真实管理员 session 对 RunId `cloud_261010_005249_1c73ec` 的 profile ID 1 调用一次；随后重新读取 active profile 并 logout。
- 调用前：type=`SINGLE_NODE`、status=`ACTIVE`、`hiveJdbcUrl` 未配置。既有只读浏览器列表共 1 行/1 ACTIVE。
- `test()` 会调用 `profileMapper.updateById(p)`；即使探测未全通过也会走该写回。此次调用后 profile 仍为 ID 1、`ACTIVE`、type `SINGLE_NODE`，没有切换/激活/连接配置修改。

## 结果（HTTP 200）

- `allPassed=false`。
- Landing：applicable=true / passed=true。
- Hive：applicable=true / passed=false。当前 profile 缺 `hiveJdbcUrl`，因此**没有建立 Thrift HiveServer2 连接**。
- Spark：applicable=true / passed=true。
- Metric：applicable=true / passed=true。
- 因并非所有检查通过，服务未将 profile 置为 TESTING；状态仍 ACTIVE。

## 结论与限制

本结果真实覆盖当前 ACTIVE RuntimeProfile 的正式 test API、Landing/Spark/Metric 检查，以及 Hive 配置缺失时未通过的结果；它不是 Thrift 健康通过证据，也未验证 Profile 连接配置。仓库只读列表为 1 profile，当前没有第二条可直接选择的已登记 HS2 profile。新增/编辑候选 profile、登记 credential_ref 或激活均未进行；需有明确授权和有效隔离凭据后，才能对指定候选做真实 Thrift 探测。

没有切换/激活 profile，没有修改连接字段；没有连接 MySQL 3306。阶段4整体、阶段8最终复现和项目验收保持开放。
