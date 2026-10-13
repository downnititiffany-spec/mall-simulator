# 阶段 6 AI 调用审计字段与异常脱敏（离线定向验证）

日期：2026-10-06  
状态：代码修复与定向回归通过；真实 LLM Provider 仍未验证，阶段 6 不关闭。

## 问题与修复

- `ai_call_log` 已有 `provider`、`model`、`prompt_version`、token、耗时、状态和错误字段，但调用链此前将模型名写入 provider，并将 model 留空；Text-to-SQL 调用失败也没有独立的模型调用失败记录。
- OpenAI 兼容实现现在分别提供稳定 provider 标识 `openai-compat` 和配置模型名；调用成功时审计优先记录响应携带的实际模型名，失败时记录安全配置模型名。Mock 的 provider/model 也分列。
- Text-to-SQL 的 provider 成功/失败尝试写入 `ai_call_log`；模型失败只存稳定错误码。该类异常不会将供应商正文、endpoint 或 prompt 回显到查询历史或应用日志。其它非模型异常仍保留既有诊断路径。
- 解释调用的日志模型列也按响应模型或安全配置模型填充；解释最终来源继续由调用结果决定。没有更改提示词、SQL 安全策略、Provider 请求次数或外部调用配置。

## 验证

使用 JDK 17 执行离线 Maven 定向 reactor：

```text
mvn -o -f analytics-server/pom.xml -pl ai-decision -am \
  -Dtest=OpenAiCompatLlmProviderTest,ExplanationProviderProvenanceTest,ExplanationEvidenceTest,TextToSqlAuditTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：**41/41 PASS，0 failures、0 errors、0 skipped，BUILD SUCCESS**。覆盖 provider/model 分列、响应模型审计、超时错误码脱敏、解释来源、provider HTTP stub 的状态分类/超时与格式失败。`git diff --check` 无空白错误；仅报告既有 Web 与批次文档行尾提示。

## 证据边界

- HTTP stub/Mockito 属于离线测试，不是外部真实 Provider 调用，不证明 endpoint、凭据、费用预算、数据外发授权或真实服务模型响应。
- D-039 条件仍未满足；本次未访问外部模型、未读取或写入真实凭据、未连接数据库。不得将阶段 6 标为已完成。
- 测试输出中曾观察到模拟异常的原始消息；修复后复跑只记录 `stage=GENERATE` 与稳定码 `TIMEOUT`。该测试正文是合成哨兵，不含真实密钥。
