# 阶段 6：AI 调用来源与请求指纹进入运维审计页（2026-10-08）

## 变更

- 运维中心的 AI 调用审计表现在显示 `sourceId`、`snapshotId` 和请求指纹预览。指纹列保留完整 SHA-256 于悬停提示；CSV 导出携带完整指纹与来源/快照字段。
- 表格映射不重算或补造身份：历史空值显示占位符；原始问题和 prompt 明文仍不进入审计页面或导出。
- 新增 Web 映射测试，覆盖历史缺值、来源/快照透传、短显与完整 CSV 字段列。

## 验证

- `web/npm run verify`：**400/400 PASS**，0 fail/cancelled/skipped；Vite production build 成功，转换 680 modules。
- 测试输出出现一次 Vite WebSocket `Port is already in use` 提示；所有测试通过，production build 成功。本次未据此终止或接管其他进程。
- 验证范围是当前工作树 Web 自动化测试与生产构建；未运行真实浏览器、后端 API、数据库回读或部署。

## 阶段边界

此 UI 接线不代表 V36 已应用或真实数据库审计回读通过，也不代表真实 LLM Provider 已验证。阶段 6、阶段 4 实服分页/Profile 切换和最终产品验收仍未关闭；未连接数据库或访问 3306。