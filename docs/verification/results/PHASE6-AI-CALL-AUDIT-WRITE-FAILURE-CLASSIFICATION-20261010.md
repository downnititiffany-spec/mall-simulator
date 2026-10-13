# 阶段 6 Text-to-SQL Provider 调用审计写入故障分类修复（2026-10-10）

## 问题

在当前在途的 `TextToSqlService.completeAndAudit()` 中，Provider 调用和成功审计写入位于同一个 `try/catch`。Provider 已成功返回、但 `ai_call_log` 插入失败时，catch 会将数据库审计故障误认为模型调用失败，并再次尝试写一条 `FAILED` 记录；这会让状态/费用解释失真。Provider 本身失败且失败审计写入再次失败时，也可能把底层 mapper 异常泄漏到外层阶段分类。

## 修复

- Provider 执行异常和审计记录持久化异常现在走分离分支；不会因为成功结果的审计写入失败而伪造模型调用失败或重放模型请求。
- 成功/失败调用日志都无法持久化时，对外使用稳定 `AI_CALL_AUDIT_WRITE_FAILED`，日志不回显 mapper 异常；成功 Provider 调用的 token 用量保留在返回的稳定异常对象中。
- Provider 返回 `null` 时记录 `FAILED/FORMAT`；若该失败记录也无法写入，则返回稳定审计写入错误。
- `ai_query_history` 仍通过原有 finally 路径记录问数失败状态；没有新增表、迁移、接口或真实 Provider 调用。

## 验证与边界

- `mvn -o -f analytics-server/pom.xml -pl ai-decision -am -DskipTests compile`：`platform-common`、`connection-ingestion`、`metric-analysis`、`ai-decision` 均 `BUILD SUCCESS`；ai-decision 编译 41 个源码文件。
- `git diff --check` 针对此代码文件通过。
- 本轮未运行测试、未调用真实 Provider、未连接数据库/3306、未部署。Mapper 抛错分支仍需定向回归覆盖；这项代码修复不关闭阶段 6 或总验收。

修改文件：`analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/TextToSqlService.java`。本轮只在已有未提交改动上做窄修复，没有覆盖其它在途实现。

## 追加修正：审计故障与调用结果的区分

后续复核发现，单一错误码/message 会把 Provider 已知失败且其失败审计落库失败，也表述成“调用结果不确定”。已进一步拆分稳定错误码：`AI_CALL_AUDIT_WRITE_FAILED`（Provider 已返回，但成功审计行写失败，调用可能已计费）、`AI_CALL_FAILURE_AUDIT_WRITE_FAILED`（Provider 调用失败且失败审计行写失败）、`AI_RESPONSE_AUDIT_WRITE_FAILED`（Provider 返回空响应且失败审计行写失败）。外层历史错误各自使用无 mapper 原文的准确说明。修正后四模块离线 compile 再次 BUILD SUCCESS；仍未运行测试或调用 Provider。编辑前备份后缀 `bak-20261010-audit-outcome-error-code-clarification`。

## 补充：ExplanationService 摘要改写审计失败回退（2026-10-10）

- 同类故障也存在于 `ExplanationService.rewriteSummary()`：Provider 成功、拒绝或失败后的调用审计若写库失败，必须继续使用证据包模板摘要；不能把审计异常说成 Provider 故障，也不能把未审计的 AI 摘要返回给用户。
- 现将 Provider 异常、空响应、格式/长度拒绝、数值拒绝和成功审计分别处理。每种审计写入异常均转为稳定模板回退说明；mapper 原始异常不进入用户响应。成功日志落库后才接受模型改写。
- 离线 compile：`mvn -o -f analytics-server/pom.xml -pl ai-decision -am -DskipTests compile`，四个 Reactor 模块均 `BUILD SUCCESS`。本次增量输出为 `Nothing to compile - all classes are up to date`，表示当前源码/目标类时间戳无需重编。
- 未运行测试、未调用真实 Provider、未连接数据库/3306、未部署。审计 mapper 故障定向回归仍开放；阶段6与总验收未关闭。
- 修改前备份后缀：`bak-20261010-explanation-audit-write-fallback`。

涉及源码：`analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/ExplanationService.java`。

## 再补充：QueryResult 解释入口审计分支（2026-10-10）

- 复核发现 `explain(QueryResult, ...)` 也有相同的宽 `try/catch` 风险。现将成功/拒绝/失败审计写入分别捕获；审计写入失败时回退规则摘要，不将 mapper 异常误报为 Provider 故障。空响应明确记录 `FAILED/FORMAT`；失败审计写不进去时也返回受限规则摘要。
- 修复后的定向离线 compile 实际重编 ai-decision 41 个源码文件；`platform-common`、`connection-ingestion`、`metric-analysis`、`ai-decision` 均 `BUILD SUCCESS`。命令：`mvn -o -f analytics-server/pom.xml -pl ai-decision -am -DskipTests compile`。
- 未运行测试或调用真实 Provider，未连接数据库/3306，未部署。Mapper 故障分支仍需定向回归；阶段6和总验收开放。编辑前备份后缀：`bak-20261010-query-explanation-audit-branches`。
