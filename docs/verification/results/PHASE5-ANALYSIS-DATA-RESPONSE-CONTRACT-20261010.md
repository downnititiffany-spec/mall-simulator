# 阶段5 分析页响应数据结构校验（2026-10-10）

## 变更

新增 `web/src/utils/analysisDataResponse.js`，按页面声明必需的数组/对象字段，在分析页面调用共享 `readEnvelope` 之前校验 HTTP 成功响应。接入概览、销售、行为漏斗与活跃趋势、RFM/用户聚合以及 AI 助手的概览基线。损坏的成功响应进入既有错误态，页面不再把缺字段/错类型显示为真实空图表。

共享 `readEnvelope` 仍保持既有缺失字段宽松降级契约。仅对该页面明确的空快照协议放行空 `data`：`snapshotId` 为空且 warnings 包含 `NO_ACTIVE_SNAPSHOT` 或 `UNKNOWN_SNAPSHOT`。

## 验证

- 定向：`node --test tests/analysisDataResponse.test.js tests/envelope.test.js`，16/16 PASS。
- Web 全量：`npm test`，437/437 PASS，0 fail/cancelled/skipped。输出有 WebSocket server port-in-use 提示，进程 exit 0；未操作未归属端口。
- 生产构建：Vite 5.4.21，转换 690 modules，成功，输出至 `C:\Users\ASUS\AppData\Local\Temp\graduationproject-analysis-response-contract-20261010`，未覆盖 `web/dist`。
- Playwright/Chromium 本机浏览器验证：临时 Vite 服务在 127.0.0.1:5189 由测试包装器启动；每种场景的平台 API 全部由脚本拦截。畸形 HTTP 200 销售响应显示 3 个图表错误态且无空态；合法快照空数组显示正常空态且无错误态。共 6 次 mock API 请求，意外路径 0，页面异常 0。包装器退出后只读复核发现其遗留 Vite 子进程；核对 PID 34932 命令行为本仓库 web 下本次指定的 Vite 且仅监听 5189 后，停止该 task-owned 进程并确认端口关闭。

## 证据边界

浏览器使用合成分析员身份和全 mock API，不是真实平台登录、接口、数据库或业务快照验证。阶段4/5和最终验收保持开放；未连接 MySQL 3306。

动态文档编辑前备份后缀：`bak-20261010-analysis-data-response-contract`。
