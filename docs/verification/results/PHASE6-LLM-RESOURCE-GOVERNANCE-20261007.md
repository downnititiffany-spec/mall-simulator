# 阶段 6 OpenAI 兼容 Provider 资源治理（本地 stub 验证）

## 实现

按设计 V3.0 §13.4，为真实 HTTP Provider 增加可由受控环境覆盖的本地调用边界：

- 按当前请求 JSON 结构序列化后的完整 HTTP payload 默认不超过 256 KiB（含 model、消息、温度、response_format、max_tokens 与 JSON 转义开销），超限在网络调用前拒绝。
- 请求发送 `max_tokens`（默认 1024），并拒绝 usage 报告的 completion tokens 超过配置上限的响应。
- 单 JVM 并发上限默认 2，超限立即以稳定错误类拒绝，不排队等待。
- UTC 日预算默认 1,000,000 个保守预留单位；每次请求按“完整序列化 payload 字节数 + max_tokens”预留，预算耗尽时在发 HTTP 前拒绝。无论请求成功、失败或超时，预留都不释放，避免结果不明时同日重复消耗额度。
- 连续网络/超时/格式失败默认累计 5 次后熔断 60 秒；AUTH 和 Provider 明确返回的 RATE_LIMITED 不计入熔断失败。
- HTTP 请求最多发送一次；对于 5xx/超时等结果可能不确定的调用不自动重试，避免重复计费或重复执行。
- 新配置位于 `application.yml` 的 `llm` 区段，对应环境变量 `LLM_MAX_REQUEST_BYTES`、`LLM_MAX_OUTPUT_TOKENS`、`LLM_MAX_CONCURRENT_CALLS`、`LLM_MAX_DAILY_TOKEN_BUDGET`、`LLM_CIRCUIT_FAILURE_THRESHOLD`、`LLM_CIRCUIT_OPEN_MS`。

## 验证

本地 HTTP stub/Mockito 定向 Maven reactor：

```powershell
mvn -o -f analytics-server/pom.xml -pl ai-decision -am '-Dtest=OpenAiCompatLlmProviderTest,ExplanationProviderProvenanceTest,ExplanationEvidenceTest,TextToSqlAuditTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- `OpenAiCompatLlmProviderTest` 25/25、`ExplanationProviderProvenanceTest` 4/4、`ExplanationEvidenceTest` 11/11、`TextToSqlAuditTest` 7/7，合计 **47/47 PASS**，0 failure/error/skipped，五模块 reactor BUILD SUCCESS。
- 覆盖超大请求零 HTTP、max_tokens 字段、超限响应拒收、并发上限、每日预留额度耗尽后不再发送 HTTP、连续失败熔断、503 单次 HTTP、不自动重试，以及既有 provider provenance、模板回退和审计行为。
- 所有联网仅指向进程内/本机 loopback stub；没有访问外部 LLM endpoint、使用真实 key 或发送业务数据。

## 未覆盖与阶段边界

- 单次/每日**金额预算**尚未实现：价格因模型/供应商而异，当前 D-039 真实 Provider 与账单口径未具备，不能拿 token 估算冒充金额上限。每日 token 是按字节+最大输出额度保守预留，并非供应商真实 tokenizer usage；跨进程共享限额及分布式熔断也未实现，当前并发/预算/熔断仅为单 JVM。
- 没有配置自动重试次数；设计要求防止失控重试，本实现按 0 自动重试执行，待真实供应商幂等/计费协议证明安全后才可调整。
- 真实 Provider 的正样本、拒绝样本、超时、非法 JSON 和实际 provider/model/耗时仍需 D-039 条件齐备后单独验收。阶段 6 继续未完成，整项目不构成最终验收。

未连接数据库；3306 零接触。动态文档编辑前备份：

- `docs/PROJECT_STATUS.md.bak-20261007-ai-provider-resource-governance`
- `docs/PROJECT_COMPLETION_CHECKLIST.md.bak-20261007-ai-provider-resource-governance`
- `docs/verification/CURRENT_BATCH.md.bak-20261007-ai-provider-resource-governance`
