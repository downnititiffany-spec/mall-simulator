# 阶段 4 采集分页实服验收脚本本地协议验证（2026-10-09）

## 目的

为采集批次服务端分页的真实认证验收提供一个不回显凭据、只读检查分页的 PowerShell 入口，并在本机 loopback mock API 上验证登录、查询和注销协议。

## 验证对象

- 运行脚本：`scripts/verify-ingestion-batch-pagination.ps1`
- 本地 mock：`scripts/fixtures/mock-ingestion-pagination-api.mjs`
- mock 仅监听 `127.0.0.1:18091`，合成 25 条分页记录，不连接项目 API、数据库或其它服务。

## 验证结果

- PowerShell AST 解析：PASS；无管理员变量时，脚本在任何 HTTP 请求前 fail-closed，守卫 PASS。
- Node.js mock HTTP 验证：PASS。脚本完成管理员登录、ID 降序第 1 页、ID 升序第 1 页、ID 降序第 2 页、页间无重复/边界递减检查，并注销临时会话。
- 响应汇总：`result=PASS`、`total=25`、`pageBoundaryVerified=true`；mock 确认请求序列 `login,page,page,page,logout`。
- `node --check` 与 `git diff --check` 作为交付前复核执行。

## 可复现方式

在终端 A 启动 mock：

```powershell
node scripts/fixtures/mock-ingestion-pagination-api.mjs
```

在终端 B 使用仅供 mock 的临时值调用验证器：

```powershell
$env:GRADUATION_TEST_ADMIN_PASSWORD = 'mock-only-secret'
try {
  .\scripts\verify-ingestion-batch-pagination.ps1 -BaseUrl 'http://127.0.0.1:18091'
} finally {
  Remove-Item Env:GRADUATION_TEST_ADMIN_PASSWORD -ErrorAction SilentlyContinue
}
```

mock 在收到注销请求后自动退出。真实验证时应让真实受控口令由本机安全环境注入，并使用默认 loopback API 地址；脚本不会将口令或 token 输出到汇总中。

## 边界

这是本机 mock 协议和验证器行为证据，不证明真实管理员认证、MySQL 非空数据、服务端排序/翻页或浏览器行为。当前任务未能读取到 `GRADUATION_TEST_ADMIN_PASSWORD`，本轮没有对真实平台发送登录或分页请求，没有访问数据库，也未访问 3306；阶段 4 与总体验收保持开放。
