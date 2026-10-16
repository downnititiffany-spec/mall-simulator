# 阶段 4 当前 RunId RuntimeProfile 真实管理员只读浏览器（2026-10-10）

- RunId：`cloud_261010_040051_b2ab54`。使用仓库 `scripts/verify-runtime-profile-authenticated-readonly.py --base-url http://127.0.0.1:5173`，Playwright Chromium 真实 loopback 管理员登录并打开 RuntimeProfile 管理页面。
- 结果 `PASS`：profile 列表 2 行、ACTIVE 1 行；`/api/v1/runtime-profiles` 与 `/api/v1/sources` 两个只读 API 均 HTTP 2xx、`code=OK`、data 为数组；API 请求 5，页面错误 0；logout 成功。未写入、未测试连接、未修改/激活/禁用/切换 profile，也未直接访问数据库。
- 凭据/token/响应体未输出或持久化；未触碰 MySQL 3306。

证据只覆盖当前 RunId 的管理员只读页面/API，不证明 profile 测试/激活/切换；相关真实 `/test` 另见 `PHASE4-RUNTIMEPROFILE-HS2-REAL-API-TEST-20261010.md`。阶段4和项目总体验收继续开放。动态文档备份后缀：`bak-20261010-runtime-profile-readonly-current-run`。