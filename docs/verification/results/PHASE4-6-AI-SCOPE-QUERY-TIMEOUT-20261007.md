# 阶段 4/6 AI 快照作用域查询统一超时

日期：2026-10-07

## 发现与修复

阶段 4 的 `metricReadJdbcTemplate` 由 `QueryTimeoutPolicy` 统一配置只读 SQL 超时。但 `AiScopeResolver` 此前只注入 `metricReadDataSource`，并自行 `new JdbcTemplate(dataSource)`；AI 问数在 SQL 执行前读取 ACTIVE/ARCHIVED 快照范围的查询因此没有继承超时配置，违反“分页、限流、超时统一”的阶段 4 要求。

现改为按限定名注入已有的 `metricReadJdbcTemplate`，AI 作用域解析、看板与 MetricStore 共用统一超时配置及 metric_read 数据源。平台回归通过最小 Spring 容器实际解析 `@Qualifier`，确认解析器收到同一模板实例且保留配置值 9 秒；原只读源缺失 fail-closed 与 SQL 权限测试继续覆盖。

## 验证

命令：

```text
mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=PlatformDataSourcesQueryTimeoutTest,AiSqlSecurityTest,QueryTimeoutOwnerDriftTest,SqlExecutorTimeoutConfigurationTest,AiQueryTimeoutMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

JDK 17 七模块 reactor：

- `AiSqlSecurityTest` 37/37 PASS
- `QueryTimeoutOwnerDriftTest` 2/2 PASS
- `SqlExecutorTimeoutConfigurationTest` 4/4 PASS
- `AiQueryTimeoutMappingTest` 3/3 PASS
- `PlatformDataSourcesQueryTimeoutTest` 6/6 PASS（含实际 Spring 容器限定名注入）
- 合计 **52/52 PASS**，0 failure/error/skipped，`BUILD SUCCESS`

## 证据边界

只证明配置装配及 mock/JDBC 测试路径。未连接真实 MySQL、未部署；未证明真实驱动在超时点中断查询。阶段 4 和阶段 6 仍有其它必做项，尤其真实 Provider 继续受 D-039 条件约束。未访问 3306。
