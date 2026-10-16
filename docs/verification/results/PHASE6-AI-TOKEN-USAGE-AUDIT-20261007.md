# 阶段 6 AI 调用 token 用量审计

日期：2026-10-07（Asia/Shanghai）。依据：V3.0 设计 §13.1/13.4、ai_call_log 用量与真实调用来源要求。

## 问题与实现

既有 Provider 返回 token 用量，但 TextToSqlService 和 ExplanationService 写调用审计时没有设置已有 input_tokens/output_tokens 字段；OpenAI 兼容响应缺少 usage 时还会通过 asInt(0) 虚构为零。

- AiResponse 使用可空 Integer：未知为 null，显式报告 0 保留。HTTP Provider 只接受非负且可放入 INT 的整数计数；负数、小数、溢出、字符串、布尔值和非对象 usage 失败关闭为 FORMAT。缺失/null usage 或某个计数允许为空。
- AiCallUsage 统一把已报告用量传入 AiCallLog。SQL 生成和受控修复分别记录自己的用量，不累加混用；两种解释的成功、数值/长度拒绝和内容解析失败均保留已知计数。
- LlmException 增加可选用量元数据；输出 token 预算拒绝发生在拿到响应之后，故异常携带已报告计数，调用者记 FAILED 时仍保留。超时、响应体超限等没有可信响应的失败保持未知；原双参数异常构造器保留。
- 证据摘要拒绝日志采用响应提供的模型标识（缺失时回到配置模型），同成功路径一致。模板回退和调用失败状态保持。
- V3__platform_ai_audit.sql 已有可空 INT 列且无零默认值；没有修改历史迁移或增加 schema。AiResponse 是内部接口，构造签名发生变化，部署前需统一重建模块。

## 验证

Windows JDK17.0.12 / Maven3.9.14，离线命令：

    mvn -o -f analytics-server/pom.xml -pl ai-decision -am "-Dtest=OpenAiCompatLlmProviderTest,PlatformOutboundClientTimeoutGuardTest,ExplanationProviderProvenanceTest,ExplanationEvidenceTest,TextToSqlAuditTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

| 测试类 | 用例 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| OpenAiCompatLlmProviderTest | 42 | 0 | 0 | 0 |
| PlatformOutboundClientTimeoutGuardTest | 4 | 0 | 0 | 0 |
| ExplanationProviderProvenanceTest | 9 | 0 | 0 | 0 |
| ExplanationEvidenceTest | 12 | 0 | 0 | 0 |
| TextToSqlAuditTest | 7 | 0 | 0 | 0 |
| 合计 | 74 | 0 | 0 | 0 |

五模块 reactor BUILD SUCCESS。覆盖未知/部分/显式零用量，非法计数的两种字段，输出预算异常元数据，生成/修复分别为 10/10 与 21/7，解释成功/数值拒绝/长度拒绝/JSON 解析失败用量保留，以及无响应时审计计数为空。HTTP仅为本机stub；审计通过Mapper捕获断言。

另外执行：

    mvn -o -f analytics-server/pom.xml -pl platform-app -am -DskipTests test-compile

七模块 BUILD SUCCESS，含 platform-app 28 个主源码及52个测试源码重新编译。该命令不执行测试，不能扩展为平台HTTP或完整后端回归通过。

## 范围与剩余门槛

未连接真实 Provider、MySQL 或3306，未部署或启停项目服务；没有真实数据库审计回读。计数为供应商报告值，未运行真实tokenizer/计费核对，不能把未知用量当免费调用。金额预算、请求指纹、跨进程资源协调和D-039真实模型门槛仍未完成；阶段6/整体验收不关闭。

动态文档编辑前备份后缀：bak-20261007-ai-token-usage-audit。历史批次与冻结文稿保留只读。
