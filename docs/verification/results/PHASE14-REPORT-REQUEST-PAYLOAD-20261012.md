# 阶段14 报告创建请求字段前端守卫（2026-10-12）

## 变更

- 将 `Reports.vue` 的请求体组装提取到纯函数 `web/src/utils/reportRequest.js`。
- 为 `DECISION_EFFECT` 锁定只发送 `decisionId`（及模板元数据）；不携带客户端 snapshotId 或日期字段。
- 覆盖 OPS_DAILY 的快照/业务日期绑定和 OPS_WEEKLY 的显式窗口字段。

## 验证

- `web`: `npm test`，450/450 PASS，0 failed、0 skipped。
- `web`: `npm run build`，Vite production build 成功（694 modules）。
- 未调用服务、数据库、管理员认证或浏览器；未访问 MySQL 3306。

## 边界

此项仅证明前端纯函数 payload 契约及 Web 自动化/构建，不证明真实 API、数据库或浏览器工作流。阶段14和项目总验收仍开放。
