# 阶段4 RuntimeProfile 真实认证只读浏览器验收器（2026-10-10）

## 工具

新增 `scripts/verify-runtime-profile-authenticated-readonly.py`，供隔离平台管理员凭据可用时使用。脚本仅允许 loopback HTTP(S)，从当前进程环境读取 `GRADUATION_TEST_ADMIN_PASSWORD`；缺失凭据时在启动浏览器或发起请求前返回 `NOT_RUN`。浏览器内通过真实登录页登录、打开 `/runtime-profiles`，核对页面成功/显式空态、列表行数及 ACTIVE 行数，然后退出并核实平台 token 已从 localStorage 清除。

脚本不创建/编辑/测试/激活/停用 RuntimeProfile，不直接访问数据库，不连接 3306；不打印或持久化密码、token、响应体、截图或 trace。

## 当前验证

- Python AST 解析通过。
- 在当前任务没有 `GRADUATION_TEST_ADMIN_PASSWORD` 时执行，返回 `NOT_RUN` / exit 3；代码路径在创建 Playwright 浏览器前结束，未发请求。
- 以 `https://example.com` 作为 base URL 的负例返回 FAIL / exit 2，拒绝非 loopback 目标。
- `git diff --check` 未发现空白错误；仅报告既有多文件 LF→CRLF 工作树提示。

## 未覆盖与边界

本轮没有真实管理员凭据，因此未执行登录、真实 RuntimeProfile/source API、浏览器列表/空态、退出或数据库读取；也没有健康测试、profile 激活/切换或 Thrift HiveServer2 探测证据。该脚本目前只证明安全前置守卫可用，不代表阶段4验收通过。阶段4与总体验收保持开放。

运行命令：

```powershell
python scripts/verify-runtime-profile-authenticated-readonly.py --base-url http://127.0.0.1:5173
```
