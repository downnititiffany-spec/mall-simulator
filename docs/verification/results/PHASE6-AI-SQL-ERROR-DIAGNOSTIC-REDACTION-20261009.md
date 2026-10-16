# 阶段 6：AI SQL 诊断错误脱敏（2026-10-09）

## 变更

- `QueryCostGuard` 对普通 EXPLAIN 失败保留 `SQL_COST_TOO_HIGH` 和 fail-closed，但错误原因不再拼接 JDBC 消息或异常 cause；无效 EXPLAIN 行不再序列化整行内容。
- `TextToSqlService` 对 SQL 执行异常返回稳定 `SQL_EXECUTION_FAILED` 与固定说明；其它非 Provider 异常按阶段归入稳定错误码，底层文本不再进入问数结果或 `ai_query_history.errors`。超时日志不再记录驱动异常消息。
- EXPLAIN 成本门、只读 SQL 执行、超时与安全拒绝的业务状态和阻断逻辑保持不变。

## 验证

定向命令：

```text
mvn -o -f analytics-server/pom.xml -pl ai-decision -am -Dtest=QueryCostGuardDiagnosticRedactionTest,TextToSqlDiagnosticRedactionTest,AiScopeResolverDiagnosticRedactionTest,AiSqlSecurityTest,TextToSqlAuditTest -Dsurefire.failIfNoSpecifiedTests=false test
```

定向 **49/49 PASS**，0 failure/error/skipped。完整依赖 reactor 命令 `mvn -o -f analytics-server/pom.xml -pl ai-decision -am test`：fresh Surefire XML 汇总 **898 项，0 failure/error、2 skipped，BUILD SUCCESS**；模块计数为 platform-common 121、connection-ingestion 412（2 skipped）、metric-analysis 152、ai-decision 213。

## 边界

这是离线测试/Mockito，不含真实 MySQL、HTTP、认证、浏览器或 Provider 验收；未连接数据库或访问 3306。D-039 与阶段 6 真实 Provider 门槛仍未满足，整体项目验收不变。
