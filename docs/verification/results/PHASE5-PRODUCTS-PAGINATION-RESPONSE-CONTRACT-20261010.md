# 阶段5 商品服务端分页响应契约（2026-10-10）

## 变更

`Products.vue` 调用服务端分页 API 后，先通过 `requireProductsPageResponse` 校验视图模型。校验覆盖 `hot` 与 `conversion` 数组、正整数 `topN/page/size`、非负安全整数 `total`、布尔 `hasMore`，以及 `topN === size`、当前页行数不超过 `size`、`hasMore` 与 `page * size < total` 一致。畸形响应显示错误而不被通用分析封套回退成空图表。

仅当 `snapshotId` 为空且 warnings 含 `NO_ACTIVE_SNAPSHOT` 或 `UNKNOWN_SNAPSHOT` 时接受空 `data`，保留当前无可用快照的明确空态。共享 `useAnalysis` 封套行为未变。

## 验证

- 定向：`node --test tests/productsPageResponse.test.js tests/productServerPagination.test.js`，10/10 PASS。
- 全量：`npm test`，434/434 PASS，0 fail/cancelled/skipped。输出曾提示 WebSocket server port in use，但命令退出码为 0；未对未归属端口采取操作。
- 构建：`npm run build -- --outDir $env:TEMP\graduationproject-products-pagination-build-20261010`，Vite 5.4.21，转换 689 modules，成功。输出写入独立 TEMP 目录，没有覆盖 `web/dist`。

## 证据边界

这是本地前端单测与生产构建证据，不证明真实认证 API、数据库分页、浏览器实服行为或业务数据正确性。阶段4/5与最终验收保持开放；未访问 MySQL 3306。

动态文档编辑前备份后缀：`bak-20261010-products-pagination-response-contract`。
