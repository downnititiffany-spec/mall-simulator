# 阶段5 当前源码 Web 全量验证（2026-10-10）

## 结果

在当前 Web 工作树执行 `npm test`：Node 测试 **424/424 PASS**，0 fail、0 cancelled、0 skipped，约 3.93 秒。测试控制台出现一次 `WebSocket server error: Port is already in use` 与 Vite 依赖重优化提示，但进程退出码为 0，完整测试汇总为 424 PASS。

生产构建执行 `npm run build -- --outDir <临时目录>`，避免覆盖仓库 `web/dist`：Vite 5.4.21 转换 **685 modules**，构建成功，产出 32 个文件。临时输出位于系统 TEMP 中本轮新建的 `graduationproject-web-build-c7e18afc9ae14f8db57c1df040e3094b`。

## 证据边界

该结果覆盖当前源码的 Node 前端测试和 production build；不证明登录后真实 API、数据库、受保护页面、管理员操作或完整用户工作流。本轮没有改变业务源码、未访问数据库或 3306；隔离栈仍为 `cloud_261010_005249_1c73ec`，构建输出没有写入 `web/dist`。阶段5与总体验收按既有清单继续保持开放。