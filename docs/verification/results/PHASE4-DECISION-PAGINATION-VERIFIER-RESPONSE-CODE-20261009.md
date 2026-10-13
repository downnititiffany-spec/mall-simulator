# 阶段 4 决策分页验证器业务响应码校验

日期：2026-10-09  
范围：修正 loopback 实服分页验收器，使登录与分页 HTTP 200 响应还必须满足统一信封 `code=OK`。

## 改动与验证

- `scripts/verify-decision-pagination.ps1` 在读取 token 和分页内容前检查登录、分页响应的 `code`；业务错误即失败，错误信息不输出响应正文或凭据。
- `scripts/fixtures/mock-decision-pagination-api.mjs` 成功登录、分页、退出响应补齐 `code=OK`。
- PowerShell AST 解析通过；Node `--check` 通过。
- loopback mock 使用 25 条合成记录运行验证器：`PASS`，total=25、totalPages=3、pageSize=10；升序、降序、第二页无重复及严格跨页边界均通过。mock 确认请求序列为 `login,page-desc-1,page-asc-1,page-desc-2,logout`。

## 证据边界

这次只修复并验证验收器的响应信封检查，不是实服认证分页证据。未在本任务向平台发认证请求；管理员变量仍不可用。阶段 4 仍开放，3306 未触碰。
