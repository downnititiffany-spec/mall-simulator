# 阶段14 DECISION_EFFECT 候选排序稳定性（2026-10-10）

## 修改

- 决策分页按 ID 降序返回；评价详情并发读取完成次序不确定。报告页现并发收集评价匹配的任务，再按原分页顺序过滤，保持候选排序不受网络响应时序影响。

## 验证与范围

- Vite 5.4.21 production build 成功，694 modules，输出至 `%TEMP%/gp-report-decision-order-7172de6d5daf4fbc8d353c2d2abdcdf5`。
- 未运行测试或真实 API/DB/浏览器验收；阶段14与项目总验收保持开放。
- 未提交/推送。
