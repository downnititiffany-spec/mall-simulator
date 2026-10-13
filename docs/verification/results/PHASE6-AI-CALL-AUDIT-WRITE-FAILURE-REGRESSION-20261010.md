# 阶段6：AI调用审计写入失败回归补充（2026-10-10）

## 范围

对当前工作树的 `TextToSqlService` 与 `ExplanationService` 调用审计 Mapper 插入失败路径执行离线定向单测，覆盖 Provider 成功/失败/空响应、响应格式或数值拒绝，以及证据摘要和 QueryResult 解释入口。验证失败分类、模板/规则回退、历史记录安全说明、Provider 不重复调用和敏感异常正文不泄露。

## 命令与结果

```powershell
mvn -o -f analytics-server/pom.xml -pl ai-decision -am '-Dtest=TextToSqlAuditTest,ExplanationProviderProvenanceTest,ExplanationEvidenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：Maven Reactor 5 个项目均 `BUILD SUCCESS`；40 tests run，0 failures，0 errors，0 skipped。分项：`TextToSqlAuditTest` 10/10、`ExplanationProviderProvenanceTest` 18/18、`ExplanationEvidenceTest` 12/12。

## 证据边界

测试使用 Mockito 与本地单元测试，不访问真实数据库、运行服务或外部 LLM Provider；不证明审计数据在部署数据库中的持久性，也不关闭阶段6。D-039 的 Provider endpoint、受控凭据和数据外发许可仍需按总控要求落实。全项目验收继续开放。