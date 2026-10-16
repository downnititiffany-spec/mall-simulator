# 阶段14 DECISION_EFFECT 报告模板服务语义回归（2026-10-10）

- 新增用例验证决策效果报告从最新已保存 DecisionEvaluation 复用结果/值/窗口/样本数/口径及快照 refs，不重跑 AnalysisService，不声称因果关系。
- `INSUFFICIENT_DATA` 用 baselineSnapshotId 和 baselineWindowEnd 建证据；缺失 actualValue/actualSampleCount保持 null，不补造事实。
- task 与 evaluation 结果不一致时，在调用 EvidenceService 和 mapper.insert 前 fail-closed。
- 命令（analytics-server）：`mvn -o -pl platform-app -am '-Dtest=ReportInstanceServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。`ReportInstanceServiceTest` 13/13 PASS，0 failure/error/skipped；七模块 Maven Reactor BUILD SUCCESS。该轮编译重新编译 ai-decision 28个测试源和 platform-app 62个测试源。
- 范围限制：Mockito/JUnit 离线证据，不代表真实决策生命周期、数据库评价回读或 DECISION_EFFECT 实服API/浏览器流程。本 RunId 无合格已完成且评价的任务，未伪造审批/执行/评价。阶段14和项目总验收开放。
