# 阶段14 DECISION_EFFECT 决策分页契约与成功提示修正（2026-10-10）

## 修改

- Reports 页的 DECISION_EFFECT 决策候选读取现复用决策中心的 `requirePageResponse`；分页 envelope 无效时进入读取错误提示，不再把缺失/错误 `items` 静默当成“没有符合条件的决策”。
- 决策效果报告成功提示显示所绑定的 decisionId；普通快照模板仍显示绑定的 snapshotId，避免向用户展示 `snapshotId=undefined`。

## 验证与范围

- Web Vite 5.4.21 production build 成功，694 modules，输出至 `%TEMP%/gp-report-decision-response-fe6e0e2b303641b39d42bde9f1ac81d7`。
- 按当前会话要求未运行测试；未调用真实 API、数据库、浏览器或部署服务。改动为前端代码切片，不构成阶段14真实链路验收。
- 阶段14与项目整体验收保持开放。当前分支仍为 `codex/cloud-v3-development`，未提交/推送。
