# 阶段6 AI 调用审计写入失败定向回归（2026-10-12）

## 范围

复核当前工作树中解释生成与 Text-to-SQL 的 AI 调用审计分支：Provider 成功/失败、审计 Mapper 写入成功/失败、格式拒绝和稳定错误分类。目标是确认审计失败时不重放 Provider、不将 Mapper 原始异常透出，并按模板结果回退或保留安全问数历史。

## 验证

在 `analytics-server` 下运行：

```text
mvn -o -pl ai-decision -am -Dtest=ExplanationProviderProvenanceTest,ExplanationEvidenceTest,TextToSqlAuditTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- Maven Reactor：`platform-common`、`connection-ingestion`、`metric-analysis`、`ai-decision` 均 SUCCESS。
- 定向测试合计 40/40，0 failure、0 error、0 skipped；BUILD SUCCESS。
- 覆盖 Provider provenance、解释审计成功/失败后模板回退、Text-to-SQL 调用审计与问数历史写入失败分类。

## 证据边界

本结果仅是本地 Mockito/JUnit 回归。未连接数据库、未启动/重启平台、未调用真实 LLM Provider，也不证明审计记录真实持久化、隔离权限或端到端链路。真实 Provider 仍受 D-039 外部条件约束；阶段6及项目总验收保持开放。3306 未访问。
