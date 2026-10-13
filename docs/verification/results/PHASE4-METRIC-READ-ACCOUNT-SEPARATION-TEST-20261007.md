# 阶段 4 指标只读账号隔离装配回归

日期：2026-10-07  
范围：`PlatformDataSources.metricReadDataSource()` 的只读与发布账号隔离配置守卫。

## 验证命令

```powershell
mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=PlatformDataSourcesQueryTimeoutTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：七模块 reactor `BUILD SUCCESS`；`PlatformDataSourcesQueryTimeoutTest` 9/9 PASS，0 failure、0 error、0 skipped。

## 覆盖范围

- 指标只读 URL 启用后，发布账号或读取账号缺失/空白时拒绝装配。
- 发布与读取账号相同（忽略大小写和首尾空格）时拒绝装配。
- 账号不同可创建 Hikari 只读数据源；断言用户名/JDBC URL，并在测试结束关闭连接池。用例不调用 `getConnection()`。

## 证据边界

本报告只证明配置装配层的 fail-closed 规则。没有连接隔离 MySQL、执行 GRANT/权限探测或运行真实 HTTP 链，不能证明 `metric_read` 在数据库侧只有 SELECT 权限，也不能证明服务运行时实际使用了对应账号。阶段 4 保持部分完成。
