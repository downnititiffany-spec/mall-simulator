# 阶段4认证只读验收器本地安全启动入口（2026-10-10）

## 变更

扩展 `scripts/run-runtime-profile-authenticated-readonly.ps1`，用 `-Verifier` 选择既有三种 loopback-only 只读验收器：`runtime-profile`（默认，web :5173）、`decision-pagination`（平台 :8091）、`ingestion-batch-pagination`（平台 :8091）。分页入口支持 1–50 的 `-PageSize`，默认 10。沿用已有 `GRADUATION_TEST_ADMIN_PASSWORD` 进程环境；若缺失则通过不回显的 SecureString 提示输入，仅临时供子验收器使用，并在结束时恢复/清理环境。命令行不接收密码。

示例：

```powershell
./scripts/run-runtime-profile-authenticated-readonly.ps1
./scripts/run-runtime-profile-authenticated-readonly.ps1 -Verifier decision-pagination -PageSize 10
./scripts/run-runtime-profile-authenticated-readonly.ps1 -Verifier ingestion-batch-pagination -PageSize 10
```

## 本轮验证与边界

- PowerShell AST 解析通过。
- 非 loopback 地址在凭据提示/验收器启动前拒绝，返回 `$LASTEXITCODE=2`；IPv6 loopback 被 Uri 判定为 loopback。
- 三种验收器均沿用其既有独立实现与自身响应/会话检查；本轮没有真实密码、没有调用真实认证/API/数据库，也没有执行真实分页。真实验收器分派未被本轮端到端运行，因此不能登记真实登录或业务通过。
- 未访问 3306，未启停服务。

此工具只降低本机安全执行门槛，不构成阶段4验收证据。阶段4及总验收继续开放。

## 分派替身补充（2026-10-10）

在临时目录用三个替身验收脚本验证启动器将 runtime-profile、decision-pagination、ingestion-batch-pagination 分别分派到正确脚本；验证分页参数、loopback BaseUrl、进程环境标记传入、调用后保留原环境变量。三种分派均通过；替身不发 HTTP 请求，不含真实凭据。

