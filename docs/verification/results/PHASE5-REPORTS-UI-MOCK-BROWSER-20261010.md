# 阶段5 报告页面浏览器 Mock 流程（2026-10-10）

- 新增可重复脚本 `scripts/verify-reports-browser.py`。只允许 loopback 页面 URL；Chromium 所有 `/api/v1/**` 请求均由脚本拦截并返回合成响应，浏览器 localStorage 身份是 mock analyst。
- 流程覆盖六模板入口可见、OPS_WEEKLY 选择快照后展示 2026-10-04 至 2026-10-10 窗口、创建请求绑定该快照/窗口、报告列表及结构摘要显示；DECISION_EFFECT 仅在切换模板后懒加载决策候选，生成请求只发送 `decisionId`，不发送 `snapshotId`/`businessDate`。
- 执行 `python scripts/verify-reports-browser.py`：PASS，mock API 请求11次，创建请求2个，意外路径0，页面错误0。
- 完整 artifact 按钮和其浏览器呈现未纳入本次流程。该脚本不提供真实认证、真实 API/DB、权限隔离、迁移、指标数据或浏览器员工验收证据。阶段5与14及总验收仍开放；本轮未启动/停止服务，未连接3306。
