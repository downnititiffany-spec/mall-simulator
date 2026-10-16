# 阶段 6：AI 快照作用域查询异常脱敏（2026-10-09）

## 变更

`AiScopeResolver` 查询快照作用域发生运行时数据访问异常时，保留稳定错误码 `METRIC_READ_SOURCE_MISSING` 和 fail-closed 行为；业务异常消息改为固定文本，不拼接驱动错误消息，也不附带原始异常 cause，避免接口/审计路径或后续异常日志回显 JDBC 细节。

## 验证

```text
mvn -o -f analytics-server/pom.xml -pl ai-decision -am -Dtest=AiScopeResolverDiagnosticRedactionTest,AiSqlSecurityTest,TextToSqlAuditTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`AiScopeResolverDiagnosticRedactionTest` 1/1、`AiSqlSecurityTest` 38/38、`TextToSqlAuditTest` 7/7，共 **46/46 PASS**，0 failure/error/skipped；五模块 reactor `BUILD SUCCESS`。

## 边界

这是 Mockito/离线证据，不含 MySQL、平台 HTTP、真实 Provider、认证或浏览器验证；未连接数据库或访问 3306。D-039 与阶段 6 真实 Provider 门槛仍未完成，项目验收状态不变。
