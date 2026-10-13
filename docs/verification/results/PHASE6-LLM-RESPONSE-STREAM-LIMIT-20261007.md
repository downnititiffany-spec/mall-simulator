# 阶段 6 Provider 响应流字节上限

日期：2026-10-07（Asia/Shanghai）。依据：设计 V3.0 §13.4 的 Provider 资源治理与阶段 6 失败回退要求。

## 问题与实现

既有 Provider 限制请求字节、发送 max_tokens 并核对上游 usage，但 JSON 转换和 HTTP 错误处理仍可完整读取任意大的响应。上游不报告 usage 时，输出 token 检查也不能提供本地内存边界。

OpenAiCompatLlmProvider 现在在 RestClient 响应进入 JSON 转换器或错误响应缓冲之前包装输入流。默认最多消费 1,048,576 字节；探测超限只额外读取一个字节，随后抛出固定异常，转换为 RESPONSE_TOO_LARGE。关闭响应时先关闭读取流，避免为连接复用而继续排空被拒绝的响应。该保护不依赖 Content-Length，适用于 chunked 响应。鉴权/限流/服务端错误仍保留 AUTH/RATE_LIMITED/NETWORK 分类，错误正文不进入调用结果或审计日志。

新增受控配置 llm.max-response-bytes，对应 LLM_MAX_RESPONSE_BYTES；非正值回落到 1 MiB 默认上限。保留原构造器供现有调用者使用；Spring 唯一注入构造器接受新配置。原并发信号量在拒绝后仍释放，失败参与既有熔断，不新增 HTTP 重试。ExplanationService 遇到新错误沿既有模板回退并记录 FAILED/RESPONSE_TOO_LARGE。

## 验证

Windows JDK 17.0.12 / Maven 3.9.14，离线执行：

    mvn -o -f analytics-server/pom.xml -pl ai-decision -am "-Dtest=OpenAiCompatLlmProviderTest,PlatformOutboundClientTimeoutGuardTest,ExplanationProviderProvenanceTest,ExplanationEvidenceTest,TextToSqlAuditTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

| 测试类 | 用例 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| OpenAiCompatLlmProviderTest | 32 | 0 | 0 | 0 |
| PlatformOutboundClientTimeoutGuardTest | 4 | 0 | 0 | 0 |
| ExplanationProviderProvenanceTest | 5 | 0 | 0 | 0 |
| ExplanationEvidenceTest | 11 | 0 | 0 | 0 |
| TextToSqlAuditTest | 7 | 0 | 0 | 0 |
| 合计 | 59 | 0 | 0 | 0 |

五模块 reactor BUILD SUCCESS。新增证据覆盖 UTF-8 完整响应恰好达到字节上限时接受、超出一个字节拒绝、拒绝后并发许可释放、无 usage 且无 Content-Length 的超大 chunked 响应、非正配置回落、Spring 实际配置注入及超限后模板来源/失败审计。

流式负例将响应结束保持在测试 latch 之后：客户端在服务端未发送 EOF、10 秒读取超时尚未触发时，于 3 秒 Future 观察界限内返回 RESPONSE_TOO_LARGE；证明拒绝发生在读取阶段，且关闭响应不等待服务端排空。错误大正文另验证 401/429/503 分类和正文不泄露。所有 HTTP 仅访问本机测试服务。

## 范围与剩余门槛

字节上限约束客户端实际消费的 HTTP 响应体；它不计算供应商真实 token、不证明供应商遵守 max_tokens，也不限制账单费用。单次/每日金额预算、跨进程协调、真实 Provider 的正负样本及 D-039 的 endpoint/凭据/数据外发许可仍未闭合。没有调用外部模型、连接数据库或访问 3306；没有部署当前修改或启停项目服务。阶段 6 与项目整体仍未完成。

动态文档编辑前备份后缀：bak-20261007-llm-response-stream-limit。此前证据保留不改。
