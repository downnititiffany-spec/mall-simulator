# 阶段4 RuntimeProfile 管理页浏览器 mock 工作流（2026-10-10）

## 执行

命令：`python scripts/verify-runtime-profile-browser.py --base-url http://127.0.0.1:5173`

脚本使用 headless Chromium 访问当前 Vite 页面，将 `/api/v1/**` 全部拦截到内存 mock API；浏览器本地角色仅用于通过客户端路由守卫。没有向平台 API 或数据库发送请求。

## 结果

- `result=PASS`；11 次 mock API 请求，无意外路径，页面错误 0。
- 新建 SINGLE_NODE 草稿并提交 `credentialRef`；请求 payload 未出现 password/token/secret 字段，页面没有 password 输入框。
- 测试通过前激活按钮禁用；mock connectivity check 全部通过后按钮启用；确认文案包含候选环境名并由浏览器确认；mock 激活后列表显示为 ACTIVE。

## 证据边界

这是 RuntimeProfile 页面交互和前端状态门禁的浏览器 mock 证据。页面角色来自合成 localStorage，API 全被 mock；不证明真实管理员登录、服务端 `runtime:manage` 权限、DB-backed profile、HiveServer2 连通性、并发激活或实际切换后的快照范围。阶段4与总体验收保持开放，未连接数据库、未访问 3306。