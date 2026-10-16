# 阶段 4 当前 RunId 采集批次真实认证分页（2026-10-10）

## 验证

- RunId：`cloud_261010_040051_b2ab54`；平台服务：loopback `http://127.0.0.1:8091`。
- 使用仓库 `scripts/verify-ingestion-batch-pagination.ps1`，通过当前进程环境安全提供隔离环境管理员凭据；脚本执行管理员登录、分页读取及注销，摘要不输出凭据、token 或响应正文。
- 命令：`./scripts/verify-ingestion-batch-pagination.ps1 -BaseUrl http://127.0.0.1:8091 -PageSize 1`。
- 结果 `PASS`：升序/降序两次请求都返回合法分页信封；`total=3`、`totalPages` 与 size 对应；每页 1 条；降序第 1/2 页边界严格递减且无重复 ID。脚本完成注销。

本轮未调用 POST ingestion/run、未新增批次/业务数据、未直接访问数据库；账号认证与 API 查询属于隔离 RunId 真实平台服务。未连接 MySQL 3306。此项只证明当前 RunId 的真实管理员分页 API 与降序跨页边界，不证明浏览器 Ops 页面、独立数据库并发一致性或阶段4/整体验收完成。

验收器：`scripts/verify-ingestion-batch-pagination.ps1`；动态文档编辑前备份后缀 `bak-20261010-ingestion-batch-pagination-cloudrun`。