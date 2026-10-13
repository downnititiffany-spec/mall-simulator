# 阶段14 D-060 决策效果报告实现切片（2026-10-10）

## 实现范围

- Reports 页面允许选择已完成且状态为 `EFFECTIVE/PARTIAL/INEFFECTIVE/INSUFFICIENT_DATA` 的决策；按现有决策分页接口加载，支持逐页继续查找历史任务。报告创建只发送 `decisionId`，不允许客户端传入评价值、来源、口径或状态。
- 报告服务读取该任务最新的已保存 `DecisionEvaluation`，校验任务终态与评价结果一致，并核对 sourceId、runtimeProfileId、指标 definitionVersion、窗口长度和实际样本数。已计算评价是唯一的效果结论来源；本服务不触发 `/evaluate`、不重算 improvementRate/分级，也不改变决策状态。
- 对完整评价使用实际窗口末快照作为报告锚点；`INSUFFICIENT_DATA` 使用基线末快照锚点。证据包须精确返回该快照、同源且同指标口径。制品保存前后窗口、保存值/分级、样本数、评价版本与两侧快照引用。
- 文案明确前后指标变化不代表行动因果。无合法快照、旧评价缺来源/profile/口径、任务/评价状态不同或作用域不匹配时 fail-closed。详情 API 返回完整制品，便于页面直接查看模板内容。

## 验证与限制

- `mvn -o -f analytics-server/pom.xml -pl ai-decision,platform-app -am -DskipTests compile`：7/7 Reactor modules `BUILD SUCCESS`。
- Vite production build 成功，输出到唯一 TEMP 路径；`git diff --check` 通过。**未运行测试**，本次也没有真实 API/数据库/浏览器验收。
- 未应用 V37/V38，未部署，未连接 3306。不能据此声称真实决策评价/报告制品回读已通过。
- 六种模板均已具有代码/UI实现切片；阶段14的迁移、授权、报告端到端、完整模板口径与总控签收仍未完成。D-039真实 LLM Provider保持未完成。
