# 阶段4 RuntimeProfile 认证只读验收器响应契约

日期：2026-10-10

## 修正

审查现有 loopback-only Playwright 验收器与 Vue 页面/API 契约后，发现原 PASS 条件检查了页面 alert、行/显式空态、登出和前端错误，但没有直接断言两个首屏只读接口的 HTTP 状态与统一响应信封。来源请求失败目前会显示页面错误，但验收器的 PASS 还应由服务端响应本身支撑。

验证器现收集 `GET /api/v1/runtime-profiles` 和 `GET /api/v1/sources` 的响应，只记录摘要布尔值；要求两个端点均被观察到、HTTP 2xx、信封 `code=OK` 且 `data` 为数组。数据数组可以合法为空，仍按显式空列表语义验证。错误正文、响应数据、URL query、token、密码、截图与 trace 均不输出或落盘。最终 PASS 同时要求认证成功、页面路由完成、两个 API 契约通过、无 pageerror、登出成功。

## 验证结果

- Python 模块编译：通过。
- 4 个本地假响应契约断言通过：成功空数组、非 2xx/业务错误、无效 JSON、忽略非目标登出端点。
- 实际运行 verifier 返回 `NOT_RUN`：当前进程未提供 `GRADUATION_TEST_ADMIN_PASSWORD`，因此未启动浏览器、未发请求。
- 未做真实管理员登录、真实 RuntimeProfile/来源 API、数据库状态或 profile 切换验收；本报告不是阶段4通过证据。3306 未访问。

## 证据边界

这次是验证器逻辑修复和本地替身检查。真实认证只读浏览器运行仍需通过 `scripts/run-runtime-profile-authenticated-readonly.ps1` 在本机安全提示中输入管理员密码；不需要在聊天中发送密码或手动把它写入文件。阶段4与总验收继续开放。

## 持久化回归补充

将响应契约检查固化到 `scripts/test_verify_runtime_profile_authenticated_readonly.py`。5 项 unittest 覆盖合法空列表、HTTP/业务失败、非法 JSON、非数组数据、忽略登出端点，以及两项只读 API 必须同时通过聚合门禁；命令 `python -B scripts/test_verify_runtime_profile_authenticated_readonly.py`，5/5 PASS。该单测不执行浏览器、登录、HTTP 或数据库。
