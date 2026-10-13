# RuntimeProfile 认证只读验收器的安全本地启动器（2026-10-10）

## 目标与实现

新增 `scripts/run-runtime-profile-authenticated-readonly.ps1`，可从用户自己的 PowerShell 启动既有 loopback-only Python/Playwright 只读验收器。它先拒绝非 loopback/带凭据/查询串/fragment 的 URL；进程环境已有 `GRADUATION_TEST_ADMIN_PASSWORD` 时沿用原值，否则通过 `Read-Host -AsSecureString` 不回显地提示输入。密码只临时设置在当前 PowerShell 进程环境供 Python 子进程继承，结束后恢复旧值或清除，并释放 SecureString/BSTR；不放进参数、命令行、日志或仓库。启动器在调用结束后留在交互 shell，并将验收器退出码保存到 `$LASTEXITCODE`。

## 验证

- PowerShell AST 解析通过。
- loopback mock 子进程确认：已有变量被继承、非零退出码 7 原样保留、调用后原变量恢复。
- SecureString 输入路径用无敏感内容的虚构标记替身验证：环境只在子进程执行期间存在，之后清除；不触发真实登录或启动 Playwright。
- 非 loopback URL 被拒绝，exit 2，未提示密码或启动验收器。

本轮没有输入真实凭据，没有访问真实认证 API/数据库/HS2，没有修改 RuntimeProfile；3306 未访问。脚本只提供本机安全执行入口，不构成验收证据。阶段4/总验收继续开放。

本地执行：`./scripts/run-runtime-profile-authenticated-readonly.ps1`。需要检查传入退出码时，在同一 PowerShell 会话查看 `$LASTEXITCODE`。

## 补充负例（2026-10-10）

空 SecureString 输入返回 exit 3，不启动 Python 子进程，且进程环境中没有残留密码。该用例由 PowerShell 替身提供空 SecureString，不涉及真实凭据。

