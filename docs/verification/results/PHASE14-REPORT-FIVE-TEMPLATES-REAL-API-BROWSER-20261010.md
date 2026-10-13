# 阶段14 五种可用报告模板真实 API 与浏览器复核（2026-10-10）

- RunId：`cloud_261010_061315_23e7b3`；ACTIVE 快照 `S20261010_1`，sourceId=1，definitionVersion=v2。报告均为同一快照的隔离测试产物，不请求 AI 叙述。
- 已有 OPS_DAILY 报告 ID 1：上轮真实 API 创建及本人分页/详情/artifact 9/9 检查通过；本轮和其余模板一起做页面验证。
- 本轮通过正式 `/api/v1/reports` 各创建一次：OPS_WEEKLY ID 2（2026-10-04～2026-10-10）、ORDER_ANALYSIS ID 3、PRODUCT_ANOMALY ID 4、USER_OPERATIONS ID 5。每条随后经本人详情、artifact、分页 API 核对 READY/owner/checksum/template/snapshot/source/definition/templateContent，共10/10 PASS；没有重试POST。
- 更新 `scripts/verify-reports-real-browser.py` 支持多报告 ID/模板清单。真实 Chromium 管理员登录页面后验证五条报告列表及快照，逐条打开详情和完整 JSON artifact，核对模板与快照，最后退出并清除 token：28/28 PASS，15个API响应均成功，page errors=0，浏览器检查无写操作。
- 首轮多报告浏览器尝试出现4个详情标题断言失败。根因是验证器在 Vue 异步详情请求结束前读取旧面板；不是页面服务数据错误。增加对目标报告标题的等待后重跑，28/28 PASS。
- 范围限制：仅证明五种模板（不含 DECISION_EFFECT）的真实本地 API/浏览器流程与当前快照；没有实服双用户 owner 隔离、PDF/文件导出、真实 LLM 叙述、生产部署验收。决策效果模板需要已完成并评价的决策，本 RunId 无合格记录，本轮不伪造/不执行审批生命周期。通过正式应用 API 写入五条报告记录；未直连数据库，未连接3306。阶段14及总验收开放。
