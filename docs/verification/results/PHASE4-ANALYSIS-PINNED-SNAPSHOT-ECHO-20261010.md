# 阶段4 分析页固定快照回显一致性（2026-10-10）

## 变更

`useAnalysis` 在 `pinSnapshot=true` 时将所选 `snapshotId` 发送给数据接口。此前成功响应仅经通用信封解包，未核对响应回显的 `snapshotId`，因此接口若返回另一快照或缺少身份字段，页面仍可能把数据标记为成功。现要求响应快照号与本次请求的固定快照号完全一致；不匹配或缺失时进入错误态，且不写入错误数据。

## 验证

- `node --test tests/useAnalysisRequestRace.test.js tests/analysisDataResponse.test.js`：8/8 PASS，覆盖错配、缺失、匹配及既有竞态/取消路径。
- Web 全量 `npm test`：440/440 PASS，0 fail/cancelled/skipped。
- Vite 5.4.21 production build：690 modules 成功。
- 全量测试输出出现一次 `WebSocket server error: Port is already in use`，但测试进程退出码为 0、最终汇总 440/440；未对未归属端口采取操作。

## 证据边界

以上是本地前端单测与打包证据，不含真实认证 API、数据库、真实快照数据或端到端浏览器验证。真实管理员登录本轮仍未成功；阶段4及总体验收保持开放，3306 未访问。

动态文档编辑前备份后缀：`bak-20261010-analysis-pin-snapshot-echo`。
