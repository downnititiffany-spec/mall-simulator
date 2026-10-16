# 阶段5 裸数组列表响应契约校验（2026-10-10）

## 发现

AI 历史、数据来源向导来源列表、Ops 最近快照上下文和质量结果都通过返回裸数组的 API 读取。原实现将成功响应中的 `null`、对象或其他非数组值转换为 `[]`，页面会把响应结构异常误显示成无历史/无来源/无快照/无质量结果。

## 修复

新增 `web/src/utils/arrayResponse.js` 的 `requireArrayResponse`。上述接口现在接受真实空数组，并对非数组响应抛出固定格式错误，分别进入既有错误提示/分析错误态。Ops 的分页响应继续使用 `requirePageResponse`；分析统一信封中缺失字段降级为空数组的既有契约未改。

## 验证

- 定向 `node --test tests/arrayResponse.test.js tests/aiHistoryConcurrency.test.js tests/opsPipelinePagination.test.js tests/sourceWizard.test.js tests/runtimeProfiles.test.js`：40/40 PASS。
- Web 全量 `npm test`：430/430 PASS，0 fail/cancelled/skipped。
- Vite 5.4.21 production build：688 modules 成功，输出使用已有独立目录 `web/tmp-build-page-response`，未覆盖 `web/dist`。
- 全量测试日志包含一次 WebSocket port-in-use，但测试汇总和退出码均通过；未处理未归属端口。

## 证据边界

本轮仅为离线前端测试和构建；没有验证真实认证/API/DB/浏览器用户路径，也未触碰 MySQL 3306。阶段5和总体验收继续开放。
