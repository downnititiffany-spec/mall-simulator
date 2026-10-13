# 阶段 4 决策列表只读分页验收器

日期：2026-10-09  
范围：新增真实服务决策分页验证脚本及 loopback mock 协议夹具。

## 验收器行为

- `scripts/verify-decision-pagination.ps1` 只允许 loopback HTTP(S)，从当前 PowerShell 进程读取 `GRADUATION_TEST_ADMIN_PASSWORD`。缺少凭据时在任何 HTTP 请求前拒绝。
- 登录后只读调用 `/api/v1/decisions/page`，检查非空结果、升/降序、page/size/total/totalPages/sort 回显、行数及第二页 ID 边界；存在第二页时检查重复 ID 与跨页排序。结束时注销临时会话。
- 输出只包括聚合计数和边界是否验证，不输出响应正文、标题、口令或 token。真实数据只读；脚本不创建、审批或执行决策。
- `scripts/fixtures/mock-decision-pagination-api.mjs` 为本机 loopback 夹具，不连接真实平台。

## 验证

- PowerShell AST 解析通过；Node.js `--check` 通过。
- mock 用 25 条合成 DRAFT 记录运行脚本：降序第 1/2 页、升序第 1 页、总数/页数/排序/页内行数/跨页边界均通过，输出 `PASS`，测试服务调用序列为 `login,page-desc-1,page-asc-1,page-desc-2,logout`。
- 缺少管理员变量的负向检查在 HTTP 前拒绝；没有请求外部地址。

## 证据边界

mock 结果只验证验收器与响应契约，不证明真实隔离服务的认证、数据库非空数据、排序、并发变化或浏览器表现。当前任务未提供管理员变量，因此本轮没有运行真实 API 验收；阶段 4 仍开放。未访问 3306，未改数据库或服务。
