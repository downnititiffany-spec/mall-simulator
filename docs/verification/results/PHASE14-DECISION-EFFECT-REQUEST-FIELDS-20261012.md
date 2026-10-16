# 阶段14 DECISION_EFFECT 请求字段 fail-closed 修复（2026-10-12）

## 问题与修复

D-060 的 DECISION_EFFECT 补充契约规定客户端只提交 `decisionId`，报告快照和时间窗口从已保存的决策评价派生。此前 `ReportInstanceService.create` 会忽略请求中的 `snapshotId`、`businessDate`、`fromDate` 或 `toDate`，调用方可能误以为覆盖值生效。

现在服务在查询决策任务、读取评价或构建证据之前拒绝这些字段，返回 `REPORT_DECISION_INPUT_UNSUPPORTED`。测试分别覆盖四个输入字段，并确认决策 Mapper、EvidenceService 和持久化 Mapper 均未调用。无决策报告的 `decisionId` 仍由原有守卫拒绝。

## 验证

执行：`mvn -o -f analytics-server/pom.xml -pl ai-decision -am '-Dtest=ReportInstanceServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

`ReportInstanceServiceTest` 16/16 PASS，0 failure、0 error、0 skipped；Maven 五项目 Reactor `BUILD SUCCESS`。

## 范围

仅离线服务单测；当前隔离 RunId 服务虽在监听，但管理员密码变量不在当前任务环境。本轮未调用真实 HTTP、浏览器或数据库；未写业务数据，未连接 MySQL 3306。阶段14及项目验收开放。

冻结的 V3.0 指导书/设计文档未修改。编辑 `PROJECT_STATUS.md` 前已备份为 `PROJECT_STATUS.md.bak-20261012-decision-report-input-contract`。
