# 阶段 4 采集分页验收器响应契约补强（2026-10-09）

## 变更

`scripts/verify-ingestion-batch-pagination.ps1` 除检查非空结果、ID 升降序和跨页边界外，现在还断言：

- `totalPages` 与 `total/size` 一致；
- 服务端回显的排序与请求相同；
- 每页返回条数与 `total`、`size`、当前页一致。

这样真实验收在出现分页元数据或截断行数错误时会明确失败，不会只依据排序后的少量样本给出通过结果。

## 验证

- PowerShell AST 解析：PASS。
- 使用 `scripts/fixtures/mock-ingestion-pagination-api.mjs` 对 25 条 loopback 合成记录执行管理员登录、ID 降序第 1 页、ID 升序第 1 页、ID 降序第 2 页及注销：PASS。
- 脚本结果：`result=PASS`、`total=25`、`pageBoundaryVerified=true`；mock 确认请求序列 `login,page,page,page,logout`。
- 测试口令仅为本机 mock 临时值；当前隔离服务未接收该请求。

## 限制

本报告只证明 mock 契约与验收器逻辑，不证明当前 API、数据库、真实认证或浏览器分页。当前任务的管理员凭据变量不可见；阶段 4 与总体验收仍未关闭。
