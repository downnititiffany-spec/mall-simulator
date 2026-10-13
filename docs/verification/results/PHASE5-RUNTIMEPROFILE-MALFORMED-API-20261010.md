# 阶段5 RuntimeProfile 列表响应格式错误状态

日期：2026-10-10

## 实现

- 新增 `web/src/utils/runtimeProfiles.js` 的 `requireRuntimeProfileList`。成功响应的 `data` 必须是数组；对象、null 等错误结构抛出不含响应正文的固定 `TypeError`，不再静默降级成空数组。
- RuntimeProfile 页在开始刷新时清空上一轮环境/来源行，避免请求失败时把过期数据当作当前列表。
- 运行环境列表响应结构错误显示错误状态和“列表当前不可用”提示，不再同时显示“尚无运行环境”。来源列表结构错误保留已成功读取的环境行，但显示来源不可用提示。
- 有效空数组继续显示真实空状态。

## 验证

- `node --test tests/runtimeProfiles.test.js`：4/4 PASS。
- `npm test`：425/425 PASS，0 fail/cancelled/skipped。日志包含既存 `WebSocket server error: Port is already in use` 文本，测试进程退出码 0 且完整汇总通过；本轮未处置该未归属端口。
- `npm run build -- --outDir <TEMP>`：Vite 5.4.21，686 modules，构建成功，2 个顶层构建文件；没有覆盖 `web/dist`。
- `git diff --check` 纳入最终工作区检查，结果见该轮 shell 汇总。

## 边界

这轮是纯函数/静态页面契约测试与构建，不是受保护页面真实浏览器、管理员 API 或数据库验收。没有认证/写请求、没有触碰 3306。阶段4/5与总验收仍开放。
