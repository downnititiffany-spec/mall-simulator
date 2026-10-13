# PHASE 6 Text-to-SQL Explanation Number Guard — 2026-10-06

## 发现与修复

- V3.0 阶段 6 要求解释/建议只能引用证据中的数字。证据包解释主路径已有数值守卫，但 `/api/v1/ai/queries` 使用的 Text-to-SQL 结果解释路径此前直接采用模型 JSON，没有检查其中的数字。
- 该路径现对模型生成的自然语言与数值字段执行数字白名单校验，白名单来自本次实际执行结果行；证据引用和指标/规则代码字段不作为业务数字检查。发现未在结果中出现的数字时，调用审计记为 `REJECTED` / `QUERY_RESULT_NUMBER_GUARD`，不采用模型解释，改回规则摘要。
- 合法引用查询结果数字的模型输出保持可采用。

## 验证

- RED：新增的编造数字用例在修复前失败，显示 `providerUsed=mock`、模型 JSON 被接受。
- 定向 `ExplanationEvidenceTest` + `ExplanationProviderProvenanceTest`：15/15 PASS。
- 完整 `ai-decision` 上游 reactor：platform-common 4、connection-ingestion 389（2 skipped）、metric-analysis 141、ai-decision 175；合计 709 项，0 failure、0 error、2 skipped，BUILD SUCCESS。使用 JDK 17、Maven 离线依赖。

## 边界

测试使用本地 mock/stub，没有调用真实 LLM、数据库或商城。此修复加强离线解释安全，不满足阶段 6 的真实 Provider 门槛；D-039 条件未齐，阶段 6 仍未完成。
