# 阶段 4/6：Text-to-SQL EXPLAIN 统一查询超时补齐

日期：2026-10-07

## 发现与修改

平台的 `metricReadJdbcTemplate` 已配置统一只读查询超时，但 `QueryCostGuard` 为执行 `EXPLAIN` 另建 `JdbcTemplate`，导致 SQL 成本检查绕过该时限。成本查询可能因此等待超过指导书统一超时。

`QueryCostGuard` 现在从 `SqlExecutor` 取得 `QueryTimeoutPolicy` 解析值并设置到 EXPLAIN 专用模板。仍使用相同 `metricReadDataSource`，未改变只读数据源、成本阈值或 fail-closed 行为。

## 验证

- 命令：`mvn -o -f analytics-server/pom.xml -pl ai-decision -am "-Dtest=SqlExecutorTimeoutConfigurationTest,AiQueryTimeoutMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- JDK 17、离线五模块 reactor：`SqlExecutorTimeoutConfigurationTest` 4/4，`AiQueryTimeoutMappingTest` 3/3；共 **7/7 PASS**，0 failure/error/skipped，`BUILD SUCCESS`。
- 覆盖统一默认 30 秒、自定义 9 秒、`SqlExecutor` 查询/EXPLAIN statement 超时配置，以及成本守卫模板读取同一设置。
- 首次执行新测试时，两个测试方法未声明 fixture 构建抛出的受检异常，`testCompile` 失败；已修正签名并以相同命令复跑通过。

## 边界

未部署、未访问真实 MySQL 或外部 Provider。该验证不证明实际 MySQL JDBC 驱动在给定超时秒数精确中断 EXPLAIN 查询。阶段 4/6 和产品验收不因此关闭。

动态文档编辑前备份后缀：`bak-20261007-explain-timeout`。
