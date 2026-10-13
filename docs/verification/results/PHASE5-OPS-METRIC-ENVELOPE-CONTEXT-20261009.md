# 阶段 5 运维页快照指标上下文信封（2026-10-09）

## 变更

- 运维页所选快照指标区改用既有 `/dashboards/overview?snapshotId=...` 统一分析信封；不传快照时仍保持零请求和显式空态，不回退到 ACTIVE。
- 指标表继续读取信封 `data.metrics`。上下文展示快照、业务来源 ID、发布方、口径版本、业务时间、数据更新时间和质量状态；保留 `/metrics/overview` 裸数组路由与前端兼容方法。
- 新增 `web/tests/opsMetricSnapshotContext.test.js` 固定前端接口选择及字段展示。

## 验证

- 新增页面契约测试及 context/useAnalysis 定向回归通过。
- `web/npm run verify`：**423/423 PASS，0 fail/cancelled/skipped**；其中包括新测试。
- `web/npm run build`：Vite 5.4.21，684 modules transformed，production build 成功。
- 全部为本地 Node 测试和构建；未运行浏览器、真实 API、数据库或当前隔离服务验收。此项不关闭阶段5或整体验收。
